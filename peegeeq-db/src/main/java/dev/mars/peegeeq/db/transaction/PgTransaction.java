package dev.mars.peegeeq.db.transaction;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Represents a PostgreSQL transaction.
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