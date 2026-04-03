package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
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
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests proving that flapping protection is required.
 *
 * <p>These tests MUST FAIL against the current implementation, which marks
 * subscriptions DEAD immediately on the first heartbeat timeout. Proper
 * flapping protection should require multiple consecutive missed heartbeats
 * before transitioning to DEAD.</p>
 *
 * <p>Once flapping protection is implemented, these tests should pass.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class FlappingProtectionIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FlappingProtectionIntegrationTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerDetector detector;

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
        detector = new DeadConsumerDetector(connectionManager, SERVICE_ID);

        logger.info("FlappingProtectionIntegrationTest setup complete");
    }

    /**
     * A single heartbeat timeout should NOT immediately mark a subscription DEAD.
     *
     * <p>Current behavior: detection marks DEAD on first timeout — this test
     * proves that gap by asserting the subscription should remain ACTIVE after
     * the first detection pass (consecutive_misses = 1, below threshold).</p>
     *
     * <p>Expected behavior with flapping protection: a configurable strike
     * threshold (e.g., 3 consecutive misses) must be exceeded before the
     * subscription transitions to DEAD.</p>
     */
    @Test
    void testSingleHeartbeatTimeoutDoesNotMarkDead(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-single-miss");
        String groupName = "flapping-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName)
                        .map(status -> {
                            testContext.verify(() -> {
                                // With flapping protection, a single missed heartbeat
                                // should NOT mark the subscription as DEAD.
                                // It should remain ACTIVE with an incremented miss count.
                                assertNotEquals("DEAD", status,
                                        "Single heartbeat timeout should NOT immediately mark subscription DEAD. " +
                                        "Flapping protection should require multiple consecutive misses.");
                                assertEquals("ACTIVE", status,
                                        "Subscription should remain ACTIVE after a single missed heartbeat");
                            });
                            return (Void) null;
                        }))
                .onSuccess(v -> {
                    logger.info("Flapping protection: single miss tolerance verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * A subscription that recovers (sends heartbeat) after one missed detection
     * cycle should have its consecutive miss count reset to zero.
     *
     * <p>This test proves the need for strike-count state that resets on
     * heartbeat recovery.</p>
     */
    @Test
    void testHeartbeatRecoveryResetsConsecutiveMisses(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-recovery");
        String groupName = "recovering-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // First detection pass with expired heartbeat
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                // Consumer recovers — sends a fresh heartbeat via production API
                .compose(detected -> subscriptionManager.updateHeartbeat(topic, groupName))
                // Second detection pass with expired heartbeat again
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName)
                        .compose(status -> getConsecutiveMisses(topic, groupName)
                                .map(misses -> {
                                    testContext.verify(() -> {
                                        // After recovery + one more miss, consecutive count should be 1 not 2.
                                        assertNotEquals("DEAD", status,
                                                "Consumer should not be DEAD after recovery + single miss");
                                        assertEquals("ACTIVE", status,
                                                "Subscription should remain ACTIVE after recovery resets miss count");
                                        assertEquals(1, misses,
                                                "Consecutive misses should be 1 (reset by heartbeat recovery), not 2");
                                    });
                                    return (Void) null;
                                })))
                .onSuccess(v -> {
                    logger.info("Flapping protection: heartbeat recovery resets miss count verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Only after exceeding the consecutive miss threshold should a subscription
     * be marked DEAD.
     *
     * <p>This test simulates multiple consecutive detection passes without
     * heartbeat recovery and proves that the subscription transitions to DEAD
     * only after the threshold is exceeded.</p>
     */
    @Test
    void testMarkedDeadOnlyAfterConsecutiveMissThreshold(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-threshold");
        String groupName = "threshold-consumer";
        int expectedThreshold = 3; // default consecutive miss threshold

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // Miss 1
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName).map(status -> {
                    testContext.verify(() -> assertEquals("ACTIVE", status, "Should be ACTIVE after miss 1"));
                    return (Void) null;
                }))
                // Miss 2
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName).map(status -> {
                    testContext.verify(() -> assertEquals("ACTIVE", status, "Should be ACTIVE after miss 2"));
                    return (Void) null;
                }))
                // Miss 3 — should now exceed threshold and mark DEAD
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName).map(status -> {
                    testContext.verify(() -> assertEquals("DEAD", status,
                            "Should be DEAD after " + expectedThreshold + " consecutive misses"));
                    return (Void) null;
                }))
                .onSuccess(v -> {
                    logger.info("Flapping protection: threshold-based DEAD transition verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Two consumers on the same topic: one flapping, one healthy.
     * The healthy consumer must not be affected by the other's misses.
     * Detection runs topic-wide, so isolation must be per-consumer-group.
     */
    @Test
    void testMultiConsumerIsolation(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-isolation");
        String flappingGroup = "flapping-group";
        String healthyGroup = "healthy-group";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, flappingGroup))
                .compose(v -> subscribeWithShortTimeout(topic, healthyGroup))
                // Only the flapping consumer has an expired heartbeat
                .compose(v -> setHeartbeatInPast(topic, flappingGroup, 10))
                // Healthy consumer keeps heartbeat fresh (default from subscribe is NOW())
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, healthyGroup)
                        .compose(healthyStatus -> getSubscriptionStatus(topic, flappingGroup)
                                .map(flappingStatus -> {
                                    testContext.verify(() -> {
                                        assertEquals("ACTIVE", healthyStatus,
                                                "Healthy consumer must remain ACTIVE regardless of other consumers");
                                        // Flapping consumer should still be ACTIVE with flapping protection
                                        // (single miss, below threshold). Currently fails: marked DEAD.
                                        assertNotEquals("DEAD", flappingStatus,
                                                "Flapping consumer should not be DEAD after single miss " +
                                                "(multi-consumer isolation: one miss must not be terminal)");
                                    });
                                    return (Void) null;
                                })))
                .onSuccess(v -> {
                    logger.info("Flapping protection: multi-consumer isolation verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * PAUSED subscriptions are also subject to heartbeat detection (the
     * detection SQL targets ACTIVE and PAUSED). Flapping protection must
     * apply equally — a PAUSED subscription with a single missed heartbeat
     * should remain PAUSED, not jump to DEAD.
     */
    @Test
    void testPausedSubscriptionFlappingProtection(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-paused");
        String groupName = "paused-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                .compose(v -> subscriptionManager.pause(topic, groupName))
                .compose(v -> getSubscriptionStatus(topic, groupName).map(status -> {
                    testContext.verify(() -> assertEquals("PAUSED", status, "Precondition: subscription should be PAUSED"));
                    return (Void) null;
                }))
                // Expire heartbeat while PAUSED
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName)
                        .map(status -> {
                            testContext.verify(() -> {
                                // With flapping protection, single miss should keep PAUSED (not DEAD)
                                assertNotEquals("DEAD", status,
                                        "PAUSED subscription should not be marked DEAD after a single missed heartbeat. " +
                                        "Flapping protection must apply to PAUSED subscriptions too.");
                                assertEquals("PAUSED", status,
                                        "Subscription should remain PAUSED after single miss");
                            });
                            return (Void) null;
                        }))
                .onSuccess(v -> {
                    logger.info("Flapping protection: PAUSED subscription tolerance verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Classic flapping pattern: miss → recover → miss → recover → miss.
     * Each recovery resets the consecutive miss count, so the consumer
     * should never reach the threshold despite multiple total misses.
     *
     * <p>This is the scenario the feature is named after — a consumer that
     * intermittently drops heartbeats but keeps recovering should not be
     * killed.</p>
     */
    @Test
    void testIntermittentFlappingNeverReachesThreshold(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-intermittent");
        String groupName = "intermittent-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // Flap cycle 1: miss then recover via production heartbeat API
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> subscriptionManager.updateHeartbeat(topic, groupName))
                // Flap cycle 2: miss then recover
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> subscriptionManager.updateHeartbeat(topic, groupName))
                // Flap cycle 3: miss then recover
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> subscriptionManager.updateHeartbeat(topic, groupName))
                // Flap cycle 4: one more miss (total misses = 4 > threshold, but consecutive = 1)
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName)
                        .map(status -> {
                            testContext.verify(() -> {
                                // Despite 4 total misses, consecutive count never exceeded 1
                                assertNotEquals("DEAD", status,
                                        "Intermittent flapping (miss/recover/miss/recover) should never reach " +
                                        "DEAD threshold. Total misses=4 but consecutive misses never exceeded 1.");
                                assertEquals("ACTIVE", status,
                                        "Subscription should remain ACTIVE when recoveries reset miss count");
                            });
                            return (Void) null;
                        }))
                .onSuccess(v -> {
                    logger.info("Flapping protection: intermittent flapping tolerance verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * After a subscription is marked DEAD (threshold exceeded) and then
     * resubscribes via subscribe(), the consecutive miss count must reset
     * to zero. Without this, a reactivated consumer inherits stale miss
     * state and gets killed prematurely.
     *
     * <p>subscribe() ON CONFLICT upserts DEAD → ACTIVE and resets
     * last_heartbeat_at, but currently has no concept of consecutive_misses.
     * This test proves the miss count must be reset on resubscription.</p>
     */
    @Test
    void testResubscribeAfterDeadResetsMissCount(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-resub");
        String groupName = "resub-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // Force subscription to DEAD via direct SQL (simulates threshold exceeded)
                .compose(v -> forceSubscriptionStatus(topic, groupName, "DEAD"))
                .compose(v -> getSubscriptionStatus(topic, groupName).map(status -> {
                    testContext.verify(() -> assertEquals("DEAD", status, "Precondition: subscription should be DEAD"));
                    return (Void) null;
                }))
                // Resubscribe — should reactivate and reset miss count
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // Now miss one heartbeat
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName)
                        .compose(status -> getConsecutiveMisses(topic, groupName)
                                .map(misses -> {
                                    testContext.verify(() -> {
                                        assertNotEquals("DEAD", status,
                                                "Resubscribed consumer should not be DEAD after single miss — " +
                                                "miss count must be reset on resubscription");
                                        assertEquals("ACTIVE", status,
                                                "Resubscribed consumer should remain ACTIVE");
                                        assertEquals(1, misses,
                                                "Consecutive misses should be 1, not carried from previous lifecycle");
                                    });
                                    return (Void) null;
                                })))
                .onSuccess(v -> {
                    logger.info("Flapping protection: resubscription resets miss count verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * updateHeartbeat() has built-in DEAD → ACTIVE auto-resurrection SQL.
     * When this resurrection occurs, the consecutive miss count must also
     * reset to zero. This is a different code path from resubscribe().
     */
    @Test
    void testHeartbeatAutoResurrectionResetsMissCount(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-resurrect");
        String groupName = "resurrect-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // Force DEAD
                .compose(v -> forceSubscriptionStatus(topic, groupName, "DEAD"))
                // Consumer sends heartbeat — auto-resurrects via updateHeartbeat()
                .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
                .compose(result -> getSubscriptionStatus(topic, groupName).map(status -> {
                    testContext.verify(() -> assertEquals("ACTIVE", status,
                            "Precondition: heartbeat should auto-resurrect DEAD → ACTIVE"));
                    return (Void) null;
                }))
                // Now miss one heartbeat
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> getSubscriptionStatus(topic, groupName)
                        .compose(status -> getConsecutiveMisses(topic, groupName)
                                .map(misses -> {
                                    testContext.verify(() -> {
                                        assertNotEquals("DEAD", status,
                                                "Auto-resurrected consumer should not be DEAD after single miss — " +
                                                "miss count must be reset by heartbeat resurrection");
                                        assertEquals(1, misses,
                                                "Consecutive misses should be 1, not carried from pre-resurrection state");
                                    });
                                    return (Void) null;
                                })))
                .onSuccess(v -> {
                    logger.info("Flapping protection: heartbeat auto-resurrection resets miss count verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Two groups on same topic at different miss stages. After a single
     * detection run: group A (already at miss 2) should hit threshold=3
     * and die; group B (at miss 0) should survive at miss 1.
     *
     * <p>Proves per-row independent tracking within a single atomic
     * detection pass.</p>
     */
    @Test
    void testDifferentialMissCountsInSingleDetection(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-differential");
        String groupA = "advanced-miss-group";
        String groupB = "fresh-group";
        int threshold = 3;

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupA))
                .compose(v -> subscribeWithShortTimeout(topic, groupB))
                // Advance group A to miss 2 (two detection cycles)
                .compose(v -> setHeartbeatInPast(topic, groupA, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> setHeartbeatInPast(topic, groupA, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                // Now both groups have expired heartbeats for the next run
                .compose(detected -> setHeartbeatInPast(topic, groupA, 10))
                .compose(v -> setHeartbeatInPast(topic, groupB, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                // Group A should be DEAD (miss 3 = threshold), group B ACTIVE (miss 1)
                .compose(detected -> getSubscriptionStatus(topic, groupA)
                        .compose(statusA -> getSubscriptionStatus(topic, groupB)
                                .map(statusB -> {
                                    testContext.verify(() -> {
                                        assertEquals("DEAD", statusA,
                                                "Group A should be DEAD after " + threshold + " consecutive misses");
                                        assertEquals("ACTIVE", statusB,
                                                "Group B should be ACTIVE with only 1 miss");
                                    });
                                    return (Void) null;
                                })))
                .onSuccess(v -> {
                    logger.info("Flapping protection: differential miss counts verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Miss count must be persisted in the database, not held in-memory
     * by the detector instance. Creating a new DeadConsumerDetector after
     * the first detection should see the accumulated miss count.
     *
     * <p>This is critical because the detection job could restart, and
     * a fresh detector instance must not lose previously accumulated
     * miss state.</p>
     */
    @Test
    void testMissCountPersistedAcrossDetectorInstances(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-persist");
        String groupName = "persistent-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                // Miss 1 with original detector
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(detected -> {
                    // Create a completely new detector instance
                    DeadConsumerDetector freshDetector = new DeadConsumerDetector(connectionManager, SERVICE_ID);
                    // Miss 2 with new detector
                    return setHeartbeatInPast(topic, groupName, 10)
                            .compose(v2 -> freshDetector.detectDeadSubscriptions(topic));
                })
                .compose(detected -> getConsecutiveMisses(topic, groupName)
                        .map(misses -> {
                            testContext.verify(() -> assertEquals(2, misses,
                                    "Miss count should be 2 (accumulated across detector instances), " +
                                    "proving persistence in DB not in-memory"));
                            return (Void) null;
                        }))
                .onSuccess(v -> {
                    logger.info("Flapping protection: miss count DB persistence verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * detectAllDeadSubscriptionsWithDetails() is a separate code path from
     * detectDeadSubscriptions(topic). It must enforce the same flapping
     * protection — a single missed heartbeat via the all-topics path should
     * not mark a subscription DEAD.
     */
    @Test
    void testAllTopicsDetectionRespectsFlappingProtection(VertxTestContext testContext) throws InterruptedException {
        String topic = uniqueTopic("flap-all-topics");
        String groupName = "all-topics-consumer";

        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, groupName))
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                // Use the all-topics detection path instead of per-topic
                .compose(v -> detector.detectAllDeadSubscriptionsWithDetails())
                .compose(result -> getSubscriptionStatus(topic, groupName)
                        .map(status -> {
                            testContext.verify(() -> {
                                assertNotEquals("DEAD", status,
                                        "detectAllDeadSubscriptionsWithDetails() must also respect flapping " +
                                        "protection — single miss should not mark DEAD");
                                assertEquals("ACTIVE", status,
                                        "Subscription should remain ACTIVE after single miss via all-topics path");
                            });
                            return (Void) null;
                        }))
                .onSuccess(v -> {
                    logger.info("Flapping protection: all-topics detection path verified");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Same consumer group subscribed to two different topics — miss tracking
     * must be independent per (topic, group_name) pair. A miss on topic A
     * must not contribute to the count on topic B.
     */
    @Test
    void testCrossTopicMissIsolation(VertxTestContext testContext) throws InterruptedException {
        String topicA = uniqueTopic("flap-topic-a");
        String topicB = uniqueTopic("flap-topic-b");
        String groupName = "cross-topic-consumer";

        createTopic(topicA)
                .compose(v -> createTopic(topicB))
                .compose(v -> subscribeWithShortTimeout(topicA, groupName))
                .compose(v -> subscribeWithShortTimeout(topicB, groupName))
                // Miss heartbeat on topic A only
                .compose(v -> setHeartbeatInPast(topicA, groupName, 10))
                // Topic B heartbeat stays fresh
                .compose(v -> detector.detectDeadSubscriptions(topicA))
                .compose(detected -> detector.detectDeadSubscriptions(topicB))
                .compose(detected -> getSubscriptionStatus(topicB, groupName)
                        .compose(statusB -> getSubscriptionStatus(topicA, groupName)
                                .map(statusA -> {
                                    testContext.verify(() -> {
                                        assertEquals("ACTIVE", statusB,
                                                "Topic B subscription must be unaffected by topic A misses");
                                        // Topic A: single miss should not mark DEAD with flapping protection
                                        assertNotEquals("DEAD", statusA,
                                                "Topic A: single miss should not mark DEAD (cross-topic isolation)");
                                    });
                                    return (Void) null;
                                })))
                .onSuccess(v -> {
                    logger.info("Flapping protection: cross-topic miss isolation verified");
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

    private Future<Void> createTopic(String topic) {
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .mapEmpty();
    }

    private Future<Void> subscribeWithShortTimeout(String topic, String groupName) {
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();
        return subscriptionManager.subscribe(topic, groupName, options).mapEmpty();
    }

    private Future<Void> setHeartbeatInPast(String topic, String groupName, int secondsAgo) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET last_heartbeat_at = NOW() - ($3 || ' seconds')::INTERVAL
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName, String.valueOf(secondsAgo)))
                    .mapEmpty();
        });
    }

    private Future<String> getSubscriptionStatus(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                SELECT subscription_status FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> rows.iterator().next().getString("subscription_status"));
        });
    }

    private Future<Integer> getConsecutiveMisses(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                SELECT COALESCE(consecutive_misses, 0) as consecutive_misses
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> rows.iterator().next().getInteger("consecutive_misses"));
        });
    }

    private Future<Void> forceSubscriptionStatus(String topic, String groupName, String status) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = $3
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName, status))
                    .mapEmpty();
        });
    }
}
