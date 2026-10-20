import { AGENTS, AgentMeta } from "./agents";

/** Server-side only: resolve each agent's base URL from the environment. */
export function agentUrl(agent: AgentMeta): string {
  return (process.env[agent.env] ?? agent.fallbackUrl).replace(/\/$/, "");
}

export const orchestratorUrl = () => agentUrl(AGENTS[0]);

/**
 * A2A v1.0 servers assume protocol version 0.3 when the A2A-Version header is missing, and then reject the call with
 * "Protocol version '0.3' is not supported". Every JSON-RPC call to an agent must therefore send this header.
 */
export const A2A_VERSION = "1.0";

export const rpcHeaders = (extra: Record<string, string> = {}): Record<string, string> => ({
  "Content-Type": "application/json",
  "A2A-Version": A2A_VERSION,
  ...extra,
});
