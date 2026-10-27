# Phase 2 plan — application rebuild (G2)

Status: **Planned; starts after the G1 backend composition is usable.** G2 is
the native application rebuild: the UI runs entirely on the Kotlin bridge,
legacy web/WebView/Capacitor paths are removed, and the regression map is
complete.

G2 does not reopen G0 decisions. The open G0 blockers remain release blockers,
and G2 acceptance cannot substitute for G1 device evidence.

## Milestones

### M1 — bridge transport and app composition

- [ ] Bind the production `BridgeCommandHandler` to the in-process transport
      used by the app.
- [ ] Implement bridge JSON request/result/event serialization with the v1
      envelope and payload DTOs.
- [ ] Wire runtime, supervisor, gateway, workspace, Git, terminal, security
      and diagnostics ports through one composition root.
- [ ] Expose typed state flows for runtime/server/workspace and typed events
      for session, permission, terminal, workspace and diagnostics topics.
- [ ] Add bridge contract tests for every command and event used by the UI.

Exit: the app can drive all supported backend operations through the bridge
without importing service implementations into UI code.

### M2 — native Compose screens

- [ ] Chat and session transcript with streamed updates, abort, revert and
      permission prompts.
- [ ] Session list, create, fork, delete and status.
- [ ] File browser with bounded ranged reads, atomic writes, rename, move,
      trash and restore.
- [ ] Git status, diff, branch, checkout and commit.
- [ ] Terminal screen backed by the production `PtyService`, bounded
      scrollback and extra-key row.
- [ ] Settings for provider credentials, workspace access and diagnostics.
- [ ] Diagnostics screen with redacted export and explicit share review.
- [ ] Remove the placeholder verification-checklist panel from the user UI.

Exit: every G2 screen reads and mutates state only through the bridge.

### M3 — lifecycle and recovery UX

- [ ] Foreground-service notification and stop/restart controls.
- [ ] Battery-optimization exemption explainer and OEM autostart guidance.
- [ ] Resume reconciliation: health check, SSE reconnect from the last event
      ID, and authoritative session re-fetch.
- [ ] Visible degraded/backoff/crash-loop states with actionable recovery.
- [ ] Rotation and process-death behavior with no lost or duplicated content.

Exit: LC-1, LC-4 and the app-level parts of STR-5 pass on device.

### M4 — remove legacy paths

- [ ] Remove Capacitor and legacy WebView entry points from the app.
- [ ] Retire the TypeScript bridge from the runtime path; keep it only as a
      parity fixture until the Kotlin contract is frozen.
- [ ] Remove placeholder/unreachable screens and dead dependencies.
- [ ] Complete the regression map for known limitations.
- [ ] Confirm the release manifest has only the intended exported components,
      no backups and loopback-only cleartext.

Exit: no G2 feature depends on a web runtime, and the architecture check
enforces the native boundary.

### M5 — G2 acceptance

- [ ] Run the full E2E flow on the Infinix X6870: install, first run, session,
      stream, permission reply, file write, Git status, terminal and export.
- [ ] Verify SEC-4: every permission request is visible and answerable; file
      and shell actions appear in the transcript.
- [ ] Measure event-to-paint latency and SSE recovery.
- [ ] Run the regression matrix and record device evidence with exact APK and
      runtime hashes.
- [ ] Update release notes, traceability and the compatibility matrix.

Exit: G2 acceptance is green on the reference device and the app is ready for
release hardening.

## Constraints

- Keep the native Kotlin/Compose direction from D1 and the architecture rules
  in `docs/architecture/g1-contracts.md`.
- Do not reintroduce a WebView or Capacitor runtime path.
- Do not hide agent permissions or silently approve file/shell actions.
- Keep provider credentials out of URLs, logs and process arguments.
- Do not claim G2 acceptance from host tests, harness runs or owner reports
  without device evidence.
