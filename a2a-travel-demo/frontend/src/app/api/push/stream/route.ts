import { history, subscribe } from "@/lib/push-hub";

/** SSE feed of received push notifications, optionally filtered with ?taskId= . Replays what already arrived. */
export async function GET(req: Request) {
  const taskId = new URL(req.url).searchParams.get("taskId");
  const encoder = new TextEncoder();

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
      const emit = (e: { body: unknown }) => send(`data: ${JSON.stringify(e.body)}\n\n`);

      for (const e of history(taskId)) emit(e);
      const unsubscribe = subscribe((e) => {
        if (!taskId || e.taskId === taskId) emit(e);
      });
      const heartbeat = setInterval(() => send(": hb\n\n"), 15000);

      req.signal.addEventListener("abort", () => {
        closed = true;
        unsubscribe();
        clearInterval(heartbeat);
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      });
    },
  });

  return new Response(stream, {
    headers: { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache, no-transform", Connection: "keep-alive" },
  });
}
