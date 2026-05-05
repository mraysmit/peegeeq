package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.BaseIntegrationTest;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for SubscriptionManager using TestContainers.
 * 
 * <p>These tests are fast, focused, and isolated - testing individual methods
 * of SubscriptionManager with a real database. Each test validates a single
 * responsibility and runs in <1 second.</p>
 * 
 * <p>Tagged as CORE because they are:
 * <ul>
 *   <li>Fast: Each test completes in <1 second</li>
 *   <li>Isolated: Each test focuses on a single method/behavior</li>
 *   <li>Focused: Tests one component (SubscriptionManager) not integration scenarios</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class SubscriptionManagerCoreTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerCoreTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
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
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("SubscriptionManagerCoreTest setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testSubscribeWithDefaultOptions(VertxTestContext testContext) {
        String topic = "test-topic-default";
        String groupName = "test-group-1";
        
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        
        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(topic, subscription.topic());
                assertEquals(groupName, subscription.groupName());
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                assertNotNull(subscription.subscribedAt());
                assertNotNull(subscription.lastHeartbeatAt());
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeWithFromBeginning(VertxTestContext testContext) {
        String topic = "test-topic-beginning";
        String groupName = "test-group-2";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(1L, subscription.startFromMessageId());
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeWithFromNow(VertxTestContext testContext) {
        String topic = "test-topic-now";
        String groupName = "test-group-3";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertNotNull(subscription.startFromMessageId());
                assertTrue(subscription.startFromMessageId() >= 1);
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeWithFromMessageId(VertxTestContext testContext) {
        String topic = "test-topic-msgid";
        String groupName = "test-group-4";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(100L)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(100L, subscription.startFromMessageId());
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeWithFromTimestamp(VertxTestContext testContext) {
        String topic = "test-topic-timestamp";
        String groupName = "test-group-5";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        Instant startTime = Instant.now().minusSeconds(3600); // 1 hour ago
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(startTime)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertNotNull(subscription.startFromTimestamp());
                // PostgreSQL stores timestamps with microsecond precision, so truncate to micros for comparison
                Instant expectedTruncated = startTime.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                Instant actualTruncated = subscription.startFromTimestamp().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                assertEquals(expectedTruncated, actualTruncated);
                testContext.completeNow();
            })));
    }

    @Test
    void testPauseSubscription(VertxTestContext testContext) {
        String topic = "test-topic-pause";
        String groupName = "test-group-6";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(SubscriptionState.PAUSED, subscription.state());
                testContext.completeNow();
            })));
    }

    @Test
    void testResumeSubscription(VertxTestContext testContext) {
        String topic = "test-topic-resume";
        String groupName = "test-group-7";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.resume(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                testContext.completeNow();
            })));
    }

    @Test
    void testCancelSubscription(VertxTestContext testContext) {
        String topic = "test-topic-cancel";
        String groupName = "test-group-8";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(SubscriptionState.CANCELLED, subscription.state());
                testContext.completeNow();
            })));
    }

    @Test
    void testResumeCancelledSubscriptionFails(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Cannot resume cancelled subscription') is EXPECTED this test deliberately attempts to resume a cancelled subscription to verify rejection");
        String topic = "test-topic-cancel-resume";
        String groupName = "test-group-cancel-resume";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.resume(topic, groupName)
                .<SubscriptionInfo>transform(ar -> {
                    if (ar.succeeded()) {
                        return Future.failedFuture(new AssertionError("Expected resume of cancelled subscription to fail"));
                    }
                    return subscriptionManager.getSubscription(topic, groupName);
                }))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription);
                assertEquals(SubscriptionState.CANCELLED, subscription.state());
                testContext.completeNow();
            })));
    }

    @Test
    void testUpdateHeartbeat(VertxTestContext testContext) {
        String topic = "test-topic-heartbeat-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "test-group-9";

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        AtomicReference<Instant> initialHeartbeatRef = new AtomicReference<>();
        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(before -> {
                initialHeartbeatRef.set(before.lastHeartbeatAt());
                return manager.getVertx().timer(100);
            })
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertNotNull(after.lastHeartbeatAt());
                assertTrue(after.lastHeartbeatAt().isAfter(initialHeartbeatRef.get()),
                    "Heartbeat should be updated to a later time");
                testContext.completeNow();
            })));
    }

    @Test
    void testListSubscriptionsForTopic(VertxTestContext testContext) {
        String topic = "test-topic-list-" + UUID.randomUUID().toString().substring(0, 8);

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, "group-1"))
            .compose(v -> subscriptionManager.subscribe(topic, "group-2"))
            .compose(v -> subscriptionManager.subscribe(topic, "group-3"))
            .compose(v -> subscriptionManager.listSubscriptions(topic))
            .onComplete(testContext.succeeding(subscriptions -> testContext.verify(() -> {
                assertNotNull(subscriptions);
                assertEquals(3, subscriptions.size());
                List<String> groupNames = subscriptions.stream()
                    .map(SubscriptionInfo::groupName)
                    .sorted()
                    .toList();
                assertEquals(List.of("group-1", "group-2", "group-3"), groupNames);
                testContext.completeNow();
            })));
    }

    @Test
    void testGetNonExistentSubscription(VertxTestContext testContext) {
        subscriptionManager.getSubscription("non-existent-topic", "non-existent-group")
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNull(subscription, "Should return null for non-existent subscription");
                testContext.completeNow();
            })));
    }

    @Test
    void testSubscribeRequiresNonNullTopic(VertxTestContext testContext) {
        assertThrows(NullPointerException.class, () -> subscriptionManager.subscribe(null, "group-name"));
        testContext.completeNow();
    }

    @Test
    void testSubscribeRequiresNonNullGroupName(VertxTestContext testContext) {
        assertThrows(NullPointerException.class, () -> subscriptionManager.subscribe("topic", null));
        testContext.completeNow();
    }

    @Test
    void testSubscribeRequiresNonNullOptions(VertxTestContext testContext) {
        assertThrows(NullPointerException.class, () -> subscriptionManager.subscribe("topic", "group", null));
        testContext.completeNow();
    }
}

