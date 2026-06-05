package dev.mars.peegeeq.rest.support;

import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import io.vertx.core.Future;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Hand-written test double for HealthService.
 *
 * Allows each test to configure individual method delegates while leaving the
 * rest at safe defaults. Mirrors the builder pattern of ControllableSetupService.
 *
 * Not Mockito. No mocking framework involved.
 *
 * Usage:
 *   ControllableHealthService.alwaysFailing("health unavailable")
 *       — all async methods return failed futures
 *
 *   ControllableHealthService.alwaysFailing("health unavailable")
 *       .withGetComponentHealthAsync(name -> Future.failedFuture(...))
 *       — override one method while the rest stay as failed futures
 */
public class ControllableHealthService implements HealthService {

    private Supplier<Future<OverallHealthInfo>> getOverallHealthAsync;
    private Function<String, Future<HealthStatusInfo>> getComponentHealthAsync;

    private ControllableHealthService() {}

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * All async methods return failed futures with the given reason message.
     * Sync convenience methods return safe no-op values.
     */
    public static ControllableHealthService alwaysFailing(String reason) {
        ControllableHealthService s = new ControllableHealthService();
        RuntimeException cause = new RuntimeException(reason);
        s.getOverallHealthAsync = () -> Future.failedFuture(cause);
        s.getComponentHealthAsync = name -> Future.failedFuture(cause);
        return s;
    }

    // -------------------------------------------------------------------------
    // Builder-style with* methods — override individual delegates
    // -------------------------------------------------------------------------

    public ControllableHealthService withGetOverallHealthAsync(Supplier<Future<OverallHealthInfo>> fn) {
        this.getOverallHealthAsync = fn;
        return this;
    }

    public ControllableHealthService withGetComponentHealthAsync(Function<String, Future<HealthStatusInfo>> fn) {
        this.getComponentHealthAsync = fn;
        return this;
    }

    // -------------------------------------------------------------------------
    // HealthService implementation
    // -------------------------------------------------------------------------

    @Override
    public Future<OverallHealthInfo> getOverallHealthAsync() {
        return getOverallHealthAsync.get();
    }

    @Override
    public Future<HealthStatusInfo> getComponentHealthAsync(String componentName) {
        return getComponentHealthAsync.apply(componentName);
    }

    /** Not used by handler — returns null as a safe no-op. */
    @Override
    public OverallHealthInfo getOverallHealth() {
        return null;
    }

    /** Not used by handler — returns null as a safe no-op. */
    @Override
    public HealthStatusInfo getComponentHealth(String componentName) {
        return null;
    }

    /** Not used by handler — returns false as a safe no-op. */
    @Override
    public boolean isHealthy() {
        return false;
    }

    /** Not used by handler — returns false as a safe no-op. */
    @Override
    public boolean isRunning() {
        return false;
    }
}
