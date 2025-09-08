package dev.mars.peegeeq.db.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class DatabaseTemplateManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseTemplateManager.class);

    public void createDatabaseFromTemplate(Connection adminConnection,
                                         String newDatabaseName,
                                         String templateName,
                                         String encoding,
                                         Map<String, String> options) throws SQLException {

        logger.info("DatabaseTemplateManager.createDatabaseFromTemplate called for database: {}", newDatabaseName);

        // Check if database already exists
        if (databaseExists(adminConnection, newDatabaseName)) {
            logger.info("Database {} already exists, dropping and recreating", newDatabaseName);
            dropDatabase(adminConnection, newDatabaseName);
        } else {
            logger.info("Database {} does not exist, proceeding with creation", newDatabaseName);
        }

        String sql = buildCreateDatabaseSql(newDatabaseName, templateName, encoding, options);
        logger.info("Creating database with SQL: {}", sql);

        try (var stmt = adminConnection.createStatement()) {
            logger.info("About to execute SQL: {}", sql);
            stmt.execute(sql);
            logger.info("SQL executed successfully");
        } catch (SQLException e) {
            // Check if this is a database conflict (expected in concurrent scenarios)
            if (isDatabaseConflictError(e)) {
                logger.debug("🚫 EXPECTED: Database creation conflict - {}", e.getMessage());
            } else {
                logger.error("Failed to execute SQL: {} - Error: {}", sql, e.getMessage());
            }
            throw e;
        }

        logger.info("Database {} created successfully", newDatabaseName);
    }

    /**
     * Check if the SQLException is a database conflict error (expected in concurrent scenarios).
     */
    private boolean isDatabaseConflictError(SQLException e) {
        if (e == null) return false;

        String message = e.getMessage();
        return message != null &&
               (message.contains("duplicate key value violates unique constraint \"pg_database_datname_index\"") ||
                message.contains("already exists"));
    }

    public void dropDatabase(Connection adminConnection, String databaseName) throws SQLException {
        logger.info("Dropping database: {}", databaseName);

        // Terminate active connections to the database first
        String terminateConnectionsSql =
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
            "WHERE datname = ? AND pid <> pg_backend_pid()";

        try (var stmt = adminConnection.prepareStatement(terminateConnectionsSql)) {
            stmt.setString(1, databaseName);
            var resultSet = stmt.executeQuery();
            int terminatedConnections = 0;
            while (resultSet.next()) {
                terminatedConnections++;
            }
            if (terminatedConnections > 0) {
                logger.info("Terminated {} active connections to database {}", terminatedConnections, databaseName);
            }
        }

        // Wait a brief moment for connections to fully terminate
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drop the database
        String dropSql = "DROP DATABASE IF EXISTS " + databaseName;
        try (var stmt = adminConnection.createStatement()) {
            stmt.execute(dropSql);
        }

        logger.info("Database {} dropped successfully", databaseName);
    }

    public boolean databaseExists(Connection adminConnection, String databaseName) throws SQLException {
        String checkSql = "SELECT 1 FROM pg_database WHERE datname = ?";
        try (var stmt = adminConnection.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String buildCreateDatabaseSql(String dbName, String template,
                                        String encoding, Map<String, String> options) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DATABASE ").append(dbName);

        if (template != null) {
            sql.append(" TEMPLATE ").append(template);
        }

        if (encoding != null) {
            sql.append(" ENCODING '").append(encoding).append("'");
        }

        return sql.toString();
    }
}