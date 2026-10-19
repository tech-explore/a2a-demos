# 06 · Testing

## What is verified, and how much

| Area | Tests | Run where | Status when this was written |
|---|---|---|---|
| Spring version guard | `SpringBootVersionTest` (2 tests) | `mvn test` | Fails the build with a clear message if Spring Boot is not 4.x or Spring Framework not 7.x (stale copy, other parent POM, overriding BOM) |
| `TripRequest` parsing (shared by all agents) | `TripRequestTest` (JUnit 5) | `mvn test` | The same 7 cases were compiled and run with the JDK compiler in the authoring environment: **all passed** |
| Backend end to end | `TravelPlannerE2ETest` (JUnit 5) | `mvn verify` | **Written, not run** (no Maven Central access where it was authored) |
| Frontend: parsers, derivations, push hub | `tests/a2a.test.ts`, `derive.test.ts`, `push-hub.test.ts` | `npm test` | **Passing** (19 tests) |
| Frontend: every API route | `tests/routes.test.ts` against a mock A2A agent | `npm test` | **Passing** (13 tests) |
| Frontend: themes | `tests/theme.test.ts`, `tests/theme-toggle.test.tsx` | `npm test` | **Passing** (15 tests): soft-palette rules, WCAG contrast, pre-paint script, server render |
| Frontend: types and production build | `npm run typecheck`, `npm run build` | CI | **Passing** |
| Java sources | JDK-compiler syntax pass | n/a | No syntax errors; every remaining error was a missing dependency |
| Spring Boot 4.1.1 migration | Checked against the 4.1.1 source tree and the 4.0 migration guide | n/a | Starter names, Jackson 3, auto-configuration and `SpringApplicationBuilder` locations verified; **never built with Maven** |

CI (`.github/workflows/ci.yml`) runs the backend (`mvn -B verify`) and the frontend (`typecheck`, `test`, `build`) on every push.

## Backend end-to-end suite (`backend/e2e-tests`)

`TestStack` boots the flight, hotel, activities and orchestrator applications in one JVM on ports **19100–19103**
(`SpringApplicationBuilder`). All properties are passed explicitly, because the four modules each ship an `application.yml`
and only one would be picked up from a shared classpath. `A2ATestClient` is a raw HTTP + Jackson client, so the tests
assert on the real wire format rather than trusting the SDK on both ends.

| Test | Behaviour proven |
|---|---|
| `everyAgentPublishesAnAgentCard` | Discovery; streaming capability; skills; orchestrator advertises push |
| `happyPathStreamsParallelWorkAndReturnsAStreamedTripPlan` | Streaming, parallel fan-out, final `COMPLETED`, `trip-plan` artifact with structured data, 5 days reassembled from streamed chunks |
| `missingBudgetPausesTheTaskAndTheSameTaskContinuesAfterTheAnswer` | Orchestrator `INPUT_REQUIRED`; same task id continues |
| `hotelQuestionIsRelayedToTheUserAndTheAnswerIsForwardedBack` | Sub-agent `INPUT_REQUIRED` relayed; answer forwarded to the sub-task; `rooms == 2` |
| `optionalAgentFailureDegradesGracefully` | Activities `FAILED` → plan still `COMPLETED`, `activitiesAvailable == false` |
| `rejectionByAnEssentialAgentFailsTheTask` | Flight `REJECTED` → orchestrator `FAILED` |
| `cancelTaskIsPropagatedToSubAgents` | `CancelTask` → orchestrator `CANCELED`, and at least one sub-agent task reaches `CANCELED` (checked via `ListTasks` on the sub-agents) |
| `completedTasksCanBeListedAndFetched` | `ListTasks` contains the task; `GetTask` says `completed` |
| `aRunningTaskCanBeResubscribed` | `SubscribeToTask` from a second client follows a running task to `COMPLETED` |
| `pushNotificationsAreDeliveredToAWebhook` | `SendMessage(returnImmediately)` + webhook config; a `COMPLETED` notification arrives with the right token header |

