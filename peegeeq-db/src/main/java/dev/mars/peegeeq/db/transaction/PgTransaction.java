package dev.mars.peegeeq.db.transaction;

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
 * Represents a PostgreSQL transaction (JDBC removed).
 *
 * This interface previously provided JDBC-based transaction management.
 * All JDBC functionality has been removed to make this a pure Vert.x project.
 *
 * For transaction management, use Vert.x reactive patterns with Pool.withTransaction().
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 * @deprecated JDBC functionality removed. Use Vert.x reactive patterns instead.
 */
@Deprecated
public interface PgTransaction extends AutoCloseable {

    /**
     * Checks if the transaction is active (deprecated - JDBC removed).
     *
     * @deprecated JDBC functionality removed. Use Vert.x reactive patterns instead.
     * @return Always throws UnsupportedOperationException
     * @throws UnsupportedOperationException Always thrown since JDBC was removed
     */
    @Deprecated
    default boolean isActive() {
        throw new UnsupportedOperationException(
            "JDBC transaction functionality has been removed. Use Vert.x reactive patterns with Pool.withTransaction().");
    }
    
    /**
     * Closes the transaction, rolling back if it is still active.
     *
     * @throws Exception If closing the transaction fails
     */
    @Override
    void close() throws Exception;
}