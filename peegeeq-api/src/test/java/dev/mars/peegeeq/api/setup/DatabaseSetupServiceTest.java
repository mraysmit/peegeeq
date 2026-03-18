package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseSetupServiceTest {

    @Test
    void testDefaultAsyncBridgeMethods() throws ExecutionException, InterruptedException {
        DatabaseSetupService service = new DatabaseSetupService() {
            @Override public Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) { return Future.succeededFuture(null); }
            @Override public Future<Void> destroySetup(String setupId) { return Future.succeededFuture(); }
            @Override public Future<DatabaseSetupStatus> getSetupStatus(String setupId) { return Future.succeededFuture(DatabaseSetupStatus.ACTIVE); }
            @Override public Future<DatabaseSetupResult> getSetupResult(String setupId) { return Future.succeededFuture(null); }
            @Override public Future<Void> addQueue(String setupId, QueueConfig queueConfig) { return Future.succeededFuture(); }
            @Override public Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) { return Future.succeededFuture(); }
            @Override public Future<Set<String>> getAllActiveSetupIds() { return Future.succeededFuture(Collections.emptySet()); }
            @Override public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionServiceForSetup(String setupId) { return null; }
            @Override public dev.mars.peegeeq.api.deadletter.DeadLetterService getDeadLetterServiceForSetup(String setupId) { return null; }
            @Override public dev.mars.peegeeq.api.health.HealthService getHealthServiceForSetup(String setupId) { return null; }
            @Override public dev.mars.peegeeq.api.QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) { return null; }
        };

        // Test primary Future-returning methods
        Future<DatabaseSetupResult> createFuture = service.createCompleteSetup(null);
        assertNotNull(createFuture);
        assertTrue(createFuture.succeeded());

        Future<Void> destroyFuture = service.destroySetup("id");
        assertNotNull(destroyFuture);
        assertTrue(destroyFuture.succeeded());

        Future<DatabaseSetupStatus> statusFuture = service.getSetupStatus("id");
        assertNotNull(statusFuture);
        assertEquals(DatabaseSetupStatus.ACTIVE, statusFuture.result());

        Future<DatabaseSetupResult> resultFuture = service.getSetupResult("id");
        assertNotNull(resultFuture);
        assertTrue(resultFuture.succeeded());

        Future<Void> queueFuture = service.addQueue("id", null);
        assertNotNull(queueFuture);
        assertTrue(queueFuture.succeeded());

        Future<Void> eventStoreFuture = service.addEventStore("id", null);
        assertNotNull(eventStoreFuture);
        assertTrue(eventStoreFuture.succeeded());

        Future<Set<String>> idsFuture = service.getAllActiveSetupIds();
        assertNotNull(idsFuture);
        Set<String> ids = idsFuture.result();
        assertNotNull(ids);
        assertTrue(ids.isEmpty());

        // Test default *Async() bridge methods return CompletableFuture
        CompletableFuture<Void> destroyAsync = service.destroySetupAsync("id");
        assertNotNull(destroyAsync);
        destroyAsync.get();

        CompletableFuture<DatabaseSetupStatus> statusAsync = service.getSetupStatusAsync("id");
        assertNotNull(statusAsync);
        assertEquals(DatabaseSetupStatus.ACTIVE, statusAsync.get());

        CompletableFuture<Set<String>> idsAsync = service.getAllActiveSetupIdsAsync();
        assertNotNull(idsAsync);
        assertTrue(idsAsync.get().isEmpty());
    }
}
