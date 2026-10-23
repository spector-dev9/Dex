# Contract reconciliation: §3 interfaces and §4.3 bridge commands

Status: contract-level reconciliation complete, 2026-10-23.

This record reconciles the service interfaces in `docs/spec.md` §3, the
bridge command surface in §4.3, and the authoritative command constants in
`core:bridge-api`. It closes only the six named contract gaps below. It does
not claim that any service is implemented.

`contract/endpoints.json` is an inventory of the pinned OpenCode HTTP
surface. It is used here only to verify the session command mapping; it
does not define bridge commands.

## Closure rule

An item is **CLOSED** only when the reconciled contract names it and the
Kotlin contract surface contains the same operation. A closed item is
still unimplemented and unverified until its gate evidence exists.

## Items

| # | Item | Source contract | Contract evidence | Disposition |
| --- | --- | --- | --- | --- |
| 1 | `workspace.stat` | §4.3 commands; §4.2 native owner | `docs/spec.md:138`; `WorkspaceContract.kt:48`; `BridgeCommandNames.kt:29` | **CLOSED** |
| 2 | `workspace.search` | §4.3 commands; §4.2 native owner | `docs/spec.md:140`; `WorkspaceContract.kt:64`; `BridgeCommandNames.kt:37` | **CLOSED** |
| 3 | `workspace.move` | §4.3 commands; §4.2 native owner | `docs/spec.md:139`; `WorkspaceContract.kt:58`; `BridgeCommandNames.kt:34` | **CLOSED** |
| 4 | `terminal.signal` | §3 `PtyService.signal`; §4.3 terminal commands | `docs/spec.md:93,143`; `TerminalContract.kt:52`; `BridgeCommandNames.kt:51` | **CLOSED** |
| 5 | `GitService` full interface | §4.2 Git owner; §4.3 git commands | `GitContract.kt:57-85`; `BridgeCommandNames.kt:39-46` | **CLOSED** |
| 6 | Every `session.*` operation, no wildcard | §4.1 used endpoints; §4.3 commands | `docs/spec.md:133-137`; `BridgeCommandNames.kt:10-26` | **CLOSED** |

### 1-3. Workspace operations

`workspace.stat`, `workspace.search` and `workspace.move` were named in
the §4.3 command list but absent from the earlier bridge mirror. The
reconciled contract now matches all three in both places:

- `WorkspaceService.stat(path)` — `WorkspaceContract.kt:48`
- `WorkspaceService.search(path, query, limit)` — `WorkspaceContract.kt:64`
- `WorkspaceService.move(from, to)` — `WorkspaceContract.kt:58`
- `BridgeCommandNames.WORKSPACE_STAT/SEARCH/MOVE` —
  `BridgeCommandNames.kt:29,37,34`

These are native operations, not OpenCode endpoints, as required by §4.2.

### 4. `terminal.signal`

§3 defines `signal(sig)` on `PtySession` in the PTY interface. §4.3
previously listed only `terminal.open`, `terminal.write`,
`terminal.resize` and `terminal.close`. The reconciled §4.3 now includes
`terminal.signal`, and the bridge constant mirrors it.

- `PtySession.signal(signal)` — `TerminalContract.kt:52`
- `terminal.signal` — `docs/spec.md:143`
- `BridgeCommandNames.TERMINAL_SIGNAL` — `BridgeCommandNames.kt:51`

The command remains unimplemented; this closes only the contract gap.

### 5. `GitService` full interface

The complete command set is defined by §4.2 and §4.3 and mirrored by
`GitService`:

| Contract operation | Interface method | Bridge command |
| --- | --- | --- |
| status | `status(repositoryPath)` | `git.status` |
| diff | `diff(repositoryPath, path, staged)` | `git.diff` |
| init | `initialize(repositoryPath)` | `git.init` |
| fetch | `fetch(repositoryPath, remote)` | `git.fetch` |
| pull | `pull(repositoryPath, remote, branch)` | `git.pull` |
| branches | `branches(repositoryPath)` | `git.branches` |
| checkout | `checkout(repositoryPath, ref)` | `git.checkout` |
| commit | `commit(repositoryPath, message, paths)` | `git.commit` |

Evidence: `GitContract.kt:57-85`; `BridgeCommandNames.kt:39-46`.

Credential handling remains a D9 implementation concern and does not add
a ninth bridge operation.

### 6. Explicit `session.*` enumeration

The §4.3 wildcard has been replaced with the complete 17-command list.
Each command maps to a documented §4.1 route that is present in the pinned
OpenAPI inventory:

| Bridge command | Contract route |
| --- | --- |
| `session.list` | `GET /session` |
| `session.get` | `GET /session/{sessionID}` |
| `session.create` | `POST /session` |
| `session.update` | `PATCH /session/{sessionID}` |
| `session.delete` | `DELETE /session/{sessionID}` |
| `session.status` | `GET /session/status` |
| `session.fork` | `POST /session/{sessionID}/fork` |
| `session.abort` | `POST /session/{sessionID}/abort` |
| `session.revert` | `POST /session/{sessionID}/revert` |
| `session.unrevert` | `POST /session/{sessionID}/unrevert` |
| `session.summarize` | `POST /session/{sessionID}/summarize` |
| `session.diff` | `GET /session/{sessionID}/diff` |
| `session.messages` | `GET /session/{sessionID}/message` |
| `session.promptAsync` | `POST /session/{sessionID}/prompt_async` |
| `session.command` | `POST /session/{sessionID}/command` |
| `session.shell` | `POST /session/{sessionID}/shell` |
| `session.permissionReply` | `POST /session/{sessionID}/permissions/{permissionID}` |

Evidence: `docs/spec.md:133-137`; `BridgeCommandNames.kt:10-26`;
`contract/endpoints.json` `required` entries.

The upstream `POST /session/{sessionID}/message` route is present in the
pinned OpenAPI document but is not in the §4.1 used-route table; the
bridge does not expose it as a separate command. Prompt submission uses
`session.promptAsync` as specified.

## Result

All six contract gaps are closed at the specification and Kotlin contract
level. No wildcard remains. No service implementation, Android code,
annotation gate, compiled-class parity test, or runtime evidence is
provided by this record.

Tracked contract edits for this reconciliation:

- `docs/spec.md` §4.3: explicit 17-command `session.*` list and
  `terminal.signal`.
- `core/bridge-api/.../BridgeCommandNames.kt`: `TERMINAL_SIGNAL`.
- `contract/reconciliation.md`: this record.
