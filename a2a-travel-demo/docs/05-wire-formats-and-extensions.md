# 05 · Wire formats, limitations and extensions

## Example messages
Shapes follow the A2A v1.0 JSON-RPC binding as implemented by the Java SDK (protobuf-JSON field names).
They are indicative; the **Wire log** tab shows the exact bytes from your run.

### Required request header: `A2A-Version`
Every JSON-RPC call must send `A2A-Version: 1.0`. **If the header is missing the server assumes protocol 0.3** (backward
compatibility) and, because this stack speaks only 1.0, answers with
`Protocol version '0.3' is not supported. Supported versions: [1.0]`. The Java SDK client sends it automatically; the Next.js
routes send it through `rpcHeaders()` (`lib/server-config.ts`) and the e2e test client sets it explicitly.

### Request (sent by `/api/chat`)
```json
{
  "jsonrpc": "2.0",
  "id": "9f3c…",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "messageId": "b1a2…",
      "role": "ROLE_USER",
      "parts": [{ "text": "Plan a 5 day trip to Lisbon from Berlin for 2 people, budget 3000 EUR" }]
    }
  }
}
```
To continue a task, add `"taskId"` and `"contextId"` to `message`.

### Streaming response (SSE)
```
data: {"jsonrpc":"2.0","id":"9f3c…","result":{"statusUpdate":{"taskId":"…","contextId":"…","status":{"state":"TASK_STATE_WORKING","message":{"role":"ROLE_AGENT","parts":[{"text":"Reading your request"}]}}}}}
id: 1

```
Other `result` variants: `task`, `message`, `artifactUpdate`.

### Method names used
`SendStreamingMessage`, `CancelTask` (UI → orchestrator and orchestrator → agents via the SDK client).
The server also handles `SendMessage`, `GetTask`, `ListTasks`, `SubscribeToTask` and the push-notification config methods.

## Known limitations
| Area | Limitation |
|---|---|
| Verification | The Java modules were written against the SDK source but never compiled or run in the authoring environment; first run of `mvn verify` is the real check (see [06-testing.md](06-testing.md)) |
| Persistence | Tasks, queues and traces are in memory; restarting an agent loses them |
| Cancellation | Propagated to sub-agents, but best effort: a sub-task that already finished is left alone, and a cancel racing the very first sub-agent event may arrive before its task id is known |
| Push notifications | Orchestrator only; in-memory config store; `ALLOW_ALL` URL validator (do not use outside localhost) |
| Task history | In-memory; lost when the orchestrator restarts, so a stored `localStorage` task id may point at a task that no longer exists (the UI ignores that) |
| Message parts | Text and structured data only; no file or image parts |
| Security | No authentication or TLS; CORS is wide open; intended for localhost only |
| Parsing | Trip text is parsed with regexes (capitalised city names, simple budget patterns), not an LLM |
| Transports | JSON-RPC only; gRPC and REST bindings are not wired |
| Tracing | `/trace/stream` is a demo feature and is unauthenticated; do not expose it |

## Extension ideas
1. **LLM-backed agents:** replace `TripRequest` and the simulated logic with real model calls (the executors stay the same).
2. **Push for every agent:** enable `a2a.push.enabled` on the specialists and let the orchestrator register webhooks with them instead of holding SSE streams open.
3. **File/image parts:** return a PDF itinerary or a map image as a file part with a URL or bytes.
4. **Persistence:** swap in the SDK's JPA task store (`extras/task-store-database-jpa`) and Kafka queue manager for multi-instance runs.
5. **Authentication:** add Spring Security and map the principal into `ServerCallContext`; declare `securitySchemes` on the cards.
6. **More agents:** add a module with an `AgentCard` and `AgentExecutor`, register its URL in the orchestrator config, and add a node to `AgentGraph`.
7. **Containers/Kubernetes:** each agent is a standalone Spring Boot jar; set the `a2a.remote.*` URLs and the card URLs via environment.
