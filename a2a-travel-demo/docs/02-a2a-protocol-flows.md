# 02 · A2A protocol flows

All flows below are real traffic you can watch in the UI (Wire log and Task timeline tabs).

## Task state machine (as used by the demo)

```mermaid
stateDiagram-v2
    [*] --> submitted
    submitted --> working
    working --> working: status updates (progress text)
    working --> input_required: agent needs more info
    input_required --> working: user replies on the same taskId
    working --> completed
    working --> failed
    working --> rejected
    working --> canceled: CancelTask
    completed --> [*]
    failed --> [*]
    rejected --> [*]
    canceled --> [*]
```
Wire names are `TASK_STATE_SUBMITTED`, `TASK_STATE_WORKING`, `TASK_STATE_INPUT_REQUIRED`, and so on.
Terminal states are final; `input-required` is interrupted (the stream ends, but the task can continue).

## 1. Discovery

```mermaid
sequenceDiagram
    participant UI as Next.js /api/cards
    participant A as Any agent
    UI->>A: GET /.well-known/agent-card.json
    A-->>UI: AgentCard (name, skills, capabilities.streaming, supportedInterfaces[JSONRPC url])
```
The orchestrator does the same before each delegation (`A2A.getAgentCard(url)`).

## 2. Happy path: parallel delegation with streaming

```mermaid
sequenceDiagram
    participant UI as Browser + /api/chat
    participant O as Orchestrator
    participant F as Flight
    participant H as Hotel
    participant A as Activities

    UI->>O: POST / SendStreamingMessage "Plan a 5 day trip to Lisbon..."
    O-->>UI: SSE: status SUBMITTED, WORKING "Reading your request"
    O-->>UI: SSE: WORKING "Plan: Lisbon from Berlin, 5 days, budget 3000 EUR"

    par fan-out (A2A client calls)
        O->>F: GET agent card, then POST / SendStreamingMessage
        O->>H: GET agent card, then POST / SendStreamingMessage
        O->>A: GET agent card, then POST / SendStreamingMessage
    end

    F-->>O: SSE: WORKING (progress) ... artifact flight-options, COMPLETED
    H-->>O: SSE: WORKING (progress) ... artifact hotel-options, COMPLETED
    A-->>O: SSE: WORKING (progress) ... artifact activity-plan, COMPLETED
    O-->>UI: SSE: relayed progress lines ("✈️ flight-agent: Comparing fares...")

    O-->>UI: SSE: artifact trip-plan (text summary + structured DataPart)
    O-->>UI: SSE: status COMPLETED "Your trip plan is ready."
```
Sub-agent progress is relayed as the orchestrator's own WORKING updates. The merged result is a single `trip-plan` artifact.

## 3. Multi-turn: `input-required`

```mermaid
sequenceDiagram
    participant UI as Browser
    participant O as Orchestrator
    UI->>O: SendStreamingMessage "Plan a 4 day trip to Tokyo from Berlin"
    O-->>UI: status INPUT_REQUIRED "What is your total budget?"
    Note over O: partial request stored by taskId
    UI->>O: SendStreamingMessage "3500 EUR" (same taskId + contextId)
    Note over O: executor runs again, parsed budget merged with stored request
    O-->>UI: status WORKING ... (continues like the happy path)
```
The UI remembers `taskId`/`contextId` from the last frame and attaches them when the task is `input-required`.

## 3b. Multi-turn: a sub-agent's question is relayed (INPUT_REQUIRED propagates up the chain)

```mermaid
sequenceDiagram
    participant UI as Browser
    participant O as Orchestrator
    participant F as Flight
    participant H as Hotel
    participant A as Activities
    UI->>O: SendStreamingMessage "...4 days to Madrid for 4 people, budget 6000 EUR"
    par fan-out
        O->>F: SendStreamingMessage
        O->>H: SendStreamingMessage
        O->>A: SendStreamingMessage
    end
    H-->>O: INPUT_REQUIRED "Group of 4: one family suite or separate double rooms?"
    F-->>O: COMPLETED + artifact (kept by the orchestrator)
    A-->>O: COMPLETED + artifacts (kept)
    O-->>UI: INPUT_REQUIRED "hotel-agent asks: Group of 4 ..."
    UI->>O: SendStreamingMessage "two double rooms" (same taskId + contextId)
    O->>H: SendStreamingMessage follow-up on the hotel's own taskId/contextId
    H-->>O: WORKING ... artifact hotel-options, COMPLETED
    Note over O: only the hotel is re-run, flight and activities results are reused
    O-->>UI: trip-plan artifact, COMPLETED
```
The orchestrator keeps a `TripState` per task (parsed trip, one `RemoteCall` per sub-agent, and which agent is waiting).

