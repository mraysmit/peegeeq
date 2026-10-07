package dev.mars.peegeeq.db.resilience;

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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.PgTestImageConstant;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.vertx.junit5.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Realistic HAProxy TCP failover integration test for PeeGeeQ database connections.
 *
 * <h2>Architecture under test</h2>
 * <pre>
 *   Vert.x Pool (PgConnectionManager)
 *         connects to HAProxy:5400
 *   HAProxy (TCP proxy with active health-checking)
 *        pg_primary:5432   (active, always preferred)
 *        pg_secondary:5432 (backup  only used when primary is DOWN)
 * </pre>
 *
 * <h2>What is tested</h2>
 * <ol>
 *   <li>Normal operation: SELECT 1 routes through HAProxy to primary.</li>
 *   <li>Real SQL round-trip: DDL + DML via {@code withTransaction} using a temp table.</li>
 *   <li>Transaction rollback safety: rolled-back insert leaves no row.</li>
 *   <li>{@code checkHealth()} returns {@code true} while primary is healthy.</li>
 *   <li>Failback: stop primary, verify the pool reaches the secondary, start a replacement
 *       primary, verify that a new connection and the existing pool both reach it.</li>
 *   <li>Failover: stop active primary (destructive), verify the pool reaches the secondary.</li>
 * </ol>
 *
 * <p>Phases 5 and 6 identify the node that answered by its PostgreSQL system identifier.
 * They wait for a routing change by polling that identity against a deadline.
 *
 * <h2>Production note</h2>
 * In production, primary and secondary would be connected via PostgreSQL
 * streaming replication so that secondary has the same data.  This test uses
 * two independent PostgreSQL instances because it targets connection-level
 * resilience (can the pool reconnect?), not data consistency.
 *
 * <h2>Test ordering</h2>
 * Tests run in declared order.  Phase 5 stops the original primary and starts a replacement.
 * Phase 6 (failover) stops the active primary, so it runs last.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("HAProxy TCP Failover Integration Tests")
class HaProxyConnectionFailoverTest {

    private static final Logger logger = LoggerFactory.getLogger(HaProxyConnectionFailoverTest.class);

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Port HAProxy listens on inside the Docker network; mapped to a random host port. */
    private static final int HAPROXY_PG_PORT = 5400;

    private static final String DB_NAME = "peegeeq_test";
    private static final String DB_USER = "peegeeq_test";
    private static final String DB_PASS = "peegeeq_test";

    /** Pool connection timeout  short for tests so failures surface quickly. */
    private static final Duration POOL_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Deadline for a routing change to become observable.  HAProxy detects a failed node after
     * fall=2 checks at inter=500ms and a recovered node after rise=1 check.  The deadline bounds
     * the poll; the poll completes as soon as the expected node answers.
     */
    private static final long ROUTING_DEADLINE_MS = 30_000;

    /** Interval between node-identity probes while waiting for a routing change. */
    private static final long POLL_INTERVAL_MS = 250;

    /**
     * Returns a value that is unique to one PostgreSQL cluster.  Each container runs its own
     * initdb, so each node reports a different system identifier.
     */
    private static final String NODE_IDENTITY_SQL =
        "SELECT system_identifier::text AS node_id FROM pg_control_system()";

    // -----------------------------------------------------------------------
    // Shared containers  (started ONCE per test class)
    // -----------------------------------------------------------------------

    static Network network;

    @SuppressWarnings("resource")
    static PostgreSQLContainer primary;

    @SuppressWarnings("resource")
    static PostgreSQLContainer secondary;

    @SuppressWarnings("resource")
    static GenericContainer<?> haproxy;

    /**
     * Replacement primary used in Phase 5 (failback test).  Not started in @BeforeAll 
     * started mid-test with the same network alias "pg_primary" so HAProxy re-discovers it
     * automatically on the next health-check cycle.
     */
    @SuppressWarnings("resource")
    static PostgreSQLContainer primary2;

    // -----------------------------------------------------------------------
    // Per-test state
    // -----------------------------------------------------------------------

    private PgConnectionManager connectionManager;
    private Pool pool;

    /** Gives each node-identity probe pool its own service id. */
    private final AtomicInteger probeSequence = new AtomicInteger();

