# S3 — Agent shell redirection (host guest partial)

Date: 2026-10-23. Server: pinned 1.19.3, no password. Status:
**partial — SHELL override works and agent bash enters PRoot Alpine;
app sandbox and native PTY identity untested.**

> Pin note (2026-10-24): this host run used PRoot 5.1.107.98. The pin is
> now 5.1.107.100 because the .98 Termux deb returns HTTP 404. Re-run the
> guest wrapper with .100 on devices.

## .100 pin revalidation (host only)

Re-ran the same guest wrapper on the host with the staged PRoot
5.1.107.100 binary and loader:

```text
PROOT_LOADER=<staged>/loader PROOT_TMP_DIR=<staged>/tmp \
PROOT_NO_SECCOMP=1 LD_LIBRARY_PATH=<staged>:<staged>/runtime \
PATH=/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
<staged>/proot --kill-on-exit -0 -r <staged>/rootfs \
  -b /dev -b /proc -b /sys -w /root \
  /bin/sh -c 'pwd; echo PATH=$PATH; /bin/cat /etc/alpine-release; echo GUEST-OK'
```

Output: `/root`, Alpine `3.20.3`, `GUEST-OK`; exit 0. This is still
host-only and does not satisfy S3 device acceptance.

Host gotcha: Termux exports `LD_PRELOAD=libtermux-exec-ld-preload.so`;
leaving it set makes the guest musl loader fail with
`Error relocating ... symbol not found`. The harness removes
`LD_PRELOAD` from the child environment before launching. Device app
processes normally do not set it, but the harness should not inherit it.

## Question

Does the agent's bash tool honor a `SHELL` override so commands can be
redirected into the Alpine/PRoot guest?

## Host shell wrapper

- `SHELL` pointed at a wrapper that appends its arguments to a marker
  file, then executes the host shell: `exec /system/bin/sh "$@"`.
- Prompt: use the bash tool to run exactly `printf S3-AGENT-OK`, no
  other tool.
- Provider: credential-free `opencode/deepseek-v4.1-flash-free`.

| Check | Result |
| --- | --- |
| Tool selected | `bash` |
| Tool status | completed, exit 0 |
| Tool output | `S3-AGENT-OK` |
| Wrapper marker | `-c printf S3-AGENT-OK` |
| Wrapper invoked | yes |

The marker proves OpenCode resolved and executed the `SHELL` value for
the agent's bash tool. The source path is `Shell.acceptable(cfg.shell)`
in the shell tool, with `process.env.SHELL` as the default.

## PRoot guest wrapper

The pinned Alpine 3.20.3 minirootfs, PRoot 5.1.107.98, libtalloc and
libandroid-shmem were fetched with hash verification. A new wrapper
executes PRoot, binds `/dev`, `/proc` and `/sys`, starts in `/root` and
sets the guest `PATH` before running `/bin/sh`.

Prompt: use the bash tool to run exactly
`pwd; printf S3-GUEST-OK`, no other tool. Result:

```
/root
S3-GUEST-OK
```

Marker: `-c pwd; printf S3-GUEST-OK`; exit 0. Running the same wrapper
directly also reports `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:
/usr/bin:/sbin:/bin` and Alpine `3.20.3`. This is the Profile A guest
shell path, minus the app sandbox and supervisor.

## Server PTY limitation

Creating a PTY through `/pty` on this host fails for both
`/system/bin/sh` and the guest wrapper with HTTP 500 and
`TypeError: undefined is not an object (evaluating 'q.symbols')`.
The server log shows the real cause:

```
ERR_DLOPEN_FAILED
Failed to open library "...-00000002.so":
Error relocating ...: gnu_get_libc_version: symbol not found
```

The bundled `bun-pty` native library is glibc-linked and cannot load
under Android bionic. This is not a TTY problem; the server was also
tested under `script`. D10 already requires a native `PtyService`, so
this does not block Profile A, but S3 cannot use the server PTY for its
comparison and the app must not depend on `/pty`.

## Conclusion

Profile A can redirect the agent shell into the PRoot guest with a
`SHELL` wrapper, and the wrapper is actually used by the bash tool.
This removes the main uncertainty about guest shell redirection.

## Still required for S3 pass

- Run inside the app sandbox with the bundled loader/PRoot and the
  Android supervisor.
- Compare `pwd`, `node -v` and `PATH` in the agent shell and the native
  PTY; they must be identical.
- Test `shell` in opencode config as an alternative to `SHELL`.

Raw evidence: `results/raw/s3-shell-result.json` and
`results/raw/s3-guest-result.json`.
