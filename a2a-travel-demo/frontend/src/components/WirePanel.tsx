"use client";

import { useMemo, useState } from "react";
import { AGENTS, agentMeta } from "@/lib/agents";
import { TraceEvent, safeParse } from "@/lib/a2a";

const KIND_LABEL: Record<string, string> = {
  "rpc-request": "JSON-RPC request",
  "rpc-response": "JSON-RPC response",
  "sse-frame": "SSE frame",
  delegation: "delegation",
  "client-event": "client event",
  note: "note",
};

function pretty(payload: string) {
  const json = safeParse(payload);
  return json ? JSON.stringify(json, null, 2) : payload;
}

function method(ev: TraceEvent): string {
  if (ev.method) return ev.method;
  const json = safeParse(ev.payload);
  return json?.method ?? "";
}

function summary(ev: TraceEvent): string {
  if (ev.kind === "delegation" || ev.kind === "client-event" || ev.kind === "note") return ev.payload.slice(0, 110);
  const json = safeParse(ev.payload);
  if (!json) return "";
  if (json.method) return json.method;
  const r = json.result ?? {};
  if (json.error) return `error: ${json.error.message}`;
  const key = Object.keys(r)[0];
  const state = r.statusUpdate?.status?.state ?? r.task?.status?.state;
  return [key, state].filter(Boolean).join(" · ");
}

export default function WirePanel({ events, onClear }: { events: TraceEvent[]; onClear: () => void }) {
  const [filter, setFilter] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());

  const rows = useMemo(() => (filter ? events.filter((e) => e.agent === filter) : events).slice(-250).reverse(), [events, filter]);

  return (
    <div className="wire">
      <div className="toolbar">
        <button className={`chip ${filter === null ? "on" : ""}`} onClick={() => setFilter(null)}>
          all agents
        </button>
        {AGENTS.map((a) => (
          <button key={a.name} className={`chip ${filter === a.name ? "on" : ""}`} onClick={() => setFilter(a.name)}>
            {a.emoji} {a.name.replace("-agent", "")}
          </button>
        ))}
        <span style={{ flex: 1 }} />
        <button className="btn ghost" onClick={onClear}>
          Clear
        </button>
      </div>

      {rows.length === 0 && <p className="muted empty">No traffic yet. Send a trip request to see the raw A2A messages.</p>}

      <ul className="wire-list">
        {rows.map((ev) => {
          const key = `${ev.agent}:${ev.seq}`;
          const isOpen = open.has(key);
          const meta = agentMeta(ev.agent);
          return (
            <li key={key}>
              <button
                className="wire-row"
                onClick={() =>
                  setOpen((prev) => {
                    const next = new Set(prev);
                    if (next.has(key)) next.delete(key);
                    else next.add(key);
                    return next;
                  })
                }
              >
                <span className="time">{new Date(ev.ts).toLocaleTimeString([], { hour12: false })}</span>
                <span className="tag" style={{ background: meta?.color }}>
                  {ev.agent.replace("-agent", "")}
                </span>
                <span className={`dir ${ev.direction}`}>{ev.direction === "in" ? "⇦ in" : "out ⇨"}</span>
                <span className={`kind k-${ev.kind}`}>{KIND_LABEL[ev.kind] ?? ev.kind}</span>
                <span className="sum">{summary(ev) || method(ev)}</span>
              </button>
              {isOpen && <pre className="json">{pretty(ev.payload)}</pre>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
