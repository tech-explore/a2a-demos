package org.xplore.a2ademos.travel.activities;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:10
 */

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.xplore.a2ademos.springsupport.AgentSupport;
import org.xplore.a2ademos.springsupport.TripRequest;

@SpringBootApplication
public class ActivitiesAgentApplication {

  private static final String ARTIFACT_ID = "activity-plan";

  private static final String[][] IDEAS = {
    {"Old town walking tour", "Local food market", "Sunset viewpoint"},
    {"Museum of history", "Street-food crawl", "River or harbour cruise"},
    {"Day trip to a nearby village", "Cooking class", "Live music evening"},
    {"Botanical garden", "Neighbourhood cafés", "Rooftop dinner"},
    {"Local crafts workshop", "Hidden-gems bike ride", "Farewell tasting menu"}};

  public static void main(String[] args) {
    SpringApplication.run(ActivitiesAgentApplication.class, args);
  }

  @Bean
  public AgentCard agentCard(@Value("${server.port}") int port) {
    return AgentCard.builder()
      .name("Activities Agent")
      .description("Builds a day-by-day activity plan for the destination.")
      .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), "http://localhost:" + port)))
      .version("1.0.0")
      .documentationUrl("https://a2a-protocol.org")
      .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
      .defaultInputModes(List.of("text"))
      .defaultOutputModes(List.of("text", "application/json"))
      .skills(List.of(AgentSkill.builder()
        .id("plan_activities")
        .name("Plan activities")
        .description("Suggests things to do for each day of the trip.")
        .tags(List.of("travel", "activities", "itinerary"))
        .examples(List.of("Trip to Lisbon from Berlin, 4 days, 2 travellers, budget 2000 EUR"))
        .build()))
      .build();
  }

  @Bean
  public AgentExecutor agentExecutor() {
    return new AgentExecutor() {
      @Override
      public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        if (context.getTask() == null) {
          emitter.submit();
        }
        TripRequest trip = TripRequest.parse(context.getUserInput(" ")).withDefaults();
        try {
          emitter.startWork(AgentSupport.agentText(emitter, "Reading the trip request"));
          AgentSupport.pause(400);

          if (trip.destination == null) {
            emitter.requiresInput(AgentSupport.agentText(emitter, "Which destination should I plan for?"));
            return;
          }

          List<Map<String, Object>> plan = new ArrayList<>();
          int days = Math.min(trip.days, 5);
          for (int day = 1; day <= days; day++) {
            AgentSupport.progress(emitter, "Planning day " + day + " in " + trip.destination);
            AgentSupport.pause(800);
            if (trip.simulateFailure && day == 2) {
              // Demonstrates the FAILED terminal state; the orchestrator degrades gracefully.
              emitter.fail(AgentSupport.agentText(emitter,
                "Activities provider timed out while planning day 2 (simulated failure)."));
              return;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("day", day);
            d.put("activities", List.of(IDEAS[day - 1]));
            plan.add(d);

            // Streamed artifact: one chunk per day, appended to the same artifact id.
            // lastChunk=true tells the consumer the artifact is complete.
            emitter.addArtifact(
              List.<Part<?>>of(new TextPart("Day " + day + ": " + String.join(", ", IDEAS[day - 1])),
                new DataPart(d)),
              ARTIFACT_ID, "activity-plan", null, day > 1, day == days);
          }
          emitter.complete();
        } catch (CancellationException e) {
          // cancelled while working
        }
      }

      @Override
      public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
      }
    };
  }
}
