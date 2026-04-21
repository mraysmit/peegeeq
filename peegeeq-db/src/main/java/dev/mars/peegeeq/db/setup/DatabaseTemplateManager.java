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
import java.util.Set;

import dev.mars.peegeeq.api.info.PeeGeeQInfoCodes;
import dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator;

public class DatabaseTemplateManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseTemplateManager.class);

    private static final Set<String> ALLOWED_ENCODINGS = Set.of(
        "UTF8", "SQL_ASCII", "LATIN1", "LATIN2", "LATIN3", "LATIN4", "LATIN5",
        "LATIN6", "LATIN7", "LATIN8", "LATIN9", "LATIN10",
        "WIN1250", "WIN1251", "WIN1252", "WIN1253", "WIN1254",
        "WIN1255", "WIN1256", "WIN1257", "WIN1258",
        "EUC_JP", "EUC_CN", "EUC_KR", "EUC_TW",
        "SJIS", "BIG5", "GBK", "GB18030",
        "JOHAB", "KOI8R", "KOI8U", "ISO_8859_5", "ISO_8859_6",
        "ISO_8859_7", "ISO_8859_8", "MULE_INTERNAL"
    );

    private final Vertx vertx;

    public DatabaseTemplateManager(Vertx vertx) {
        this.vertx = vertx;
    }

    public Future<Void> createDatabaseFromTemplate(String host, int port, String username, String password,
                                                   String newDatabaseName,
                                                   String templateName,
                                                   String encoding,
                                                   Map<String, String> options) {

        // Validate identifiers to prevent SQL injection (C1 remediation)
        validateDatabaseIdentifier(newDatabaseName, "database");
        if (templateName != null) {
            validateDatabaseIdentifier(templateName, "template");
        }
        if (encoding != null) {
            validateEncoding(encoding);
        }

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
                        return Future.<Void>succeededFuture();
                    }
                })
                .compose(v -> {
                    String sql = buildCreateDatabaseSql(newDatabaseName, templateName, encoding, options);
                    logger.info("Creating database with SQL: {}", sql);

                    return connection.query(sql).execute()
                        .map(rowSet -> (Void) null)
                        .onSuccess(v2 -> logger.info("[{}] Database {} created successfully", PeeGeeQInfoCodes.DATABASE_CREATED, newDatabaseName))
                        .transform(ar -> {
                            if (ar.failed()) {
                                if (isDatabaseConflictError(ar.cause())) {
                                    logger.debug("\uD83D\uDEAB EXPECTED: Database creation conflict - {}", ar.cause().getMessage());
                                    return Future.<Void>succeededFuture();
                                } else {
                                    logger.error("Failed to execute SQL: {} - Error: {}", sql, ar.cause().getMessage());
                                    return Future.<Void>failedFuture(ar.cause());
                                }
                            }
                            return Future.<Void>succeededFuture();
                        });
                });
        })
                .eventually(adminPool::close);
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
        // Validate identifier to prevent SQL injection (H5 remediation)
        validateDatabaseIdentifier(databaseName, "database");

        logger.info("Dropping database: {}", databaseName);

        // Poll pg_stat_activity until all connections are confirmed gone, then drop.
        // pg_terminate_backend() is asynchronous: it signals backends to exit but returns
        // before they have fully disconnected. A fixed delay is not a correctness guarantee.
        // Each poll iteration also re-terminates any new connections that appeared since
        // the previous iteration, handling pools that auto-reconnect.
        return dropWhenDrained(connection, databaseName, 0);
    }

    /**
     * Terminate all remaining sessions on {@code dbName}, confirm via {@code pg_stat_activity}
     * that the count has reached zero, then issue {@code DROP DATABASE}.
     *
     * <p>Retries up to 20 times with a 100 ms pause between attempts (~2 s total).
     * Re-terminates on every attempt so that auto-reconnecting connection pools are handled.
     *
     * @param conn    admin connection to the {@code postgres} system database
     * @param dbName  identifier already validated against SQL injection
     * @param attempt current retry count (starts at 0)
     */
    private Future<Void> dropWhenDrained(SqlConnection conn, String dbName, int attempt) {
        if (attempt > 20) {
            return Future.failedFuture(
                "Timed out waiting for connections to drain from database: " + dbName);
        }

        String terminateSql =
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
            "WHERE datname = $1 AND pid <> pg_backend_pid()";

        String checkSql =
            "SELECT COUNT(*) AS n FROM pg_stat_activity " +
            "WHERE datname = $1 AND pid <> pg_backend_pid()";

        return conn.preparedQuery(terminateSql)
            .execute(Tuple.of(dbName))
            .compose(terminated -> {
                if (terminated.size() > 0) {
                    logger.info("Terminated {} active connections to database {}",
                        terminated.size(), dbName);
                }
                return conn.preparedQuery(checkSql).execute(Tuple.of(dbName));
            })
            .compose(rows -> {
                long remaining = rows.iterator().next().getLong("n");
                if (remaining == 0) {
                    // All connections gone — safe to drop.
                    String dropSql = "DROP DATABASE IF EXISTS \"" + dbName + "\"";
                    logger.info("All connections drained from {}, issuing DROP DATABASE", dbName);
                    return conn.query(dropSql).execute()
                        .map(rs -> {
                            logger.info("Database {} dropped successfully", dbName);
                            return (Void) null;
                        });
                }
                logger.debug("Database {} still has {} connection(s), retrying (attempt {})",
                    dbName, remaining, attempt + 1);
                return Future.<Void>future(p -> vertx.setTimer(100, id -> p.complete()))
                    .compose(ignored -> dropWhenDrained(conn, dbName, attempt + 1));
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
        .eventually(adminPool::close);
    }

    private String buildCreateDatabaseSql(String dbName, String template,
                                        String encoding, Map<String, String> options) {
        // Identifiers and encoding already validated in createDatabaseFromTemplate()
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DATABASE \"").append(dbName).append("\"");

        if (template != null) {
            sql.append(" TEMPLATE \"").append(template).append("\"");
        }

        if (encoding != null) {
            sql.append(" ENCODING '").append(encoding).append("'");
        }

        return sql.toString();
    }

    private static void validateDatabaseIdentifier(String identifier, String identifierType) {
        PostgreSqlIdentifierValidator.validate(identifier, identifierType);
    }

    private static void validateEncoding(String encoding) {
        if (!ALLOWED_ENCODINGS.contains(encoding.toUpperCase())) {
            throw new IllegalArgumentException(
                "Unsupported encoding: '" + encoding + "'. Allowed encodings: " + ALLOWED_ENCODINGS);
        }
    }
}