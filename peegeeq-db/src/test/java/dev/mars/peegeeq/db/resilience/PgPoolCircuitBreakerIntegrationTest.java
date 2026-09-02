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

import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.PgTestImageConstant;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
class PgPoolCircuitBreakerIntegrationTest {

    private static final String SECONDARY_SERVICE = "circuit-breaker-secondary";
    private static final String DEFAULT_BREAKER = "db.pool." + PeeGeeQDefaults.DEFAULT_POOL_ID;
    private static final long RECOVERY_DEADLINE_MS = 20_000L;
    private static final int HAPROXY_PORT = 5400;
    private static final String DATABASE = "peegeeq_circuit_breaker";
    private static final String USERNAME = "peegeeq_test";
    private static final String PASSWORD = "peegeeq_test";

    @SuppressWarnings("resource")
    private static PostgreSQLContainer primary;

    @SuppressWarnings("resource")
    private static PostgreSQLContainer replacement;

    @SuppressWarnings("resource")
    private static GenericContainer<?> haproxy;

    private static Network network;

    private PeeGeeQManager manager;

    @BeforeAll
    static void startPostgres() {
        network = Network.newNetwork();
        primary = postgresNode();
        replacement = postgresNode();
        primary.start();

        haproxy = new GenericContainer<>("haproxy:2.8-alpine")
            .withNetwork(network)
            .withClasspathResourceMapping(
                "haproxy-circuit-breaker.cfg",
                "/usr/local/etc/haproxy/haproxy.cfg",
                BindMode.READ_ONLY)
            .withExposedPorts(HAPROXY_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
        haproxy.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (haproxy != null && haproxy.isRunning()) haproxy.stop();
        if (replacement != null && replacement.isRunning()) replacement.stop();
        if (primary != null && primary.isRunning()) primary.stop();
        if (network != null) network.close();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (manager == null) {
            testContext.completeNow();
            return;
        }
        manager.closeReactive()
            .onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void poolBreakersOpenIndependentlyAndRecoverAgainstRealPostgres(
            Vertx vertx, VertxTestContext testContext) {
        PeeGeeQConfiguration configuration = configuration();
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry(), vertx);
        manager.getClientFactory().createClient(
            SECONDARY_SERVICE, configuration.getDatabaseConfig(), configuration.getPoolConfig());

        PgConnectionManager connectionManager = manager.getClientFactory().getConnectionManager();
        CircuitBreakerManager breakerManager = manager.getCircuitBreakerManager();
        long[] transactionMetricsBefore = new long[2];

        connectionManager.withConnection(PeeGeeQDefaults.DEFAULT_POOL_ID,
                connection -> connection.query("SELECT 1").execute().mapEmpty())
            .compose(ignored -> stopPostgresAsync(vertx))
            .compose(ignored -> expectFailure(
                connectionManager.withConnection(PeeGeeQDefaults.DEFAULT_POOL_ID,
                    connection -> connection.query("SELECT 1").execute().mapEmpty()),
                failure -> assertFalse(failure instanceof CallNotPermittedException,
                    "The first outage failure must be the original pool/database error")))
            .compose(ignored -> expectFailure(
                connectionManager.withConnection(PeeGeeQDefaults.DEFAULT_POOL_ID,
                    connection -> connection.query("SELECT 1").execute().mapEmpty()),
                failure -> assertFalse(failure instanceof CallNotPermittedException,
                    "The threshold failure must still be the original pool/database error")))
            .compose(ignored -> {
                assertEquals("OPEN", breakerManager.getMetrics(DEFAULT_BREAKER).getState());
                boolean[] transactionInvoked = {false};
                return expectFailure(
                    connectionManager.withTransaction(PeeGeeQDefaults.DEFAULT_POOL_ID, connection -> {
                        transactionInvoked[0] = true;
                        return connection.query("SELECT 1").execute().mapEmpty();
                    }),
                    failure -> {
                        CallNotPermittedException rejection =
                            assertInstanceOf(CallNotPermittedException.class, failure);
                        assertEquals(DEFAULT_BREAKER, rejection.getCausingCircuitBreakerName());
                        assertFalse(transactionInvoked[0],
                            "An open breaker must reject before transaction acquisition");
                    });
            })
            .compose(ignored -> startPostgresAsync(vertx))
            .compose(ignored -> waitForDatabase(vertx, connectionManager, SECONDARY_SERVICE))
            .compose(ignored -> connectionManager.withTransaction(SECONDARY_SERVICE,
                connection -> connection.query("SELECT 1 AS value").execute()
                    .map(rows -> rows.iterator().next().getInteger("value"))))
            .compose(value -> {
                assertEquals(1, value);
                assertEquals("CLOSED", breakerManager.getMetrics("db.pool." + SECONDARY_SERVICE).getState());
                assertFalse("CLOSED".equals(breakerManager.getMetrics(DEFAULT_BREAKER).getState()),
                    "Recovery through another pool must not close the default pool's breaker");
                return waitForBreakerState(vertx, breakerManager, DEFAULT_BREAKER,
                    CircuitBreaker.State.HALF_OPEN);
            })
            .compose(ignored -> connectionManager.withConnection(PeeGeeQDefaults.DEFAULT_POOL_ID,
                connection -> connection.query("SELECT 1").execute().mapEmpty()))
            .compose(ignored -> {
                assertEquals("CLOSED", breakerManager.getMetrics(DEFAULT_BREAKER).getState());
                CircuitBreakerManager.CircuitBreakerMetrics metrics = breakerManager.getMetrics(DEFAULT_BREAKER);
                transactionMetricsBefore[0] = metrics.getSuccessfulCalls();
                transactionMetricsBefore[1] = metrics.getFailedCalls();
                return expectFailure(
                    connectionManager.withTransaction(PeeGeeQDefaults.DEFAULT_POOL_ID,
                        connection -> connection.query(
                            "SELECT * FROM task_3_2_table_that_does_not_exist").execute().mapEmpty()),
                    failure -> assertInstanceOf(PgException.class, failure,
                        "The transaction's original PostgreSQL error must propagate"));
            })
            .compose(ignored -> {
                CircuitBreakerManager.CircuitBreakerMetrics metrics = breakerManager.getMetrics(DEFAULT_BREAKER);
                assertEquals(transactionMetricsBefore[1] + 1, metrics.getFailedCalls());
                return connectionManager.withTransaction(PeeGeeQDefaults.DEFAULT_POOL_ID,
                    connection -> connection.query("SELECT 1").execute().mapEmpty());
            })
            .onSuccess(ignored -> testContext.verify(() -> {
                CircuitBreakerManager.CircuitBreakerMetrics metrics = breakerManager.getMetrics(DEFAULT_BREAKER);
                assertEquals(transactionMetricsBefore[0] + 1, metrics.getSuccessfulCalls());
                assertEquals(transactionMetricsBefore[1] + 1, metrics.getFailedCalls());
                assertEquals("CLOSED", metrics.getState());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private static PeeGeeQConfiguration configuration() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.database.host", haproxy.getHost());
        properties.setProperty("peegeeq.database.port", String.valueOf(haproxy.getMappedPort(HAPROXY_PORT)));
        properties.setProperty("peegeeq.database.name", DATABASE);
        properties.setProperty("peegeeq.database.username", USERNAME);
        properties.setProperty("peegeeq.database.password", PASSWORD);
        properties.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);
        properties.setProperty("peegeeq.database.ssl.enabled", "false");
        properties.setProperty("peegeeq.database.pool.max-size", "2");
        properties.setProperty("peegeeq.database.pool.shared", "false");
        properties.setProperty("peegeeq.database.pool.connection-timeout-ms", "1000");
        properties.setProperty("peegeeq.database.pool.idle-timeout-ms", "1000");
        properties.setProperty("peegeeq.metrics.enabled", "false");
        properties.setProperty("peegeeq.circuit-breaker.enabled", "true");
        properties.setProperty("peegeeq.circuit-breaker.failure-rate-threshold", "100.0");
        properties.setProperty("peegeeq.circuit-breaker.minimum-number-of-calls", "2");
        properties.setProperty("peegeeq.circuit-breaker.sliding-window-size", "2");
        properties.setProperty("peegeeq.circuit-breaker.wait-duration-in-open-state", "PT1S");
        properties.setProperty("peegeeq.circuit-breaker.permitted-calls-in-half-open-state", "1");
        properties.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        properties.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        return new PeeGeeQConfiguration("circuit-breaker-integration", properties);
    }

    private static Future<Void> stopPostgresAsync(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            primary.stop();
            return null;
        });
    }

    private static Future<Void> startPostgresAsync(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            replacement.start();
            return null;
        });
    }

    private static PostgreSQLContainer postgresNode() {
        return new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pg_circuit_breaker")
            .withDatabaseName(DATABASE)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .withInitScript("haproxy-check-init.sql");
    }

    private static Future<Void> waitForDatabase(
            Vertx vertx, PgConnectionManager connectionManager, String serviceId) {
        Promise<Void> result = Promise.promise();
        attemptDatabaseProbe(vertx, connectionManager, serviceId,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_DEADLINE_MS), result);
        return result.future();
    }

    private static void attemptDatabaseProbe(
            Vertx vertx,
            PgConnectionManager connectionManager,
            String serviceId,
            long deadlineNanos,
            Promise<Void> result) {
        connectionManager.getReactiveConnection(serviceId)
            .compose(connection -> connection.query("SELECT 1").execute().mapEmpty()
                .eventually(connection::close))
            .onSuccess(ignored -> result.complete())
            .onFailure(failure -> {
                if (System.nanoTime() >= deadlineNanos) {
                    result.fail(failure);
                    return;
                }
                vertx.setTimer(100L, ignored -> attemptDatabaseProbe(
                    vertx, connectionManager, serviceId, deadlineNanos, result));
            });
    }

    private static Future<Void> waitForBreakerState(
            Vertx vertx,
            CircuitBreakerManager breakerManager,
            String breakerName,
            CircuitBreaker.State expectedState) {
        Promise<Void> result = Promise.promise();
        pollBreakerState(vertx, breakerManager, breakerName, expectedState,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_DEADLINE_MS), result);
        return result.future();
    }

    private static void pollBreakerState(
            Vertx vertx,
            CircuitBreakerManager breakerManager,
            String breakerName,
            CircuitBreaker.State expectedState,
            long deadlineNanos,
            Promise<Void> result) {
        CircuitBreaker.State actualState = breakerManager.getCircuitBreaker(breakerName).getState();
        if (actualState == expectedState) {
            result.complete();
            return;
        }
        if (System.nanoTime() >= deadlineNanos) {
            result.fail(new AssertionError(
                "Circuit breaker " + breakerName + " remained " + actualState
                    + ", expected " + expectedState));
            return;
        }
        vertx.setTimer(25L, ignored -> pollBreakerState(
            vertx, breakerManager, breakerName, expectedState, deadlineNanos, result));
    }

    private static <T> Future<Void> expectFailure(
            Future<T> operation, Consumer<Throwable> assertion) {
        Promise<Void> result = Promise.promise();
        operation
            .onSuccess(value -> result.fail(new AssertionError("Expected operation to fail")))
            .onFailure(failure -> {
                try {
                    assertion.accept(failure);
                    result.complete();
                } catch (AssertionError assertionFailure) {
                    result.fail(assertionFailure);
                }
            });
        return result.future();
    }
}
