package dev.mars.peegeeq.db.fanout;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance validation test for consumer group fanout (Phase 6 - Option A).
 *
 * <p>This test validates basic performance characteristics with higher message volumes:
 * <ul>
 *   <li>Throughput measurement with 1000 messages</li>
 *   <li>Fanout to 4 consumer groups</li>
 *   <li>End-to-end latency tracking</li>
 *   <li>Completion tracking validation</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Isolated("Performance test requires exclusive database access")
public class FanoutPerformanceValidationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FanoutPerformanceValidationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() {
        logger.info("=== PERFORMANCE TEST SETUP STARTED ===");

        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
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
            .maxSize(5)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(5))
            .connectionTimeout(Duration.ofSeconds(30))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create service instances
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        cleanupService = new CleanupService(connectionManager, "peegeeq-main");

        logger.info("=== PERFORMANCE TEST SETUP COMPLETE ===");
        logger.info("Performance test setup complete");
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
     * P1-Simple: Basic throughput validation with 1000 messages and 4 consumer groups.
     * This test measures end-to-end throughput including production, fanout, consumption, and cleanup.
     */
    @Test
    void testBasicThroughputValidation(VertxTestContext testContext) {
        logger.info("=== TEST METHOD: testBasicThroughputValidation STARTED ===");

        String topic = "perf-test-basic-" + UUID.randomUUID().toString().substring(0, 8);
        int messageCount = 1000;
        int consumerGroupCount = 4;
        int payloadSizeBytes = 2048;
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < consumerGroupCount; i++) {
            groups.add("perf-group-" + i);
        }

        logger.info("Starting performance validation: {} messages, {} groups, {} byte payload",
            messageCount, consumerGroupCount, payloadSizeBytes);

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();

        // Step 1: Create topic
        topicConfigService.createTopic(config)
            // Step 2: Create consumer group subscriptions
            .compose(v -> {
                Future<Void> subChain = Future.succeededFuture();
                for (String groupName : groups) {
                    subChain = subChain.compose(ignored ->
                        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()).mapEmpty());
                }
                return subChain;
            })
            // Step 3: Publish messages in batches
            .compose(v -> {
                AtomicLong publishStart = new AtomicLong(System.currentTimeMillis());
                AtomicReference<Long> firstMessageId = new AtomicReference<>();
                return publishInBatches(topic, messageCount, payloadSizeBytes, 50, firstMessageId)
                    .map(ignored -> {
                        long elapsed = System.currentTimeMillis() - publishStart.get();
                        double throughput = (messageCount * 1000.0) / elapsed;
                        logger.info("Published {} messages in {} ms ({} msg/sec)",
                            messageCount, elapsed, String.format("%.2f", throughput));
                        return firstMessageId.get();
                    });
            })
            // Step 4: Verify fanout
            .compose(firstId -> getRequiredConsumerGroups(firstId).map(req -> {
                assertEquals(consumerGroupCount, req,
                    "Messages should be fanned out to all consumer groups");
                logger.info("Fanout verified: required_consumer_groups = {}", req);
                return null;
            }))
            // Step 5: Consume messages from all groups
            .compose(v -> {
                long consumeStart = System.currentTimeMillis();
                AtomicInteger totalConsumed = new AtomicInteger(0);
                Future<Void> consumeChain = Future.succeededFuture();
                for (String groupName : groups) {
                    consumeChain = consumeChain.compose(ignored ->
                        consumeAllMessages(topic, groupName, messageCount).map(consumed -> {
                            totalConsumed.addAndGet(consumed);
                            logger.info("Group {} consumed {} messages", groupName, consumed);
                            return null;
                        })
                    );
                }
                return consumeChain.map(ignored -> {
                    long elapsed = System.currentTimeMillis() - consumeStart;
                    double throughput = (totalConsumed.get() * 1000.0) / elapsed;
                    logger.info("Consumed {} total messages in {} ms ({} msg/sec)",
                        totalConsumed.get(), elapsed, String.format("%.2f", throughput));
                    int expected = messageCount * consumerGroupCount;
                    assertEquals(expected, totalConsumed.get(),
                        "All messages should be consumed by all groups");
                    return null;
                });
            })
            // Step 6: Cleanup
            .compose(v -> cleanupService.cleanupCompletedMessages(topic, 100).map(deleted -> {
                logger.info("Cleanup deleted {} messages", deleted);
                logger.info("=== TEST METHOD: testBasicThroughputValidation COMPLETED ===");
                return null;
            }))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    // Helper methods

    private Future<Void> publishInBatches(String topic, int messageCount, int payloadSizeBytes,
                                          int batchSize, AtomicReference<Long> firstMessageId) {
        Future<Void> chain = Future.succeededFuture();
        for (int batchStart = 0; batchStart < messageCount; batchStart += batchSize) {
            final int start = batchStart;
            final int end = Math.min(batchStart + batchSize, messageCount);
            chain = chain.compose(ignored -> {
                List<Future<Long>> batchFutures = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    batchFutures.add(insertMessage(topic, generatePayload(i, payloadSizeBytes)));
                }
                return Future.all(batchFutures).map(cf -> {
                    if (firstMessageId.get() == null && !batchFutures.isEmpty()) {
                        firstMessageId.set(batchFutures.get(0).result());
                    }
                    return null;
                });
            });
        }
        return chain;
    }

    private Future<Integer> consumeAllMessages(String topic, String groupName, int expectedCount) {
        AtomicInteger consumed = new AtomicInteger(0);
        return consumeLoop(topic, groupName, expectedCount, consumed)
            .map(ignored -> consumed.get());
    }

    private Future<Void> consumeLoop(String topic, String groupName, int expectedCount, AtomicInteger consumed) {
        if (consumed.get() >= expectedCount) {
            return Future.succeededFuture();
        }
        return fetcher.fetchMessages(topic, groupName, 100)
            .compose(messages -> {
                if (messages.isEmpty()) {
                    return Future.succeededFuture();
                }
                Future<Void> markChain = Future.succeededFuture();
                for (OutboxMessage message : messages) {
                    markChain = markChain.compose(ignored ->
                        completionTracker.markCompleted(message.getId(), groupName, topic).mapEmpty());
                }
                return markChain.compose(ignored -> {
                    consumed.addAndGet(messages.size());
                    return consumeLoop(topic, groupName, expectedCount, consumed);
                });
            });
    }

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;

            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));

            return connection.preparedQuery(sql)
                .execute(params)
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return row.getLong("id");
                });
        });
    }

    private Future<Integer> getRequiredConsumerGroups(Long messageId) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "SELECT required_consumer_groups FROM outbox WHERE id = $1";

            return connection.preparedQuery(sql)
                .execute(Tuple.of(messageId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new RuntimeException("Message not found: " + messageId);
                    }
                    Row row = rows.iterator().next();
                    return row.getInteger("required_consumer_groups");
                });
        });
    }

    private JsonObject generatePayload(int messageIndex, int sizeBytes) {
        // Generate a payload of approximately the specified size
        StringBuilder data = new StringBuilder();
        int targetDataSize = sizeBytes - 100; // Reserve space for JSON structure

        while (data.length() < targetDataSize) {
            data.append("x");
        }

        return new JsonObject()
            .put("messageIndex", messageIndex)
            .put("timestamp", System.currentTimeMillis())
            .put("data", data.toString());
    }
}
