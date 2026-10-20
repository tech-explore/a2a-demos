# Release notes

## 1.0.0 · Initial release

First version of the demo.
- Four Spring Boot 4.1.1 agents (orchestrator, flight, hotel, activities) on the Java A2A SDK, with a Spring MVC bridge for the
  SDK's CDI-based request handler (`a2a-spring-support`).
- Next.js 16 UI: chat, animated agent network graph, task timeline, raw wire log and Agent Card viewer.
- A2A features: Agent Card discovery, `SendStreamingMessage` with SSE, task lifecycle, parallel delegation, artifacts with
  structured data, `input-required` raised by the orchestrator, `failed` / `rejected` handling with graceful degradation, `CancelTask`.
- Four scenarios (*Happy path*, *Input required*, *Partial failure*, *Rejected*).
- Architecture documents
- A2A explained document
- `Future demo concepts document`

---

**Protocol behaviours**
- **Relayed `input-required`.** The hotel agent asks "family suite or separate double rooms?" for groups of 3 or more.
  The orchestrator surfaces the question to the user, forwards the answer to the hotel's own task, and reuses the flight and
  activities results it already has. New UI scenario: *Relayed question*.
- **Cancellation propagation.** `CancelTask` on the orchestrator now sends `CancelTask` to every running sub-agent task.
- **Task history and re-attach.** New *Tasks* tab backed by `ListTasks`. *Open* a finished task (`GetTask`) or *Attach* to a
  running one (`SubscribeToTask`). Refresh the page mid-run and the chat re-attaches automatically.
- **Push notifications.** A *background (push)* toggle sends `SendMessage` with `returnImmediately` and a webhook; the
  orchestrator posts task updates to a token-checked endpoint and the UI shows them live.
- **Streamed artifacts.** The activities agent streams its plan one day at a time (`append` / `lastChunk`); the final
  `trip-plan` is a text chunk followed by an appended data chunk. The orchestrator reassembles chunks itself.
- **Hotel room logic.** Group size now drives room count and price (family suite versus double rooms).


### 
### Compatibility

| Component | Version |
| --- | --- |
| A2A Java SDK | 1.3.0.Final |
| A2A protocol | v1.0 (JSON-RPC over HTTP + SSE) |
| Spring Boot | 4.1.1 (Spring Framework 7, Tomcat 11, Jackson 3) |
| Java | 21 |
| Next.js / React | 16.4.0 / 19.2 |
| Node | 20+ (CI uses 22) |
| gson / protobuf-java | 2.14.0 / 4.35.1 |

### Known issues and limitations
- **No browser-level UI tests** (Playwright's browser download was blocked where this was built); the UI is covered at the
  component-logic and route level.
- **Cancellation is best effort.** A sub-task that already finished is left alone, and a cancel racing the very first
  sub-agent event may arrive before the sub-task id is known.
- **In-memory state.** Tasks, queues, traces and push configuration are lost on restart; a stored `localStorage` task id may
  then point at a task that no longer exists (the UI ignores it).
- **Security is demo-grade.** No authentication or TLS, open CORS, an unauthenticated `/trace/stream`, and an
  `ALLOW_ALL` push-URL validator. Localhost only.
- **Push is orchestrator-only**, and message parts are text and structured data only (no file or image parts).
- **Simulated agents.** Trip text is parsed with regexes; no LLM is involved.
- **Patch releases.** Spring Boot 4.1.1 was the latest GA when this was prepared; bump the version in `backend/pom.xml` if a
  newer 4.1.x exists.

### What's next
- Database-backed task store and durable queues; authentication with declared security schemes and signed agent cards.
- LLM-backed agents and registry-based discovery.
- Playwright UI tests; an auto-generated sequence diagram from the live trace.

---
