import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { startMockAgent } from "./helpers/mock-a2a";
import { parseFrame, readSSE, safeParse } from "@/lib/a2a";

let mock: Awaited<ReturnType<typeof startMockAgent>>;

beforeAll(async () => {
  mock = await startMockAgent();
  process.env.ORCHESTRATOR_URL = mock.url;
  process.env.FLIGHT_URL = mock.url;
  process.env.HOTEL_URL = mock.url;
  process.env.ACTIVITIES_URL = mock.url;
});

afterAll(async () => {
  await mock.close();
});

const post = (path: string, body: unknown) =>
  new Request(`http://ui.test${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

describe("POST /api/chat (streaming)", () => {
  it("wraps text in SendStreamingMessage and passes the SSE stream through untouched", async () => {
    const { POST } = await import("@/app/api/chat/route");
    const res = await POST(post("/api/chat", { text: "Plan a trip to Lisbon" }));
    expect(res.headers.get("content-type")).toContain("text/event-stream");

    const frames: any[] = [];
    await readSSE(res, (d) => frames.push(parseFrame(safeParse(d))));
    expect(frames.map((f) => f.state)).toEqual(["working", "completed"]);

    const call = mock.rpcCalls("SendStreamingMessage").at(-1)!;
    expect(call.json.params.message).toMatchObject({ role: "ROLE_USER", parts: [{ text: "Plan a trip to Lisbon" }] });
    expect(call.json.params.message.taskId).toBeUndefined();
  });

  it("continues an existing task when taskId/contextId are given (input-required answers)", async () => {
    const { POST } = await import("@/app/api/chat/route");
    await (await POST(post("/api/chat", { text: "3500 EUR", taskId: "t-9", contextId: "c-9" }))).text();
    const call = mock.rpcCalls("SendStreamingMessage").at(-1)!;
    expect(call.json.params.message).toMatchObject({ taskId: "t-9", contextId: "c-9" });
  });

  it("answers 502 with a readable error when the orchestrator is down", async () => {
    const saved = process.env.ORCHESTRATOR_URL;
    process.env.ORCHESTRATOR_URL = "http://127.0.0.1:1";
    try {
      const { POST } = await import("@/app/api/chat/route");
      const res = await POST(post("/api/chat", { text: "hi" }));
      expect(res.status).toBe(502);
      expect((await res.json()).error.message).toContain("Cannot reach the orchestrator");
    } finally {
      process.env.ORCHESTRATOR_URL = saved;
    }
  });
});

describe("background mode + push notifications", () => {
  it("sends SendMessage(returnImmediately) with a webhook config, then relays authenticated webhook calls to the stream", async () => {
    const { POST } = await import("@/app/api/chat/route");
    const res = await POST(post("/api/chat", { text: "Plan a trip to Oslo", background: true }));
    expect(parseFrame(await res.json())).toMatchObject({ type: "task", taskId: "t-bg" });

    const sent = mock.rpcCalls("SendMessage").at(-1)!.json.params;
    expect(sent.configuration.returnImmediately).toBe(true);
    const { url, token } = sent.configuration.taskPushNotificationConfig;
    expect(url).toBe("http://ui.test/api/push/webhook");
    expect(token).toBeTruthy();

    const webhook = (await import("@/app/api/push/webhook/route")).POST;
    const body = { statusUpdate: { taskId: "t-bg", status: { state: "TASK_STATE_COMPLETED" } } };

    // wrong / missing token is rejected
    const bad = await webhook(new Request(url, { method: "POST", headers: { "x-a2a-notification-token": "wrong" }, body: JSON.stringify(body) }));
    expect(bad.status).toBe(401);

    // the token we registered is accepted
    const ok = await webhook(new Request(url, { method: "POST", headers: { "x-a2a-notification-token": token }, body: JSON.stringify(body) }));
    expect(ok.status).toBe(200);

    // the SSE stream replays it for that task, and only for that task
    const { GET } = await import("@/app/api/push/stream/route");
    const abort = new AbortController();
    const stream = await GET(new Request("http://ui.test/api/push/stream?taskId=t-bg", { signal: abort.signal }));
    const reader = stream.body!.getReader();
    const { value } = await reader.read();
    const text = new TextDecoder().decode(value);
    expect(text).toContain('"taskId":"t-bg"');
    abort.abort();
  });

  it("rejects malformed webhook bodies", async () => {
    const { registerToken } = await import("@/lib/push-hub");
    registerToken("tok-json");
    const webhook = (await import("@/app/api/push/webhook/route")).POST;
    const res = await webhook(new Request("http://ui.test/api/push/webhook", { method: "POST", headers: { "x-a2a-notification-token": "tok-json" }, body: "{not json" }));
    expect(res.status).toBe(400);
  });
});

describe("task history, resubscribe and cancel", () => {
  it("ListTasks", async () => {
    const { GET } = await import("@/app/api/tasks/route");
    const json = await (await GET()).json();
    expect(json.result.tasks[0].id).toBe("t-1");
    expect(mock.rpcCalls("ListTasks").length).toBeGreaterThan(0);
  });

  it("GetTask passes the id from the URL", async () => {
    const { GET } = await import("@/app/api/tasks/[id]/route");
    const json = await (await GET(new Request("http://ui.test/api/tasks/abc"), { params: Promise.resolve({ id: "abc" }) })).json();
    expect(parseFrame(json)).toMatchObject({ type: "task", taskId: "abc", state: "completed" });
  });

  it("SubscribeToTask streams updates for a running task", async () => {
    const { POST } = await import("@/app/api/subscribe/route");
    const res = await POST(post("/api/subscribe", { taskId: "t-run" }));
    const states: string[] = [];
    await readSSE(res, (d) => {
      const f = parseFrame(safeParse(d));
      if (f && "state" in f) states.push(f.state);
    });
    expect(states).toEqual(["working", "completed"]);
    expect(mock.rpcCalls("SubscribeToTask").at(-1)!.json.params).toEqual({ id: "t-run" });
  });

  it("CancelTask", async () => {
    const { POST } = await import("@/app/api/cancel/route");
    const json = await (await POST(post("/api/cancel", { taskId: "t-1" }))).json();
    expect(parseFrame(json)).toMatchObject({ type: "task", state: "canceled" });
  });
});

describe("protocol version header", () => {
  it("every JSON-RPC call to the orchestrator carries A2A-Version: 1.0 (a missing header means 0.3 and is rejected)", async () => {
    const calls = mock.requests.filter((r) => r.json?.method);
    expect(calls.length).toBeGreaterThan(5);
    for (const call of calls) {
      expect(call.headers["a2a-version"], `${call.json.method} is missing the header`).toBe("1.0");
    }
  });

  it("the mock really rejects calls without it, like the SDK does", async () => {
    const res = await fetch(`${mock.url}/`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: "x", method: "GetTask", params: { id: "t" } }) });
    expect((await res.json()).error.message).toBe("Protocol version '0.3' is not supported. Supported versions: [1.0]");
  });
});

describe("discovery and tracing", () => {
  it("GET /api/cards returns every agent card and online flag", async () => {
    const { GET } = await import("@/app/api/cards/route");
    const cards = await (await GET()).json();
    expect(cards).toHaveLength(4);
    expect(cards.every((c: any) => c.online && c.card.name === "Mock Agent")).toBe(true);
  });

  it("GET /api/trace multiplexes every agent's trace stream and reports who is online", async () => {
    const { GET } = await import("@/app/api/trace/route");
    const abort = new AbortController();
    const res = await GET(new Request("http://ui.test/api/trace", { signal: abort.signal }));
    const reader = res.body!.getReader();
    const decoder = new TextDecoder();

    let text = "";
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      const { value } = await reader.read();
      text += decoder.decode(value);
      const traces = text.split("\n\n").filter((b) => b.startsWith("data:")).length;
      const online = (text.match(/event: agent-status/g) ?? []).length;
      if (traces >= 4 && online >= 4) break;
    }
    abort.abort();

    expect(text.split("\n\n").filter((b) => b.startsWith("data:")).length).toBeGreaterThanOrEqual(4);
    expect((text.match(/"online":true/g) ?? []).length).toBeGreaterThanOrEqual(4);
  });
});
