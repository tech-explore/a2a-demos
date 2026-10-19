package org.xplore.a2ademos.springsupport;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 15:29
 */

import static org.a2aproject.sdk.server.ServerCallContext.TRANSPORT_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.HEADERS_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.METHOD_NAME_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.TENANT_KEY;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonSyntaxException;
import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.server.util.sse.SseFormatter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Spring MVC front door for the A2A Java SDK's transport-agnostic JSON-RPC handler.
 * (The SDK's reference server is Quarkus; this class is the Spring equivalent of its routes.)
 *
 * <ul>
 *   <li>GET  /.well-known/agent-card.json  - the Agent Card (discovery)</li>
 *   <li>POST /                             - A2A JSON-RPC 2.0 (blocking JSON, or SSE for streaming methods)</li>
 *   <li>GET  /trace/stream                 - demo-only SSE feed of everything this agent sees</li>
 * </ul>
 */
@RestController
@CrossOrigin
public class A2AController {

  private static final Logger LOG = LoggerFactory.getLogger(A2AController.class);

  private final JSONRPCHandler handler;
  private final TraceBus trace;

  public A2AController(JSONRPCHandler handler, TraceBus trace) {
    this.handler = handler;
    this.trace = trace;
  }

  @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public String agentCard() throws Exception {
    return JsonUtil.toJson(handler.getAgentCard());
  }

  @GetMapping(value = "/trace/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter traceStream() {
    return trace.subscribe();
  }

  @PostMapping(value = "/", consumes = MediaType.ALL_VALUE)
  public ResponseEntity<?> invoke(@RequestBody String body, HttpServletRequest http) {
    ServerCallContext context = createCallContext(http);
    trace.publish("rpc-request", "in", null, http.getHeader("X-A2A-Caller"), body);

    A2ARequest<?> request;
    try {
      request = JSONRPCUtils.parseRequestBody(body, "");
      context.getState().put(METHOD_NAME_KEY, request.getMethod());
    } catch (A2AError e) {
      return json(new A2AErrorResponse(e), null);
    } catch (InvalidParamsJsonMappingException e) {
      return json(new A2AErrorResponse(e.getId(),
        new org.a2aproject.sdk.spec.InvalidParamsError(null, e.getMessage(), null)), null);
    } catch (MethodNotFoundJsonMappingException e) {
      return json(new A2AErrorResponse(e.getId(),
        new org.a2aproject.sdk.spec.MethodNotFoundError(null, e.getMessage(), null)), null);
    } catch (IdJsonMappingException e) {
      return json(new A2AErrorResponse(e.getId(),
        new org.a2aproject.sdk.spec.InvalidRequestError(null, e.getMessage(), null)), null);
    } catch (JsonMappingException e) {
      return json(new A2AErrorResponse(new org.a2aproject.sdk.spec.InvalidRequestError(null, e.getMessage(), null)), null);
    } catch (JsonSyntaxException | JsonProcessingException e) {
      return json(new A2AErrorResponse(new JSONParseError(e.getMessage())), null);
    } catch (Throwable t) {
      LOG.error("Internal error while parsing request", t);
      return json(new A2AErrorResponse(new org.a2aproject.sdk.spec.InternalError("Internal error")), null);
    }

    String method = request.getMethod();
    try {
      if (request instanceof NonStreamingJSONRPCRequest<?> nonStreaming) {
        return json(processNonStreaming(nonStreaming, context), method);
      }
      return stream(request, context, method);
    } catch (A2AError e) {
      return json(new A2AErrorResponse(request.getId(), e), method);
    } catch (Throwable t) {
      LOG.error("Internal error while processing request", t);
      return json(new A2AErrorResponse(request.getId(), new org.a2aproject.sdk.spec.InternalError("Internal error")), method);
    }
  }

  // ---------------------------------------------------------------- non-streaming

  private A2AResponse<?> processNonStreaming(NonStreamingJSONRPCRequest<?> request, ServerCallContext ctx) {
    if (request instanceof GetTaskRequest req) return handler.onGetTask(req, ctx);
    if (request instanceof CancelTaskRequest req) return handler.onCancelTask(req, ctx);
    if (request instanceof ListTasksRequest req) return handler.onListTasks(req, ctx);
    if (request instanceof CreateTaskPushNotificationConfigRequest req) return handler.setPushNotificationConfig(req, ctx);
    if (request instanceof GetTaskPushNotificationConfigRequest req) return handler.getPushNotificationConfig(req, ctx);
    if (request instanceof SendMessageRequest req) return handler.onMessageSend(req, ctx);
    if (request instanceof ListTaskPushNotificationConfigsRequest req) return handler.listPushNotificationConfigs(req, ctx);
    if (request instanceof DeleteTaskPushNotificationConfigRequest req) return handler.deletePushNotificationConfig(req, ctx);
    if (request instanceof GetExtendedAgentCardRequest req) return handler.onGetExtendedCardRequest(req, ctx);
    return new A2AErrorResponse(request.getId(), new UnsupportedOperationError());
  }

  private ResponseEntity<String> json(A2AResponse<?> response, String method) {
    String payload = toJson(response);
    trace.publish("rpc-response", "out", method, null, payload);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
  }

  // ---------------------------------------------------------------- streaming (SSE)

  private ResponseEntity<?> stream(A2ARequest<?> request, ServerCallContext ctx, String method) {
    ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
    if (request instanceof SendStreamingMessageRequest req) {
      // Subscribe synchronously (on the request thread) so no early event can be missed.
      pipe(handler.onMessageSendStream(req, ctx), emitter, method);
    } else if (request instanceof SubscribeToTaskRequest req) {
      pipe(handler.onSubscribeToTask(req, ctx), emitter, method);
    } else {
      return json(new A2AErrorResponse(request.getId(), new UnsupportedOperationError()), method);
    }
    return ResponseEntity.ok()
      .contentType(MediaType.TEXT_EVENT_STREAM)
      .header(HttpHeaders.CACHE_CONTROL, "no-cache")
      .header("X-Accel-Buffering", "no")
      .body(emitter);
  }

  private <T extends A2AResponse<?>> void pipe(Flow.Publisher<T> publisher, ResponseBodyEmitter emitter, String method) {
    AtomicLong eventId = new AtomicLong();
    AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

    emitter.onError(t -> cancel(subscription));
    emitter.onTimeout(() -> cancel(subscription));

    publisher.subscribe(new Flow.Subscriber<T>() {
      @Override
      public void onSubscribe(Flow.Subscription s) {
        subscription.set(s);
        s.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(T item) {
        try {
          String sse = SseFormatter.formatResponseAsSSE(item, eventId.getAndIncrement());
          trace.publish("sse-frame", "out", method, null, extractJson(sse));
          emitter.send(sse, MediaType.TEXT_EVENT_STREAM);
        } catch (IOException e) {
          cancel(subscription);
        } catch (Exception e) {
          LOG.warn("Failed to write SSE frame", e);
        }
      }

      @Override
      public void onError(Throwable throwable) {
        LOG.warn("Stream failed", throwable);
        emitter.completeWithError(throwable);
      }

      @Override
      public void onComplete() {
        emitter.complete();
      }
    });
  }

  private static void cancel(AtomicReference<Flow.Subscription> subscription) {
    Flow.Subscription s = subscription.get();
    if (s != null) {
      s.cancel();
    }
  }

  // ---------------------------------------------------------------- helpers

  /** SseFormatter yields "data: {json}\nid: N\n\n"; reuse it to get the exact wire JSON. */
  private static String toJson(A2AResponse<?> response) {
    return extractJson(SseFormatter.formatResponseAsSSE(response, 0));
  }

  private static String extractJson(String sse) {
    int start = sse.indexOf("data: ") + "data: ".length();
    int end = sse.lastIndexOf("\nid: ");
    return end > start ? sse.substring(start, end) : sse.substring(start).trim();
  }

  private ServerCallContext createCallContext(HttpServletRequest http) {
    Map<String, String> headers = new HashMap<>();
    for (String name : Collections.list(http.getHeaderNames())) {
      headers.put(name, http.getHeader(name));
    }
    Map<String, Object> state = new HashMap<>();
    state.put(HEADERS_KEY, headers);
    state.put(TENANT_KEY, "");
    state.put(TRANSPORT_KEY, TransportProtocol.JSONRPC);

    String requestedVersion = http.getHeader(A2AHeaders.A2A_VERSION);
    List<String> extensionHeaders = Collections.list(http.getHeaders(A2AHeaders.A2A_EXTENSIONS));
    Set<String> requestedExtensions = A2AExtensions.getRequestedExtensions(extensionHeaders);
    return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, requestedExtensions, requestedVersion);
  }
}
