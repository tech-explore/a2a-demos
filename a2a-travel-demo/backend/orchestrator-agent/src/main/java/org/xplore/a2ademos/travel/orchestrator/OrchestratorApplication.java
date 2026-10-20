package org.xplore.a2ademos.travel.orchestrator;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:38
 */

import java.util.List;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.xplore.a2ademos.springsupport.TraceBus;

@SpringBootApplication
public class OrchestratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrchestratorApplication.class, args);
  }

  @Bean
  public AgentCard agentCard(@Value("${server.port}") int port,
                             @Value("${a2a.push.enabled:false}") boolean push) {
    return AgentCard.builder()
      .name("Trip Orchestrator")
      .description("Plans a whole trip by delegating to flight, hotel and activities agents over A2A.")
      .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), "http://localhost:" + port)))
      .version("1.0.0")
      .documentationUrl("https://a2a-protocol.org")
      .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(push).build())
      .defaultInputModes(List.of("text"))
      .defaultOutputModes(List.of("text", "application/json"))
      .skills(List.of(AgentSkill.builder()
        .id("plan_trip")
        .name("Plan a trip")
        .description("Understands a trip request, fans it out to specialist agents and merges the results.")
        .tags(List.of("travel", "orchestration"))
        .examples(List.of("Plan a 5 day trip to Tokyo from Berlin for 2 people, budget 4000 EUR"))
        .build()))
      .build();
  }

  @Bean
  public AgentExecutor agentExecutor(TraceBus trace,
                                     @Value("${a2a.remote.flight}") String flightUrl,
                                     @Value("${a2a.remote.hotel}") String hotelUrl,
                                     @Value("${a2a.remote.activities}") String activitiesUrl) {
    return new OrchestratorExecutor(trace, flightUrl, hotelUrl, activitiesUrl);
  }
}
