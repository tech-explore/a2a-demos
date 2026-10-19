package org.xplore.a2ademos.travel.e2e;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:57
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.Gson;

/**
 * A deliberately raw A2A client (plain HTTP + Gson), so the tests assert on the real wire format
 * instead of trusting the SDK on both ends.
 */
final class A2ATestClient {

  private static final Gson GSON = new Gson();
  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;

  A2ATestClient(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  // ---------------------------------------------------------------- building requests

  static Map<String, Object> userMessage(String text, String taskId, String contextId) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("messageId", UUID.randomUUID().toString());
    message.put("role", "ROLE_USER");
    message.put("parts", List.of(Map.of("text", text)));
    if (taskId != null) message.put("taskId", taskId);
    if (contextId != null) message.put("contextId", contextId);
    return message;
  }

  private String envelope(String method, Map<String, Object> params) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("jsonrpc", "2.0");
    body.put("id", UUID.randomUUID().toString());
    body.put("method", method);
    body.put("params", params);
    return GSON.toJson(body);
  }

  private HttpRequest.Builder post(String method, Map<String, Object> params, Duration timeout) throws IOException {
    return HttpRequest.newBuilder(URI.create(baseUrl + "/"))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      // Required: without it the server assumes protocol 0.3 and rejects the call (v1.0 only)
      .header("A2A-Version", "1.0")
      .POST(HttpRequest.BodyPublishers.ofString(envelope(method, params)));
  }

  // ---------------------------------------------------------------- calls

  Node agentCard() throws Exception {
    HttpResponse<String> res = http.send(
      HttpRequest.newBuilder(URI.create(baseUrl + "/.well-known/agent-card.json")).GET().build(),
      HttpResponse.BodyHandlers.ofString());
    return Node.parse(res.body());
  }

  /** Blocking JSON-RPC call; returns the whole JSON-RPC response. */
  Node rpc(String method, Map<String, Object> params) throws Exception {
    HttpResponse<String> res = http.send(post(method, params, Duration.ofSeconds(30)).build(),
      HttpResponse.BodyHandlers.ofString());
    return Node.parse(res.body());
  }

  /** Streaming JSON-RPC call; invokes onFrame for every SSE data frame and returns all frames when the stream ends. */
  List<Node> stream(String method, Map<String, Object> params, Duration timeout, Consumer<Node> onFrame) throws Exception {
    HttpResponse<java.io.InputStream> res = http.send(
      post(method, params, timeout).header("Accept", "text/event-stream").build(),
      HttpResponse.BodyHandlers.ofInputStream());
    List<Node> frames = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) {
          Node frame = Node.parse(line.substring(5).trim());
          frames.add(frame);
          if (onFrame != null) onFrame.accept(frame);
        }
      }
    }
    return frames;
  }

  List<Node> sendStreaming(String text, String taskId, String contextId) throws Exception {
    return stream("SendStreamingMessage", Map.of("message", userMessage(text, taskId, contextId)),
      Duration.ofSeconds(60), null);
  }

  // ---------------------------------------------------------------- reading frames (shape-tolerant)

  static Node result(Node frame) {
    return frame.has("result") ? frame.get("result") : frame;
  }

  static String normState(String raw) {
    return raw == null ? null : raw.replace("TASK_STATE_", "").toLowerCase().replace('_', '-');
  }

  /** Task state carried by this frame (status update or task snapshot), normalised, or null. */
  static String state(Node frame) {
    Node r = result(frame);
    if (r.has("statusUpdate")) return normState(r.path("statusUpdate").path("status").path("state").asText(null));
    if (r.has("task")) return normState(r.path("task").path("status").path("state").asText(null));
    return null;
  }

  static List<String> states(List<Node> frames) {
    List<String> out = new ArrayList<>();
    for (Node f : frames) {
      String s = state(f);
      if (s != null) out.add(s);
    }
    return out;
  }

  static String lastState(List<Node> frames) {
    List<String> s = states(frames);
    return s.isEmpty() ? null : s.get(s.size() - 1);
  }

  static String taskId(List<Node> frames) {
    for (Node f : frames) {
      Node r = result(f);
      if (r.has("statusUpdate")) return r.path("statusUpdate").path("taskId").asText(null);
      if (r.has("task")) return r.path("task").path("id").asText(null);
    }
    return null;
  }

  static String contextId(List<Node> frames) {
    for (Node f : frames) {
      Node r = result(f);
      if (r.has("statusUpdate")) return r.path("statusUpdate").path("contextId").asText(null);
      if (r.has("task")) return r.path("task").path("contextId").asText(null);
    }
    return null;
  }

  /** Text of the status message on the last frame that has one. */
  static String lastStatusText(List<Node> frames) {
    String text = null;
    for (Node f : frames) {
      Node r = result(f);
      Node status = r.has("statusUpdate") ? r.path("statusUpdate").path("status") : r.path("task").path("status");
      for (Node part : status.path("message").path("parts")) {
        if (part.has("text")) text = part.get("text").asText();
      }
    }
    return text;
  }

  /** Names of all artifacts announced through artifactUpdate frames. */
  static List<String> artifactNames(List<Node> frames) {
    List<String> names = new ArrayList<>();
    for (Node f : frames) {
      Node r = result(f);
      if (r.has("artifactUpdate")) names.add(r.path("artifactUpdate").path("artifact").path("name").asText());
    }
    return names;
  }

  /** All DataPart payloads (parts with a "data" object) of the named artifact, in order of arrival. */
  static List<Node> artifactData(List<Node> frames, String artifactName) {
    List<Node> out = new ArrayList<>();
    for (Node f : frames) {
      Node r = result(f);
      if (!r.has("artifactUpdate")) continue;
      Node artifact = r.path("artifactUpdate").path("artifact");
      if (!artifactName.equals(artifact.path("name").asText())) continue;
      for (Node part : artifact.path("parts")) {
        if (part.has("data")) out.add(part.get("data"));
      }
    }
    return out;
  }
}
