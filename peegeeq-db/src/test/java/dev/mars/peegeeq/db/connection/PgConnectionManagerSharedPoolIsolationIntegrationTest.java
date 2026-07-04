package dev.mars.peegeeq.db.connection;

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

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression guard for the Phase F1 root cause: {@code PgConnectionManager.createReactivePool} built a
 * Vert.x <em>shared</em> pool ({@code setShared(true)}) with no explicit name. Vert.x shared pools are
 * keyed by <b>(Vert.x instance, pool name)</b>; with the default name, two pools to <em>different</em>
 * databases on the <em>same</em> Vert.x collapse onto one underlying pool pinned to the first — so a
 * second setup's pooled operations (produce, etc.) silently execute against the first setup's database.
 * Full analysis: {@code docs-design/tasks/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md}
 * ("F1 root cause"). The fix names the pool by its full connection identity.
 *
 * <p>This test forces the exact hazardous shape — two SHARED pools, the same service id
 * ({@code "peegeeq-main"}, as production uses), two different databases, ONE Vert.x — and asserts each
 * pool connects to its own database. Without the unique-name fix both pools report the first database
 * and the second assertion fails; with the fix each routes correctly. It is the tight, unit-level lock
 * for the fix, complementing the setup-level {@code MultiSetupNativeListenIsolationTest} (P1).
 *
 * <p><b>Why {@code shared(true)}:</b> the collision only exists for shared pools — that is deliberately
 * the production default this test reproduces. Test pools normally use {@code shared(false)}.
 */
@Tag(TestCategories.INTEGRATION)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("PgConnectionManager shared-pool isolation across databases on one Vert.x")
public class PgConnectionManagerSharedPoolIsolationIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger =
            LoggerFactory.getLogger(PgConnectionManagerSharedPoolIsolationIntegrationTest.class);

    private static final String DB_A = "peegeeq_shared_pool_iso_a";
    private static final String DB_B = "peegeeq_shared_pool_iso_b";

    private Vertx isolatedVertx;
    private PgConnectionManager cmA;
    private PgConnectionManager cmB;

    @BeforeEach
    void setUpSharedVertx(VertxTestContext testContext) {
        // One Vert.x shared by two managers — the deployment shape that exposed F1.
        isolatedVertx = Vertx.vertx();
        cmA = new PgConnectionManager(isolatedVertx, null);
        cmB = new PgConnectionManager(isolatedVertx, null);
        ensureDatabases()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDownSharedVertx(VertxTestContext testContext) {
        Future<Void> closeA = cmA != null ? cmA.close() : Future.succeededFuture();
        Future<Void> closeB = cmB != null ? cmB.close() : Future.<Void>succeededFuture();
        closeA.eventually(() -> closeB)
                .eventually(() -> isolatedVertx != null ? isolatedVertx.close() : Future.<Void>succeededFuture())
                .onComplete(ar -> testContext.completeNow());
    }

    /**
     * Creates the two test databases if absent, via an admin pool on the shared container's default
     * database. {@code CREATE DATABASE} cannot run inside a transaction, so {@code withConnection}
     * (auto-commit) is the correct — and only — option here.
     */
    private Future<Void> ensureDatabases() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        PgConnectionConfig adminConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PgPoolConfig adminPoolConfig = new PgPoolConfig.Builder()
                .maxSize(2)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
        Pool adminPool = cmA.getOrCreateReactivePool("admin", adminConfig, adminPoolConfig);
        return createDatabaseIfAbsent(adminPool, DB_A)
                .compose(v -> createDatabaseIfAbsent(adminPool, DB_B));
    }

    private Future<Void> createDatabaseIfAbsent(Pool adminPool, String dbName) {
        return adminPool.withConnection(conn ->
                conn.preparedQuery("SELECT 1 FROM pg_database WHERE datname = $1").execute(Tuple.of(dbName))
                        .compose(rows -> rows.size() > 0
                                ? Future.succeededFuture()
                                : conn.query("CREATE DATABASE " + dbName).execute().mapEmpty()));
    }

    @Test
    @DisplayName("Two SHARED pools to different databases on one Vert.x each connect to their OWN database")
    void twoSharedPoolsDifferentDatabasesOneVertx_eachRoutesToItsOwnDatabase(VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgPoolConfig sharedPool = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(true) // reproduce the production default that made F1 possible
                .idleTimeout(Duration.ofSeconds(5))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

        // Same service id on BOTH managers — exactly what production uses ("peegeeq-main").
        cmA.getOrCreateReactivePool("peegeeq-main", dbConfig(postgres, DB_A), sharedPool);
        cmB.getOrCreateReactivePool("peegeeq-main", dbConfig(postgres, DB_B), sharedPool);

        Future<String> dbFromA = cmA.withConnection("peegeeq-main", conn ->
                conn.query("SELECT current_database()").execute()
                        .map(rows -> rows.iterator().next().getString(0)));
        Future<String> dbFromB = cmB.withConnection("peegeeq-main", conn ->
                conn.query("SELECT current_database()").execute()
                        .map(rows -> rows.iterator().next().getString(0)));

        Future.all(dbFromA, dbFromB)
                .onComplete(testContext.succeeding(cf -> testContext.verify(() -> {
                    assertEquals(DB_A, dbFromA.result(),
                            "Manager A's shared pool must connect to " + DB_A);
                    assertEquals(DB_B, dbFromB.result(),
                            "Manager B's shared pool must connect to " + DB_B
                                    + " — an unnamed shared pool collides on one Vert.x and misroutes this to "
                                    + DB_A);
                    logger.info("Shared-pool isolation holds: A->{}, B->{}", dbFromA.result(), dbFromB.result());
                    testContext.completeNow();
                })));
    }

    private PgConnectionConfig dbConfig(PostgreSQLContainer postgres, String database) {
        return new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(database)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
    }
}
