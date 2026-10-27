// Production PTY backend for the OpenCode Android terminal.
//
// A native forkpty session whose child is exec()'d with caller-provided
// arguments and environment (typically the packaged PRoot loader running
// an Alpine guest shell). The master end is polled by the JVM reader;
// writes/resize/signal requests arrive here through JNI.
//
// Handles are small integers indexing a process-local registry guarded by
// a mutex. Every function validates its handle before touching the child.
// Argument and environment vectors are materialised before forkpty() so the
// child only calls async-signal-safe code between fork and exec.

#include <jni.h>
#include <poll.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct PtySession {
    pid_t child = -1;
    int master = -1;
    long exit_code = -1;  // meaningful once the child was reaped
    bool reaped = false;
};

std::mutex g_sessions_mutex;
std::map<jint, PtySession> g_sessions;
jint g_next_handle = 1;

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> stringList(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(fromJava(env, item));
        env->DeleteLocalRef(item);
    }
    return result;
}

jint registerSession(JNIEnv* env, pid_t child, int master) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const jint handle = g_next_handle++;
    if (g_next_handle < 0) {
        // handle space exhausted; refuse instead of wrapping
        close(master);
        close(master);
        g_next_handle = 1;
        return -1;
    }
    g_sessions.emplace(handle, PtySession{child, master, -1, false});
    return handle;
}

PtySession* lockedSession(JNIEnv* env, jint handle) {
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "PTY session handle is not open");
        return nullptr;
    }
    return &it->second;
}

// Reap the child if it has exited (WNOHANG). Returns true when reaped.
bool tryReap(JNIEnv* env, jint handle, PtySession* session) {
    if (session->reaped) return true;
    int status = 0;
    const pid_t result = waitpid(session->child, &status, WNOHANG | WUNTRACED);
    if (result == session->child) {
        if (WIFEXITED(status)) {
            session->exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            session->exit_code = 128 + WTERMSIG(status);
        }
        session->reaped = true;
        return true;
    }
    return (void)env, false;
}

