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

/**
 * Represents the health state of a component.
 * 
 * This enum is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific health check details.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public enum ComponentHealthState {
    /**
     * Component is fully operational.
     */
    HEALTHY,
    
    /**
     * Component is operational but with reduced capacity or performance.
     */
    DEGRADED,
    
    /**
     * Component is not operational.
     */
    UNHEALTHY
}

