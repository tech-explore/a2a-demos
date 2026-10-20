import { orchestratorUrl, rpcHeaders } from "@/lib/server-config";

/** A2A GetTask */
export async function GET(_req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const rpc = { jsonrpc: "2.0", id: crypto.randomUUID(), method: "GetTask", params: { id } };
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
