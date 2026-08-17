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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.recovery.StuckMessageRecoveryManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration test for the stuck message recovery mechanism.
 * 
 * This test validates that the StuckMessageRecoveryManager correctly identifies
 * and recovers messages that are stuck in PROCESSING state due to consumer crashes.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class StuckMessageRecoveryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(StuckMessageRecoveryIntegrationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private Pool reactivePool;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "recovery-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Set up database connection
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.recovery.enabled", "false")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().compose(v -> {
            // Create factory and components
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, config);
            producer = outboxFactory.createProducer(testTopic, String.class);
            consumer = outboxFactory.createConsumer(testTopic, String.class);

            // Get reactive pool for verification queries
            return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main");
        }).onSuccess(pool -> {
            reactivePool = pool;
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        factoryClose.transform(factoryResult -> {
                    Future<Void> managerClose = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return managerClose.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }



    /**
     * Test that demonstrates stuck message recovery by directly creating stuck messages.
     * This simulates the exact scenario where a consumer crashes after polling messages.
     */
    @Test
    void testStuckMessageRecoveryWithRealCrash(VertxTestContext testContext) {
        logger.info("=== Testing Stuck Message Recovery with Simulated Consumer Crash ===");

        // Create a dedicated recovery manager for testing with a very short timeout
        StuckMessageRecoveryManager testRecoveryManager =
            new StuckMessageRecoveryManager(reactivePool, Duration.ofSeconds(2), true);

        // Send multiple test messages
        int messageCount = 3;
        List<Future<?>> sends = new ArrayList<>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            sends.add(producer.send("Test message " + i + " for crash simulation"));
        }
        Future.all(sends)
                .compose(v -> countMessagesByStatus("PENDING"))
                .compose(pendingCount -> {
                    logger.info("Found {} messages in PENDING state", pendingCount);
                    assertEquals(messageCount, pendingCount,
                            "Every sent message should be persisted as PENDING");
                    logger.info("Simulating consumer crash - forcing messages into PROCESSING state");
                    return forceMessagesIntoProcessingState(messageCount);
                })
                .compose(forcedCount -> {
                    assertEquals(messageCount, forcedCount,
                            "The crash fixture should move every message into PROCESSING");
                    return countMessagesByStatus("PROCESSING");
                })
                .compose(processingCount -> {
                    logger.info("Found {} messages stuck in PROCESSING state after simulated crash", processingCount);
                    assertEquals(messageCount, processingCount,
                            "Every fixture message should be stuck in PROCESSING before recovery");
                    logger.info("Running stuck message recovery...");
                    return testRecoveryManager.recoverStuckMessages()
                            .map(recoveredCount -> {
                                assertEquals(messageCount, recoveredCount,
                                        "Recovery manager should recover every stuck fixture message");
                                logger.info("Recovery manager recovered {} stuck messages", recoveredCount);
                                return (Void) null;
                            })
                            .compose(v -> Future.all(
                                    countMessagesByStatus("PENDING"),
                                    countMessagesByStatus("PROCESSING")))
                            .compose(afterRecovery -> {
                                int pendingAfterRecovery = afterRecovery.resultAt(0);
                                int processingAfterRecovery = afterRecovery.resultAt(1);
                                logger.info("After recovery: {} PENDING, {} PROCESSING",
                                        pendingAfterRecovery, processingAfterRecovery);
                                assertEquals(messageCount, pendingAfterRecovery,
                                        "Every recovered message should return to PENDING");
                                assertEquals(0, processingAfterRecovery,
                                        "No fixture message should remain in PROCESSING after recovery");
                                return testRecoveryManager.getRecoveryStats();
                            })
                            .map(stats -> {
                                assertTrue(stats.isEnabled(), "Recovery should be enabled");
                                assertEquals(0, stats.getStuckMessagesCount(),
                                        "No stuck messages should remain after recovery");
                                assertEquals(0, stats.getTotalProcessingCount(),
                                        "No PROCESSING messages should remain after recovery");
                                logger.info("Recovery stats: {}", stats);
                                logger.info("Stuck message recovery test completed successfully!");
                                logger.info("This test demonstrates that the recovery mechanism can successfully");
                                logger.info("   recover messages that get stuck in PROCESSING state due to consumer crashes");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Test recovery manager with disabled recovery.
     */
    @Test
    void testDisabledRecovery(VertxTestContext testContext) {
        logger.info("=== Testing Disabled Recovery Mechanism ===");

        // Create a recovery manager with recovery disabled
        StuckMessageRecoveryManager disabledRecoveryManager =
            new StuckMessageRecoveryManager(reactivePool, Duration.ofMinutes(1), false);

        insertStuckProcessingMessage()
                .compose(stuckMessageId -> {
                    logger.info("Inserted stuck PROCESSING message with ID: {}", stuckMessageId);
                    return verifyMessageStatus(stuckMessageId, "PROCESSING")
                            .compose(v -> disabledRecoveryManager.recoverStuckMessages())
                            .compose(recoveredCount -> {
                                assertEquals(0, recoveredCount,
                                        "Disabled recovery manager should not recover any messages");
                                return verifyMessageStatus(stuckMessageId, "PROCESSING");
                            });
                })
                .compose(v -> disabledRecoveryManager.getRecoveryStats())
                .map(stats -> {
                    assertFalse(stats.isEnabled(), "Recovery should be disabled");
                    logger.info("Disabled recovery test completed successfully");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Tests recovery of a single message inserted at the post-poll crash boundary.
     */
    @Test
    void testDirectlyInsertedStuckMessageIsRecoveredToPending(VertxTestContext testContext) {
        StuckMessageRecoveryManager testRecoveryManager =
            new StuckMessageRecoveryManager(reactivePool, Duration.ofMinutes(3), true);

        insertStuckProcessingMessage()
                .compose(stuckMessageId -> fetchMessageState(stuckMessageId)
                        .map(stuckState -> {
                            assertEquals("PROCESSING", stuckState.status(),
                                    "The crash-boundary fixture must start in PROCESSING");
                            assertNotNull(stuckState.processedAt(),
                                    "The stuck message must retain its processing timestamp before recovery");
                            assertEquals(0, stuckState.retryCount(),
                                    "The crash-boundary fixture must start without retries");
                            return stuckMessageId;
                        }))
                .compose(stuckMessageId -> testRecoveryManager.recoverStuckMessages()
                        .map(recoveredCount -> {
                            assertEquals(1, recoveredCount,
                                    "Recovery must reset exactly the inserted stuck message");
                            return stuckMessageId;
                        }))
                .compose(this::fetchMessageState)
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
     * Directly inserts a stuck PROCESSING message into the database using reactive pool.
     * This simulates the exact scenario where a consumer crashes after polling.
     *
     * @return the ID of the inserted stuck message
     */
    private Future<Long> insertStuckProcessingMessage() {
        logger.info("About to insert stuck PROCESSING message");

        String insertSql = """
            INSERT INTO outbox (topic, payload, status, processed_at, retry_count, created_at, priority)
            VALUES ($1, $2::jsonb, 'PROCESSING', $3, 0, $4, 5)
            RETURNING id
            """;

        Instant now = Instant.now();
        Instant stuckTime = now.minus(Duration.ofMinutes(10));

        return reactivePool.withTransaction(client ->
            client.preparedQuery(insertSql)
                .execute(Tuple.of(testTopic, "\"Stuck message for recovery test\"",
                    stuckTime.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC)))
                .map(rows -> {
                    if (rows.size() > 0) {
                        long id = rows.iterator().next().getLong("id");
                        logger.info("Successfully inserted message with ID: {}", id);
                        return id;
                    } else {
                        throw new RuntimeException("Failed to insert stuck message - no ID returned");
                    }
                })
        );
    }

    /**
     * Verifies that a message with the given ID has the expected status using reactive pool.
     */
    private Future<Void> verifyMessageStatus(long messageId, String expectedStatus) {
        logger.info("Looking for message with ID: {}", messageId);

        return fetchMessageState(messageId)
                .map(state -> {
                    assertEquals(expectedStatus, state.status(),
                            "Message " + messageId + " should have status: " + expectedStatus);
                    return (Void) null;
                });
    }

    /**
     * Reads the exact state of one fixture message.
     */
    private Future<MessageState> fetchMessageState(long messageId) {
        String sql = "SELECT status, processed_at, retry_count FROM outbox WHERE id = $1 AND topic = $2";
        return reactivePool.withConnection(conn -> conn.preparedQuery(sql)
                .execute(Tuple.of(messageId, testTopic))
                .map(rows -> {
                    assertEquals(1, rows.size(),
                            "Exactly one fixture message should exist for ID " + messageId);
                    var row = rows.iterator().next();
                    return new MessageState(
                            row.getString("status"),
                            row.getOffsetDateTime("processed_at"),
                            row.getInteger("retry_count"));
                }));
    }

    /**
     * Counts messages by status for the test topic using reactive pool.
     */
    private Future<Integer> countMessagesByStatus(String status) {
        String sql = "SELECT COUNT(*) as count FROM outbox WHERE topic = $1 AND status = $2";
        
        return reactivePool.withConnection(conn ->
            conn.preparedQuery(sql).execute(Tuple.of(testTopic, status))
                .map(rows -> {
                    if (rows.size() > 0) {
                        int c = rows.iterator().next().getInteger("count");
                        logger.debug("Found {} messages with status '{}' for topic '{}'", c, status, testTopic);
                        return c;
                    }
                    return 0;
                })
        );
    }

    /**
     * Forces messages from PENDING to PROCESSING state to simulate a consumer crash scenario using reactive pool.
     * This simulates the exact moment when a consumer polls messages but crashes before completing.
     * @return the number of messages that were forced into PROCESSING state
     */
    private Future<Integer> forceMessagesIntoProcessingState(int maxMessages) {
        return reactivePool.withTransaction(client -> {
            // First, let's see what messages exist
            String selectSql = "SELECT id, topic, status, payload::text as payload_text FROM outbox WHERE topic = $1";
            return client.preparedQuery(selectSql).execute(Tuple.of(testTopic))
                .compose(selectRows -> {
                    logger.info("Messages in database for topic {}:", testTopic);
                    selectRows.forEach(row -> {
                        logger.info("  - ID: {}, Status: {}, Payload: {}",
                            row.getLong("id"), row.getString("status"), row.getString("payload_text"));
                    });

                    // PostgreSQL doesn't support LIMIT in UPDATE, so we use a subquery
                    String updateSql = """
                        UPDATE outbox
                        SET status = 'PROCESSING', processed_at = $1
                        WHERE id IN (
                            SELECT id FROM outbox
                            WHERE topic = $2 AND status = 'PENDING'
                            ORDER BY created_at ASC
                            LIMIT $3
                        )
                        """;

                    // Set processed_at to a time that makes messages appear stuck (5 minutes ago)
                    Instant stuckTime = Instant.now().minus(Duration.ofMinutes(5));
                    logger.info("Executing update for topic: {}, maxMessages: {}", testTopic, maxMessages);

                    return client.preparedQuery(updateSql)
                        .execute(Tuple.of(stuckTime.atOffset(ZoneOffset.UTC), testTopic, maxMessages));
                })
                .map(updateRows -> {
                    int updated = updateRows.rowCount();
                    logger.info("Forced {} messages from PENDING to PROCESSING state", updated);
                    return updated;
                });
        });
    }

    private record MessageState(String status, OffsetDateTime processedAt, int retryCount) {
    }

}
