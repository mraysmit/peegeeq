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

package dev.mars.peegeeq.runtime;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Tuple;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RuntimeDatabaseSetupService using TestContainers.
 * Verifies the full wiring of native, outbox, and bitemporal modules.
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class RuntimeDatabaseSetupServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeDatabaseSetupServiceIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        return PostgreSQLTestConstants.createContainer(
                "peegeeq_runtime_test",
                "peegeeq_test",
                "peegeeq_test"
        );
    }

    private DatabaseSetupService setupService;
    private String testSetupId;

    @BeforeAll
    void setUp(VertxTestContext testContext) {
        testSetupId = "runtime-integration-test-" + System.currentTimeMillis();
        setupService = PeeGeeQRuntime.createDatabaseSetupService();
        logger.info("=== Starting Runtime Integration Tests ===");
        logger.info("Test Setup ID: {}", testSetupId);
        logger.info("PostgreSQL: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        testContext.completeNow();
    }

    @AfterAll
    void tearDown(VertxTestContext testContext) {
        if (setupService != null) {
            setupService.close()
                    .onSuccess(v -> {
                        setupService = null;
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("createCompleteSetup - creates setup with native and outbox factories")
    void createCompleteSetup_createsSetupWithFactories(VertxTestContext ctx) {
        // Given
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName("testqueue")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(queueConfig),
                List.of(),
                Map.of()
        );

        // When / Then
        setupService.createCompleteSetup(request)
                .onSuccess(result -> {
                    ctx.verify(() -> {
                        assertNotNull(result, "Setup result should not be null");
                        assertEquals(testSetupId, result.getSetupId(), "Setup ID should match");
                        assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "Status should be ACTIVE");

                        Map<String, QueueFactory> factories = result.getQueueFactories();
                        assertNotNull(factories, "Queue factories should not be null");
                        assertFalse(factories.isEmpty(), "Should have at least one queue factory");
                        assertTrue(factories.containsKey("testqueue"), "Should have testqueue factory");

                        QueueFactory factory = factories.get("testqueue");
                        assertNotNull(factory, "Queue factory should not be null");
                        logger.info("Queue factory type: {}", factory.getImplementationType());
                        logger.info("Setup created successfully with {} queue factories", factories.size());
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("getSetupStatus - returns ACTIVE for existing setup")
    void getSetupStatus_returnsActiveForExistingSetup(VertxTestContext ctx) {
        setupService.getSetupStatus(testSetupId)
                .onSuccess(status -> {
                    ctx.verify(() -> assertEquals(DatabaseSetupStatus.ACTIVE, status, "Status should be ACTIVE"));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("createCompleteSetup - records queue kind + setup metadata in self-describing registry")
    void createCompleteSetup_recordsSelfDescribingRegistry(Vertx vertx, VertxTestContext ctx) {
        // With native + outbox factories wired, a queue with no explicit implementation type resolves to
        // the runtime's best-available kind. That resolved kind is NOT recoverable from the (byte-identical)
        // table DDL, so createCompleteSetup must record it in peegeeq_object_registry — matching exactly the
        // implementation type of the factory it actually created — alongside the self-identifying metadata row.
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "registry_runtime_test_db_" + System.currentTimeMillis();
        String setupId = "registry-runtime-" + System.currentTimeMillis();

        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(dbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName("registryqueue")
                .maxRetries(3)
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(queueConfig), List.of(), Map.of());

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        setupService.createCompleteSetup(request)
                .compose(result -> {
                    assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "Status should be ACTIVE");
                    QueueFactory factory = result.getQueueFactories().get("registryqueue");
                    assertNotNull(factory, "Queue factory should have been created for registryqueue");
                    String expectedKind = factory.getImplementationType();

                    PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                            .host(dbConfig.getHost())
                            .port(dbConfig.getPort())
                            .database(dbName)
                            .username(dbConfig.getUsername())
                            .password(dbConfig.getPassword())
                            .schema(schema)
                            .build();
                    verifyMgr.getOrCreateReactivePool("verify-registry", connConfig,
                            new PgPoolConfig.Builder().maxSize(1).build());

                    return verifyMgr.withConnection("verify-registry", conn ->
                            // 1. Self-identifying metadata row.
                            conn.preparedQuery("SELECT schema_name FROM " + schema
                                            + ".peegeeq_setup_metadata WHERE setup_id = $1")
                                    .execute(Tuple.of(setupId))
                                    .compose(rows -> {
                                        var it = rows.iterator();
                                        if (!it.hasNext()) {
                                            return Future.failedFuture(new AssertionError(
                                                    "peegeeq_setup_metadata row should exist for setup: " + setupId));
                                        }
                                        assertEquals(schema, it.next().getString("schema_name"),
                                                "metadata row should record the setup's schema");
                                        return Future.succeededFuture();
                                    })
                                    // 2. Object-registry row for the queue, kind == the created factory's type.
                                    .compose(v -> conn.preparedQuery("SELECT kind, config FROM " + schema
                                                    + ".peegeeq_object_registry WHERE object_name = $1")
                                            .execute(Tuple.of("registryqueue"))
                                            .compose(rows -> {
                                                var it = rows.iterator();
                                                if (!it.hasNext()) {
                                                    return Future.failedFuture(new AssertionError(
                                                            "peegeeq_object_registry row should exist for object: registryqueue"));
                                                }
                                                var row = it.next();
                                                assertEquals(expectedKind, row.getString("kind"),
                                                        "recorded kind should match the created factory's implementation type");
                                                assertNotNull(row.getValue("config"),
                                                        "object-registry config JSON should be recorded");
                                                return Future.succeededFuture();
                                            })));
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> setupService.destroySetup(setupId))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("addQueue - records the dynamically added queue in the object registry")
    void addQueue_recordsQueueInRegistry(Vertx vertx, VertxTestContext ctx) {
        // A queue added AFTER setup creation must be recorded in the self-describing object registry with its
        // resolved kind, exactly like queues provisioned at create time — otherwise connectToExistingSetup
        // could not rebuild a dynamically added queue's factory (native vs outbox is not recoverable from DDL).
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "registry_addqueue_test_db_" + System.currentTimeMillis();
        String setupId = "registry-addqueue-" + System.currentTimeMillis();

        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(dbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(), List.of(), Map.of());

        QueueConfig dynamicQueue = new QueueConfig.Builder()
                .queueName("dynamicqueue")
                .maxRetries(3)
                .build();

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        setupService.createCompleteSetup(request)
                .compose(v -> setupService.addQueue(setupId, dynamicQueue))
                .compose(v -> setupService.getSetupResult(setupId))
                .compose(result -> {
                    QueueFactory factory = result.getQueueFactories().get("dynamicqueue");
                    assertNotNull(factory, "Queue factory should have been created for dynamicqueue");
                    String expectedKind = factory.getImplementationType();

                    PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                            .host(dbConfig.getHost())
                            .port(dbConfig.getPort())
                            .database(dbName)
                            .username(dbConfig.getUsername())
                            .password(dbConfig.getPassword())
                            .schema(schema)
                            .build();
                    verifyMgr.getOrCreateReactivePool("verify-addqueue", connConfig,
                            new PgPoolConfig.Builder().maxSize(1).build());

                    return verifyMgr.withConnection("verify-addqueue", conn ->
                            conn.preparedQuery("SELECT kind, config FROM " + schema
                                            + ".peegeeq_object_registry WHERE object_name = $1")
                                    .execute(Tuple.of("dynamicqueue"))
                                    .compose(rows -> {
                                        var it = rows.iterator();
                                        if (!it.hasNext()) {
                                            return Future.failedFuture(new AssertionError(
                                                    "peegeeq_object_registry row should exist for dynamically added queue: dynamicqueue"));
                                        }
                                        var row = it.next();
                                        assertEquals(expectedKind, row.getString("kind"),
                                                "recorded kind should match the created factory's implementation type");
                                        assertNotNull(row.getValue("config"),
                                                "object-registry config JSON should be recorded");
                                        return Future.succeededFuture();
                                    }));
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> setupService.destroySetup(setupId))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("addEventStore - records the dynamically added event store in the object registry")
    void addEventStore_recordsEventStoreInRegistry(Vertx vertx, VertxTestContext ctx) {
        // An event store added AFTER setup creation must be recorded in the self-describing object registry
        // (kind 'bitemporal', with its config) exactly like event stores provisioned at create time, so
        // connectToExistingSetup can rebuild it.
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "registry_addstore_test_db_" + System.currentTimeMillis();
        String setupId = "registry-addstore-" + System.currentTimeMillis();

        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(dbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(), List.of(), Map.of());

        EventStoreConfig dynamicStore = new EventStoreConfig.Builder()
                .eventStoreName("dynamicstore")
                .tableName("dynamicstore")
                .build();

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        setupService.createCompleteSetup(request)
                .compose(v -> setupService.addEventStore(setupId, dynamicStore))
                .compose(v -> {
                    PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                            .host(dbConfig.getHost())
                            .port(dbConfig.getPort())
                            .database(dbName)
                            .username(dbConfig.getUsername())
                            .password(dbConfig.getPassword())
                            .schema(schema)
                            .build();
                    verifyMgr.getOrCreateReactivePool("verify-addstore", connConfig,
                            new PgPoolConfig.Builder().maxSize(1).build());

                    return verifyMgr.withConnection("verify-addstore", conn ->
                            conn.preparedQuery("SELECT kind, config FROM " + schema
                                            + ".peegeeq_object_registry WHERE object_name = $1")
                                    .execute(Tuple.of("dynamicstore"))
                                    .compose(rows -> {
                                        var it = rows.iterator();
                                        if (!it.hasNext()) {
                                            return Future.failedFuture(new AssertionError(
                                                    "peegeeq_object_registry row should exist for dynamically added event store: dynamicstore"));
                                        }
                                        var row = it.next();
                                        assertEquals("bitemporal", row.getString("kind"),
                                                "event store should be registered as 'bitemporal'");
                                        assertNotNull(row.getValue("config"),
                                                "object-registry config JSON should be recorded");
                                        return Future.succeededFuture();
                                    }));
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> setupService.destroySetup(setupId))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("removeEventStore - removes the event store's object-registry row")
    void removeEventStore_removesRegistryRow(Vertx vertx, VertxTestContext ctx) {
        // Removing an object must also remove its self-describing registry row, so the registry never drifts
        // from physical reality (a dropped object must not appear reconstitutable to connectToExistingSetup).
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "registry_rmstore_test_db_" + System.currentTimeMillis();
        String setupId = "registry-rmstore-" + System.currentTimeMillis();

        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(dbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(), List.of(), Map.of());

        EventStoreConfig store = new EventStoreConfig.Builder()
                .eventStoreName("removablestore")
                .tableName("removablestore")
                .build();

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        setupService.createCompleteSetup(request)
                .compose(v -> setupService.addEventStore(setupId, store))
                .compose(v -> setupService.removeEventStore(setupId, "removablestore"))
                .compose(v -> {
                    PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                            .host(dbConfig.getHost())
                            .port(dbConfig.getPort())
                            .database(dbName)
                            .username(dbConfig.getUsername())
                            .password(dbConfig.getPassword())
                            .schema(schema)
                            .build();
                    verifyMgr.getOrCreateReactivePool("verify-rmstore", connConfig,
                            new PgPoolConfig.Builder().maxSize(1).build());

                    return verifyMgr.withConnection("verify-rmstore", conn ->
                            conn.preparedQuery("SELECT 1 FROM " + schema
                                            + ".peegeeq_object_registry WHERE object_name = $1")
                                    .execute(Tuple.of("removablestore"))
                                    .compose(rows -> {
                                        if (rows.iterator().hasNext()) {
                                            return Future.failedFuture(new AssertionError(
                                                    "peegeeq_object_registry row should have been removed for event store: removablestore"));
                                        }
                                        return Future.succeededFuture();
                                    }));
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> setupService.destroySetup(setupId))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(8)
    @DisplayName("connectToExistingSetup - reconstitutes queues + event stores from the registry")
    void connectToExistingSetup_reconstitutesFromRegistry(Vertx vertx, VertxTestContext ctx) {
        // Instance A provisions the setup (creating the DB, schema, and self-describing registry). A FRESH
        // instance B then attaches non-destructively to the SAME database with an EMPTY request body — it
        // must rebuild the queue (with the exact native/outbox kind) and the event store purely from
        // peegeeq_setup_metadata + peegeeq_object_registry, recovering the setupId from the schema.
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "connect_test_db_" + System.currentTimeMillis();
        String setupId = "connect-" + System.currentTimeMillis();

        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(dbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queue = new QueueConfig.Builder().queueName("connectqueue").maxRetries(3).build();
        EventStoreConfig store = new EventStoreConfig.Builder()
                .eventStoreName("connectstore").tableName("connectstore").build();

        DatabaseSetupRequest createReq = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(queue), List.of(store), Map.of());
        // Connect request: SAME coordinates but EMPTY contents — contents must be reconstituted, not supplied.
        DatabaseSetupRequest connectReq = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(), List.of(), Map.of());

        DatabaseSetupService serviceB = PeeGeeQRuntime.createDatabaseSetupService();

        setupService.createCompleteSetup(createReq)
                .compose(created -> {
                    String createdKind = created.getQueueFactories().get("connectqueue").getImplementationType();
                    return serviceB.connectToExistingSetup(connectReq)
                            .map(reconstituted -> {
                                assertEquals(DatabaseSetupStatus.ACTIVE, reconstituted.getStatus(),
                                        "connected setup should be ACTIVE");
                                assertEquals(setupId, reconstituted.getSetupId(),
                                        "setupId should be recovered from peegeeq_setup_metadata");
                                assertTrue(reconstituted.getQueueFactories().containsKey("connectqueue"),
                                        "queue should be reconstituted from the object registry");
                                assertEquals(createdKind,
                                        reconstituted.getQueueFactories().get("connectqueue").getImplementationType(),
                                        "reconstituted queue kind must match the originally created kind");
                                assertTrue(reconstituted.getEventStores().containsKey("connectstore"),
                                        "event store should be reconstituted from the object registry");
                                return (Void) null;
                            });
                })
                .eventually(() -> serviceB.close())
                .compose(v -> setupService.destroySetup(setupId))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("getAllActiveSetupIds - includes test setup")
    void getAllActiveSetupIds_includesTestSetup(VertxTestContext ctx) {
        setupService.getAllActiveSetupIds()
                .onSuccess(activeIds -> {
                    ctx.verify(() -> {
                        assertNotNull(activeIds, "Active IDs should not be null");
                        assertTrue(activeIds.contains(testSetupId), "Should contain test setup ID");
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }
}

