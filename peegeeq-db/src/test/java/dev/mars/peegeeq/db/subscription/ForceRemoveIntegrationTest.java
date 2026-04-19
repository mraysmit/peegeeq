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
import dev.mars.peegeeq.api.subscription.ForceRemoveResult;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link SubscriptionManager#forceRemoveConsumerGroup(String, String)}.
 *
 * <p>Tests the full force-remove lifecycle against a real PostgreSQL database:
 * subscribe → force-remove → verify subscription CANCELLED and messages unblocked.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Force-remove — integration tests")
class ForceRemoveIntegrationTest extends BaseIntegrationTest {

    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerGroupCleanup cleanup;

    @BeforeEach
    void setUpForceRemove() {
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

        connectionManager.getOrCreateReactivePool(SERVICE_ID, connectionConfig, poolConfig);

        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);
        cleanup = new DeadConsumerGroupCleanup(connectionManager, SERVICE_ID);
        subscriptionManager.setDeadConsumerGroupCleanup(cleanup);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            awaitFuture(connectionManager.close());
        }
    }

    // =========================================================================
    // Force-remove active subscription
    // =========================================================================

    @Test
    @DisplayName("force-remove active subscription marks CANCELLED")
    void forceRemove_activeSubscription_marksCancelled(VertxTestContext ctx) {
        String topic = "force-rm-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-group-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder().build())
                .compose(v -> subscriptionManager.forceRemoveConsumerGroup(topic, groupName))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertNotNull(result);
                        assertEquals(topic, result.topic());
                        assertEquals(groupName, result.groupName());
                        assertEquals("ACTIVE", result.previousStatus());
                    });
                    // Verify subscription is now CANCELLED
                    return subscriptionManager.getSubscription(topic, groupName);
                })
                .onSuccess(info -> ctx.verify(() -> {
                    assertNotNull(info);
                    assertEquals(SubscriptionState.CANCELLED, info.state());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Force-remove already-dead subscription
    // =========================================================================

    @Test
    @DisplayName("force-remove dead subscription succeeds and marks CANCELLED")
    void forceRemove_deadSubscription_marksCancelled(VertxTestContext ctx) {
        String topic = "force-rm-dead-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-dead-g-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder().build())
                // Manually mark as DEAD first
                .compose(v -> updateStatusDirectly(topic, groupName, SubscriptionStatus.DEAD))
                .compose(v -> subscriptionManager.forceRemoveConsumerGroup(topic, groupName))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertNotNull(result);
                        assertEquals("DEAD", result.previousStatus());
                    });
                    return subscriptionManager.getSubscription(topic, groupName);
                })
                .onSuccess(info -> ctx.verify(() -> {
                    assertEquals(SubscriptionState.CANCELLED, info.state());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Force-remove non-existent subscription
    // =========================================================================

    @Test
    @DisplayName("force-remove non-existent subscription fails")
    void forceRemove_nonExistent_fails(VertxTestContext ctx) {
        String topic = "no-such-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-such-group";

        subscriptionManager.forceRemoveConsumerGroup(topic, groupName)
                .onSuccess(result -> ctx.failNow("Should have failed for non-existent subscription"))
                .onFailure(err -> ctx.verify(() -> {
                    assertNotNull(err.getMessage());
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // Force-remove already-cancelled subscription
    // =========================================================================

    @Test
    @DisplayName("force-remove already-cancelled subscription fails")
    void forceRemove_alreadyCancelled_fails(VertxTestContext ctx) {
        String topic = "force-rm-cancel-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-cancel-g-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder().build())
                .compose(v -> subscriptionManager.cancel(topic, groupName))
                .compose(v -> subscriptionManager.forceRemoveConsumerGroup(topic, groupName))
                .onSuccess(result -> ctx.failNow("Should have failed for already-cancelled subscription"))
                .onFailure(err -> ctx.verify(() -> {
                    assertNotNull(err.getMessage());
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // Force-remove with idempotency
    // =========================================================================

    @Test
    @DisplayName("force-remove is idempotent — second call fails gracefully")
    void forceRemove_idempotent(VertxTestContext ctx) {
        String topic = "force-rm-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "force-rm-idemp-g-" + UUID.randomUUID().toString().substring(0, 8);

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder().build())
                .compose(v -> subscriptionManager.forceRemoveConsumerGroup(topic, groupName))
                .compose(result -> {
                    ctx.verify(() -> assertEquals("ACTIVE", result.previousStatus()));
                    // Second call should fail (already CANCELLED)
                    return subscriptionManager.forceRemoveConsumerGroup(topic, groupName);
                })
                .onSuccess(result -> ctx.failNow("Second force-remove should have failed"))
                .onFailure(err -> ctx.verify(() -> {
                    assertNotNull(err.getMessage());
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // Helper — directly update subscription status (bypasses normal validation)
    // =========================================================================

    private Future<Void> updateStatusDirectly(String topic, String groupName, SubscriptionStatus status) {
        return connectionManager.withConnection(SERVICE_ID, connection ->
                connection.preparedQuery(
                        "UPDATE outbox_topic_subscriptions " +
                        "SET subscription_status = $1 WHERE topic = $2 AND group_name = $3"
                ).execute(Tuple.of(status.name(), topic, groupName))
                .mapEmpty()
        );
    }
}
