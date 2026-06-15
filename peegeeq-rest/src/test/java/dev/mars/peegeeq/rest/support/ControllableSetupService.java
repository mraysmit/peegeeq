package dev.mars.peegeeq.rest.support;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import io.vertx.core.Future;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Hand-written test double for DatabaseSetupService.
 *
 * Allows each test to configure 1-2 methods while leaving the rest at safe defaults.
 * Builder-style factory methods keep per-test intent visible without anonymous classes
 * that must implement all 11 interface methods.
 *
 * Not Mockito. No mocking framework involved.
 *
 * Usage:
 *   ControllableSetupService.defaults()          all async methods return success
 *   ControllableSetupService.alwaysFailing("x")  all async methods return failure
 *   ControllableSetupService.defaults()
 *       .withGetSetupStatus(id -> Future.failedFuture(new SetupNotFoundException(id)))
 */
public class ControllableSetupService implements DatabaseSetupService {

    // --- Async method delegates ---
    private Function<DatabaseSetupRequest, Future<DatabaseSetupResult>> createCompleteSetup;
    private Function<String, Future<Void>> destroySetup;
    private Function<String, Future<DatabaseSetupStatus>> getSetupStatus;
    private Function<String, Future<DatabaseSetupResult>> getSetupResult;
    private BiFunction<String, QueueConfig, Future<Void>> addQueue;
    private BiFunction<String, EventStoreConfig, Future<Void>> addEventStore;
    private Supplier<Future<Set<String>>> getAllActiveSetupIds;

    // --- ServiceProvider method delegates ---
    private Function<String, SubscriptionService> subscriptionServiceForSetup;
    private Function<String, DeadLetterService> deadLetterServiceForSetup;
    private Function<String, HealthService> healthServiceForSetup;
    private Function<String, QueueFactoryProvider> queueFactoryProviderForSetup;

    private ControllableSetupService() {}

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * All async methods return succeeded futures with minimal valid responses.
     * All ServiceProvider methods return null (no active setup).
     */
    public static ControllableSetupService defaults() {
        ControllableSetupService s = new ControllableSetupService();
        DatabaseSetupResult minimalResult = new DatabaseSetupResult(
                "default-id", Map.of(), Map.of(), DatabaseSetupStatus.ACTIVE);
        s.createCompleteSetup = req -> Future.succeededFuture(minimalResult);
        s.destroySetup = id -> Future.succeededFuture();
        s.getSetupStatus = id -> Future.succeededFuture(DatabaseSetupStatus.ACTIVE);
        s.getSetupResult = id -> Future.succeededFuture(minimalResult);
        s.addQueue = (id, cfg) -> Future.succeededFuture();
        s.addEventStore = (id, cfg) -> Future.succeededFuture();
        s.getAllActiveSetupIds = () -> Future.succeededFuture(Set.of());
        s.subscriptionServiceForSetup = id -> null;
        s.deadLetterServiceForSetup = id -> null;
        s.healthServiceForSetup = id -> null;
        s.queueFactoryProviderForSetup = id -> null;
        return s;
    }

    /**
     * All async methods return failed futures with the given reason.
     * All ServiceProvider methods return null.
     */
    public static ControllableSetupService alwaysFailing(String reason) {
        ControllableSetupService s = new ControllableSetupService();
        RuntimeException cause = new RuntimeException(reason);
        s.createCompleteSetup = req -> Future.failedFuture(cause);
        s.destroySetup = id -> Future.failedFuture(cause);
        s.getSetupStatus = id -> Future.failedFuture(cause);
        s.getSetupResult = id -> Future.failedFuture(cause);
        s.addQueue = (id, cfg) -> Future.failedFuture(cause);
        s.addEventStore = (id, cfg) -> Future.failedFuture(cause);
        s.getAllActiveSetupIds = () -> Future.failedFuture(cause);
        s.subscriptionServiceForSetup = id -> null;
        s.deadLetterServiceForSetup = id -> null;
        s.healthServiceForSetup = id -> null;
        s.queueFactoryProviderForSetup = id -> null;
        return s;
    }

