package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.CleanupService;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.consumer.CompletionTracker;
import dev.mars.peegeeq.db.consumer.ConsumerGroupFetcher;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
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
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3: Mixed Topics Test - Validates QUEUE and PUB_SUB topics running concurrently.
 *
 * <p>This test validates:
 * <ul>
 *   <li>QUEUE topic (distribution semantics) with 3 competing consumers</li>
 *   <li>PUB_SUB topic (replication semantics) with 3 consumer groups</li>
 *   <li>Both topics running concurrently without interference</li>
 *   <li>Correct message distribution vs replication behavior</li>
 * </ul>
 *
 * <p>Performance Target: No interference between QUEUE and PUB_SUB workloads
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Isolated("Performance test requires exclusive database access")
public class P3_MixedTopicsTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(P3_MixedTopicsTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(5))
            .connectionTimeout(Duration.ofSeconds(30))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        cleanupService = new CleanupService(connectionManager, "peegeeq-main");

        logger.info("P3 Mixed Topics Test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    /**
     * Test QUEUE and PUB_SUB topics running concurrently.
     * Validates correct distribution vs replication semantics.
     */
    @Test
    void testMixedTopicsConcurrent(VertxTestContext testContext) {
        logger.info("=== P3: MIXED TOPICS TEST ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String queueTopic = "perf-test-queue-" + uniqueId;
        String pubsubTopic = "perf-test-pubsub-" + uniqueId;
        int messageCount = 300;
        int payloadSizeBytes = 2048;
        List<String> queueGroups = List.of("queue-consumer-1", "queue-consumer-2", "queue-consumer-3");
        List<String> pubsubGroups = List.of("pubsub-group-1", "pubsub-group-2", "pubsub-group-3");

        logger.info("QUEUE Topic: {}, PUB_SUB Topic: {}, {} messages, {} bytes",
            queueTopic, pubsubTopic, messageCount, payloadSizeBytes);

        // Step 1: Create QUEUE topic + subscriptions
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(queueTopic).semantics(TopicSemantics.QUEUE).messageRetentionHours(1).build())
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (String g : queueGroups) {
                    chain = chain.compose(ignored ->
                        subscriptionManager.subscribe(queueTopic, g, SubscriptionOptions.defaults()).mapEmpty());
                }
                return chain;
            })
            // Step 2: Create PUB_SUB topic + subscriptions
            .compose(v -> topicConfigService.createTopic(TopicConfig.builder()
                    .topic(pubsubTopic).semantics(TopicSemantics.PUB_SUB).messageRetentionHours(1).build()))
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (String g : pubsubGroups) {
                    chain = chain.compose(ignored ->
                        subscriptionManager.subscribe(pubsubTopic, g, SubscriptionOptions.defaults()).mapEmpty());
                }
                return chain;
            })
            // Step 3: Publish to both topics
            .compose(v -> {
                long start = System.currentTimeMillis();
                return publishInBatches(queueTopic, messageCount, payloadSizeBytes, 50)
                    .compose(ignored -> publishInBatches(pubsubTopic, messageCount, payloadSizeBytes, 50))
                    .map(ignored -> {
                        logger.info("Publish done in {} ms", System.currentTimeMillis() - start);
                        return null;
                    });
            })
            // Step 4: Consume QUEUE topic (distributed total = messageCount)
            .compose(v -> {
                AtomicInteger queueTotal = new AtomicInteger(0);
                Future<Void> chain = Future.succeededFuture();
                for (String g : queueGroups) {
                    chain = chain.compose(ignored ->
                        consumeUntilEmpty(queueTopic, g).map(n -> {
                            queueTotal.addAndGet(n);
                            logger.info("QUEUE consumer '{}' consumed {} messages", g, n);
                            return null;
                        })
                    );
                }
                return chain.map(ignored -> queueTotal.get());
            })
            .compose(queueTotal -> {
                assertEquals(messageCount, queueTotal,
                    "QUEUE topic should distribute " + messageCount + " messages across all consumers");
                // Step 5: Consume PUB_SUB topic (replicated total = messageCount * groups)
                AtomicInteger pubsubTotal = new AtomicInteger(0);
                Future<Void> chain = Future.succeededFuture();
                for (String g : pubsubGroups) {
                    chain = chain.compose(ignored ->
                        consumeUntilEmpty(pubsubTopic, g).map(n -> {
                            pubsubTotal.addAndGet(n);
                            logger.info("PUB_SUB consumer '{}' consumed {} messages", g, n);
                            return null;
                        })
                    );
                }
                int expectedPubsub = messageCount * pubsubGroups.size();
                return chain.map(ignored -> {
                    assertEquals(expectedPubsub, pubsubTotal.get(),
                        "PUB_SUB topic should replicate to all groups");
                    return null;
                });
            })
            // Step 6: Cleanup
            .compose(v -> cleanupService.cleanupCompletedMessages(queueTopic, 100)
                .compose(qDel -> cleanupService.cleanupCompletedMessages(pubsubTopic, 100)
                    .map(pDel -> {
                        logger.info("Cleaned {} QUEUE, {} PUB_SUB messages", qDel, pDel);
                        logger.info("=== P3: MIXED TOPICS TEST COMPLETE ===");
                        return null;
                    })))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    private Future<Void> publishInBatches(String topic, int count, int payloadSizeBytes, int batchSize) {
        Future<Void> chain = Future.succeededFuture();
        for (int batchStart = 0; batchStart < count; batchStart += batchSize) {
            final int start = batchStart;
            final int end = Math.min(batchStart + batchSize, count);
            chain = chain.compose(ignored -> {
                List<Future<Long>> batch = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    batch.add(insertMessage(topic, generatePayload(i, payloadSizeBytes)));
                }
                return Future.all(batch).mapEmpty();
            });
        }
        return chain;
    }

    private Future<Integer> consumeUntilEmpty(String topic, String groupName) {
        AtomicInteger consumed = new AtomicInteger(0);
        return consumeLoop(topic, groupName, consumed);
    }

    private Future<Integer> consumeLoop(String topic, String groupName, AtomicInteger consumed) {
        return fetcher.fetchMessages(topic, groupName, 50)
            .compose(messages -> {
                if (messages.isEmpty()) {
                    return Future.succeededFuture(consumed.get());
                }
                Future<Void> markChain = Future.succeededFuture();
                for (OutboxMessage message : messages) {
                    markChain = markChain.compose(ignored ->
                        completionTracker.markCompleted(message.getId(), groupName, topic).mapEmpty());
                }
                return markChain.compose(ignored -> {
                    consumed.addAndGet(messages.size());
                    return consumeLoop(topic, groupName, consumed);
                });
            });
    }

    private List<Future<Long>> publishMessages(String topic, int count, int payloadSizeBytes) {
        // Retained for compatibility; prefer publishInBatches for reactive use.
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(insertMessage(topic, generatePayload(i, payloadSizeBytes)));
        }
        return futures;
    }

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "INSERT INTO outbox (topic, payload, created_at) VALUES ($1, $2, $3) RETURNING id";
            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC)))
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    return Future.succeededFuture(row.getLong("id"));
                });
        });
    }

    private JsonObject generatePayload(int messageIndex, int sizeBytes) {
        JsonObject payload = new JsonObject()
            .put("messageIndex", messageIndex)
            .put("timestamp", System.currentTimeMillis());

        int currentSize = payload.encode().length();
        if (currentSize < sizeBytes) {
            int paddingSize = sizeBytes - currentSize - 20;
            payload.put("padding", "x".repeat(Math.max(0, paddingSize)));
        }

        return payload;
    }
}

