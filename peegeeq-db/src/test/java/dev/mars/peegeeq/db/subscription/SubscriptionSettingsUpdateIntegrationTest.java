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
import dev.mars.peegeeq.api.subscription.SubscriptionBoundConflictException;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionSettingsUpdate;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for
 * {@link SubscriptionManager#updateSubscriptionSettings(String, String, SubscriptionSettingsUpdate)}.
 *
 * <p>The update changes only the heartbeat settings that are supplied. It never changes the
 * lifecycle status and never moves the stored start bound. A named start position is
 * accepted only when it is the stored bound.</p>
 */
@Tag(TestCategories.INTEGRATION)
public class SubscriptionSettingsUpdateIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionSettingsUpdateIntegrationTest.class);

    private static final Instant STORED_TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");
    private static final int STORED_INTERVAL = 45;
    private static final int STORED_TIMEOUT = 200;

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

    // ------------------------------------------------------------------ heartbeat settings

    @Test
    void updatesOnlyTheSuppliedHeartbeatInterval(VertxTestContext testContext) {
        String topic = uniqueName("settings-interval");
        String groupName = uniqueName("group");
        AtomicReference<SubscriptionInfo> before = new AtomicReference<>();

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(stored -> {
                before.set(stored);
                return subscriptionManager.updateSubscriptionSettings(topic, groupName,
                    new SubscriptionSettingsUpdate(30, null, null, null, null));
            })
            .compose(returned -> {
                assertEquals(30, returned.heartbeatIntervalSeconds(),
                    "The returned subscription must carry the stored interval");
                return subscriptionManager.getSubscription(topic, groupName);
            })
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(30, after.heartbeatIntervalSeconds(), "The supplied interval must be stored");
                assertEquals(STORED_TIMEOUT, after.heartbeatTimeoutSeconds(),
                    "The omitted timeout must keep its stored value and must not become the default");
                assertEquals(42L, after.startFromMessageId(), "The start bound must not change");
                assertEquals(SubscriptionState.ACTIVE, after.state(), "The status must not change");
                assertEquals(before.get().lastHeartbeatAt(), after.lastHeartbeatAt(),
                    "A settings update is not a heartbeat");
                testContext.completeNow();
            })));
    }

    @Test
    void updatesOnlyTheSuppliedHeartbeatTimeout(VertxTestContext testContext) {
        String topic = uniqueName("settings-timeout");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, 600, null, null, null)))
            .compose(returned -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(STORED_INTERVAL, after.heartbeatIntervalSeconds(),
                    "The omitted interval must keep its stored value and must not become the default");
                assertEquals(600, after.heartbeatTimeoutSeconds(), "The supplied timeout must be stored");
                testContext.completeNow();
            })));
    }

    @Test
    void updatesBothHeartbeatSettings(VertxTestContext testContext) {
        String topic = uniqueName("settings-both");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(400, 900, null, null, null)))
            .onComplete(testContext.succeeding(returned -> testContext.verify(() -> {
                assertEquals(400, returned.heartbeatIntervalSeconds());
                assertEquals(900, returned.heartbeatTimeoutSeconds());
                testContext.completeNow();
            })));
    }

    @Test
    void emptyUpdateChangesNothingAndReturnsTheStoredSubscription(VertxTestContext testContext) {
        String topic = uniqueName("settings-empty");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, null, null, null, null)))
            .onComplete(testContext.succeeding(returned -> testContext.verify(() -> {
                assertEquals(topic, returned.topic());
                assertEquals(groupName, returned.groupName());
                assertEquals(STORED_INTERVAL, returned.heartbeatIntervalSeconds());
                assertEquals(STORED_TIMEOUT, returned.heartbeatTimeoutSeconds());
                assertEquals(42L, returned.startFromMessageId());
                assertEquals(SubscriptionState.ACTIVE, returned.state());
                testContext.completeNow();
            })));
    }

    @Test
    void keepsTheLifecycleStatus(VertxTestContext testContext) {
        String topic = uniqueName("settings-status");
        List<String> statuses = List.of("PAUSED", "STOPPED", "DEAD", "CANCELLED");

        Future<Void> chain = createTopic(topic);
        for (String status : statuses) {
            String groupName = "group-" + status.toLowerCase();
            chain = chain
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
                .compose(v -> forceStatus(topic, groupName, status))
                .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                    new SubscriptionSettingsUpdate(30, 120, null, null, null)))
                .compose(returned -> {
                    assertEquals(SubscriptionState.valueOf(status), returned.state(),
                        "A settings update must leave a " + status + " subscription " + status);
                    assertEquals(30, returned.heartbeatIntervalSeconds());
                    assertEquals(120, returned.heartbeatTimeoutSeconds());
                    return Future.<Void>succeededFuture();
                });
        }

        chain.onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // ------------------------------------------------------------------ named start bound

    @Test
    void acceptsTheStoredMessageIdBound(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-equal-id");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(30, null, StartPosition.FROM_MESSAGE_ID, 42L, null)))
            .onComplete(testContext.succeeding(returned -> testContext.verify(() -> {
                assertEquals(42L, returned.startFromMessageId());
                assertEquals(30, returned.heartbeatIntervalSeconds(),
                    "The heartbeat setting must be stored when the named bound is the stored bound");
                testContext.completeNow();
            })));
    }

    @Test
    void acceptsFromBeginningWhenTheStoredBoundIsOne(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-beginning");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.fromBeginning()))
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_BEGINNING, null, null)))
            .onComplete(testContext.succeeding(returned -> testContext.verify(() -> {
                assertEquals(1L, returned.startFromMessageId());
                testContext.completeNow();
            })));
    }

    @Test
    void acceptsTheStoredTimestampBound(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-equal-timestamp");
        String groupName = uniqueName("group");

        subscribedFromTimestamp(topic, groupName)
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_TIMESTAMP, null, STORED_TIMESTAMP)))
            .onComplete(testContext.succeeding(returned -> testContext.verify(() -> {
                assertEquals(STORED_TIMESTAMP, returned.startFromTimestamp());
                assertNull(returned.startFromMessageId());
                testContext.completeNow();
            })));
    }

    @Test
    void rejectsADifferentMessageIdBoundAndChangesNothing(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-other-id");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> expectBoundConflict(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(30, 120, StartPosition.FROM_MESSAGE_ID, 43L, null))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(42L, after.startFromMessageId(), "A refused update must keep the stored bound");
                assertEquals(STORED_INTERVAL, after.heartbeatIntervalSeconds(),
                    "A refused update must change no heartbeat setting");
                assertEquals(STORED_TIMEOUT, after.heartbeatTimeoutSeconds(),
                    "A refused update must change no heartbeat setting");
                testContext.completeNow();
            })));
    }

    @Test
    void rejectsFromBeginningWhenTheStoredBoundIsNotOne(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-not-beginning");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> expectBoundConflict(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_BEGINNING, null, null))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(42L, after.startFromMessageId());
                testContext.completeNow();
            })));
    }

    @Test
    void rejectsATimestampBoundWhenTheStoredBoundIsAMessageId(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-kind");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> expectBoundConflict(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_TIMESTAMP, null, STORED_TIMESTAMP))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(42L, after.startFromMessageId());
                assertNull(after.startFromTimestamp());
                testContext.completeNow();
            })));
    }

    @Test
    void rejectsADifferentTimestampBound(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-other-timestamp");
        String groupName = uniqueName("group");

        subscribedFromTimestamp(topic, groupName)
            .compose(v -> expectBoundConflict(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_TIMESTAMP, null,
                    STORED_TIMESTAMP.plusSeconds(1)))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(STORED_TIMESTAMP, after.startFromTimestamp());
                testContext.completeNow();
            })));
    }

    @Test
    void rejectsFromNowOnAnExistingSubscription(VertxTestContext testContext) {
        String topic = uniqueName("settings-bound-from-now");
        String groupName = uniqueName("group");

        // The subscription is created with FROM_NOW. FROM_NOW still does not name its stored bound.
        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
            .compose(v -> expectBoundConflict(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(30, null, StartPosition.FROM_NOW, null, null))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(60, after.heartbeatIntervalSeconds(), "A refused update must change no heartbeat setting");
                testContext.completeNow();
            })));
    }

    // ------------------------------------------------------------------ heartbeat validation against stored values

    @Test
    void rejectsATimeoutNotGreaterThanTheStoredInterval(VertxTestContext testContext) {
        String topic = uniqueName("settings-timeout-low");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> expectInvalidHeartbeat(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(null, STORED_INTERVAL, null, null, null))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(STORED_TIMEOUT, after.heartbeatTimeoutSeconds(),
                    "A refused update must change no heartbeat setting");
                testContext.completeNow();
            })));
    }

    @Test
    void rejectsAnIntervalNotLessThanTheStoredTimeout(VertxTestContext testContext) {
        String topic = uniqueName("settings-interval-high");
        String groupName = uniqueName("group");

        subscribedFromMessageId(topic, groupName, 42L)
            .compose(v -> expectInvalidHeartbeat(subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(STORED_TIMEOUT, null, null, null, null))))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(after -> testContext.verify(() -> {
                assertEquals(STORED_INTERVAL, after.heartbeatIntervalSeconds(),
                    "A refused update must change no heartbeat setting");
                testContext.completeNow();
            })));
    }

    // ------------------------------------------------------------------ failure modes

    @Test
    void failsWhenTheSubscriptionDoesNotExist(VertxTestContext testContext) {
        String topic = uniqueName("settings-missing");
        String groupName = uniqueName("group");

        createTopic(topic)
            .compose(v -> subscriptionManager.updateSubscriptionSettings(topic, groupName,
                new SubscriptionSettingsUpdate(30, null, null, null, null)))
            .onSuccess(v -> testContext.failNow("A settings update must fail when the group has no subscription"))
            .onFailure(err -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err);
                assertTrue(err.getMessage().contains("Subscription not found"),
                    "The failure must name the missing subscription; got: " + err.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    void returnsTheDatabaseFailure(VertxTestContext testContext) {
        // "no-such-pool" has no pool, so the database call fails before any SQL runs.
        SubscriptionManager managerWithoutPool = new SubscriptionManager(connectionManager, "no-such-pool");

        managerWithoutPool.updateSubscriptionSettings("settings-no-pool", "group-no-pool",
                new SubscriptionSettingsUpdate(30, null, null, null, null))
            .onSuccess(v -> testContext.failNow("A settings update must return the database failure"))
            .onFailure(err -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err);
                assertTrue(err.getMessage().contains("No reactive pool found for service"),
                    "The database failure must be returned unchanged; got: " + err.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    void requiresNonNullArguments(VertxTestContext testContext) {
        SubscriptionSettingsUpdate update = new SubscriptionSettingsUpdate(30, null, null, null, null);

        assertThrows(NullPointerException.class,
            () -> subscriptionManager.updateSubscriptionSettings(null, "group-name", update));
        assertThrows(NullPointerException.class,
            () -> subscriptionManager.updateSubscriptionSettings("topic", null, update));
        assertThrows(NullPointerException.class,
            () -> subscriptionManager.updateSubscriptionSettings("topic", "group-name", null));
        testContext.completeNow();
    }

    // ------------------------------------------------------------------ helpers

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Future<Void> createTopic(String topic) {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        return topicConfigService.createTopic(topicConfig);
    }

    /**
     * Creates a subscription with a message id bound and heartbeat settings of 45 s and 200 s.
     * The settings differ from the defaults (60 s, 300 s), so a test can tell a kept value from
     * a default.
     */
    private Future<Void> subscribedFromMessageId(String topic, String groupName, long messageId) {
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(messageId)
            .heartbeatIntervalSeconds(STORED_INTERVAL)
            .heartbeatTimeoutSeconds(STORED_TIMEOUT)
            .build();
        return createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, options));
    }

    private Future<Void> subscribedFromTimestamp(String topic, String groupName) {
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(STORED_TIMESTAMP)
            .build();
        return createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, options));
    }

    /** Succeeds only when the update failed with a {@link SubscriptionBoundConflictException}. */
    private Future<Void> expectBoundConflict(Future<SubscriptionInfo> update) {
        // The call is already sent. Its WARN is logged when the database answers, after this line.
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Subscription settings update refused') is EXPECTED: "
            + "this test names a start bound that is not the stored bound");
        return update.transform(result -> {
            if (result.succeeded()) {
                return Future.failedFuture(new AssertionError(
                    "A settings update that names another start bound must fail"));
            }
            if (result.cause() instanceof SubscriptionBoundConflictException) {
                return Future.<Void>succeededFuture();
            }
            return Future.failedFuture(new AssertionError(
                "The update must fail with a SubscriptionBoundConflictException, but failed with: "
                    + result.cause(), result.cause()));
        });
    }

    /** Succeeds only when the update failed because the timeout would not exceed the interval. */
    private Future<Void> expectInvalidHeartbeat(Future<SubscriptionInfo> update) {
        // The call is already sent. Its WARN is logged when the database answers, after this line.
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Subscription settings update refused') is EXPECTED: "
            + "this test supplies a heartbeat timeout that does not exceed the heartbeat interval");
        return update.transform(result -> {
            if (result.succeeded()) {
                return Future.failedFuture(new AssertionError(
                    "A settings update that leaves the timeout at or below the interval must fail"));
            }
            if (result.cause() instanceof IllegalArgumentException
                    && result.cause().getMessage().contains(
                        "heartbeatTimeoutSeconds must be greater than heartbeatIntervalSeconds")) {
                return Future.<Void>succeededFuture();
            }
            return Future.failedFuture(new AssertionError(
                "The update must fail with an IllegalArgumentException on the heartbeat settings, but failed with: "
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
}
