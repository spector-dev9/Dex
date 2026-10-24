# Upstream tracking

The upstream remote is `https://github.com/yuga-hashimoto/and-code.git`.
Its `main` branch resolved to `f28d8d93f82b10ddfb48da44d787192f87ed8360`
when this record was created. No upstream changes have been merged or copied
as part of this update.

## Known divergences

| Area | This repository | Upstream at recorded revision |
| --- | --- | --- |
| Module boundaries | Pure Kotlin contracts and extracted implementations are split into `core/*` Gradle modules. | Runtime implementation, including `LocalRuntimeManager`, `LocalRuntimeService`, and `LocalRuntimeTarget`, is under the Android app source tree and uses app-specific types. |
| OpenCode contract | Client integration is pinned to OpenCode 1.19.3 and its checked-in OpenAPI snapshot. | Upstream runtime/client code is coupled to upstream API and model types; compatibility with the pinned 1.19.3 contract has not been established. |
| Runtime implementation | `core/runtime-api` defines the current boundary; `core/runtime` implementation has not yet been extracted. | Local runtime installer, manager, process lifecycle, and Android service sources exist in `app/src/main/.../runtime/local`. |

## Cherry-pick and sync policy

- Record the upstream commit being compared before importing a change.
- Review its dependencies against the current module boundaries and pinned API
  contract; do not copy app-coupled code directly into a core module.
- Port only compatible changes, preserving contract tests and the debug-build
  and device-smoke-test gates.
- Update this table with the resulting divergence or convergence and the
  commit(s) involved.
