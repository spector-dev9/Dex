/*
 * Profile A agent-shell entrypoint.
 *
 * OpenCode's shell tool executes a configured shell as `<shell> -c <command>`.
 * This helper is packaged as an APK-native executable so Android never
 * execve()s a writable-storage file. It turns that invocation into the same
 * PRoot/Alpine command used by the terminal and Git services.
 *
 * Required environment (set by ProductionServerLauncher):
 *   DEX_GUEST_PROOT       packaged libproot.so
 *   DEX_GUEST_LOADER      packaged libproot_loader.so
 *   DEX_GUEST_ROOTFS      installed Alpine rootfs
 *   DEX_GUEST_WORKSPACE   host workspace bound at /workspace
 *   DEX_GUEST_TMP         host PRoot temporary directory
 *   DEX_GUEST_RUNTIME     runtime directory containing Alpine libraries
 */

#define _GNU_SOURCE

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char* required(const char* name) {
    const char* value = getenv(name);
    if (value == NULL || value[0] == '\0') {
        fprintf(stderr, "guest shell: %s is not set\n", name);
        _exit(127);
    }
    return value;
}

static void set_guest_environment(
    const char* rootfs,
    const char* runtime,
    const char* tmp,
    const char* loader) {
    if (clearenv() != 0) {
        fprintf(stderr, "guest shell: clearenv failed: %s\n", strerror(errno));
        _exit(127);
    }

    setenv("HOME", "/root", 1);
    setenv("TMPDIR", "/tmp", 1);
    setenv("PATH",
           "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("TERM", "xterm-256color", 1);
    setenv("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt", 1);
    setenv("GIT_CONFIG_GLOBAL", "/root/.gitconfig", 1);
    setenv("GIT_CONFIG_SYSTEM", "/dev/null", 1);
    setenv("GIT_SSL_CAINFO", "/etc/ssl/certs/ca-certificates.crt", 1);
    setenv("GIT_TERMINAL_PROMPT", "0", 1);
    setenv("GIT_EDITOR", "true", 1);
    setenv("GIT_PAGER", "cat", 1);

    setenv("PROOT_TMP_DIR", tmp, 1);
    setenv("PROOT_LOADER", loader, 1);
    setenv("PROOT_NO_SECCOMP", "1", 1);
    unsetenv("LD_PRELOAD");

    const size_t library_path_size = strlen(rootfs) + strlen(runtime) + 2;
    char* library_path = malloc(library_path_size);
    if (library_path == NULL) {
        fprintf(stderr, "guest shell: out of memory\n");
        _exit(127);
    }
    snprintf(library_path, library_path_size, "%s:%s", rootfs, runtime);
    setenv("LD_LIBRARY_PATH", library_path, 1);
}

int main(int argc, char** argv) {
    const char* proot = required("DEX_GUEST_PROOT");
    const char* loader = required("DEX_GUEST_LOADER");
    const char* rootfs = required("DEX_GUEST_ROOTFS");
    const char* workspace = required("DEX_GUEST_WORKSPACE");
    const char* tmp = required("DEX_GUEST_TMP");
    const char* runtime = required("DEX_GUEST_RUNTIME");

    const size_t bind_size = strlen(workspace) + sizeof(":/workspace");
    char* bind = malloc(bind_size);
    if (bind == NULL) {
        fprintf(stderr, "guest shell: out of memory\n");
        return 127;
    }
    snprintf(bind, bind_size, "%s:/workspace", workspace);

    // OpenCode spawns the shell with the session/workdir as the host cwd.
    // Map a cwd below the bound workspace into the matching guest path so
    // bash workdir overrides keep working through the wrapper. Resolve the
    // workspace first because Android aliases /data/user/0 to /data/data.
    char resolved_workspace[PATH_MAX];
    const char* canonical_workspace = workspace;
    if (realpath(workspace, resolved_workspace) != NULL) {
        canonical_workspace = resolved_workspace;
    }
    char guest_cwd[PATH_MAX] = "/workspace";
    char host_cwd[PATH_MAX];
    const size_t workspace_length = strlen(canonical_workspace);
    if (getcwd(host_cwd, sizeof(host_cwd)) != NULL &&
        strncmp(host_cwd, canonical_workspace, workspace_length) == 0 &&
        (host_cwd[workspace_length] == '/' ||
         host_cwd[workspace_length] == '\0')) {
        snprintf(guest_cwd, sizeof(guest_cwd), "/workspace%s",
                 host_cwd + workspace_length);
    }

    char* arguments[argc + 20];
    int count = 0;
    arguments[count++] = (char*) proot;
    arguments[count++] = "--kill-on-exit";
    arguments[count++] = "-0";
    arguments[count++] = "-r";
    arguments[count++] = (char*) rootfs;
    arguments[count++] = "-b";
    arguments[count++] = "/dev";
    arguments[count++] = "-b";
    arguments[count++] = "/proc";
    arguments[count++] = "-b";
    arguments[count++] = "/sys";
    arguments[count++] = "-b";
    arguments[count++] = bind;
    arguments[count++] = "-w";
    arguments[count++] = guest_cwd;
    arguments[count++] = "/bin/sh";
    for (int index = 1; index < argc; ++index) {
        arguments[count++] = argv[index];
    }
    arguments[count] = NULL;

    set_guest_environment(rootfs, runtime, tmp, loader);
    execv(proot, arguments);

    fprintf(stderr, "guest shell: exec %s failed: %s\n", proot, strerror(errno));
    return 127;
}
