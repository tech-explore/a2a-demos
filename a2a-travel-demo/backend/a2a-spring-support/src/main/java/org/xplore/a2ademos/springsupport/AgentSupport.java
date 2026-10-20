package org.xplore.a2ademos.springsupport;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 15:30
 */

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

/** Small helpers shared by all demo agents. */
public final class AgentSupport {

  private AgentSupport() {
  }

  /** Simulates work. Interruptions (task cancellation) surface as CancellationException. */
  public static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted");
    }
  }

  public static Message agentText(AgentEmitter emitter, String text) {
    return emitter.newAgentMessage(List.<Part<?>>of(new TextPart(text)), null);
  }

  /** Emits a WORKING status update carrying a human-readable progress line. */
  public static void progress(AgentEmitter emitter, String text) {
    emitter.updateStatus(TaskState.TASK_STATE_WORKING, agentText(emitter, text));
  }

  /** A text summary + a structured DataPart: the usual shape of an A2A artifact. */
  public static void artifact(AgentEmitter emitter, String name, String summary, Map<String, Object> data) {
    emitter.addArtifact(List.<Part<?>>of(new TextPart(summary), new org.a2aproject.sdk.spec.DataPart(data)),
      null, name, null);
  }
}
