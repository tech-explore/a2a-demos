package org.xplore.a2ademos.travel.e2e;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 11:00
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.xplore.a2ademos.travel.e2e.A2ATestClient.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives the whole system over real HTTP: UI-style client -> orchestrator -> flight/hotel/activities agents.
 * Each test covers one A2A behaviour the demo advertises.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class TravelPlannerE2ETest {

  private static TestStack stack;
  private static A2ATestClient orchestrator;

  @BeforeAll
  static void boot() {
    stack = TestStack.start();
    orchestrator = TestStack.client(TestStack.ORCHESTRATOR);
  }

  @AfterAll
  static void shutdown() {
    if (stack != null) stack.close();
  }

  // ------------------------------------------------------------------ discovery

  @Test
  void everyAgentPublishesAnAgentCard() throws Exception {
    for (int port : new int[]{TestStack.ORCHESTRATOR, TestStack.FLIGHT, TestStack.HOTEL, TestStack.ACTIVITIES}) {
      Node card = TestStack.client(port).agentCard();
      assertFalse(card.path("name").asText().isBlank(), "card name on port " + port);
      assertTrue(card.path("capabilities").path("streaming").asBoolean(), "streaming capability on port " + port);
      assertTrue(card.path("skills").size() >= 1, "skills on port " + port);
    }
    assertTrue(orchestrator.agentCard().path("capabilities").path("pushNotifications").asBoolean());
  }

  // ------------------------------------------------------------------ happy path

  @Test
  void happyPathStreamsParallelWorkAndReturnsAStreamedTripPlan() throws Exception {
    List<Node> frames = orchestrator.sendStreaming("Plan a 5 day trip to Lisbon from Berlin for 2 people, budget 3000 EUR", null, null);

    List<String> states = states(frames);
    assertTrue(states.contains("working"), "states: " + states);
    assertEquals("completed", lastState(frames));
    assertTrue(artifactNames(frames).contains("trip-plan"));

    // the plan is streamed in two chunks (text, then structured data appended); the data chunk carries the plan
    Node plan = artifactData(frames, "trip-plan").get(0);
    assertTrue(plan.path("estimatedTotal").asInt() > 0);
    assertTrue(plan.path("activitiesAvailable").asBoolean());
    assertEquals(5, plan.path("activities").path("plan").size(), "one activity entry per day, reassembled from streamed chunks");
    assertEquals("Lisbon", plan.path("trip").path("destination").asText());
  }

  // ------------------------------------------------------------------ input-required (orchestrator)

  @Test
  void missingBudgetPausesTheTaskAndTheSameTaskContinuesAfterTheAnswer() throws Exception {
    List<Node> first = orchestrator.sendStreaming("Plan a 4 day trip to Tokyo from Berlin", null, null);
    assertEquals("input-required", lastState(first));
    assertTrue(lastStatusText(first).toLowerCase().contains("budget"), lastStatusText(first));

    String taskId = taskId(first);
    List<Node> second = orchestrator.sendStreaming("3500 EUR", taskId, contextId(first));
    assertEquals("completed", lastState(second));
    assertEquals(taskId, taskId(second), "the answer continues the same task");
    assertEquals("Tokyo", artifactData(second, "trip-plan").get(0).path("trip").path("destination").asText());
  }

  // ------------------------------------------------------------------ input-required relayed from a sub-agent

  @Test
  void hotelQuestionIsRelayedToTheUserAndTheAnswerIsForwardedBack() throws Exception {
    List<Node> first = orchestrator.sendStreaming("Plan a 4 day trip to Madrid from Berlin for 4 people, budget 6000 EUR", null, null);
    assertEquals("input-required", lastState(first));
    assertTrue(lastStatusText(first).contains("hotel-agent asks"), lastStatusText(first));

    List<Node> second = orchestrator.sendStreaming("two double rooms", taskId(first), contextId(first));
    assertEquals("completed", lastState(second));
    Node hotels = artifactData(second, "trip-plan").get(0).path("hotels");
    assertEquals(2, hotels.path("rooms").asInt(), "4 travellers in double rooms = 2 rooms");
  }

  // ------------------------------------------------------------------ failure modes

  @Test
  void optionalAgentFailureDegradesGracefully() throws Exception {
    List<Node> frames = orchestrator.sendStreaming("Plan a 4 day trip to Rome from Berlin, budget 2000 EUR simulate-failure", null, null);
    assertEquals("completed", lastState(frames));
    assertFalse(artifactData(frames, "trip-plan").get(0).path("activitiesAvailable").asBoolean());
  }

  @Test
  void rejectionByAnEssentialAgentFailsTheTask() throws Exception {
    List<Node> frames = orchestrator.sendStreaming("Plan a trip to Atlantis from Berlin, budget 5000 EUR", null, null);
    assertEquals("failed", lastState(frames));
    assertTrue(lastStatusText(frames).toLowerCase().contains("flight-agent"), lastStatusText(frames));
  }

  // ------------------------------------------------------------------ cancellation propagation

  @Test
  void cancelTaskIsPropagatedToSubAgents() throws Exception {
    AtomicReference<String> taskId = new AtomicReference<>();
    CountDownLatch subAgentWorking = new CountDownLatch(1);

    Thread streamThread = new Thread(() -> {
      try {
        orchestrator.stream("SendStreamingMessage",
          Map.of("message", A2ATestClient.userMessage("Plan a 5 day trip to Lisbon from Berlin, budget 3000 EUR", null, null)),
          Duration.ofSeconds(60), frame -> {
            if (taskId.get() == null) taskId.set(taskId(List.of(frame)));
            String text = lastStatusText(List.of(frame));
            if (text != null && text.contains("flight-agent:")) subAgentWorking.countDown();
          });
      } catch (Exception ignored) {
        // stream ends/aborts after cancellation
      }
    });
    streamThread.start();

    assertTrue(subAgentWorking.await(20, TimeUnit.SECONDS), "a sub-agent should start working");
    Node cancelled = orchestrator.rpc("CancelTask", Map.of("id", taskId.get()));
    assertEquals("canceled", A2ATestClient.normState(
      A2ATestClient.result(cancelled).path("status").path("state").asText(
        A2ATestClient.result(cancelled).path("task").path("status").path("state").asText())));

    // at least one sub-agent must have received CancelTask and reached CANCELED
    boolean anyCanceled = false;
    long deadline = System.currentTimeMillis() + 10_000;
    while (!anyCanceled && System.currentTimeMillis() < deadline) {
      for (int port : new int[]{TestStack.FLIGHT, TestStack.HOTEL, TestStack.ACTIVITIES}) {
        Node tasks = A2ATestClient.result(TestStack.client(port).rpc("ListTasks", Map.of())).path("tasks");
        for (Node t : tasks) {
          if ("canceled".equals(A2ATestClient.normState(t.path("status").path("state").asText()))) anyCanceled = true;
        }
      }
      if (!anyCanceled) Thread.sleep(300);
    }
    assertTrue(anyCanceled, "no sub-agent task was cancelled");
    streamThread.join(5000);
  }

  // ------------------------------------------------------------------ GetTask / ListTasks / SubscribeToTask

  @Test
  void completedTasksCanBeListedAndFetched() throws Exception {
    List<Node> frames = orchestrator.sendStreaming("Plan a 3 day trip to Prague from Berlin, budget 1500 EUR", null, null);
    String taskId = taskId(frames);

    Node list = A2ATestClient.result(orchestrator.rpc("ListTasks", Map.of()));
    boolean found = false;
    for (Node t : list.path("tasks")) found |= taskId.equals(t.path("id").asText());
    assertTrue(found, "ListTasks should include " + taskId);

    Node task = A2ATestClient.result(orchestrator.rpc("GetTask", Map.of("id", taskId)));
    Node t = task.has("task") ? task.get("task") : task;
    assertEquals("completed", A2ATestClient.normState(t.path("status").path("state").asText()));
  }

  @Test
  void aRunningTaskCanBeResubscribed() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<String> taskId = new AtomicReference<>();
    Thread first = new Thread(() -> {
      try {
        orchestrator.stream("SendStreamingMessage",
          Map.of("message", A2ATestClient.userMessage("Plan a 5 day trip to Vienna from Berlin, budget 2800 EUR", null, null)),
          Duration.ofSeconds(60), frame -> {
            if (taskId.get() == null) {
              String id = taskId(List.of(frame));
              if (id != null) { taskId.set(id); started.countDown(); }
            }
          });
      } catch (Exception ignored) {
        // the original stream is not what this test is about
      }
    });
    first.start();
    assertTrue(started.await(10, TimeUnit.SECONDS));

    // a second, independent client attaches to the same task and follows it to the end
    List<Node> resumed = orchestrator.stream("SubscribeToTask", Map.of("id", taskId.get()), Duration.ofSeconds(60), null);
    assertNotNull(lastState(resumed));
    assertEquals("completed", lastState(resumed));
    first.join(5000);
  }

  // ------------------------------------------------------------------ push notifications

  @Test
  void pushNotificationsAreDeliveredToAWebhook() throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    List<String> tokens = new CopyOnWriteArrayList<>();
    HttpServer webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    webhook.createContext("/hook", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes()));
      String token = exchange.getRequestHeaders().getFirst("X-A2A-Notification-Token");
      if (token != null) tokens.add(token);
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    webhook.start();
    try {
      String url = "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook";
      Map<String, Object> params = Map.of(
        "message", A2ATestClient.userMessage("Plan a 3 day trip to Oslo from Berlin, budget 2500 EUR", null, null),
        "configuration", Map.of(
          "returnImmediately", true,
          "taskPushNotificationConfig", Map.of("url", url, "token", "t0k3n")));
      Node response = orchestrator.rpc("SendMessage", params);
      assertFalse(response.has("error"), response.toString());

      long deadline = System.currentTimeMillis() + 30_000;
      boolean completed = false;
      while (!completed && System.currentTimeMillis() < deadline) {
        for (String body : bodies) {
          if (body.toUpperCase().contains("COMPLETED")) completed = true;
        }
        if (!completed) Thread.sleep(300);
      }
      assertTrue(completed, "no COMPLETED notification arrived; got " + bodies.size() + " notifications");
      assertTrue(tokens.contains("t0k3n"), "notification token header missing");
    } finally {
      webhook.stop(0);
    }
  }
}
