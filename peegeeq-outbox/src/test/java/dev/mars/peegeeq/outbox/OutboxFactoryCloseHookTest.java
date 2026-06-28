package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.lifecycle.LifecycleHookRegistrar;
import dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for OutboxFactory close hook lifecycle (Task 1).
 *
 * <p>These tests verify that the close hook registered with PeeGeeQManager
 * actually closes tracked resources during shutdown, instead of being a no-op.
 *
 * <p>All tests are CORE — no database access, no TestContainers. Async close-hook
 * completion is driven via {@link VertxTestContext}; the Vert.x instance is supplied
 * by {@link VertxExtension}, so there is no manually-created, leaked {@code Vertx}.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@DisplayName("OutboxFactory close hook lifecycle")
class OutboxFactoryCloseHookTest {

    // -- Test 1: Close hook is registered --

    @Test
    @DisplayName("Close hook should be registered when DatabaseService is a LifecycleHookRegistrar")
    void closeHookIsRegistered(Vertx vertx) {
        var dbService = new HookCapturingDatabaseService(vertx);
        new OutboxFactory(dbService, minimalTestConfig());

        assertNotNull(dbService.getCapturedHook(), "Close hook should have been registered");
        assertEquals("outbox", dbService.getCapturedHook().name());
    }

    // -- Test 2: Close hook closes tracked consumers --

    @Test
    @DisplayName("Close hook closeReactive() should close tracked consumers")
    void closeHookClosesConsumers(Vertx vertx, VertxTestContext testContext) {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        // Create a consumer — adds to createdResources
        MessageConsumer<String> consumer = factory.createConsumer("test-topic", String.class);
        assertNotNull(consumer);

        // Invoke the close hook (simulates PeeGeeQManager shutdown step 2)
        dbService.getCapturedHook().closeReactive()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                // Factory should now be closed — verify by trying to create another resource
                assertThrows(IllegalStateException.class, () ->
                        factory.createProducer("another-topic", String.class),
                        "Factory should reject operations after close hook fires");
                testContext.completeNow();
            })));
    }

    // -- Test 3: Close hook closes tracked consumer groups --

    @Test
    @DisplayName("Close hook closeReactive() should close tracked consumer groups")
    void closeHookClosesConsumerGroups(Vertx vertx, VertxTestContext testContext) {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        ConsumerGroup<String> group = factory.createConsumerGroup("test-topic", "test-group", String.class);
        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;
        assertNotEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState());

        dbService.getCapturedHook().closeReactive()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState(),
                        "Consumer group should be in CLOSED state after close hook fires");
                testContext.completeNow();
            })));
    }

    // -- Test 4: Close hook is idempotent --

    @Test
    @DisplayName("Close hook closeReactive() should be idempotent")
    void closeHookIsIdempotent(Vertx vertx, VertxTestContext testContext) {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        factory.createConsumer("test-topic", String.class);

        // Call twice — the second close must also succeed (idempotent). Composing the two
        // routes a failure of either call to failNow via succeeding().
        dbService.getCapturedHook().closeReactive()
            .compose(v -> dbService.getCapturedHook().closeReactive())
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // -- Test 5: Factory rejects operations after close hook fires --

    @Test
    @DisplayName("Factory should reject createProducer after close hook fires")
    void factoryRejectsOperationsAfterCloseHook(Vertx vertx, VertxTestContext testContext) {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        dbService.getCapturedHook().closeReactive()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                        factory.createProducer("test-topic", String.class));
                assertTrue(ex.getMessage().contains("closed"), "Exception should mention closed state");
                testContext.completeNow();
            })));
    }

    // -- Test 6: Sync close() delegates to async path --

    @Test
    @DisplayName("Sync close() should close tracked resources (same as hook)")
    void syncCloseClosesResources(Vertx vertx) throws Exception {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        ConsumerGroup<String> group = factory.createConsumerGroup("test-topic", "test-group", String.class);
        OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;

        factory.close();

        assertEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState(),
                "Consumer group should be closed after factory.close()");
    }

    // -- Test 7a: Close hook then close() should not error --

    @Test
    @DisplayName("Calling close hook then close() should not error")
    void closeHookThenSyncClose(Vertx vertx, VertxTestContext testContext) throws Exception {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        factory.createConsumer("test-topic", String.class);

        // Hook fires first (manager shutdown); drive it to completion before the sync close.
        dbService.getCapturedHook().closeReactive()
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "Close hook should complete");
        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }

        // Then explicit close() later, on the JUnit worker thread — QueueFactory.close()
        // is thread-affinity guarded and must not run on a Vert.x event-loop thread.
        assertDoesNotThrow(() -> factory.close(),
                "close() after close hook should not throw");
    }

    // -- Test 7b: close() then close hook should not error --

    @Test
    @DisplayName("Calling close() then close hook should not error")
    void syncCloseThenCloseHook(Vertx vertx, VertxTestContext testContext) throws Exception {
        var dbService = new HookCapturingDatabaseService(vertx);
        OutboxFactory factory = new OutboxFactory(dbService, minimalTestConfig());

        factory.createConsumer("test-topic", String.class);

        // Explicit close() first (on the JUnit worker thread), then the hook fires during shutdown.
        factory.close();
        dbService.getCapturedHook().closeReactive()
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // -- Test infrastructure --

    private static PeeGeeQConfiguration minimalTestConfig() {
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host", "localhost");
        props.setProperty("peegeeq.database.name", "test");
        props.setProperty("peegeeq.database.username", "test");
        props.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);
        return new PeeGeeQConfiguration("default", props);
    }

    /**
     * DatabaseService that also implements LifecycleHookRegistrar,
     * so OutboxFactory's constructor registers the close hook.
     * Captures the hook for test invocation.
     *
     * <p>The Vert.x instance is supplied by the caller (the VertxExtension-managed
     * instance) rather than created here, so there is no unmanaged Vert.x to leak.
     */
    private static class HookCapturingDatabaseService implements DatabaseService, LifecycleHookRegistrar {
        private final Vertx vertx;
        private PeeGeeQCloseHook capturedHook;

        HookCapturingDatabaseService(Vertx vertx) {
            this.vertx = vertx;
        }

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
        public io.vertx.core.Vertx getVertx() { return vertx; }

        @Override
        public io.vertx.sqlclient.Pool getPool() { return null; }

        @Override
        public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }

        @Override
        public void close() { }
    }
}
