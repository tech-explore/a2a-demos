import { isKnownToken, publish } from "@/lib/push-hub";

/** Receives A2A push notifications from the orchestrator (it POSTs task updates here). */
export async function POST(req: Request) {
  if (!isKnownToken(req.headers.get("x-a2a-notification-token"))) {
    return Response.json({ error: "unknown or missing notification token" }, { status: 401 });
  }
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return Response.json({ error: "invalid JSON" }, { status: 400 });
  }
  publish(body);
  return new Response(null, { status: 200 });
}
