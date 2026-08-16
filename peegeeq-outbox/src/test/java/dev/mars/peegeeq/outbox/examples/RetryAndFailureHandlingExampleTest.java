package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxConsumerConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive JUnit test demonstrating retry and failure handling in PeeGeeQ Outbox Pattern.
 * 
 * This test demonstrates configurable retry behavior with the peegeeq.queue.max-retries property,
 * showing how PeeGeeQ handles different failure scenarios and retry strategies.
 * 
 * <h2> INTENTIONAL FAILURES - This Test Contains Expected Errors</h2>
 * <p>This test class deliberately triggers various failure conditions to demonstrate proper retry behavior.
 * The following errors are <b>INTENTIONAL</b> and expected:</p>
 * <ul>
 *   <li><b>Simulated processing failure</b> - Tests retry mechanism with configurable max retries</li>
 *   <li><b>Always failing processor</b> - Tests dead letter queue behavior after max retries exceeded</li>
 *   <li><b>Eventually successful processor</b> - Tests successful processing after initial failures</li>
 * </ul>
 * 
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li><b>Quick Failure Configuration</b> - Minimal retries (2) before dead letter queue</li>
 *   <li><b>Extensive Retries Configuration</b> - Many retries (8) before giving up</li>
 *   <li><b>Successful Retry Configuration</b> - Eventual success after some failures</li>
 * </ul>
 * 
 * <h2>Key Features Tested</h2>
 * <ul>
 *   <li>Configurable max retries with peegeeq.queue.max-retries property</li>
 *   <li>Dead letter queue processing after max retries exceeded</li>
 *   <li>Successful processing after initial failures</li>
 *   <li>Different retry strategies and configurations</li>
 *   <li>Failure handling behavior and logging</li>
 * </ul>
 * 
 * <h2>Expected Test Results</h2>
 * <p>All tests should <b>PASS</b> by correctly handling the intentional failures:</p>
 * <ul>
 *   <li>Quick failure scenarios complete after 2 retries</li>
 *   <li>Extensive retry scenarios complete after 8 retries</li>
 *   <li>Eventually successful scenarios succeed after configured failures</li>
 *   <li>All retry configurations work as expected</li>
 * </ul>
 * 
 * <h2>Error Log Messages</h2>
 * <p>The following ERROR/WARN log messages are <b>EXPECTED</b> and indicate proper retry handling:</p>
 * <ul>
 *   <li>" Attempt X - Simulated failure" - Expected for retry testing</li>
 *   <li>" Processing failed for message" - Expected for failure scenarios</li>
 *   <li>" Message failed after maximum retries" - Expected for always-failing processors</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-14
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryAndFailureHandlingExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryAndFailureHandlingExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        logger.info("=== Setting up Retry and Failure Handling Example Test ===");

        // Configure PeeGeeQ to use container database
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .map(v -> {
                // Create outbox factory - following established pattern
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                // Register outbox factory implementation
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                queueFactory = provider.createFactory("outbox", databaseService);
                logger.info("Retry and Failure Handling Example Test setup completed");
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        logger.info(" Cleaning up Retry and Failure Handling Example Test");

        Future<Void> closeFactory = queueFactory != null
                ? queueFactory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.verify(() -> {
                    logger.info("Retry and Failure Handling Example Test cleanup completed");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testQuickFailureConfiguration(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Quick Failure Configuration (2 retries) ===");

        int maxRetries = 2;
        runFailureScenario(vertx, "quick-failure", new AlwaysFailingProcessor(), maxRetries)
            .onSuccess(result -> testContext.verify(() -> {
                assertFalse(result.processorSucceeded());
                assertEquals(maxRetries + 1, result.attemptCount());
                assertEquals("DEAD_LETTER", result.status());
                assertEquals(maxRetries, result.retryCount());
                logger.info("Quick failure configuration test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testExtensiveRetriesConfiguration(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Extensive Retries Configuration (8 retries) ===");

        int maxRetries = 8;
        runFailureScenario(vertx, "extensive-retries", new AlwaysFailingProcessor(), maxRetries)
            .onSuccess(result -> testContext.verify(() -> {
                assertFalse(result.processorSucceeded());
                assertEquals(maxRetries + 1, result.attemptCount());
                assertEquals("DEAD_LETTER", result.status());
                assertEquals(maxRetries, result.retryCount());
                logger.info("Extensive retries configuration test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testSuccessfulRetryConfiguration(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Successful Retry Configuration (5 retries) ===");

        int failuresBeforeSuccess = 3;
        int maxRetries = 5;
        runFailureScenario(vertx, "successful-retry",
                new EventuallySuccessfulProcessor(failuresBeforeSuccess), maxRetries)
            .onSuccess(result -> testContext.verify(() -> {
                assertTrue(result.processorSucceeded());
                assertEquals(failuresBeforeSuccess + 1, result.attemptCount());
                assertEquals("COMPLETED", result.status());
                assertEquals(failuresBeforeSuccess, result.retryCount());
                logger.info("Successful retry configuration test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Runs a failure scenario to demonstrate retry behavior.
     *
     * @param scenarioName The name of the scenario for logging
     * @param processor The message processor to use (failing or eventually successful)
     * @param expectedMaxRetries The expected maximum number of retries
     * @return the observed processor and database terminal state
     */
    private Future<FailureScenarioResult> runFailureScenario(
            Vertx vertx,
            String scenarioName,
            MessageProcessor processor,
            int expectedMaxRetries) {
        logger.info(" INTENTIONAL FAILURE SCENARIO: {} - Max retries: {}", scenarioName, expectedMaxRetries);

        String topic = "retry-demo-" + scenarioName;

        MessageProducer<FailureTestMessage> producer = queueFactory.createProducer(topic, FailureTestMessage.class);
        OutboxConsumerConfig consumerConfig = OutboxConsumerConfig.builder()
                .maxRetries(expectedMaxRetries)
                .pollingInterval(Duration.ofMillis(100))
                .build();
        MessageConsumer<FailureTestMessage> consumer = queueFactory.createConsumer(
                topic, FailureTestMessage.class, consumerConfig);

        int expectedTerminalAttempt = processor instanceof EventuallySuccessfulProcessor
                ? ((EventuallySuccessfulProcessor) processor).getFailuresBeforeSuccess() + 1
                : expectedMaxRetries + 1;
        String expectedStatus = processor instanceof EventuallySuccessfulProcessor
                ? "COMPLETED"
                : "DEAD_LETTER";
        Promise<Void> terminalAttempt = Promise.promise();

        // Set up consumer with failure-prone processor
        Future<FailureScenarioResult> scenario = consumer.subscribe(message ->
            processor.process(message)
                .onFailure(err -> {
                    logger.warn(" INTENTIONAL TEST FAILURE: Processing failed for message {}: {}",
                        message.getPayload().id, err.getMessage());
                    if (processor.getAttemptCount() == expectedTerminalAttempt) {
                        terminalAttempt.tryComplete();
                    }
                })
                .onSuccess(v -> {
                    if (processor.getAttemptCount() == expectedTerminalAttempt) {
                        terminalAttempt.tryComplete();
                    }
                }))
            .compose(v -> {
                FailureTestMessage message = new FailureTestMessage(
                    "failure-test-" + System.currentTimeMillis(),
                    "This message will fail initially",
                    scenarioName
                );

                Map<String, String> headers = new HashMap<>();
                headers.put("scenario", scenarioName);
                headers.put("expectedRetries", String.valueOf(expectedMaxRetries));

                logger.info(" Sending message that will fail initially: {}", message.id);
                return producer.send(message, headers);
            })
            .compose(v -> terminalAttempt.future())
            .compose(v -> awaitTerminalState(vertx, topic, expectedStatus, 100))
            .map(row -> new FailureScenarioResult(
                    processor.getAttemptCount(),
                    processor.hasSucceeded(),
                    row.getString("status"),
                    row.getInteger("retry_count")))
            .eventually(() -> {
                producer.close();
                consumer.close();
                return Future.succeededFuture();
            });

        return scenario;
    }

    private Future<Row> awaitTerminalState(
            Vertx vertx,
            String topic,
            String expectedStatus,
            int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
            .getReactivePool("peegeeq-main")
            .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                    SELECT status, retry_count
                    FROM outbox
                    WHERE topic = $1
                    ORDER BY created_at DESC
                    LIMIT 1
                    """).execute(Tuple.of(topic))))
            .compose(rows -> {
                if (rows.size() == 1) {
                    Row row = rows.iterator().next();
                    if (expectedStatus.equals(row.getString("status"))) {
                        return Future.succeededFuture(row);
                    }
                }
                if (remainingAttempts <= 0) {
                    return Future.failedFuture(
                            "Message on topic " + topic + " did not reach " + expectedStatus);
                }
                return vertx.timer(100)
                        .compose(v -> awaitTerminalState(
                                vertx, topic, expectedStatus, remainingAttempts - 1));
            });
    }

    private record FailureScenarioResult(
            int attemptCount,
            boolean processorSucceeded,
            String status,
            int retryCount) {
    }

    /**
     * Interface for different message processing strategies.
     */
    interface MessageProcessor {
        Future<Void> process(Message<FailureTestMessage> message);

        int getAttemptCount();

        boolean hasSucceeded();
    }

    /**
     * Processor that always fails to demonstrate retry behavior.
     * This processor is used to test the retry mechanism and dead letter queue functionality.
     */
    static class AlwaysFailingProcessor implements MessageProcessor {
        private final AtomicInteger attemptCount = new AtomicInteger(0);

        @Override
        public Future<Void> process(Message<FailureTestMessage> message) {
            int attempt = attemptCount.incrementAndGet();
            logger.warn(" INTENTIONAL TEST FAILURE: Attempt {} - Simulated failure for message: {}",
                attempt, message.getPayload().id);
            logger.info("    This failure demonstrates retry mechanism and dead letter queue behavior");
            return Future.failedFuture(new RuntimeException("Simulated processing failure (attempt " + attempt + ")"));
        }

        @Override
        public int getAttemptCount() {
            return attemptCount.intValue();
        }

        @Override
        public boolean hasSucceeded() {
            return false;
        }
    }

    /**
     * Processor that fails a few times then succeeds.
     * This processor is used to test successful processing after initial failures.
     */
    static class EventuallySuccessfulProcessor implements MessageProcessor {
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        private final int failuresBeforeSuccess;

        public EventuallySuccessfulProcessor(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public Future<Void> process(Message<FailureTestMessage> message) {
            int attempt = attemptCount.incrementAndGet();

            if (attempt <= failuresBeforeSuccess) {
                logger.warn(" INTENTIONAL TEST FAILURE: Attempt {} - Simulated failure for message: {} (will succeed on attempt {})",
                    attempt, message.getPayload().id, failuresBeforeSuccess + 1);
                logger.info("    This failure demonstrates eventual success after configured failures");
                return Future.failedFuture(new RuntimeException("Simulated processing failure (attempt " + attempt + ")"));
            } else {
                logger.info("EXPECTED SUCCESS: Attempt {} - Successfully processed message: {}",
                    attempt, message.getPayload().id);
                return Future.succeededFuture();
            }
        }

        @Override
        public boolean hasSucceeded() {
            return attemptCount.intValue() > failuresBeforeSuccess;
        }

        @Override
        public int getAttemptCount() {
            return attemptCount.intValue();
        }

        public int getFailuresBeforeSuccess() {
            return failuresBeforeSuccess;
        }
    }

    /**
     * Test message class for failure scenarios.
     */
    public static class FailureTestMessage {
        public String id;
        public String content;
        public String scenario;
        public long timestamp;

        public FailureTestMessage() {}

        public FailureTestMessage(String id, String content, String scenario) {
            this.id = id;
            this.content = content;
            this.scenario = scenario;
            this.timestamp = System.currentTimeMillis();
        }
    }
}

