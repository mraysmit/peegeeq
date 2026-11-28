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

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link SubscriptionManager#forceRemoveConsumerGroup(String, String)}.
 *
 * <p>Force-remove is the operator's way to end a consumer group's subscription from any status
 * except CANCELLED. It must end the subscription and change no message: a message that no
 * consumer has processed stays PENDING. The subscription service is the one
 * {@code PeeGeeQManager} gives every caller, so the tests run with the production wiring.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Force-remove integration tests")
class ForceRemoveIntegrationTest extends BaseIntegrationTest {

    private static final String POOL = PeeGeeQDefaults.DEFAULT_POOL_ID;

    private SubscriptionService subscriptions;
    private PgConnectionManager connectionManager;
    /** A second connection manager whose schema does not exist; created only by the failure test. */
    private PgConnectionManager missingSchemaConnectionManager;

    @BeforeEach
    void setUpForceRemove() {
        subscriptions = manager.createSubscriptionService();
        connectionManager = manager.getClientFactory().getConnectionManager();
    }

    @AfterEach
    void tearDownForceRemove(VertxTestContext testContext) {
        PgConnectionManager secondary = missingSchemaConnectionManager;
        missingSchemaConnectionManager = null;
        (secondary != null ? secondary.close() : Future.<Void>succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("force-remove active subscription marks CANCELLED")
    void forceRemove_activeSubscription_marksCancelled(VertxTestContext ctx) {
        String topic = "force-rm-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-group-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptions.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.builder().build())
                .compose(v -> subscriptions.forceRemoveConsumerGroup(topic, groupName))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertNotNull(result);
                        assertEquals(topic, result.topic());
                        assertEquals(groupName, result.groupName());
                        assertEquals("ACTIVE", result.previousStatus());
                    });
                    return subscriptions.getSubscription(topic, groupName);
                })
                .onComplete(ctx.succeeding(info -> ctx.verify(() -> {
                    assertNotNull(info);
                    assertEquals(SubscriptionState.CANCELLED, info.state());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("force-remove dead subscription succeeds and marks CANCELLED")
    void forceRemove_deadSubscription_marksCancelled(VertxTestContext ctx) {
        String topic = "force-rm-dead-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-dead-g-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptions.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.builder().build())
                .compose(v -> updateStatusDirectly(topic, groupName, SubscriptionStatus.DEAD))
                .compose(v -> subscriptions.forceRemoveConsumerGroup(topic, groupName))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertNotNull(result);
                        assertEquals("DEAD", result.previousStatus());
                    });
                    return subscriptions.getSubscription(topic, groupName);
                })
                .onComplete(ctx.succeeding(info -> ctx.verify(() -> {
                    assertEquals(SubscriptionState.CANCELLED, info.state());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("force-remove of one of two groups on a QUEUE topic leaves the pending message PENDING")
    void forceRemove_oneOfTwoGroupsOnQueueTopic_leavesPendingMessagePending(VertxTestContext ctx) {
        String topic = "force-rm-msg-" + UUID.randomUUID().toString().substring(0, 8);

        // A topic with no outbox_topics row is a QUEUE topic.
        subscriptions.subscribe(topic, "group-a", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults())
                .compose(v -> subscriptions.subscribe(topic, "group-b", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
                .compose(v -> insertMessage(topic))
                .compose(id -> subscriptions.forceRemoveConsumerGroup(topic, "group-b")
                        .compose(result -> messageRow(id)))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    assertEquals("PENDING", row.getString("status"),
                            "force-remove must not mark an unread message processed");
                    assertEquals(1, row.getInteger("required_consumer_groups"),
                            "force-remove must not change the required group count");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("force-remove non-existent subscription fails")
    void forceRemove_nonExistent_fails(VertxTestContext ctx) {
        String topic = "no-such-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-such-group";

        subscriptions.forceRemoveConsumerGroup(topic, groupName)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("force-remove already-cancelled subscription fails and leaves it CANCELLED")
    void forceRemove_alreadyCancelled_fails(VertxTestContext ctx) {
        String topic = "force-rm-cancel-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-cancel-g-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptions.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.builder().build())
                .compose(v -> subscriptions.cancel(topic, groupName))
                .compose(v -> subscriptions.forceRemoveConsumerGroup(topic, groupName)
                        .<Throwable>transform(ar -> ar.failed()
                                ? Future.succeededFuture(ar.cause())
                                : Future.failedFuture(new AssertionError("force-remove of a CANCELLED subscription succeeded"))))
                .compose(err -> subscriptions.getSubscription(topic, groupName).map(info -> {
                    ctx.verify(() -> {
                        assertInstanceOf(IllegalStateException.class, err);
                        assertEquals(SubscriptionState.CANCELLED, info.state());
                    });
                    return (Void) null;
                }))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("force-remove is idempotent: a second call fails")
    void forceRemove_idempotent(VertxTestContext ctx) {
        String topic = "force-rm-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-idemp-g-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptions.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.builder().build())
                .compose(v -> subscriptions.forceRemoveConsumerGroup(topic, groupName))
                .compose(result -> {
                    ctx.verify(() -> assertEquals("ACTIVE", result.previousStatus()));
                    return subscriptions.forceRemoveConsumerGroup(topic, groupName);
                })
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("force-remove fails with the database failure when the subscription table cannot be read")
    void forceRemove_databaseFailure_fails(VertxTestContext ctx) {
        missingSchemaConnectionManager = new PgConnectionManager(manager.getVertx(), null);
        PostgreSQLContainer postgres = getPostgres();
        missingSchemaConnectionManager.getOrCreateReactivePool(POOL,
                new PgConnectionConfig.Builder()
                        .host(postgres.getHost())
                        .port(postgres.getFirstMappedPort())
                        .database(postgres.getDatabaseName())
                        .username(postgres.getUsername())
                        .password(postgres.getPassword())
                        .schema("force_remove_missing_schema")
                        .build(),
                new PgPoolConfig.Builder()
                        .maxSize(1)
                        .shared(false)
                        .idleTimeout(Duration.ofSeconds(2))
                        .connectionTimeout(Duration.ofSeconds(5))
                        .build());
        SubscriptionManager missingSchema = new SubscriptionManager(missingSchemaConnectionManager, POOL);

        missingSchema.forceRemoveConsumerGroup("force-rm-db-failure", "group")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    PgException pg = assertInstanceOf(PgException.class, err);
                    assertEquals("42P01", pg.getSqlState(), "undefined table");
                    ctx.completeNow();
                })));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Sets a status directly, without the lifecycle checks of the service. */
    private Future<Void> updateStatusDirectly(String topic, String groupName, SubscriptionStatus status) {
        return connectionManager.withTransaction(POOL, connection ->
                connection.preparedQuery(
                        "UPDATE outbox_topic_subscriptions " +
                        "SET subscription_status = $1 WHERE topic = $2 AND group_name = $3"
                ).execute(Tuple.of(status.name(), topic, groupName))
                .mapEmpty()
        );
    }

    /** Inserts a PENDING message after the subscriptions, so it belongs to both groups. */
    private Future<Long> insertMessage(String topic) {
        return connectionManager.withTransaction(POOL, connection -> connection
                .preparedQuery("""
                        INSERT INTO outbox (topic, payload, created_at, status)
                        VALUES ($1, $2::jsonb, $3, 'PENDING')
                        RETURNING id
                        """)
                .execute(Tuple.of(topic, new JsonObject().put("force-remove", true), OffsetDateTime.now(ZoneOffset.UTC)))
                .map(rows -> rows.iterator().next().getLong("id")));
    }

    private Future<Row> messageRow(long id) {
        return connectionManager.withConnection(POOL, connection -> connection
                .preparedQuery("SELECT status, required_consumer_groups FROM outbox WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.iterator().next()));
    }
}
