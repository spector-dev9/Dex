/**
 * Server-Sent Events parser for the gateway's /event stream.
 *
 * The pinned server sends `data: {json}` frames with no SSE `event:`
 * name; event identity lives in the JSON payload (`evt_…`). The parser
 * still implements the full framing rules so comments, multi-line data
 * and retry fields cannot corrupt the stream.
 */

export interface SseFrame {
  id?: string;
  event?: string;
  data: string;
  retry?: number;
}

export class SseParser {
  private buffer = "";
  private dataLines: string[] = [];
  private eventName: string | undefined;
  private lastId: string | undefined;
  private retry: number | undefined;

  /** Feed a decoded text chunk; returns every frame completed by it. */
  feed(chunk: string): SseFrame[] {
    this.buffer += chunk;
    const frames: SseFrame[] = [];
    let newline = this.buffer.indexOf("\n");
    while (newline >= 0) {
      let line = this.buffer.slice(0, newline);
      this.buffer = this.buffer.slice(newline + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      this.consumeLine(line, frames);
      newline = this.buffer.indexOf("\n");
    }
    return frames;
  }

  /** Last event id seen, for diagnostics; the server does not use it. */
  get lastEventId(): string | undefined {
    return this.lastId;
  }

  private consumeLine(line: string, frames: SseFrame[]): void {
    if (line === "") {
      this.dispatch(frames);
      return;
    }
    if (line.startsWith(":")) return;

    const colon = line.indexOf(":");
    const field = colon >= 0 ? line.slice(0, colon) : line;
    let value = colon >= 0 ? line.slice(colon + 1) : "";
    if (value.startsWith(" ")) value = value.slice(1);

    switch (field) {
      case "data":
        this.dataLines.push(value);
        break;
      case "event":
        this.eventName = value;
        break;
      case "id":
        if (!value.includes("\0")) this.lastId = value;
        break;
      case "retry": {
        const parsed = Number(value);
        if (Number.isSafeInteger(parsed) && parsed >= 0) this.retry = parsed;
        break;
      }
      default:
        break;
    }
  }

  private dispatch(frames: SseFrame[]): void {
    if (this.dataLines.length === 0) {
      this.eventName = undefined;
      return;
    }
    frames.push({
      data: this.dataLines.join("\n"),
      ...(this.eventName !== undefined ? { event: this.eventName } : {}),
      ...(this.lastId !== undefined ? { id: this.lastId } : {}),
      ...(this.retry !== undefined ? { retry: this.retry } : {}),
    });
    this.dataLines = [];
    this.eventName = undefined;
  }
}

export interface ServerEvent {
  id: string;
  type: string;
  properties: Record<string, unknown>;
}

/** Parse an SSE data payload into the server's event envelope. */
export function parseServerEvent(frame: SseFrame): ServerEvent | null {
  let value: unknown;
  try {
    value = JSON.parse(frame.data);
  } catch {
    return null;
  }
  if (!value || typeof value !== "object") return null;
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.id !== "string" || typeof candidate.type !== "string") {
    return null;
  }
  const properties =
    candidate.properties && typeof candidate.properties === "object"
      ? (candidate.properties as Record<string, unknown>)
      : {};
  return { id: candidate.id, type: candidate.type, properties };
}
