package org.xplore.a2ademos.travel.hotel;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 07:21
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
public class HotelAgentApplication {

 public static void main(String[] args) {
  SpringApplication.run(HotelAgentApplication.class, args);
 }

 @Bean
 public AgentCard agentCard(@Value("${server.port}") int port) {
  return AgentCard.builder()
    .name("Hotel Agent")
    .description("Finds hotels that fit the trip length and budget.")
    .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), "http://localhost:" + port)))
    .version("1.0.0")
    .documentationUrl("https://a2a-protocol.org")
    .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
    .defaultInputModes(List.of("text"))
    .defaultOutputModes(List.of("text", "application/json"))
    .skills(List.of(AgentSkill.builder()
      .id("find_hotels")
      .name("Find hotels")
      .description("Ranks hotels by value for the given destination, nights and budget.")
      .tags(List.of("travel", "hotels"))
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
     AgentSupport.pause(400);

     if (trip.destination == null) {
      emitter.requiresInput(AgentSupport.agentText(emitter, "Which city do you need a hotel in?"));
      return;
     }
     if (trip.budget == null) {
      emitter.requiresInput(AgentSupport.agentText(emitter, "What is your total budget?"));
      return;
     }

     // Groups need a decision we cannot guess: ask. The orchestrator relays this question
     // to the user (INPUT_REQUIRED propagates up the agent chain) and relays the answer back.
     if (trip.travellers >= 3 && trip.answer == null) {
      emitter.requiresInput(AgentSupport.agentText(emitter, "Group of " + trip.travellers
        + " travellers: one family suite or separate double rooms?"));
      return;
     }
     boolean suite = trip.answer != null && trip.answer.toLowerCase().contains("suite");
     int rooms = suite ? 1 : (int) Math.ceil(trip.travellers / 2.0);
     double factor = suite ? 1.6 : 1.0;

     int nights = Math.max(1, trip.days - 1);
     AgentSupport.progress(emitter, "Searching " + trip.destination + " for " + nights + " nights");
     AgentSupport.pause(1200);
     AgentSupport.progress(emitter, "Filtering by budget and guest ratings");
     AgentSupport.pause(1000);

     List<Map<String, Object>> options = buildOptions(trip, nights, rooms, factor);
     int recommendedTotal = 0;
     for (Map<String, Object> o : options) {
      if (Boolean.TRUE.equals(o.get("recommended"))) {
       recommendedTotal = (Integer) o.get("total");
      }
     }
     Map<String, Object> data = new LinkedHashMap<>();
     data.put("kind", "hotels");
     data.put("currency", trip.currency);
     data.put("nights", nights);
     data.put("rooms", rooms);
     data.put("roomType", suite ? "family suite" : "double room(s)");
     data.put("options", options);
     data.put("recommendedTotal", recommendedTotal);

     AgentSupport.artifact(emitter, "hotel-options",
       "Found " + options.size() + " hotels in " + trip.destination + " for " + nights
         + " nights. Recommended total: " + recommendedTotal + " " + trip.currency + ".",
       data);
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

 private static List<Map<String, Object>> buildOptions(TripRequest trip, int nights, int rooms, double factor) {
  int seed = Math.abs(trip.destination.hashCode());
  // Aim the middle option at roughly a third of the budget, whatever the room setup.
  int target = Math.max(60, (int) (trip.budget / 3.0 / nights / (rooms * factor)));
  String[] names = {"Grand %s Palace", "%s Central Suites", "Hostel & Co %s"};
  int[] perNight = {(int) (target * 1.6), target, (int) (target * 0.45)};
  double[] ratings = {4.8, 4.5, 4.1};
  int[] stars = {5, 4, 3};
  List<Map<String, Object>> options = new ArrayList<>();
  for (int i = 0; i < 3; i++) {
   Map<String, Object> o = new LinkedHashMap<>();
   o.put("name", names[i].formatted(trip.destination));
   o.put("stars", stars[i]);
   o.put("rating", Math.round((ratings[i] - (seed % 3) * 0.1) * 10) / 10.0);
   int nightly = (int) (perNight[i] * factor);
   o.put("pricePerNight", nightly);
   o.put("total", nightly * nights * rooms);
   o.put("recommended", i == 1);
   options.add(o);
  }
  return options;
 }
}
