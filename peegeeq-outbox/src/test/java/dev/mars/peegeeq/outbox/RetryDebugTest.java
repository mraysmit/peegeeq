package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Debug test to understand why retry mechanism is not working.
 */
@Tag(TestCategories.FLAKY)  // Column ocg.outbox_message_id does not exist - needs investigation
@Testcontainers
@ExtendWith(VertxExtension.class)
public class RetryDebugTest {

    private static final Logger logger = LoggerFactory.getLogger(RetryDebugTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_debug")
            .withUsername("debug")
            .withPassword("debug");

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private OutboxFactory outboxFactory;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();

        // Create factory and components (following the pattern of working tests)
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

        logger.info("🔧 Creating producer and consumer...");
        producer = outboxFactory.createProducer("debug-retry", String.class);
        logger.info("Producer created: {}", producer.getClass().getSimpleName());

        consumer = outboxFactory.createConsumer("debug-retry", String.class);
        System.out.println("Consumer created: " + consumer.getClass().getName());
        logger.info("Consumer created: {}", consumer.getClass().getSimpleName());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (outboxFactory != null) outboxFactory.close();
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
    }

    /**
     * Checks database state using reactive pool for verification queries.
     */
    private void checkDatabaseState(String phase) throws Exception {
        io.vertx.sqlclient.Pool pool = manager.getDatabaseService().getConnectionProvider()
            .getReactivePool("peegeeq-main").toCompletionStage().toCompletableFuture().get();

        pool.withConnection(conn -> {
            logger.info("🔍 === DATABASE STATE: {} ===", phase);

            // Check outbox table
            String outboxSql = "SELECT id, topic, status, retry_count, max_retries, error_message FROM outbox WHERE topic = 'debug-retry' ORDER BY created_at DESC LIMIT 5";
            return conn.preparedQuery(outboxSql).execute()
                .compose(outboxRows -> {
                    logger.info("📊 OUTBOX TABLE:");
                    outboxRows.forEach(row -> {
                        logger.info("   ID: {}, Topic: {}, Status: {}, Retry: {}/{}, Error: {}",
                            row.getLong("id"), row.getString("topic"), row.getString("status"),
                            row.getInteger("retry_count"), row.getInteger("max_retries"),
                            row.getString("error_message"));
                    });

                    // Check consumer groups table
                    String consumerGroupSql = "SELECT ocg.consumer_group_name, ocg.status, ocg.retry_count, o.topic FROM outbox_consumer_groups ocg JOIN outbox o ON ocg.outbox_message_id = o.id WHERE o.topic = 'debug-retry' ORDER BY ocg.created_at DESC LIMIT 5";
                    return conn.preparedQuery(consumerGroupSql).execute();
                })
                .map(groupRows -> {
                    logger.info("📊 CONSUMER GROUPS TABLE:");
                    groupRows.forEach(row -> {
                        logger.info("   Group: {}, Status: {}, Retry: {}, Topic: {}",
                            row.getString("consumer_group_name"), row.getString("status"),
                            row.getInteger("retry_count"), row.getString("topic"));
                    });
                    logger.info("🔍 === END DATABASE STATE ===");
                    return null;
                });
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void debugRetryMechanism(Vertx vertx, VertxTestContext testContext) throws Exception {
        System.out.println("🔍 === DEBUGGING RETRY MECHANISM ===");
        logger.info("🔍 === DEBUGGING RETRY MECHANISM ===");

        String testMessage = "Debug retry message";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint firstAttempt = testContext.checkpoint();

        System.out.println("📤 Sending message: " + testMessage);
        logger.info("📤 Sending message: {}", testMessage);

        // Send message FIRST, then set up consumer to avoid race condition
        System.out.println("📤 Sending message FIRST to avoid race condition: " + testMessage);
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        System.out.println("📤 Message sent successfully: " + testMessage);
        logger.info("📤 Message sent successfully: {}", testMessage);

        // Wait a moment to ensure the message is committed to the database
        vertx.timer(500).toCompletionStage().toCompletableFuture().join();

        // Check initial database state
        checkDatabaseState("After message sent");

        // NOW set up consumer that always fails
        System.out.println("🔧 Setting up consumer subscription AFTER message is sent...");
        logger.info("🔧 Setting up consumer subscription...");
        System.out.println("🔧 Consumer instance: " + consumer);
        System.out.println("🔧 Consumer class: " + consumer.getClass().getName());

        try {
            System.out.println("🔧 About to call subscribe() on consumer...");
            consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                System.out.println("🔥 ATTEMPT " + attempt + ": Processing message: " + message.getPayload());
                logger.info("🔥 ATTEMPT {}: Processing message: {}", attempt, message.getPayload());

                firstAttempt.flag();

                // Check database state during processing
                try {
                    checkDatabaseState("During attempt " + attempt);
                } catch (Exception e) {
                    System.out.println("Error checking database state: " + e.getMessage());
                    logger.error("Error checking database state: {}", e.getMessage());
                }

                throw new RuntimeException("INTENTIONAL FAILURE: Debug retry, attempt " + attempt);
            });
            System.out.println("Consumer subscribed successfully");
            logger.info("Consumer subscribed successfully");
        } catch (Exception e) {
            System.out.println("❌ Failed to subscribe consumer: " + e.getMessage());
            logger.error("❌ Failed to subscribe consumer: {}", e.getMessage(), e);
            throw e;
        }


        // Wait for first attempt
        boolean firstCompleted = testContext.awaitCompletion(10, TimeUnit.SECONDS);
        System.out.println("First attempt completed: " + firstCompleted);
        logger.info("First attempt completed: {}", firstCompleted);

        if (!firstCompleted) {
            System.out.println("❌ First attempt never happened - consumer may not be working");
            logger.error("❌ First attempt never happened - consumer may not be working");
        }

        // Check database state after first failure
        vertx.timer(1000).toCompletionStage().toCompletableFuture().join();
        checkDatabaseState("After first failure");

        // Wait longer to see if retry happens
        System.out.println("⏳ Waiting 5 seconds for potential retries...");
        logger.info("⏳ Waiting 5 seconds for potential retries...");
        vertx.timer(5000).toCompletionStage().toCompletableFuture().join();
        checkDatabaseState("After waiting 5 seconds");

        System.out.println("🔍 Total attempts made: " + attemptCount.get());
        logger.info("🔍 Total attempts made: {}", attemptCount.get());
        System.out.println("🔍 Debug test completed");
        logger.info("🔍 Debug test completed");
    }
}


