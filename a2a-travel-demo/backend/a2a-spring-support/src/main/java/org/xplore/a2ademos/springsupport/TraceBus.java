package org.xplore.a2ademos.springsupport;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 15:30
 */

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tiny in-memory "network tap" for one agent. The Next.js app subscribes to every agent's
 * /trace/stream and merges them into one live view.
 */
public class TraceBus {

  private static final int BUFFER_SIZE = 400;

  private final String agentName;
  private final AtomicLong seq = new AtomicLong();
  private final Deque<TraceEvent> buffer = new ConcurrentLinkedDeque<>();
  private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

  public TraceBus(String agentName) {
    this.agentName = agentName;
  }

  public String agentName() {
    return agentName;
  }

  public void publish(String kind, String direction, String method, String peer, String payload) {
    TraceEvent event = new TraceEvent(seq.incrementAndGet(), System.currentTimeMillis(),
      agentName, kind, direction, method, peer, payload);
    buffer.addLast(event);
    while (buffer.size() > BUFFER_SIZE) {
      buffer.pollFirst();
    }
    for (SseEmitter emitter : subscribers) {
      send(emitter, event);
    }
  }

  public SseEmitter subscribe() {
    SseEmitter emitter = new SseEmitter(0L); // no timeout
    subscribers.add(emitter);
    emitter.onCompletion(() -> subscribers.remove(emitter));
    emitter.onTimeout(() -> subscribers.remove(emitter));
    emitter.onError(t -> subscribers.remove(emitter));
    for (TraceEvent event : buffer) {
      send(emitter, event);
    }
    return emitter;
  }

  private void send(SseEmitter emitter, TraceEvent event) {
    try {
      emitter.send(SseEmitter.event().name("trace").data(event, MediaType.APPLICATION_JSON));
    } catch (Exception e) {
      subscribers.remove(emitter);
    }
  }
}
