"use client";

/* eslint-disable @typescript-eslint/no-explicit-any */
import { useEffect, useRef, useState } from "react";
import { Frame, isFinalState, parseFrame, readSSE, safeParse, stateLabel } from "@/lib/a2a";
import TripPlan from "./TripPlan";

interface ChatItem {
  id: string;
  role: "user" | "agent";
  text?: string;
  lines?: string[];
  state?: string;
  plan?: any;
  error?: string;
}

export interface AttachRequest {
  taskId: string;
  nonce: number;
}

const LAST_TASK_KEY = "a2a.lastTask";

const SCENARIOS = [
  { label: "Happy path", hint: "Streaming + parallel delegation + streamed artifacts", text: "Plan a 5 day trip to Lisbon from Berlin for 2 people, budget 3000 EUR" },
  { label: "Input required", hint: "Task pauses and asks for the budget", text: "Plan a 4 day trip to Tokyo from Berlin" },
  { label: "Relayed question", hint: "The hotel agent's question travels up to you and your answer travels back down", text: "Plan a 4 day trip to Madrid from Berlin for 4 people, budget 6000 EUR" },
  { label: "Partial failure", hint: "Activities agent FAILS, plan degrades gracefully", text: "Plan a 4 day trip to Rome from Berlin, budget 2000 EUR simulate-failure" },
  { label: "Rejected", hint: "Flight agent REJECTS, orchestrator fails the task", text: "Plan a trip to Atlantis from Berlin, budget 5000 EUR" },
];

