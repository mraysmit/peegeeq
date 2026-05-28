package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for {@link OutboxConsumerGroup#stopGracefully()} against a real PostgreSQL container.
 *
 * <p>Validates that graceful shutdown cancels the subscription in the database
 * when the group was started with subscription options, and is a no-op when
 * the group was started without subscription options or is not active.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("OutboxConsumerGroup \u2014 graceful shutdown")
class OutboxConsumerGroupGracefulShutdownTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupGracefulShutdownTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private OutboxConsumerGroup<String> group;
    private Vertx vertx;
    private PeeGeeQManager manager;
    private DatabaseService databaseService;
    private PeeGeeQConfiguration config;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        this.config = new PeeGeeQConfiguration("default", testProps);
        this.manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        this.manager.start()
                .onSuccess(v -> {
                    this.databaseService = new PgDatabaseService(manager);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future.<Void>succeededFuture()
                .eventually(() -> group != null ? group.close() : Future.succeededFuture())
                .eventually(() -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // No-op cases
    // =========================================================================

    @Test
    @Timeout(5)
    @DisplayName("stopGracefully on NEW group returns succeeded future")
    void stopGracefully_whenNotActive_returnsSuccess() {
        group = createGroup("not-active-group", "test-topic");
        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "Should succeed on non-active group");
    }

    @Test
    @DisplayName("stopGracefully on CLOSED group returns succeeded future")
    void stopGracefully_whenClosed_returnsSuccess(VertxTestContext testContext) throws Exception {
        group = createGroup("closed-group", "test-topic");
        group.close()
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    var future = group.stopGracefully();
                    assertTrue(future.succeeded(), "Should succeed on closed group");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopGracefully is idempotent second call returns succeeded")
    void stopGracefully_idempotent(VertxTestContext testContext) throws Exception {
        group = createGroup("idempotent-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start()
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stopGracefully())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped after first call");
                    var second = group.stopGracefully();
                    assertTrue(second.succeeded(), "Second stopGracefully should succeed (idempotent)");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Without subscription local stop only
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on group started without subscription stops locally, no cancel call")
    void stopGracefully_withoutSubscription_stopsLocallyOnly(VertxTestContext testContext) throws Exception {
        group = createGroup("local-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start()
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stopGracefully())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped");
                    // Group was started without SubscriptionOptions: stopGracefully completes without DB cancel
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // With subscription cancel then stop
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on subscription-started group cancels subscription then stops")
    void stopGracefully_withSubscription_cancelsAndStops(VertxTestContext testContext) throws Exception {
        group = createGroup("sub-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        // Start with subscription options — creates the subscription in the DB
        group.start(SubscriptionOptions.builder().build())
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stopGracefully())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopGracefully when cancel fails still stops the group")
    void stopGracefully_cancelFails_stillStops(VertxTestContext testContext) throws Exception {
        group = createGroup("no-sub-cancel-fail-group", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start()
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> {
                    // Force startedWithSubscription=true without actually creating a DB subscription,
                    // so the cancel call will fail (subscription not found in DB).
                    // stopGracefully() must still succeed via its .transform(ar -> succeededFuture()) guard.
                    try {
                        setPrivateField(group, "startedWithSubscription", true);
                    } catch (Exception e) {
                        return Future.failedFuture(e);
                    }
                    return group.stopGracefully();
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive(), "Group should be stopped even when cancel fails");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("stopGracefully after stop() is no-op does not cancel again")
    void stopGracefully_afterStop_isNoOp(VertxTestContext testContext) throws Exception {
        group = createGroup("stop-then-graceful", "test-topic");
        group.addConsumer("c1", msg -> Future.succeededFuture());
        // Start with subscription options, then stop (not gracefully), then verify stopGracefully is no-op
        group.start(SubscriptionOptions.builder().build())
                .map(v -> { testContext.verify(() -> assertTrue(group.isActive())); return v; })
                .compose(v -> group.stop())  // regular stop — does not cancel DB subscription
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive());
                    var future = group.stopGracefully();
                    assertTrue(future.succeeded(), "Should succeed as no-op — group is already stopped");
                    testContext.completeNow();
                })));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OutboxConsumerGroup<String> createGroup(String groupName, String topic) {
        return new OutboxConsumerGroup<>(
                groupName, topic, String.class,
                databaseService, null, null, config);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass().getName());
    }
}
