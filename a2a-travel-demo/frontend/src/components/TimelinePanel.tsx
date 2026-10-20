"use client";

import { agentMeta } from "@/lib/agents";
import { stateLabel } from "@/lib/a2a";
import type { FrameEvent } from "@/lib/derive";

const LIFECYCLE = ["submitted", "working", "input-required", "completed"];

function dotClass(state: string) {
  if (state === "completed") return "ok";
  if (state === "failed" || state === "rejected") return "bad";
  if (state === "canceled") return "muted";
  if (state === "input-required") return "warn";
  return "busy";
}

export default function TimelinePanel({ frames }: { frames: FrameEvent[] }) {
  const orchestrator = frames.filter((f) => f.ev.agent === "orchestrator-agent");
  const seen = new Set<string>();
  let last = "";
  for (const { frame } of orchestrator) {
    if (frame.type === "status" || frame.type === "task") {
      seen.add(frame.state);
      last = frame.state;
    }
  }
  const terminal = ["failed", "rejected", "canceled"].includes(last) ? last : null;

  const rows = frames
    .filter((f) => f.frame.type === "status" || f.frame.type === "artifact" || f.frame.type === "task")
    .slice(-120)
    .reverse();

  return (
    <div className="timeline">
      <div className="lifecycle">
        {LIFECYCLE.map((s, i) => (
          <div key={s} className={`step ${seen.has(s) ? "done" : ""} ${last === s ? "current" : ""}`}>
            <span className="dot" />
            <span>{stateLabel(s)}</span>
            {i < LIFECYCLE.length - 1 && <span className="bar" />}
          </div>
        ))}
        {terminal && <div className={`step done current ${dotClass(terminal)}`}><span className="dot" /><span>{terminal}</span></div>}
      </div>
      <p className="muted small">Orchestrator task lifecycle · below: every status/artifact update from every agent</p>

      {rows.length === 0 && <p className="muted empty">Waiting for a task…</p>}
      <ul className="events">
        {rows.map(({ ev, frame }) => {
          const meta = agentMeta(ev.agent);
          if (frame.type === "artifact") {
            return (
              <li key={`${ev.agent}:${ev.seq}`}>
                <span className="dot art" />
                <span className="who" style={{ color: meta?.color }}>{meta?.emoji} {ev.agent.replace("-agent", "")}</span>
                <span className="what">artifact <b>{frame.artifact.name}</b></span>
              </li>
            );
          }
          const state = frame.type === "status" || frame.type === "task" ? frame.state : "";
          const text = frame.type === "status" || frame.type === "task" ? frame.text : "";
          return (
            <li key={`${ev.agent}:${ev.seq}`}>
              <span className={`dot ${dotClass(state)}`} />
              <span className="who" style={{ color: meta?.color }}>{meta?.emoji} {ev.agent.replace("-agent", "")}</span>
              <span className="what">
                <b>{stateLabel(state)}</b>
                {text ? ` — ${text}` : ""}
              </span>
              <span className="time">{new Date(ev.ts).toLocaleTimeString([], { hour12: false })}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
