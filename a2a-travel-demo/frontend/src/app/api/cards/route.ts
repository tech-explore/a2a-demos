import { AGENTS } from "@/lib/agents";
import { agentUrl } from "@/lib/server-config";


/** A2A discovery: fetch every agent's Agent Card from /.well-known/agent-card.json */
export async function GET() {
  const cards = await Promise.all(
    AGENTS.map(async (agent) => {
      const url = agentUrl(agent);
      try {
        const res = await fetch(`${url}/.well-known/agent-card.json`, { signal: AbortSignal.timeout(2500), cache: "no-store" });
        if (!res.ok) throw new Error(String(res.status));
        return { name: agent.name, url, online: true, card: await res.json() };
      } catch {
        return { name: agent.name, url, online: false, card: null };
      }
    }),
  );
  return Response.json(cards);
}