    // -----------------------------------------------------------------------
    // Container lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    static void startInfrastructure() {
        long t0 = System.currentTimeMillis();
        logger.info("=== Infrastructure startup BEGIN (image={}) ===", PgTestImageConstant.POSTGRES_IMAGE);

        network = Network.newNetwork();
        logger.debug("[infra] Docker network created: id={}", network.getId());

        primary = new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pg_primary")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASS)
            // Creates the 'haproxy_check' user so HAProxy's pgsql-check can probe each node.
            .withInitScript("haproxy-check-init.sql");

        secondary = new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pg_secondary")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASS)
            // Creates the 'haproxy_check' user so HAProxy's pgsql-check can probe each node.
            .withInitScript("haproxy-check-init.sql");

        // Start both PostgreSQL nodes before starting HAProxy, so HAProxy can
        // successfully health-check both backends on startup.
        logger.debug("[infra] Starting primary PostgreSQL ");
        primary.start();
        logger.info("[infra] Primary   started: host={} mappedPort={} containerId={}",
            primary.getHost(), primary.getFirstMappedPort(),
            primary.getContainerId().substring(0, 12));

        logger.debug("[infra] Starting secondary PostgreSQL ");
        secondary.start();
        logger.info("[infra] Secondary started: host={} mappedPort={} containerId={}",
            secondary.getHost(), secondary.getFirstMappedPort(),
            secondary.getContainerId().substring(0, 12));

        // Prepare the replacement primary for the failback test (Phase 5).
        // Uses the same alias so HAProxy re-discovers it without config changes.
        // Not started here  started mid-test in Phase 5.
        primary2 = new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pg_primary")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASS)
            .withInitScript("haproxy-check-init.sql");
        logger.debug("[infra] primary2 container prepared (not started yet)");

