package dev.mars.peegeeq.db.setup;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import dev.mars.peegeeq.api.info.PeeGeeQInfoCodes;

public class DatabaseTemplateManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseTemplateManager.class);

    private final Vertx vertx;

    public DatabaseTemplateManager(Vertx vertx) {
        this.vertx = vertx;
    }

    public Future<Void> createDatabaseFromTemplate(String host, int port, String username, String password,
                                                   String newDatabaseName,
                                                   String templateName,
                                                   String encoding,
                                                   Map<String, String> options) {

        logger.info("DatabaseTemplateManager.createDatabaseFromTemplate called for database: {}", newDatabaseName);

        // Connect to postgres system database for admin operations
        PgConnectOptions adminOptions = new PgConnectOptions()
            .setHost(host)
            .setPort(port)
            .setDatabase("postgres")  // System database
            .setUser(username)
            .setPassword(password);

        Pool adminPool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(1))
            .connectingTo(adminOptions)
            .using(vertx)
            .build();

        return adminPool.withConnection(connection -> {
            return databaseExists(connection, newDatabaseName)
                .compose(exists -> {
                    if (exists) {
                        logger.info("Database {} already exists, dropping and recreating", newDatabaseName);
                        return dropDatabase(connection, newDatabaseName);
                    } else {
                        logger.info("Database {} does not exist, proceeding with creation", newDatabaseName);
                        return Future.succeededFuture();
                    }
                })
                .compose(v -> {
                    String sql = buildCreateDatabaseSql(newDatabaseName, templateName, encoding, options);
                    logger.info("Creating database with SQL: {}", sql);

                    return connection.query(sql).execute()
                        .map(rowSet -> (Void) null)
                        .onSuccess(v2 -> logger.info("[{}] Database {} created successfully", PeeGeeQInfoCodes.DATABASE_CREATED, newDatabaseName))
                        .recover(error -> {
                            if (isDatabaseConflictError(error)) {
                                logger.debug("🚫 EXPECTED: Database creation conflict - {}", error.getMessage());
                                return Future.succeededFuture();
                            } else {
                                logger.error("Failed to execute SQL: {} - Error: {}", sql, error.getMessage());
                                return Future.failedFuture(error);
                            }
                        });
                });
        })
        .onComplete(ar -> adminPool.close());
    }

    /**
     * Check if the error is a database conflict error (expected in concurrent scenarios).
     */
    private boolean isDatabaseConflictError(Throwable e) {
        if (e == null) return false;

        String message = e.getMessage();
        return message != null &&
               (message.contains("duplicate key value violates unique constraint \"pg_database_datname_index\"") ||
                message.contains("already exists"));
    }

    public Future<Void> dropDatabase(SqlConnection connection, String databaseName) {
        logger.info("Dropping database: {}", databaseName);

        // Terminate active connections to the database first
        String terminateConnectionsSql =
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
            "WHERE datname = $1 AND pid <> pg_backend_pid()";

        return connection.preparedQuery(terminateConnectionsSql)
            .execute(Tuple.of(databaseName))
            .compose(rowSet -> {
                int terminatedConnections = rowSet.size();
                if (terminatedConnections > 0) {
                    logger.info("Terminated {} active connections to database {}", terminatedConnections, databaseName);
                }

                // Wait a brief moment for connections to fully terminate using Promise
                return Future.<Void>future(promise -> {
                    vertx.setTimer(100, id -> promise.complete());
                })
                .compose(v -> {
                    // Drop the database
                    String dropSql = "DROP DATABASE IF EXISTS " + databaseName;
                    return connection.query(dropSql).execute();
                });
            })
            .map(rowSet -> {
                logger.info("Database {} dropped successfully", databaseName);
                return null;
            });
    }

    public Future<Boolean> databaseExists(SqlConnection connection, String databaseName) {
        String checkSql = "SELECT 1 FROM pg_database WHERE datname = $1";
        return connection.preparedQuery(checkSql)
            .execute(Tuple.of(databaseName))
            .map(rowSet -> rowSet.size() > 0);
    }

    public Future<Void> dropDatabaseFromAdmin(String host, int port, String username, String password, String databaseName) {
        logger.info("Dropping database: {}", databaseName);

        // Connect to postgres system database for admin operations
        PgConnectOptions adminOptions = new PgConnectOptions()
            .setHost(host)
            .setPort(port)
            .setDatabase("postgres")  // System database
            .setUser(username)
            .setPassword(password);

        Pool adminPool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(1))
            .connectingTo(adminOptions)
            .using(vertx)
            .build();

        return adminPool.withConnection(connection -> {
            return dropDatabase(connection, databaseName);
        })
        .onComplete(ar -> adminPool.close());
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