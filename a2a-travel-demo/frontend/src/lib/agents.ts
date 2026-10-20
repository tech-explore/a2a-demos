export type AgentName = "orchestrator-agent" | "flight-agent" | "hotel-agent" | "activities-agent";

export interface AgentMeta {
  name: AgentName;
  label: string;
  emoji: string;
  color: string;
  env: string;
  fallbackUrl: string;
}

export const AGENTS: AgentMeta[] = [
  { name: "orchestrator-agent", label: "Trip Orchestrator", emoji: "🧭", color: "#a78bfa", env: "ORCHESTRATOR_URL", fallbackUrl: "http://localhost:9100" },
  { name: "flight-agent", label: "Flight Agent", emoji: "✈️", color: "#38bdf8", env: "FLIGHT_URL", fallbackUrl: "http://localhost:9101" },
  { name: "hotel-agent", label: "Hotel Agent", emoji: "🏨", color: "#f59e0b", env: "HOTEL_URL", fallbackUrl: "http://localhost:9102" },
  { name: "activities-agent", label: "Activities Agent", emoji: "🎟️", color: "#34d399", env: "ACTIVITIES_URL", fallbackUrl: "http://localhost:9103" },
];

export const agentMeta = (name: string) => AGENTS.find((a) => a.name === name);
