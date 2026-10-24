#include <jni.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <string>
#include <vector>

namespace {

constexpr size_t kMaxOutputBytes = 64 * 1024;
constexpr char kProbeScript[] =
    "stty -echo; stty size; "
    "node_version=$(node -v 2>&1); node_status=$?; "
    "printf 'D=%s\\nP=%s\\nN=%s\\n' \"$PWD\" \"$PATH\" \"$node_version\"; "
    "[ \"$node_status\" -eq 0 ] || exit \"$node_status\"; "
    "printf 'GUEST-PTY-OK\\n'; exit\n";

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void appendOutput(std::string* output, const char* data, size_t count) {
    if (output->size() >= kMaxOutputBytes) return;
    const size_t remaining = kMaxOutputBytes - output->size();
    output->append(data, count < remaining ? count : remaining);
}

int waitForChild(pid_t child, int* status) {
    pid_t result;
    do {
        result = waitpid(child, status, 0);
    } while (result < 0 && errno == EINTR);
    return result == child ? 0 : -1;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_spectordev9_opencode_spike_NativePtyProbe_runProotShell(
    JNIEnv* env,
    jobject,
    jstring proot_value,
    jstring loader_value,
    jstring rootfs_value,
    jstring runtime_root_value,
    jlong timeout_ms) {
    std::string proot = fromJava(env, proot_value);
    const std::string loader = fromJava(env, loader_value);
    std::string rootfs = fromJava(env, rootfs_value);
    const std::string runtime_root = fromJava(env, runtime_root_value);
    if (proot.empty() || loader.empty() || rootfs.empty() || runtime_root.empty()) {
        return env->NewStringUTF("exitCode=-1\ninvalid or missing path argument\n");
    }

    const std::string library_path = runtime_root + ":" + runtime_root + "/runtime";
    const std::string tmp_dir = runtime_root + "/tmp";
    std::vector<std::string> environment = {
        "PROOT_LOADER=" + loader,
        "PROOT_TMP_DIR=" + tmp_dir,
        "PROOT_NO_SECCOMP=1",
        "LD_LIBRARY_PATH=" + library_path,
        "PATH=/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:"
        "/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME=/root",
        "TERM=xterm-256color",
    };
    std::vector<char*> environment_pointers;
    environment_pointers.reserve(environment.size() + 1);
    for (std::string& entry : environment) {
        environment_pointers.push_back(entry.data());
    }
    environment_pointers.push_back(nullptr);

    char arg_kill_on_exit[] = "--kill-on-exit";
    char arg_zero[] = "-0";
    char arg_root[] = "-r";
    char arg_bind_dev[] = "-b";
    char arg_dev[] = "/dev";
    char arg_bind_proc[] = "-b";
    char arg_proc[] = "/proc";
    char arg_bind_sys[] = "-b";
    char arg_sys[] = "/sys";
    char arg_workdir[] = "-w";
    char arg_root_workdir[] = "/root";
    char arg_shell[] = "/bin/sh";
    char arg_interactive[] = "-i";
    std::vector<char*> arguments = {
        proot.data(),
        arg_kill_on_exit,
        arg_zero,
        arg_root,
        rootfs.data(),
        arg_bind_dev,
        arg_dev,
        arg_bind_proc,
        arg_proc,
        arg_bind_sys,
        arg_sys,
        arg_workdir,
        arg_root_workdir,
        arg_shell,
        arg_interactive,
        nullptr,
    };

    struct winsize size {};
    size.ws_col = 80;
    size.ws_row = 24;
    int master = -1;
    const pid_t child = forkpty(&master, nullptr, nullptr, &size);
    if (child < 0) {
        return env->NewStringUTF("exitCode=-1\nforkpty failed\n");
    }

    if (child == 0) {
        execve(proot.c_str(), arguments.data(), environment_pointers.data());
        _exit(127);
    }

    const int flags = fcntl(master, F_GETFL, 0);
    if (flags >= 0) fcntl(master, F_SETFL, flags | O_NONBLOCK);
    const size_t script_length = sizeof(kProbeScript) - 1;
    size_t written = 0;
    std::string output;
    int status = 0;
    bool child_exited = false;
    bool master_closed = false;
    bool timed_out = false;
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(timeout_ms > 0 ? timeout_ms : 60000);

    while (std::chrono::steady_clock::now() < deadline) {
        if (written < script_length) {
            const ssize_t count = write(master, kProbeScript + written, script_length - written);
            if (count > 0) written += static_cast<size_t>(count);
        }

        struct pollfd descriptor {};
        descriptor.fd = master;
        descriptor.events = POLLIN;
        poll(&descriptor, 1, 100);
        if (descriptor.revents & (POLLIN | POLLHUP | POLLERR)) {
            char buffer[4096];
            const ssize_t count = read(master, buffer, sizeof(buffer));
            if (count > 0) appendOutput(&output, buffer, static_cast<size_t>(count));
            else if (count == 0 || (count < 0 && errno == EIO)) master_closed = true;
        }

        if (!child_exited) {
            const pid_t result = waitpid(child, &status, WNOHANG);
            child_exited = result == child;
        }
        if (child_exited &&
            (master_closed || output.find("GUEST-PTY-OK") != std::string::npos)) {
            break;
        }
    }

    if (!child_exited) {
        timed_out = true;
        kill(-child, SIGTERM);
        usleep(250000);
        if (waitpid(child, &status, WNOHANG) == 0) {
            kill(-child, SIGKILL);
        }
        waitForChild(child, &status);
    }
    close(master);

    int exit_code = -1;
    if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) exit_code = 128 + WTERMSIG(status);

    std::string result = "exitCode=" + std::to_string(exit_code);
    result += timed_out ? "\ntimed out\n" : "\n";
    result += output;
    return env->NewStringUTF(result.c_str());
}
