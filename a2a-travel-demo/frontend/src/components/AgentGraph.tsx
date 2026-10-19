"use client";

import { AGENTS, AgentName } from "@/lib/agents";
import type { EdgeId } from "@/lib/derive";
import { isFinalState, stateLabel } from "@/lib/a2a";

interface Props {
  states: Record<string, string>;
  online: Record<string, boolean>;
  active: Set<EdgeId>;
  selected: string | null;
  onSelect: (name: AgentName) => void;
}

const POS: Record<string, { x: number; y: number }> = {
  ui: { x: 80, y: 180 },
  "orchestrator-agent": { x: 330, y: 180 },
  "flight-agent": { x: 640, y: 62 },
  "hotel-agent": { x: 640, y: 180 },
  "activities-agent": { x: 640, y: 298 },
};

const EDGES: { id: EdgeId; from: string; to: string }[] = [
  { id: "ui-orchestrator-agent", from: "ui", to: "orchestrator-agent" },
  { id: "orchestrator-agent-flight-agent", from: "orchestrator-agent", to: "flight-agent" },
  { id: "orchestrator-agent-hotel-agent", from: "orchestrator-agent", to: "hotel-agent" },
  { id: "orchestrator-agent-activities-agent", from: "orchestrator-agent", to: "activities-agent" },
];

function stateClass(state?: string) {
  if (!state) return "idle";
  if (state === "completed") return "ok";
  if (state === "failed" || state === "rejected") return "bad";
  if (state === "canceled" || state === "cancelled") return "muted";
  if (state === "input-required" || state === "auth-required") return "warn";
  return "busy"; // submitted / working
}

const W = 168;
const H = 64;

export default function AgentGraph({ states, online, active, selected, onSelect }: Props) {
  return (
    <svg viewBox="0 0 760 360" className="graph" role="img" aria-label="A2A agent network">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0 0 L10 5 L0 10 z" fill="currentColor" />
        </marker>
      </defs>

      {EDGES.map((e) => {
        const a = POS[e.from];
        const b = POS[e.to];
        const x1 = a.x + W / 2 + (e.from === "ui" ? -W / 2 + 56 : 0);
        const x2 = b.x - W / 2;
        const mx = (x1 + x2) / 2;
        const d = `M ${x1} ${a.y} C ${mx} ${a.y}, ${mx} ${b.y}, ${x2} ${b.y}`;
        const isActive = active.has(e.id);
        return (
          <g key={e.id} className={`edge ${isActive ? "active" : ""}`}>
            <path d={d} className="edge-base" markerEnd="url(#arrow)" />
            {isActive && <path d={d} className="edge-flow" />}
          </g>
        );
      })}

      {/* the browser */}
      <g transform={`translate(${POS.ui.x - 56}, ${POS.ui.y - 32})`}>
        <rect width={112} height={H} rx={14} className="node idle" />
        <text x={56} y={28} textAnchor="middle" className="node-emoji">🖥️</text>
        <text x={56} y={50} textAnchor="middle" className="node-label">Next.js UI</text>
      </g>

      {AGENTS.map((agent) => {
        const p = POS[agent.name];
        const state = states[agent.name];
        const isOnline = online[agent.name] !== false;
        const cls = isOnline ? stateClass(state) : "off";
        return (
          <g
            key={agent.name}
            transform={`translate(${p.x - W / 2}, ${p.y - H / 2})`}
            className={`agent-node ${selected === agent.name ? "selected" : ""}`}
            onClick={() => onSelect(agent.name)}
            tabIndex={0}
            role="button"
            onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onSelect(agent.name)}
          >
            <rect width={W} height={H} rx={14} className={`node ${cls}`} style={{ ["--accent" as string]: agent.color }} />
            <rect width={5} height={H - 20} x={0} y={10} rx={2.5} fill={agent.color} />
            <text x={26} y={29} className="node-emoji">{agent.emoji}</text>
            <text x={50} y={28} className="node-label">{agent.label}</text>
            <text x={50} y={48} className="node-state">
              {!isOnline ? "offline" : state ? stateLabel(state) : "idle"}
              {state && !isFinalState(state) && isOnline ? " …" : ""}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
