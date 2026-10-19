import { orchestratorUrl, rpcHeaders } from "@/lib/server-config";

/** A2A ListTasks: the task history the orchestrator holds. */
export async function GET() {
  const rpc = { jsonrpc: "2.0", id: crypto.randomUUID(), method: "ListTasks", params: { pageSize: 25 } };
  try {
    const upstream = await fetch(`${orchestratorUrl()}/`, {
      method: "POST",
      headers: rpcHeaders(),
      body: JSON.stringify(rpc),
      cache: "no-store",
    });
    return new Response(await upstream.text(), { status: upstream.status, headers: { "Content-Type": "application/json" } });
  } catch {
    return Response.json({ error: { message: "Cannot reach the orchestrator" } }, { status: 502 });
  }
}
