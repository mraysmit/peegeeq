package dev.mars.peegeeq.api.database;

import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseServiceTest {

    @Test
    void testDefaultReactiveMethods() throws ExecutionException, InterruptedException {
        // Create a mock or anonymous implementation
        DatabaseService service = new DatabaseService() {
            @Override public CompletableFuture<Void> initialize() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> start() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> stop() { return CompletableFuture.completedFuture(null); }
            @Override public boolean isRunning() { return false; }
            @Override public boolean isHealthy() { return false; }
            @Override public ConnectionProvider getConnectionProvider() { return null; }
            @Override public MetricsProvider getMetricsProvider() { return NoOpMetricsProvider.INSTANCE; }
            @Override public CompletableFuture<Void> runMigrations() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Boolean> performHealthCheck() { return CompletableFuture.completedFuture(true); }
            @Override public void close() throws Exception {}
            @Override public io.vertx.core.Vertx getVertx() { return null; }
            @Override public io.vertx.sqlclient.Pool getPool() { return null; }
            @Override public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }
        };

        // Test initializeReactive
        Future<Void> initFuture = service.initializeReactive();
        assertNotNull(initFuture);
        initFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(initFuture.succeeded());

        // Test startReactive
        Future<Void> startFuture = service.startReactive();
        assertNotNull(startFuture);
        startFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(startFuture.succeeded());

        // Test stopReactive
        Future<Void> stopFuture = service.stopReactive();
        assertNotNull(stopFuture);
        stopFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(stopFuture.succeeded());

        // Test runMigrationsReactive
        Future<Void> migrationFuture = service.runMigrationsReactive();
        assertNotNull(migrationFuture);
        migrationFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(migrationFuture.succeeded());

        // Test performHealthCheckReactive
        Future<Boolean> healthFuture = service.performHealthCheckReactive();
        assertNotNull(healthFuture);
        assertTrue(healthFuture.toCompletionStage().toCompletableFuture().get());
        assertTrue(healthFuture.succeeded());
    }
}
