package dev.mars.peegeeq.db.connection;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgConnectionManagerDurabilityIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgConnectionManagerDurabilityIntegrationTest.class);
    private static final String SCHEMA = PostgreSQLTestConstants.TEST_SCHEMA;
    private static final String SERVICE = "durability";
    private static final String REPLICA_DATA = "/var/lib/postgresql/replica";
    private static Network network;
    private static GenericContainer<?> haproxy;
    private static PostgreSQLContainer primary;
    private static GenericContainer<?> standby2;
    private static GenericContainer<?> standby3;
    private PgConnectionManager connectionManager;
    private Pool observer;
    private Vertx vertx;
    private String topic;

    @BeforeAll
    static void startReplication() throws Exception {
        network = Network.newNetwork();
        primary = PostgreSQLTestConstants.createStandardContainer()
            .withNetwork(network)
            .withNetworkAliases("pg_primary")
            .withInitScript("streaming-replication-primary-init.sql")
            .withCommand("postgres", "-c", "wal_level=replica",
                "-c", "max_wal_senders=5", "-c", "wal_keep_size=64MB");
        primary.start();
        PeeGeeQTestSchemaInitializer.initializeSchema(primary, SCHEMA, SchemaComponent.QUEUE_ALL);
        requireSuccess(primary.execInContainer("sh", "-c",
            "echo 'host replication replicator 0.0.0.0/0 scram-sha-256' >> \"$PGDATA/pg_hba.conf\""));
        requireSuccess(primary.execInContainer("psql", "-U", primary.getUsername(),
            "-d", primary.getDatabaseName(), "-c", "SELECT pg_reload_conf()"));
        standby2 = startStandby("pg-node-2");
        standby3 = startStandby("pg-node-3");
        requireSuccess(primary.execInContainer("psql", "-U", primary.getUsername(),
            "-d", primary.getDatabaseName(), "-c",
            "ALTER SYSTEM SET synchronous_standby_names = 'ANY 2 (\"pg-node-2\", \"pg-node-3\")'"));
        requireSuccess(primary.execInContainer("psql", "-U", primary.getUsername(),
            "-d", primary.getDatabaseName(), "-c", "SELECT pg_reload_conf()"));
        haproxy = new GenericContainer<>("haproxy:2.8-alpine")
            .withNetwork(network).withExposedPorts(5400)
            .withClasspathResourceMapping("haproxy-durability.cfg",
                "/usr/local/etc/haproxy/haproxy.cfg", BindMode.READ_ONLY)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
        haproxy.start();
    }

    @AfterAll
    static void stopReplication() {
        if (haproxy != null) haproxy.stop();
        if (standby3 != null) standby3.stop();
        if (standby2 != null) standby2.stop();
        if (primary != null) primary.stop();
        if (network != null) network.close();
    }

    @BeforeEach
    void setUpDurability(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        topic = "durability-" + UUID.randomUUID();
        connectionManager = new PgConnectionManager(vertx);
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3).shared(false)
            .connectionTimeout(Duration.ofSeconds(2))
            .idleTimeout(Duration.ofSeconds(2)).build();
        observer = connectionManager.getOrCreateReactivePool("observer", config(primary), poolConfig);
        connectionManager.getOrCreateReactivePool(SERVICE,
            config(haproxy.getHost(), haproxy.getMappedPort(5400)), poolConfig);
        connectionManager.getOrCreateReactivePool("standby2",
            config(standby2.getHost(), standby2.getMappedPort(5432)), poolConfig);
        connectionManager.getOrCreateReactivePool("standby3",
            config(standby3.getHost(), standby3.getMappedPort(5432)), poolConfig);
        startStandbyProcess(vertx, standby3)
            .compose(ignored -> waitForCoverage(System.nanoTime() + TimeUnit.SECONDS.toNanos(20)))
            .onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void closeDurability(VertxTestContext testContext) {
        Future<Void> restore = vertx == null ? Future.succeededFuture()
            : startStandbyProcess(vertx, standby3);
        restore.transform(restored -> {
            Future<Void> close = connectionManager == null
                ? Future.succeededFuture() : connectionManager.close();
            return close.compose(ignored -> restored.succeeded()
                ? Future.succeededFuture() : Future.failedFuture(restored.cause()));
        }).onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @ParameterizedTest
    @CsvSource({"off,true,false", "local,true,false", "remote_write,true,false", "off,false,false", "on,true,true"})
    void weakerCallerPolicyCannotAcknowledgeWithoutBothPeers(
            String setting, boolean local, boolean cancel, VertxTestContext testContext) {
        int[] backend = {0};
        Future<Void> stop = vertx.<Void>executeBlocking(() -> {
            requireSuccess(standby3.execInContainer("su-exec", "postgres", "pg_ctl",
                "-D", REPLICA_DATA, "-m", "fast", "-w", "stop"));
            return null;
        });
        stop.compose(ignored -> {
            Future<String> write = connectionManager.withTransaction(SERVICE, connection ->
                connection.query("SELECT pg_backend_pid() AS pid").execute()
                    .compose(rows -> {
                        backend[0] = rows.iterator().next().getInteger("pid");
                        return connection.preparedQuery(
                            "SELECT set_config('synchronous_commit', $1, $2)").execute(Tuple.of(setting, local));
                    })
                    .compose(rows -> insert(connection, topic))
                    .map(topic))
                .onFailure(failure -> logger.debug("Expected interrupted commit", failure));
            return waitForCommitWait(backend, write,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
                .compose(v -> observer.preparedQuery("SELECT " + (cancel ? "pg_cancel_backend" : "pg_terminate_backend")
                    + "($1) AS terminated")
                    .execute(Tuple.of(backend[0])))
                .compose(rows -> {
                    assertTrue(rows.iterator().next().getBoolean("terminated"));
                    return write.transform(result -> {
                        assertTrue(result.failed(),
                            "Missing synchronous peer must not yield an acknowledged commit");
                        assertInstanceOf(PgCommitOutcomeUnknownException.class, result.cause());
                        return countRows();
                    }).compose(count -> {
                        assertEquals(1L, count,
                            "An unacknowledged commit must not be reported as a rollback");
                        return Future.<Void>succeededFuture();
                    });
                });
        }).onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void acknowledgedWriteIsFlushedOnBothStandbys(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection ->
            connection.query("SET LOCAL synchronous_commit = off").execute()
                .compose(ignored -> insert(connection, topic))
                .map(topic))
            .compose(result -> {
                assertEquals(topic, result);
                return observer.query("SELECT pg_current_wal_lsn()::text AS lsn").execute();
            })
            .compose(rows -> {
                String lsn = rows.iterator().next().getString("lsn");
                return waitForFlush(lsn, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
            })
            .compose(ignored -> countStandbyRows("standby2"))
            .compose(count -> {
                assertEquals(1L, count);
                return countStandbyRows("standby3");
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void failedCallerFutureRollsBack(VertxTestContext testContext) {
        IllegalArgumentException failure = new IllegalArgumentException("caller rejected");
        expectRollback(connection -> insert(connection, topic)
            .compose(ignored -> Future.failedFuture(failure)), failure, testContext);
    }

    @Test
    void thrownCallerFailureRollsBack(VertxTestContext testContext) {
        IllegalArgumentException failure = new IllegalArgumentException("caller threw");
        expectRollback(connection -> insert(connection, topic).compose(ignored -> {
            throw failure;
        }), failure, testContext);
    }

    @Test
    void nullFutureReturnedDirectlyIsRejected(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection -> null)
            .onComplete(testContext.failing(failure -> testContext.verify(() -> {
                assertInstanceOf(NullPointerException.class, failure);
                assertEquals("Transaction operation returned null Future", failure.getMessage());
                testContext.completeNow();
            })));
    }

    @Test
    void postgresFailureRollsBack(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection ->
            insert(connection, topic).compose(ignored ->
                connection.query("SELECT 1 / 0").execute()))
            .transform(result -> {
                assertTrue(result.failed());
                PgException failure = assertInstanceOf(PgException.class, result.cause());
                assertEquals("22012", failure.getSqlState());
                return countRows();
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(0L, count);
                testContext.completeNow();
            })).onFailure(testContext::failNow);
    }

    @Test
    void callerSqlCommitCannotReturnSuccessfulManagerResult(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection ->
            insert(connection, topic).compose(ignored -> connection.query("COMMIT").execute()).map(topic))
            .onComplete(testContext.failing(failure -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, failure);
                assertEquals("Transaction operation completed the manager-owned transaction",
                    failure.getMessage());
                testContext.completeNow();
            })));
    }

    @Test
    void callerApiCommitCannotReturnSuccessfulManagerResult(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection ->
            insert(connection, topic).compose(ignored -> connection.transaction().commit()).map(topic))
            .onComplete(testContext.failing(failure -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, failure);
                assertEquals("Transaction operation completed the manager-owned transaction",
                    failure.getMessage());
                testContext.completeNow();
            })));
    }

    @Test
    void callerRollbackCannotReturnSuccessfulManagerResult(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection ->
            insert(connection, topic).compose(ignored -> connection.query("ROLLBACK").execute()).map(topic))
            .transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(IllegalStateException.class, result.cause());
                return countRows();
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(0L, count);
                testContext.completeNow();
            })).onFailure(testContext::failNow);
    }

    @Test
    void legacyConnectionWriteStillCommits(VertxTestContext testContext) {
        connectionManager.withConnection(SERVICE, connection -> insert(connection, topic))
            .compose(ignored -> countRows())
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count);
                testContext.completeNow();
            })).onFailure(testContext::failNow);
    }

    @Test
    void nullSuccessfulValueStillCommits(VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, connection -> insert(connection, topic).mapEmpty())
            .compose(result -> {
                assertNull(result);
                return countRows();
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count);
                testContext.completeNow();
            })).onFailure(testContext::failNow);
    }

    private void expectRollback(
            java.util.function.Function<SqlConnection, Future<Void>> operation,
            Throwable failure, VertxTestContext testContext) {
        connectionManager.withTransaction(SERVICE, operation)
            .transform(result -> {
                assertTrue(result.failed());
                assertSame(failure, result.cause());
                return countRows();
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(0L, count);
                testContext.completeNow();
            })).onFailure(testContext::failNow);
    }

    private static Future<Void> insert(SqlConnection connection, String topic) {
        return connection.preparedQuery("INSERT INTO " + SCHEMA
            + ".queue_messages (topic, payload) VALUES ($1, '{}'::jsonb)")
            .execute(Tuple.of(topic)).mapEmpty();
    }

    private Future<Long> countStandbyRows(String service) {
        return connectionManager.withConnection(service, connection ->
            connection.preparedQuery("SELECT pg_is_in_recovery() AS recovery, "
                + "(SELECT count(*) FROM " + SCHEMA
                + ".queue_messages WHERE topic = $1) AS count")
                .execute(Tuple.of(topic)).map(rows -> {
                    var row = rows.iterator().next();
                    assertTrue(row.getBoolean("recovery"));
                    return row.getLong("count");
                }));
    }

    private Future<Long> countRows() {
        return observer.preparedQuery("SELECT count(*) AS count FROM " + SCHEMA
            + ".queue_messages WHERE topic = $1").execute(Tuple.of(topic))
            .map(rows -> rows.iterator().next().getLong("count"));
    }

    private Future<Void> waitForCoverage(long deadline) {
        return observer.query("""
            SELECT count(*) AS count
            FROM pg_stat_replication
            WHERE application_name IN ('pg-node-2', 'pg-node-3')
              AND state = 'streaming' AND sync_state = 'quorum'
            """).execute().compose(rows -> {
                if (rows.iterator().next().getLong("count") == 2) {
                    return Future.succeededFuture();
                }
                if (System.nanoTime() >= deadline) {
                    return Future.failedFuture(new AssertionError("Both synchronous peers must be streaming"));
                }
                return vertx.timer(50).compose(ignored -> waitForCoverage(deadline));
            });
    }

    private Future<Void> waitForFlush(String lsn, long deadline) {
        return observer.preparedQuery("""
            SELECT count(*) AS count FROM pg_stat_replication
            WHERE application_name IN ('pg-node-2', 'pg-node-3')
              AND state = 'streaming' AND flush_lsn >= $1::pg_lsn
            """).execute(Tuple.of(lsn)).compose(rows -> {
                if (rows.iterator().next().getLong("count") == 2) {
                    return Future.succeededFuture();
                }
                if (System.nanoTime() >= deadline) {
                    return Future.failedFuture(new AssertionError("Both peers must flush acknowledged WAL"));
                }
                return vertx.timer(50).compose(ignored -> waitForFlush(lsn, deadline));
            });
    }

    private Future<Void> waitForCommitWait(int[] backend, Future<?> write, long deadline) {
        if (write.isComplete()) {
            return Future.failedFuture(new AssertionError(
                "Write completed before the missing synchronous peer returned"));
        }
        return observer.preparedQuery("""
            SELECT count(*) AS count FROM pg_stat_activity
            WHERE pid = $1 AND wait_event = 'SyncRep'
            """).execute(Tuple.of(backend[0])).compose(rows -> {
                if (rows.iterator().next().getLong("count") == 1) {
                    return Future.succeededFuture();
                }
                if (System.nanoTime() >= deadline) {
                    return Future.failedFuture(new AssertionError("Commit never waited for synchronous replication"));
                }
                return vertx.timer(25).compose(ignored ->
                    waitForCommitWait(backend, write, deadline));
            });
    }

    private static PgConnectionConfig config(PostgreSQLContainer postgres) {
        return config(postgres.getHost(), postgres.getFirstMappedPort());
    }

    private static PgConnectionConfig config(String host, int port) {
        return new PgConnectionConfig.Builder().host(host)
            .port(port).database(primary.getDatabaseName())
            .username(primary.getUsername()).password(primary.getPassword())
            .schema(SCHEMA).build();
    }

    private static GenericContainer<?> startStandby(String nodeId) throws Exception {
        GenericContainer<?> standby = new GenericContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withNetwork(network).withNetworkAliases(nodeId).withExposedPorts(5432)
            .withCommand("tail", "-f", "/dev/null")
            .waitingFor(Wait.forSuccessfulCommand("test -d /var/lib/postgresql"));
        standby.start();
        requireSuccess(standby.execInContainer("sh", "-c", """
            set -eu
            mkdir -p /var/lib/postgresql/replica
            chown postgres:postgres /var/lib/postgresql/replica
            chmod 700 /var/lib/postgresql/replica
            su-exec postgres env PGPASSWORD=peegeeq_replication pg_basebackup \
              -d "host=pg_primary port=5432 user=replicator application_name=%s" \
              -D /var/lib/postgresql/replica -Fp -Xs -R
            su-exec postgres pg_ctl -D /var/lib/postgresql/replica -l /tmp/postgres.log \
              -o "-c listen_addresses='*' -c hot_standby=on" -w start
            """.formatted(nodeId)));
        return standby;
    }

    private static Future<Void> startStandbyProcess(Vertx vertx, GenericContainer<?> standby) {
        return vertx.<Void>executeBlocking(() -> {
            Container.ExecResult status = standby.execInContainer("su-exec", "postgres", "pg_ctl",
                "-D", REPLICA_DATA, "status");
            if (status.getExitCode() == 3) {
                requireSuccess(standby.execInContainer("su-exec", "postgres", "pg_ctl",
                    "-D", REPLICA_DATA, "-l", "/tmp/postgres.log", "-w", "start"));
            } else {
                requireSuccess(status);
            }
            return null;
        });
    }

    private static void requireSuccess(Container.ExecResult result) {
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("PostgreSQL fixture command failed: "
                + result.getStdout() + result.getStderr());
        }
    }
}