Runtime is roughly a minute (each scenario takes 5-8 s of simulated work).

## Frontend suites (`frontend/tests`)

- **`a2a.test.ts`:** state normalisation; every frame flavour (status, artifact, wrapped and bare task, message, error, legacy `kind` shape); task-id extraction; SSE reassembly across arbitrary chunk boundaries.
- **`derive.test.ts`:** which frames count, latest state per agent, which graph edges light up and when they go quiet.
- **`push-hub.test.ts`:** token registry, per-task history, live subscribers.
- **`theme.test.ts` / `theme-toggle.test.tsx`:** the palette rules (no pitch black or pure white anywhere, identical tokens in both themes, WCAG AA contrast for every pairing), `resolveTheme()`, the inline pre-paint script executed in a sandbox against it, and a server render of the toggle. Verified by sabotage: a `#000000` background, a `#fff` literal and an unreadable muted colour each made the suite fail.
- **`routes.test.ts`:** each route handler called directly with a `Request` while a mock agent records what arrives: SSE pass-through, follow-up task ids, 502 when the orchestrator is down, background mode (`returnImmediately`, webhook URL, token), webhook auth (401 for a wrong token, 400 for bad JSON), per-task push stream, `ListTasks`, `GetTask`, `SubscribeToTask`, `CancelTask`, agent cards, and the trace multiplexer.

The mock encodes the wire shapes assumed from the SDK source. It proves the UI is consistent with those shapes; the Java e2e
suite is what proves the real agents produce them.

## Found on the first real run
These two problems only appeared once the stack was started for real, which is exactly what the suites above could not prove:

| Symptom | Cause | Fix | Guard added |
|---|---|---|---|
| Startup: *"Field configProvider in DefaultRequestHandler required a bean of type A2AConfigProvider"* | Spring honoured the CDI `@Inject` fields of an SDK class | `DefaultRequestHandler` is no longer a Spring bean (see 03-backend-design.md). Registering `DefaultValuesConfigProvider` instead is **not** a safe fix: it silently turns authorization on and every task call then returns `TaskNotFound` | The e2e suite's task tests (`GetTask`, `ListTasks`, cancel, re-subscribe) would fail loudly if authorization were wrongly on |
| UI error: *"Protocol version '0.3' is not supported. Supported versions: [1.0]"* | The Next.js routes sent no `A2A-Version` header, which the server reads as 0.3 | `rpcHeaders()` adds `A2A-Version: 1.0` to every call | The mock agent now rejects calls without the header exactly like the SDK; route tests fail if any route omits it, plus a test that every recorded call carries it. Verified by deliberately removing the header from one route: 2 tests failed |

## What to expect on the first real run

1. **Compile errors** in the Spring wiring or orchestrator are possible: the SDK API was read from source but never compiled against, and the code targets Spring Boot 4.1.1 / Spring Framework 7, which changed several packages from 3.x.
2. **Wire-shape mismatches** are the most likely test failures (for example a different field name for a part, state or push configuration). The test client's accessors in `A2ATestClient` (`state`, `taskId`, `artifactData`) and the UI's `parseFrame` are the two places to adjust; the Wire log tab shows the exact JSON.
3. **Ports 19100-19103** must be free for the e2e suite.
4. Timing-sensitive tests (`cancelTaskIsPropagatedToSubAgents`, `aRunningTaskCanBeResubscribed`) depend on the simulated delays in the agents; if they flake, lengthen the delays in `AgentSupport.pause` calls rather than loosening the assertions.

## Not covered yet
- Browser-level UI tests (Playwright). The browser download was blocked where this was authored, so the UI is covered at the component-logic and route level instead.
- Load and concurrency behaviour (many simultaneous tasks).
- Security behaviour beyond the webhook token check.
