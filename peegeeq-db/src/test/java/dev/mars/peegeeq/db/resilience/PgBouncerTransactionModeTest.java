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
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
class PgBouncerTransactionModeTest {

    private static final String DATABASE = "peegeeq_pgbouncer";
    private static final String USERNAME = "peegeeq_test";
    private static final String PASSWORD = "peegeeq_test";
    private static final String TENANT_A = "tenant_a";
    private static final String TENANT_B = "tenant_b";
    private static final String SETUP_SERVICE = "pgbouncer-setup";
    private static final String TENANT_A_SERVICE = "pgbouncer-tenant-a";
    private static final String TENANT_B_SERVICE = "pgbouncer-tenant-b";

    private static Network network;

    @SuppressWarnings("resource")
    private static PostgreSQLContainer postgres;

    @SuppressWarnings("resource")
    private static GenericContainer<?> pgbouncer;

    private PgConnectionManager connectionManager;

    @BeforeAll
    static void startInfrastructure() {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withDatabaseName(DATABASE)
            .withUsername(USERNAME)
            .withPassword(PASSWORD);
        postgres.start();

        pgbouncer = new GenericContainer<>("edoburu/pgbouncer:v1.25.2-p0")
            .withNetwork(network)
            .withEnv("DB_HOST", "postgres")
            .withEnv("DB_PORT", "5432")
            .withEnv("DB_NAME", DATABASE)
            .withEnv("DB_USER", USERNAME)
            .withEnv("DB_PASSWORD", PASSWORD)
            .withEnv("AUTH_TYPE", "scram-sha-256")
            .withEnv("POOL_MODE", "transaction")
            .withEnv("MAX_CLIENT_CONN", "20")
            .withEnv("DEFAULT_POOL_SIZE", "1")
            .withEnv("MAX_PREPARED_STATEMENTS", "32")
            .withEnv("IGNORE_STARTUP_PARAMETERS", "extra_float_digits,search_path")
            .withEnv("SERVER_RESET_QUERY", "DISCARD ALL")
            .withEnv("SERVER_RESET_QUERY_ALWAYS", "1")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
        pgbouncer.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (pgbouncer != null && pgbouncer.isRunning()) pgbouncer.stop();
        if (postgres != null && postgres.isRunning()) postgres.stop();
        if (network != null) network.close();
    }

