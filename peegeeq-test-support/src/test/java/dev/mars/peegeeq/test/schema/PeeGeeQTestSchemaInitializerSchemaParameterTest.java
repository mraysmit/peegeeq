package dev.mars.peegeeq.test.schema;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests schema parameter support using the standard PostgreSQL container and a
 * non-shared Vert.x PgClient verification pool.
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
class PeeGeeQTestSchemaInitializerSchemaParameterTest {

    @Container
    private static final PostgreSQLContainer postgres =
        PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private Pool verificationPool;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        verificationPool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(2).setShared(false))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        verificationPool.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testInitializeSchemaWithCustomSchema(VertxTestContext testContext) {
        String customSchema = "tenant_abc";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, customSchema,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);

        verificationPool.preparedQuery(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = $1")
            .execute(Tuple.of(customSchema))
            .compose(schemaRows -> {
                testContext.verify(() -> {
                    assertEquals(1, schemaRows.size(), "Custom schema should exist");
                    assertEquals(customSchema, schemaRows.iterator().next().getString("schema_name"));
                });
                return verificationPool.preparedQuery("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = $1 AND table_name = 'queue_messages'
                        """)
                    .execute(Tuple.of(customSchema));
            })
            .onSuccess(tableRows -> testContext.verify(() -> {
                assertEquals(1, tableRows.size(),
                    "queue_messages table should exist in custom schema");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testInitializeSchemaWithPublicSchema(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "public",
            PeeGeeQTestSchemaInitializer.SchemaComponent.OUTBOX);

        verificationPool.preparedQuery("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'outbox'
                """)
            .execute()
            .onSuccess(rows -> testContext.verify(() -> {
                assertEquals(1, rows.size(), "outbox table should exist in public schema");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testSchemaValidation_NullSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, (String) null,
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE));
        assertTrue(exception.getMessage().contains("Schema parameter is required"));
    }

    @Test
    void testSchemaValidation_BlankSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "  ",
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE));
        assertTrue(exception.getMessage().contains("Schema parameter is required"));
    }

    @Test
    void testSchemaValidation_InvalidSchemaName() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "test'; DROP TABLE users; --",
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE));
        assertTrue(exception.getMessage().contains("Invalid schema name"));
    }

    @Test
    void testSchemaValidation_ReservedSchemaName_PgPrefix() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "pg_catalog",
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE));
        assertTrue(exception.getMessage().contains("Reserved schema name"));
    }

    @Test
    void testSchemaValidation_ReservedSchemaName_InformationSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "information_schema",
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE));
        assertTrue(exception.getMessage().contains("Reserved schema name"));
    }

    @Test
    void testMultiTenantSchemaIsolation(VertxTestContext testContext) {
        String schema1 = "tenant_a";
        String schema2 = "tenant_b";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema1,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema2,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);

        verificationPool.preparedQuery("""
                SELECT table_schema FROM information_schema.tables
                WHERE table_schema = $1 AND table_name = 'queue_messages'
                """)
            .execute(Tuple.of(schema1))
            .compose(schema1Rows -> {
                testContext.verify(() -> assertEquals(1, schema1Rows.size(),
                    "queue_messages table should exist in schema1"));
                return verificationPool.preparedQuery("""
                        SELECT table_schema FROM information_schema.tables
                        WHERE table_schema = $1 AND table_name = 'queue_messages'
                        """)
                    .execute(Tuple.of(schema2));
            })
            .onSuccess(schema2Rows -> testContext.verify(() -> {
                assertEquals(1, schema2Rows.size(),
                    "queue_messages table should exist in schema2");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testMultiTenantDataIsolationForBitemporalEvents(VertxTestContext testContext) {
        String schema1 = "tenant_iso_a";
        String schema2 = "tenant_iso_b";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema1,
            PeeGeeQTestSchemaInitializer.SchemaComponent.BITEMPORAL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema2,
            PeeGeeQTestSchemaInitializer.SchemaComponent.BITEMPORAL);

        verificationPool.withTransaction(connection ->
                connection.preparedQuery("""
                        INSERT INTO tenant_iso_a.bitemporal_event_log
                            (event_id, event_type, valid_time, payload)
                        VALUES ($1, $2, NOW(), $3)
                        """)
                    .execute(Tuple.of(
                        "event-1", "TenantEvent", new JsonObject().put("tenant", "A")))
                    .compose(v -> connection.preparedQuery("""
                            INSERT INTO tenant_iso_b.bitemporal_event_log
                                (event_id, event_type, valid_time, payload)
                            VALUES ($1, $2, NOW(), $3)
                            """)
                        .execute(Tuple.of(
                            "event-1", "TenantEvent", new JsonObject().put("tenant", "B"))))
                    .mapEmpty())
            .compose(v -> verificationPool.preparedQuery("""
                    SELECT COUNT(*) AS cnt, MIN(payload->>'tenant') AS tenant
                    FROM tenant_iso_a.bitemporal_event_log WHERE event_id = $1
                    """).execute(Tuple.of("event-1")))
            .compose(rowsA -> {
                testContext.verify(() -> {
                    assertEquals(1L, rowsA.iterator().next().getLong("cnt"),
                        "Schema A should contain exactly its own event");
                    assertEquals("A", rowsA.iterator().next().getString("tenant"));
                });
                return verificationPool.preparedQuery("""
                        SELECT COUNT(*) AS cnt, MIN(payload->>'tenant') AS tenant
                        FROM tenant_iso_b.bitemporal_event_log WHERE event_id = $1
                        """).execute(Tuple.of("event-1"));
            })
            .onSuccess(rowsB -> testContext.verify(() -> {
                assertEquals(1L, rowsB.iterator().next().getLong("cnt"),
                    "Schema B should contain exactly its own event");
                assertEquals("B", rowsB.iterator().next().getString("tenant"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCleanupTestDataOnlyAffectsTargetSchema(VertxTestContext testContext) {
        String schema1 = "tenant_cleanup_a";
        String schema2 = "tenant_cleanup_b";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema1,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema2,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);

        verificationPool.withTransaction(connection ->
                connection.preparedQuery("""
                        INSERT INTO tenant_cleanup_a.queue_messages (topic, payload)
                        VALUES ($1, $2)
                        """)
                    .execute(Tuple.of("orders", new JsonObject().put("id", "a1")))
                    .compose(v -> connection.preparedQuery("""
                            INSERT INTO tenant_cleanup_b.queue_messages (topic, payload)
                            VALUES ($1, $2)
                            """)
                        .execute(Tuple.of("orders", new JsonObject().put("id", "b1"))))
                    .mapEmpty())
            .compose(v -> vertx.executeBlocking(() -> {
                PeeGeeQTestSchemaInitializer.cleanupTestData(
                    postgres,
                    schema1,
                    PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
                return null;
            }, false).mapEmpty())
            .compose(v -> verificationPool.query(
                "SELECT COUNT(*) AS cnt FROM tenant_cleanup_a.queue_messages").execute())
            .compose(rowsA -> {
                testContext.verify(() -> assertEquals(
                    0L,
                    rowsA.iterator().next().getLong("cnt"),
                    "Cleanup should truncate only target schema"));
                return verificationPool.query(
                    "SELECT COUNT(*) AS cnt FROM tenant_cleanup_b.queue_messages").execute();
            })
            .onSuccess(rowsB -> testContext.verify(() -> {
                assertEquals(1L, rowsB.iterator().next().getLong("cnt"),
                    "Non-target tenant schema data must remain intact");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
