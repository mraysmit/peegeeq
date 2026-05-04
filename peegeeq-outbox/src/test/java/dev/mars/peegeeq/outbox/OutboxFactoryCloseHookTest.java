package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.lifecycle.LifecycleHookRegistrar;
import dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for OutboxFactory close hook lifecycle (Task 1).
 *
 * <p>These tests verify that the close hook registered with PeeGeeQManager
 * actually closes tracked resources during shutdown, instead of being a no-op.
 *
 * <p>All tests are CORE no database access, no TestContainers.
 */
@Tag(TestCategories.CORE)
@DisplayName("OutboxFactory close hook lifecycle")
class OutboxFactoryCloseHookTest {

    // -- Test 1: Close hook is registered --

    @Test
    @DisplayName("Close hook should be registered when DatabaseService is a LifecycleHookRegistrar")
    void closeHookIsRegistered() {
        var dbService = new HookCapturingDatabaseService();
        new OutboxFactory(dbService);

        assertNotNull(dbService.getCapturedHook(), "Close hook should have been registered");
        assertEquals("outbox", dbService.getCapturedHook().name());
    }

    // -- Test 2: Close hook closes tracked consumers --

    @Test
    @DisplayName("Close hook closeReactive() should close tracked consumers")
    void closeHookClosesConsumers() {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        // Create a consumer adds to createdResources
        MessageConsumer<String> consumer = factory.createConsumer("test-topic", String.class);
        assertNotNull(consumer);

        // Invoke the close hook (simulates PeeGeeQManager shutdown step 2)
        Future<Void> result = dbService.getCapturedHook().closeReactive();
        result.await();

        assertTrue(result.succeeded(), "Close hook should complete successfully");

        // Factory should now be closed verify by trying to create another resource
        assertThrows(IllegalStateException.class, () ->
                factory.createProducer("another-topic", String.class),
                "Factory should reject operations after close hook fires");
    }

    // -- Test 3: Close hook closes tracked consumer groups --

    @Test
    @DisplayName("Close hook closeReactive() should close tracked consumer groups")
    void closeHookClosesConsumerGroups() {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        ConsumerGroup<String> group = factory.createConsumerGroup("test-topic", "test-group", String.class);
        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;
        assertNotEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState());

        Future<Void> result = dbService.getCapturedHook().closeReactive();
        result.await();

        assertTrue(result.succeeded(), "Close hook should complete successfully");
        assertEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState(),
                "Consumer group should be in CLOSED state after close hook fires");
    }

    // -- Test 4: Close hook is idempotent --

    @Test
    @DisplayName("Close hook closeReactive() should be idempotent")
    void closeHookIsIdempotent() {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        factory.createConsumer("test-topic", String.class);

        // Call twice
        dbService.getCapturedHook().closeReactive().await();
        Future<Void> second = dbService.getCapturedHook().closeReactive();
        second.await();

        assertTrue(second.succeeded(), "Second close hook call should succeed without error");
    }

    // -- Test 5: Factory rejects operations after close hook fires --

    @Test
    @DisplayName("Factory should reject createProducer after close hook fires")
    void factoryRejectsOperationsAfterCloseHook() {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        dbService.getCapturedHook().closeReactive().await();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factory.createProducer("test-topic", String.class));
        assertTrue(ex.getMessage().contains("closed"), "Exception should mention closed state");
    }

    // -- Test 6: Sync close() delegates to async path --

    @Test
    @DisplayName("Sync close() should close tracked resources (same as hook)")
    void syncCloseClosesResources() throws Exception {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        ConsumerGroup<String> group = factory.createConsumerGroup("test-topic", "test-group", String.class);
        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;

        factory.close();

        assertEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState(),
                "Consumer group should be closed after factory.close()");
    }

    // -- Test 7a: Close hook then close() should not error --

    @Test
    @DisplayName("Calling close hook then close() should not error")
    void closeHookThenSyncClose() {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        factory.createConsumer("test-topic", String.class);

        // Hook fires first (manager shutdown), then explicit close() later
        dbService.getCapturedHook().closeReactive().await();
        assertDoesNotThrow(() -> factory.close(),
                "close() after close hook should not throw");
    }

    // -- Test 7b: close() then close hook should not error --

    @Test
    @DisplayName("Calling close() then close hook should not error")
    void syncCloseThenCloseHook() throws Exception {
        var dbService = new HookCapturingDatabaseService();
        OutboxFactory factory = new OutboxFactory(dbService);

        factory.createConsumer("test-topic", String.class);

        // Explicit close() first, then hook fires during manager shutdown
        factory.close();
        Future<Void> hookResult = dbService.getCapturedHook().closeReactive();
        hookResult.await();
        assertTrue(hookResult.succeeded(),
                "Close hook after close() should succeed");
    }

    // -- Test infrastructure --

    /**
     * DatabaseService that also implements LifecycleHookRegistrar,
     * so OutboxFactory's constructor registers the close hook.
     * Captures the hook for test invocation.
     */
    private static class HookCapturingDatabaseService implements DatabaseService, LifecycleHookRegistrar {
        private PeeGeeQCloseHook capturedHook;

        @Override
        public void registerCloseHook(PeeGeeQCloseHook hook) {
            this.capturedHook = hook;
        }

        PeeGeeQCloseHook getCapturedHook() {
            return capturedHook;
        }

        // -- Minimal DatabaseService stubs --

        @Override
        public Future<Void> initialize() { return Future.succeededFuture(); }

        @Override
        public Future<Void> start() { return Future.succeededFuture(); }

        @Override
        public Future<Void> stop() { return Future.succeededFuture(); }

        @Override
        public boolean isRunning() { return true; }

        @Override
        public boolean isHealthy() { return true; }

        @Override
        public dev.mars.peegeeq.api.database.ConnectionProvider getConnectionProvider() { return null; }

        @Override
        public MetricsProvider getMetricsProvider() { return null; }

        @Override
        public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionService() { return null; }

        @Override
        public Future<Void> runMigrations() { return Future.succeededFuture(); }

        @Override
        public Future<Boolean> performHealthCheck() { return Future.succeededFuture(true); }

        @Override
        public io.vertx.core.Vertx getVertx() { return null; }

        @Override
        public io.vertx.sqlclient.Pool getPool() { return null; }

        @Override
        public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }

        @Override
        public void close() { }
    }
}