    // -------------------------------------------------------------------------
    // Builder-style with* methods  override individual delegates
    // -------------------------------------------------------------------------

    public ControllableSetupService withCreateCompleteSetup(
            Function<DatabaseSetupRequest, Future<DatabaseSetupResult>> fn) {
        this.createCompleteSetup = fn;
        return this;
    }

    public ControllableSetupService withDestroySetup(Function<String, Future<Void>> fn) {
        this.destroySetup = fn;
        return this;
    }

    public ControllableSetupService withGetSetupStatus(Function<String, Future<DatabaseSetupStatus>> fn) {
        this.getSetupStatus = fn;
        return this;
    }

    public ControllableSetupService withGetSetupResult(Function<String, Future<DatabaseSetupResult>> fn) {
        this.getSetupResult = fn;
        return this;
    }

    public ControllableSetupService withAddQueue(BiFunction<String, QueueConfig, Future<Void>> fn) {
        this.addQueue = fn;
        return this;
    }

    public ControllableSetupService withAddEventStore(BiFunction<String, EventStoreConfig, Future<Void>> fn) {
        this.addEventStore = fn;
        return this;
    }

    public ControllableSetupService withGetAllActiveSetupIds(Supplier<Future<Set<String>>> fn) {
        this.getAllActiveSetupIds = fn;
        return this;
    }

    public ControllableSetupService withSubscriptionServiceForSetup(Function<String, SubscriptionService> fn) {
        this.subscriptionServiceForSetup = fn;
        return this;
    }

    public ControllableSetupService withDeadLetterServiceForSetup(Function<String, DeadLetterService> fn) {
        this.deadLetterServiceForSetup = fn;
        return this;
    }

    public ControllableSetupService withHealthServiceForSetup(Function<String, HealthService> fn) {
        this.healthServiceForSetup = fn;
        return this;
    }

    public ControllableSetupService withQueueFactoryProviderForSetup(Function<String, QueueFactoryProvider> fn) {
        this.queueFactoryProviderForSetup = fn;
        return this;
    }

    // -------------------------------------------------------------------------
    // DatabaseSetupService implementation
    // -------------------------------------------------------------------------

    @Override
    public Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        return createCompleteSetup.apply(request);
    }

    @Override
    public Future<Void> destroySetup(String setupId) {
        return this.destroySetup.apply(setupId);
    }

    @Override
    public Future<DatabaseSetupStatus> getSetupStatus(String setupId) {
        return this.getSetupStatus.apply(setupId);
    }

    @Override
    public Future<DatabaseSetupResult> getSetupResult(String setupId) {
        return this.getSetupResult.apply(setupId);
    }

    @Override
    public Future<Void> addQueue(String setupId, QueueConfig queueConfig) {
        return this.addQueue.apply(setupId, queueConfig);
    }

    @Override
    public Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        return this.addEventStore.apply(setupId, eventStoreConfig);
    }

    @Override
    public Future<Void> removeEventStore(String setupId, String storeName) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Set<String>> getAllActiveSetupIds() {
        return this.getAllActiveSetupIds.get();
    }

    // close() and addFactoryRegistration() use the inherited defaults from DatabaseSetupService

    @Override
    public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        // no-op  test double does not process factory registrations
    }

    // -------------------------------------------------------------------------
    // ServiceProvider implementation
    // -------------------------------------------------------------------------

    @Override
    public SubscriptionService getSubscriptionServiceForSetup(String setupId) {
        return subscriptionServiceForSetup.apply(setupId);
    }

    @Override
    public DeadLetterService getDeadLetterServiceForSetup(String setupId) {
        return deadLetterServiceForSetup.apply(setupId);
    }

    @Override
    public HealthService getHealthServiceForSetup(String setupId) {
        return healthServiceForSetup.apply(setupId);
    }

    @Override
    public QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) {
        return queueFactoryProviderForSetup.apply(setupId);
    }
}
