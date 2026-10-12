package dev.mars.peegeeq.db.subscription;

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

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStateException;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the subscription lifecycle transitions of {@link SubscriptionManager}.
 *
 * <p>The five statuses are ACTIVE, STOPPED, PAUSED, DEAD, and CANCELLED. Each test puts a
 * subscription in one status, makes one call, and checks the status after the call. A
 * refused call fails with {@link SubscriptionStateException}, names the refusing status,
 * and leaves the row unchanged. A subscribe on an existing subscription keeps the stored
 * start bound.</p>
 */
@Tag(TestCategories.INTEGRATION)
public class SubscriptionLifecycleTransitionIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionLifecycleTransitionIntegrationTest.class);

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
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
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
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ------------------------------------------------------------------ pause

    @Test
    void pauseMakesStoppedSubscriptionPaused(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-pause-stopped");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, subscription.state(),
                    "pause must make a STOPPED subscription PAUSED");
                testContext.completeNow();
            })));
    }

    @Test
    void pauseFailsOnDeadSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-pause-dead");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> forceStatus(topic, groupName, "DEAD"))
            .compose(v -> expectRefused(subscriptionManager.pause(topic, groupName), SubscriptionState.DEAD, "pause"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.DEAD, subscription.state(),
                    "A refused pause must leave the subscription DEAD");
                testContext.completeNow();
            })));
    }

    @Test
    void pauseFailsOnCancelledSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-pause-cancelled");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> expectRefused(subscriptionManager.pause(topic, groupName), SubscriptionState.CANCELLED, "pause"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, subscription.state(),
                    "A refused pause must leave the subscription CANCELLED");
                testContext.completeNow();
            })));
    }

    @Test
    void pauseFailsWhenSubscriptionDoesNotExist(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-pause-missing");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> expectNotFound(subscriptionManager.pause(topic, groupName), "pause"))
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // ------------------------------------------------------------------ resume

    @Test
    void resumeFailsOnStoppedSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-resume-stopped");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> expectRefused(subscriptionManager.resume(topic, groupName), SubscriptionState.STOPPED, "resume"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.STOPPED, subscription.state(),
                    "A refused resume must leave the subscription STOPPED");
                testContext.completeNow();
            })));
    }

    @Test
    void resumeMakesDeadSubscriptionActive(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-resume-dead");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> forceStatus(topic, groupName, "DEAD"))
            .compose(v -> subscriptionManager.resume(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state(),
                    "resume is an explicit decision and must make a DEAD subscription ACTIVE");
                testContext.completeNow();
            })));
    }

    @Test
    void resumeFailsOnCancelledSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-resume-cancelled");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> expectRefused(subscriptionManager.resume(topic, groupName), SubscriptionState.CANCELLED, "resume"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, subscription.state(),
                    "A refused resume must leave the subscription CANCELLED");
                testContext.completeNow();
            })));
    }

    @Test
    void resumeLeavesActiveSubscriptionActive(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-resume-active");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.resume(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                testContext.completeNow();
            })));
    }

    @Test
    void resumeFailsWhenSubscriptionDoesNotExist(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-resume-missing");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> expectNotFound(subscriptionManager.resume(topic, groupName), "resume"))
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // ------------------------------------------------------------------ heartbeat

    @Test
    void heartbeatFailsOnDeadSubscriptionAndLeavesItDead(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-heartbeat-dead");
        String groupName = uniqueName("group");
        AtomicReference<Instant> heartbeatBefore = new AtomicReference<>();

        subscribed(topic, groupName)
            .compose(v -> forceStatus(topic, groupName, "DEAD"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(dead -> {
                heartbeatBefore.set(dead.lastHeartbeatAt());
                return expectRefused(subscriptionManager.updateHeartbeat(topic, groupName),
                    SubscriptionState.DEAD, "heartbeat");
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.DEAD, subscription.state(),
                    "A heartbeat must not make a DEAD subscription ACTIVE");
                assertEquals(heartbeatBefore.get(), subscription.lastHeartbeatAt(),
                    "A refused heartbeat must leave the heartbeat time unchanged");
                testContext.completeNow();
            })));
    }

    @Test
    void heartbeatFailsOnStoppedSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-heartbeat-stopped");
        String groupName = uniqueName("group");
        AtomicReference<Instant> heartbeatBefore = new AtomicReference<>();

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(stopped -> {
                heartbeatBefore.set(stopped.lastHeartbeatAt());
                return expectRefused(subscriptionManager.updateHeartbeat(topic, groupName),
                    SubscriptionState.STOPPED, "heartbeat");
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.STOPPED, subscription.state(),
                    "A refused heartbeat must leave the subscription STOPPED");
                assertEquals(heartbeatBefore.get(), subscription.lastHeartbeatAt(),
                    "A refused heartbeat must leave the heartbeat time unchanged");
                testContext.completeNow();
            })));
    }

    @Test
    void heartbeatFailsOnCancelledSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-heartbeat-cancelled");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> expectRefused(subscriptionManager.updateHeartbeat(topic, groupName),
                SubscriptionState.CANCELLED, "heartbeat"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, subscription.state(),
                    "A refused heartbeat must leave the subscription CANCELLED");
                testContext.completeNow();
            })));
    }

    @Test
    void heartbeatOnPausedSubscriptionSetsTimeAndKeepsItPaused(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-heartbeat-paused");
        String groupName = uniqueName("group");
        Instant oldHeartbeat = Instant.now().minus(1, ChronoUnit.HOURS);

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> forceHeartbeatTime(topic, groupName, oldHeartbeat))
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, subscription.state(),
                    "A heartbeat must leave a PAUSED subscription PAUSED");
                assertTrue(subscription.lastHeartbeatAt().isAfter(oldHeartbeat.plus(30, ChronoUnit.MINUTES)),
                    "A heartbeat on a PAUSED subscription must set the heartbeat time; got "
                        + subscription.lastHeartbeatAt());
                testContext.completeNow();
            })));
    }

    @Test
    void heartbeatOnActiveSubscriptionSetsTimeAndClearsMisses(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-heartbeat-active");
        String groupName = uniqueName("group");
        Instant oldHeartbeat = Instant.now().minus(1, ChronoUnit.HOURS);

        subscribed(topic, groupName)
            .compose(v -> forceHeartbeatTime(topic, groupName, oldHeartbeat))
            .compose(v -> forceMisses(topic, groupName, 2))
            .compose(v -> subscriptionManager.updateHeartbeat(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(subscription -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                assertTrue(subscription.lastHeartbeatAt().isAfter(oldHeartbeat.plus(30, ChronoUnit.MINUTES)),
                    "A heartbeat on an ACTIVE subscription must set the heartbeat time; got "
                        + subscription.lastHeartbeatAt());
                return readMisses(topic, groupName);
            })
            .onComplete(testContext.succeeding(misses -> testContext.verify(() -> {
                assertEquals(0, misses, "A heartbeat on an ACTIVE subscription must clear the miss count");
                testContext.completeNow();
            })));
    }

    @Test
    void heartbeatFailsWhenSubscriptionDoesNotExist(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-heartbeat-missing");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> expectNotFound(subscriptionManager.updateHeartbeat(topic, groupName), "heartbeat"))
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // ------------------------------------------------------------------ subscribe

    @Test
    void subscribeFailsOnCancelledSubscriptionAndKeepsItsBound(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-subscribe-cancelled");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(42L)))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> expectRefused(
                subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning()),
                SubscriptionState.CANCELLED, "subscribe"))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, subscription.state(),
                    "CANCELLED is terminal: a subscribe must leave it CANCELLED");
                assertEquals(42L, subscription.startFromMessageId(),
                    "A refused subscribe must leave the stored start bound unchanged");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeLeavesPausedSubscriptionPaused(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-subscribe-paused");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, subscription.state(),
                    "Only resume ends a pause: a subscribe must leave the subscription PAUSED");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeMakesDeadSubscriptionActiveAndClearsMisses(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-subscribe-dead");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> forceStatus(topic, groupName, "DEAD"))
            .compose(v -> forceMisses(topic, groupName, 3))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(subscription -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state(),
                    "A subscribe is an explicit start and must make a DEAD subscription ACTIVE");
                return readMisses(topic, groupName);
            })
            .onComplete(testContext.succeeding(misses -> testContext.verify(() -> {
                assertEquals(0, misses, "A subscribe must clear the miss count");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeKeepsStoredMessageIdBoundOnActiveSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-bound-active");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(42L)))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning()))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromTimestamp(Instant.parse("2026-01-01T00:00:00Z"))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                assertEquals(42L, subscription.startFromMessageId(),
                    "A later subscribe must keep the stored message id bound");
                assertNull(subscription.startFromTimestamp(),
                    "A later subscribe must not add a timestamp bound");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeKeepsStoredBoundWhenItMakesStoppedSubscriptionActive(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-bound-stopped");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(42L)))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning()))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state());
                assertEquals(42L, subscription.startFromMessageId(),
                    "A restart with other options must keep the stored start bound");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeKeepsStoredTimestampBound(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-bound-timestamp");
        String groupName = uniqueName("group");
        Instant storedTimestamp = Instant.parse("2026-01-01T00:00:00Z");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromTimestamp(storedTimestamp)))
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, fromMessageId(7L)))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(storedTimestamp, subscription.startFromTimestamp(),
                    "A later subscribe must keep the stored timestamp bound");
                assertNull(subscription.startFromMessageId(),
                    "A later subscribe must not add a message id bound");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeStoresHeartbeatSettingsOfOptionsOnExistingSubscription(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-subscribe-heartbeat");
        String groupName = uniqueName("group");
        SubscriptionOptions secondOptions = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(15)
            .heartbeatTimeoutSeconds(45)
            .build();

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, secondOptions))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(15, subscription.heartbeatIntervalSeconds());
                assertEquals(45, subscription.heartbeatTimeoutSeconds());
                testContext.completeNow();
            })));
    }

    @Test
    void operatorPauseSurvivesGroupStopAndStart(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-pause-survives");
        String groupName = uniqueName("group");

        subscribed(topic, groupName)
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(afterStop -> {
                assertEquals(SubscriptionState.PAUSED, afterStop.state(),
                    "A graceful stop must leave an operator's pause in place");
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(afterStart -> {
                assertEquals(SubscriptionState.PAUSED, afterStart.state(),
                    "A start must leave an operator's pause in place");
                return subscriptionManager.resume(topic, groupName);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state(),
                    "resume must end the pause");
                testContext.completeNow();
            })));
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelMakesStoppedAndDeadSubscriptionsCancelled(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-cancel");
        String stoppedGroup = uniqueName("group-stopped");
        String deadGroup = uniqueName("group-dead");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, stoppedGroup, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.subscribe(topic, deadGroup, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.stopGroup(topic, stoppedGroup))
            .compose(v -> forceStatus(topic, deadGroup, "DEAD"))
            .compose(v -> subscriptionManager.cancel(topic, stoppedGroup))
            .compose(v -> subscriptionManager.cancel(topic, deadGroup))
            .compose(v -> subscriptionManager.getSubscription(topic, stoppedGroup))
            .compose(stopped -> {
                assertEquals(SubscriptionState.CANCELLED, stopped.state(),
                    "cancel must make a STOPPED subscription CANCELLED");
                return subscriptionManager.getSubscription(topic, deadGroup);
            })
            .onComplete(testContext.succeeding(dead -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, dead.state(),
                    "cancel must make a DEAD subscription CANCELLED");
                testContext.completeNow();
            })));
    }

    @Test
    void cancelFailsWhenSubscriptionDoesNotExist(VertxTestContext testContext) {
        String topic = uniqueName("lifecycle-cancel-missing");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> expectNotFound(subscriptionManager.cancel(topic, groupName), "cancel"))
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // ------------------------------------------------------------------ database failure

    @Test
    void lifecycleCallsReturnDatabaseFailure(VertxTestContext testContext) {
        // "no-such-pool" has no pool, so each database call fails before any SQL runs.
        SubscriptionManager managerWithoutPool = new SubscriptionManager(connectionManager, "no-such-pool");

        expectNoPool(managerWithoutPool.subscribe("no-pool-topic", "no-pool-group", SubscriptionStore.OUTBOX), "subscribe")
            .compose(v -> expectNoPool(managerWithoutPool.pause("no-pool-topic", "no-pool-group"), "pause"))
            .compose(v -> expectNoPool(managerWithoutPool.resume("no-pool-topic", "no-pool-group"), "resume"))
            .compose(v -> expectNoPool(managerWithoutPool.updateHeartbeat("no-pool-topic", "no-pool-group"), "heartbeat"))
            .compose(v -> expectNoPool(managerWithoutPool.cancel("no-pool-topic", "no-pool-group"), "cancel"))
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // ------------------------------------------------------------------ helpers

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static SubscriptionOptions fromMessageId(long messageId) {
        return SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(messageId)
            .build();
    }

    private static SubscriptionOptions fromTimestamp(Instant timestamp) {
        return SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(timestamp)
            .build();
    }

    private Future<Void> createTopic(String topic) {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        return topicConfigService.createTopic(topicConfig);
    }

    private Future<Void> subscribed(String topic, String groupName) {
        return createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX));
    }

    /**
     * Succeeds only when the call failed with a {@link SubscriptionStateException} that
     * carries the expected status and names it in its message.
     */
    private Future<Void> expectRefused(Future<Void> call, SubscriptionState expectedState, String callName) {
        // The call is already sent. Its WARN is logged when the database answers, after this line.
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log (a refused {} on a {} subscription) is EXPECTED: "
            + "this test makes a lifecycle call that the current status refuses", callName, expectedState);
        return call.transform(result -> {
            if (result.succeeded()) {
                return Future.failedFuture(new AssertionError(
                    callName + " must fail on a " + expectedState + " subscription"));
            }
            if (result.cause() instanceof SubscriptionStateException stateException
                    && stateException.getCurrentState() == expectedState
                    && stateException.getMessage().contains(expectedState.name())) {
                return Future.<Void>succeededFuture();
            }
            return Future.failedFuture(new AssertionError(
                callName + " on a " + expectedState + " subscription must fail with a SubscriptionStateException"
                    + " that names the status, but failed with: " + result.cause(), result.cause()));
        });
    }

    private Future<Void> expectNotFound(Future<Void> call, String callName) {
        return call.transform(result -> {
            if (result.succeeded()) {
                return Future.failedFuture(new AssertionError(
                    callName + " must fail when the group has no subscription"));
            }
            if (result.cause() instanceof IllegalStateException
                    && !(result.cause() instanceof SubscriptionStateException)
                    && result.cause().getMessage().contains("Subscription not found")) {
                return Future.<Void>succeededFuture();
            }
            return Future.failedFuture(new AssertionError(
                callName + " with no subscription must fail with 'Subscription not found', but failed with: "
                    + result.cause(), result.cause()));
        });
    }

    private Future<Void> expectNoPool(Future<Void> call, String callName) {
        return call.transform(result -> {
            if (result.succeeded()) {
                return Future.failedFuture(new AssertionError(
                    callName + " must return the database failure"));
            }
            if (result.cause() instanceof IllegalStateException
                    && result.cause().getMessage().contains("No reactive pool found for service")) {
                return Future.<Void>succeededFuture();
            }
            return Future.failedFuture(new AssertionError(
                callName + " must return the database failure unchanged, but failed with: "
                    + result.cause(), result.cause()));
        });
    }

    private Future<Void> forceStatus(String topic, String groupName, String status) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET subscription_status = $3
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withTransaction("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, status))
                .mapEmpty()
        );
    }

    private Future<Void> forceMisses(String topic, String groupName, int misses) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET consecutive_misses = $3
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withTransaction("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, misses))
                .mapEmpty()
        );
    }

    private Future<Void> forceHeartbeatTime(String topic, String groupName, Instant heartbeatTime) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET last_heartbeat_at = $3
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withTransaction("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, heartbeatTime.atOffset(java.time.ZoneOffset.UTC)))
                .mapEmpty()
        );
    }

    private Future<Integer> readMisses(String topic, String groupName) {
        String sql = """
            SELECT consecutive_misses
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> rows.iterator().next().getInteger("consecutive_misses"))
        );
    }
}
