import assert from "node:assert/strict";
import { test } from "node:test";

import {
  ResyncController,
  TranscriptModel,
  type SessionEvent,
  type TranscriptSnapshot,
} from "./resync.ts";

function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function partEvent(
  sequence: number,
  message: number,
  part: number,
  revision: number,
): SessionEvent {
  return {
    id: `evt_${sequence}`,
    type: "message.part.updated",
    properties: {
      part: {
        id: `prt_${message}_${part}`,
        messageID: `msg_${message}`,
        type: "text",
        text: `message ${message} part ${part} revision ${revision}`,
        time: { updated: revision },
      },
    },
  };
}

function buildEvents(messages = 20, parts = 5, revisions = 3): SessionEvent[] {
  const events: SessionEvent[] = [];
  let sequence = 0;
  for (let message = 0; message < messages; message += 1) {
    for (let part = 0; part < parts; part += 1) {
      for (let revision = 1; revision <= revisions; revision += 1) {
        sequence += 1;
        events.push(partEvent(sequence, message, part, revision));
      }
    }
  }
  return events;
}

function snapshotAfter(events: SessionEvent[], count: number): TranscriptSnapshot {
  const model = new TranscriptModel();
  for (const event of events.slice(0, count)) model.apply(event);
  return model.snapshot();
}

test("500 drop scenarios resync to the authoritative transcript", async () => {
  const events = buildEvents();
  const reference = snapshotAfter(events, events.length);
  const random = mulberry32(0xc0ffee);

  for (let trial = 0; trial < 500; trial += 1) {
    const dropStart = Math.floor(random() * events.length);
    const dropCount = Math.min(
      events.length - dropStart,
      1 + Math.floor(random() * 60),
    );
    const reconnectAt = dropStart + dropCount;

    const controller = new ResyncController();

    // Initial connect: fetch an empty snapshot, then stream live.
    controller.receive({
      id: `evt_conn_${trial}_initial`,
      type: "server.connected",
      properties: {},
    });
    await controller.finishResync(() => ({ messages: [] }));

    for (const event of events.slice(0, dropStart)) {
      assert.equal(controller.receive(event), "applied");
    }

    // Duplicate delivery before the drop must be ignored.
    if (dropStart > 0) {
      assert.equal(controller.receive(events[dropStart - 1]), "duplicate");
    }

    // Connection drops; events dropStart..reconnectAt are lost.
    controller.receive({
      id: `evt_conn_${trial}_reconnect`,
      type: "server.connected",
      properties: {},
    });

    // Live events during resync are buffered, not applied.
    for (const event of events.slice(reconnectAt)) {
      assert.equal(controller.receive(event), "buffered");
    }

    // Authoritative snapshot was taken when the reconnect happened.
    const authoritative = snapshotAfter(events, reconnectAt);
    await controller.finishResync(() => authoritative);

    assert.equal(controller.bufferedCount, 0);
    assert.equal(controller.resyncs, 2);
    assert.deepEqual(
      controller.model.snapshot(),
      reference,
      `trial ${trial} drop ${dropStart}..${reconnectAt}`,
    );
  }
});

test("stale buffered revisions cannot regress a newer snapshot", async () => {
  const controller = new ResyncController();
  controller.receive({
    id: "evt_conn",
    type: "server.connected",
    properties: {},
  });

  // Snapshot already contains revision 3.
  const authoritative: TranscriptSnapshot = {
    messages: [
      {
        id: "msg_1",
        parts: [{ id: "prt_1_1", text: "revision 3", revision: 3 }],
      },
    ],
  };
  controller.receive(partEvent(2, 1, 1, 2));
  await controller.finishResync(() => authoritative);

  assert.deepEqual(controller.model.snapshot(), authoritative);
});

test("events before any connect are applied live", () => {
  const controller = new ResyncController();
  assert.equal(controller.receive(partEvent(1, 1, 1, 1)), "applied");
  assert.equal(controller.model.snapshot().messages.length, 1);
});
