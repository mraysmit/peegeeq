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

import io.vertx.sqlclient.Pool;

/**
 * Interface for providing access to the underlying Vert.x SQL client pool.
 * 
 * This interface enables components that need direct access to the database
 * connection pool (e.g., for creating VertxPoolAdapter) to obtain it through
 * dependency injection rather than reflection.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public interface PoolProvider {
    
    /**
     * Gets the underlying Vert.x SQL client pool.
     * 
     * @return The Pool instance used by this provider
     */
    Pool getPool();
}

