# Legacy application review (research only)

Reference: `spector-dev9/Test`, commit `3a75b62` ("Allow custom shell
fields in native PTY requests"), inspected on 2026-10-23.
No source code is copied into this repository.

## Baseline

- Capacitor Android client; React 19 + Vite UI in JSX; Java native
  plugins; a pinned OpenCode 1.19.3 musl build from a community Termux
  release, launched through a bundled musl loader.
- CI prepares PRoot, libtalloc, libandroid-shmem and an Alpine 3.20
  rootfs. The audit report says a pinned release was verified on
  OpenCode 1.19.3.
- The UI is large and test-heavy: the `src/lib` suite has roughly 200
  `test()` call sites covering URL policy, native request validation,
  path safety, sessions, todos, terminal output and presentation logic.
- The audit report documents round-two work: Build/Plan switching,
  Git workflows, Alpine + PRoot terminal, tool cards, error boundaries.

## Confirmed risks and limitations (from code and docs)

1. **Unauthenticated loopback server (highest severity).**
   `LocalServerManager.startServer()` runs
   `opencode serve --hostname 127.0.0.1 --port 4097` and
   `configureEnvironment()` never sets `OPENCODE_SERVER_PASSWORD`.
   Any app with INTERNET permission could reach shell and file routes.
   Client-side origin allowlisting does not protect the server.
2. **Agent shell runs on the host, not in the guest.**
   The server environment sets `SHELL=/system/bin/sh` and a host
   `PATH`; PRoot/Alpine are used for the terminal panel and Git, not
   for the agent's own bash tool. This is the three-filesystems problem
   (spec F9).
3. **Polling instead of streaming.** The CapacitorHttp bridge cannot
   consume SSE; the UI polls message lists. The native transport also
   caps responses at 5 MiB.
4. **Incomplete file operations.** Writes go through single-file
   patches: no folder create, rename, move, delete, or empty files.
5. **PTY dependency.** The app uses `/pty` REST endpoints plus a
   WebSocket through `PtySocketPlugin`. The pinned 1.19.3 `/doc`
   documents these routes (5 `/pty` and 4 `/api/pty` paths), although
   the public server docs lag. D10 (native PTY) still stands so the
   terminal works when the server is down.
6. **Fixed origin and port.** `http://127.0.0.1:4097` is compiled into
   the client and server start path; no free-port selection.
7. **Download-on-first-launch runtime.** The binary is fetched from a
   GitHub release at runtime, then checksum-verified. This conflicts
   with offline first-run and supply-chain goals (spec D5).
8. **Debug-oriented distribution.** CI produces a debug APK only;
   cleartext is allowed for loopback in debug. No release signing or
   reproducible-build check.
9. **No native workspace service.** Projects are scoped through
   client-side allowlists and app-private paths; SAF interoperability
   and POSIX semantics remain unresolved.
10. **Session-store and rootfs drift.** The audit report describes two
    session stores and an unused bundled rootfs in the earlier
    baseline; later work bound the Alpine panel to projects.

## Useful for a reviewed port (not automatic reuse)

- Pure-logic modules with their tests: connection/URL policy, request
  validation, project scoping, file path/patch safety, todos, markdown,
  diff, terminal output handling, session transfer.
- UI components: error boundary, tool cards, todo panel, appearance
  settings, session import/export, file tree, terminal view.
- Verification of these items was not re-run against the new
  architecture. Each port requires a dependency and test review and
  must be re-tested under the new bridge.

## Explicitly not reused

- Capacitor configuration, native Java implementation, Gradle/build
  files, bundled binaries/rootfs, or the application tree as a whole.

## Open questions from the spec, answered from code

1. Does `LocalServerManager` pass `OPENCODE_SERVER_PASSWORD` today? **No.**
2. Where does the agent's bash tool run? **Host Android.**
3. Does the pinned version expose PTY routes? **Yes — documented in
   the pinned 1.19.3 `/doc`** (9 routes). D10 (native PTY) still
   applies for server-down operation.
4. Physical device availability? **Owner input needed.**
5. Target audience: sideload, Play, or both? **Owner decision at S9.**
