package dev.mars.peegeeq.db.health;

/**
 * Interface for health check implementations.
 */
@FunctionalInterface
public interface HealthCheck {
    /**
     * Performs the health check.
     * 
     * @return The health status result
     */
    HealthStatus check();
}
