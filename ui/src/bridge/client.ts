import { SseParser, parseServerEvent } from "./sse.ts";
import {
  ResyncController,
  type SessionEvent,
  type TranscriptSnapshot,
} from "./resync.ts";
import { TIMEOUTS } from "./protocol.ts";

export type GatewayStatus =
  | "connecting"
  | "open"
  | "resyncing"
  | "reconnecting"
  | "stopped";

export interface BackoffPolicy {
  initialMs: number;
  maxMs: number;
  factor: number;
}

export const DEFAULT_BACKOFF: BackoffPolicy = {
  initialMs: 500,
  maxMs: 30_000,
  factor: 2,
};

export interface GatewayClientOptions {
  baseUrl: string;
  password: string;
  directory: string;
  fetch: typeof fetch;
  fetchSnapshot: () => Promise<TranscriptSnapshot>;
  onEvent?: (event: SessionEvent) => void;
  onStatus?: (status: GatewayStatus) => void;
  onError?: (error: unknown) => void;
  backoff?: Partial<BackoffPolicy>;
  idleTimeoutMs?: number;
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  random?: () => number;
}

function defaultSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

function basicAuth(password: string): string {
  return `Basic ${btoa(`opencode:${password}`)}`;
}

export class GatewayClient {
  private readonly resync = new ResyncController();
  private readonly abort = new AbortController();
  private readonly backoff: BackoffPolicy;
  private readonly sleep: (ms: number, signal: AbortSignal) => Promise<void>;
  private readonly random: () => number;
  private readonly idleTimeoutMs: number;
  private loopPromise: Promise<void> | undefined;
  private status: GatewayStatus = "stopped";

  private readonly options: GatewayClientOptions;

  constructor(options: GatewayClientOptions) {
    this.options = options;
    this.backoff = { ...DEFAULT_BACKOFF, ...options.backoff };
    this.sleep = options.sleep ?? defaultSleep;
    this.random = options.random ?? Math.random;
    this.idleTimeoutMs = options.idleTimeoutMs ?? TIMEOUTS.sseIdleWatchdog;
  }

  get model(): ResyncController["model"] {
    return this.resync.model;
  }

  get currentStatus(): GatewayStatus {
    return this.status;
  }

  start(): void {
    if (this.loopPromise) return;
    this.loopPromise = this.loop();
  }

  async stop(): Promise<void> {
    this.abort.abort();
    await this.loopPromise;
    this.setStatus("stopped");
  }

  private setStatus(status: GatewayStatus): void {
    if (this.status === status) return;
    this.status = status;
    this.options.onStatus?.(status);
  }

  private delay(attempt: number): number {
    const cap = Math.min(
      this.backoff.maxMs,
      this.backoff.initialMs * this.backoff.factor ** attempt,
    );
    return Math.round(this.random() * cap);
  }

  private async loop(): Promise<void> {
    let attempt = 0;
    while (!this.abort.signal.aborted) {
      this.setStatus(attempt === 0 ? "connecting" : "reconnecting");
      const connection = new AbortController();
      const onStop = () => connection.abort(this.abort.signal.reason);
      this.abort.signal.addEventListener("abort", onStop, { once: true });
      let timer: ReturnType<typeof setTimeout> | undefined;
      const onActivity = () => {
        if (this.idleTimeoutMs <= 0) return;
        clearTimeout(timer);
        timer = setTimeout(
          () => connection.abort(new Error("SSE idle timeout")),
          this.idleTimeoutMs,
        );
      };
      try {
        onActivity();
        const response = await this.request(connection.signal);
        this.setStatus("open");
        await this.consume(response, onActivity);
        attempt = 0;
      } catch (error) {
        if (this.abort.signal.aborted) break;
        this.options.onError?.(error);
        attempt += 1;
      } finally {
        clearTimeout(timer);
        this.abort.signal.removeEventListener("abort", onStop);
      }
      if (this.abort.signal.aborted) break;
      try {
        await this.sleep(this.delay(attempt - 1), this.abort.signal);
      } catch {
        break;
      }
    }
    this.setStatus("stopped");
  }

  private async request(signal: AbortSignal): Promise<Response> {
    const url = new URL("/event", this.options.baseUrl);
    url.searchParams.set("directory", this.options.directory);
    const response = await this.options.fetch(url, {
      headers: {
        Accept: "text/event-stream",
        Authorization: basicAuth(this.options.password),
      },
      signal,
    });
    if (!response.ok) {
      throw new Error(`gateway SSE failed: ${response.status}`);
    }
    if (!response.body) {
      throw new Error("gateway SSE response has no body");
    }
    return response;
  }

  private async consume(
    response: Response,
    onActivity: () => void,
  ): Promise<void> {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) return;
        onActivity();
        for (const frame of parser.feed(decoder.decode(value, { stream: true }))) {
          const event = parseServerEvent(frame);
          if (!event) continue;
          this.options.onEvent?.(event);
          const outcome = this.resync.receive(event);
          if (outcome === "resync") {
            this.setStatus("resyncing");
            await this.resync.finishResync(this.options.fetchSnapshot);
            this.setStatus("open");
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
  }
}
