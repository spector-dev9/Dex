/**
 * SSE resync algorithm (spec S4/STR-5).
 *
 * Invariant: after any number of dropped connections, the model equals
 * the server's authoritative transcript plus every event delivered
 * after the reconnect.
 *
 * Algorithm:
 *   1. On `server.connected`, enter buffering mode; do not apply live
 *      events to the model yet.
 *   2. Fetch the authoritative transcript (GET /session/:id/message).
 *   3. Replace the model with the snapshot.
 *   4. Replay buffered events in arrival order, deduplicated by event id
 *      and guarded by per-part revision.
 *   5. Leave buffering mode; apply subsequent events live.
 *
 * Duplicate events are ignored by id. Stale part updates (lower
 * revision than the model) are ignored, so a snapshot that is newer
 * than a buffered event cannot regress it.
 */

import type { ServerEvent } from "./sse.ts";

export type SessionEvent = ServerEvent;

export interface TranscriptPart {
  id: string;
  text: string;
  revision: number;
}

export interface TranscriptMessage {
  id: string;
  parts: TranscriptPart[];
}

export interface TranscriptSnapshot {
  messages: TranscriptMessage[];
}

interface PartRecord {
  id: string;
  text: string;
  revision: number;
}

interface MessageRecord {
  id: string;
  parts: Map<string, PartRecord>;
}

export class TranscriptModel {
  private messages = new Map<string, MessageRecord>();
  private applied = 0;

  get appliedEvents(): number {
    return this.applied;
  }

  apply(event: SessionEvent): void {
    if (event.type !== "message.part.updated") return;
    const part = event.properties.part;
    if (!part || typeof part !== "object") return;
    const value = part as Record<string, unknown>;
    if (
      typeof value.id !== "string" ||
      typeof value.messageID !== "string" ||
      typeof value.text !== "string"
    ) {
      return;
    }
    const time = value.time;
    const revision =
      time && typeof time === "object" && typeof (time as Record<string, unknown>).updated === "number"
        ? ((time as Record<string, unknown>).updated as number)
        : 0;

    let message = this.messages.get(value.messageID);
    if (!message) {
      message = { id: value.messageID, parts: new Map() };
      this.messages.set(value.messageID, message);
    }
    const existing = message.parts.get(value.id);
    if (existing && existing.revision > revision) return;
    message.parts.set(value.id, { id: value.id, text: value.text, revision });
    this.applied += 1;
  }

  replace(snapshot: TranscriptSnapshot): void {
    this.messages.clear();
    for (const message of snapshot.messages) {
      const record: MessageRecord = { id: message.id, parts: new Map() };
      for (const part of message.parts) {
        record.parts.set(part.id, { ...part });
      }
      this.messages.set(message.id, record);
    }
  }

  snapshot(): TranscriptSnapshot {
    return {
      messages: [...this.messages.values()]
        .sort((left, right) => left.id.localeCompare(right.id))
        .map((message) => ({
          id: message.id,
          parts: [...message.parts.values()]
            .sort((left, right) => left.id.localeCompare(right.id))
            .map((part) => ({ ...part })),
        })),
    };
  }
}

export type ReceiveOutcome = "applied" | "buffered" | "resync" | "duplicate";

export class ResyncController {
  readonly model = new TranscriptModel();
  private readonly seen = new Set<string>();
  private buffer: SessionEvent[] = [];
  private buffering = false;
  private resyncCount = 0;

  get resyncs(): number {
    return this.resyncCount;
  }

  get bufferedCount(): number {
    return this.buffer.length;
  }

  receive(event: SessionEvent): ReceiveOutcome {
    if (this.seen.has(event.id)) return "duplicate";
    this.seen.add(event.id);

    if (event.type === "server.connected") {
      this.buffering = true;
      this.buffer = [];
      this.resyncCount += 1;
      return "resync";
    }
    if (this.buffering) {
      this.buffer.push(event);
      return "buffered";
    }
    this.model.apply(event);
    return "applied";
  }

  async finishResync(
    fetchSnapshot: () => Promise<TranscriptSnapshot> | TranscriptSnapshot,
  ): Promise<void> {
    const snapshot = await fetchSnapshot();
    this.model.replace(snapshot);
    for (const event of this.buffer) this.model.apply(event);
    this.buffer = [];
    this.buffering = false;
  }
}
