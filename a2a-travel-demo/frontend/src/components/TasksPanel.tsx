"use client";

/* eslint-disable @typescript-eslint/no-explicit-any */
import { useCallback, useEffect, useState } from "react";
import { isFinalState, normState, safeParse, stateLabel } from "@/lib/a2a";

interface Row {
  id: string;
  state: string;
  text: string;
  ts: string;
}

function toRows(json: any): Row[] {
  const tasks: any[] = json?.result?.tasks ?? [];
  return tasks
    .map((t) => ({
      id: t.id as string,
      state: normState(t.status?.state),
      text: ((t.status?.message?.parts ?? []) as any[]).map((p) => p.text ?? "").join(""),
      ts: (t.status?.timestamp as string) ?? "",
    }))
    .sort((a, b) => b.ts.localeCompare(a.ts));
}

export default function TasksPanel({ onOpen }: { onOpen: (taskId: string) => void }) {
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/tasks", { cache: "no-store" });
      const json = safeParse(await res.text());
      if (json?.error) setError(json.error.message ?? "ListTasks failed");
      setRows(toRows(json));
    } catch (e: any) {
      setError(e?.message ?? "Network error");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="tasks">
      <div className="toolbar">
        <span className="muted small">
          A2A <code>ListTasks</code> · open a task to <code>GetTask</code> it, or <code>SubscribeToTask</code> if it is still running
        </span>
        <span style={{ flex: 1 }} />
        <button className="btn ghost" onClick={load} disabled={loading}>
          {loading ? "Loading…" : "Refresh"}
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      {!error && rows.length === 0 && <p className="muted empty">No tasks yet.</p>}
      <ul className="events">
        {rows.map((r) => (
          <li key={r.id}>
            <span className={`pill state-${r.state}`}>{stateLabel(r.state)}</span>
            <span className="what">
              <code>{r.id.slice(0, 8)}</code> {r.text}
            </span>
            <span className="time">{r.ts ? new Date(r.ts).toLocaleTimeString([], { hour12: false }) : ""}</span>
            <button className="btn ghost" onClick={() => onOpen(r.id)}>
              {isFinalState(r.state) ? "Open" : "Attach"}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
