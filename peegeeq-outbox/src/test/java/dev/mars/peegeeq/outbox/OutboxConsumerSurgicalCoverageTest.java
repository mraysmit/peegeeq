package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Surgical tests targeting specific uncovered branches in OutboxConsumer.
 * Created to bridge gap from 84.38% to 90%+ coverage.
 * 
 * Focus areas based on JaCoCo analysis:
 * - Configuration-based branch variations (consumerThreads, maxRetries, batchSize)
 * - Error path coverage in retry logic
 * - Pool acquisition failure scenarios
 * - Consumer group name tracking
 * - Message completion edge cases
 * - Executor states during shutdown
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerSurgicalCoverageTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerSurgicalCoverageTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private SimpleMeterRegistry meterRegistry;
    private String testTopic;

    @BeforeEach
    void setup() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        testTopic = "surgical-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup(VertxTestContext testContext) throws Exception {
        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        factoryClose
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    private Future<Void> startManager(PeeGeeQConfiguration config, boolean createProducer) {
        meterRegistry = new SimpleMeterRegistry();
        manager = new PeeGeeQManager(config, meterRegistry);
        return manager.start().map(v -> {
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, config);
            if (createProducer) {
                producer = outboxFactory.createProducer(testTopic, String.class);
            }
            consumer = outboxFactory.createConsumer(testTopic, String.class);
            return null;
        });
    }

    private Future<Double> awaitCounter(Vertx vertx, String meterName, long deadline) {
        Counter counter = meterRegistry.find(meterName).tag("topic", testTopic).counter();
        if (counter != null && counter.count() > 0) {
            return Future.succeededFuture(counter.count());
        }
        if (System.currentTimeMillis() >= deadline) {
            return Future.failedFuture(new AssertionError(
                    "Counter " + meterName + " was not incremented for topic " + testTopic));
        }
        return vertx.timer(50).compose(v -> awaitCounter(vertx, meterName, deadline));
    }

    /**
     * Test consumer with multi-threaded configuration to cover consumerThreads branch.
     * OutboxConsumer constructor uses configuration.getQueueConfig().getConsumerThreads().
     */
    @Test
    void testConsumerWithMultipleThreads(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.consumer-threads", "4")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        Checkpoint checkpoint = testContext.checkpoint(3);
        AtomicInteger receivedCount = new AtomicInteger(0);

        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    receivedCount.incrementAndGet();
                    checkpoint.flag();
                    return Future.succeededFuture();
                }))
                .compose(v -> Future.all(
                        producer.send("msg1"),
                        producer.send("msg2"),
                        producer.send("msg3")).mapEmpty())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process all messages with multi-threaded executor");
        assertEquals(3, receivedCount.get(), "Should process exactly 3 messages");
    }

    /**
     * Test consumer with custom batch size to cover batchSize configuration branch.
     * processAvailableMessages() uses configuration.getQueueConfig().getBatchSize().
     */
    @Test
    void testConsumerWithCustomBatchSize(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.batch-size", "5")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        Checkpoint checkpoint = testContext.checkpoint(5);
        AtomicInteger receivedCount = new AtomicInteger(0);

        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    receivedCount.incrementAndGet();
                    checkpoint.flag();
                    return Future.succeededFuture();
                }))
                .compose(v -> Future.all(
                        producer.send("batch-msg-0"),
                        producer.send("batch-msg-1"),
                        producer.send("batch-msg-2"),
                        producer.send("batch-msg-3"),
                        producer.send("batch-msg-4")).mapEmpty())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process batch of messages");
        assertEquals(5, receivedCount.get(), "Should process all batch messages");
    }

    /**
     * Test message retry with configuration-based maxRetries.
     * handleMessageFailureWithRetry() checks configuration.getQueueConfig().getMaxRetries().
     */
    @Test
    void testRetryWithConfiguredMaxRetries(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint firstAttemptCheckpoint = testContext.checkpoint();

        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    attemptCount.incrementAndGet();
                    firstAttemptCheckpoint.flag();
                    throw new RuntimeException("Intentional failure for retry test");
                }))
                .compose(v -> producer.send("retry-msg"))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should attempt message processing");
        assertTrue(attemptCount.get() >= 1);
    }

    /**
     * Test setConsumerGroupName() to cover consumer group tracking branch.
     */
    @Test
    void testSetConsumerGroupName(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        Checkpoint checkpoint = testContext.checkpoint();
        startManager(config, true)
                .compose(v -> {
                    OutboxConsumer<?> outboxConsumer = assertInstanceOf(OutboxConsumer.class, consumer);
                    outboxConsumer.setConsumerGroupName("test-group");
                    return consumer.subscribe(message -> {
                        checkpoint.flag();
                        return Future.succeededFuture();
                    });
                })
                .compose(v -> producer.send("group-msg"))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process message with consumer group name set");
    }

    /**
     * Test exception in message handler that completes exceptionally.
     * processMessageWithCompletion() handles both direct exceptions and failed futures.
     */
    @Test
    void testHandlerCompletesExceptionally(Vertx vertx, VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    return Future.failedFuture(new RuntimeException("Async failure"));
                }))
                .compose(v -> producer.send("async-fail"))
                .compose(v -> awaitCounter(vertx, "peegeeq.messages.failed.by.topic",
                        System.currentTimeMillis() + 10_000))
                .onSuccess(count -> testContext.verify(() -> {
                    assertEquals(1.0, count, "Failed handler should increment the per-topic failure counter");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
                "Failed handler should produce an observable failure metric");
    }

    /**
     * Test message with all header fields populated including correlationId.
     * processRow() adds correlationId to headers if present.
     */
    @Test
    void testMessageWithCorrelationId(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        Checkpoint checkpoint = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        String correlationId = "corr-" + UUID.randomUUID();

        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    receivedHeaders.set(message.getHeaders());
                    checkpoint.flag();
                    return Future.succeededFuture();
                }))
                .compose(v -> producer.send("correlation-msg", headers, correlationId))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message");
        assertNotNull(receivedHeaders.get(), "Should have headers");
        assertEquals(correlationId, receivedHeaders.get().get("correlationId"), "Should have correlation ID in headers");
    }

    /**
     * Test subscribing when already subscribed to cover "Already subscribed" warning branch.
     */
    @Test
    void testDoubleSubscribe(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, false)
                .compose(v -> consumer.subscribe(message -> Future.succeededFuture()))
                .compose(v -> consumer.subscribe(message -> Future.succeededFuture()))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    /**
     * Test subscribe after consumer is closed to cover IllegalStateException branch.
     */
    @Test
    void testSubscribeAfterClose(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, false)
                .onSuccess(v -> testContext.verify(() -> {
                    consumer.close();
                    Future<Void> result = consumer.subscribe(message -> Future.succeededFuture());
                    assertTrue(result.failed(), "subscribe on closed consumer should return a failed future");
                    assertInstanceOf(IllegalStateException.class, result.cause(),
                            "Should fail with IllegalStateException when subscribing to closed consumer");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    /**
     * Test unsubscribe without prior subscribe to cover compareAndSet false branch.
     */
    @Test
    void testUnsubscribeWithoutSubscribe(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, false)
                .onSuccess(v -> testContext.verify(() -> {
                    consumer.unsubscribe();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    /**
     * Test message with null headers to cover header parsing edge case.
     */
    @Test
    void testMessageWithNullHeaders(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        Checkpoint checkpoint = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    receivedHeaders.set(message.getHeaders());
                    checkpoint.flag();
                    return Future.succeededFuture();
                }))
                .compose(v -> producer.send("null-header-msg", null))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message");
        assertNotNull(receivedHeaders.get(), "Headers map should not be null (should be empty map)");
    }

    /**
     * Test message with empty headers to cover parseHeadersFromJsonObject empty case.
     */
    @Test
    void testMessageWithEmptyHeaders(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        Checkpoint checkpoint = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    receivedHeaders.set(message.getHeaders());
                    checkpoint.flag();
                    return Future.succeededFuture();
                }))
                .compose(v -> producer.send("empty-header-msg", new HashMap<>()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message");
        assertNotNull(receivedHeaders.get(), "Headers should not be null");
        assertTrue(receivedHeaders.get().isEmpty() || receivedHeaders.get().size() <= 1, 
            "Headers should be empty or contain only system headers");
    }

    /**
     * Test message processing metrics recording to cover metrics branch.
     */
    @Test
    void testMessageMetricsRecording(Vertx vertx, VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> Future.succeededFuture()))
                .compose(v -> producer.send("metrics-msg"))
                .compose(v -> awaitCounter(vertx, "peegeeq.messages.processed.by.topic",
                        System.currentTimeMillis() + 10_000))
                .onSuccess(count -> testContext.verify(() -> {
                    assertEquals(1.0, count, "Successful processing should increment the per-topic counter");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
                "Successful processing metric should become observable");
    }

    /**
     * Test message failure metrics recording to cover failure metrics branch.
     */
    @Test
    void testMessageFailureMetricsRecording(Vertx vertx, VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, true)
                .compose(v -> consumer.subscribe(message -> {
                    throw new IllegalArgumentException("Test failure for metrics");
                }))
                .compose(v -> producer.send("failure-metrics-msg"))
                .compose(v -> awaitCounter(vertx, "peegeeq.messages.failed.by.topic",
                        System.currentTimeMillis() + 10_000))
                .onSuccess(count -> testContext.verify(() -> {
                    assertEquals(1.0, count, "Failed processing should increment the per-topic failure counter");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
                "Failed processing metric should become observable");
    }

    /**
     * Test double close to cover close() compareAndSet false branch.
     */
    @Test
    void testDoubleClose(VertxTestContext testContext) throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        startManager(config, false)
                .onSuccess(v -> testContext.verify(() -> {
                    consumer.close();
                    consumer.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}
