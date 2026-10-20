import { registerToken } from "@/lib/push-hub";
import { orchestratorUrl, rpcHeaders } from "@/lib/server-config";


/**
 * Browser -> (this route) -> Orchestrator, speaking real A2A JSON-RPC.
 * We wrap the user's text in a SendStreamingMessage request and pipe the SSE stream straight back.
 */
export async function POST(req: Request) {
  const { text, taskId, contextId, background } = (await req.json()) as {
    text: string;
    taskId?: string;
    contextId?: string;
    background?: boolean;
  };

  const message: Record<string, unknown> = {
    messageId: crypto.randomUUID(),
    role: "ROLE_USER",
    parts: [{ text }],
  };
  if (taskId) message.taskId = taskId; // continue an existing task (e.g. answer INPUT_REQUIRED)
  if (contextId) message.contextId = contextId;

  if (background) return sendInBackground(req, message);

  const rpc = {
    jsonrpc: "2.0",
    id: crypto.randomUUID(),
    method: "SendStreamingMessage",
    params: { message },
  };

  let upstream: Response;
  try {
    upstream = await fetch(`${orchestratorUrl()}/`, {
      method: "POST",
      headers: rpcHeaders({ Accept: "text/event-stream" }),
      body: JSON.stringify(rpc),
      signal: req.signal,
    });
  } catch {
    return Response.json(
      { error: { message: `Cannot reach the orchestrator at ${orchestratorUrl()}. Are the Spring Boot agents running?` } },
      { status: 502 },
    );
  }

  const contentType = upstream.headers.get("content-type") ?? "application/json";
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": contentType.includes("event-stream") ? "text/event-stream; charset=utf-8" : contentType,
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
    },
  });
}

/**
 * Background mode: a blocking SendMessage with returnImmediately=true and a push-notification config.
 * The orchestrator answers right away with the Task, then POSTs every update to our webhook.
 */
async function sendInBackground(req: Request, message: Record<string, unknown>) {
  const token = crypto.randomUUID();
  registerToken(token);
  const origin = process.env.PUBLIC_URL ?? new URL(req.url).origin;

  const rpc = {
    jsonrpc: "2.0",
    id: crypto.randomUUID(),
    method: "SendMessage",
    params: {
      message,
      configuration: {
        returnImmediately: true,
        taskPushNotificationConfig: { url: `${origin}/api/push/webhook`, token },
      },
    },
  };

  try {
    const upstream = await fetch(`${orchestratorUrl()}/`, {
      method: "POST",
      headers: rpcHeaders(),
      body: JSON.stringify(rpc),
      signal: req.signal,
    });
    return new Response(await upstream.text(), { status: upstream.status, headers: { "Content-Type": "application/json" } });
  } catch {
    return Response.json({ error: { message: `Cannot reach the orchestrator at ${orchestratorUrl()}.` } }, { status: 502 });
  }
}
