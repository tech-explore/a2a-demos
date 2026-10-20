package org.xplore.a2ademos.travel.orchestrator;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:29
 */

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.xplore.a2ademos.springsupport.TraceBus;

/**
 * Handle for one in-flight exchange with a remote agent: its eventual Task, the remote task id (so it can be
 * cancelled or continued) and the artifact chunks received so far.
 */
public class RemoteCall {

  private final String agentName;
  private final TraceBus trace;
  private final CompletableFuture<Task> future = new CompletableFuture<>();
  private final Map<String, List<Part<?>>> artifacts = new ConcurrentHashMap<>();

  private volatile Client client;
  private volatile String taskId;
  private volatile String contextId;

  RemoteCall(String agentName, TraceBus trace) {
    this.agentName = agentName;
    this.trace = trace;
  }

  public String agentName() {
    return agentName;
  }

  /** Completes when the remote task is final or interrupted (e.g. INPUT_REQUIRED). May complete with null. */
  public CompletableFuture<Task> future() {
    return future;
  }

  public String taskId() {
    return taskId;
  }

  public String contextId() {
    return contextId;
  }

  void bind(Client client) {
    this.client = client;
  }

  void remember(Task task) {
    if (task != null) {
      this.taskId = task.id();
      this.contextId = task.contextId();
    }
  }

  /** Artifact chunks are accumulated here ourselves: append=true adds, otherwise the artifact is replaced. */
  void addChunk(TaskArtifactUpdateEvent event) {
    String key = event.artifact().name() != null ? event.artifact().name() : event.artifact().artifactId();
    List<Part<?>> parts = artifacts.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    if (!Boolean.TRUE.equals(event.append())) {
      parts.clear();
    }
    parts.addAll(event.artifact().parts());
  }

  /** All parts received so far for the named artifact (empty if none). */
  public List<Part<?>> artifactParts(String name) {
    return artifacts.getOrDefault(name, List.of());
  }

  /** Propagates cancellation: sends A2A CancelTask to the remote agent, if its task is known and still running. */
  public void cancel() {
    Client c = client;
    String id = taskId;
    if (c == null || id == null || future.isDone() && !isRunning()) {
      return;
    }
    try {
      trace.publish("delegation", "out", "CancelTask", agentName, id);
      c.cancelTask(new CancelTaskParams(id));
    } catch (Exception e) {
      trace.publish("note", "in", null, agentName, "cancel failed: " + e.getMessage());
    }
  }

  private boolean isRunning() {
    Task t = future.getNow(null);
    return t != null && !t.status().state().isFinal();
  }
}
