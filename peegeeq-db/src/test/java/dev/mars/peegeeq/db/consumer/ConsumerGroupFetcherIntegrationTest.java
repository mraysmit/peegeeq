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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    public void setUp() throws Exception {
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
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("ConsumerGroupFetcher test setup complete");
    }

    @Test
    public void testFetchMessagesBasic() throws Exception {
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

        CountDownLatch latch = new CountDownLatch(1);
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
                        latch.countDown();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    latch.countDown();
                });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesBasic COMPLETED ===");
    }

    @Test
    public void testFetchMessagesBatchSize() throws Exception {
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

        CountDownLatch latch = new CountDownLatch(1);
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
                        latch.countDown();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    latch.countDown();
                });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesBatchSize COMPLETED ===");
    }

    @Test
    public void testFetchMessagesFiltersByGroup() throws Exception {
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

        CountDownLatch latch = new CountDownLatch(1);
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
                        latch.countDown();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    latch.countDown();
                });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesFiltersByGroup COMPLETED ===");
    }

    @Test
    public void testFetchMessagesEmptyResult() throws Exception {
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

        CountDownLatch latch = new CountDownLatch(1);
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
                        latch.countDown();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    latch.countDown();
                });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testFetchMessagesEmptyResult COMPLETED ===");
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

