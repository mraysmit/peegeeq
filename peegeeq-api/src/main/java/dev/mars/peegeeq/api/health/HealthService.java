package dev.mars.peegeeq.api.health;

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

import java.util.concurrent.CompletableFuture;

/**
 * Service interface for health check operations.
 * 
 * This interface is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific health check details.
 * Implementations should provide comprehensive health monitoring
 * for database, queues, and system resources.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public interface HealthService {
    
    /**
     * Gets the overall health status of the system.
     * This includes the status of all registered health checks.
     * 
     * @return The overall health status
     */
    OverallHealthInfo getOverallHealth();
    
    /**
     * Gets the overall health status of the system asynchronously.
     * 
     * @return A CompletableFuture containing the overall health status
     */
    CompletableFuture<OverallHealthInfo> getOverallHealthAsync();
    
    /**
     * Gets the health status of a specific component.
     * 
     * @param componentName The name of the component to check
     * @return The health status of the component, or null if not found
     */
    HealthStatusInfo getComponentHealth(String componentName);
    
    /**
     * Gets the health status of a specific component asynchronously.
     * 
     * @param componentName The name of the component to check
     * @return A CompletableFuture containing the health status
     */
    CompletableFuture<HealthStatusInfo> getComponentHealthAsync(String componentName);
    
    /**
     * Returns true if the overall system is healthy.
     * This is a convenience method that checks if all components are healthy.
     * 
     * @return true if the system is healthy, false otherwise
     */
    boolean isHealthy();
    
    /**
     * Returns true if the health service is currently running.
     * 
     * @return true if running, false otherwise
     */
    boolean isRunning();
}

