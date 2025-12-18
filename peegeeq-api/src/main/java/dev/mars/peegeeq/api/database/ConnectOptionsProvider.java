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

import io.vertx.pgclient.PgConnectOptions;

/**
 * Interface for providing PostgreSQL connection options for dedicated connections.
 * 
 * This interface enables components that need to create dedicated (non-pooled)
 * connections (e.g., for LISTEN/NOTIFY) to obtain connection options through
 * dependency injection rather than reflection.
 * 
 * Dedicated connections are needed for PostgreSQL LISTEN/NOTIFY because:
 * 1. LISTEN requires a persistent connection that stays open
 * 2. Pooled connections may be returned to the pool and reused
 * 3. Notifications are delivered to the specific connection that issued LISTEN
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-18
 * @version 1.0
 */
public interface ConnectOptionsProvider {
    
    /**
     * Gets the PostgreSQL connection options for creating dedicated connections.
     * 
     * @return The PgConnectOptions for dedicated connections
     */
    PgConnectOptions getConnectOptions();
}

