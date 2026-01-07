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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

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
public class P3_MixedTopicsTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(P3_MixedTopicsTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(20)
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        cleanupService = new CleanupService(connectionManager, "peegeeq-main");

        logger.info("P3 Mixed Topics Test setup complete");
    }

    /**
     * Test QUEUE and PUB_SUB topics running concurrently.
     * Validates correct distribution vs replication semantics.
     */
    @Test
    void testMixedTopicsConcurrent() throws Exception {
        logger.info("=== P3: MIXED TOPICS TEST ===");

        // Use unique topic names to avoid conflicts in parallel test execution
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String queueTopic = "perf-test-queue-" + uniqueId;
        String pubsubTopic = "perf-test-pubsub-" + uniqueId;
        int messageCount = 300;  // Per topic
        int payloadSizeBytes = 2048;

        logger.info("=== TEST CONFIGURATION ===");
        logger.info("QUEUE Topic: {}", queueTopic);
        logger.info("PUB_SUB Topic: {}", pubsubTopic);
        logger.info("Messages per topic: {}", messageCount);
        logger.info("Payload Size: {} bytes", payloadSizeBytes);

        // Step 1: Create QUEUE topic with 3 consumer groups (competing consumers)
        TopicConfig queueConfig = TopicConfig.builder()
            .topic(queueTopic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(1)
            .build();
        topicConfigService.createTopic(queueConfig)
            .toCompletionStage().toCompletableFuture().get();

        List<String> queueGroups = List.of("queue-consumer-1", "queue-consumer-2", "queue-consumer-3");
        for (String groupName : queueGroups) {
            subscriptionManager.subscribe(queueTopic, groupName, SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();
        }

        // Step 2: Create PUB_SUB topic with 3 consumer groups (all receive all messages)
        TopicConfig pubsubConfig = TopicConfig.builder()
            .topic(pubsubTopic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();
        topicConfigService.createTopic(pubsubConfig)
            .toCompletionStage().toCompletableFuture().get();

        List<String> pubsubGroups = List.of("pubsub-group-1", "pubsub-group-2", "pubsub-group-3");
        for (String groupName : pubsubGroups) {
            subscriptionManager.subscribe(pubsubTopic, groupName, SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();
        }

        // Step 3: Publish messages to both topics concurrently
        long publishStartTime = System.currentTimeMillis();

        // Publish to QUEUE topic
        List<Future<Long>> queueFutures = publishMessages(queueTopic, messageCount, payloadSizeBytes);
        Future.all(queueFutures).toCompletionStage().toCompletableFuture().get();

        // Publish to PUB_SUB topic
        List<Future<Long>> pubsubFutures = publishMessages(pubsubTopic, messageCount, payloadSizeBytes);
        Future.all(pubsubFutures).toCompletionStage().toCompletableFuture().get();

        long publishEndTime = System.currentTimeMillis();
        long publishDurationMs = publishEndTime - publishStartTime;

        // Step 4: Consume from QUEUE topic (messages distributed across 3 groups)
        long queueConsumeStart = System.currentTimeMillis();
        AtomicInteger queueTotalConsumed = new AtomicInteger(0);

        for (String groupName : queueGroups) {
            int consumed = 0;
            while (true) {
                List<OutboxMessage> messages = fetcher.fetchMessages(queueTopic, groupName, 50)
                    .toCompletionStage().toCompletableFuture().get();

                if (messages.isEmpty()) {
                    break;
                }

                for (OutboxMessage message : messages) {
                    completionTracker.markCompleted(message.getId(), groupName, queueTopic)
                        .toCompletionStage().toCompletableFuture().get();
                    consumed++;
                    queueTotalConsumed.incrementAndGet();
                }
            }
            logger.info("QUEUE consumer '{}' consumed {} messages", groupName, consumed);
        }

        long queueConsumeEnd = System.currentTimeMillis();
        long queueConsumeDurationMs = queueConsumeEnd - queueConsumeStart;

        // Step 5: Consume from PUB_SUB topic (all groups receive all messages)
        long pubsubConsumeStart = System.currentTimeMillis();
        AtomicInteger pubsubTotalConsumed = new AtomicInteger(0);

        for (String groupName : pubsubGroups) {
            int consumed = 0;
            while (consumed < messageCount) {
                List<OutboxMessage> messages = fetcher.fetchMessages(pubsubTopic, groupName, 50)
                    .toCompletionStage().toCompletableFuture().get();

                if (messages.isEmpty()) {
                    break;
                }

                for (OutboxMessage message : messages) {
                    completionTracker.markCompleted(message.getId(), groupName, pubsubTopic)
                        .toCompletionStage().toCompletableFuture().get();
                    consumed++;
                    pubsubTotalConsumed.incrementAndGet();
                }
            }
            logger.info("PUB_SUB consumer '{}' consumed {} messages", groupName, consumed);
        }

        long pubsubConsumeEnd = System.currentTimeMillis();
        long pubsubConsumeDurationMs = pubsubConsumeEnd - pubsubConsumeStart;

        // Step 6: Validate semantics
        // QUEUE: Total consumed should equal messageCount (distributed)
        assertEquals(messageCount, queueTotalConsumed.get(),
            "QUEUE topic should distribute " + messageCount + " messages across all consumers");

        // PUB_SUB: Total consumed should equal messageCount * groupCount (replicated)
        int expectedPubsubConsumed = messageCount * pubsubGroups.size();
        assertEquals(expectedPubsubConsumed, pubsubTotalConsumed.get(),
            "PUB_SUB topic should replicate " + messageCount + " messages to all " + pubsubGroups.size() + " groups");

        // Step 7: Cleanup
        int queueDeleted = cleanupService.cleanupCompletedMessages(queueTopic, 100)
            .toCompletionStage().toCompletableFuture().get();
        int pubsubDeleted = cleanupService.cleanupCompletedMessages(pubsubTopic, 100)
            .toCompletionStage().toCompletableFuture().get();

        // Log performance summary
        logger.info("=== PERFORMANCE SUMMARY ===");
        logger.info("Total Publish Duration: {} ms", publishDurationMs);
        logger.info("QUEUE Consume Duration: {} ms", queueConsumeDurationMs);
        logger.info("PUB_SUB Consume Duration: {} ms", pubsubConsumeDurationMs);
        logger.info("QUEUE Messages Consumed: {} (expected: {})", queueTotalConsumed.get(), messageCount);
        logger.info("PUB_SUB Messages Consumed: {} (expected: {})", pubsubTotalConsumed.get(), expectedPubsubConsumed);
        logger.info("QUEUE Messages Cleaned: {}", queueDeleted);
        logger.info("PUB_SUB Messages Cleaned: {}", pubsubDeleted);
        logger.info("=== P3: MIXED TOPICS TEST COMPLETE ===");
    }

    private List<Future<Long>> publishMessages(String topic, int count, int payloadSizeBytes) {
        List<Future<Long>> futures = new ArrayList<>();
        int batchSize = 50;

        for (int batchStart = 0; batchStart < count; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, count);

            for (int i = batchStart; i < batchEnd; i++) {
                JsonObject payload = generatePayload(i, payloadSizeBytes);
                futures.add(insertMessage(topic, payload));
            }

            // Wait for batch to complete to avoid connection pool exhaustion
            try {
                Future.all(futures.subList(batchStart, Math.min(batchEnd, futures.size())))
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to publish batch", e);
            }
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

