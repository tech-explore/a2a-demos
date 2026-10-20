import { orchestratorUrl, rpcHeaders } from "@/lib/server-config";


/** A2A CancelTask */
export async function POST(req: Request) {
  const { taskId } = (await req.json()) as { taskId: string };
  const rpc = { jsonrpc: "2.0", id: crypto.randomUUID(), method: "CancelTask", params: { id: taskId } };
  try {
    const upstream = await fetch(`${orchestratorUrl()}/`, {
      method: "POST",
      headers: rpcHeaders(),
      body: JSON.stringify(rpc),
    });
    return new Response(await upstream.text(), { status: upstream.status, headers: { "Content-Type": "application/json" } });
  } catch {
    return Response.json({ error: { message: "Cannot reach the orchestrator" } }, { status: 502 });
  }
}
