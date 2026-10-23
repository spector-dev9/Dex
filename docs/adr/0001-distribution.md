# ADR 0001 — Distribution and licensing (D1)

Status: **Accepted** (owner sign-off 2026-10-23)
Date: 2026-10-23
Owners: project owner
Related: S9, D1, D8, D13, SEC-5, REL-1/2/3

## Context

The app bundles a native OpenCode runtime, a musl loader, PRoot and an
Alpine rootfs. Android 10+ blocks `execve` from app-writable storage, so
the runtime must ship inside the APK and be mapped/executed through
native-library extraction or PRoot (D3). Downloading the runtime on
first launch, as the legacy app did, conflicts with Play's executable
code policy and with reproducibility.

The spec's default is GitHub Releases plus a self-hosted F-Droid repo
(Obtainium compatible), with Google Play only after S9. This ADR records
that default as a proposal and lists what must be true before it is
accepted.

Current facts verified for this ADR:

- OpenCode is MIT (upstream `LICENSE`, checked 2026-10-23).
- The community Termux artifact is MIT, but the tarball contains only
  binaries (`opencode`, musl loader and libs), no license texts.
- PRoot is GPL-2.0.
- Alpine 3.20.3 is a mixed-license aggregate (GPL-2.0 components such as
  BusyBox/apk-tools, MIT musl, Apache-2.0 OpenSSL, and others).
- Play requires new apps and updates to target API 36 from
  2026-08-31 (extension to 2026-11-01 available).
- Play forbids downloading executable code (dex/JAR/`.so`) outside Play,
  but allows code that runs in an interpreter/VM.
- F-Droid main-repo inclusion requires the app itself to be FLOSS, a
  100% FLOSS build toolchain, and prebuilt binaries only from trusted
  sources; prebuilt Alpine binaries are not a listed source.
- Android developer verification starts 2026-09-30 in Brazil,
  Indonesia, Singapore and Thailand for participating stores, expanding
  globally in 2027. Unregistered apps remain installable via ADB or the
  advanced sideload flow.

## Decision

1. **Primary channel:** signed APKs on GitHub Releases, arm64-v8a,
   compatible with Obtainium. Publish checksums, SBOM and license
   notices with every release.
2. **Secondary channel:** a self-hosted F-Droid repo once REL-1
   (reproducible build) passes. Do not target the F-Droid main repo
   until the app license, toolchain and rootfs sourcing questions are
   resolved.
3. **Runtime delivery:** bundle the pinned runtime, loader, PRoot and
   rootfs in the APK. No first-launch executable download.
4. **Google Play:** deferred. A Play variant would need SAF-only
   workspace access (no `MANAGE_EXTERNAL_STORAGE`), a policy review for
   bundled native executables, target API 36, a foreground-service
   declaration, and developer verification. Do not ship the sideload
   build to Play.
5. **App license:** Apache-2.0 for the clean-room code, keeping the
   F-Droid main-repo option open (owner decision, 2026-10-23).
6. **Developer verification:** register the application ID and signing
   identity in the Android Developer Console before the 2027 global
   rollout; document the advanced-flow/ADB path for users in the
   meantime.
7. **Trademark:** confirm the right to use "OpenCode" in the app name
   and listing, or rename before the first public release. Do not imply
   endorsement by the upstream project.

## Options considered

| Option | Pros | Cons | Verdict |
| --- | --- | --- | --- |
| GitHub Releases + Obtainium | No store policy gate; fast iteration; full native runtime | Manual updates; unknown-source warnings; 2027 verification rollout | **Primary** |
| Self-hosted F-Droid repo | F-Droid client UX; reproducible metadata | Must maintain repo/signing; still needs source offers; no main-repo trust | **Secondary, after REL-1** |
| F-Droid main repo | Trust and discoverability | App must be FLOSS; prebuilt Alpine/PRoot likely rejected; long review | Deferred |
| Google Play | Reach | Executable-download policy, target 36, FGS/data-safety review, no broad storage permission | Deferred |

## Consequences

- Users install outside Play and may need to allow unknown sources or
  use Obtainium/ADB.
- GPL-2.0 source-offer duties apply to PRoot and GPL Alpine packages;
  MIT/Apache notices apply to the rest.
- The release pipeline must publish source, SBOM and notices, not just
  the APK.
- The Play-compatible variant, if ever built, cannot rely on broad
  shared-storage access.

## Exit criteria for accepting this ADR

- [x] Owner signs the channel and app-license choices (2026-10-23).
- [ ] `docs/licenses/` checklist complete for the first release.
- [ ] PRoot and GPL package source offer published.
- [ ] SBOM generated and scanned (SEC-5).
- [ ] Reproducible-build check passes (REL-1).
- [ ] Trademark decision recorded.
- [ ] Developer-verification registration plan recorded.
