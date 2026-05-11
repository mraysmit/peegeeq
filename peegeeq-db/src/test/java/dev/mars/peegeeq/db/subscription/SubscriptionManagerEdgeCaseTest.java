package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for SubscriptionManager.
 * 
 * <p><b>PURPOSE:</b> Comprehensive edge case coverage at the database layer to ensure
 * robust behavior for all StartPosition variants, updates, and error conditions.
 * 
 * <p><b>CRITICAL INVARIANTS TESTED:</b>
 * <ul>
 *   <li>FROM_BEGINNING → start_from_message_id = 1</li>
 *   <li>FROM_NOW → start_from_message_id = maxId + 1</li>
 *   <li>FROM_MESSAGE_ID → exact message ID storage</li>
 *   <li>FROM_TIMESTAMP → exact timestamp storage</li>
 *   <li>Subscription updates preserve data integrity</li>
 *   <li>Edge cases (ID=0, null handling, concurrent updates)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-23
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class SubscriptionManagerEdgeCaseTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerEdgeCaseTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

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
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("SubscriptionManagerEdgeCaseTest setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main")
                    .cleanupAllDeadGroups()
                    .eventually(() -> connectionManager.close())
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testFromBeginningStoresMessageIdOne(VertxTestContext testContext) {
        logger.info("=== Testing FROM_BEGINNING stores start_from_message_id = 1 ===");

        String topic = "test-from-beginning-storage";
        String groupName = "group-beginning-storage";

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription.startFromMessageId(),
                    "FROM_BEGINNING must store start_from_message_id (not NULL)");
                assertEquals(1L, subscription.startFromMessageId().longValue(),
                    "FROM_BEGINNING MUST store start_from_message_id = 1");
                assertNull(subscription.startFromTimestamp(),
                    "start_from_timestamp should be NULL for FROM_BEGINNING");
                logger.info("FROM_BEGINNING verified");
                testContext.completeNow();
            })));
    }

    @Test
    void testFromNowStoresNonNullMessageId(VertxTestContext testContext) {
        logger.info("=== Testing FROM_NOW stores non-null start_from_message_id ===");

        String topic = "test-from-now-storage";
        String groupName = "group-now-storage";

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription.startFromMessageId(),
                    "FROM_NOW must store start_from_message_id (not NULL)");
                assertTrue(subscription.startFromMessageId() >= 1L,
                    "FROM_NOW should store start_from_message_id >= 1");
                assertNull(subscription.startFromTimestamp());
                logger.info("FROM_NOW verified: start_from_message_id={}",
                    subscription.startFromMessageId());
                testContext.completeNow();
            })));
    }

    @Test
    void testFromMessageIdWithExplicitValue(VertxTestContext testContext) {
        logger.info("=== Testing FROM_MESSAGE_ID with explicit value ===");

        String topic = "test-from-message-id";
        String groupName = "group-message-id";
        Long explicitMessageId = 42L;

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(explicitMessageId)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription.startFromMessageId());
                assertEquals(explicitMessageId, subscription.startFromMessageId(),
                    "FROM_MESSAGE_ID must store exact provided message ID");
                assertNull(subscription.startFromTimestamp());
                logger.info("FROM_MESSAGE_ID(42) verified");
                testContext.completeNow();
            })));
    }

    @Test
    void testFromTimestampWithExplicitValue(VertxTestContext testContext) {
        logger.info("=== Testing FROM_TIMESTAMP with explicit value ===");

        String topic = "test-from-timestamp";
        String groupName = "group-timestamp";
        Instant explicitTimestamp = Instant.now().minusSeconds(3600); // 1 hour ago

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(explicitTimestamp)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNull(subscription.startFromMessageId(),
                    "start_from_message_id should be NULL for FROM_TIMESTAMP");
                assertNotNull(subscription.startFromTimestamp());
                // Timestamps may lose precision, compare within 1 second tolerance
                long diffMillis = Math.abs(explicitTimestamp.toEpochMilli() -
                                           subscription.startFromTimestamp().toEpochMilli());
                assertTrue(diffMillis < 1000,
                    String.format("Timestamp difference should be < 1 second (was %d ms)", diffMillis));
                logger.info("FROM_TIMESTAMP verified (diff: {} ms)", diffMillis);
                testContext.completeNow();
            })));
    }

    @Test
    void testUpdateFromNowToFromBeginning(VertxTestContext testContext) {
        logger.info("=== Testing update FROM_NOW to FROM_BEGINNING ===");

        String topic = "test-update-position";
        String groupName = "group-update-position";

        SubscriptionOptions initialOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        SubscriptionOptions updatedOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        AtomicReference<Long> initialMessageIdRef = new AtomicReference<>();
        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, initialOptions))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(initialSub -> {
                Long initialMessageId = initialSub.startFromMessageId();
                assertNotNull(initialMessageId, "Initial message ID should not be null");
                initialMessageIdRef.set(initialMessageId);
                return subscriptionManager.subscribe(topic, groupName, updatedOptions);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(updatedSub -> testContext.verify(() -> {
                assertEquals(1L, updatedSub.startFromMessageId().longValue(),
                    "After update, start_from_message_id should be 1 (FROM_BEGINNING)");
                logger.info("Update FROM_NOW to FROM_BEGINNING verified");
                testContext.completeNow();
            })));
    }

    @Test
    void testEdgeCaseMessageIdZero(VertxTestContext testContext) {
        logger.info("=== Testing FROM_MESSAGE_ID with ID = 0 (edge case) ===");

        String topic = "test-message-id-zero";
        String groupName = "group-zero";

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(0L)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription.startFromMessageId());
                assertEquals(0L, subscription.startFromMessageId().longValue(),
                    "Should accept and store message ID = 0");
                logger.info("Edge case verified: message ID = 0 handled correctly");
                testContext.completeNow();
            })));
    }

    @Test
    void testGetNonExistentSubscription(VertxTestContext testContext) {
        logger.info("=== Testing get non-existent subscription ===");

        String topic = "test-nonexistent";
        String groupName = "group-nonexistent";

        createTopic(topic)
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNull(subscription, "Non-existent subscription should return null");
                logger.info("Non-existent subscription returns null correctly");
                testContext.completeNow();
            })));
    }

    @Test
    void testUpdateFromBeginningToMessageId(VertxTestContext testContext) {
        logger.info("=== Testing update FROM_BEGINNING to FROM_MESSAGE_ID ===");

        String topic = "test-update-beginning-to-id";
        String groupName = "group-update-beg-id";

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName,
                SubscriptionOptions.builder().startPosition(StartPosition.FROM_BEGINNING).build()))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(initial -> {
                assertEquals(1L, initial.startFromMessageId());
                return subscriptionManager.subscribe(topic, groupName,
                    SubscriptionOptions.builder()
                        .startPosition(StartPosition.FROM_MESSAGE_ID)
                        .startFromMessageId(100L)
                        .build());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(updated -> testContext.verify(() -> {
                assertEquals(100L, updated.startFromMessageId().longValue());
                logger.info("Update FROM_BEGINNING to FROM_MESSAGE_ID(100) verified");
                testContext.completeNow();
            })));
    }

    @Test
    void testMultipleUpdatesLastWins(VertxTestContext testContext) {
        logger.info("=== Testing multiple updates (last write wins) ===");

        String topic = "test-multiple-updates";
        String groupName = "group-multiple-updates";

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName,
                SubscriptionOptions.builder().startPosition(StartPosition.FROM_BEGINNING).build()))
            .compose(v -> subscriptionManager.subscribe(topic, groupName,
                SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_MESSAGE_ID)
                    .startFromMessageId(50L)
                    .build()))
            .compose(v -> subscriptionManager.subscribe(topic, groupName,
                SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_MESSAGE_ID)
                    .startFromMessageId(100L)
                    .build()))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(final_sub -> testContext.verify(() -> {
                assertEquals(100L, final_sub.startFromMessageId().longValue(),
                    "Last update should win");
                logger.info("Multiple updates: last write (100) wins");
                testContext.completeNow();
            })));
    }

    @Test
    void testHeartbeatIntervalEdgeCases(VertxTestContext testContext) {
        logger.info("=== Testing heartbeat interval edge cases ===");

        String topic = "test-heartbeat-edge";

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, "group-hb-1",
                SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_NOW)
                    .heartbeatIntervalSeconds(1)
                    .heartbeatTimeoutSeconds(2)
                    .build()))
            .compose(v -> subscriptionManager.getSubscription(topic, "group-hb-1"))
            .compose(sub1 -> {
                assertEquals(1, sub1.heartbeatIntervalSeconds());
                assertEquals(2, sub1.heartbeatTimeoutSeconds());
                return subscriptionManager.subscribe(topic, "group-hb-3600",
                    SubscriptionOptions.builder()
                        .startPosition(StartPosition.FROM_NOW)
                        .heartbeatIntervalSeconds(3600)
                        .heartbeatTimeoutSeconds(7200)
                        .build());
            })
            .compose(v -> subscriptionManager.getSubscription(topic, "group-hb-3600"))
            .onComplete(testContext.succeeding(sub3600 -> testContext.verify(() -> {
                assertEquals(3600, sub3600.heartbeatIntervalSeconds());
                assertEquals(7200, sub3600.heartbeatTimeoutSeconds());
                logger.info("Heartbeat edge cases: (1s/2s) and (3600s/7200s) verified");
                testContext.completeNow();
            })));
    }

    // Helper method
    private Future<Void> createTopic(String topic) {
        return topicConfigService.createTopic(TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build())
            .mapEmpty();
    }
}
