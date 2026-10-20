import { describe, expect, it } from "vitest";
import type { TraceEvent } from "@/lib/a2a";
import { activeEdges, framesFrom, nodeStates } from "@/lib/derive";

let seq = 0;
const ev = (agent: string, kind: TraceEvent["kind"], payload: unknown, extra: Partial<TraceEvent> = {}): TraceEvent => ({
  seq: ++seq,
  ts: 1000,
  agent,
  kind,
  direction: kind === "rpc-request" || kind === "client-event" ? "in" : "out",
  method: null,
  peer: null,
  payload: typeof payload === "string" ? payload : JSON.stringify(payload),
  ...extra,
});
const statusFrame = (state: string) => ({ result: { statusUpdate: { taskId: "t", status: { state } } } });

describe("framesFrom / nodeStates", () => {
  it("decodes only what agents sent, and tracks the latest state per agent", () => {
    const events = [
      ev("orchestrator-agent", "rpc-request", { method: "SendStreamingMessage" }), // inbound: ignored
      ev("flight-agent", "sse-frame", statusFrame("TASK_STATE_WORKING")),
      ev("flight-agent", "sse-frame", statusFrame("TASK_STATE_COMPLETED")),
      ev("hotel-agent", "sse-frame", statusFrame("TASK_STATE_WORKING")),
      ev("hotel-agent", "note", "not a frame"),
    ];
    const frames = framesFrom(events);
    expect(frames).toHaveLength(3);
    expect(nodeStates(frames)).toEqual({ "flight-agent": "completed", "hotel-agent": "working" });
  });

  it("skips undecodable payloads", () => {
    expect(framesFrom([ev("flight-agent", "sse-frame", "not json")])).toHaveLength(0);
  });
});

describe("activeEdges", () => {
  const now = 2000;
  it("lights the UI edge for orchestrator traffic and agent edges for delegation + sub-agent traffic", () => {
    const events = [
      ev("orchestrator-agent", "rpc-request", {}, { ts: 1900 }),
      ev("orchestrator-agent", "delegation", "x", { ts: 1900, peer: "flight-agent" }),
      ev("hotel-agent", "sse-frame", statusFrame("TASK_STATE_WORKING"), { ts: 1950 }),
    ];
    const active = activeEdges(events, now);
    expect(active.has("ui-orchestrator-agent")).toBe(true);
    expect(active.has("orchestrator-agent-flight-agent")).toBe(true);
    expect(active.has("orchestrator-agent-hotel-agent")).toBe(true);
    expect(active.has("orchestrator-agent-activities-agent")).toBe(false);
  });

  it("lets edges go quiet after the window", () => {
    const events = [ev("flight-agent", "sse-frame", statusFrame("TASK_STATE_WORKING"), { ts: 100 })];
    expect(activeEdges(events, now).size).toBe(0);
  });
});
