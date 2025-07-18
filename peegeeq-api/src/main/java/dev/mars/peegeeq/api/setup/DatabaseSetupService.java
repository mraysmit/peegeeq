package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.QueueFactory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface DatabaseSetupService {
    CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request);
    CompletableFuture<Void> destroySetup(String setupId);
    CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId);
}