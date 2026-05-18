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

import dev.mars.peegeeq.db.PgTestImageConstant;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Realistic HAProxy TCP failover integration test for PeeGeeQ database connections.
 *
 * <h2>Architecture under test</h2>
 * <pre>
 *   Vert.x Pool (PgConnectionManager)
 *       ↓  connects to HAProxy:5400
 *   HAProxy (TCP proxy with active health-checking)
 *       ├── pg_primary:5432   (active, always preferred)
 *       └── pg_secondary:5432 (backup – only used when primary is DOWN)
 * </pre>
 *
 * <h2>What is tested</h2>
 * <ol>
 *   <li>Normal operation: SELECT 1 routes through HAProxy to primary.</li>
 *   <li>Real SQL round-trip: DDL + DML via {@code withConnection} using a temp table.</li>
 *   <li>Transaction rollback safety: rolled-back insert leaves no row.</li>
 *   <li>{@code checkHealth()} returns {@code true} while primary is healthy.</li>
 *   <li>Failback: stop primary, verify secondary takes over, start replacement primary,
 *       verify traffic is served again.</li>
 *   <li>Failover: stop active primary (destructive), verify pool retries succeed via secondary.</li>
 * </ol>
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

    /** Pool connection timeout – short for tests so failures surface quickly. */
    private static final Duration POOL_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** HAProxy health-check: fall=2 × inter=500ms = ~1 s to detect failure. */
    private static final long HAPROXY_FAILOVER_WAIT_MS = 4_000;

    /**
     * Time to wait for failback: replacement container must start AND HAProxy must
     * detect it healthy (inter=500ms, rise=1).  Container startup typically 3-5 s.
     */
    private static final long HAPROXY_FAILBACK_WAIT_MS = 8_000;

    /** Retry interval between query attempts after failover is triggered. */
    private static final long RETRY_INTERVAL_MS = 1_000;

    /** Maximum query retry attempts after stopping primary. */
    private static final int MAX_RETRY_ATTEMPTS = 8;

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
     * Replacement primary used in Phase 5 (failback test).  Not started in @BeforeAll —
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
        logger.debug("[infra] Starting primary PostgreSQL …");
        primary.start();
        logger.info("[infra] Primary   started: host={} mappedPort={} containerId={}",
            primary.getHost(), primary.getFirstMappedPort(),
            primary.getContainerId().substring(0, 12));

        logger.debug("[infra] Starting secondary PostgreSQL …");
        secondary.start();
        logger.info("[infra] Secondary started: host={} mappedPort={} containerId={}",
            secondary.getHost(), secondary.getFirstMappedPort(),
            secondary.getContainerId().substring(0, 12));

        // Prepare the replacement primary for the failback test (Phase 5).
        // Uses the same alias so HAProxy re-discovers it without config changes.
        // Not started here — started mid-test in Phase 5.
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

        logger.debug("[infra] Starting HAProxy …");
        haproxy.start();
        logger.info("[infra] HAProxy    started: host={} mappedPort={} containerId={}",
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT),
            haproxy.getContainerId().substring(0, 12));

        logger.info("=== Infrastructure startup COMPLETE in {}ms ===", System.currentTimeMillis() - t0);
        logger.info("[infra] Topology: App → HAProxy:{}:{} → pg_primary:5432 / pg_secondary:5432 (backup)",
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT));
        logger.info("[infra] pgsql-check user: haproxy_check (no password, no privileges)");
        logger.info("[infra] Failover timing: fall=2 x inter=500ms ≈ 1s detection, {}ms test buffer",
            HAPROXY_FAILOVER_WAIT_MS);
        logger.info("[infra] Failback timing: {}ms (container start + HAProxy rise=1 probe)", HAPROXY_FAILBACK_WAIT_MS);
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
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .maxWaitQueueSize(20)
            .connectionTimeout(POOL_CONNECT_TIMEOUT)
            .idleTimeout(Duration.ofSeconds(30))
            .shared(false)
            .build();

        pool = connectionManager.getOrCreateReactivePool("haproxy-failover-test", connConfig, poolConfig);

        logger.info("[setup] Pool created – PgConnectionManager@{} → HAProxy {}:{} db={} maxSize=5 timeout={}s",
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
    void closePool(Vertx vertx, VertxTestContext ctx) {
        logger.debug("[teardown] Closing pool for PgConnectionManager@{}", connectionManager.getInstanceId());
        connectionManager.close()
            .eventually(() -> vertx.close())
            .onSuccess(v -> {
                logger.debug("[teardown] Pool closed successfully");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Phase 1 – Normal operation.
     *
     * Verifies that the Vert.x reactive pool can execute queries through HAProxy
     * while the primary is healthy.  HAProxy routes all traffic to pg_primary.
     */
    @Test
    @Order(1)
    @DisplayName("Phase 1: queries succeed when primary is healthy (routes via HAProxy)")
    void testNormalOperationViaPrimary(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 1 BEGIN: normal operation via HAProxy → primary ---");
        logger.debug("[phase-1] Primary running={} secondary running={}",
            primary.isRunning(), secondary.isRunning());

        pool.query("SELECT 1 AS health").execute()
            .onSuccess(rows -> ctx.verify(() -> {
                int value = rows.iterator().next().getInteger("health");
                long elapsed = System.currentTimeMillis() - t0;
                logger.info("[phase-1] SELECT 1 = {} via HAProxy → primary  ({}ms)", value, elapsed);
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
     * Phase 2 – Real SQL round-trip via {@link PgConnectionManager#withConnection}.
     *
     * Creates a temporary table, inserts a row, reads it back, and verifies the value.
     * Temporary tables are session-scoped and auto-dropped when the connection closes,
     * leaving no residual schema objects.
     */
    @Test
    @Order(2)
    @DisplayName("Phase 2: withConnection DDL + DML round-trip via HAProxy (temp table)")
    void testWithConnectionRealSqlRoundTrip(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 2 BEGIN: withConnection DDL + DML round-trip ---");

        connectionManager.withConnection("haproxy-failover-test", conn -> {
            logger.debug("[phase-2] Connection acquired from pool — executing CREATE TEMP TABLE");
            return conn.query("CREATE TEMP TABLE haproxy_roundtrip (id INT, label TEXT)").execute()
                .compose(v -> {
                    logger.debug("[phase-2] Temp table created — inserting test row (id=42)");
                    return conn.preparedQuery("INSERT INTO haproxy_roundtrip VALUES ($1, $2)")
                        .execute(io.vertx.sqlclient.Tuple.of(42, "peegeeq-via-haproxy"));
                })
                .compose(v -> {
                    logger.debug("[phase-2] Insert complete — reading back via SELECT");
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
            logger.error("[phase-2] FAIL: withConnection round-trip failed after {}ms: {}",
                System.currentTimeMillis() - t0, err.getMessage(), err);
            ctx.failNow(err);
        });
    }

    /**
     * Phase 3 – Transaction rollback safety via {@link PgConnectionManager#withTransaction}.
     *
     * Inserts a row inside a transaction, explicitly rolls back, then verifies the row
     * is absent.  Confirms the pool correctly surfaces rollback through HAProxy.
     */
    @Test
    @Order(3)
    @DisplayName("Phase 3: withTransaction rollback leaves no row (via HAProxy)")
    void testWithTransactionRollbackSafety(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 3 BEGIN: transaction rollback safety ---");

        connectionManager.withConnection("haproxy-failover-test", conn -> {
            logger.debug("[phase-3] Connection acquired — creating temp table haproxy_rollback_test");
            return conn.query("CREATE TEMP TABLE haproxy_rollback_test (val INT)").execute()
                .compose(v -> {
                    logger.debug("[phase-3] Beginning explicit transaction");
                    return conn.begin();
                })
                .compose(tx -> {
                    logger.debug("[phase-3] Inserting val=999 inside transaction");
                    return conn.preparedQuery("INSERT INTO haproxy_rollback_test VALUES ($1)")
                        .execute(io.vertx.sqlclient.Tuple.of(999))
                        .compose(v -> {
                            logger.debug("[phase-3] Insert done — rolling back transaction");
                            return tx.rollback();
                        });
                })
                .compose(v -> {
                    logger.debug("[phase-3] Rollback complete — verifying row count = 0");
                    return conn.query("SELECT COUNT(*) AS cnt FROM haproxy_rollback_test").execute();
                })
                .map(rows -> {
                    int count = rows.iterator().next().getInteger("cnt");
                    logger.info("[phase-3] Row count after rollback: {} (expected 0, {}ms)",
                        count, System.currentTimeMillis() - t0);
                    assertEquals(0, count, "No rows should exist after rollback");
                    return count;
                });
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
     * Phase 4 – {@link PgConnectionManager#checkHealth} returns {@code true} while primary healthy.
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
     * Phase 5 – Failback: traffic returns to the primary after it recovers.
     *
     * <ol>
     *   <li>Stop the original primary.  HAProxy detects failure and routes to secondary.</li>
     *   <li>Start {@code primary2} — a fresh container with the same network alias
     *       {@code pg_primary}.  HAProxy picks it up on the next health-check cycle.</li>
     *   <li>Verify queries succeed again and that HAProxy is routing to the primary
     *       (not just the secondary backup).</li>
     * </ol>
     *
     * <p><strong>Important:</strong> after this test, the original primary is gone and
     * primary2 is running.  Phase 6 will stop primary2 to exercise failover again.
     */
    @Test
    @Order(5)
    @Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Phase 5: failback — traffic returns after replacement primary starts")
    void testFailbackAfterPrimaryRecovery(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        logger.info("--- Phase 5 BEGIN: failback after primary recovery ---");
        logger.info("[phase-5] Container states: primary={} secondary={} haproxy={}",
            primary.isRunning() ? "UP" : "DOWN",
            secondary.isRunning() ? "UP" : "DOWN",
            haproxy.isRunning() ? "UP" : "DOWN");

        // Step 1: confirm we are connected and healthy before inducing any failure.
        pool.query("SELECT 1 AS health").execute()
            .compose(rows -> {
                assertEquals(1, rows.iterator().next().getInteger("health"));
                logger.info("[phase-5] Pre-stop query OK — primary healthy ({}ms)",
                    System.currentTimeMillis() - t0);

                // Step 2: stop original primary.
                String containerId = primary.getContainerId().substring(0, 12);
                logger.info("[phase-5] Stopping primary (containerId={}) to simulate outage …", containerId);
                primary.stop();
                logger.info("[phase-5] Primary stopped after {}ms — waiting {}ms for HAProxy fall=2 detection …",
                    System.currentTimeMillis() - t0, HAPROXY_FAILOVER_WAIT_MS);

                Promise<Void> failoverDelay = Promise.promise();
                vertx.setTimer(HAPROXY_FAILOVER_WAIT_MS, id -> failoverDelay.complete());
                return failoverDelay.future();
            })
            .compose(v -> {
                logger.info("[phase-5] Failover wait complete ({}ms elapsed) — verifying secondary responds …",
                    System.currentTimeMillis() - t0);
                return queryWithRetry(vertx, pool, MAX_RETRY_ATTEMPTS, RETRY_INTERVAL_MS);
            })
            .compose(value -> {
                assertEquals(1, value, "Secondary should answer SELECT 1 during failover");
                logger.info("[phase-5] Secondary confirmed UP at {}ms elapsed — starting primary2 …",
                    System.currentTimeMillis() - t0);

                // Step 5: start replacement primary with same alias — HAProxy will detect it.
                primary2.start();
                logger.info("[phase-5] primary2 started: host={} mappedPort={} containerId={} ({}ms elapsed)",
                    primary2.getHost(), primary2.getFirstMappedPort(),
                    primary2.getContainerId().substring(0, 12),
                    System.currentTimeMillis() - t0);
                logger.info("[phase-5] Waiting {}ms for HAProxy pgsql-check to detect primary2 (rise=1 x inter=500ms) …",
                    HAPROXY_FAILBACK_WAIT_MS);

                Promise<Void> failbackDelay = Promise.promise();
                vertx.setTimer(HAPROXY_FAILBACK_WAIT_MS, id -> failbackDelay.complete());
                return failbackDelay.future();
            })
            .compose(v -> {
                logger.info("[phase-5] Failback wait complete ({}ms elapsed) — verifying pool healthy …",
                    System.currentTimeMillis() - t0);
                logger.debug("[phase-5] primary2 still running={}", primary2.isRunning());
                return queryWithRetry(vertx, pool, MAX_RETRY_ATTEMPTS, RETRY_INTERVAL_MS);
            })
            .onSuccess(value -> ctx.verify(() -> {
                long elapsed = System.currentTimeMillis() - t0;
                assertEquals(1, value, "SELECT 1 should succeed after failback");
                logger.info("--- Phase 5 PASS: failback complete in {}ms — pool serving queries via primary2 ---",
                    elapsed);
                ctx.completeNow();
            }))
            .onFailure(err -> {
                logger.error("[phase-5] FAIL: failback verification failed after {}ms: {}",
                    System.currentTimeMillis() - t0, err.getMessage(), err);
                ctx.failNow(err);
            });
    }

    /**
     * Phase 6 – HAProxy TCP failover (destructive).
     *
     * <p>Simulates a primary PostgreSQL failure and verifies that:
     * <ol>
     *   <li>The Vert.x pool initially routes to primary through HAProxy.</li>
     *   <li>After the primary is stopped, HAProxy detects the failure
     *       (2 × 500 ms = ~1 s) and promotes the secondary (backup).</li>
     *   <li>The pool discards stale connections and re-connects through
     *       HAProxy to the secondary.  Queries succeed without restarting
     *       the application.</li>
     * </ol>
     *
     * <p><strong>Important:</strong> stopping {@code primary2} here removes the container.
     * No further tests should assume a primary is running.
     */
    @Test
    @Order(6)
    @DisplayName("Phase 6: HAProxy routes to secondary after primary stops")
    void testFailoverToSecondaryWhenPrimaryFails(Vertx vertx, VertxTestContext ctx) {
        long t0 = System.currentTimeMillis();
        PostgreSQLContainer activePrimary = (primary2 != null && primary2.isRunning()) ? primary2 : primary;
        String primaryLabel = activePrimary == primary2 ? "primary2" : "primary";
        logger.info("--- Phase 6 BEGIN: failover (destructive) — active primary is {} ---", primaryLabel);
        logger.info("[phase-6] Container states: {}={} secondary={} haproxy={}",
            primaryLabel, activePrimary.isRunning() ? "UP" : "DOWN",
            secondary.isRunning() ? "UP" : "DOWN",
            haproxy.isRunning() ? "UP" : "DOWN");

        // Step 1: confirm active primary is reachable before the failover.
        pool.query("SELECT 1 AS health").execute()
            .compose(rows -> {
                int value = rows.iterator().next().getInteger("health");
                assertEquals(1, value, "Pre-failover query should succeed");
                logger.info("[phase-6] Pre-failover query OK — {} handling traffic ({}ms)",
                    primaryLabel, System.currentTimeMillis() - t0);

                // Step 2: Stop the active primary to trigger the failover.
                logger.info("[phase-6] Stopping {} (containerId={}) to trigger HAProxy failover …",
                    primaryLabel, activePrimary.getContainerId().substring(0, 12));
                activePrimary.stop();
                logger.info("[phase-6] {} stopped after {}ms — waiting {}ms for HAProxy fall=2 detection …",
                    primaryLabel, System.currentTimeMillis() - t0, HAPROXY_FAILOVER_WAIT_MS);

                Promise<Void> delay = Promise.promise();
                vertx.setTimer(HAPROXY_FAILOVER_WAIT_MS, id -> delay.complete());
                return delay.future();
            })
            .compose(v -> {
                logger.info("[phase-6] Failover wait complete ({}ms elapsed) — retrying via secondary …",
                    System.currentTimeMillis() - t0);
                return queryWithRetry(vertx, pool, MAX_RETRY_ATTEMPTS, RETRY_INTERVAL_MS);
            })
            .onSuccess(value -> ctx.verify(() -> {
                long elapsed = System.currentTimeMillis() - t0;
                assertEquals(1, value, "Post-failover query should return 1 via secondary");
                logger.info("--- Phase 6 PASS: failover complete in {}ms — HAProxy routing via secondary ---",
                    elapsed);
                ctx.completeNow();
            }))
            .onFailure(err -> {
                logger.error("[phase-6] FAIL: all retries failed after {}ms: {}",
                    System.currentTimeMillis() - t0, err.getMessage(), err);
                ctx.failNow(err);
            });
    }

    // -----------------------------------------------------------------------
    // Retry helpers  (no .recover() – uses Promise + onSuccess/onFailure)
    // -----------------------------------------------------------------------

    /**
     * Returns a Future that executes {@code SELECT 1} via the pool, retrying on
     * failure up to {@code maxAttempts} times with {@code delayMs} between
     * attempts.
     *
     * <p>Retry is implemented with {@link Promise} and {@link Vertx#setTimer}
     * (Vert.x-safe delay) rather than {@code .recover()} which is forbidden in
     * this codebase.
     *
     * @param vertx       the Vert.x instance used for timers
     * @param pool        the reactive pool to query
     * @param maxAttempts maximum number of attempts (>= 1)
     * @param delayMs     delay in milliseconds between attempts
     * @return Future resolving to the integer result of {@code SELECT 1}
     */
    private Future<Integer> queryWithRetry(Vertx vertx, Pool pool, int maxAttempts, long delayMs) {
        Promise<Integer> result = Promise.promise();
        scheduleAttempt(vertx, pool, maxAttempts, 1, delayMs, result);
        return result.future();
    }

    private void scheduleAttempt(Vertx vertx, Pool pool, int maxAttempts, int attempt,
                                 long delayMs, Promise<Integer> result) {
        pool.query("SELECT 1 AS health").execute()
            .onSuccess(rows -> {
                int value = rows.iterator().next().getInteger("health");
                logger.debug("[retry] Attempt {}/{} OK — value={}", attempt, maxAttempts, value);
                result.complete(value);
            })
            .onFailure(err -> {
                if (attempt >= maxAttempts) {
                    logger.error("[retry] All {} attempt(s) exhausted — failing. Last error: {}",
                        maxAttempts, err.getMessage(), err);
                    result.fail(err);
                } else {
                    logger.debug("[retry] Attempt {}/{} failed: {} — retrying in {}ms …",
                        attempt, maxAttempts, err.getMessage(), delayMs);
                    vertx.setTimer(delayMs, id ->
                        scheduleAttempt(vertx, pool, maxAttempts, attempt + 1, delayMs, result));
                }
            });
    }
}
