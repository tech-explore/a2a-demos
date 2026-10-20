import { describe, expect, it } from "vitest";
import { history, isKnownToken, publish, registerToken, subscribe } from "@/lib/push-hub";

describe("push hub", () => {
  it("only accepts tokens it handed out", () => {
    expect(isKnownToken(null)).toBe(false);
    expect(isKnownToken("nope")).toBe(false);
    registerToken("abc");
    expect(isKnownToken("abc")).toBe(true);
  });

  it("indexes notifications by task id and replays history per task", () => {
    publish({ statusUpdate: { taskId: "task-a", status: { state: "TASK_STATE_WORKING" } } });
    publish({ statusUpdate: { taskId: "task-b", status: { state: "TASK_STATE_WORKING" } } });
    publish({ task: { id: "task-a", status: { state: "TASK_STATE_COMPLETED" } } });
    expect(history("task-a")).toHaveLength(2);
    expect(history("task-b")).toHaveLength(1);
    expect(history().length).toBeGreaterThanOrEqual(3);
  });

  it("notifies live subscribers until they unsubscribe", () => {
    const seen: (string | undefined)[] = [];
    const off = subscribe((e) => seen.push(e.taskId));
    publish({ statusUpdate: { taskId: "live-1" } });
    off();
    publish({ statusUpdate: { taskId: "live-2" } });
    expect(seen).toEqual(["live-1"]);
  });
});
