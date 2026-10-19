"use client";

import { useEffect, useMemo, useState } from "react";
import AgentGraph from "@/components/AgentGraph";
import CardsPanel from "@/components/CardsPanel";
import ChatPanel, { AttachRequest } from "@/components/ChatPanel";
import TasksPanel from "@/components/TasksPanel";
import ThemeToggle from "@/components/ThemeToggle";
import TimelinePanel from "@/components/TimelinePanel";
import WirePanel from "@/components/WirePanel";
import { AgentName } from "@/lib/agents";
import { activeEdges, framesFrom, nodeStates } from "@/lib/derive";
import { useTrace } from "@/lib/useTrace";

type Tab = "timeline" | "wire" | "tasks" | "cards";

export default function Home() {
  const { events, online } = useTrace();
  const [tab, setTab] = useState<Tab>("timeline");
  const [selected, setSelected] = useState<AgentName | null>(null);
  const [runStart, setRunStart] = useState(0); // graph + timeline only show the current run
  const [wireClearedAt, setWireClearedAt] = useState(0);
  const [attach, setAttach] = useState<AttachRequest | null>(null);
  const [now, setNow] = useState(0); // set after mount: Date.now() during render is not prerender-safe

  useEffect(() => {
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 300);
    return () => clearInterval(id);
  }, []);

  const runEvents = useMemo(() => events.filter((e) => e.ts >= runStart), [events, runStart]);
  const frames = useMemo(() => framesFrom(runEvents), [runEvents]);
  const states = useMemo(() => nodeStates(frames), [frames]);
  const active = useMemo(() => activeEdges(events, now), [events, now]);
  const wireEvents = useMemo(() => events.filter((e) => e.ts >= wireClearedAt), [events, wireClearedAt]);

  const anyOnline = Object.values(online).some(Boolean);

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <h1>A2A Travel Planner</h1>
          <p className="muted">
            Agent2Agent protocol · Java A2A SDK + Spring Boot agents · live wire view
          </p>
        </div>
        <div className="topbar-actions">
          <ThemeToggle />
          <div className={`pill ${anyOnline ? "ok" : "bad"}`}>{anyOnline ? "agents connected" : "agents offline — start the backend"}</div>
        </div>
      </header>

      <div className="layout">
        <ChatPanel onRunStart={() => setRunStart(Date.now())} attach={attach} />

        <div className="right">
          <section className="panel graph-panel">
            <header className="panel-head">
              <h2>Agent network</h2>
              <span className="muted small">click an agent to see its Agent Card</span>
            </header>
            <AgentGraph
              states={states}
              online={online}
              active={active}
              selected={selected}
              onSelect={(name) => {
                setSelected(name);
                setTab("cards");
              }}
            />
          </section>

          <section className="panel tabs-panel">
            <nav className="tabs">
              {(
                [
                  ["timeline", "Task timeline"],
                  ["wire", "Wire log"],
                  ["tasks", "Tasks"],
                  ["cards", "Agent cards"],
                ] as [Tab, string][]
              ).map(([id, label]) => (
                <button key={id} className={tab === id ? "tab on" : "tab"} onClick={() => setTab(id)}>
                  {label}
                </button>
              ))}
            </nav>
            <div className="tab-body">
              {tab === "timeline" && <TimelinePanel frames={frames} />}
              {tab === "wire" && <WirePanel events={wireEvents} onClear={() => setWireClearedAt(Date.now())} />}
              {tab === "tasks" && <TasksPanel onOpen={(taskId) => setAttach({ taskId, nonce: Date.now() })} />}
              {tab === "cards" && <CardsPanel focus={selected} />}
            </div>
          </section>
        </div>
      </div>
    </main>
  );
}
