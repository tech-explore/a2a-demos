package org.xplore.a2ademos.travel.flight;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 16:29
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
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.xplore.a2ademos.springsupport.AgentSupport;
import org.xplore.a2ademos.springsupport.TripRequest;

@SpringBootApplication
public class FlightAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(FlightAgentApplication.class, args);
  }

  @Bean
  public AgentCard agentCard(@Value("${server.port}") int port) {
    return AgentCard.builder()
      .name("Flight Agent")
      .description("Searches and ranks flight options between two cities.")
      .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), "http://localhost:" + port)))
      .version("1.0.0")
      .documentationUrl("https://a2a-protocol.org")
      .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
      .defaultInputModes(List.of("text"))
      .defaultOutputModes(List.of("text", "application/json"))
      .skills(List.of(AgentSkill.builder()
        .id("search_flights")
        .name("Search flights")
        .description("Finds flight options and recommends the best value one.")
        .tags(List.of("travel", "flights"))
        .examples(List.of("Trip to Tokyo from Berlin, 5 days, 1 travellers, budget 3000 EUR"))
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
          AgentSupport.pause(500);

          if (trip.destination == null) {
            emitter.requiresInput(AgentSupport.agentText(emitter, "Which city should I fly to?"));
            return;
          }
          if (trip.destination.equalsIgnoreCase("Mars") || trip.destination.equalsIgnoreCase("Atlantis")) {
            emitter.reject(AgentSupport.agentText(emitter,
              "No commercial routes exist to " + trip.destination + "."));
            return;
          }

          AgentSupport.progress(emitter, "Querying airline fare APIs for " + trip.origin + " → " + trip.destination);
          AgentSupport.pause(1100);
          AgentSupport.progress(emitter, "Comparing fares, layovers and total travel time");
          AgentSupport.pause(1100);

          List<Map<String, Object>> options = buildOptions(trip);
          int recommendedTotal = 0;
          for (Map<String, Object> o : options) {
            if (Boolean.TRUE.equals(o.get("recommended"))) {
              recommendedTotal = (Integer) o.get("total");
            }
          }
          Map<String, Object> data = new LinkedHashMap<>();
          data.put("kind", "flights");
          data.put("currency", trip.currency);
          data.put("options", options);
          data.put("recommendedTotal", recommendedTotal);

          AgentSupport.artifact(emitter, "flight-options",
            "Found " + options.size() + " flight options " + trip.origin + " → " + trip.destination
              + ". Recommended total: " + recommendedTotal + " " + trip.currency + ".",
            data);
          emitter.complete();
        } catch (CancellationException e) {
          // cancelled while working; cancel() already emitted the terminal state
        }
      }

      @Override
      public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
      }
    };
  }

  private static List<Map<String, Object>> buildOptions(TripRequest trip) {
    int seed = Math.abs((trip.origin + trip.destination).hashCode());
    String[][] airlines = {{"Lufthansa", "LH"}, {"Air France", "AF"}, {"Emirates", "EK"}, {"KLM", "KL"}, {"Qatar Airways", "QR"}};
    int base = 250 + seed % 300;
    int[] perPerson = {(int) (base * 1.5), base, (int) (base * 0.75)};
    int[] stops = {0, 1, 2};
    int[] hours = {11, 14, 19};
    List<Map<String, Object>> options = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String[] airline = airlines[(seed + i * 2) % airlines.length];
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("airline", airline[0]);
      o.put("flightNo", airline[1] + (100 + (seed + i * 37) % 800));
      o.put("stops", stops[i]);
      o.put("durationHours", hours[i]);
      o.put("pricePerPerson", perPerson[i]);
      o.put("total", perPerson[i] * trip.travellers);
      o.put("recommended", i == 1);
      options.add(o);
    }
    return options;
  }
}
