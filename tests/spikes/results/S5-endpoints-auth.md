# S5 — Endpoint inventory and auth

Date: 2026-10-23. Pinned OpenCode 1.19.3, live `GET /doc`.
Snapshot: `contract/openapi/opencode-1.19.3.json` (sha-256
`eebb5604…417b`). Generated inventory: `contract/endpoints.json`.

## Endpoint inventory

| Property | Result |
| --- | --- |
| OpenAPI version | 3.1.0 |
| Paths | 162 |
| Section 4.1 endpoints present | 36/36 |
| Path parameter naming | `{sessionID}`, `{permissionID}` (spec wrote `:id`) |
| `/doc` in its own path list | no (serves the document, not listed) |
| `/api/*` surface | 51 paths not mentioned in Section 4.1 |
| PTY routes | 9 documented: `/pty`, `/pty/shells`, `/pty/{ptyID}`, `/pty/{ptyID}/connect`, `/pty/{ptyID}/connect-token` plus `/api/pty/*` equivalents |

### F3 correction

The spec said PTY routes were undocumented. The pinned version's `/doc`
documents them. D10 (native PtyService) can still stand — the terminal
must work when the server is down — but its rationale must be revised
at G0: it is no longer "avoid an undocumented endpoint".

Runtime check: `POST /pty` on the host 1.19.3 build returns 500 because
the bundled `bun-pty` library is glibc-linked and bionic cannot relocate
`gnu_get_libc_version`. The routes are documented but unusable on this
artifact; native PTY is now a hard requirement, not only an offline
fallback.

## Authentication (`OPENCODE_SERVER_PASSWORD`)

| Request | No password set | Password `test-secret` |
| --- | --- | --- |
| No credentials | 200 | **401** |
| Basic `opencode:test-secret` | 200 | **200** |
| Basic `:test-secret` (empty user) | 200 | 401 |
| Bearer `test-secret` | 200 | 401 |
| Basic `opencode:wrong` | 200 | 401 |

- The accepted scheme is HTTP Basic with username **`opencode`**.
- `/global/health`, `/doc`, `/project`, `/session`, `/pty` and `/event`
  are all protected once a password is set. The supervisor's health
  probe and the SSE client must therefore send the header too.
- Without a password, every route is open to any local process (200).
  This confirms F5 and makes D7 mandatory before G1.

Raw evidence: `results/raw/no-auth-summary.json`,
`results/raw/with-auth-summary.json`.

## Consequences for the contract

1. Use `{sessionID}`/`{permissionID}` in the pinned inventory.
2. Add the 51 `/api/*` paths to the drift watch, even though v1 does not
   use them.
3. Treat PTY as documented but still implement D10.
4. Gateway: Basic auth on every request, including health and SSE.
5. Auth matrix becomes an automated test (`contract/tests/contract.test.mjs`).
