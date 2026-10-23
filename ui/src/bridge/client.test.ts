import assert from "node:assert/strict";
import { test } from "node:test";

import { GatewayClient, type GatewayStatus } from "./client.ts";

const encoder = new TextEncoder();

function frame(id: string, type: string, properties: unknown = {}): string {
  return `data: ${JSON.stringify({ id, type, properties })}\n\n`;
}

function streamResponse(chunks: string[]): Response {
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }),
    { status: 200, headers: { "content-type": "text/event-stream" } },
  );
}

function closedAfterAbort(_ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((_, reject) => {
    signal.addEventListener("abort", () => reject(signal.reason), { once: true });
  });
}

test("sends Basic auth and a directory-scoped /event URL", async () => {
  let captured: { url: URL; headers: Headers } | undefined;
  const client = new GatewayClient({
    baseUrl: "http://127.0.0.1:43123",
    password: "s3cr3t",
    directory: "/data/user/0/app/files/workspace a",
    fetch: async (input, init) => {
      captured = {
        url: new URL(input instanceof URL ? input : String(input)),
        headers: new Headers(init?.headers),
      };
      return streamResponse([frame("evt_conn", "server.connected")]);
    },
    fetchSnapshot: async () => ({ messages: [] }),
    sleep: closedAfterAbort,
  });

  client.start();
  while (!captured) await new Promise((resolve) => setTimeout(resolve, 1));
  await client.stop();

  assert.equal(captured.url.pathname, "/event");
  assert.equal(
    captured.url.searchParams.get("directory"),
    "/data/user/0/app/files/workspace a",
  );
  assert.equal(
    captured.headers.get("authorization"),
    `Basic ${btoa("opencode:s3cr3t")}`,
  );
  assert.equal(captured.headers.get("accept"), "text/event-stream");
});

test("backs off exponentially with full jitter after failures", async () => {
  let attempts = 0;
  const delays: number[] = [];
  const seen: GatewayStatus[] = [];
  const client = new GatewayClient({
    baseUrl: "http://127.0.0.1:43123",
    password: "secret",
    directory: "/workspace",
    fetch: async () => {
      attempts += 1;
      if (attempts < 4) throw new Error("connection refused");
      return streamResponse([]);
    },
    fetchSnapshot: async () => ({ messages: [] }),
    backoff: { initialMs: 100, maxMs: 1_000, factor: 2 },
    random: () => 0.5,
    onStatus: (status) => seen.push(status),
    sleep: async (ms) => {
      delays.push(ms);
      if (attempts >= 4) throw new Error("stop");
    },
  });

  client.start();
  while (attempts < 4) await new Promise((resolve) => setTimeout(resolve, 1));
  await client.stop();

  assert.deepEqual(delays.slice(0, 3), [50, 100, 200]);
  assert.deepEqual(seen, ["connecting", "reconnecting", "open", "stopped"]);
});

test("wires server.connected to the snapshot resync", async () => {
  let snapshotCalls = 0;
  const statuses: GatewayStatus[] = [];
  let resolveOpen: (() => void) | undefined;
  const opened = new Promise<void>((resolve) => {
    resolveOpen = resolve;
  });

  const client = new GatewayClient({
    baseUrl: "http://127.0.0.1:43123",
    password: "secret",
    directory: "/workspace",
    fetch: async () =>
      streamResponse([
        frame("evt_conn", "server.connected"),
        frame("evt_part", "message.part.updated", {
          part: {
            id: "prt_1",
            messageID: "msg_1",
            text: "hello",
            time: { updated: 1 },
          },
        }),
      ]),
    fetchSnapshot: async () => {
      snapshotCalls += 1;
      return { messages: [] };
    },
    onStatus: (status) => {
      statuses.push(status);
      if (status === "open" && snapshotCalls > 0) resolveOpen?.();
    },
    sleep: closedAfterAbort,
  });

  client.start();
  await opened;
  await client.stop();

  assert.equal(snapshotCalls, 1);
  assert.ok(statuses.includes("resyncing"));
  assert.deepEqual(client.model.snapshot(), {
    messages: [
      {
        id: "msg_1",
        parts: [{ id: "prt_1", text: "hello", revision: 1 }],
      },
    ],
  });
});

test("aborts and reconnects when the stream goes idle", async () => {
  let requests = 0;
  const errors: unknown[] = [];
  const client = new GatewayClient({
    baseUrl: "http://127.0.0.1:43123",
    password: "secret",
    directory: "/workspace",
    fetch: async (_input, init) => {
      requests += 1;
      const signal = init?.signal as AbortSignal;
      const body = new ReadableStream({
        start(controller) {
          signal.addEventListener(
            "abort",
            () => controller.error(signal.reason),
            { once: true },
          );
        },
      });
      return new Response(body, {
        status: 200,
        headers: { "content-type": "text/event-stream" },
      });
    },
    fetchSnapshot: async () => ({ messages: [] }),
    idleTimeoutMs: 25,
    onError: (error) => errors.push(error),
    sleep: async () => {},
  });

  client.start();
  while (requests < 2) await new Promise((resolve) => setTimeout(resolve, 1));
  await client.stop();

  assert.ok(errors.length >= 1);
  assert.match(String(errors[0]), /idle timeout/);
  assert.equal(client.currentStatus, "stopped");
});
