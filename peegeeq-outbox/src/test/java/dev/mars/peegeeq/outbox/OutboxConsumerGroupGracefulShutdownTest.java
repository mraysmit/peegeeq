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

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OutboxConsumerGroup#stopGracefully()}.
 *
 * <p>Validates that graceful shutdown cancels the subscription in the database
 * when the group was started with subscription options, and is a no-op when
 * the group was started without subscription options or is not active.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@DisplayName("OutboxConsumerGroup \u2014 graceful shutdown")
class OutboxConsumerGroupGracefulShutdownTest {

    private OutboxConsumerGroup<String> group;
    private Vertx vertx;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
    }

    @AfterEach
    void tearDown() {
        if (group != null) {
            group.close();
        }
    }

    // =========================================================================
    // No-op cases
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on NEW group returns succeeded future")
    void stopGracefully_whenNotActive_returnsSuccess() {
        group = createGroup("not-active-group", "test-topic", new StubDatabaseService(vertx));
        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "Should succeed on non-active group");
    }

    @Test
    @DisplayName("stopGracefully on CLOSED group returns succeeded future")
    void stopGracefully_whenClosed_returnsSuccess() {
        group = createGroup("closed-group", "test-topic", new StubDatabaseService(vertx));
        group.close();
        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "Should succeed on closed group");
    }

    @Test
    @DisplayName("stopGracefully is idempotent second call returns succeeded")
    void stopGracefully_idempotent() {
        group = createGroup("idempotent-group", "test-topic", new StubDatabaseService(vertx));
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start();
        assertTrue(group.isActive());

        var first = group.stopGracefully();
        assertTrue(first.succeeded(), "First stopGracefully should succeed");
        assertFalse(group.isActive(), "Group should be stopped after first call");

        var second = group.stopGracefully();
        assertTrue(second.succeeded(), "Second stopGracefully should succeed (idempotent)");
    }

    // =========================================================================
    // Without subscription local stop only
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on group started without subscription stops locally, no cancel call")
    void stopGracefully_withoutSubscription_stopsLocallyOnly() {
        var cancelTracker = new AtomicBoolean(false);
        var dbService = new CancelTrackingDatabaseService(vertx, cancelTracker);
        group = createGroup("local-group", "test-topic", dbService);
        group.addConsumer("c1", msg -> Future.succeededFuture());
        group.start();  // start without subscription options
        assertTrue(group.isActive());

        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "Should succeed");
        assertFalse(group.isActive(), "Group should be stopped");
        assertFalse(cancelTracker.get(), "Should NOT call cancel when no subscription was created");
    }

    // =========================================================================
    // With subscription cancel then stop
    // =========================================================================

    @Test
    @DisplayName("stopGracefully on subscription-started group cancels subscription then stops")
    void stopGracefully_withSubscription_cancelsAndStops() {
        var cancelCount = new AtomicInteger(0);
        var dbService = new SubscriptionTrackingDatabaseService(vertx, cancelCount);
        group = createGroup("sub-group", "test-topic", dbService);
        group.addConsumer("c1", msg -> Future.succeededFuture());

        // Start with subscription options
        var startFuture = group.start(SubscriptionOptions.builder().build());
        assertTrue(startFuture.succeeded(), "start with subscription should succeed");
        assertTrue(group.isActive());

        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "stopGracefully should succeed");
        assertFalse(group.isActive(), "Group should be stopped");
        assertEquals(1, cancelCount.get(), "Should have called cancel exactly once");
    }

    @Test
    @DisplayName("stopGracefully when cancel fails still stops the group")
    void stopGracefully_cancelFails_stillStops() {
        var dbService = new FailingCancelDatabaseService(vertx);
        group = createGroup("fail-cancel-group", "test-topic", dbService);
        group.addConsumer("c1", msg -> Future.succeededFuture());

        var startFuture = group.start(SubscriptionOptions.builder().build());
        assertTrue(startFuture.succeeded());
        assertTrue(group.isActive());

        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "stopGracefully should succeed even when cancel fails");
        assertFalse(group.isActive(), "Group should be stopped even when cancel fails");
    }

    @Test
    @DisplayName("stopGracefully after stop() is no-op does not cancel again")
    void stopGracefully_afterStop_isNoOp() {
        var cancelCount = new AtomicInteger(0);
        var dbService = new SubscriptionTrackingDatabaseService(vertx, cancelCount);
        group = createGroup("stop-then-graceful", "test-topic", dbService);
        group.addConsumer("c1", msg -> Future.succeededFuture());

        group.start(SubscriptionOptions.builder().build());
        assertTrue(group.isActive());

        group.stop();  // regular stop first
        assertFalse(group.isActive());

        var future = group.stopGracefully();
        assertTrue(future.succeeded(), "Should succeed as no-op");
        assertEquals(0, cancelCount.get(), "Should NOT cancel group is already stopped");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OutboxConsumerGroup<String> createGroup(String groupName, String topic, DatabaseService dbService) {
        return new OutboxConsumerGroup<>(
                groupName, topic, String.class,
                dbService, null, null,
                new PeeGeeQConfiguration("test"));
    }

    /**
     * Stub DatabaseService with no-op subscription service.
     */
    private static class StubDatabaseService implements DatabaseService {
        private final Vertx vertx;
        StubDatabaseService() { this(null); }
        StubDatabaseService(Vertx vertx) { this.vertx = vertx; }
        @Override public Future<Void> initialize() { return Future.succeededFuture(); }
        @Override public Future<Void> start() { return Future.succeededFuture(); }
        @Override public Future<Void> stop() { return Future.succeededFuture(); }
        @Override public boolean isRunning() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public ConnectionProvider getConnectionProvider() { return null; }
        @Override public MetricsProvider getMetricsProvider() { return null; }
        @Override public SubscriptionService getSubscriptionService() { return null; }
        @Override public Future<Void> runMigrations() { return Future.succeededFuture(); }
        @Override public Future<Boolean> performHealthCheck() { return Future.succeededFuture(true); }
        @Override public Vertx getVertx() { return vertx; }
        @Override public Pool getPool() { return null; }
        @Override public PgConnectOptions getConnectOptions() { return null; }
        @Override public void close() { }
    }

    /**
     * Tracks whether cancel was called (subscription tracking without actual subscription).
     */
    private static class CancelTrackingDatabaseService extends StubDatabaseService {
        private final AtomicBoolean cancelCalled;

        CancelTrackingDatabaseService(Vertx vertx, AtomicBoolean cancelCalled) {
            super(vertx);
            this.cancelCalled = cancelCalled;
        }

        @Override
        public SubscriptionService getSubscriptionService() {
            return new StubSubscriptionService() {
                @Override
                public Future<Void> cancel(String topic, String groupName) {
                    cancelCalled.set(true);
                    return Future.succeededFuture();
                }
            };
        }
    }

    /**
     * Tracks cancel call count and provides working subscribe + cancel.
     */
    private static class SubscriptionTrackingDatabaseService extends StubDatabaseService {
        private final AtomicInteger cancelCount;

        SubscriptionTrackingDatabaseService(Vertx vertx, AtomicInteger cancelCount) {
            super(vertx);
            this.cancelCount = cancelCount;
        }

        @Override
        public SubscriptionService getSubscriptionService() {
            return new StubSubscriptionService() {
                @Override
                public Future<Void> subscribe(String topic, String groupName, SubscriptionOptions options) {
                    return Future.succeededFuture();
                }

                @Override
                public Future<Void> cancel(String topic, String groupName) {
                    cancelCount.incrementAndGet();
                    return Future.succeededFuture();
                }
            };
        }
    }

    /**
     * Subscribe succeeds but cancel fails.
     */
    private static class FailingCancelDatabaseService extends StubDatabaseService {
        FailingCancelDatabaseService(Vertx vertx) { super(vertx); }
        @Override
        public SubscriptionService getSubscriptionService() {
            return new StubSubscriptionService() {
                @Override
                public Future<Void> subscribe(String topic, String groupName, SubscriptionOptions options) {
                    return Future.succeededFuture();
                }

                @Override
                public Future<Void> cancel(String topic, String groupName) {
                    return Future.failedFuture(new RuntimeException("Simulated cancel failure"));
                }
            };
        }
    }

    /**
     * Base stub SubscriptionService all methods return failed futures by default.
     */
    private static abstract class StubSubscriptionService implements SubscriptionService {
        @Override public Future<Void> subscribe(String topic, String groupName) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<Void> subscribe(String topic, String groupName, SubscriptionOptions options) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<Void> pause(String topic, String groupName) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<Void> resume(String topic, String groupName) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<Void> cancel(String topic, String groupName) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<Void> updateHeartbeat(String topic, String groupName) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<dev.mars.peegeeq.api.subscription.SubscriptionInfo> getSubscription(String topic, String groupName) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
        @Override public Future<java.util.List<dev.mars.peegeeq.api.subscription.SubscriptionInfo>> listSubscriptions(String topic) {
            return Future.failedFuture(new UnsupportedOperationException("not stubbed"));
        }
    }
}
