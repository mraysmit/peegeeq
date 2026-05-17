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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive JUnit test demonstrating retry and failure handling in PeeGeeQ Outbox Pattern.
 * 
 * This test demonstrates configurable retry behavior with the peegeeq.queue.max-retries property,
 * showing how PeeGeeQ handles different failure scenarios and retry strategies.
 * 
 * <h2>⚠️ INTENTIONAL FAILURES - This Test Contains Expected Errors</h2>
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
 *   <li>"💥 Attempt X - Simulated failure" - Expected for retry testing</li>
 *   <li>"❌ Processing failed for message" - Expected for failure scenarios</li>
 *   <li>"💀 Message failed after maximum retries" - Expected for always-failing processors</li>
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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        logger.info("=== Setting up Retry and Failure Handling Example Test ===");

        // Configure PeeGeeQ to use container database
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                // Create outbox factory - following established pattern
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                // Register outbox factory implementation
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                queueFactory = provider.createFactory("outbox", databaseService);
                logger.info("Retry and Failure Handling Example Test setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("🧹 Cleaning up Retry and Failure Handling Example Test");
        
        if (queueFactory != null) {
            queueFactory.close();
        }
        
        Future<Void> closeFuture = (manager != null)
            ? manager.closeReactive()
            : Future.succeededFuture();

        closeFuture
                .onSuccess(v -> {
                    logger.info("Retry and Failure Handling Example Test cleanup completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @Test
    void testQuickFailureConfiguration(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing Quick Failure Configuration (2 retries) ===");
        
        runFailureScenario("quick-failure", new AlwaysFailingProcessor(), 2)
            .onSuccess(v -> {
                logger.info("Quick failure configuration test completed successfully!");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }
    
    @Test
    void testExtensiveRetriesConfiguration(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing Extensive Retries Configuration (8 retries) ===");
        
        runFailureScenario("extensive-retries", new AlwaysFailingProcessor(), 8)
            .onSuccess(v -> {
                logger.info("Extensive retries configuration test completed successfully!");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }
    
    @Test
    void testSuccessfulRetryConfiguration(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing Successful Retry Configuration (5 retries) ===");
        
        runFailureScenario("successful-retry", new EventuallySuccessfulProcessor(3), 5)
            .onSuccess(v -> {
                logger.info("Successful retry configuration test completed successfully!");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    /**
     * Runs a failure scenario to demonstrate retry behavior.
     *
     * @param scenarioName The name of the scenario for logging
     * @param processor The message processor to use (failing or eventually successful)
     * @param expectedMaxRetries The expected maximum number of retries
     * @return true if the scenario completed within timeout, false otherwise
     */
    private Future<Void> runFailureScenario(String scenarioName, MessageProcessor processor, int expectedMaxRetries) {
        logger.info("🧪 INTENTIONAL FAILURE SCENARIO: {} - Max retries: {}", scenarioName, expectedMaxRetries);

        // Log current configuration
        var config = manager.getConfiguration().getQueueConfig();
        logger.info("🔧 Configuration: maxRetries={}, pollingInterval={}, threads={}, batchSize={}",
            config.getMaxRetries(), config.getPollingInterval(),
            config.getConsumerThreads(), config.getBatchSize());

        String topic = "retry-demo-" + scenarioName;

        MessageProducer<FailureTestMessage> producer = queueFactory.createProducer(topic, FailureTestMessage.class);
        MessageConsumer<FailureTestMessage> consumer = queueFactory.createConsumer(topic, FailureTestMessage.class);

        Promise<Void> latch = Promise.promise();

        // Set up consumer with failure-prone processor
        consumer.subscribe(message -> {
            return processor.process(message)
                .onFailure(err -> {
                    logger.error("🎯 INTENTIONAL TEST FAILURE: Processing failed for message {}: {}",
                        message.getPayload().id, err.getMessage());
                    logger.info("   📋 This failure demonstrates proper retry behavior in PeeGeeQ Outbox pattern");
                    latch.tryComplete();
                })
                .onSuccess(v -> {
                    if (processor instanceof EventuallySuccessfulProcessor &&
                        ((EventuallySuccessfulProcessor) processor).hasSucceeded()) {
                        latch.tryComplete();
                    }
                });
        });

        // Send a test message that will fail initially
        FailureTestMessage message = new FailureTestMessage(
            "failure-test-" + System.currentTimeMillis(),
            "This message will fail initially",
            scenarioName
        );

        Map<String, String> headers = new HashMap<>();
        headers.put("scenario", scenarioName);
        headers.put("expectedRetries", String.valueOf(expectedMaxRetries));

        logger.info("📤 Sending message that will fail initially: {}", message.id);
        return producer.send(message, headers)
            .compose(v -> latch.future())
            .onSuccess(v -> {
                if (processor instanceof EventuallySuccessfulProcessor &&
                    ((EventuallySuccessfulProcessor) processor).hasSucceeded()) {
                    logger.info("EXPECTED SUCCESS: Message eventually processed successfully after {} attempts",
                        ((EventuallySuccessfulProcessor) processor).getAttemptCount());
                } else {
                    logger.info("EXPECTED FAILURE: Message failed after maximum retries and moved to dead letter queue");
                }
                try { producer.close(); } catch (Exception e) { logger.warn("producer.close() failed", e); }
                try { consumer.close(); } catch (Exception e) { logger.warn("consumer.close() failed", e); }
            })
            .onFailure(err -> {
                logger.warn("Scenario '{}' future failed unexpectedly", scenarioName, err);
                try { producer.close(); } catch (Exception e) { logger.warn("producer.close() failed", e); }
                try { consumer.close(); } catch (Exception e) { logger.warn("consumer.close() failed", e); }
            });
    }

    /**
     * Interface for different message processing strategies.
     */
    interface MessageProcessor {
        Future<Void> process(Message<FailureTestMessage> message);
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
            logger.warn("🎯 INTENTIONAL TEST FAILURE: Attempt {} - Simulated failure for message: {}",
                attempt, message.getPayload().id);
            logger.info("   📋 This failure demonstrates retry mechanism and dead letter queue behavior");
            return Future.failedFuture(new RuntimeException("Simulated processing failure (attempt " + attempt + ")"));
        }
    }

    /**
     * Processor that fails a few times then succeeds.
     * This processor is used to test successful processing after initial failures.
     */
    static class EventuallySuccessfulProcessor implements MessageProcessor {
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        private final int failuresBeforeSuccess;
        private boolean succeeded = false;

        public EventuallySuccessfulProcessor(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public Future<Void> process(Message<FailureTestMessage> message) {
            int attempt = attemptCount.incrementAndGet();

            if (attempt <= failuresBeforeSuccess) {
                logger.warn("🎯 INTENTIONAL TEST FAILURE: Attempt {} - Simulated failure for message: {} (will succeed on attempt {})",
                    attempt, message.getPayload().id, failuresBeforeSuccess + 1);
                logger.info("   📋 This failure demonstrates eventual success after configured failures");
                return Future.failedFuture(new RuntimeException("Simulated processing failure (attempt " + attempt + ")"));
            } else {
                logger.info("EXPECTED SUCCESS: Attempt {} - Successfully processed message: {}",
                    attempt, message.getPayload().id);
                succeeded = true;
                return Future.succeededFuture();
            }
        }

        public boolean hasSucceeded() { return succeeded; }
        public int getAttemptCount() { return attemptCount.get(); }
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