    @AfterEach
    void closePools(VertxTestContext testContext) {
        if (connectionManager == null) {
            testContext.completeNow();
            return;
        }
        connectionManager.close()
            .onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void composeEquivalentTransactionPoolingPreservesTenantSearchPath(
            Vertx vertx, VertxTestContext testContext) {
        connectionManager = new PgConnectionManager(vertx);
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(2)
            .shared(false)
            .connectionTimeout(Duration.ofSeconds(5))
            .idleTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool(
            SETUP_SERVICE,
            connectionConfig(postgres.getHost(), postgres.getFirstMappedPort(), "public"),
            poolConfig);
        connectionManager.getOrCreateReactivePool(
            TENANT_A_SERVICE,
            connectionConfig(pgbouncer.getHost(), pgbouncer.getMappedPort(5432), TENANT_A),
            poolConfig);
        connectionManager.getOrCreateReactivePool(
            TENANT_B_SERVICE,
            connectionConfig(pgbouncer.getHost(), pgbouncer.getMappedPort(5432), TENANT_B),
            poolConfig);

        List<TransactionObservation> observations = new ArrayList<>();

        initializeTenantSchemas()
            .compose(ignored -> sendMessage(
                TENANT_A_SERVICE, 1, "tenant-a-message", "tenant-a-session"))
            .compose(observation -> {
                observations.add(observation);
                return sendMessage(
                    TENANT_B_SERVICE, 2, "tenant-b-message", "tenant-b-session");
            })
            .compose(observation -> {
                observations.add(observation);
                return readMessage(TENANT_A_SERVICE, 1);
            })
            .compose(observation -> {
                observations.add(observation);
                return readMessage(TENANT_B_SERVICE, 2);
            })
            .compose(observation -> {
                observations.add(observation);
                return consumeMessage(TENANT_A_SERVICE, 1);
            })
            .compose(observation -> {
                observations.add(observation);
                return consumeMessage(TENANT_B_SERVICE, 2);
            })
            .compose(observation -> {
                observations.add(observation);
                return countRemainingMessages();
            })
            .onComplete(testContext.succeeding(remaining -> testContext.verify(() -> {
                assertEquals(6, observations.size());
                assertEquals(TENANT_A, observations.get(0).schema());
                assertEquals(TENANT_B, observations.get(1).schema());
                assertEquals(TENANT_A, observations.get(2).schema());
                assertEquals(TENANT_B, observations.get(3).schema());
                assertEquals("tenant-a-message", observations.get(2).payload());
                assertEquals("tenant-b-message", observations.get(3).payload());
                assertEquals(TENANT_A, observations.get(4).schema());
                assertEquals(TENANT_B, observations.get(5).schema());
                assertEquals("tenant-a-message", observations.get(4).payload());
                assertEquals("tenant-b-message", observations.get(5).payload());
                assertTrue(observations.subList(1, observations.size()).stream()
                        .allMatch(observation -> observation.sessionMarker() == null
                            || observation.sessionMarker().isBlank()),
                    "DISCARD ALL must remove session state before the backend is reused");
                assertEquals(1, new HashSet<>(observations.stream()
                        .map(TransactionObservation::backendPid)
                        .toList()).size(),
                    "All client transactions must be multiplexed onto one backend connection");
                assertEquals(0L, remaining,
                    "Each tenant must consume only the message from its configured schema");
                testContext.completeNow();
            })));
    }

    private PgConnectionConfig connectionConfig(String host, int port, String schema) {
        return new PgConnectionConfig.Builder()
            .host(host)
            .port(port)
            .database(DATABASE)
            .username(USERNAME)
            .password(PASSWORD)
            .schema(schema)
            .build();
    }

    private Future<Void> initializeTenantSchemas() {
        return connectionManager.withTransaction(SETUP_SERVICE, connection ->
            connection.query("CREATE SCHEMA IF NOT EXISTS tenant_a").execute()
                .compose(ignored -> connection.query(
                    "CREATE SCHEMA IF NOT EXISTS tenant_b").execute())
                .compose(ignored -> connection.query("""
                    CREATE TABLE IF NOT EXISTS tenant_a.transaction_pool_messages (
                        id INTEGER PRIMARY KEY,
                        payload TEXT NOT NULL
                    )
                    """).execute())
                .compose(ignored -> connection.query("""
                    CREATE TABLE IF NOT EXISTS tenant_b.transaction_pool_messages (
                        id INTEGER PRIMARY KEY,
                        payload TEXT NOT NULL
                    )
                    """).execute())
                .compose(ignored -> connection.query(
                    "TRUNCATE tenant_a.transaction_pool_messages, "
                        + "tenant_b.transaction_pool_messages").execute())
                .mapEmpty());
    }

    private Future<TransactionObservation> sendMessage(
            String serviceId, int id, String payload, String sessionMarker) {
        return connectionManager.withTransaction(serviceId, connection ->
            observeTransaction(connection)
                .compose(observation -> connection.preparedQuery(
                    "INSERT INTO transaction_pool_messages (id, payload) VALUES ($1, $2)")
                    .execute(Tuple.of(id, payload))
                    .compose(ignored -> connection.preparedQuery(
                        "SELECT set_config('peegeeq.session_marker', $1, false)")
                        .execute(Tuple.of(sessionMarker)))
                    .map(observation)));
    }

    private Future<TransactionObservation> consumeMessage(String serviceId, int id) {
        return connectionManager.withTransaction(serviceId, connection ->
            observeTransaction(connection)
                .compose(observation -> connection.preparedQuery(
                    "DELETE FROM transaction_pool_messages WHERE id = $1 RETURNING payload")
                    .execute(Tuple.of(id))
                    .map(rows -> new TransactionObservation(
                        observation.schema(),
                        observation.backendPid(),
                        observation.sessionMarker(),
                        rows.iterator().next().getString("payload")))));
    }

    private Future<TransactionObservation> readMessage(String serviceId, int id) {
        return connectionManager.withConnection(serviceId, connection ->
            observeTransaction(connection)
                .compose(observation -> connection.preparedQuery(
                    "SELECT payload FROM transaction_pool_messages WHERE id = $1")
                    .execute(Tuple.of(id))
                    .map(rows -> new TransactionObservation(
                        observation.schema(),
                        observation.backendPid(),
                        observation.sessionMarker(),
                        rows.iterator().next().getString("payload")))));
    }

    private Future<TransactionObservation> observeTransaction(io.vertx.sqlclient.SqlConnection connection) {
        return connection.query("""
            SELECT current_schema() AS current_schema,
                   pg_backend_pid() AS backend_pid,
                   current_setting('peegeeq.session_marker', true) AS session_marker
            """).execute()
            .map(rows -> {
                Row row = rows.iterator().next();
                return new TransactionObservation(
                    row.getString("current_schema"),
                    row.getInteger("backend_pid"),
                    row.getString("session_marker"),
                    null);
            });
    }

    private Future<Long> countRemainingMessages() {
        return connectionManager.withConnection(SETUP_SERVICE, connection ->
            connection.query("""
                SELECT (SELECT COUNT(*) FROM tenant_a.transaction_pool_messages)
                     + (SELECT COUNT(*) FROM tenant_b.transaction_pool_messages) AS remaining
                """).execute()
                .map(rows -> rows.iterator().next().getLong("remaining")));
    }

    private record TransactionObservation(
        String schema,
        int backendPid,
        String sessionMarker,
        String payload) {
    }
}
