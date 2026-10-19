package org.xplore.a2ademos.travel.orchestrator;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:31
 */

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UpdateEvent;
import org.xplore.a2ademos.springsupport.TraceBus;

/**
 * One remote A2A agent as seen by the orchestrator:
 * discover (Agent Card) -> send a streaming message -> follow status/artifact updates -> final Task.
 */
public class RemoteAgent {

  /** Calls are started off the caller's thread so the three delegations really run in parallel. */
  private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "remote-agent");
    t.setDaemon(true);
    return t;
  });

  private final String name;
  private final String url;
  private final TraceBus trace;

  public RemoteAgent(String name, String url, TraceBus trace) {
    this.name = name;
    this.url = url;
    this.trace = trace;
  }

  public String name() {
    return name;
  }

  /** Starts a new remote task. {@code onProgress} receives human-readable status lines as they stream in. */
  public RemoteCall start(String prompt, Consumer<String> onProgress) {
    return send(A2A.toUserMessage(prompt), "SendStreamingMessage", prompt, onProgress);
  }

  /** Continues an existing remote task (e.g. answers its INPUT_REQUIRED question). */
  public RemoteCall followUp(String text, String contextId, String taskId, Consumer<String> onProgress) {
    return send(A2A.createUserTextMessage(text, contextId, taskId), "SendStreamingMessage (follow-up)", text, onProgress);
  }

  private RemoteCall send(Message message, String traceMethod, String traceText, Consumer<String> onProgress) {
    RemoteCall call = new RemoteCall(name, trace);
    POOL.execute(() -> {
      try {
        // 1. Discovery: GET /.well-known/agent-card.json
        AgentCard card = A2A.getAgentCard(url);
        trace.publish("delegation", "out", "GetAgentCard", name, card.name() + " @ " + url);

        // 2. A client with a streaming JSON-RPC transport
        BiConsumer<ClientEvent, AgentCard> consumer = (event, c) -> handle(call, event, onProgress);
        Client client = Client.builder(card)
          .addConsumers(List.of(consumer))
          .streamingErrorHandler(call.future()::completeExceptionally)
          .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
          .clientConfig(ClientConfig.builder().setStreaming(true).build())
          .build();
        call.bind(client);

        // 3. Send. Responses arrive via the consumer above.
        trace.publish("delegation", "out", traceMethod, name, traceText);
        client.sendMessage(message);
      } catch (Exception e) {
        call.future().completeExceptionally(e);
      }
    });
    return call;
  }

  private void handle(RemoteCall call, ClientEvent event, Consumer<String> onProgress) {
    if (event instanceof TaskUpdateEvent update) {
      Task task = update.getTask();
      call.remember(task);
      UpdateEvent u = update.getUpdateEvent();
      if (u instanceof TaskStatusUpdateEvent status) {
        String text = text(status.status().message());
        String state = status.status().state().name();
        trace.publish("client-event", "in", "statusUpdate", name, state + (text.isEmpty() ? "" : " · " + text));
        if (!text.isEmpty()) {
          onProgress.accept(text);
        }
      } else if (u instanceof TaskArtifactUpdateEvent artifact) {
        call.addChunk(artifact);
        trace.publish("client-event", "in", "artifactUpdate", name, String.valueOf(artifact.artifact().name()));
        onProgress.accept("received artifact: " + artifact.artifact().name());
      }
      finishIfDone(task, call);
    } else if (event instanceof TaskEvent taskEvent) {
      call.remember(taskEvent.getTask());
      finishIfDone(taskEvent.getTask(), call);
    } else if (event instanceof MessageEvent message) {
      onProgress.accept(text(message.getMessage()));
      call.future().complete(null);
    }
  }

  private static void finishIfDone(Task task, RemoteCall call) {
    TaskState state = task.status().state();
    if (state.isFinal() || state == TaskState.TASK_STATE_INPUT_REQUIRED || state == TaskState.TASK_STATE_AUTH_REQUIRED) {
      call.future().complete(task);
    }
  }

  public static String text(Message message) {
    if (message == null || message.parts() == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Part<?> part : message.parts()) {
      if (part instanceof TextPart tp) {
        sb.append(tp.text());
      }
    }
    return sb.toString();
  }
}