## 4. Partial failure (optional agent fails)

```mermaid
sequenceDiagram
    participant O as Orchestrator
    participant A as Activities
    participant F as Flight
    participant H as Hotel
    O->>A: SendStreamingMessage (text contains "simulate-failure")
    A-->>O: WORKING "Planning day 1", then FAILED "timed out on day 2 (simulated)"
    F-->>O: COMPLETED + artifact
    H-->>O: COMPLETED + artifact
    Note over O: activities are optional, so the plan is built without them
    O-->>O: trip-plan {activitiesAvailable:false}, COMPLETED
```

## 5. Rejection (essential agent refuses)

```mermaid
sequenceDiagram
    participant O as Orchestrator
    participant F as Flight
    O->>F: SendStreamingMessage "Trip to Atlantis..."
    F-->>O: REJECTED "No commercial routes exist to Atlantis."
    Note over O: flights and hotels are essential
    O-->>O: FAILED "Flights unavailable: rejected (...)"
```

## 6. Cancellation, propagated to sub-agents

```mermaid
sequenceDiagram
    participant UI as Browser + /api/cancel
    participant O as Orchestrator
    participant S as Flight / Hotel / Activities
    UI->>O: POST / CancelTask {id: taskId}
    O->>S: CancelTask {id: subTaskId} (for every running sub-task)
    S-->>O: Task (CANCELED)
    O->>O: AgentExecutor.cancel(): mark cancelled, emit CANCELED
    Note over O: SDK also interrupts the running execute() thread,<br/>the wait loop notices and returns
    O-->>UI: Task (state CANCELED)
```
`RemoteCall` remembers each sub-agent's task id as soon as the first event arrives, so `cancel()` can address it.

## 7. Task history and re-attaching (GetTask, ListTasks, SubscribeToTask)

```mermaid
sequenceDiagram
    participant UI as Browser
    participant N as Next.js routes
    participant O as Orchestrator
    UI->>N: GET /api/tasks
    N->>O: ListTasks
    O-->>UI: tasks[] (state, last message, timestamp)
    UI->>N: open a task: GET /api/tasks/{id}
    N->>O: GetTask {id}
    alt task still running
        UI->>N: POST /api/subscribe {taskId}
        N->>O: SubscribeToTask {id}
        O-->>UI: SSE: current Task snapshot, then live updates
    else final
        O-->>UI: stored Task incl. artifacts (rendered as the plan)
    end
```
After a page refresh the UI resumes the last running task automatically (its id is kept in `localStorage`).

## 8. Push notifications (background mode)

```mermaid
sequenceDiagram
    participant UI as Browser
    participant N as Next.js
    participant O as Orchestrator
    UI->>N: POST /api/chat {background: true}
    N->>O: SendMessage {returnImmediately: true,<br/>taskPushNotificationConfig {url: /api/push/webhook, token}}
    O-->>N: Task (WORKING), returned immediately
    N-->>UI: Task
    UI->>N: GET /api/push/stream?taskId=...  (SSE)
    loop every task update
        O-)N: POST /api/push/webhook  header X-A2A-Notification-Token
        N-)UI: relayed frame (status / artifact / task)
    end
```
The webhook rejects calls whose token the UI server did not hand out (HTTP 401).

## 9. Streamed artifacts

An artifact can be delivered in chunks that share one `artifactId`: the first chunk has `append=false`, later chunks
`append=true`, and the final one `lastChunk=true`.
- **Activities agent:** one chunk per day (`TextPart` + `DataPart {day, activities}`).
- **Orchestrator:** the `trip-plan` artifact is a text chunk followed by an appended structured-data chunk.
- The orchestrator accumulates chunks itself (`RemoteCall.artifactParts`) and reassembles the days in order, rather than
  relying on the SDK client's aggregated task.

## Decision table: who decides what

| Situation | Orchestrator reaction |
|---|---|
| Destination or budget missing | Emit `INPUT_REQUIRED`, store partial request |
| Flight or hotel ended in a non-completed final state | Emit `FAILED` with the sub-agent's reason |
| A sub-agent returns `INPUT_REQUIRED` | Emit `INPUT_REQUIRED` with its question; forward the answer to that sub-task |
| `CancelTask` | Cancel every running sub-task, then emit `CANCELED` |
| Activities not `COMPLETED` | Continue, flag `activitiesAvailable: false` |
| No answer within 60 s | Stops waiting; missing results are treated as failures |
