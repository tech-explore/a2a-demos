/* Types + defensive parsers for A2A (v1.0 JSON-RPC, protobuf-JSON field names). */

export interface TraceEvent {
  seq: number;
  ts: number;
  agent: string;
  kind: "rpc-request" | "rpc-response" | "sse-frame" | "delegation" | "client-event" | "note";
  direction: "in" | "out";
  method: string | null;
  peer: string | null;
  payload: string;
}

export interface UiPart {
  text?: string;
  data?: any;
}

export interface UiArtifact {
  id?: string;
  name?: string;
  parts: UiPart[];
}

export type Frame =
  | { type: "status"; taskId?: string; contextId?: string; state: string; text: string }
  | { type: "artifact"; taskId?: string; artifact: UiArtifact }
  | { type: "task"; taskId?: string; contextId?: string; state: string; text: string; artifacts: UiArtifact[] }
  | { type: "message"; text: string }
  | { type: "error"; message: string };

export function safeParse(s?: string | null): any {
  if (!s) return null;
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

/** TASK_STATE_INPUT_REQUIRED | input-required | INPUT_REQUIRED  ->  "input-required" */
export function normState(s?: string): string {
  return (s ?? "").replace(/^TASK_STATE_/, "").toLowerCase().replace(/_/g, "-");
}

export const isFinalState = (s: string) => ["completed", "failed", "canceled", "cancelled", "rejected"].includes(s);

function parts(raw: any): UiPart[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((p) => ({ text: typeof p?.text === "string" ? p.text : p?.root?.text, data: p?.data ?? p?.root?.data }));
}

const partsText = (raw: any) => parts(raw).map((p) => p.text ?? "").join("");
const messageText = (m: any) => (m ? partsText(m.parts) : "");

function artifact(a: any): UiArtifact {
  return { id: a?.artifactId ?? a?.artifact_id, name: a?.name, parts: parts(a?.parts) };
}

/** Turn one JSON-RPC response (or SSE frame) into a simple, UI-friendly Frame. */
export function parseFrame(json: any): Frame | null {
  if (!json) return null;
  if (json.error) return { type: "error", message: json.error.message ?? "error" };
  const r = json.result ?? json;
  const su = r.statusUpdate ?? r.status_update ?? (r.kind === "status-update" ? r : null);
  const au = r.artifactUpdate ?? r.artifact_update ?? (r.kind === "artifact-update" ? r : null);
  // a bare Task (GetTask result) has id + status; status updates use taskId, messages use messageId
  const task = r.task ?? (r.kind === "task" || (r.id && r.status && !su && !au) ? r : null);
  const msg = r.message ?? (r.kind === "message" ? r : null);

  if (su) {
    return {
      type: "status",
      taskId: su.taskId ?? su.task_id,
      contextId: su.contextId ?? su.context_id,
      state: normState(su.status?.state),
      text: messageText(su.status?.message),
    };
  }
  if (au) {
    return { type: "artifact", taskId: au.taskId ?? au.task_id, artifact: artifact(au.artifact) };
  }
  if (task) {
    return {
      type: "task",
      taskId: task.id,
      contextId: task.contextId ?? task.context_id,
      state: normState(task.status?.state),
      text: messageText(task.status?.message),
      artifacts: (task.artifacts ?? []).map(artifact),
    };
  }
  if (msg) return { type: "message", text: messageText(msg) };
  return null;
}

/** Read an SSE response body and call onData for every `data:` payload. */
export async function readSSE(res: Response, onData: (data: string) => void, signal?: AbortSignal) {
  if (!res.body) return;
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    if (signal?.aborted) {
      await reader.cancel();
      return;
    }
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.search(/\r?\n\r?\n/)) !== -1) {
      const block = buffer.slice(0, idx);
      buffer = buffer.slice(idx).replace(/^\r?\n\r?\n/, "");
      const data = block
        .split(/\r?\n/)
        .filter((l) => l.startsWith("data:"))
        .map((l) => l.slice(5).replace(/^ /, ""))
        .join("\n");
      if (data) onData(data);
    }
  }
}

/** Task id carried by a JSON-RPC response, SSE frame or push-notification body (shape-tolerant). */
export function frameTaskId(json: any): string | undefined {
  if (!json) return undefined;
  const r = json.result ?? json;
  const su = r.statusUpdate ?? r.status_update;
  const au = r.artifactUpdate ?? r.artifact_update;
  const task = r.task ?? (r.kind === "task" ? r : undefined);
  return su?.taskId ?? su?.task_id ?? au?.taskId ?? au?.task_id ?? task?.id ?? r.taskId ?? r.message?.taskId;
}

export const stateLabel = (s: string) => s.replace(/-/g, " ");
