import assert from "node:assert/strict";
import { test } from "node:test";

import { SseParser, parseServerEvent } from "./sse.ts";

test("parses a single data frame", () => {
  const parser = new SseParser();
  const frames = parser.feed(
    'data: {"id":"evt_1","type":"server.connected","properties":{}}\n\n',
  );
  assert.equal(frames.length, 1);
  assert.equal(frames[0].data, '{"id":"evt_1","type":"server.connected","properties":{}}');
  const event = parseServerEvent(frames[0]);
  assert.deepEqual(event, {
    id: "evt_1",
    type: "server.connected",
    properties: {},
  });
});

test("reassembles frames split across arbitrary chunk boundaries", () => {
  const payload =
    'data: {"id":"evt_2","type":"server.heartbeat","properties":{}}\r\n\r\n';
  const parser = new SseParser();
  const frames = [];
  for (const character of payload) {
    frames.push(...parser.feed(character));
  }
  assert.equal(frames.length, 1);
  assert.equal(parseServerEvent(frames[0])?.type, "server.heartbeat");
});

test("supports comments, event names, multi-line data and retry", () => {
  const parser = new SseParser();
  const frames = parser.feed(
    ": keep-alive\nretry: 2500\nevent: message\ndata: first\ndata: second\nid: 42\n\n",
  );
  assert.deepEqual(frames, [
    { data: "first\nsecond", event: "message", id: "42", retry: 2500 },
  ]);
});

test("ignores blank frames and malformed JSON", () => {
  const parser = new SseParser();
  assert.deepEqual(parser.feed("\n\n: comment\n\n"), []);
  const frames = parser.feed("data: not-json\n\n");
  assert.equal(frames.length, 1);
  assert.equal(parseServerEvent(frames[0]), null);
});

test("returns multiple frames from one chunk", () => {
  const parser = new SseParser();
  const frames = parser.feed(
    'data: {"id":"evt_a","type":"server.connected","properties":{}}\n\n' +
      'data: {"id":"evt_b","type":"server.heartbeat","properties":{}}\n\n',
  );
  assert.deepEqual(
    frames.map((frame) => parseServerEvent(frame)?.type),
    ["server.connected", "server.heartbeat"],
  );
});

test("a frame is only dispatched on a blank line", () => {
  const parser = new SseParser();
  assert.deepEqual(parser.feed("data: pending\n"), []);
  assert.equal(parser.feed("\n").length, 1);
});
