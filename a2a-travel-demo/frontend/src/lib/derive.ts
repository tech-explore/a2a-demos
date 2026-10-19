import { Frame, TraceEvent, parseFrame, safeParse } from "./a2a";

export interface FrameEvent {
  ev: TraceEvent;
  frame: Frame;
}

/** Everything an agent *sent* (SSE frames + blocking responses), decoded into A2A frames. */
export function framesFrom(events: TraceEvent[]): FrameEvent[] {
  const out: FrameEvent[] = [];
  for (const ev of events) {
    if ((ev.kind === "sse-frame" || ev.kind === "rpc-response") && ev.direction === "out") {
      const frame = parseFrame(safeParse(ev.payload));
      if (frame) out.push({ ev, frame });
    }
  }
  return out;
}

/** Latest task state per agent. */
export function nodeStates(frames: FrameEvent[]): Record<string, string> {
  const states: Record<string, string> = {};
  for (const { ev, frame } of frames) {
    if (frame.type === "status" || frame.type === "task") states[ev.agent] = frame.state;
  }
  return states;
}

export type EdgeId = "ui-orchestrator-agent" | "orchestrator-agent-flight-agent" | "orchestrator-agent-hotel-agent" | "orchestrator-agent-activities-agent";

/** Which graph edges saw traffic recently (drives the flowing-dots animation). */
export function activeEdges(events: TraceEvent[], now: number, windowMs = 1400): Set<EdgeId> {
  const active = new Set<EdgeId>();
  for (let i = events.length - 1; i >= 0; i--) {
    const ev = events[i];
    if (now - ev.ts > windowMs) break;
    if (ev.agent === "orchestrator-agent") {
      if (ev.kind === "rpc-request" || ev.kind === "sse-frame" || ev.kind === "rpc-response") active.add("ui-orchestrator-agent");
      if ((ev.kind === "delegation" || ev.kind === "client-event") && ev.peer) {
        active.add(`orchestrator-agent-${ev.peer}` as EdgeId);
      }
    } else {
      active.add(`orchestrator-agent-${ev.agent}` as EdgeId);
    }
  }
  return active;
}
