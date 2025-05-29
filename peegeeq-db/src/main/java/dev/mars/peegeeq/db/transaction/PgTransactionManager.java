package dev.mars.peegeeq.db.transaction;

import dev.mars.peegeeq.db.client.PgClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager for PostgreSQL transactions.
 */
public class PgTransactionManager {
    private static final Logger logger = LoggerFactory.getLogger(PgTransactionManager.class);

    private final PgClient pgClient;

    /**
     * Creates a new PgTransactionManager.
     *
     * @param pgClient The PgClient to use for connections
     */
    public PgTransactionManager(PgClient pgClient) {
        this.pgClient = pgClient;
    }

    /**
     * Creates a new transaction with the default isolation level.
     *
     * @return A new transaction
     * @throws SQLException If creating the transaction fails
     */
    public PgTransaction beginTransaction() throws SQLException {
        return beginTransaction(Connection.TRANSACTION_READ_COMMITTED);
    }

    /**
     * Creates a new transaction with the specified isolation level.
     *
     * @param isolationLevel The isolation level to use
     * @return A new transaction
     * @throws SQLException If creating the transaction fails
     */
    public PgTransaction beginTransaction(int isolationLevel) throws SQLException {
        Connection connection = pgClient.getConnection();

        // Save the original auto-commit state
        boolean originalAutoCommit = connection.getAutoCommit();

        // Set auto-commit to false to begin a transaction
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }

        // Set the isolation level
        connection.setTransactionIsolation(isolationLevel);

        return new DefaultPgTransaction(connection, originalAutoCommit);
    }

    /**
     * Executes a function within a transaction, committing if the function completes successfully
     * or rolling back if an exception is thrown.
     *
     * @param transactionFunction The function to execute within a transaction
     * @throws SQLException If a database error occurs
     */
    public void executeInTransaction(TransactionConsumer transactionFunction) throws SQLException {
        try (PgTransaction transaction = beginTransaction()) {
            transactionFunction.accept(transaction);
            transaction.commit();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Error executing in transaction", e);
        }
    }

    /**
     * Executes a function within a transaction, committing if the function completes successfully
     * or rolling back if an exception is thrown, and returns a result.
     *
     * @param transactionFunction The function to execute within a transaction
     * @param <T> The type of the result
     * @return The result of the function
     * @throws SQLException If a database error occurs
     */
    public <T> T executeInTransactionWithResult(TransactionFunction<T> transactionFunction) throws SQLException {
        try (PgTransaction transaction = beginTransaction()) {
            T result = transactionFunction.apply(transaction);
            transaction.commit();
            return result;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Error executing in transaction", e);
        }
    }

    /**
     * Default implementation of PgTransaction.
     */
    private static class DefaultPgTransaction implements PgTransaction {
        private final Connection connection;
        private final boolean originalAutoCommit;
        private boolean active = true;
        private final Map<String, Savepoint> savepoints = new HashMap<>();

        public DefaultPgTransaction(Connection connection, boolean originalAutoCommit) {
            this.connection = connection;
            this.originalAutoCommit = originalAutoCommit;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public void commit() throws SQLException {
            if (!active) {
                throw new SQLException("Transaction is not active");
            }

            connection.commit();
            active = false;
        }

        @Override
        public void rollback() throws SQLException {
            if (!active) {
                throw new SQLException("Transaction is not active");
            }

            connection.rollback();
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void setSavepoint(String name) throws SQLException {
            if (!active) {
                throw new SQLException("Transaction is not active");
            }

            Savepoint savepoint = connection.setSavepoint(name);
            savepoints.put(name, savepoint);
        }

        @Override
        public void rollbackToSavepoint(String name) throws SQLException {
            if (!active) {
                throw new SQLException("Transaction is not active");
            }

            Savepoint savepoint = savepoints.get(name);
            if (savepoint == null) {
                throw new SQLException("Savepoint not found: " + name);
            }

            connection.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(String name) throws SQLException {
            if (!active) {
                throw new SQLException("Transaction is not active");
            }

            Savepoint savepoint = savepoints.get(name);
            if (savepoint == null) {
                throw new SQLException("Savepoint not found: " + name);
            }

            connection.releaseSavepoint(savepoint);
            savepoints.remove(name);
        }

        @Override
        public void close() throws Exception {
            try {
                if (active) {
                    try {
                        connection.rollback();
                    } catch (SQLException e) {
                        logger.warn("Error rolling back transaction", e);
                    }
                }

                // Restore the original auto-commit state
                connection.setAutoCommit(originalAutoCommit);
            } finally {
                connection.close();
                active = false;
            }
        }
    }

    /**
     * Functional interface for consuming a transaction.
     */
    @FunctionalInterface
    public interface TransactionConsumer {
        void accept(PgTransaction transaction) throws Exception;
    }

    /**
     * Functional interface for applying a function to a transaction and returning a result.
     */
    @FunctionalInterface
    public interface TransactionFunction<T> {
        T apply(PgTransaction transaction) throws Exception;
    }
}
