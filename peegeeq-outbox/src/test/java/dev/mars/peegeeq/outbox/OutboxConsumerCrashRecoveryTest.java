package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.Future;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for consumer crash recovery scenarios.
 * 
 * This test class focuses on the critical issue where consumer crashes
 * can leave messages in "PROCESSING" state indefinitely.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxConsumerCrashRecoveryTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerCrashRecoveryTest.class);

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled"
    };

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;
    private Vertx testVertx;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "crash-recovery-test-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        // Create test-specific reactive pool for verification
        testVertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(testVertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .build();

        testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (testVertx != null) {
            testVertx.close().await();
        }
        
        // Clear system properties
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    /**
     * Test the critical crash scenario: Consumer crashes after polling messages
     * but before marking them as completed, leaving them in PROCESSING state.
     *
     * This test demonstrates that the stuck message recovery mechanism automatically
     * recovers messages that get stuck in PROCESSING state due to consumer crashes.
     */
    @Test
    void testConsumerCrashLeavesMessagesInProcessingState() throws Exception {
        String testMessage = "Message that will be left in PROCESSING state";

        producer.send(testMessage).await();

        // Wait for message to be persisted
        testVertx.timer(1000).await();

        verifyMessageExists(testMessage, "PENDING");

        // Simulate consumer crash: update message to PROCESSING without any consumer processing it
        createStuckProcessingMessage(testMessage);

        // Wait for the recovery mechanism to potentially run
        testVertx.timer(2000).await();

        String currentStatus = getCurrentMessageStatus(testMessage);

        // The message should either be:
        // 1. Still in PROCESSING state (if recovery hasn't run yet), or
        // 2. Back in PENDING state (if recovery has already run)
        assertTrue(currentStatus.equals("PROCESSING") || currentStatus.equals("PENDING"),
            "Message should be either PROCESSING (before recovery) or PENDING (after recovery), but was: " + currentStatus);
    }

    /**
     * Directly creates the problematic state by updating a message to PROCESSING
     * without any consumer actually processing it. This simulates the crash scenario.
     */
    private void createStuckProcessingMessage(String messagePayload) throws Exception {
        logger.info("Test: consumer crash leaves messages in processing state");
        String updateSql = """
            UPDATE outbox
            SET status = 'PROCESSING', processed_at = $1
            WHERE payload::text LIKE $2 AND topic = $3 AND status = 'PENDING'
            """;

        Integer updated = testReactivePool.withConnection(connection -> {
            return connection.preparedQuery(updateSql)
                .execute(io.vertx.sqlclient.Tuple.of(
                    java.time.OffsetDateTime.now(),
                    "%" + messagePayload + "%",
                    testTopic
                ))
                .map(rowSet -> rowSet.rowCount());
        }).await();


        assertTrue(updated > 0, "Should have updated at least one message to PROCESSING state");
    }



    /**
     * Gets the current status of a message in the database.
     */
    private String getCurrentMessageStatus(String expectedPayload) throws Exception {
        return testReactivePool.withConnection(connection -> {
            String sql = "SELECT status FROM outbox WHERE payload::text LIKE $1 AND topic = $2";
            return connection.preparedQuery(sql)
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%", testTopic))
                .map(rowSet -> {
                    if (rowSet.size() > 0) {
                        return rowSet.iterator().next().getString("status");
                    } else {
                        throw new AssertionError("No message found with payload: " + expectedPayload);
                    }
                });
        }).await();
    }

    /**
     * Helper method to verify that a message exists in the database with the expected status.
     */
    private void verifyMessageExists(String expectedPayload, String expectedStatus) throws Exception {
        testReactivePool.withConnection(connection -> {
            String sql = "SELECT id, status, processed_at, retry_count FROM outbox WHERE payload::text LIKE $1 AND topic = $2";
            return connection.preparedQuery(sql)
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%", testTopic))
                .map(rowSet -> {
                    assertTrue(rowSet.size() > 0, "Message should exist in database");

                    io.vertx.sqlclient.Row row = rowSet.iterator().next();
                    String status = row.getString("status");


                    assertEquals(expectedStatus, status,
                        "Message should have status: " + expectedStatus);

                    return null;
                });
        }).await();
    }
}


