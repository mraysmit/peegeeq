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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link SubscriptionManager#stopGroup(String, String)}.
 *
 * <p>A graceful stop of a consumer group stores the status STOPPED. An ACTIVE subscription
 * becomes STOPPED. A subscription in any other status is left unchanged. The start position
 * is kept. A later subscribe makes a STOPPED subscription ACTIVE again.</p>
 */
@Tag(TestCategories.INTEGRATION)
public class SubscriptionStopGroupIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionStopGroupIntegrationTest.class);

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

        logger.info("SubscriptionStopGroupIntegrationTest setup complete");
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

    @Test
    void stopGroupMovesActiveSubscriptionToStoppedAndKeepsStartPosition(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-active-" + uniqueId;
        String groupName = "group-active-" + uniqueId;
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(42L)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, options))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription, "The subscription row must remain after a stop");
                assertEquals(SubscriptionState.STOPPED, subscription.state(),
                    "stopGroup must store STOPPED for an ACTIVE subscription");
                assertEquals(42L, subscription.startFromMessageId(),
                    "stopGroup must keep the stored start position");
                testContext.completeNow();
            })));
    }

    @Test
    void subscribeMakesStoppedSubscriptionActive(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-restart-" + uniqueId;
        String groupName = "group-restart-" + uniqueId;

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .compose(stopped -> {
                assertEquals(SubscriptionState.STOPPED, stopped.state(),
                    "Pre-condition: the subscription must be STOPPED before the restart");
                return subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX);
            })
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.ACTIVE, subscription.state(),
                    "subscribe must make a STOPPED subscription ACTIVE");
                testContext.completeNow();
            })));
    }

    @Test
    void stopGroupIsRepeatableOnStoppedSubscription(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-twice-" + uniqueId;
        String groupName = "group-twice-" + uniqueId;

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.STOPPED, subscription.state(),
                    "A second stopGroup must leave the subscription STOPPED");
                testContext.completeNow();
            })));
    }

    @Test
    void stopGroupLeavesPausedSubscriptionPaused(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-paused-" + uniqueId;
        String groupName = "group-paused-" + uniqueId;

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.pause(topic, groupName))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.PAUSED, subscription.state(),
                    "stopGroup must not end an operator's pause");
                testContext.completeNow();
            })));
    }

    @Test
    void stopGroupLeavesCancelledSubscriptionCancelled(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-cancelled-" + uniqueId;
        String groupName = "group-cancelled-" + uniqueId;

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> subscriptionManager.cancel(topic, groupName))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, subscription.state(),
                    "stopGroup must leave a CANCELLED subscription CANCELLED");
                testContext.completeNow();
            })));
    }

    @Test
    void stopGroupLeavesDeadSubscriptionDead(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-dead-" + uniqueId;
        String groupName = "group-dead-" + uniqueId;

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX))
            .compose(v -> markDead(topic, groupName))
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .compose(v -> subscriptionManager.getSubscription(topic, groupName))
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertEquals(SubscriptionState.DEAD, subscription.state(),
                    "stopGroup must leave a DEAD subscription DEAD");
                testContext.completeNow();
            })));
    }

    @Test
    void stopGroupFailsWhenSubscriptionDoesNotExist(VertxTestContext testContext) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "stop-group-missing-" + uniqueId;
        String groupName = "group-missing-" + uniqueId;

        createTopic(topic)
            .compose(v -> subscriptionManager.stopGroup(topic, groupName))
            .onSuccess(v -> testContext.failNow("stopGroup must fail when the group has no subscription"))
            .onFailure(err -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err);
                assertTrue(err.getMessage().contains("Subscription not found"),
                    "The failure must name the missing subscription; got: " + err.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    void stopGroupReturnsDatabaseFailure(VertxTestContext testContext) {
        // "no-such-pool" has no pool, so the database call fails before any SQL runs.
        SubscriptionManager managerWithoutPool = new SubscriptionManager(connectionManager, "no-such-pool");

        managerWithoutPool.stopGroup("stop-group-no-pool", "group-no-pool")
            .onSuccess(v -> testContext.failNow("stopGroup must return the database failure"))
            .onFailure(err -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err);
                assertTrue(err.getMessage().contains("No reactive pool found for service"),
                    "The database failure must be returned unchanged; got: " + err.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    void stopGroupRequiresNonNullTopic(VertxTestContext testContext) {
        assertThrows(NullPointerException.class, () -> subscriptionManager.stopGroup(null, "group-name"));
        testContext.completeNow();
    }

    @Test
    void stopGroupRequiresNonNullGroupName(VertxTestContext testContext) {
        assertThrows(NullPointerException.class, () -> subscriptionManager.stopGroup("topic", null));
        testContext.completeNow();
    }

    private Future<Void> createTopic(String topic) {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        return topicConfigService.createTopic(topicConfig);
    }

    private Future<Void> markDead(String topic, String groupName) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET subscription_status = 'DEAD'
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withTransaction("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .mapEmpty()
        );
    }
}
