package dev.mars.peegeeq.examples.springboot.outbox;

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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Retry Logic in Spring Boot context.
 * 
 * This test suite demonstrates how PeeGeeQ handles transient failures through
 * automatic retry mechanisms. Retry logic is essential for building resilient
 * distributed systems that can recover from temporary issues like:
 * 
 * - Network timeouts
 * - Temporary service unavailability
 * - Database connection issues
 * - Rate limiting
 * 
 * Key Patterns Demonstrated:
 * - Automatic retry on transient failures
 * - Exponential backoff behavior
 * - Max retry limit enforcement
 * - Successful recovery after retries
 * - Proper resource cleanup in Spring context
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-30
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "logging.level.dev.mars.peegeeq.examples.springboot=INFO",
        "peegeeq.queue.max-retries=3",
        "peegeeq.queue.polling-interval=PT0.1S",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(VertxExtension.class)
class OutboxRetrySpringBootTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxRetrySpringBootTest.class);
    
    @Autowired
    private OutboxFactory outboxFactory;

    @Autowired
    private PeeGeeQManager peeGeeQManager;
    private static PeeGeeQManager peeGeeQManagerRef;

    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxRetry test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot retry test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }
    
    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info(" Cleaning up Retry Spring Boot Test");

        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            try {
                consumer.close();
                logger.info("Closed consumer");
            } catch (Exception e) {
                logger.error(" Error closing consumer: {}", e.getMessage());
            }
        }
        activeConsumers.clear();

        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
                logger.info("Closed producer");
            } catch (Exception e) {
                logger.error(" Error closing producer: {}", e.getMessage());
            }
        }
        activeProducers.clear();

        peeGeeQManagerRef = peeGeeQManager;

        // Wait for connections to be fully released before next test
        logger.info(" Waiting for connections to be released...");
        vertx.timer(2000).onComplete(testContext.succeeding(v -> {
            logger.info("Cleanup complete");
            testContext.completeNow();
        }));
    }

    @AfterAll
    static void closeManager(VertxTestContext testContext) {
        if (peeGeeQManagerRef == null) {
            testContext.completeNow();
            return;
        }
        peeGeeQManagerRef.closeReactive().onComplete(testContext.succeedingThenComplete());
    }
    
    /**
     * Test that messages are automatically retried on transient failures.
     * 
     * This test verifies:
     * - Failed messages are automatically retried
     * - Retry count increments correctly
     * - Messages eventually succeed after retries
     * - System recovers from transient failures
     */
    @Test
    @Order(1)
    @DisplayName("Retry Logic - Automatic Retry on Transient Failures")
    @Timeout(60)
    void testAutomaticRetryOnTransientFailures(VertxTestContext testContext) {
        logger.info("=== Testing Automatic Retry on Transient Failures ===");
        logger.info("This test verifies that messages are automatically retried on transient failures");

        String topicName = "retry-transient-" + UUID.randomUUID().toString().substring(0, 8);

        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Track retry attempts
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        // Subscribe with handler that fails first 2 times, then succeeds
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("Processing attempt #{} for message: {}", attempt, message.getPayload());

            // Fail first 2 attempts
            if (attempt <= 2) {
                logger.info(" Simulating transient failure on attempt #{}", attempt);
                return Future.failedFuture(
                    new RuntimeException("Simulated transient failure"));
            }

            // Succeed on 3rd attempt
            logger.info("Successfully processed on attempt #{}", attempt);
            successCount.incrementAndGet();
            testContext.verify(() -> {
                logger.info(" Retry Results:");
                logger.info("  Total attempts: {}", attemptCount.get());
                logger.info("  Successful processing: {}", successCount.get());

                assertEquals(3, attemptCount.get(), "Should have 3 attempts (2 failures + 1 success)");
                assertEquals(1, successCount.get(), "Should have 1 successful processing");

                logger.info("Automatic Retry test passed");
                logger.info("Message successfully processed after {} retries", attemptCount.get() - 1);
                testContext.completeNow();
            });
            return Future.succeededFuture();
        });

        // Send message
        logger.info(" Sending message that will fail twice before succeeding");
        producer.send("test-message").onFailure(testContext::failNow);
    }
    
    /**
     * Test that retry count is enforced and messages move to DLQ after max retries.
     * 
     * This test verifies:
     * - Max retry limit is enforced (configured as 3)
     * - Messages that consistently fail are not retried indefinitely
     * - System doesn't get stuck in infinite retry loops
     * - Failed messages are handled appropriately
     */
    @Test
    @Order(2)
    @DisplayName("Retry Logic - Max Retry Limit Enforcement")
    @Timeout(60)
    void testMaxRetryLimitEnforcement(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Max Retry Limit Enforcement ===");
        logger.info("This test verifies that retry count is enforced (max 3 retries configured)");

        String topicName = "retry-maxlimit-" + UUID.randomUUID().toString().substring(0, 8);

        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Track retry attempts
        AtomicInteger attemptCount = new AtomicInteger(0);
        io.vertx.core.Promise<Void> retriesDone = io.vertx.core.Promise.promise();

        // Subscribe with handler that always fails; signal when initial + 3 retries are exhausted.
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("Processing attempt #{} for message: {}", attempt, message.getPayload());
            logger.info(" Simulating persistent failure on attempt #{}", attempt);
            if (attempt >= 4) {
                retriesDone.tryComplete();
            }
            return Future.failedFuture(
                new RuntimeException("Simulated persistent failure"));
        });

        // Send message
        logger.info(" Sending message that will always fail");
        producer.send("failing-message").onFailure(testContext::failNow);

        // Wait for retries to settle, then a small grace period to ensure no extra retries fire.
        retriesDone.future()
            .compose(v -> vertx.timer(2000))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                logger.info(" Max Retry Results:");
                logger.info("  Total attempts: {}", attemptCount.get());

                assertEquals(4, attemptCount.get(),
                    "Should have exactly 4 attempts (initial + 3 retries)");

                logger.info("Max Retry Limit test passed");
                logger.info("Retry limit enforced: stopped after {} attempts", attemptCount.get());
                testContext.completeNow();
            })));
    }
}

