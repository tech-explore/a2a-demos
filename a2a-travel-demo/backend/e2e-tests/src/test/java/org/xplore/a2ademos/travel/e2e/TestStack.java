package org.xplore.a2ademos.travel.e2e;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:48
 */

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.xplore.a2ademos.travel.activities.ActivitiesAgentApplication;
import org.xplore.a2ademos.travel.flight.FlightAgentApplication;
import org.xplore.a2ademos.travel.hotel.HotelAgentApplication;
import org.xplore.a2ademos.travel.orchestrator.OrchestratorApplication;

/**
 * Boots all four agents inside the test JVM. Every property is passed explicitly because the four modules each ship
 * an application.yml with the same name and only one of them would be picked up from a shared classpath.
 */
final class TestStack implements AutoCloseable {

 static final int ORCHESTRATOR = 19100;
 static final int FLIGHT = 19101;
 static final int HOTEL = 19102;
 static final int ACTIVITIES = 19103;

 private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

 static TestStack start() {
  TestStack stack = new TestStack();
  stack.boot(FlightAgentApplication.class, FLIGHT, "flight-agent");
  stack.boot(HotelAgentApplication.class, HOTEL, "hotel-agent");
  stack.boot(ActivitiesAgentApplication.class, ACTIVITIES, "activities-agent");
  stack.boot(OrchestratorApplication.class, ORCHESTRATOR, "orchestrator-agent",
    "a2a.push.enabled=true",
    "a2a.remote.flight=http://localhost:" + FLIGHT,
    "a2a.remote.hotel=http://localhost:" + HOTEL,
    "a2a.remote.activities=http://localhost:" + ACTIVITIES);
  return stack;
 }

 private void boot(Class<?> app, int port, String name, String... extra) {
  List<String> props = new ArrayList<>(List.of(
    "server.port=" + port,
    "spring.application.name=" + name,
    "a2a.agent.name=" + name,
    "spring.main.banner-mode=off",
    "logging.level.root=WARN"));
  props.addAll(List.of(extra));
  contexts.add(new SpringApplicationBuilder(app).properties(props.toArray(String[]::new)).run());
 }

 static A2ATestClient client(int port) {
  return new A2ATestClient("http://localhost:" + port);
 }

 @Override
 public void close() {
  for (int i = contexts.size() - 1; i >= 0; i--) {
   contexts.get(i).close();
  }
 }
}
