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


import java.sql.Connection;
import java.sql.SQLException;

/**
 * Represents a PostgreSQL transaction.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public interface PgTransaction extends AutoCloseable {
    
    /**
     * Gets the connection associated with this transaction.
     *
     * @return The connection
     */
    Connection getConnection();
    
    /**
     * Commits the transaction.
     *
     * @throws SQLException If the commit fails
     */
    void commit() throws SQLException;
    
    /**
     * Rolls back the transaction.
     *
     * @throws SQLException If the rollback fails
     */
    void rollback() throws SQLException;
    
    /**
     * Checks if the transaction is active.
     *
     * @return True if the transaction is active, false otherwise
     * @throws SQLException If the check fails
     */
    boolean isActive() throws SQLException;
    
    /**
     * Sets a savepoint with the given name.
     *
     * @param name The name of the savepoint
     * @throws SQLException If setting the savepoint fails
     */
    void setSavepoint(String name) throws SQLException;
    
    /**
     * Rolls back to the savepoint with the given name.
     *
     * @param name The name of the savepoint
     * @throws SQLException If the rollback fails
     */
    void rollbackToSavepoint(String name) throws SQLException;
    
    /**
     * Releases the savepoint with the given name.
     *
     * @param name The name of the savepoint
     * @throws SQLException If releasing the savepoint fails
     */
    void releaseSavepoint(String name) throws SQLException;
    
    /**
     * Closes the transaction, rolling back if it is still active.
     *
     * @throws Exception If closing the transaction fails
     */
    @Override
    void close() throws Exception;
}