        // HAProxy is configured via haproxy-failover.cfg on the classpath.
        // It references pg_primary / pg_secondary by their Docker network aliases.
        haproxy = new GenericContainer<>("haproxy:2.8-alpine")
            .withNetwork(network)
            .withClasspathResourceMapping(
                "haproxy-failover.cfg",
                "/usr/local/etc/haproxy/haproxy.cfg",
                BindMode.READ_ONLY)
            .withExposedPorts(HAPROXY_PG_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));

        logger.debug("[infra] Starting HAProxy ");
        haproxy.start();
        logger.info("[infra] HAProxy    started: host={} mappedPort={} containerId={}",
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT),
            haproxy.getContainerId().substring(0, 12));

        logger.info("=== Infrastructure startup COMPLETE in {}ms ===", System.currentTimeMillis() - t0);
        logger.info("[infra] Topology: App  HAProxy:{}:{}  pg_primary:5432 / pg_secondary:5432 (backup)",
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT));
        logger.info("[infra] pgsql-check user: haproxy_check (no password, no privileges)");
        logger.info("[infra] Routing deadline: {}ms, node-identity poll interval: {}ms",
            ROUTING_DEADLINE_MS, POLL_INTERVAL_MS);
    }

    @AfterAll
    static void stopInfrastructure() {
        logger.info("=== Infrastructure teardown BEGIN ===");
        // Stop in reverse order; primary may already be stopped by the failover test.
        if (haproxy    != null && haproxy.isRunning())    { logger.debug("[teardown] Stopping haproxy");    haproxy.stop();   }
        if (primary2   != null && primary2.isRunning())   { logger.debug("[teardown] Stopping primary2");   primary2.stop();  }
        if (secondary  != null && secondary.isRunning())  { logger.debug("[teardown] Stopping secondary");  secondary.stop(); }
        if (primary    != null && primary.isRunning())    { logger.debug("[teardown] Stopping primary");    primary.stop();   }
        if (network    != null)                           { logger.debug("[teardown] Closing network");     network.close();  }
        logger.info("=== Infrastructure teardown COMPLETE ===");
    }

    // -----------------------------------------------------------------------
    // Per-test pool lifecycle
    // -----------------------------------------------------------------------

    @BeforeEach
    void createPool(Vertx vertx, VertxTestContext ctx) {
        connectionManager = new PgConnectionManager(vertx);

        PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
            .host(haproxy.getHost())
            .port(haproxy.getMappedPort(HAPROXY_PG_PORT))
            .database(DB_NAME)
            .username(DB_USER)
            .password(DB_PASS)
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .maxWaitQueueSize(20)
            .connectionTimeout(POOL_CONNECT_TIMEOUT)
            .idleTimeout(Duration.ofSeconds(30))
            .shared(false)
            .build();

        pool = connectionManager.getOrCreateReactivePool("haproxy-failover-test", connConfig, poolConfig);

        logger.info("[setup] Pool created  PgConnectionManager@{}  HAProxy {}:{} db={} maxSize=5 timeout={}s",
            connectionManager.getInstanceId(),
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT),
            DB_NAME, POOL_CONNECT_TIMEOUT.getSeconds());
        logger.debug("[setup] Container states: primary={} secondary={} haproxy={} primary2={}",
            primary != null && primary.isRunning() ? "UP(" + primary.getContainerId().substring(0, 12) + ")" : "DOWN",
            secondary != null && secondary.isRunning() ? "UP(" + secondary.getContainerId().substring(0, 12) + ")" : "DOWN",
            haproxy != null && haproxy.isRunning() ? "UP" : "DOWN",
            primary2 != null && primary2.isRunning() ? "UP(" + primary2.getContainerId().substring(0, 12) + ")" : "not started");
        ctx.completeNow();
    }

    @AfterEach
    void closePool(VertxTestContext ctx) {
        if (connectionManager == null) {
            ctx.completeNow();
            return;
        }
        logger.debug("[teardown] Closing pool for PgConnectionManager@{}", connectionManager.getInstanceId());
        // VertxExtension owns the injected Vertx and closes it; this method closes only the pools.
        connectionManager.close()
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Phase 1  Normal operation.
     *
     * Verifies that the Vert.x reactive pool can execute queries through HAProxy
     * while the primary is healthy.  HAProxy routes all traffic to pg_primary.
     */
    @Test
    @Order(1)
    @DisplayName("Phase 1: queries succeed when primary is healthy (routes via HAProxy)")
    void testNormalOperationViaPrimary(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 1 BEGIN: normal operation via HAProxy  primary ---");
        logger.debug("[phase-1] Primary running={} secondary running={}",
            primary.isRunning(), secondary.isRunning());

        pool.query("SELECT 1 AS health").execute()
            .onSuccess(rows -> ctx.verify(() -> {
                int value = rows.iterator().next().getInteger("health");
                long elapsed = System.currentTimeMillis() - t0;
                logger.info("[phase-1] SELECT 1 = {} via HAProxy  primary  ({}ms)", value, elapsed);
                assertEquals(1, value, "SELECT 1 should return 1 when primary is healthy");
                logger.info("--- Phase 1 PASS ---");
                ctx.completeNow();
            }))
            .onFailure(err -> {
                logger.error("[phase-1] FAIL: query failed after {}ms: {}",
                    System.currentTimeMillis() - t0, err.getMessage(), err);
                ctx.failNow(err);
            });
    }

    /**
     * Phase 2  Real SQL round-trip via {@link PgConnectionManager#withTransaction}.
     *
     * Creates a temporary table, inserts a row, reads it back, and verifies the value.
     * Temporary tables are session-scoped and auto-dropped when the connection closes,
     * leaving no residual schema objects.
     */
    @Test
    @Order(2)
    @DisplayName("Phase 2: withTransaction DDL + DML round-trip via HAProxy (temp table)")
    void testWithConnectionRealSqlRoundTrip(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 2 BEGIN: withTransaction DDL + DML round-trip ---");

        connectionManager.withTransaction("haproxy-failover-test", conn -> {
            logger.debug("[phase-2] Connection acquired from pool  executing CREATE TEMP TABLE");
            return conn.query("CREATE TEMP TABLE haproxy_roundtrip (id INT, label TEXT)").execute()
                .compose(v -> {
                    logger.debug("[phase-2] Temp table created  inserting test row (id=42)");
                    return conn.preparedQuery("INSERT INTO haproxy_roundtrip VALUES ($1, $2)")
                        .execute(io.vertx.sqlclient.Tuple.of(42, "peegeeq-via-haproxy"));
                })
                .compose(v -> {
                    logger.debug("[phase-2] Insert complete  reading back via SELECT");
                    return conn.query("SELECT id, label FROM haproxy_roundtrip").execute();
                })
                .map(rows -> {
                    var row = rows.iterator().next();
                    int id = row.getInteger("id");
                    String label = row.getString("label");
                    logger.info("[phase-2] Round-trip: id={}, label={} ({}ms)", id, label,
                        System.currentTimeMillis() - t0);
                    assertEquals(42, id, "id should round-trip correctly");
                    assertEquals("peegeeq-via-haproxy", label, "label should round-trip correctly");
                    return id;
                });
        })
        .onSuccess(id -> {
            logger.info("--- Phase 2 PASS: DDL + DML round-trip succeeded in {}ms ---",
                System.currentTimeMillis() - t0);
            ctx.completeNow();
        })
        .onFailure(err -> {
            logger.error("[phase-2] FAIL: withTransaction round-trip failed after {}ms: {}",
                System.currentTimeMillis() - t0, err.getMessage(), err);
            ctx.failNow(err);
        });
    }

    /**
     * Phase 3  Transaction rollback safety via {@link PgConnectionManager#withTransaction}.
     *
     * Inserts a row inside a transaction, fails that transaction deliberately, then verifies
     * the row is absent. Confirms the manager rolls back failed work through HAProxy.
     */
    @Test
    @Order(3)
    @DisplayName("Phase 3: withTransaction rollback leaves no row (via HAProxy)")
    void testWithTransactionRollbackSafety(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 3 BEGIN: transaction rollback safety ---");

        IllegalStateException rollbackSignal = new IllegalStateException("intentional rollback");
        connectionManager.withTransaction("haproxy-failover-test", conn -> {
            logger.debug("[phase-3] Creating and clearing rollback verification table");
            return conn.query("CREATE TABLE IF NOT EXISTS public.haproxy_rollback_test (val INT)").execute()
                .compose(v -> conn.query("TRUNCATE public.haproxy_rollback_test").execute())
                .mapEmpty();
        })
        .compose(v -> connectionManager.withTransaction("haproxy-failover-test", conn -> {
            logger.debug("[phase-3] Inserting val=999 inside transaction");
            return conn.preparedQuery("INSERT INTO public.haproxy_rollback_test VALUES ($1)")
                .execute(io.vertx.sqlclient.Tuple.of(999))
                .compose(inserted -> Future.<Void>failedFuture(rollbackSignal));
        }))
        .transform(ar -> {
            if (ar.succeeded()) {
                return Future.failedFuture(new AssertionError("Rollback transaction unexpectedly committed"));
            }
            if (ar.cause() != rollbackSignal) {
                return Future.failedFuture(ar.cause());
            }
            logger.debug("[phase-3] Expected transaction failure observed  verifying row count = 0");
            return Future.succeededFuture();
        })
        .compose(v -> connectionManager.withConnection("haproxy-failover-test", conn ->
            conn.query("SELECT COUNT(*) AS cnt FROM public.haproxy_rollback_test").execute()))
        .map(rows -> {
            int count = rows.iterator().next().getInteger("cnt");
            logger.info("[phase-3] Row count after rollback: {} (expected 0, {}ms)",
                count, System.currentTimeMillis() - t0);
            assertEquals(0, count, "No rows should exist after rollback");
            return count;
        })
        .onSuccess(v -> {
            logger.info("--- Phase 3 PASS: rollback safety confirmed in {}ms ---",
                System.currentTimeMillis() - t0);
            ctx.completeNow();
        })
        .onFailure(err -> {
            logger.error("[phase-3] FAIL: rollback safety check failed after {}ms: {}",
                System.currentTimeMillis() - t0, err.getMessage(), err);
            ctx.failNow(err);
        });
    }

    /**
     * Phase 4  {@link PgConnectionManager#checkHealth} returns {@code true} while primary healthy.
     *
     * Validates the health-check API works end-to-end through HAProxy.
     */
    @Test
    @Order(4)
    @DisplayName("Phase 4: checkHealth() returns true via HAProxy while primary healthy")
    void testCheckHealthReturnsTrueViaPrimary(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 4 BEGIN: checkHealth() API via HAProxy ---");
        logger.debug("[phase-4] Primary running={} secondary running={}",
            primary.isRunning(), secondary.isRunning());

        connectionManager.checkHealth("haproxy-failover-test")
            .onSuccess(healthy -> ctx.verify(() -> {
                long elapsed = System.currentTimeMillis() - t0;
                logger.info("[phase-4] checkHealth() = {} ({}ms)", healthy, elapsed);
                assertEquals(Boolean.TRUE, healthy, "checkHealth() should return true while primary is up");
                logger.info("--- Phase 4 PASS: checkHealth() confirmed healthy in {}ms ---", elapsed);
                ctx.completeNow();
            }))
            .onFailure(err -> {
                logger.error("[phase-4] FAIL: checkHealth() threw after {}ms: {}",
                    System.currentTimeMillis() - t0, err.getMessage(), err);
                ctx.failNow(err);
            });
    }

    /**
     * Phase 5: failback to a replacement primary.
     *
     * <ol>
     *   <li>Read the system identifier of the primary and the secondary directly from each node.</li>
     *   <li>Confirm the pool reaches the primary through HAProxy.</li>
     *   <li>Stop the primary.  Wait until the pool reaches the secondary.</li>
     *   <li>Start {@code primary2}, a new container with the same network alias
     *       {@code pg_primary}, and read its system identifier directly.</li>
     *   <li>Wait until a new connection through HAProxy reaches {@code primary2}.</li>
     *   <li>Wait until the pool that served queries during the outage reaches {@code primary2}.</li>
     * </ol>
     *
     * <p>Every routing claim is proven by the system identifier of the node that answered.
     *
     * <p>After this test the original primary is gone and {@code primary2} is running.
     * Phase 6 stops {@code primary2}.
     */
    @Test
    @Order(5)
    @Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Phase 5: failback - new and pooled connections reach the replacement primary")
    void testFailbackAfterPrimaryRecovery(Vertx vertx, VertxTestContext ctx) {
        String[] nodeIds = new String[3]; // 0 = primary, 1 = secondary, 2 = primary2

        nodeIdentityOnNewConnection(primary.getHost(), primary.getFirstMappedPort())
            .compose(primaryId -> {
                nodeIds[0] = primaryId;
                return nodeIdentityOnNewConnection(secondary.getHost(), secondary.getFirstMappedPort());
            })
            .compose(secondaryId -> {
                nodeIds[1] = secondaryId;
                assertNotEquals(nodeIds[0], nodeIds[1],
                    "Primary and secondary must report different system identifiers");
                return awaitNodeIdentity(vertx, this::nodeIdentityFromPool, nodeIds[0],
                    "Pool before the outage");
            })
            .compose(v -> stopContainer(vertx, primary))
            .compose(v -> awaitNodeIdentity(vertx, this::nodeIdentityFromPool, nodeIds[1],
                "Pool after the primary stopped"))
            .compose(v -> startContainer(vertx, primary2))
            .compose(v -> nodeIdentityOnNewConnection(primary2.getHost(), primary2.getFirstMappedPort()))
            .compose(primary2Id -> {
                nodeIds[2] = primary2Id;
                assertNotEquals(nodeIds[1], nodeIds[2],
                    "Secondary and replacement primary must report different system identifiers");
                return awaitNodeIdentity(vertx, this::nodeIdentityThroughHaProxyOnNewConnection, nodeIds[2],
                    "New connection through HAProxy after the replacement primary started");
            })
            .compose(v -> awaitNodeIdentity(vertx, this::nodeIdentityFromPool, nodeIds[2],
                "Pool that served queries during the outage, after the replacement primary started"))
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    /**
     * Phase 6: failover to the secondary (destructive).
     *
     * <p>Stops the active primary and waits until the pool reaches the secondary.  HAProxy routes
     * new connections to the backup server.  It does not promote PostgreSQL.  The nodes in this
     * test are independent, so the secondary accepts the query without promotion.
     *
     * <p>Stopping the active primary removes its container.  No later test may assume a primary
     * is running.
     */
    @Test
    @Order(6)
    @Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Phase 6: HAProxy routes the pool to the secondary after the primary stops")
    void testFailoverToSecondaryWhenPrimaryFails(Vertx vertx, VertxTestContext ctx) {
        PostgreSQLContainer activePrimary = (primary2 != null && primary2.isRunning()) ? primary2 : primary;
        String[] nodeIds = new String[2]; // 0 = active primary, 1 = secondary

        nodeIdentityOnNewConnection(activePrimary.getHost(), activePrimary.getFirstMappedPort())
            .compose(primaryId -> {
                nodeIds[0] = primaryId;
                return nodeIdentityOnNewConnection(secondary.getHost(), secondary.getFirstMappedPort());
            })
            .compose(secondaryId -> {
                nodeIds[1] = secondaryId;
                assertNotEquals(nodeIds[0], nodeIds[1],
                    "Active primary and secondary must report different system identifiers");
                return awaitNodeIdentity(vertx, this::nodeIdentityFromPool, nodeIds[0],
                    "Pool before the outage");
            })
            .compose(v -> stopContainer(vertx, activePrimary))
            .compose(v -> awaitNodeIdentity(vertx, this::nodeIdentityFromPool, nodeIds[1],
                "Pool after the active primary stopped"))
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static PgConnectionConfig connectionConfig(String host, int port) {
        return new PgConnectionConfig.Builder()
            .host(host)
            .port(port)
            .database(DB_NAME)
            .username(DB_USER)
            .password(DB_PASS)
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();
    }

    /** Stops a container on a worker thread.  A container stop blocks for seconds. */
    private static Future<Void> stopContainer(Vertx vertx, PostgreSQLContainer container) {
        return vertx.<Void>executeBlocking(() -> {
            container.stop();
            return null;
        });
    }

    /** Starts a container on a worker thread.  A container start blocks for seconds. */
    private static Future<Void> startContainer(Vertx vertx, PostgreSQLContainer container) {
        return vertx.<Void>executeBlocking(() -> {
            container.start();
            return null;
        });
    }

    /** Reads the identity of the node that answers the pool under test. */
    private Future<String> nodeIdentityFromPool() {
        return pool.query(NODE_IDENTITY_SQL).execute()
            .map(rows -> rows.iterator().next().getString("node_id"));
    }

    /** Reads the identity of the node that HAProxy selects for a connection opened now. */
    private Future<String> nodeIdentityThroughHaProxyOnNewConnection() {
        return nodeIdentityOnNewConnection(haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT));
    }

    /**
     * Opens a new single-connection pool to the endpoint, reads the node identity, and closes
     * the pool.  A new pool guarantees a new TCP connection, so the result shows where the
     * endpoint routes a connection opened at this moment.
     */
    private Future<String> nodeIdentityOnNewConnection(String host, int port) {
        String serviceId = "node-identity-" + probeSequence.incrementAndGet();
        PgPoolConfig probePoolConfig = new PgPoolConfig.Builder()
            .maxSize(1)
            .connectionTimeout(POOL_CONNECT_TIMEOUT)
            .idleTimeout(Duration.ofSeconds(30))
            .shared(false)
            .build();
        Pool probePool = connectionManager.getOrCreateReactivePool(
            serviceId, connectionConfig(host, port), probePoolConfig);
        return probePool.query(NODE_IDENTITY_SQL).execute()
            .map(rows -> rows.iterator().next().getString("node_id"))
            .eventually(() -> connectionManager.closePool(serviceId));
    }

    /**
     * Polls {@code probe} until it returns {@code expectedNodeId}, and fails when
     * {@link #ROUTING_DEADLINE_MS} elapses first.  A probe failure counts as "not yet": during a
     * routing change the pool returns connection errors before HAProxy switches server.
     */
    private Future<Void> awaitNodeIdentity(
            Vertx vertx, Supplier<Future<String>> probe, String expectedNodeId, String description) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTING_DEADLINE_MS);
        return pollNodeIdentity(vertx, probe, expectedNodeId, description, deadlineNanos);
    }

    private Future<Void> pollNodeIdentity(
            Vertx vertx, Supplier<Future<String>> probe, String expectedNodeId, String description,
            long deadlineNanos) {
        return probe.get().transform(probeResult -> {
            if (probeResult.succeeded() && expectedNodeId.equals(probeResult.result())) {
                logger.info("{}: node {} answered", description, expectedNodeId);
                return Future.<Void>succeededFuture();
            }
            if (System.nanoTime() >= deadlineNanos) {
                String lastObserved = probeResult.succeeded()
                    ? "node " + probeResult.result()
                    : "failure " + probeResult.cause();
                return Future.<Void>failedFuture(new AssertionError(
                    description + ": expected node " + expectedNodeId + " within "
                        + ROUTING_DEADLINE_MS + " ms, last observed " + lastObserved,
                    probeResult.cause()));
            }
            return vertx.timer(POLL_INTERVAL_MS)
                .compose(timerId -> pollNodeIdentity(vertx, probe, expectedNodeId, description, deadlineNanos));
        });
    }
}
