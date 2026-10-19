package org.xplore.a2ademos.travel.orchestrator;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:34
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.xplore.a2ademos.springsupport.AgentSupport;
import org.xplore.a2ademos.springsupport.TraceBus;
import org.xplore.a2ademos.springsupport.TripRequest;

/**
 * The orchestrator is an A2A *server* (to the UI) and an A2A *client* (to the specialist agents).
 *
 * Flow: parse request -> (ask for missing info: INPUT_REQUIRED) -> fan out to 3 agents in parallel ->
 * relay their progress -> merge artifacts into one trip plan (streamed as artifact chunks).
 *
 * Protocol behaviours demonstrated here:
 * - INPUT_REQUIRED raised by the orchestrator itself (missing destination/budget)
 * - INPUT_REQUIRED relayed from a sub-agent (the hotel agent asks about rooms for groups); the user's answer is
 *   forwarded to that sub-agent's task while the other agents' finished results are kept
 * - CancelTask propagated to every sub-agent task
 * - flights/hotels are essential (failure fails the task); activities are optional (graceful degradation)
 */
public class OrchestratorExecutor implements AgentExecutor {

  static final String FLIGHTS = "flights";
  static final String HOTELS = "hotels";
  static final String ACTIVITIES = "activities";
  private static final List<String> KEYS = List.of(FLIGHTS, HOTELS, ACTIVITIES);

  /** Everything we remember about one orchestrator task between turns. */
  static final class TripState {
    volatile TripRequest trip;
    final Map<String, RemoteCall> calls = new ConcurrentHashMap<>();
    /** Set while a sub-agent's question is waiting for the user's answer. */
    volatile String waitingFor;
  }

  private final TraceBus trace;
  private final Map<String, RemoteAgent> agents = new LinkedHashMap<>();
  private final Map<String, String> labels = Map.of(
    FLIGHTS, "✈️ flight-agent", HOTELS, "🏨 hotel-agent", ACTIVITIES, "🎟️ activities-agent");

  private final Map<String, TripState> states = new ConcurrentHashMap<>();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  public OrchestratorExecutor(TraceBus trace, String flightUrl, String hotelUrl, String activitiesUrl) {
    this.trace = trace;
    agents.put(FLIGHTS, new RemoteAgent("flight-agent", flightUrl, trace));
    agents.put(HOTELS, new RemoteAgent("hotel-agent", hotelUrl, trace));
    agents.put(ACTIVITIES, new RemoteAgent("activities-agent", activitiesUrl, trace));
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    String taskId = context.getTaskId();
    if (context.getTask() == null) {
      emitter.submit();
    }
    boolean keepState = false;
    try {
      TripState state = states.computeIfAbsent(taskId, k -> new TripState());
      String userText = context.getUserInput(" ");
      boolean resuming = state.waitingFor != null;

      emitter.startWork(AgentSupport.agentText(emitter, resuming ? "Thanks, got your answer" : "Reading your request"));
      AgentSupport.pause(500);

      Object lock = new Object();
      if (resuming) {
        forwardAnswer(state, userText, emitter, lock);
      } else {
        TripRequest trip = TripRequest.parse(userText).mergeOver(state.trip).withDefaults();
        state.trip = trip;

        // ---- multi-turn: ask the user for what is missing (task goes to INPUT_REQUIRED)
        if (trip.destination == null) {
          keepState = true;
          emitter.requiresInput(AgentSupport.agentText(emitter,
            "Where would you like to go? For example: “Plan a trip to Lisbon”."));
          return;
        }
        if (trip.budget == null) {
          keepState = true;
          emitter.requiresInput(AgentSupport.agentText(emitter,
            "Nice choice! What is your total budget for the trip? (e.g. 2500 EUR)"));
          return;
        }

        AgentSupport.progress(emitter, "Plan: " + trip.destination + " from " + trip.origin + ", " + trip.days
          + " days, " + trip.travellers + " traveller(s), budget " + trip.budget + " " + trip.currency);
        AgentSupport.pause(400);
        AgentSupport.progress(emitter, "Delegating to the flight, hotel and activities agents in parallel");

        // ---- fan out (parallel A2A client calls)
        String prompt = trip.toSentence();
        for (String key : KEYS) {
          register(state, key, agents.get(key).start(prompt, progress(key, emitter, lock)));
        }
      }

      awaitAll(taskId, state);
      if (cancelled.remove(taskId)) {
        return; // cancel() already emitted CANCELED and propagated it
      }

      // ---- essential agents: a final failure fails the whole task (before bothering the user)
      for (String key : List.of(FLIGHTS, HOTELS)) {
        Task t = result(state.calls.get(key));
        boolean failed = t == null
          || (t.status().state().isFinal() && t.status().state() != TaskState.TASK_STATE_COMPLETED);
        if (failed) {
          String reason = t == null ? "no answer" : describe(t);
          emitter.fail(AgentSupport.agentText(emitter, agents.get(key).name() + " unavailable: " + reason));
          return;
        }
      }

      // ---- a sub-agent needs the user: relay its question (INPUT_REQUIRED propagates up the chain)
      for (String key : KEYS) {
        Task t = result(state.calls.get(key));
        if (t != null && t.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
          state.waitingFor = key;
          keepState = true;
          emitter.requiresInput(AgentSupport.agentText(emitter,
            labels.get(key) + " asks: " + RemoteAgent.text(t.status().message())));
          return;
        }
      }

      AgentSupport.progress(emitter, "Merging results into one itinerary");
      AgentSupport.pause(700);
      publishPlan(state, emitter);
      emitter.complete(AgentSupport.agentText(emitter, "Your trip plan is ready."));
    } catch (CancellationException e) {
      // cancel() already emitted the terminal CANCELED state
    } finally {
      cancelled.remove(taskId);
      if (!keepState) {
        states.remove(taskId);
      }
    }
  }

