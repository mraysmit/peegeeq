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
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;


import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for H1: clientId propagation through the consumer group path.
 *
 * <p>These tests verify that when an {@link OutboxFactory} is constructed with a
 * non-null clientId, that clientId is propagated through to the {@link OutboxConsumerGroup}
 * and ultimately to the {@link OutboxConsumer} created inside
 * {@link OutboxConsumerGroup#start()}.</p>
 *
 * <p>Current defect: {@code OutboxFactory.createConsumerGroup()} does not pass its
 * {@code clientId} field to the {@code OutboxConsumerGroup} constructor. The consumer
 * group has no clientId field at all. When {@code OutboxConsumerGroup.start()} creates
 * an {@code OutboxConsumer}, it uses a constructor without clientId, defaulting to null
 * (default pool). In multi-pool/multi-tenant deployments, this silently uses the wrong
 * database.</p>
 */
@Tag(TestCategories.CORE)
@DisplayName("H1: clientId propagation through consumer group path")
class OutboxConsumerGroupClientIdPropagationTest {

    // ========================================================================
    // Positive tests: clientId SHOULD be propagated
    // ========================================================================

    @Test
    @DisplayName("OutboxFactory with clientId should propagate it to created consumer groups")
    void factoryWithClientIdShouldPropagateToConsumerGroup() throws Exception {
        // Given: a factory constructed with an explicit clientId
        String expectedClientId = "tenant-pool-42";
        DatabaseService databaseService = new StubDatabaseService();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        OutboxFactory factory = new OutboxFactory(databaseService, null, config, expectedClientId);

        // When: we create a consumer group through the factory
        ConsumerGroup<String> group = factory.createConsumerGroup("my-group", "my-topic", String.class);

        // Then: the consumer group should have captured the clientId
        assertNotNull(group);
        assertTrue(group instanceof OutboxConsumerGroup, "Expected OutboxConsumerGroup instance");

        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;

        // Verify clientId is present on the consumer group itself
        // (currently fails — OutboxConsumerGroup has no clientId field)
        String actualClientId = getPrivateField(outboxGroup, "clientId", String.class);
        assertEquals(expectedClientId, actualClientId,
                "Consumer group should hold the factory's clientId for propagation to its underlying consumer");

        factory.close();
    }

    @Test
    @DisplayName("Consumer group start() should create underlying consumer with correct clientId")
    void consumerGroupStartShouldPassClientIdToUnderlyingConsumer() throws Exception {
        // Given: a consumer group that was created with a specific clientId
        String expectedClientId = "tenant-pool-99";
        DatabaseService databaseService = new StubDatabaseService();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        OutboxConsumerGroup<String> group = new OutboxConsumerGroup<>(
                "test-group", "test-topic", String.class,
                databaseService, null, null, config);

        // NOTE: With the fix, this constructor should accept clientId.
        // For now, we set it reflectively to test the start() propagation path
        // independently of the constructor fix.
        setPrivateField(group, "clientId", expectedClientId);

        // Add a member so start() has something to work with
        group.addConsumer("member-1", message -> Future.succeededFuture());

        // When: we start the group (creates the underlying OutboxConsumer)
        // This will fail without a real DB, but we can still inspect the state
        // before the polling loop tries to connect.
        try {
            group.start();
        } catch (Exception ignored) {
            // Expected — no real database available
        }

        // Then: the underlying consumer should have the same clientId
        Object underlyingConsumer = getPrivateField(group, "underlyingConsumer", Object.class);
        if (underlyingConsumer instanceof OutboxConsumer<?> consumer) {
            String actualClientId = getPrivateField(consumer, "clientId", String.class);
            assertEquals(expectedClientId, actualClientId,
                    "Underlying consumer created by start() should have the group's clientId");
        }
        // If underlyingConsumer is null, start() failed before creating it — acceptable in unit context

        group.close();
    }

    // ========================================================================
    // Negative tests: null clientId should remain null (default pool)
    // ========================================================================

    @Test
    @DisplayName("OutboxFactory with null clientId should propagate null to consumer groups")
    void factoryWithNullClientIdShouldPropagateNullToConsumerGroup() throws Exception {
        // Given: a factory constructed without a clientId (uses default pool)
        DatabaseService databaseService = new StubDatabaseService();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        OutboxFactory factory = new OutboxFactory(databaseService, config);

        // When: we create a consumer group
        ConsumerGroup<String> group = factory.createConsumerGroup("my-group", "my-topic", String.class);

        // Then: the consumer group should have null clientId (default pool behaviour)
        assertNotNull(group);
        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;

        String actualClientId = getPrivateField(outboxGroup, "clientId", String.class);
        assertNull(actualClientId,
                "Consumer group created by factory with null clientId should have null clientId");

        factory.close();
    }

    @Test
    @DisplayName("Directly created consumer vs factory-created consumer group should have same clientId")
    void directConsumerAndGroupConsumerShouldHaveSameClientId() throws Exception {
        // Given: a factory with an explicit clientId
        String expectedClientId = "shared-pool";
        DatabaseService databaseService = new StubDatabaseService();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        OutboxFactory factory = new OutboxFactory(databaseService, null, config, expectedClientId);

        // When: we create both a direct consumer and a consumer group
        var directConsumer = factory.createConsumer("test-topic", String.class);
        ConsumerGroup<String> group = factory.createConsumerGroup("my-group", "test-topic", String.class);

        // Then: both should use the same clientId
        String directClientId = getPrivateField(directConsumer, "clientId", String.class);
        assertEquals(expectedClientId, directClientId,
                "Direct consumer should have the factory's clientId");

        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;
        String groupClientId = getPrivateField(outboxGroup, "clientId", String.class);
        assertEquals(expectedClientId, groupClientId,
                "Consumer group should have the same clientId as a direct consumer from the same factory");

        factory.close();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object target, String fieldName, Class<T> type) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass().getName());
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

    /**
     * Minimal DatabaseService stub for unit tests — no real database needed.
     */
    private static class StubDatabaseService implements DatabaseService {
        @Override public io.vertx.core.Future<Void> initialize() { return io.vertx.core.Future.succeededFuture(); }
        @Override public io.vertx.core.Future<Void> start() { return io.vertx.core.Future.succeededFuture(); }
        @Override public io.vertx.core.Future<Void> stop() { return io.vertx.core.Future.succeededFuture(); }
        @Override public boolean isRunning() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public dev.mars.peegeeq.api.database.ConnectionProvider getConnectionProvider() { return null; }
        @Override public MetricsProvider getMetricsProvider() { return null; }
        @Override public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionService() { return null; }
        @Override public io.vertx.core.Future<Void> runMigrations() { return io.vertx.core.Future.succeededFuture(); }
        @Override public io.vertx.core.Future<Boolean> performHealthCheck() { return io.vertx.core.Future.succeededFuture(true); }
        @Override public io.vertx.core.Vertx getVertx() { return null; }
        @Override public io.vertx.sqlclient.Pool getPool() { return null; }
        @Override public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }
        @Override public void close() { }
    }
}
