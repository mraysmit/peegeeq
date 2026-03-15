package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.BaseIntegrationTest;
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
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConsumerGroupFetcher.
 * 
 * <p>Tests the message fetching logic for consumer groups including:
 * <ul>
 *   <li>Basic message fetching</li>
 *   <li>Concurrent consumer safety with FOR UPDATE SKIP LOCKED</li>
 *   <li>Filtering by consumer group</li>
 *   <li>FIFO ordering</li>
 * </ul>
 * </p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Tag(TestCategories.FLAKY)  // Tests are unstable in parallel execution - needs investigation
public class ConsumerGroupFetcherIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupFetcherIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    public void setUp(VertxTestContext testContext) throws Exception {
        super.setUpBaseIntegration();

        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        var postgres = getPostgres();
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

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("ConsumerGroupFetcher test setup complete");
    }

    @Test
    public void testFetchMessagesBasic(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testFetchMessagesBasic STARTED ===");

        String topic = "test-fetch-basic-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder()
                .build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message2")))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message3")))
                .compose(v -> fetcher.fetchMessages(topic, groupName, 10))
                .onSuccess(messages -> {
                    try {
                        logger.info("Fetched {} messages", messages.size());
                        assertEquals(3, messages.size(), "Should fetch 3 messages");

                        // Verify FIFO ordering
                        assertEquals("message1", messages.get(0).getPayload().getString("test"));
                        assertEquals("message2", messages.get(1).getPayload().getString("test"));
                        assertEquals("message3", messages.get(2).getPayload().getString("test"));
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesBasic COMPLETED ===");
    }

    @Test
    public void testFetchMessagesBatchSize(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testFetchMessagesBatchSize STARTED ===");

        String topic = "test-fetch-batch-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder()
                .build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message2")))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message3")))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message4")))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message5")))
                .compose(v -> fetcher.fetchMessages(topic, groupName, 2))  // Batch size = 2
                .onSuccess(messages -> {
                    try {
                        logger.info("Fetched {} messages with batch size 2", messages.size());
                        assertEquals(2, messages.size(), "Should fetch only 2 messages (batch size limit)");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesBatchSize COMPLETED ===");
    }

    @Test
    public void testFetchMessagesFiltersByGroup(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testFetchMessagesFiltersByGroup STARTED ===");

        String topic = "test-fetch-filter-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group1";
        String group2 = "group2";

        // Create topic and two subscriptions
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder()
                .build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, group1, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, group2, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> {
                    // Mark message as completed for group1 only
                    return markGroupCompleted(messageId, group1);
                })
                .compose(v -> fetcher.fetchMessages(topic, group1, 10))
                .compose(group1Messages -> {
                    logger.info("Group1 fetched {} messages", group1Messages.size());
                    assertEquals(0, group1Messages.size(), "Group1 should not fetch completed message");

                    // Group2 should still see the message
                    return fetcher.fetchMessages(topic, group2, 10);
                })
                .onSuccess(group2Messages -> {
                    try {
                        logger.info("Group2 fetched {} messages", group2Messages.size());
                        assertEquals(1, group2Messages.size(), "Group2 should fetch the message");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesFiltersByGroup COMPLETED ===");
    }

    @Test
    public void testFetchMessagesEmptyResult(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testFetchMessagesEmptyResult STARTED ===");

        String topic = "test-fetch-empty-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Create topic and subscription but no messages
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder()
                .build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> fetcher.fetchMessages(topic, groupName, 10))
                .onSuccess(messages -> {
                    try {
                        logger.info("Fetched {} messages (expected 0)", messages.size());
                        assertEquals(0, messages.size(), "Should fetch 0 messages when none exist");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesEmptyResult COMPLETED ===");
    }

    @Test
    public void testConcurrentFetchContentionSameGroupOnlyOneFetcherClaimsMessage(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testConcurrentFetchContentionSameGroupOnlyOneFetcherClaimsMessage STARTED ===");

        String topic = "test-fetch-concurrency-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(v -> {
                    Future<List<OutboxMessage>> fetch1 = fetcher.fetchMessages(topic, groupName, 1);
                    Future<List<OutboxMessage>> fetch2 = fetcher.fetchMessages(topic, groupName, 1);
                    return Future.all(fetch1, fetch2);
                })
                .onSuccess(result -> {
                    try {
                        @SuppressWarnings("unchecked")
                        List<OutboxMessage> messages1 = (List<OutboxMessage>) result.resultAt(0);
                        @SuppressWarnings("unchecked")
                        List<OutboxMessage> messages2 = (List<OutboxMessage>) result.resultAt(1);

                        int totalClaimed = messages1.size() + messages2.size();
                        assertEquals(1, totalClaimed,
                                "Concurrent fetches for same group should claim a message exactly once");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testConcurrentFetchContentionSameGroupOnlyOneFetcherClaimsMessage COMPLETED ===");
    }

    @Test
    public void testFailedMessageIsRefetchableForSameGroup(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testFailedMessageIsRefetchableForSameGroup STARTED ===");

        String topic = "test-refetch-failed-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(v -> fetcher.fetchMessages(topic, groupName, 1)
                        .compose(firstFetch -> {
                            assertEquals(1, firstFetch.size(), "First fetch should claim message");
                            Long messageId = firstFetch.get(0).getId();

                            return completionTracker.markFailed(messageId, groupName, topic, "simulated processing failure")
                                    .compose(ignore -> fetcher.fetchMessages(topic, groupName, 1)
                                            .map(secondFetch -> new JsonObject()
                                                    .put("messageId", messageId)
                                                    .put("secondFetchSize", secondFetch.size())
                                                    .put("secondFetchId", secondFetch.isEmpty() ? null : secondFetch.get(0).getId())));
                        }))
                .onSuccess(result -> {
                    try {
                        assertEquals(1, result.getInteger("secondFetchSize"),
                                "Failed message should be available for refetch");
                        assertEquals(result.getLong("messageId"), result.getLong("secondFetchId"),
                                "Refetched message should match original claimed message");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFailedMessageIsRefetchableForSameGroup COMPLETED ===");
    }

    @Test
    public void testStuckProcessingRequiresRecoveryTransitionBeforeRefetch(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testStuckProcessingRequiresRecoveryTransitionBeforeRefetch STARTED ===");

        String topic = "test-recover-stuck-processing-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> fetcher.fetchMessages(topic, groupName, 1)
                        .compose(firstFetch -> {
                            assertEquals(1, firstFetch.size(), "Initial fetch should claim message as PROCESSING");
                            assertEquals(messageId, firstFetch.get(0).getId());

                            // Simulate crash: message remains PROCESSING without completion/failure callback.
                            return fetcher.fetchMessages(topic, groupName, 1)
                                    .compose(secondFetchWhileStuck -> {
                                        assertEquals(0, secondFetchWhileStuck.size(),
                                                "Stuck PROCESSING row must not be reclaimed immediately");

                                        return completionTracker.markFailed(messageId, groupName, topic,
                                                        "simulated crash recovery")
                                                .compose(ignore -> fetcher.fetchMessages(topic, groupName, 1)
                                                        .map(afterRecoveryFetch -> new JsonObject()
                                                                .put("messageId", messageId)
                                                                .put("afterRecoverySize", afterRecoveryFetch.size())
                                                                .put("afterRecoveryId", afterRecoveryFetch.isEmpty()
                                                                        ? null : afterRecoveryFetch.get(0).getId())));
                                    });
                        }))
                .onSuccess(result -> {
                    try {
                        assertEquals(1, result.getInteger("afterRecoverySize"),
                                "Message should be reclaimable after recovery transition to FAILED");
                        assertEquals(result.getLong("messageId"), result.getLong("afterRecoveryId"),
                                "Reclaimed message should match the original stuck message");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testStuckProcessingRequiresRecoveryTransitionBeforeRefetch COMPLETED ===");
    }

    @Test
    public void testConcurrentFailRefetchCompleteMaintainsCrossTableConsistency(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testConcurrentFailRefetchCompleteMaintainsCrossTableConsistency STARTED ===");

        String topic = "test-cross-cutting-consistency-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        int messageCount = 20;
        int workers = 5;
        int batchSize = 10;

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessages(topic, messageCount))
                .compose(v -> fetchConcurrently(topic, groupName, workers, batchSize))
                .compose(firstFetch -> {
                    Set<Long> firstIds = new HashSet<>();
                    for (OutboxMessage message : firstFetch) {
                        firstIds.add(message.getId());
                    }
                    assertEquals(messageCount, firstIds.size(),
                            "First concurrent fetch round should claim each message exactly once");

                    List<Future<Void>> failFutures = new ArrayList<>();
                    for (Long id : firstIds) {
                        failFutures.add(completionTracker.markFailed(id, groupName, topic, "simulated failure"));
                    }

                    return Future.all(new ArrayList<>(failFutures))
                            .compose(x -> fetchConcurrently(topic, groupName, workers, batchSize))
                            .compose(secondFetch -> {
                                Set<Long> secondIds = new HashSet<>();
                                for (OutboxMessage message : secondFetch) {
                                    secondIds.add(message.getId());
                                }

                                assertEquals(firstIds, secondIds,
                                        "Retry fetch round should return the same failed message set");

                                List<Future<Void>> completeFutures = new ArrayList<>();
                                for (Long id : secondIds) {
                                    completeFutures.add(completionTracker.markCompleted(id, groupName, topic));
                                }

                                return Future.all(new ArrayList<>(completeFutures))
                                        .compose(x -> queryCrossTableConsistency(topic, groupName, messageCount));
                            });
                })
                .onSuccess(result -> {
                    try {
                        assertEquals(0, result.getInteger("mismatched_outbox_count"),
                                "No outbox rows should violate completion counters");
                        assertEquals(0, result.getInteger("non_completed_tracking_count"),
                                "All tracking rows for the consumer group should be COMPLETED");
                        assertEquals(messageCount, result.getInteger("completed_outbox_count"),
                                "All topic messages should be COMPLETED in outbox");
                        assertEquals(messageCount, result.getInteger("tracking_row_count"),
                                "Tracking row count should equal message count");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testConcurrentFailRefetchCompleteMaintainsCrossTableConsistency COMPLETED ===");
    }

    private Future<Void> insertMessages(String topic, int count) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= count; i++) {
            int index = i;
            chain = chain.compose(v -> insertMessage(topic, new JsonObject().put("index", index)).mapEmpty());
        }
        return chain;
    }

    @SuppressWarnings("unchecked")
    private Future<List<OutboxMessage>> fetchConcurrently(String topic, String groupName, int workers, int batchSize) {
        List<Future<List<OutboxMessage>>> fetchFutures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            fetchFutures.add(fetcher.fetchMessages(topic, groupName, batchSize));
        }

        return Future.all(new ArrayList<>(fetchFutures))
                .map(results -> {
                    List<OutboxMessage> allMessages = new ArrayList<>();
                    for (int i = 0; i < fetchFutures.size(); i++) {
                        allMessages.addAll((List<OutboxMessage>) results.resultAt(i));
                    }
                    return allMessages;
                });
    }

    private Future<JsonObject> queryCrossTableConsistency(String topic, String groupName, int expectedCount) {
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery("""
                    SELECT
                        SUM(CASE WHEN o.completed_consumer_groups != o.required_consumer_groups THEN 1 ELSE 0 END) AS mismatched_outbox_count,
                        SUM(CASE WHEN o.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_outbox_count,
                        COUNT(cg.*) AS tracking_row_count,
                        SUM(CASE WHEN cg.status != 'COMPLETED' THEN 1 ELSE 0 END) AS non_completed_tracking_count
                    FROM outbox o
                    LEFT JOIN outbox_consumer_groups cg
                      ON cg.message_id = o.id
                     AND cg.group_name = $2
                    WHERE o.topic = $1
                    """)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> {
                            Row row = rows.iterator().next();
                            return new JsonObject()
                                    .put("expected_count", expectedCount)
                                    .put("mismatched_outbox_count", row.getInteger("mismatched_outbox_count"))
                                    .put("completed_outbox_count", row.getInteger("completed_outbox_count"))
                                    .put("tracking_row_count", row.getInteger("tracking_row_count"))
                                    .put("non_completed_tracking_count", row.getInteger("non_completed_tracking_count"));
                        })
        );
    }

    // Helper method to insert a message
    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;
            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));
            return connection.preparedQuery(sql).execute(params)
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

    // Helper method to mark a group as completed
    private Future<Void> markGroupCompleted(Long messageId, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
                VALUES ($1, $2, 'COMPLETED', $3)
                ON CONFLICT (message_id, group_name) DO UPDATE SET status = 'COMPLETED'
                """;
            Tuple params = Tuple.of(messageId, groupName, OffsetDateTime.now(ZoneOffset.UTC));
            return connection.preparedQuery(sql).execute(params).mapEmpty();
        });
    }
}

