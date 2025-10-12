package dev.mars.peegeeq.api.lifecycle;

/**
 * Abstraction that allows modules to register lifecycle hooks with the manager
 * when they only see the DatabaseService API. Implemented by PgDatabaseService.
 */
public interface LifecycleHookRegistrar {
    void registerCloseHook(PeeGeeQCloseHook hook);
}

