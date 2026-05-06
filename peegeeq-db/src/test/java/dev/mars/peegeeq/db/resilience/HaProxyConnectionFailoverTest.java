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

import java.time.Duration;

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
 *   <li>Normal operation: the Vert.x reactive pool routes all traffic through
 *       HAProxy to the primary PostgreSQL.</li>
 *   <li>Failover: when the primary is stopped, HAProxy detects the failure
 *       (2 × 500 ms health-check interval) and routes new connections to the
 *       secondary.  The Vert.x pool discards its broken connections and
 *       re-connects through HAProxy to the secondary, making subsequent
 *       queries succeed without application restart.</li>
 * </ol>
 *
 * <h2>Production note</h2>
 * In production, primary and secondary would be connected via PostgreSQL
 * streaming replication so that secondary has the same data.  This test uses
 * two independent PostgreSQL instances because it targets connection-level
 * resilience (can the pool reconnect?), not data consistency.
 *
 * <h2>Test ordering</h2>
 * Tests run in declared order.  Phase-2 (failover) stops the primary container,
 * so all earlier tests must complete first.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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
        network = Network.newNetwork();

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
        primary.start();
        secondary.start();

        logger.info("Primary   PostgreSQL: {}:{}", primary.getHost(), primary.getFirstMappedPort());
        logger.info("Secondary PostgreSQL: {}:{}", secondary.getHost(), secondary.getFirstMappedPort());

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

        haproxy.start();

        logger.info("HAProxy: {}:{} → pg_primary:5432 / pg_secondary:5432 (backup)",
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT));
    }

    @AfterAll
    static void stopInfrastructure() {
        // Stop in reverse order; primary may already be stopped by the failover test.
        if (haproxy    != null && haproxy.isRunning())    haproxy.stop();
        if (secondary  != null && secondary.isRunning())  secondary.stop();
        if (primary    != null && primary.isRunning())    primary.stop();
        if (network    != null)                           network.close();
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

        logger.info("[setup] Pool created – connecting via HAProxy {}:{}",
            haproxy.getHost(), haproxy.getMappedPort(HAPROXY_PG_PORT));
        ctx.completeNow();
    }

    @AfterEach
    void closePool(Vertx vertx, VertxTestContext ctx) {
        connectionManager.close()
            .eventually(() -> vertx.close())
            .onSuccess(v -> ctx.completeNow())
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
        pool.query("SELECT 1 AS health").execute()
            .onSuccess(rows -> {
                int value = rows.iterator().next().getInteger("health");
                logger.info("[phase-1] SELECT 1 returned {} via HAProxy → primary", value);
                assertEquals(1, value, "SELECT 1 should return 1 when primary is healthy");
                ctx.completeNow();
            })
            .onFailure(err -> {
                logger.error("[phase-1] Query failed", err);
                ctx.failNow(err);
            });
    }

    /**
     * Phase 2 – HAProxy TCP failover.
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
     * <p><strong>Important:</strong> stopping {@code primary} here removes the container.
     * No further tests should assume primary is running.
     */
    @Test
    @Order(2)
    @DisplayName("Phase 2: HAProxy routes to secondary after primary stops")
    void testFailoverToSecondaryWhenPrimaryFails(Vertx vertx, VertxTestContext ctx) {
        // Step 1: confirm primary is reachable before the failover.
        pool.query("SELECT 1 AS health").execute()
            .compose(rows -> {
                int value = rows.iterator().next().getInteger("health");
                assertEquals(1, value, "Pre-failover query should succeed");
                logger.info("[phase-2] Pre-failover query confirmed – primary is handling traffic");

                // Step 2: Stop primary to trigger the failover.
                logger.info("[phase-2] Stopping primary PostgreSQL to trigger HAProxy failover …");
                primary.stop();
                logger.info("[phase-2] Primary stopped.  Waiting {}ms for HAProxy health-check …",
                    HAPROXY_FAILOVER_WAIT_MS);

                // Step 3: Wait for HAProxy to promote the secondary backup.
                //         fall=2 × inter=500ms → ~1 s detection + 3 s buffer.
                Promise<Void> delay = Promise.promise();
                vertx.setTimer(HAPROXY_FAILOVER_WAIT_MS, id -> delay.complete());
                return delay.future();
            })
            .compose(v -> {
                // Step 4: Retry SELECT 1 via HAProxy – should now reach the secondary.
                logger.info("[phase-2] Attempting queries via HAProxy after failover (secondary should respond) …");
                return queryWithRetry(vertx, pool, MAX_RETRY_ATTEMPTS, RETRY_INTERVAL_MS);
            })
            .onSuccess(value -> {
                assertEquals(1, value, "Post-failover query should return 1 via secondary");
                logger.info("[phase-2] SUCCESS – HAProxy is routing to secondary; value={}", value);
                ctx.completeNow();
            })
            .onFailure(err -> {
                logger.error("[phase-2] All retry attempts failed after HAProxy failover", err);
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
                logger.info("[retry] Attempt {}/{} succeeded – value={}", attempt, maxAttempts, value);
                result.complete(value);
            })
            .onFailure(err -> {
                if (attempt >= maxAttempts) {
                    logger.warn("[retry] All {} attempt(s) failed. Last error: {}",
                        maxAttempts, err.getMessage());
                    result.fail(err);
                } else {
                    logger.info("[retry] Attempt {}/{} failed: {}. Retrying in {}ms …",
                        attempt, maxAttempts, err.getMessage(), delayMs);
                    vertx.setTimer(delayMs, id ->
                        scheduleAttempt(vertx, pool, maxAttempts, attempt + 1, delayMs, result));
                }
            });
    }
}
