import assert from "node:assert/strict";
import { test } from "node:test";

import {
  PROTOCOL_VERSION,
  RETRYABLE,
  TIMEOUTS,
  makeError,
  type ErrorCode,
} from "./protocol.ts";

const CODES: ErrorCode[] = [
  "E_RUNTIME_DOWN",
  "E_AUTH",
  "E_PATH_DENIED",
  "E_CONFLICT",
  "E_TOO_LARGE",
  "E_TIMEOUT",
  "E_UNSUPPORTED_VERSION",
  "E_UPSTREAM",
  "E_CANCELLED",
];

test("protocol version and retry table are complete", () => {
  assert.equal(PROTOCOL_VERSION, 1);
  assert.deepEqual(Object.keys(RETRYABLE).sort(), [...CODES].sort());
  assert.equal(RETRYABLE.E_AUTH, false);
  assert.equal(RETRYABLE.E_RUNTIME_DOWN, true);
});

test("error envelopes derive retryability from the code", () => {
  assert.deepEqual(makeError("req_1", "E_TIMEOUT"), {
    v: 1,
    id: "req_1",
    type: "res",
    ok: false,
    error: { code: "E_TIMEOUT", retryable: true },
  });
  assert.deepEqual(makeError("req_2", "E_AUTH", "denied", 401), {
    v: 1,
    id: "req_2",
    type: "res",
    ok: false,
    error: {
      code: "E_AUTH",
      retryable: false,
      message: "denied",
      upstreamStatus: 401,
    },
  });
});

test("SSE watchdog matches the spec", () => {
  assert.equal(TIMEOUTS.sseIdleWatchdog, 60_000);
  assert.ok(TIMEOUTS.health < TIMEOUTS.listGet);
  assert.ok(TIMEOUTS.promptAsync <= TIMEOUTS.fileRead);
});
