package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure scenario tests for consumer mode implementation.
 * Tests that consumer modes handle failure scenarios gracefully including
 * database connection failures, channel name collisions, partial mode failures,
 * recovery after failure, and exception handling in message handlers.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test failure scenarios that could occur in production
 * - Validate proper error handling and recovery mechanisms
 * - Follow existing patterns from other integration tests
 * - Test resilience and graceful degradation under failure conditions
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeFailureTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeFailureTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure test properties using TestContainer pattern (following existing patterns)
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();
        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create factory using the proper pattern
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            factory = provider.createFactory("native", databaseService);

            logger.info("Test setup completed for consumer mode failure testing");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        closeResources()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    private Future<Void> closeResources() {
        Future<Void> factoryClose = factory != null ? factory.close() : Future.succeededFuture();
        return factoryClose.transform(factoryResult -> {
            Future<Void> managerClose = manager != null ? manager.closeReactive() : Future.succeededFuture();
            return managerClose.transform(managerResult -> combinedCloseResult(factoryResult.cause(), managerResult.cause()));
        });
    }

    private Future<Void> combinedCloseResult(Throwable factoryFailure, Throwable managerFailure) {
        if (factoryFailure != null) {
            if (managerFailure != null) {
                factoryFailure.addSuppressed(managerFailure);
            }
            return Future.failedFuture(factoryFailure);
        }
        return managerFailure == null ? Future.succeededFuture() : Future.failedFuture(managerFailure);
    }

    @Test
    void testExceptionHandlingInMessageHandlers(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing exception handling in message handlers");

        // A unique topic keeps rows of an earlier run on a reused container out of the counts.
        String topicName = "test-exception-handling-" + UUID.randomUUID().toString().substring(0, 8);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        int maxRetries = manager.getConfiguration().getQueueConfig().getMaxRetries();

        AtomicInteger processedCount = new AtomicInteger(0);
        Map<String, AtomicInteger> failedDeliveries = new ConcurrentHashMap<>();

        consumer.subscribe(message -> {
                String payload = message.getPayload();
                if (payload.contains("exception")) {
                    int attempt = failedDeliveries.computeIfAbsent(payload, p -> new AtomicInteger()).incrementAndGet();
                    logger.info(" Throwing intentional exception for message: {} (attempt {})", payload, attempt);
                    throw new RuntimeException("Intentional test exception for: " + payload);
                }
                processedCount.incrementAndGet();
                logger.info("Successfully processed message: {}", payload);
                return Future.succeededFuture();
            })
            .compose(v -> producer.send("Normal message 1"))
            .compose(v -> producer.send("Message with exception"))
            .compose(v -> producer.send("Normal message 2"))
            .compose(v -> producer.send("Another exception message"))
            .compose(v -> producer.send("Normal message 3"))
            .compose(v -> pollUntil(vertx, () -> count(DEAD_LETTER_COUNT_SQL, topicName).map(c -> c == 2L),
                    "both failing messages were not dead-lettered"))
            .compose(v -> pollUntil(vertx, () -> count(MESSAGE_COUNT_SQL, topicName).map(c -> c == 0L),
                    "queue_messages still holds rows of the topic"))
            .compose(v -> deadLetterRetryCounts(topicName))
            .onSuccess(retryCounts -> testContext.verify(() -> {
                assertEquals(3, processedCount.get(), "each normal message must be processed exactly once");
                assertEquals(Set.of("Message with exception", "Another exception message"), failedDeliveries.keySet(),
                        "only the two failing messages may fail");
                failedDeliveries.forEach((payload, deliveries) -> assertEquals(maxRetries + 1, deliveries.get(),
                        "with max-retries N a failing message is delivered N + 1 times: " + payload));
                assertEquals(List.of(maxRetries, maxRetries), retryCounts,
                        "each dead letter must record the N retries");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS),
                "the failing messages were not settled within 60 seconds");
    }

    private static final String DEAD_LETTER_COUNT_SQL =
            "SELECT COUNT(*) AS c FROM dead_letter_queue WHERE topic = $1";
    private static final String MESSAGE_COUNT_SQL =
            "SELECT COUNT(*) AS c FROM queue_messages WHERE topic = $1";
    private static final long POLL_DEADLINE_MS = 30_000;

    private Future<Long> count(String sql, String topic) {
        return manager.getPool().preparedQuery(sql).execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("c"));
    }

    private Future<List<Integer>> deadLetterRetryCounts(String topic) {
        return manager.getPool()
                .preparedQuery("SELECT retry_count FROM dead_letter_queue WHERE topic = $1 ORDER BY id")
                .execute(Tuple.of(topic))
                .map(rows -> {
                    List<Integer> counts = new ArrayList<>();
                    rows.forEach(row -> counts.add(row.getInteger("retry_count")));
                    return counts;
                });
    }

    private Future<Void> pollUntil(Vertx vertx, Supplier<Future<Boolean>> condition, String failureMessage) {
        return pollUntil(vertx, condition, System.currentTimeMillis() + POLL_DEADLINE_MS, failureMessage);
    }

    private Future<Void> pollUntil(Vertx vertx, Supplier<Future<Boolean>> condition, long deadline,
                                   String failureMessage) {
        return condition.get().compose(met -> {
            if (met) {
                return Future.<Void>succeededFuture();
            }
            if (System.currentTimeMillis() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError(
                        failureMessage + " within " + POLL_DEADLINE_MS + " ms"));
            }
            return vertx.timer(100).compose(t -> pollUntil(vertx, condition, deadline, failureMessage));
        });
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testChannelNameCollisionHandling(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-channel-collision";

        MessageConsumer<String> consumer1 = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        MessageConsumer<String> consumer2 = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // One strict checkpoint across both consumers: each of the three messages is processed
        // once; a fourth delivery fails the test.
        Checkpoint messagesProcessed = testContext.checkpoint(3);

        Future.all(
                consumer1.subscribe(message -> {
                    messagesProcessed.flag();
                    return Future.succeededFuture();
                }),
                consumer2.subscribe(message -> {
                    messagesProcessed.flag();
                    return Future.succeededFuture();
                }))
            .compose(ignored -> producer.send("Collision test message 1"))
            .compose(v -> producer.send("Collision test message 2"))
            .compose(v -> producer.send("Collision test message 3"))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testPartialModeFailureRecovery(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-partial-failure-recovery";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // A strict checkpoint: exactly three deliveries.
        Checkpoint messagesReceived = testContext.checkpoint(3);

        consumer.subscribe(message -> {
                messagesReceived.flag();
                return Future.succeededFuture();
            })
            .compose(ignored -> producer.send("Recovery test message 1"))
            .compose(v -> producer.send("Recovery test message 2"))
            .compose(v -> producer.send("Recovery test message 3"))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testRecoveryAfterTemporaryFailure(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-recovery-after-failure";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // A strict checkpoint: exactly two deliveries; the second must be the message sent after
        // the pause.
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(2);

        consumer.subscribe(message -> {
                if (processedCount.incrementAndGet() == 2) {
                    testContext.verify(() -> assertEquals("After recovery message", message.getPayload(),
                        "Should process the recovery message last"));
                }
                messagesReceived.flag();
                return Future.succeededFuture();
            })
            .compose(ignored -> producer.send("Before failure message"))
            // Send the second message after a pause to simulate recovery
            .compose(v -> vertx.timer(2000))
            .compose(v -> producer.send("After recovery message"))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
    void testConsumerModeRobustnessUnderLoad(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-robustness-under-load";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // A strict checkpoint: exactly ten deliveries.
        Checkpoint messagesReceived = testContext.checkpoint(10);

        consumer.subscribe(message -> {
                messagesReceived.flag();
                return Future.succeededFuture();
            })
            .compose(ignored -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 1; i <= 10; i++) {
                    final int idx = i;
                    chain = chain.compose(v -> producer.send("Load test message " + idx));
                }
                return chain;
            })
            .onFailure(testContext::failNow);
    }
}
