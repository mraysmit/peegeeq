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
    void testDefaultReactiveMethods() throws ExecutionException, InterruptedException {
        DatabaseSetupService service = new DatabaseSetupService() {
            @Override public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> destroySetup(String setupId) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) { return CompletableFuture.completedFuture(DatabaseSetupStatus.ACTIVE); }
            @Override public CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Set<String>> getAllActiveSetupIds() { return CompletableFuture.completedFuture(Collections.emptySet()); }
        };

        // Test createCompleteSetupReactive
        Future<DatabaseSetupResult> createFuture = service.createCompleteSetupReactive(null);
        assertNotNull(createFuture);
        createFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(createFuture.succeeded());

        // Test destroySetupReactive
        Future<Void> destroyFuture = service.destroySetupReactive("id");
        assertNotNull(destroyFuture);
        destroyFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(destroyFuture.succeeded());

        // Test getSetupStatusReactive
        Future<DatabaseSetupStatus> statusFuture = service.getSetupStatusReactive("id");
        assertNotNull(statusFuture);
        assertEquals(DatabaseSetupStatus.ACTIVE, statusFuture.toCompletionStage().toCompletableFuture().get());
        assertTrue(statusFuture.succeeded());

        // Test getSetupResultReactive
        Future<DatabaseSetupResult> resultFuture = service.getSetupResultReactive("id");
        assertNotNull(resultFuture);
        resultFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(resultFuture.succeeded());

        // Test addQueueReactive
        Future<Void> queueFuture = service.addQueueReactive("id", null);
        assertNotNull(queueFuture);
        queueFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(queueFuture.succeeded());

        // Test addEventStoreReactive
        Future<Void> eventStoreFuture = service.addEventStoreReactive("id", null);
        assertNotNull(eventStoreFuture);
        eventStoreFuture.toCompletionStage().toCompletableFuture().get();
        assertTrue(eventStoreFuture.succeeded());

        // Test getAllActiveSetupIdsReactive
        Future<Set<String>> idsFuture = service.getAllActiveSetupIdsReactive();
        assertNotNull(idsFuture);
        Set<String> ids = idsFuture.toCompletionStage().toCompletableFuture().get();
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
        assertTrue(idsFuture.succeeded());
    }
}
