import http from "node:http";
import type { AddressInfo } from "node:net";

export interface Recorded {
  method: string;
  path: string;
  headers: http.IncomingHttpHeaders;
  json: any;
}

const status = (state: string, text: string, taskId = "t-1") => ({
  jsonrpc: "2.0",
  id: "1",
  result: { statusUpdate: { taskId, contextId: "c-1", status: { state, message: { role: "ROLE_AGENT", parts: [{ text }] } } } },
});

/** A tiny stand-in for an A2A agent: records JSON-RPC calls and answers just enough for the UI's route handlers. */
export async function startMockAgent() {
  const requests: Recorded[] = [];

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const json = raw ? JSON.parse(raw) : null;
      requests.push({ method: req.method ?? "", path: req.url ?? "", headers: req.headers, json });

      if (req.url === "/trace/stream") {
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        res.write(`event: trace\ndata: ${JSON.stringify({ seq: 1, ts: Date.now(), agent: "mock", kind: "note", direction: "in", method: null, peer: null, payload: "hello" })}\n\n`);
        return; // keep the stream open, like the real agents
      }
      if (req.url === "/.well-known/agent-card.json") {
        res.writeHead(200, { "Content-Type": "application/json" });
        return void res.end(JSON.stringify({ name: "Mock Agent", skills: [{ id: "s", name: "S" }], capabilities: { streaming: true } }));
      }

      const method = json?.method as string;

      // Mirror the real SDK: a missing A2A-Version header means "0.3", which a v1.0-only server rejects.
      if (method && req.headers["a2a-version"] !== "1.0") {
        const asked = (req.headers["a2a-version"] as string | undefined) ?? "0.3";
        res.writeHead(200, { "Content-Type": "application/json" });
        return void res.end(JSON.stringify({ jsonrpc: "2.0", id: json.id, error: { code: -32009, message: `Protocol version '${asked}' is not supported. Supported versions: [1.0]` } }));
      }
      if (method === "SendStreamingMessage" || method === "SubscribeToTask") {
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        res.write(`data: ${JSON.stringify(status("TASK_STATE_WORKING", "working on it"))}\n\n`);
        res.write(`data: ${JSON.stringify(status("TASK_STATE_COMPLETED", "done"))}\n\n`);
        return void res.end();
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      if (method === "SendMessage") {
        return void res.end(JSON.stringify({ jsonrpc: "2.0", id: "1", result: { task: { id: "t-bg", contextId: "c-bg", status: { state: "TASK_STATE_WORKING" } } } }));
      }
      if (method === "ListTasks") {
        return void res.end(JSON.stringify({ jsonrpc: "2.0", id: "1", result: { tasks: [{ id: "t-1", status: { state: "TASK_STATE_COMPLETED" } }] } }));
      }
      if (method === "GetTask") {
        return void res.end(JSON.stringify({ jsonrpc: "2.0", id: "1", result: { id: json.params.id, contextId: "c-1", status: { state: "TASK_STATE_COMPLETED" } } }));
      }
      if (method === "CancelTask") {
        return void res.end(JSON.stringify({ jsonrpc: "2.0", id: "1", result: { id: json.params.id, status: { state: "TASK_STATE_CANCELED" } } }));
      }
      res.end(JSON.stringify({ jsonrpc: "2.0", id: "1", error: { code: -32601, message: "unknown method" } }));
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    rpcCalls: (method: string) => requests.filter((r) => r.json?.method === method),
    close: () => new Promise<void>((resolve) => {
      server.closeAllConnections();
      server.close(() => resolve());
    }),
  };
}

export async function readAll(res: Response): Promise<string> {
  return await res.text();
}
