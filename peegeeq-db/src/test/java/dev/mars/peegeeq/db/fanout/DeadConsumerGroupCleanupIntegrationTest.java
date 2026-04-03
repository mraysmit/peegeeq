package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup.CleanupResult;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DeadConsumerGroupCleanup}.
 *
 * <p>Tests validate that when a consumer group is marked DEAD, the cleanup
 * correctly decrements {@code required_consumer_groups}, removes orphaned
 * tracking rows, and auto-completes messages where all remaining consumers
 * have finished processing.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class DeadConsumerGroupCleanupIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerGroupCleanupIntegrationTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerGroupCleanup cleanup;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(10)
                .build();

        connectionManager.getOrCreateReactivePool(SERVICE_ID, connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, SERVICE_ID);
        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);
        cleanup = new DeadConsumerGroupCleanup(connectionManager, SERVICE_ID);

        logger.info("Test setup complete");
    }

    /**
     * Test basic cleanup: 2 consumer groups, one dies, its blocked messages get decremented.
     *
     * Scenario:
     * - Topic with 2 subscribers (group-a, group-b)
     * - 3 messages inserted (required_consumer_groups = 2 via trigger)
     * - group-a completes all 3 messages (completed_consumer_groups = 1)
     * - group-b is marked DEAD
     * - Cleanup decrements required_consumer_groups to 1
     * - Since completed (1) >= required (1), all 3 messages auto-complete
     */
    @Test
    void testCleanupDecrementsAndAutoCompletes(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-basic");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> subscribe(topic, "group-b"))
                .compose(v -> insertMessages(topic, 3))
                .compose(messageIds ->
                    completeMessages(messageIds, "group-a")
                        .compose(v -> assertMessageStates(testContext, messageIds, "PENDING", 2, 1))
                        .compose(v -> markSubscriptionDead(topic, "group-b"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-b"))
                        .compose(result -> {
                            testContext.verify(() -> {
                                assertEquals(3, result.messagesDecremented(), "Should decrement 3 messages");
                                assertTrue(result.messagesAutoCompleted() >= 3,
                                        "Should auto-complete 3 messages (completed 1 >= required 1)");
                                assertTrue(result.hadWork(), "Should have had work");
                                assertEquals(topic, result.topic());
                                assertEquals("group-b", result.groupName());
                            });
                            return assertMessageStates(testContext, messageIds, "COMPLETED", 1, 1);
                        })
                )
                .onSuccess(v -> {
                    logger.info("Basic cleanup with auto-complete verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that cleanup is idempotent — running it twice doesn't double-decrement.
     */
    @Test
    void testCleanupIsIdempotent(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-idempotent");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> subscribe(topic, "group-b"))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds ->
                    completeMessages(messageIds, "group-a")
                        .compose(v -> markSubscriptionDead(topic, "group-b"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-b"))
                        .compose(first -> cleanup.cleanupDeadGroup(topic, "group-b")
                            .map(second -> new CleanupResult[]{first, second}))
                        .map(results -> {
                            testContext.verify(() -> {
                                assertTrue(results[0].hadWork(), "First run should have work");
                                assertEquals(2, results[0].messagesDecremented());
                                assertEquals(0, results[1].messagesDecremented(), "Second run should have nothing to decrement");
                                assertEquals(0, results[1].messagesAutoCompleted(), "Second run should have nothing to auto-complete");
                            });
                            return (Void) null;
                        })
                )
                .onSuccess(v -> {
                    logger.info("Idempotency verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that already-completed groups are not affected by cleanup.
     *
     * Scenario: 3 groups. Group-c dies. Group-a and group-b have completed.
     * Required goes from 3 to 2, and since completed=2 >= required=2, messages auto-complete.
     */
    @Test
    void testDoesNotAffectCompletedGroups(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-completed");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> subscribe(topic, "group-b"))
                .compose(v -> subscribe(topic, "group-c"))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds ->
                    completeMessages(messageIds, "group-a")
                        .compose(v -> completeMessages(messageIds, "group-b"))
                        .compose(v -> assertMessageStates(testContext, messageIds, "PENDING", 3, 2))
                        .compose(v -> markSubscriptionDead(topic, "group-c"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-c"))
                        .compose(result -> {
                            testContext.verify(() -> {
                                assertEquals(2, result.messagesDecremented());
                                assertTrue(result.messagesAutoCompleted() >= 2,
                                        "completed(2) >= required(2) so should auto-complete");
                            });
                            return assertMessageStates(testContext, messageIds, "COMPLETED", 2, 2);
                        })
                        .compose(v -> {
                            Future<Void> chain = Future.succeededFuture();
                            for (Long msgId : messageIds) {
                                chain = chain.compose(x ->
                                    getConsumerGroupStatus(msgId, "group-a").compose(statusA ->
                                        getConsumerGroupStatus(msgId, "group-b").map(statusB -> {
                                            testContext.verify(() -> {
                                                assertEquals("COMPLETED", statusA, "group-a tracking row should be preserved");
                                                assertEquals("COMPLETED", statusB, "group-b tracking row should be preserved");
                                            });
                                            return (Void) null;
                                        })
                                    )
                                );
                            }
                            return chain;
                        })
                )
                .onSuccess(v -> {
                    logger.info("Completed group preservation verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test cleanup when the dead group had partially processed messages.
     *
     * Scenario: group-b completed 1 of 3 messages before dying.
     * - Message 1: group-b completed (shouldn't decrement, NOT EXISTS fails)
     * - Messages 2,3: group-b has no tracking row (should decrement)
     */
    @Test
    void testPartiallyProcessedDeadGroup(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-partial");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> subscribe(topic, "group-b"))
                .compose(v -> insertMessages(topic, 3))
                .compose(messageIds ->
                    completeMessages(messageIds, "group-a")
                        .compose(v -> completeMessage(messageIds.get(0), "group-b"))
                        .compose(v -> assertMessageState(testContext, messageIds.get(0), "COMPLETED", 2, 2))
                        .compose(v -> assertMessageState(testContext, messageIds.get(1), "PENDING", 2, 1))
                        .compose(v -> assertMessageState(testContext, messageIds.get(2), "PENDING", 2, 1))
                        .compose(v -> markSubscriptionDead(topic, "group-b"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-b"))
                        .compose(result -> {
                            testContext.verify(() -> {
                                assertEquals(2, result.messagesDecremented(),
                                        "Should only decrement the 2 messages group-b hadn't completed");
                                assertTrue(result.messagesAutoCompleted() >= 2,
                                        "Messages 1,2 should auto-complete (completed 1 >= required 1)");
                            });
                            return assertMessageState(testContext, messageIds.get(0), "COMPLETED", 2, 2);
                        })
                )
                .onSuccess(v -> {
                    logger.info("Partially processed dead group verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test cleanup when no messages exist for the dead group (no-op).
     */
    @Test
    void testCleanupWithNoMessages(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-empty");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> markSubscriptionDead(topic, "group-a"))
                .compose(v -> cleanup.cleanupDeadGroup(topic, "group-a"))
                .onSuccess(result -> testContext.verify(() -> {
                    assertEquals(0, result.messagesDecremented());
                    assertEquals(0, result.orphanRowsRemoved());
                    assertEquals(0, result.messagesAutoCompleted());
                    assertFalse(result.hadWork());
                    logger.info("Empty cleanup verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test orphaned tracking row removal.
     *
     * Scenario: group-b has PENDING tracking rows (e.g., created by backfill)
     * but then dies. Cleanup should remove those orphaned rows.
     */
    @Test
    void testRemovesOrphanedTrackingRows(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-orphans");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> subscribe(topic, "group-b"))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds -> {
                    Future<Void> insertRows = Future.succeededFuture();
                    for (Long msgId : messageIds) {
                        insertRows = insertRows.compose(v -> insertConsumerGroupRow(msgId, "group-b", "PENDING"));
                    }
                    return insertRows
                        .compose(v -> markSubscriptionDead(topic, "group-b"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-b"))
                        .compose(result -> {
                            testContext.verify(() -> {
                                assertEquals(2, result.messagesDecremented());
                                assertEquals(2, result.orphanRowsRemoved(), "Should remove 2 orphaned PENDING tracking rows");
                            });
                            Future<Void> verifyChain = Future.succeededFuture();
                            for (Long msgId : messageIds) {
                                verifyChain = verifyChain.compose(v ->
                                    consumerGroupRowExists(msgId, "group-b").map(exists -> {
                                        testContext.verify(() -> assertFalse(exists,
                                                "Orphaned tracking row should be removed for message " + msgId));
                                        return (Void) null;
                                    })
                                );
                            }
                            return verifyChain;
                        });
                })
                .onSuccess(v -> {
                    logger.info("Orphaned tracking row removal verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test cleanupAllDeadGroups discovers and cleans multiple dead groups.
     */
    @Test
    void testCleanupAllDeadGroups(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-all");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> subscribe(topic, "group-b"))
                .compose(v -> subscribe(topic, "group-c"))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds ->
                    completeMessages(messageIds, "group-a")
                        .compose(v -> markSubscriptionDead(topic, "group-b"))
                        .compose(v -> markSubscriptionDead(topic, "group-c"))
                        .compose(v -> cleanup.cleanupAllDeadGroups())
                        .compose(results -> {
                            List<CleanupResult> ourResults = results.stream()
                                    .filter(r -> r.topic().equals(topic))
                                    .toList();
                            testContext.verify(() -> {
                                assertTrue(results.size() >= 2, "Should have results for at least 2 dead groups, got " + results.size());
                                assertEquals(2, ourResults.size(), "Should have results for our 2 dead groups on topic " + topic);
                                int totalDecremented = ourResults.stream().mapToInt(CleanupResult::messagesDecremented).sum();
                                int totalAutoCompleted = ourResults.stream().mapToInt(CleanupResult::messagesAutoCompleted).sum();
                                assertEquals(4, totalDecremented, "Total decrements: 2 messages x 2 dead groups");
                                assertTrue(totalAutoCompleted >= 2, "Both messages should eventually auto-complete");
                            });
                            Future<Void> verifyChain = Future.succeededFuture();
                            for (Long msgId : messageIds) {
                                verifyChain = verifyChain.compose(v -> getMessageRow(msgId).map(state -> {
                                    testContext.verify(() -> {
                                        assertEquals("COMPLETED", state.getString("status"));
                                        assertEquals(1, state.getInteger("required_consumer_groups"),
                                                "required should be decremented from 3 to 1");
                                    });
                                    return (Void) null;
                                }));
                            }
                            return verifyChain;
                        })
                )
                .onSuccess(v -> {
                    logger.info("cleanupAllDeadGroups verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test that cleanup doesn't decrement below zero.
     */
    @Test
    void testDoesNotDecrementBelowZero(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-zero-guard");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> insertMessages(topic, 1))
                .compose(messageIds ->
                    setRequiredConsumerGroups(messageIds.get(0), 0)
                        .compose(v -> markSubscriptionDead(topic, "group-a"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-a"))
                        .compose(result -> {
                            testContext.verify(() ->
                                assertEquals(0, result.messagesDecremented(),
                                        "Should not decrement when required_consumer_groups is already 0"));
                            return getMessageRow(messageIds.get(0));
                        })
                        .map(state -> {
                            testContext.verify(() ->
                                assertTrue(state.getInteger("required_consumer_groups") >= 0,
                                        "required_consumer_groups should never be negative"));
                            return (Void) null;
                        })
                )
                .onSuccess(v -> {
                    logger.info("Zero-guard verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

        /**
         * Explicit decrement logic proof:
         * when required_consumer_groups is decremented from 1 to 0, message should
         * be auto-completed instead of being left PENDING/PROCESSING.
         */
        @Test
        void testDecrementToZeroAutoCompletesMessage(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-to-zero");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-dead"))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds ->
                    markSubscriptionDead(topic, "group-dead")
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-dead"))
                        .compose(result -> {
                            testContext.verify(() -> {
                                assertEquals(2, result.messagesDecremented(),
                                    "Both messages should be decremented from required=1 to required=0");
                                assertTrue(result.messagesAutoCompleted() >= 2,
                                    "Messages should be auto-completed when completed(0) >= required(0)");
                            });
                            Future<Void> verifyChain = Future.succeededFuture();
                            for (Long msgId : messageIds) {
                                verifyChain = verifyChain.compose(v -> getMessageRow(msgId).map(state -> {
                                    testContext.verify(() -> {
                                        assertEquals("COMPLETED", state.getString("status"),
                                            "Message should be auto-completed after required reaches 0");
                                        assertEquals(0, state.getInteger("required_consumer_groups"),
                                            "required_consumer_groups should be decremented to 0");
                                        assertEquals(0, state.getInteger("completed_consumer_groups"),
                                            "completed_consumer_groups remains 0 (no consumer completion rows)");
                                    });
                                    return (Void) null;
                                }));
                            }
                            return verifyChain;
                        })
                )
                .onSuccess(v -> {
                    logger.info("Decrement-to-zero auto-complete verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        }

        /**
         * Explicit decrement logic proof:
         * cleanup must NOT decrement historical messages that were created before the
         * dead group subscribed (the dead group was never part of required quorum).
         */
        @Test
        void testDoesNotDecrementMessagesCreatedBeforeDeadGroupSubscribed(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("cleanup-late-subscriber");

        createPubSubTopic(topic)
                .compose(v -> subscribe(topic, "group-a"))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds ->
                    manager.getVertx().timer(20)
                        .compose(v -> subscribe(topic, "group-b"))
                        .compose(v -> markSubscriptionDead(topic, "group-b"))
                        .compose(v -> cleanup.cleanupDeadGroup(topic, "group-b"))
                        .compose(result -> {
                            testContext.verify(() -> {
                                assertEquals(0, result.messagesDecremented(),
                                    "Late-subscribed dead group should not decrement older messages");
                                assertEquals(0, result.messagesAutoCompleted(),
                                    "No auto-completion expected because required should remain unchanged");
                            });
                            Future<Void> verifyChain = Future.succeededFuture();
                            for (Long msgId : messageIds) {
                                verifyChain = verifyChain.compose(v -> getMessageRow(msgId).map(state -> {
                                    testContext.verify(() -> {
                                        assertEquals("PENDING", state.getString("status"),
                                            "Message should remain pending (no completion yet)");
                                        assertEquals(1, state.getInteger("required_consumer_groups"),
                                            "required_consumer_groups should stay 1; group-b was never required");
                                        assertEquals(0, state.getInteger("completed_consumer_groups"));
                                    });
                                    return (Void) null;
                                }));
                            }
                            return verifyChain;
                        })
                )
                .onSuccess(v -> {
                    logger.info("Late-subscriber no-decrement behavior verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Future<Void> createPubSubTopic(String topic) {
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .mapEmpty();
    }

    private Future<Void> subscribe(String topic, String groupName) {
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();
        return subscriptionManager.subscribe(topic, groupName, options).mapEmpty();
    }

    private Future<List<Long>> insertMessages(String topic, int count) {
        Future<List<Long>> chain = Future.succeededFuture(new ArrayList<>());
        for (int i = 0; i < count; i++) {
            final int index = i;
            chain = chain.compose(ids -> connectionManager.withConnection(SERVICE_ID, connection -> {
                String sql = """
                    INSERT INTO outbox (topic, payload, created_at, status)
                    VALUES ($1, $2::jsonb, $3, 'PENDING')
                    RETURNING id
                    """;
                JsonObject payload = new JsonObject().put("test", true).put("index", index);
                return connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload.encode(), OffsetDateTime.now(ZoneOffset.UTC)))
                        .map(rows -> rows.iterator().next().getLong("id"));
            }).map(id -> { ids.add(id); return ids; }));
        }
        return chain;
    }

    private Future<Void> completeMessage(Long messageId, String groupName) {
        return connectionManager.withTransaction(SERVICE_ID, connection -> {
            String insertSql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
                VALUES ($1, $2, 'COMPLETED', NOW())
                ON CONFLICT (message_id, group_name)
                DO UPDATE SET status = 'COMPLETED', processed_at = NOW()
                """;
            return connection.preparedQuery(insertSql)
                    .execute(Tuple.of(messageId, groupName))
                    .compose(v -> {
                        String updateSql = """
                            UPDATE outbox
                            SET completed_consumer_groups = completed_consumer_groups + 1,
                                status = CASE
                                    WHEN completed_consumer_groups + 1 >= required_consumer_groups
                                        THEN 'COMPLETED'
                                    ELSE status
                                END,
                                processed_at = CASE
                                    WHEN completed_consumer_groups + 1 >= required_consumer_groups
                                        THEN NOW()
                                    ELSE processed_at
                                END
                            WHERE id = $1
                              AND completed_consumer_groups < required_consumer_groups
                            """;
                        return connection.preparedQuery(updateSql)
                                .execute(Tuple.of(messageId))
                                .mapEmpty();
                    });
        });
    }

    private Future<Void> completeMessages(List<Long> messageIds, String groupName) {
        Future<Void> chain = Future.succeededFuture();
        for (Long msgId : messageIds) {
            chain = chain.compose(v -> completeMessage(msgId, groupName));
        }
        return chain;
    }

    private Future<Void> markSubscriptionDead(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = 'DEAD'
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .mapEmpty();
        });
    }

    private Future<Void> insertConsumerGroupRow(Long messageId, String groupName, String status) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status)
                VALUES ($1, $2, $3)
                ON CONFLICT (message_id, group_name) DO NOTHING
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName, status))
                    .mapEmpty();
        });
    }

    private Future<Void> setRequiredConsumerGroups(Long messageId, int required) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = "UPDATE outbox SET required_consumer_groups = $1 WHERE id = $2";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(required, messageId))
                    .mapEmpty();
        });
    }

    private Future<Void> assertMessageState(VertxTestContext testContext, Long messageId,
                                             String expectedStatus, int expectedRequired, int expectedCompleted) {
        return getMessageRow(messageId).map(row -> {
            testContext.verify(() -> {
                assertEquals(expectedStatus, row.getString("status"),
                        "Message " + messageId + " status mismatch");
                assertEquals(expectedRequired, row.getInteger("required_consumer_groups"),
                        "Message " + messageId + " required_consumer_groups mismatch");
                assertEquals(expectedCompleted, row.getInteger("completed_consumer_groups"),
                        "Message " + messageId + " completed_consumer_groups mismatch");
            });
            return (Void) null;
        });
    }

    private Future<Void> assertMessageStates(VertxTestContext testContext, List<Long> messageIds,
                                              String expectedStatus, int expectedRequired, int expectedCompleted) {
        Future<Void> chain = Future.succeededFuture();
        for (Long msgId : messageIds) {
            chain = chain.compose(v -> assertMessageState(testContext, msgId, expectedStatus, expectedRequired, expectedCompleted));
        }
        return chain;
    }

    private Future<Row> getMessageRow(Long messageId) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = "SELECT status, required_consumer_groups, completed_consumer_groups FROM outbox WHERE id = $1";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("Message not found: " + messageId);
                        }
                        return rows.iterator().next();
                    });
        });
    }

    private Future<String> getConsumerGroupStatus(Long messageId, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                SELECT status FROM outbox_consumer_groups
                WHERE message_id = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            return null;
                        }
                        return rows.iterator().next().getString("status");
                    });
        });
    }

    private Future<Boolean> consumerGroupRowExists(Long messageId, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                SELECT COUNT(*) as cnt FROM outbox_consumer_groups
                WHERE message_id = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName))
                    .map(rows -> rows.iterator().next().getInteger("cnt") > 0);
        });
    }

    // ========================================================================
    // Test: Cleanup error resilience — one group failure doesn't break others
    // ========================================================================

    /**
     * Tests that {@code cleanupAllDeadGroups()} continues processing remaining
     * dead groups when cleanup for one group fails.
     *
     * <p>Uses a test subclass that overrides {@code cleanupDeadGroup()} to
     * inject a failure for a specific topic. This follows the project's
     * established subclass-override pattern (e.g. FakeDeadLetterQueueManager).
     * {@code cleanupDeadGroup()} is already public, so no production code
     * changes are required.</p>
     *
     * <p>Validates the {@code .recover()} block in {@code cleanupAllDeadGroups()}
     * which catches per-group failures, logs them, and adds a zero-result
     * {@code CleanupResult} so the batch can continue.</p>
     */
    @Test
    void testCleanupContinuesAfterOneGroupFails(VertxTestContext testContext) throws InterruptedException {
        String topicOk = uniqueTopic("resilience-ok");
        String topicFail = uniqueTopic("resilience-fail");

        // Set up topic that will be cleaned successfully
        createPubSubTopic(topicOk)
                .compose(v -> subscribe(topicOk, "group-alive"))
                .compose(v -> subscribe(topicOk, "group-dead-ok"))
                .compose(v -> insertMessages(topicOk, 2))
                .compose(okMessageIds ->
                    completeMessages(okMessageIds, "group-alive")
                        .compose(v -> markSubscriptionDead(topicOk, "group-dead-ok"))
                        // Set up topic whose cleanup will fail
                        .compose(v -> createPubSubTopic(topicFail))
                        .compose(v -> subscribe(topicFail, "group-alive"))
                        .compose(v -> subscribe(topicFail, "group-dead-fail"))
                        .compose(v -> insertMessages(topicFail, 2))
                        .compose(failMessageIds ->
                            completeMessages(failMessageIds, "group-alive")
                                .compose(v -> markSubscriptionDead(topicFail, "group-dead-fail"))
                                .compose(v -> {
                                    // Create a test subclass that injects a failure for topicFail
                                    DeadConsumerGroupCleanup failingCleanup = new DeadConsumerGroupCleanup(
                                            connectionManager, SERVICE_ID) {
                                        @Override
                                        public Future<CleanupResult> cleanupDeadGroup(String topic, String groupName) {
                                            if (topic.equals(topicFail)) {
                                                return Future.failedFuture(
                                                        new RuntimeException("Simulated DB failure for topic=" + topic));
                                            }
                                            return super.cleanupDeadGroup(topic, groupName);
                                        }
                                    };
                                    return failingCleanup.cleanupAllDeadGroups();
                                })
                                .compose(results -> {
                                    var okResult = results.stream()
                                            .filter(r -> r.topic().equals(topicOk) && r.groupName().equals("group-dead-ok"))
                                            .findFirst();
                                    var failResult = results.stream()
                                            .filter(r -> r.topic().equals(topicFail) && r.groupName().equals("group-dead-fail"))
                                            .findFirst();

                                    testContext.verify(() -> {
                                        assertTrue(okResult.isPresent(), "Should have result for successfully cleaned group");
                                        assertTrue(okResult.get().hadWork(), "Good group should have been cleaned");
                                        assertEquals(2, okResult.get().messagesDecremented(),
                                                "Good group should have decremented 2 messages");
                                        assertTrue(failResult.isPresent(),
                                                "Failed group should still have a result (from .recover() block)");
                                        assertFalse(failResult.get().hadWork(),
                                                ".recover() should produce a zero-work CleanupResult");
                                        assertEquals(0, failResult.get().messagesDecremented(),
                                                "Failed group should have 0 decremented");
                                        assertEquals(0, failResult.get().orphanRowsRemoved(),
                                                "Failed group should have 0 orphans removed");
                                        assertEquals(0, failResult.get().messagesAutoCompleted(),
                                                "Failed group should have 0 auto-completed");
                                    });

                                    // Verify messages on the good topic were cleaned up
                                    Future<Void> verifyOk = Future.succeededFuture();
                                    for (Long msgId : okMessageIds) {
                                        verifyOk = verifyOk.compose(x -> getMessageRow(msgId).map(state -> {
                                            testContext.verify(() -> {
                                                assertEquals("COMPLETED", state.getString("status"),
                                                        "Good topic messages should be auto-completed");
                                                assertEquals(1, state.getInteger("required_consumer_groups"),
                                                        "required_consumer_groups should be decremented from 2 to 1");
                                            });
                                            return (Void) null;
                                        }));
                                    }
                                    // Verify messages on the failed topic were NOT cleaned up
                                    Future<Void> verifyFail = verifyOk;
                                    for (Long msgId : failMessageIds) {
                                        verifyFail = verifyFail.compose(x -> getMessageRow(msgId).map(state -> {
                                            testContext.verify(() -> {
                                                assertEquals("PENDING", state.getString("status"),
                                                        "Failed topic messages should still be PENDING");
                                                assertEquals(2, state.getInteger("required_consumer_groups"),
                                                        "Failed topic required_consumer_groups should still be 2");
                                            });
                                            return (Void) null;
                                        }));
                                    }
                                    return verifyFail;
                                })
                        )
                )
                .onSuccess(v -> {
                    logger.info("Cleanup error resilience verified: good group cleaned, failed group produced zero-result");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
}
