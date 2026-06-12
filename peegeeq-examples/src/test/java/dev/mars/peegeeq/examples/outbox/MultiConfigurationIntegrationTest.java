package dev.mars.peegeeq.examples.outbox;


import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.config.MultiConfigurationManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import java.util.Properties;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating multi-configuration capabilities in a realistic scenario.
 *
 * <p>Uses {@link SharedTestContainers} for the PostgreSQL container (reused across
 * test classes) and chained {@link io.vertx.core.Future} composition end-to-end
 * subscribe and send failures propagate to the test context instead of being silently
 * swallowed into consumer-arrival timeouts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class MultiConfigurationIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationIntegrationTest.class);

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private MultiConfigurationManager configManager;
    private final List<QueueFactory> openFactories = new ArrayList<>();

    /**
     * Track a factory so it gets closed in @AfterEach (on the JUnit worker thread,
     * never on the event-loop where {@code QueueFactory.close()} would fail its guard).
     */
    private <F extends QueueFactory> F track(F factory) {
        openFactories.add(factory);
        return factory;
    }

    /**
     * Generate unique queue name for test independence
     */
    private String getUniqueQueueName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.min-size", "2")
                .property("peegeeq.database.pool.max-size", "10")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.health.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.migration.enabled", "true")
                .property("peegeeq.migration.auto-migrate", "true")
                .property("peegeeq.queue.dead-letter-enabled", "true")
                .build();

        configManager = new MultiConfigurationManager(new SimpleMeterRegistry());

        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) configManager.getFactoryProvider());

        configManager.registerConfiguration("high-throughput", new PeeGeeQConfiguration("default", testProps));
        configManager.registerConfiguration("low-latency", new PeeGeeQConfiguration("default", testProps));
        configManager.registerConfiguration("reliable", new PeeGeeQConfiguration("default", testProps));
        configManager.registerConfiguration("test", new PeeGeeQConfiguration("default", testProps));

        configManager.start()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        for (QueueFactory f : openFactories) {
            try {
                f.close();
            } catch (Exception e) {
                logger.warn("Error closing queue factory: {}", e.getMessage());
            }
        }
        openFactories.clear();

        if (configManager == null) {
            testContext.completeNow();
            return;
        }
        configManager.close()
            .onFailure(err -> logger.warn("Error during configManager cleanup: {}", err.getMessage()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> testContext.completeNow());
    }

    @Test
    void testMultipleQueueConfigurationsInSameApplication(Vertx vertx, VertxTestContext testContext) {
        QueueFactory batchProcessingQueue = track(configManager.createFactory("high-throughput", "outbox"));
        QueueFactory realTimeQueue = track(configManager.createFactory("low-latency", "outbox"));
        QueueFactory transactionalQueue = track(configManager.createFactory("reliable", "outbox"));

        Future.all(
                        batchProcessingQueue.isHealthy(),
                        realTimeQueue.isHealthy(),
                        transactionalQueue.isHealthy())
                .compose(cf -> {
                    assertTrue((Boolean) cf.resultAt(0));
                    assertTrue((Boolean) cf.resultAt(1));
                    assertTrue((Boolean) cf.resultAt(2));
                    return testBatchProcessing(batchProcessingQueue)
                            .compose(v -> testRealTimeProcessing(realTimeQueue, vertx))
                            .compose(v -> testTransactionalProcessing(transactionalQueue));
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testConfigurationBuilderIntegration(VertxTestContext testContext) {
        QueueFactory reliableQueue1 = track(configManager.createFactory("test", "outbox"));
        QueueFactory reliableQueue2 = track(configManager.createFactory("test", "outbox"));
        QueueFactory reliableQueue3 = track(configManager.createFactory("test", "outbox"));

        Future.all(
                        reliableQueue1.isHealthy(),
                        reliableQueue2.isHealthy(),
                        reliableQueue3.isHealthy())
                .compose(cf -> {
                    assertTrue((Boolean) cf.resultAt(0));
                    assertTrue((Boolean) cf.resultAt(1));
                    assertTrue((Boolean) cf.resultAt(2));
                    assertEquals("outbox", reliableQueue1.getImplementationType());
                    assertEquals("outbox", reliableQueue2.getImplementationType());
                    assertEquals("outbox", reliableQueue3.getImplementationType());
                    return Future.<Void>succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testConcurrentMultiConfigurationUsage(VertxTestContext testContext) {
        QueueFactory[] factories = new QueueFactory[4];
        factories[0] = track(configManager.createFactory("high-throughput", "outbox"));
        factories[1] = track(configManager.createFactory("low-latency", "outbox"));
        factories[2] = track(configManager.createFactory("reliable", "outbox"));
        factories[3] = track(configManager.createFactory("test", "outbox"));

        int messagesPerFactory = 3;
        AtomicInteger totalProcessed = new AtomicInteger(0);
        List<Future<Void>> perFactoryDone = new ArrayList<>();

        for (int i = 0; i < factories.length; i++) {
            final int factoryIndex = i;
            final QueueFactory factory = factories[i];
            final String queueName = getUniqueQueueName("test-topic-" + i);
            final MessageProducer<String> producer = factory.createProducer(queueName, String.class);
            final MessageConsumer<String> consumer = factory.createConsumer(queueName, String.class);

            AtomicInteger remaining = new AtomicInteger(messagesPerFactory);
            Promise<Void> factoryDone = Promise.promise();

            Future<Void> chain = consumer.subscribe(message -> {
                        totalProcessed.incrementAndGet();
                        if (remaining.decrementAndGet() == 0) factoryDone.tryComplete();
                        return Future.succeededFuture();
                    })
                    .compose(v -> {
                        List<Future<Void>> sends = new ArrayList<>();
                        for (int j = 0; j < messagesPerFactory; j++) {
                            sends.add(producer.send("Message " + j + " from factory " + factoryIndex,
                                    Map.of("factory", String.valueOf(factoryIndex)),
                                    "correlation-" + factoryIndex + "-" + j,
                                    "routing-" + factoryIndex + "-" + j));
                        }
                        return Future.all(sends).<Void>mapEmpty();
                    })
                    .onFailure(factoryDone::tryFail)
                    .compose(v -> factoryDone.future());

            perFactoryDone.add(chain);
        }

        Future.all(perFactoryDone)
                .<Void>mapEmpty()
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(factories.length * messagesPerFactory, totalProcessed.get());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Void> testBatchProcessing(QueueFactory factory) {
        String queueName = getUniqueQueueName("batch-events");
        MessageProducer<BatchEvent> producer = factory.createProducer(queueName, BatchEvent.class);
        MessageConsumer<BatchEvent> consumer = factory.createConsumer(queueName, BatchEvent.class);

        int count = 10;
        AtomicInteger remaining = new AtomicInteger(count);
        Promise<Void> done = Promise.promise();

        return consumer.subscribe(message -> {
                    if (remaining.decrementAndGet() == 0) done.tryComplete();
                    return Future.succeededFuture();
                })
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        BatchEvent event = new BatchEvent("BATCH-" + i, "Processing batch " + i, i * 10);
                        sends.add(producer.send(event, Map.of("batch-type", "high-throughput"),
                                "correlation-" + i, "batch-" + i));
                    }
                    return Future.all(sends).<Void>mapEmpty();
                })
                .onFailure(done::tryFail)
                .compose(v -> done.future())
                .eventually(() -> {
                    consumer.close();
                    producer.close();
                    return Future.<Void>succeededFuture();
                });
    }

    private Future<Void> testRealTimeProcessing(QueueFactory factory, Vertx vertx) {
        String queueName = getUniqueQueueName("realtime-events");
        MessageProducer<RealTimeEvent> producer = factory.createProducer(queueName, RealTimeEvent.class);
        MessageConsumer<RealTimeEvent> consumer = factory.createConsumer(queueName, RealTimeEvent.class);

        int count = 3;
        AtomicInteger remaining = new AtomicInteger(count);
        Promise<Void> done = Promise.promise();

        return consumer.subscribe(message -> {
                    long latency = System.currentTimeMillis() - message.getPayload().getTimestamp();
                    logger.debug("Real-time processed: {} (latency: {}ms)",
                            message.getPayload().getEventId(), latency);
                    if (remaining.decrementAndGet() == 0) done.tryComplete();
                    return Future.succeededFuture();
                })
                .compose(v -> {
                    Future<Void> sendChain = Future.succeededFuture();
                    for (int i = 1; i <= count; i++) {
                        final int idx = i;
                        sendChain = sendChain.compose(x -> {
                            RealTimeEvent event = new RealTimeEvent("RT-" + idx, System.currentTimeMillis(), "Real-time event " + idx);
                            return producer.send(event, Map.of("priority", "HIGH"), "correlation-" + idx, "realtime-" + idx)
                                    .compose(sent -> vertx.timer(100).mapEmpty());
                        });
                    }
                    return sendChain;
                })
                .onFailure(done::tryFail)
                .compose(v -> done.future())
                .eventually(() -> {
                    consumer.close();
                    producer.close();
                    return Future.<Void>succeededFuture();
                });
    }

    private Future<Void> testTransactionalProcessing(QueueFactory factory) {
        String queueName = getUniqueQueueName("critical-events");
        MessageProducer<CriticalEvent> producer = factory.createProducer(queueName, CriticalEvent.class);
        MessageConsumer<CriticalEvent> consumer = factory.createConsumer(queueName, CriticalEvent.class);

        int count = 2;
        AtomicInteger remaining = new AtomicInteger(count);
        Promise<Void> done = Promise.promise();

        return consumer.subscribe(message -> {
                    logger.debug("Critical processed: {} (importance: {})",
                            message.getPayload().getEventId(), message.getPayload().getImportanceLevel());
                    if (remaining.decrementAndGet() == 0) done.tryComplete();
                    return Future.succeededFuture();
                })
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        CriticalEvent event = new CriticalEvent("CRITICAL-" + i, "URGENT", "Critical system event " + i);
                        sends.add(producer.send(event, Map.of("importance", "CRITICAL"),
                                "correlation-" + i, "critical-" + i));
                    }
                    return Future.all(sends).<Void>mapEmpty();
                })
                .onFailure(done::tryFail)
                .compose(v -> done.future())
                .eventually(() -> {
                    consumer.close();
                    producer.close();
                    return Future.<Void>succeededFuture();
                });
    }

    // Event classes for testing
    public static class BatchEvent {
        private String batchId;
        private String description;
        private int recordCount;
        
        public BatchEvent() {}
        
        public BatchEvent(String batchId, String description, int recordCount) {
            this.batchId = batchId;
            this.description = description;
            this.recordCount = recordCount;
        }
        
        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getRecordCount() { return recordCount; }
        public void setRecordCount(int recordCount) { this.recordCount = recordCount; }
    }
    
    public static class RealTimeEvent {
        private String eventId;
        private long timestamp;
        private String data;
        
        public RealTimeEvent() {}
        
        public RealTimeEvent(String eventId, long timestamp, String data) {
            this.eventId = eventId;
            this.timestamp = timestamp;
            this.data = data;
        }
        
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }
    
    public static class CriticalEvent {
        private String eventId;
        private String importanceLevel;
        private String message;
        
        public CriticalEvent() {}
        
        public CriticalEvent(String eventId, String importanceLevel, String message) {
            this.eventId = eventId;
            this.importanceLevel = importanceLevel;
            this.message = message;
        }
        
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public String getImportanceLevel() { return importanceLevel; }
        public void setImportanceLevel(String importanceLevel) { this.importanceLevel = importanceLevel; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
