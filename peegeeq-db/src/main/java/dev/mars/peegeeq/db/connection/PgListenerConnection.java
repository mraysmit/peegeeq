package dev.mars.peegeeq.db.connection;

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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL LISTEN/NOTIFY connection wrapper (JDBC removed).
 *
 * This class previously provided PostgreSQL LISTEN/NOTIFY functionality using JDBC.
 * All JDBC functionality has been removed to make this a pure Vert.x project.
 *
 * For LISTEN/NOTIFY functionality, use Vert.x reactive patterns with Pool.getConnection().
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 * @deprecated JDBC functionality removed. Use Vert.x reactive patterns instead.
 */
@Deprecated
public class PgListenerConnection implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PgListenerConnection.class);

    /**
     * Creates a new PgListenerConnection (deprecated - JDBC removed).
     *
     * @deprecated JDBC functionality removed. Use Vert.x reactive patterns instead.
     * @throws UnsupportedOperationException Always thrown since JDBC was removed
     */
    @Deprecated
    public PgListenerConnection() {
        throw new UnsupportedOperationException(
            "PgListenerConnection JDBC functionality has been removed. Use Vert.x reactive patterns for LISTEN/NOTIFY operations.");
    }

    /**
     * Closes the connection (no-op since JDBC was removed).
     */
    @Override
    public void close() throws Exception {
        // No-op since JDBC functionality was removed
        LOGGER.debug("PgListenerConnection.close() called - no-op since JDBC was removed");
    }
}
