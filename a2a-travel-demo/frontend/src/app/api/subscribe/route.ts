import { orchestratorUrl, rpcHeaders } from "@/lib/server-config";

/** A2A SubscribeToTask: re-attach to a running task and stream its updates (SSE pass-through). */
export async function POST(req: Request) {
  const { taskId } = (await req.json()) as { taskId: string };
  const rpc = { jsonrpc: "2.0", id: crypto.randomUUID(), method: "SubscribeToTask", params: { id: taskId } };
  let upstream: Response;
  try {
    upstream = await fetch(`${orchestratorUrl()}/`, {
      method: "POST",
      headers: rpcHeaders({ Accept: "text/event-stream" }),
      body: JSON.stringify(rpc),
      signal: req.signal,
    });
  } catch {
    return Response.json({ error: { message: "Cannot reach the orchestrator" } }, { status: 502 });
  }
  const contentType = upstream.headers.get("content-type") ?? "application/json";
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": contentType.includes("event-stream") ? "text/event-stream; charset=utf-8" : contentType,
      "Cache-Control": "no-cache, no-transform",
    },
  });
}
