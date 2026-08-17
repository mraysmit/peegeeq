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
import dev.mars.peegeeq.db.recovery.StuckMessageRecoveryManager;
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
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private Pool testReactivePool;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        testTopic = "crash-recovery-test-" + UUID.randomUUID().toString().substring(0, 8);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.recovery.enabled", "false")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        connectionManager = new PgConnectionManager(vertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .build();
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
    void tearDown(VertxTestContext testContext) {
        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        factoryClose.transform(factoryResult -> {
            Future<Void> managerClose = manager != null
                    ? manager.closeReactive()
                    : Future.succeededFuture();
            return managerClose.transform(managerResult -> {
                Future<Void> connectionClose = connectionManager != null
                        ? connectionManager.close()
                        : Future.succeededFuture();
                return connectionClose.transform(connectionResult -> {
                    if (factoryResult.failed()) {
                        return Future.<Void>failedFuture(factoryResult.cause());
                    }
                    if (managerResult.failed()) {
                        return Future.<Void>failedFuture(managerResult.cause());
                    }
                    if (connectionResult.failed()) {
                        return Future.<Void>failedFuture(connectionResult.cause());
                    }
                    return Future.<Void>succeededFuture();
                });
            });
        })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Test the critical crash scenario: Consumer crashes after polling messages
     * but before marking them as completed, leaving them in PROCESSING state.
     *
     * This test demonstrates that the stuck message recovery mechanism
     * recovers messages that get stuck in PROCESSING state due to consumer crashes.
     */
    @Test
    void testConsumerCrashMessageIsRecoveredToPending(VertxTestContext testContext) {
        String testMessage = "Message that will be left in PROCESSING state";
        StuckMessageRecoveryManager recoveryManager =
                new StuckMessageRecoveryManager(testReactivePool, Duration.ofMinutes(5), true);

        producer.send(testMessage)
                .compose(v -> fetchMessageState(testMessage))
                .map(initialState -> {
                    assertEquals("PENDING", initialState.status(),
                            "A newly sent message must start PENDING");
                    assertNull(initialState.processedAt());
                    assertEquals(0, initialState.retryCount());
                    return (Void) null;
                })
                .compose(v -> createStuckProcessingMessage(testMessage))
                .compose(v -> fetchMessageState(testMessage))
                .map(crashedState -> {
                    assertEquals("PROCESSING", crashedState.status(),
                            "The crash boundary must leave the message PROCESSING");
                    assertNotNull(crashedState.processedAt());
                    assertEquals(0, crashedState.retryCount());
                    return (Void) null;
                })
                .compose(v -> recoveryManager.recoverStuckMessages())
                .map(recoveredCount -> {
                    assertEquals(1, recoveredCount,
                            "Recovery must reset exactly the crashed message");
                    return (Void) null;
                })
                .compose(v -> fetchMessageState(testMessage))
                .map(recoveredState -> {
                    assertEquals("PENDING", recoveredState.status(),
                            "The recovered message must return to PENDING");
                    assertNull(recoveredState.processedAt(),
                            "Recovery must clear the processing timestamp");
                    assertEquals(0, recoveredState.retryCount(),
                            "Recovery must preserve the retry count");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
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
        OffsetDateTime crashedAt = OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofMinutes(10));
        return testReactivePool.withTransaction(client -> client.preparedQuery(updateSql)
                .execute(Tuple.of(
                        crashedAt,
                        "%" + messagePayload + "%",
                        testTopic
                ))
                .map(rowSet -> {
                    assertEquals(1, rowSet.rowCount(),
                            "The crash fixture must move exactly one message to PROCESSING");
                    return (Void) null;
                }));
    }

    /**
     * Reads the exact state of the test message.
     */
    private Future<MessageState> fetchMessageState(String expectedPayload) {
        return testReactivePool.withConnection(connection -> {
            String sql = "SELECT id, status, processed_at, retry_count FROM outbox WHERE payload::text LIKE $1 AND topic = $2";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of("%" + expectedPayload + "%", testTopic))
                    .map(rowSet -> {
                        assertEquals(1, rowSet.size(),
                                "Exactly one test message should exist in the database");
                        var row = rowSet.iterator().next();
                        return new MessageState(
                                row.getString("status"),
                                row.getOffsetDateTime("processed_at"),
                                row.getInteger("retry_count"));
                    });
        });
    }

    private record MessageState(String status, OffsetDateTime processedAt, int retryCount) {
    }
}