export default function ChatPanel({ onRunStart, attach }: { onRunStart: () => void; attach: AttachRequest | null }) {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [background, setBackground] = useState(false);
  const [taskState, setTaskState] = useState<string | undefined>();
  const task = useRef<{ taskId?: string; contextId?: string; state?: string }>({});
  const pushSource = useRef<EventSource | null>(null);
  const resumed = useRef(false);
  const scroller = useRef<HTMLDivElement>(null);

  const patchLast = (fn: (item: ChatItem) => ChatItem) =>
    setItems((prev) => {
      if (!prev.length) return prev;
      const next = [...prev];
      next[next.length - 1] = fn(next[next.length - 1]);
      queueMicrotask(() => scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: "smooth" }));
      return next;
    });

  const remember = (taskId?: string, contextId?: string, state?: string) => {
    try {
      if (!taskId) return;
      if (state && isFinalState(state)) localStorage.removeItem(LAST_TASK_KEY);
      else localStorage.setItem(LAST_TASK_KEY, JSON.stringify({ taskId, contextId }));
    } catch {
      /* storage unavailable */
    }
  };

  const apply = (frame: Frame) => {
    if (frame.type === "error") {
      patchLast((i) => ({ ...i, error: frame.message, state: "failed" }));
      task.current = {};
      setTaskState(undefined);
      return;
    }
    if (frame.type === "status" || frame.type === "task") {
      task.current = { taskId: frame.taskId ?? task.current.taskId, contextId: frame.contextId ?? task.current.contextId, state: frame.state };
      remember(task.current.taskId, task.current.contextId, frame.state);
      setTaskState(frame.state);
      if (frame.state === "input-required") setBusy(false); // the user can answer now, even if the stream lingers
      patchLast((i) => {
        const lines = [...(i.lines ?? [])];
        if (frame.text && lines[lines.length - 1] !== frame.text) lines.push(frame.text);
        let plan = i.plan;
        if (frame.type === "task") {
          for (const a of frame.artifacts) {
            const data = a.parts.find((p) => p.data)?.data;
            if (a.name === "trip-plan" && data) plan = data;
            const text = a.parts.find((p) => p.text)?.text;
            if (a.name === "trip-plan" && text && !lines.includes(text)) lines.push(text);
          }
        }
        return { ...i, state: frame.state, lines, plan };
      });
      return;
    }
    if (frame.type === "artifact") {
      // trip-plan is streamed: a text chunk first, then the structured data appended to the same artifact
      const data = frame.artifact.parts.find((p) => p.data)?.data;
      const text = frame.artifact.parts.find((p) => p.text)?.text;
      if (frame.artifact.name === "trip-plan") {
        patchLast((i) => ({
          ...i,
          plan: data ?? i.plan,
          lines: text && !(i.lines ?? []).includes(text) ? [...(i.lines ?? []), text] : i.lines,
        }));
      }
      return;
    }
    if (frame.type === "message") patchLast((i) => ({ ...i, lines: [...(i.lines ?? []), frame.text] }));
  };

  const consume = async (res: Response) => {
    await readSSE(res, (data) => {
      const frame = parseFrame(safeParse(data));
      if (frame) apply(frame);
    });
  };

  /** Background mode: follow the webhook notifications the orchestrator pushes for this task. */
  const followPush = (taskId: string) => {
    pushSource.current?.close();
    const es = new EventSource(`/api/push/stream?taskId=${encodeURIComponent(taskId)}`);
    pushSource.current = es;
    es.onmessage = (e) => {
      const frame = parseFrame(safeParse(e.data));
      if (!frame) return;
      apply(frame);
      if ((frame.type === "status" || frame.type === "task") && (isFinalState(frame.state) || frame.state === "input-required")) es.close();
    };
  };

  const send = async (raw: string) => {
    const text = raw.trim();
    if (!text || busy) return;
    const followUp = task.current.state === "input-required" ? { taskId: task.current.taskId, contextId: task.current.contextId } : {};
    if (!followUp.taskId) {
      task.current = {};
      onRunStart();
    }
    setInput("");
    setBusy(true);
    setItems((prev) => [...prev, { id: crypto.randomUUID(), role: "user", text }, { id: crypto.randomUUID(), role: "agent", lines: [], state: "submitted" }]);
    setTaskState("submitted");

    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text, ...followUp, background }),
      });
      if (background) {
        const json = safeParse(await res.text());
        const frame = parseFrame(json);
        if (!res.ok || !frame) {
          apply({ type: "error", message: json?.error?.message ?? `Request failed (${res.status})` });
        } else {
          patchLast((i) => ({ ...i, lines: [...(i.lines ?? []), "Running in the background. Updates arrive as push notifications (webhook)."] }));
          apply(frame);
          const id = task.current.taskId;
          if (id && !isFinalState(task.current.state ?? "") && task.current.state !== "input-required") followPush(id);
        }
      } else if (!res.ok || !(res.headers.get("content-type") ?? "").includes("event-stream")) {
        const body = safeParse(await res.text());
        apply({ type: "error", message: body?.error?.message ?? `Request failed (${res.status})` });
      } else {
        await consume(res);
      }
    } catch (e: any) {
      apply({ type: "error", message: e?.message ?? "Network error" });
    } finally {
      setBusy(false);
    }
  };

  /** Re-attach to an existing task: GetTask for its state, then SubscribeToTask if it is still running. */
  const attachTo = async (taskId: string, onlyIfRunning: boolean) => {
    try {
      const res = await fetch(`/api/tasks/${encodeURIComponent(taskId)}`);
      const frame = parseFrame(safeParse(await res.text()));
      if (!frame || frame.type !== "task") return;
      const running = !isFinalState(frame.state) && frame.state !== "input-required";
      if (onlyIfRunning && !running && frame.state !== "input-required") {
        remember(taskId, undefined, frame.state);
        return;
      }
      setItems((prev) => [...prev, { id: crypto.randomUUID(), role: "agent", lines: [`Re-attached to task ${taskId.slice(0, 8)}…`], state: frame.state }]);
      apply(frame);
      if (!running) return;
      setBusy(true);
      const sub = await fetch("/api/subscribe", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ taskId }) });
      if ((sub.headers.get("content-type") ?? "").includes("event-stream")) await consume(sub);
    } catch {
      /* orchestrator offline or task unknown */
    } finally {
      setBusy(false);
    }
  };

  // resume a running task after a page refresh
  useEffect(() => {
    if (resumed.current) return;
    resumed.current = true;
    try {
      const saved = localStorage.getItem(LAST_TASK_KEY);
      if (saved) void attachTo(JSON.parse(saved).taskId, true);
    } catch {
      /* ignore */
    }
    return () => pushSource.current?.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // "Open" from the task history tab
  useEffect(() => {
    if (attach) void attachTo(attach.taskId, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attach?.nonce]);

  const cancel = async () => {
    if (!task.current.taskId) return;
    await fetch("/api/cancel", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ taskId: task.current.taskId }) });
  };

  const awaiting = taskState === "input-required";
  const running = !!taskState && !isFinalState(taskState) && taskState !== "input-required";

  return (
    <section className="panel chat">
      <header className="panel-head">
        <h2>Plan a trip</h2>
        {running && (
          <button className="btn danger" onClick={cancel}>
            Cancel task
          </button>
        )}
      </header>

      <div className="chips">
        {SCENARIOS.map((s) => (
          <button key={s.label} className="chip" title={s.hint} disabled={busy} onClick={() => send(s.text)}>
            {s.label}
          </button>
        ))}
        <label className="toggle" title="Uses SendMessage(returnImmediately) + a push-notification webhook instead of an SSE stream">
          <input type="checkbox" checked={background} onChange={(e) => setBackground(e.target.checked)} disabled={busy} />
          background (push)
        </label>
      </div>

      <div className="messages" ref={scroller}>
        {items.length === 0 && (
          <p className="muted empty">
            Pick a scenario above or type a request. Every message becomes a real A2A <code>SendStreamingMessage</code> call to the
            orchestrator, which delegates to three other agents over A2A. Refresh the page mid-run and the task re-attaches.
          </p>
        )}
        {items.map((m) =>
          m.role === "user" ? (
            <div key={m.id} className="bubble user">
              {m.text}
            </div>
          ) : (
            <div key={m.id} className="bubble agent">
              {m.state && <span className={`pill state-${m.state}`}>{stateLabel(m.state)}</span>}
              {m.error && <div className="error">{m.error}</div>}
              <ul className="lines">
                {(m.lines ?? []).slice(-7).map((l, i, arr) => (
                  <li key={i} className={i === arr.length - 1 ? "latest" : ""}>
                    {l}
                  </li>
                ))}
              </ul>
              {m.plan && <TripPlan plan={m.plan} />}
            </div>
          ),
        )}
      </div>

      <form
        className="composer"
        onSubmit={(e) => {
          e.preventDefault();
          void send(input);
        }}
      >
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={awaiting ? "Answer the agent’s question…" : "e.g. Plan a 5 day trip to Paris, budget 2500 EUR"}
          disabled={busy}
        />
        <button className="btn primary" disabled={busy || !input.trim()}>
          {awaiting ? "Reply" : "Send"}
        </button>
      </form>
    </section>
  );
}
