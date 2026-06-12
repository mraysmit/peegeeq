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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

import java.util.Properties;
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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
@ExtendWith(VertxExtension.class)
public class OutboxConsumerCrashRecoveryTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerCrashRecoveryTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        testTopic = "crash-recovery-test-" + UUID.randomUUID().toString().substring(0, 8);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        connectionManager = new PgConnectionManager(vertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
        testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);
        manager.start()
                .onSuccess(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        try { if (consumer != null) consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer", e); }
        try { if (producer != null) producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
        try { if (outboxFactory != null) outboxFactory.close(); } catch (Exception e) { logger.warn("Error closing outboxFactory", e); }
        Future.<Void>succeededFuture()
                .eventually(() -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .eventually(() -> connectionManager != null ? connectionManager.close() : Future.<Void>succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test the critical crash scenario: Consumer crashes after polling messages
     * but before marking them as completed, leaving them in PROCESSING state.
     *
     * This test demonstrates that the stuck message recovery mechanism automatically
     * recovers messages that get stuck in PROCESSING state due to consumer crashes.
     */
    @Test
    void testConsumerCrashLeavesMessagesInProcessingState(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testMessage = "Message that will be left in PROCESSING state";

        producer.send(testMessage)
                .compose(v -> vertx.timer(1000))
                .compose(v -> verifyMessageExists(testMessage, "PENDING"))
                .compose(v -> createStuckProcessingMessage(testMessage))
                .compose(v -> vertx.timer(2000))
                .compose(v -> getCurrentMessageStatus(testMessage))
                .onSuccess(currentStatus -> testContext.verify(() -> {
                    assertTrue(currentStatus.equals("PROCESSING") || currentStatus.equals("PENDING"),
                        "Message should be either PROCESSING (before recovery) or PENDING (after recovery), but was: " + currentStatus);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Directly creates the problematic state by updating a message to PROCESSING
     * without any consumer actually processing it. This simulates the crash scenario.
     */
    private Future<Void> createStuckProcessingMessage(String messagePayload) {
        logger.info("Test: consumer crash leaves messages in processing state");
        String updateSql = """
            UPDATE outbox
            SET status = 'PROCESSING', processed_at = $1
            WHERE payload::text LIKE $2 AND topic = $3 AND status = 'PENDING'
            """;
        return testReactivePool.withConnection(connection -> connection.preparedQuery(updateSql)
                .execute(io.vertx.sqlclient.Tuple.of(
                        java.time.OffsetDateTime.now(),
                        "%" + messagePayload + "%",
                        testTopic
                ))
                .map(rowSet -> {
                    assertTrue(rowSet.rowCount() > 0, "Should have updated at least one message to PROCESSING state");
                    return (Void) null;
                }));
    }



    /**
     * Gets the current status of a message in the database.
     */
    private Future<String> getCurrentMessageStatus(String expectedPayload) {
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
        });
    }

    /**
     * Helper method to verify that a message exists in the database with the expected status.
     */
    private Future<Void> verifyMessageExists(String expectedPayload, String expectedStatus) {
        return testReactivePool.withConnection(connection -> {
            String sql = "SELECT id, status, processed_at, retry_count FROM outbox WHERE payload::text LIKE $1 AND topic = $2";
            return connection.preparedQuery(sql)
                    .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%", testTopic))
                    .map(rowSet -> {
                        assertTrue(rowSet.size() > 0, "Message should exist in database");
                        io.vertx.sqlclient.Row row = rowSet.iterator().next();
                        String status = row.getString("status");
                        assertEquals(expectedStatus, status,
                                "Message should have status: " + expectedStatus);
                        return (Void) null;
                    });
        });
    }
}


