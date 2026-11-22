package dev.mars.peegeeq.migrations;


import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for custom schema deployment scenarios.
 * Verifies that migrations can be deployed to named schemas instead of the default 'public' schema.
 *
 * <p>Flyway connects through JDBC; that is the behaviour under test. The test reads the
 * resulting schema through a Vert.x pool.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class CustomSchemaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CustomSchemaIntegrationTest.class);

    private static final List<String> EXPECTED_TABLES = List.of(
            "schema_version",
            "outbox",
            "outbox_consumer_groups",
            "queue_messages",
            "message_processing",
            "dead_letter_queue",
            "bitemporal_event_log",
            "bitemporal_subscriptions");

    private static final String TABLE_COUNT_SQL =
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = $1 AND table_name = $2";

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private Pool pool;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(2).setShared(false))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        pool.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMigrationsToCustomSchemaViaJdbcUrl(VertxTestContext testContext) {
        log.info("TEST: Deploying migrations to custom schema via JDBC URL parameter");
        String customSchema = "peegeeq_custom";

        // Configure Flyway to use custom schema via JDBC URL parameter
        String jdbcUrl = postgres.getJdbcUrl() + "?currentSchema=" + customSchema + "&options=-c%20statement_timeout=60000";

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .schemas(customSchema)
                .createSchemas(true)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false)
                .connectRetries(10)
                .load();

        migrate(flyway)
            .compose(result -> {
                assertTrue(result.success, "Migration to schema " + customSchema + " should succeed");
                assertTrue(result.migrationsExecuted > 0, "At least one migration should run, ran " + result.migrationsExecuted);
                return verifyTablesInSchema(customSchema);
            })
            .compose(v -> verifyTablesNotInPublicSchema())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMigrationsToCustomSchemaViaDefaultSchema(VertxTestContext testContext) {
        log.info("TEST: Deploying migrations to custom schema via defaultSchema configuration");
        String customSchema = "peegeeq_configured";

        // Configure Flyway with explicit defaultSchema
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .defaultSchema(customSchema)
                .schemas(customSchema)
                .createSchemas(true)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false)
                .connectRetries(10)
                .load();

        migrate(flyway)
            .compose(result -> {
                assertTrue(result.success, "Migration to schema " + customSchema + " should succeed");
                assertTrue(result.migrationsExecuted > 0, "At least one migration should run, ran " + result.migrationsExecuted);
                return verifyTablesInSchema(customSchema);
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMultipleSchemaIsolation(VertxTestContext testContext) {
        log.info("TEST: Testing schema isolation - deploying to multiple schemas in same database");

        String schema1 = "peegeeq_tenant1";
        String schema2 = "peegeeq_tenant2";

        deployToSchema(schema1)
            .compose(v -> deployToSchema(schema2))
            .compose(v -> verifyTablesInSchema(schema1))
            .compose(v -> verifyTablesInSchema(schema2))
            // Verify schemas are isolated (data in one doesn't affect the other)
            .compose(v -> insertOutboxRow(schema1, "data1"))
            .compose(v -> insertOutboxRow(schema2, "data2"))
            .compose(v -> countOutboxRows(schema1, "data1"))
            .compose(count -> {
                assertEquals(1L, count, "Schema1 should have its own data");
                return countOutboxRows(schema2, "data2");
            })
            .compose(count -> {
                assertEquals(1L, count, "Schema2 should have its own data");
                return countOutboxRows(schema1, "data2");
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(0L, count, "Schema1 should NOT have schema2's data");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testFlywayHistoryTableInCustomSchema(VertxTestContext testContext) {
        log.info("TEST: Verifying Flyway history table is created in custom schema");
        String customSchema = "peegeeq_history";

        deployToSchema(customSchema)
            .compose(v -> queryCount(TABLE_COUNT_SQL, Tuple.of(customSchema, "flyway_schema_history")))
            .compose(count -> {
                assertEquals(1L, count, "flyway_schema_history should exist in custom schema");
                return queryCount(TABLE_COUNT_SQL, Tuple.of("public", "flyway_schema_history"));
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(0L, count, "flyway_schema_history should NOT be in public schema");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testSchemaCreatedAutomatically(VertxTestContext testContext) {
        log.info("TEST: Verifying custom schema is created automatically if it doesn't exist");
        String nonExistentSchema = "peegeeq_autocreate";
        String schemaCountSql = "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = $1";

        queryCount(schemaCountSql, Tuple.of(nonExistentSchema))
            .compose(count -> {
                assertEquals(0L, count, "Schema should not exist before migration");
                return deployToSchema(nonExistentSchema);
            })
            .compose(v -> queryCount(schemaCountSql, Tuple.of(nonExistentSchema)))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count, "Schema should be auto-created during migration");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // Helper methods

    /** Flyway blocks, so the migration runs on a worker thread. */
    private Future<MigrateResult> migrate(Flyway flyway) {
        return vertx.executeBlocking(flyway::migrate, false);
    }

    private Future<Void> deployToSchema(String schemaName) {
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .defaultSchema(schemaName)
                .schemas(schemaName)
                .createSchemas(true)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false)
                .connectRetries(10)
                .load();

        return migrate(flyway).compose(result -> {
            assertTrue(result.success, "Migration to schema " + schemaName + " should succeed");
            return Future.<Void>succeededFuture();
        });
    }

    private Future<Void> verifyTablesInSchema(String schemaName) {
        Future<Void> chain = Future.succeededFuture();
        for (String tableName : EXPECTED_TABLES) {
            chain = chain
                .compose(v -> queryCount(TABLE_COUNT_SQL, Tuple.of(schemaName, tableName)))
                .compose(count -> {
                    assertEquals(1L, count, String.format("Table %s should exist in schema %s", tableName, schemaName));
                    return Future.<Void>succeededFuture();
                });
        }
        return chain;
    }

    private Future<Void> verifyTablesNotInPublicSchema() {
        Future<Void> chain = Future.succeededFuture();
        for (String tableName : List.of("outbox", "queue_messages", "dead_letter_queue")) {
            chain = chain
                .compose(v -> queryCount(TABLE_COUNT_SQL, Tuple.of("public", tableName)))
                .compose(count -> {
                    assertEquals(0L, count, String.format("Table %s should NOT exist in public schema", tableName));
                    return Future.<Void>succeededFuture();
                });
        }
        return chain;
    }

    /**
     * The outbox triggers name other tables without a schema, so they resolve them through
     * search_path. As in PgConnectionManager.withTransaction, the schema is set for this
     * transaction only, before the insert.
     */
    private Future<Void> insertOutboxRow(String schema, String marker) {
        String sql = "INSERT INTO " + schema + ".outbox (topic, payload) "
                + "VALUES ('test-topic', jsonb_build_object('test', $1::text))";
        return pool.withTransaction(connection ->
            connection.query("SET LOCAL search_path TO " + schema).execute()
                .compose(set -> connection.preparedQuery(sql).execute(Tuple.of(marker)))
                .mapEmpty());
    }

    private Future<Long> countOutboxRows(String schema, String marker) {
        return queryCount("SELECT COUNT(*) FROM " + schema + ".outbox WHERE payload->>'test' = $1", Tuple.of(marker));
    }

    private Future<Long> queryCount(String sql, Tuple params) {
        return pool.preparedQuery(sql)
            .execute(params)
            .map(rows -> rows.iterator().next().getLong(0));
    }
}
