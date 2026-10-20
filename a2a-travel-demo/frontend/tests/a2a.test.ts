import { describe, expect, it } from "vitest";
import { frameTaskId, isFinalState, normState, parseFrame, readSSE, safeParse } from "@/lib/a2a";

describe("normState", () => {
  it("normalises protobuf-style and plain state names", () => {
    expect(normState("TASK_STATE_INPUT_REQUIRED")).toBe("input-required");
    expect(normState("TASK_STATE_WORKING")).toBe("working");
    expect(normState("completed")).toBe("completed");
    expect(normState(undefined)).toBe("");
  });
  it("knows which states are final", () => {
    expect(["completed", "failed", "canceled", "rejected"].every(isFinalState)).toBe(true);
    expect(["working", "submitted", "input-required"].some(isFinalState)).toBe(false);
  });
});

describe("parseFrame", () => {
  it("reads a status update with its message text", () => {
    const f = parseFrame({ result: { statusUpdate: { taskId: "t", contextId: "c", status: { state: "TASK_STATE_WORKING", message: { parts: [{ text: "hi " }, { text: "there" }] } } } } });
    expect(f).toEqual({ type: "status", taskId: "t", contextId: "c", state: "working", text: "hi there" });
  });

  it("reads artifact updates including structured data parts", () => {
    const f = parseFrame({ result: { artifactUpdate: { taskId: "t", artifact: { artifactId: "a", name: "trip-plan", parts: [{ text: "summary" }, { data: { estimatedTotal: 5 } }] } } } });
    expect(f?.type).toBe("artifact");
    if (f?.type === "artifact") {
      expect(f.artifact.name).toBe("trip-plan");
      expect(f.artifact.parts[1].data).toEqual({ estimatedTotal: 5 });
    }
  });

  it("reads wrapped and bare tasks (SendMessage vs GetTask results)", () => {
    const wrapped = parseFrame({ result: { task: { id: "t1", contextId: "c", status: { state: "TASK_STATE_COMPLETED" }, artifacts: [{ name: "x", parts: [{ text: "y" }] }] } } });
    expect(wrapped).toMatchObject({ type: "task", taskId: "t1", state: "completed" });
    const bare = parseFrame({ result: { id: "t2", contextId: "c", status: { state: "TASK_STATE_WORKING" } } });
    expect(bare).toMatchObject({ type: "task", taskId: "t2", state: "working" });
  });

  it("does not mistake a status update for a bare task", () => {
    expect(parseFrame({ result: { statusUpdate: { taskId: "t", status: { state: "TASK_STATE_WORKING" } } } })?.type).toBe("status");
  });

  it("reads messages, errors and legacy kind-style frames", () => {
    expect(parseFrame({ result: { message: { parts: [{ text: "plain" }] } } })).toEqual({ type: "message", text: "plain" });
    expect(parseFrame({ error: { message: "boom" } })).toEqual({ type: "error", message: "boom" });
    expect(parseFrame({ result: { kind: "status-update", taskId: "t", status: { state: "working" } } })?.type).toBe("status");
  });

  it("returns null for junk", () => {
    expect(parseFrame(null)).toBeNull();
    expect(parseFrame({ result: { somethingElse: 1 } })).toBeNull();
  });
});

describe("frameTaskId", () => {
  it("finds the task id in every frame flavour", () => {
    expect(frameTaskId({ result: { statusUpdate: { taskId: "a" } } })).toBe("a");
    expect(frameTaskId({ result: { artifactUpdate: { taskId: "b" } } })).toBe("b");
    expect(frameTaskId({ result: { task: { id: "c" } } })).toBe("c");
    expect(frameTaskId({ statusUpdate: { taskId: "d" } })).toBe("d"); // push-notification body, no JSON-RPC envelope
    expect(frameTaskId(null)).toBeUndefined();
  });
});

describe("safeParse", () => {
  it("never throws", () => {
    expect(safeParse("{bad")).toBeNull();
    expect(safeParse(undefined)).toBeNull();
    expect(safeParse('{"a":1}')).toEqual({ a: 1 });
  });
});

describe("readSSE", () => {
  const streamOf = (chunks: string[]) =>
    new Response(
      new ReadableStream({
        start(controller) {
          const enc = new TextEncoder();
          chunks.forEach((c) => controller.enqueue(enc.encode(c)));
          controller.close();
        },
      }),
    );

  it("reassembles events split across arbitrary chunk boundaries", async () => {
    const got: string[] = [];
    await readSSE(streamOf(['data: {"a"', ':1}\n', '\ndata: {"b":2}\r\n\r\n: heartbeat\n\n', "data: line1\ndata: line2\n\n"]), (d) => got.push(d));
    expect(got).toEqual(['{"a":1}', '{"b":2}', "line1\nline2"]);
  });

  it("ignores comments, event names and ids", async () => {
    const got: string[] = [];
    await readSSE(streamOf(["event: trace\nid: 7\ndata: payload\n\n"]), (d) => got.push(d));
    expect(got).toEqual(["payload"]);
  });
});