bool reapBlocking(JNIEnv* env, jint handle, PtySession* session) {
    if (session->reaped) return true;
    int status = 0;
    pid_t result;
    do {
        result = waitpid(session->child, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result == session->child) {
        if (WIFEXITED(status)) session->exit_code = WEXITSTATUS(status);
        else if (WIFSIGNALED(status)) session->exit_code = 128 + WTERMSIG(status);
        else session->exit_code = -1;
        session->reaped = true;
        return true;
    }
    return (void)handle, false;
}

void terminateGroup(int child) {
    kill(-child, SIGTERM);
    for (int attempt = 0; attempt < 30; ++attempt) {  // ~300 ms
        int status = 0;
        const pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child || (result < 0 && errno != EINTR)) return;
        usleep(10 * 1000);
    }
    kill(-child, SIGKILL);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_open(
    JNIEnv* env, jobject, jstring workdir_value, jint columns, jint rows,
    jobjectArray arguments, jobjectArray environment_names,
    jobjectArray environment_values) {
    const std::string workdir = fromJava(env, workdir_value);
    if (workdir.empty() || columns <= 0 || rows <= 0) return -1;

    const std::vector<std::string> argv = stringList(env, arguments);
    if (argv.empty() || argv[0].empty()) return -1;

    const jsize nameCount = environment_names == nullptr
        ? 0
        : env->GetArrayLength(environment_names);
    std::vector<std::string> entries;
    entries.reserve(static_cast<size_t>(nameCount));
    for (jsize index = 0; index < nameCount; ++index) {
        jstring nameItem = static_cast<jstring>(
            env->GetObjectArrayElement(environment_names, index));
        jstring valueItem = static_cast<jstring>(
            env->GetObjectArrayElement(environment_values, index));
        std::string name = fromJava(env, nameItem);
        std::string value = fromJava(env, valueItem);
        env->DeleteLocalRef(valueItem);
        env->DeleteLocalRef(nameItem);
        if (!name.empty()) entries.push_back(name + "=" + value);
    }

    std::vector<char*> argument_pointers;
    argument_pointers.reserve(argv.size() + 1);
    for (const std::string& argument : argv) {
        argument_pointers.push_back(const_cast<char*>(argument.c_str()));
    }
    argument_pointers.push_back(nullptr);

    std::vector<char*> environment_pointers;
    environment_pointers.reserve(entries.size() + 1);
    for (std::string& entry : entries) {
        environment_pointers.push_back(entry.data());
    }
    environment_pointers.push_back(nullptr);

    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    int master = -1;
    const pid_t child = forkpty(&master, nullptr, nullptr, &size);
    if (child < 0) return -1;

    if (child == 0) {
        chdir(workdir.c_str());  // best effort; guest -w governs later
        if (strchr(argv[0].c_str(), '/') != nullptr) {
            execve(argv[0].c_str(), argument_pointers.data(),
                   environment_pointers.data());
        } else {
            execvp(argv[0].c_str(), argument_pointers.data());
        }
        _exit(127);
    }

    // Parent: non-blocking master for poll()-based reads from the JVM.
    const int flags = fcntl(master, F_GETFL, 0);
    if (flags >= 0) fcntl(master, F_SETFL, flags | O_NONBLOCK);

    return registerSession(env, child, master);
}

// Returns
//   > 0  bytes copied
//     0  poll timeout without data
//    -1  EOF / EIO (child side closed the master)
//    -2  handle error
extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_pollRead(
    JNIEnv* env, jobject, jint handle, jbyteArray buffer, jint timeout_millis) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    PtySession* session = lockedSession(env, handle);
    if (session == nullptr) return -2;
    if (session->reaped) return -1;

    struct pollfd descriptor {};
    descriptor.fd = session->master;
    descriptor.events = POLLIN;
    const int ready = poll(&descriptor, 1, timeout_millis);
    if (ready == 0) return 0;
    if (ready < 0) {
        if (errno == EINTR) return 0;
        return -2;
    }
    if (ready > 0 && (descriptor.revents & (POLLHUP | POLLERR | POLLNVAL))) {
        char scratch[16384];
        const ssize_t first = read(session->master, scratch, sizeof(scratch));
        if (first > 0) {
            const jint copy = static_cast<jint>(first < 16384 ? first : 16384);
            env->SetByteArrayRegion(buffer, 0, copy,
                                    reinterpret_cast<const jbyte*>(scratch));
            return copy;
        }
        tryReap(env, handle, session);
        return -1;
    }
    char scratch[16384];
    const ssize_t count = read(session->master, scratch, sizeof(scratch));
    if (count > 0) {
        const jint copy = static_cast<jint>(count < 16384 ? count : 16384);
        env->SetByteArrayRegion(buffer, 0, copy,
                                reinterpret_cast<const jbyte*>(scratch));
        return copy;
    }
    if (count == 0 || errno == EIO) {
        tryReap(env, handle, session);
        return -1;
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 0;
    return -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_write(
    JNIEnv* env, jobject, jint handle, jbyteArray data, jint length) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    PtySession* session = lockedSession(env, handle);
    if (session == nullptr || length <= 0) return -2;

    std::vector<char> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length,
                            reinterpret_cast<jbyte*>(bytes.data()));
    size_t written = 0;
    while (written < bytes.size()) {
        const ssize_t count = write(session->master,
                                    bytes.data() + written, bytes.size() - written);
        if (count > 0) {
            written += static_cast<size_t>(count);
            continue;
        }
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd descriptor {};
            descriptor.fd = session->master;
            descriptor.events = POLLOUT;
            poll(&descriptor, 1, 200);
            continue;
        }
        if (count < 0 && errno == EINTR) continue;
        tryReap(env, handle, session);
        return written > 0 ? static_cast<jint>(written) : -1;
    }
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_resize(
    JNIEnv* env, jobject, jint handle, jint columns, jint rows) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    PtySession* session = lockedSession(env, handle);
    if (session == nullptr) return -2;
    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    if (ioctl(session->master, TIOCSWINSZ, &size) < 0) return -1;
    // Deliver SIGWINCH semantics to the child group; plain shells ignore it.
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_signal(
    JNIEnv* env, jobject, jint handle, jint number) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    PtySession* session = lockedSession(env, handle);
    if (session == nullptr) return -2;
    const int sent = kill(-session->child, number);
    if (sent < 0) return kill(session->child, number) < 0 ? -1 : 0;
    return 0;
}

// 1 = alive, 0 = exited (code latched), -1 = waitpid error.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_isAlive(
    JNIEnv* env, jobject, jint handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    PtySession* session = lockedSession(env, handle);
    if (session == nullptr) return -1;
    if (tryReap(env, handle, session)) return 0;
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_exitCode(
    JNIEnv* env, jobject, jint handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    PtySession* session = lockedSession(env, handle);
    if (session == nullptr) return -1;
    if (!session->reaped && !reapBlocking(env, handle, session)) return -1;
    return static_cast<jint>(session->exit_code);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_spectordev9_opencode_terminal_NativePty_close(
    JNIEnv* env, jobject, jint handle) {
    PtySession session;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        session = it->second;
        g_sessions.erase(it);
    }
    if (session.master >= 0) close(session.master);
    if (!session.reaped) {
        int status = 0;
        const pid_t result = waitpid(session.child, &status, WNOHANG);
        if (result != session.child) {
            terminateGroup(session.child);
            pid_t finalStatus = 0;
            do {
                finalStatus = waitpid(session.child, &status, 0);
            } while (finalStatus < 0 && errno == EINTR);
        }
    }
}
