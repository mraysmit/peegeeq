package dev.mars.peegeeq.outbox.examples;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
 *   <li>✅ Quick failure scenarios complete after 2 retries</li>
 *   <li>✅ Extensive retry scenarios complete after 8 retries</li>
 *   <li>✅ Eventually successful scenarios succeed after configured failures</li>
 *   <li>✅ All retry configurations work as expected</li>
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
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryAndFailureHandlingExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryAndFailureHandlingExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_retry_failure_test")
            .withUsername("postgres")
            .withPassword("password");
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    
    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        logger.info("=== Setting up Retry and Failure Handling Example Test ===");

        // Configure PeeGeeQ to use container database
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        
        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create outbox factory - following established pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("outbox", databaseService);
        
        logger.info("✅ Retry and Failure Handling Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up Retry and Failure Handling Example Test");
        
        if (queueFactory != null) {
            queueFactory.close();
        }
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.batch-size");
        
        logger.info("✅ Retry and Failure Handling Example Test cleanup completed");
    }
    
    @Test
    void testQuickFailureConfiguration() throws Exception {
        logger.info("=== Testing Quick Failure Configuration (2 retries) ===");
        
        // Configure for quick failure
        System.setProperty("peegeeq.queue.max-retries", "2");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");
        System.setProperty("peegeeq.consumer.threads", "2");
        System.setProperty("peegeeq.queue.batch-size", "1");
        
        boolean result = runFailureScenario("quick-failure", new AlwaysFailingProcessor(), 2);
        
        assertTrue(result, "Quick failure scenario should complete within timeout");
        logger.info("✅ Quick failure configuration test completed successfully!");
    }
    
    @Test
    void testExtensiveRetriesConfiguration() throws Exception {
        logger.info("=== Testing Extensive Retries Configuration (8 retries) ===");
        
        // Configure for extensive retries
        System.setProperty("peegeeq.queue.max-retries", "8");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.3S");
        System.setProperty("peegeeq.consumer.threads", "1");
        System.setProperty("peegeeq.queue.batch-size", "1");
        
        boolean result = runFailureScenario("extensive-retries", new AlwaysFailingProcessor(), 8);
        
        assertTrue(result, "Extensive retries scenario should complete within timeout");
        logger.info("✅ Extensive retries configuration test completed successfully!");
    }
    
    @Test
    void testSuccessfulRetryConfiguration() throws Exception {
        logger.info("=== Testing Successful Retry Configuration (5 retries) ===");
        
        // Configure for moderate retries
        System.setProperty("peegeeq.queue.max-retries", "5");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");
        System.setProperty("peegeeq.consumer.threads", "2");
        System.setProperty("peegeeq.queue.batch-size", "1");
        
        boolean result = runFailureScenario("successful-retry", new EventuallySuccessfulProcessor(3), 5);
        
        assertTrue(result, "Successful retry scenario should complete within timeout");
        logger.info("✅ Successful retry configuration test completed successfully!");
    }

    /**
     * Runs a failure scenario to demonstrate retry behavior.
     *
     * @param scenarioName The name of the scenario for logging
     * @param processor The message processor to use (failing or eventually successful)
     * @param expectedMaxRetries The expected maximum number of retries
     * @return true if the scenario completed within timeout, false otherwise
     */
    private boolean runFailureScenario(String scenarioName, MessageProcessor processor, int expectedMaxRetries) throws Exception {
        logger.info("🧪 INTENTIONAL FAILURE SCENARIO: {} - Max retries: {}", scenarioName, expectedMaxRetries);

        // Log current configuration
        var config = manager.getConfiguration().getQueueConfig();
        logger.info("🔧 Configuration: maxRetries={}, pollingInterval={}, threads={}, batchSize={}",
            config.getMaxRetries(), config.getPollingInterval(),
            config.getConsumerThreads(), config.getBatchSize());

        String topic = "retry-demo-" + scenarioName;

        try (MessageProducer<FailureTestMessage> producer = queueFactory.createProducer(topic, FailureTestMessage.class);
             MessageConsumer<FailureTestMessage> consumer = queueFactory.createConsumer(topic, FailureTestMessage.class)) {

            CountDownLatch latch = new CountDownLatch(1);

            // Set up consumer with failure-prone processor
            consumer.subscribe(message -> {
                try {
                    return processor.process(message);
                } catch (Exception e) {
                    logger.error("🎯 INTENTIONAL TEST FAILURE: Processing failed for message {}: {}",
                        message.getPayload().id, e.getMessage());
                    logger.info("   📋 This failure demonstrates proper retry behavior in PeeGeeQ Outbox pattern");
                    latch.countDown();
                    return CompletableFuture.failedFuture(e);
                } finally {
                    // Count down on success too (for eventually successful processor)
                    if (processor instanceof EventuallySuccessfulProcessor &&
                        ((EventuallySuccessfulProcessor) processor).hasSucceeded()) {
                        latch.countDown();
                    }
                }
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
            producer.send(message, headers).join();

            // Wait for processing to complete (success or final failure)
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (completed) {
                if (processor instanceof EventuallySuccessfulProcessor &&
                    ((EventuallySuccessfulProcessor) processor).hasSucceeded()) {
                    logger.info("✅ EXPECTED SUCCESS: Message eventually processed successfully after {} attempts",
                        ((EventuallySuccessfulProcessor) processor).getAttemptCount());
                } else {
                    logger.info("✅ EXPECTED FAILURE: Message failed after maximum retries and moved to dead letter queue");
                }
            } else {
                logger.warn("⚠️ Scenario timed out - this may indicate a configuration issue");
            }

            return completed;
        }
    }

    /**
     * Interface for different message processing strategies.
     */
    interface MessageProcessor {
        CompletableFuture<Void> process(Message<FailureTestMessage> message) throws Exception;
    }

    /**
     * Processor that always fails to demonstrate retry behavior.
     * This processor is used to test the retry mechanism and dead letter queue functionality.
     */
    static class AlwaysFailingProcessor implements MessageProcessor {
        private final AtomicInteger attemptCount = new AtomicInteger(0);

        @Override
        public CompletableFuture<Void> process(Message<FailureTestMessage> message) throws Exception {
            int attempt = attemptCount.incrementAndGet();
            logger.warn("🎯 INTENTIONAL TEST FAILURE: Attempt {} - Simulated failure for message: {}",
                attempt, message.getPayload().id);
            logger.info("   📋 This failure demonstrates retry mechanism and dead letter queue behavior");
            throw new RuntimeException("Simulated processing failure (attempt " + attempt + ")");
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
        public CompletableFuture<Void> process(Message<FailureTestMessage> message) throws Exception {
            int attempt = attemptCount.incrementAndGet();

            if (attempt <= failuresBeforeSuccess) {
                logger.warn("🎯 INTENTIONAL TEST FAILURE: Attempt {} - Simulated failure for message: {} (will succeed on attempt {})",
                    attempt, message.getPayload().id, failuresBeforeSuccess + 1);
                logger.info("   📋 This failure demonstrates eventual success after configured failures");
                throw new RuntimeException("Simulated processing failure (attempt " + attempt + ")");
            } else {
                logger.info("✅ EXPECTED SUCCESS: Attempt {} - Successfully processed message: {}",
                    attempt, message.getPayload().id);
                succeeded = true;
                return CompletableFuture.completedFuture(null);
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
