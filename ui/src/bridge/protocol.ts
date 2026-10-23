/**
 * Typed bridge contract between the WebView UI and the Kotlin gateway
 * (spec Section 4.3). Envelope shapes are frozen: additive changes only.
 */

export const PROTOCOL_VERSION = 1 as const;

export type ErrorCode =
  | "E_RUNTIME_DOWN"
  | "E_AUTH"
  | "E_PATH_DENIED"
  | "E_CONFLICT"
  | "E_TOO_LARGE"
  | "E_TIMEOUT"
  | "E_UNSUPPORTED_VERSION"
  | "E_UPSTREAM"
  | "E_CANCELLED";

export const RETRYABLE: Readonly<Record<ErrorCode, boolean>> = {
  E_RUNTIME_DOWN: true,
  E_AUTH: false,
  E_PATH_DENIED: false,
  E_CONFLICT: false,
  E_TOO_LARGE: false,
  E_TIMEOUT: true,
  E_UNSUPPORTED_VERSION: false,
  E_UPSTREAM: true,
  E_CANCELLED: false,
};

/** Default per-operation timeouts in milliseconds (Section 4.4). */
export const TIMEOUTS = {
  health: 2_000,
  listGet: 10_000,
  fileRead: 15_000,
  promptAsync: 10_000,
  abort: 5_000,
  sseIdleWatchdog: 60_000,
  gitNetwork: 120_000,
} as const;

export type EventTopic =
  | "runtime.state"
  | "server.state"
  | "session.event"
  | "permission.requested"
  | "terminal.data"
  | "workspace.changed"
  | "diagnostics.alert";

export interface CommandEnvelope<A = unknown> {
  v: typeof PROTOCOL_VERSION;
  id: string;
  type: "cmd";
  name: string;
  args: A;
}

export interface ResultOk<T = unknown> {
  v: typeof PROTOCOL_VERSION;
  id: string;
  type: "res";
  ok: true;
  value: T;
}

export interface ResultError {
  v: typeof PROTOCOL_VERSION;
  id: string;
  type: "res";
  ok: false;
  error: {
    code: ErrorCode;
    retryable: boolean;
    message?: string;
    upstreamStatus?: number;
  };
}

export type ResultEnvelope<T = unknown> = ResultOk<T> | ResultError;

export interface EventEnvelope<T = unknown> {
  v: typeof PROTOCOL_VERSION;
  type: "evt";
  topic: EventTopic;
  seq: number;
  ts: number;
  data: T;
}

export function makeError(
  id: string,
  code: ErrorCode,
  message?: string,
  upstreamStatus?: number,
): ResultError {
  return {
    v: PROTOCOL_VERSION,
    id,
    type: "res",
    ok: false,
    error: {
      code,
      retryable: RETRYABLE[code],
      ...(message ? { message } : {}),
      ...(upstreamStatus != null ? { upstreamStatus } : {}),
    },
  };
}

export interface RuntimeState {
  state: "absent" | "installing" | "ready" | "failed";
  reason?: string;
  version?: string;
}

export type ServerStateName =
  | "stopped"
  | "starting"
  | "healthy"
  | "degraded"
  | "backoff"
  | "crashLoop";

export interface ServerState {
  state: ServerStateName;
  version?: string;
  backoffUntil?: number;
}

export interface BridgeTransport {
  send(command: CommandEnvelope): void;
  subscribe(handler: (message: ResultEnvelope | EventEnvelope) => void): () => void;
  close(): void;
}