  @Override
  public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
    String taskId = context.getTaskId();
    cancelled.add(taskId);
    TripState state = states.remove(taskId);
    if (state != null) {
      // Propagate: send A2A CancelTask to every sub-agent task that is still running.
      state.calls.values().forEach(RemoteCall::cancel);
    }
    emitter.cancel(AgentSupport.agentText(emitter, "Trip planning was cancelled."));
  }

  // ------------------------------------------------------------------ steps

  private void forwardAnswer(TripState state, String answer, AgentEmitter emitter, Object lock) {
    String key = state.waitingFor;
    state.waitingFor = null;
    RemoteCall prior = state.calls.get(key);
    AgentSupport.progress(emitter, "Forwarding your answer to " + labels.get(key));
    String text = state.trip.toSentence() + ". User answer: " + answer;
    register(state, key, agents.get(key).followUp(text, prior.contextId(), prior.taskId(), progress(key, emitter, lock)));
  }

  private void publishPlan(TripState state, AgentEmitter emitter) {
    TripRequest trip = state.trip;
    Map<String, Object> flightData = firstData(state.calls.get(FLIGHTS), "flight-options");
    Map<String, Object> hotelData = firstData(state.calls.get(HOTELS), "hotel-options");

    Task activityTask = result(state.calls.get(ACTIVITIES));
    boolean activitiesOk = activityTask != null && activityTask.status().state() == TaskState.TASK_STATE_COMPLETED;
    Map<String, Object> activityData = new LinkedHashMap<>();
    if (activitiesOk) {
      // The activities agent streams one artifact chunk per day; reassemble them in day order.
      List<Map<String, Object>> days = new ArrayList<>(dataParts(state.calls.get(ACTIVITIES), "activity-plan"));
      days.sort(Comparator.comparingInt(d -> number(d.get("day"))));
      activityData.put("kind", "activities");
      activityData.put("plan", days);
    }

    int flightTotal = number(flightData.get("recommendedTotal"));
    int hotelTotal = number(hotelData.get("recommendedTotal"));
    int total = flightTotal + hotelTotal;

    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("kind", "trip-plan");
    plan.put("trip", trip.toMap());
    plan.put("flights", flightData);
    plan.put("hotels", hotelData);
    plan.put("activities", activityData);
    plan.put("activitiesAvailable", activitiesOk);
    plan.put("estimatedTotal", total);
    plan.put("withinBudget", total <= trip.budget);

    StringBuilder summary = new StringBuilder();
    summary.append("Trip to ").append(trip.destination).append(" (").append(trip.days).append(" days): flights ")
      .append(flightTotal).append(" + hotel ").append(hotelTotal).append(" = ").append(total).append(' ')
      .append(trip.currency).append(total <= trip.budget ? " — within" : " — over").append(" your ")
      .append(trip.budget).append(' ').append(trip.currency).append(" budget.");
    if (!activitiesOk) {
      summary.append(" The activities agent was unavailable, so the plan has no day-by-day activities.");
    }

    // Streamed artifact: a text chunk first, then the structured data appended to the same artifact.
    emitter.addArtifact(List.<Part<?>>of(new TextPart(summary.toString())), "trip-plan", "trip-plan", null, false, false);
    AgentSupport.pause(300);
    emitter.addArtifact(List.<Part<?>>of(new DataPart(plan)), "trip-plan", "trip-plan", null, true, true);
  }

  // ------------------------------------------------------------------ helpers

  private void register(TripState state, String key, RemoteCall call) {
    state.calls.put(key, call);
    call.future().whenComplete((task, error) -> {
      if (error != null) {
        trace.publish("note", "in", null, call.agentName(), "call failed: " + error);
      }
    });
  }

  /** Relays a sub-agent's status line as the orchestrator's own WORKING update (kept ordered by a lock). */
  private Consumer<String> progress(String key, AgentEmitter emitter, Object lock) {
    String label = labels.get(key);
    return line -> {
      if (line == null || line.isBlank()) {
        return;
      }
      synchronized (lock) {
        try {
          AgentSupport.progress(emitter, label + ": " + line);
        } catch (RuntimeException ignored) {
          // the orchestrator task may already be finished/cancelled
        }
      }
    };
  }

  private void awaitAll(String taskId, TripState state) {
    CompletableFuture<?>[] futures = state.calls.values().stream()
      .map(call -> call.future().handle((task, error) -> null)) // never fails, so allOf waits for everyone
      .toArray(CompletableFuture[]::new);
    CompletableFuture<Void> all = CompletableFuture.allOf(futures);
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
    try {
      while (!all.isDone()) {
        if (cancelled.contains(taskId) || System.currentTimeMillis() > deadline) {
          return;
        }
        try {
          all.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          // keep polling so cancellation is noticed quickly
        } catch (ExecutionException e) {
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted");
    }
  }

  private static Task result(RemoteCall call) {
    if (call == null) {
      return null;
    }
    CompletableFuture<Task> f = call.future();
    return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
  }

  private static String describe(Task task) {
    String text = RemoteAgent.text(task.status().message());
    return task.status().state().name().replace("TASK_STATE_", "").toLowerCase()
      + (text.isEmpty() ? "" : " (" + text + ")");
  }

  /** First structured payload (DataPart) of the named artifact, as a Map. */
  private static Map<String, Object> firstData(RemoteCall call, String artifactName) {
    List<Map<String, Object>> all = dataParts(call, artifactName);
    return all.isEmpty() ? new LinkedHashMap<>() : all.get(0);
  }

  /** Every structured payload (DataPart) received for the named artifact, in arrival order. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> dataParts(RemoteCall call, String artifactName) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (call == null) {
      return out;
    }
    for (Part<?> part : call.artifactParts(artifactName)) {
      if (part instanceof DataPart dp && dp.data() instanceof Map<?, ?> map) {
        out.add(new LinkedHashMap<>((Map<String, Object>) map));
      }
    }
    return out;
  }

  private static int number(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }
}
