import { AGENTS, AgentMeta } from "@/lib/agents";
import { readSSE } from "@/lib/a2a";
import { agentUrl } from "@/lib/server-config";


/**
 * Multiplexes every agent's /trace/stream (a demo-only "network tap") into one SSE stream for the browser.
 * Reconnects automatically when an agent is (re)started.
 */
export async function GET(req: Request) {
  const encoder = new TextEncoder();
  const abort = new AbortController();
  req.signal.addEventListener("abort", () => abort.abort());

  const stream = new ReadableStream({
    start(controller) {
      let closed = false;
      const send = (chunk: string) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(chunk));
        } catch {
          closed = true;
        }
      };

      const heartbeat = setInterval(() => send(": hb\n\n"), 15000);
      abort.signal.addEventListener("abort", () => {
        closed = true;
        clearInterval(heartbeat);
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      });

      for (const agent of AGENTS) void follow(agent, abort.signal, send);
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
    },
  });
}

async function follow(agent: AgentMeta, signal: AbortSignal, send: (chunk: string) => void) {
  let online: boolean | null = null;
  const setOnline = (value: boolean) => {
    if (online === value) return;
    online = value;
    send(`event: agent-status\ndata: ${JSON.stringify({ agent: agent.name, online: value })}\n\n`);
  };

  while (!signal.aborted) {
    try {
      const res = await fetch(`${agentUrl(agent)}/trace/stream`, { headers: { Accept: "text/event-stream" }, signal });
      if (!res.ok || !res.body) throw new Error(`status ${res.status}`);
      setOnline(true);
      await readSSE(res, (data) => send(`data: ${data}\n\n`), signal);
    } catch {
      /* agent offline - retry below */
    }
    if (signal.aborted) return;
    setOnline(false);
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
}
