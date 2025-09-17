package dev.mars.peegeeq.outbox;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug test to understand why retry mechanism is not working.
 */
@Testcontainers
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
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
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
        logger.info("✅ Producer created: {}", producer.getClass().getSimpleName());

        consumer = outboxFactory.createConsumer("debug-retry", String.class);
        System.out.println("✅ Consumer created: " + consumer.getClass().getName());
        logger.info("✅ Consumer created: {}", consumer.getClass().getSimpleName());
        
        // Create test-specific DataSource for debugging (HikariCP available in test scope)
        dataSource = createTestDataSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (outboxFactory != null) outboxFactory.close();
        if (manager != null) manager.close();
    }

    /**
     * Creates a test-specific DataSource using HikariCP (available in test scope).
     * This allows tests to perform direct database verification queries.
     */
    private DataSource createTestDataSource() {
        try {
            // Use reflection to create HikariCP DataSource if available (test scope)
            Class<?> hikariConfigClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");

            Object hikariConfig = hikariConfigClass.getDeclaredConstructor().newInstance();

            // Set connection properties using reflection
            hikariConfigClass.getMethod("setJdbcUrl", String.class)
                .invoke(hikariConfig, postgres.getJdbcUrl());
            hikariConfigClass.getMethod("setUsername", String.class)
                .invoke(hikariConfig, postgres.getUsername());
            hikariConfigClass.getMethod("setPassword", String.class)
                .invoke(hikariConfig, postgres.getPassword());
            hikariConfigClass.getMethod("setMaximumPoolSize", int.class)
                .invoke(hikariConfig, 5);
            hikariConfigClass.getMethod("setAutoCommit", boolean.class)
                .invoke(hikariConfig, true);

            return (DataSource) hikariDataSourceClass.getDeclaredConstructor(hikariConfigClass)
                .newInstance(hikariConfig);

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to create test DataSource. HikariCP should be available in test scope.", e);
        }
    }

    @Test
    void debugRetryMechanism() throws Exception {
        System.out.println("🔍 === DEBUGGING RETRY MECHANISM ===");
        logger.info("🔍 === DEBUGGING RETRY MECHANISM ===");

        String testMessage = "Debug retry message";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch firstAttemptLatch = new CountDownLatch(1);

        System.out.println("📤 Sending message: " + testMessage);
        logger.info("📤 Sending message: {}", testMessage);

        // Send message FIRST, then set up consumer to avoid race condition
        System.out.println("📤 Sending message FIRST to avoid race condition: " + testMessage);
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        System.out.println("📤 Message sent successfully: " + testMessage);
        logger.info("📤 Message sent successfully: {}", testMessage);

        // Wait a moment to ensure the message is committed to the database
        Thread.sleep(500);

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

                firstAttemptLatch.countDown();

                // Check database state during processing
                try {
                    checkDatabaseState("During attempt " + attempt);
                } catch (Exception e) {
                    System.out.println("Error checking database state: " + e.getMessage());
                    logger.error("Error checking database state: {}", e.getMessage());
                }

                throw new RuntimeException("INTENTIONAL FAILURE: Debug retry, attempt " + attempt);
            });
            System.out.println("✅ Consumer subscribed successfully");
            logger.info("✅ Consumer subscribed successfully");
        } catch (Exception e) {
            System.out.println("❌ Failed to subscribe consumer: " + e.getMessage());
            logger.error("❌ Failed to subscribe consumer: {}", e.getMessage(), e);
            throw e;
        }


        // Wait for first attempt
        boolean firstCompleted = firstAttemptLatch.await(10, TimeUnit.SECONDS);
        System.out.println("✅ First attempt completed: " + firstCompleted);
        logger.info("✅ First attempt completed: {}", firstCompleted);

        if (!firstCompleted) {
            System.out.println("❌ First attempt never happened - consumer may not be working");
            logger.error("❌ First attempt never happened - consumer may not be working");
        }

        // Check database state after first failure
        Thread.sleep(1000);
        checkDatabaseState("After first failure");

        // Wait longer to see if retry happens
        System.out.println("⏳ Waiting 5 seconds for potential retries...");
        logger.info("⏳ Waiting 5 seconds for potential retries...");
        Thread.sleep(5000);
        checkDatabaseState("After waiting 5 seconds");

        System.out.println("🔍 Total attempts made: " + attemptCount.get());
        logger.info("🔍 Total attempts made: {}", attemptCount.get());
        System.out.println("🔍 Debug test completed");
        logger.info("🔍 Debug test completed");
    }
    
    private void checkDatabaseState(String phase) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            logger.info("🔍 === DATABASE STATE: {} ===", phase);
            
            // Check outbox table
            String outboxSql = "SELECT id, topic, status, retry_count, max_retries, error_message FROM outbox WHERE topic = 'debug-retry' ORDER BY created_at DESC LIMIT 5";
            try (PreparedStatement stmt = conn.prepareStatement(outboxSql);
                 ResultSet rs = stmt.executeQuery()) {
                
                logger.info("📊 OUTBOX TABLE:");
                while (rs.next()) {
                    logger.info("   ID: {}, Topic: {}, Status: {}, Retry: {}/{}, Error: {}", 
                        rs.getLong("id"), rs.getString("topic"), rs.getString("status"),
                        rs.getInt("retry_count"), rs.getInt("max_retries"), 
                        rs.getString("error_message"));
                }
            }
            
            // Check consumer groups table
            String consumerGroupSql = "SELECT ocg.consumer_group_name, ocg.status, ocg.retry_count, o.topic FROM outbox_consumer_groups ocg JOIN outbox o ON ocg.outbox_message_id = o.id WHERE o.topic = 'debug-retry' ORDER BY ocg.created_at DESC LIMIT 5";
            try (PreparedStatement stmt = conn.prepareStatement(consumerGroupSql);
                 ResultSet rs = stmt.executeQuery()) {
                
                logger.info("📊 CONSUMER GROUPS TABLE:");
                while (rs.next()) {
                    logger.info("   Group: {}, Status: {}, Retry: {}, Topic: {}", 
                        rs.getString("consumer_group_name"), rs.getString("status"),
                        rs.getInt("retry_count"), rs.getString("topic"));
                }
            }
            
            logger.info("🔍 === END DATABASE STATE ===");
        }
    }
}
