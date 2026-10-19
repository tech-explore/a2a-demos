package org.xplore.a2ademos.springsupport;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 15:28
 */

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the A2A Java SDK's server components (which are CDI beans in the SDK) as plain Spring beans.
 * The host application only has to provide an {@link AgentCard} bean and an {@link AgentExecutor} bean.
 */
@AutoConfiguration
public class A2AAutoConfiguration {

  static {
    // Our AgentCards are built by hand; skip the SDK's classpath transport check.
    System.setProperty("org.a2aproject.sdk.transport.skipValidation", "true");
  }

  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService a2aExecutor() {
    return Executors.newCachedThreadPool();
  }

  @Bean
  public InMemoryTaskStore a2aTaskStore() {
    return new InMemoryTaskStore();
  }

  @Bean
  public MainEventBus a2aMainEventBus() {
    return new MainEventBus();
  }

  @Bean
  public InMemoryPushNotificationConfigStore a2aPushConfigStore() {
    return new InMemoryPushNotificationConfigStore();
  }

  /**
   * Webhook delivery of task updates. Enabled per agent with {@code a2a.push.enabled=true}.
   * ALLOW_ALL is fine for a localhost demo; use a real URL validator in production (SSRF!).
   */
  @Bean
  public PushNotificationSender a2aPushSender(InMemoryPushNotificationConfigStore store,
                                              @Value("${a2a.push.enabled:false}") boolean enabled) {
    if (!enabled) {
      return (event, snapshot) -> { };
    }
    return new BasePushNotificationSender(store, PushNotificationUrlValidator.ALLOW_ALL);
  }

  @Bean
  public InMemoryQueueManager a2aQueueManager(InMemoryTaskStore taskStore, MainEventBus eventBus) {
    return new InMemoryQueueManager(taskStore, eventBus);
  }

  /**
   * Its package-private @PostConstruct/@PreDestroy methods start/stop the background event thread;
   * Spring honours those jakarta.annotation annotations just like CDI does.
   */
  @Bean
  public MainEventBusProcessor a2aMainEventBusProcessor(MainEventBus eventBus, InMemoryTaskStore taskStore,
                                                        InMemoryQueueManager queueManager,
                                                        PushNotificationSender pushSender) {
    return new MainEventBusProcessor(eventBus, taskStore, pushSender, queueManager);
  }

  /**
   * The request handler is deliberately NOT a Spring bean. {@link DefaultRequestHandler} is a CDI class: it carries
   * {@code @Inject} fields (an A2AConfigProvider and two CDI {@code Instance<>} lookups) and an {@code @PostConstruct}
   * that re-reads its settings from that provider. If Spring managed it, Spring would try to satisfy those injection
   * points and then {@code initConfig()} would overwrite what we set on the builder with the SDK defaults, notably
   * {@code a2a.authorization.required=true}. With no authorization provider the SDK then fails closed and answers
   * every task read/write with TaskNotFound. Building it here keeps it outside the container, so the builder values stand.
   */
  @Bean
  public JSONRPCHandler a2aJsonRpcHandler(AgentCard agentCard, AgentExecutor agentExecutor,
                                          InMemoryTaskStore taskStore, InMemoryQueueManager queueManager,
                                          MainEventBusProcessor processor, ExecutorService executor,
                                          InMemoryPushNotificationConfigStore pushStore,
                                          @Value("${a2a.push.enabled:false}") boolean pushEnabled) {
    DefaultRequestHandler requestHandler = DefaultRequestHandler.builder()
      .agentExecutor(agentExecutor)
      .taskStore(taskStore)
      .queueManager(queueManager)
      .pushConfigStore(pushEnabled ? pushStore : null)
      .pushNotificationsEnabled(pushEnabled)
      .mainEventBusProcessor(processor)
      .executor(executor)
      .eventConsumerExecutor(executor)
      .authorizationRequired(false) // demo: no TaskAuthorizationProvider; real systems should plug one in
      .build();
    return new JSONRPCHandler(agentCard, requestHandler, executor);
  }

  @Bean
  public TraceBus a2aTraceBus(@Value("${a2a.agent.name:agent}") String agentName) {
    return new TraceBus(agentName);
  }

  @Bean
  public A2AController a2aController(JSONRPCHandler handler, TraceBus traceBus) {
    return new A2AController(handler, traceBus);
  }

  @Bean
  public WebMvcConfigurer a2aCors() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins("*").allowedMethods("*");
      }
    };
  }
}
