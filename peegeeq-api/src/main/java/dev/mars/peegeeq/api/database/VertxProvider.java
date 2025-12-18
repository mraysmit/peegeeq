package dev.mars.peegeeq.api.database;

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

import io.vertx.core.Vertx;

/**
 * Interface for providing access to the underlying Vert.x instance.
 * 
 * This interface enables components that need direct access to Vert.x
 * (e.g., for LISTEN/NOTIFY timers, periodic tasks) to obtain it through
 * dependency injection rather than reflection.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public interface VertxProvider {
    
    /**
     * Gets the underlying Vert.x instance.
     * 
     * @return The Vert.x instance used by this provider
     */
    Vertx getVertx();
}

