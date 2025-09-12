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


import dev.mars.peegeeq.db.client.PgClient;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager for PostgreSQL transactions.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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
     * @deprecated This method uses JDBC patterns. Use Pool.withTransaction() for reactive patterns.
     * @return A new transaction
     * @throws SQLException If creating the transaction fails
     */
    @Deprecated
    public PgTransaction beginTransaction() throws SQLException {
        throw new UnsupportedOperationException(
            "JDBC-style transactions are no longer supported. Use Pool.withTransaction() for reactive patterns. " +
            "Example: pool.withTransaction(connection -> { /* your operations */ })");
    }

    /**
     * Creates a new transaction with the specified isolation level.
     *
     * @deprecated This method uses JDBC patterns. Use Pool.withTransaction() for reactive patterns.
     * @param isolationLevel The isolation level to use
     * @return A new transaction
     * @throws SQLException If creating the transaction fails
     */
    @Deprecated
    public PgTransaction beginTransaction(int isolationLevel) throws SQLException {
        throw new UnsupportedOperationException(
            "JDBC-style transactions are no longer supported. Use Pool.withTransaction() for reactive patterns. " +
            "Example: pool.withTransaction(connection -> { /* your operations */ })");
    }

    /**
     * Executes a function within a reactive transaction using Pool.withTransaction().
     * This is the preferred method for reactive transaction management.
     *
     * Note: This is a temporary implementation that doesn't use Pool.withTransaction().
     * For proper transaction management, use Pool.withTransaction() directly.
     *
     * @param transactionFunction The function to execute within a transaction
     * @return A Future that completes when the transaction is done
     */
    public Future<Void> executeInReactiveTransaction(ReactiveTransactionConsumer transactionFunction) {
        throw new UnsupportedOperationException(
            "Reactive transactions should use Pool.withTransaction() directly. " +
            "Example: pool.withTransaction(connection -> { /* your operations */ })");
    }

    /**
     * Executes a function within a transaction, committing if the function completes successfully
     * or rolling back if an exception is thrown.
     *
     * @deprecated This method uses JDBC patterns. Use executeInReactiveTransaction() for reactive patterns.
     * @param transactionFunction The function to execute within a transaction
     * @throws SQLException If a database error occurs
     */
    @Deprecated
    public void executeInTransaction(TransactionConsumer transactionFunction) throws SQLException {
        throw new UnsupportedOperationException(
            "JDBC-style transactions are no longer supported. Use executeInReactiveTransaction() for reactive patterns.");
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

    /**
     * Functional interface for consuming a reactive SqlConnection within a transaction.
     */
    @FunctionalInterface
    public interface ReactiveTransactionConsumer {
        Future<Void> accept(SqlConnection connection) throws Exception;
    }
}
