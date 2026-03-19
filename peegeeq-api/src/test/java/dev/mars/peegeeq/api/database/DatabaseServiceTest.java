package dev.mars.peegeeq.api.database;

import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseServiceTest {

    @Test
    void testPrimaryFutureMethods() {
        // Create a mock or anonymous implementation
        DatabaseService service = new DatabaseService() {
            @Override public Future<Void> initialize() { return Future.succeededFuture(); }
            @Override public Future<Void> start() { return Future.succeededFuture(); }
            @Override public Future<Void> stop() { return Future.succeededFuture(); }
            @Override public boolean isRunning() { return false; }
            @Override public boolean isHealthy() { return false; }
            @Override public ConnectionProvider getConnectionProvider() { return null; }
            @Override public MetricsProvider getMetricsProvider() { return NoOpMetricsProvider.INSTANCE; }
            @Override public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionService() { return null; }
            @Override public Future<Void> runMigrations() { return Future.succeededFuture(); }
            @Override public Future<Boolean> performHealthCheck() { return Future.succeededFuture(true); }
            @Override public void close() throws Exception {}
            @Override public io.vertx.core.Vertx getVertx() { return null; }
            @Override public io.vertx.sqlclient.Pool getPool() { return null; }
            @Override public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }
        };

        // Test primary Future-returning methods
        Future<Void> initFuture = service.initialize();
        assertNotNull(initFuture);
        assertTrue(initFuture.succeeded());

        Future<Void> startFuture = service.start();
        assertNotNull(startFuture);
        assertTrue(startFuture.succeeded());

        Future<Void> stopFuture = service.stop();
        assertNotNull(stopFuture);
        assertTrue(stopFuture.succeeded());

        Future<Void> migrationFuture = service.runMigrations();
        assertNotNull(migrationFuture);
        assertTrue(migrationFuture.succeeded());

        Future<Boolean> healthFuture = service.performHealthCheck();
        assertNotNull(healthFuture);
        assertTrue(healthFuture.result());
    }
}
