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
import dev.mars.peegeeq.api.setup.DatabaseCreationConflictException;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
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
@Tag(TestCategories.INTEGRATION)
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
                                                assertInstanceOf(JsonObject.class, row.getValue("config"),
                                                        "config must round-trip as a JSONB object, not a double-encoded JSON string");
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
                                        assertInstanceOf(JsonObject.class, row.getValue("config"),
                                                "config must round-trip as a JSONB object, not a double-encoded JSON string");
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
                                        assertInstanceOf(JsonObject.class, row.getValue("config"),
                                                "config must round-trip as a JSONB object, not a double-encoded JSON string");
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
    @Order(9)
    @DisplayName("connectToExistingSetup - refuses a duplicate attach and leaves the active setup intact")
    void connectToExistingSetup_refusesDuplicateAttach(VertxTestContext ctx) {
        // A setup already active in THIS service must not be re-attached: a second connect would overwrite
        // (and leak) the live manager's Vert.x + pool. The guard must refuse AND leave the original ACTIVE.
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "dupconnect_test_db_" + System.currentTimeMillis();
        String setupId = "dupconnect-" + System.currentTimeMillis();

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

        QueueConfig queue = new QueueConfig.Builder().queueName("dupqueue").maxRetries(3).build();
        DatabaseSetupRequest createReq = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(queue), List.of(), Map.of());
        // Same coordinates, empty contents — a connect to the setup that create just made active.
        DatabaseSetupRequest connectReq = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(), List.of(), Map.of());

        setupService.createCompleteSetup(createReq)
                // createCompleteSetup makes the setup active; connecting to it again must be refused.
                .compose(created -> setupService.connectToExistingSetup(connectReq)
                        .transform(ar -> {
                            assertTrue(ar.failed(), "a duplicate connect to an already-active setup must fail");
                            StringBuilder chain = new StringBuilder();
                            for (Throwable t = ar.cause(); t != null; t = t.getCause()) {
                                chain.append(t.getMessage()).append(" | ");
                            }
                            assertTrue(chain.toString().contains("already active"),
                                    "the refusal should explain the duplicate attach, got: " + chain);
                            return Future.succeededFuture();
                        }))
                // The originally-created setup must still be ACTIVE — the refused connect must not tear it down.
                .compose(v -> setupService.getSetupStatus(setupId))
                .map(status -> {
                    assertEquals(DatabaseSetupStatus.ACTIVE, status,
                            "the created setup must remain ACTIVE after a refused duplicate connect");
                    return (Void) null;
                })
                .compose(v -> setupService.destroySetup(setupId))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(10)
    @DisplayName("createCompleteSetup - refuses (non-destructively) when the database already exists")
    void createCompleteSetup_refusesOnExistingDatabase(Vertx vertx, VertxTestContext ctx) {
        // Create must NEVER overwrite: a second create against the SAME database must fail as a conflict and
        // leave the first setup's schema + self-describing registry intact — proving no drop-and-recreate.
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "refuse_existing_db_" + System.currentTimeMillis();
        String setupIdA = "refuse-a-" + System.currentTimeMillis();
        String setupIdB = "refuse-b-" + System.currentTimeMillis();

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

        QueueConfig queue = new QueueConfig.Builder().queueName("refusequeue").maxRetries(3).build();
        DatabaseSetupRequest createA = new DatabaseSetupRequest(setupIdA, dbConfig, List.of(queue), List.of(), Map.of());
        // Same database name, different setupId — the second create must be refused, not overwrite.
        DatabaseSetupRequest createB = new DatabaseSetupRequest(setupIdB, dbConfig, List.of(), List.of(), Map.of());

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        setupService.createCompleteSetup(createA)
                .compose(createdA -> {
                    assertEquals(DatabaseSetupStatus.ACTIVE, createdA.getStatus(), "first setup should be ACTIVE");
                    return setupService.createCompleteSetup(createB).transform(ar -> {
                        assertTrue(ar.failed(), "create against an existing database must be refused");
                        boolean isConflict = false;
                        StringBuilder chain = new StringBuilder();
                        for (Throwable t = ar.cause(); t != null; t = t.getCause()) {
                            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
                            if (t instanceof DatabaseCreationConflictException) isConflict = true;
                        }
                        assertTrue(isConflict,
                                "refusal must be a DatabaseCreationConflictException, got: " + chain);
                        return Future.succeededFuture();
                    });
                })
                // The first setup's self-identifying metadata must still be present — the DB was NOT dropped.
                .compose(v -> {
                    PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                            .host(dbConfig.getHost())
                            .port(dbConfig.getPort())
                            .database(dbName)
                            .username(dbConfig.getUsername())
                            .password(dbConfig.getPassword())
                            .schema(schema)
                            .build();
                    verifyMgr.getOrCreateReactivePool("verify-refuse", connConfig,
                            new PgPoolConfig.Builder().maxSize(1).build());
                    return verifyMgr.withConnection("verify-refuse", conn ->
                            conn.preparedQuery("SELECT setup_id FROM " + schema
                                            + ".peegeeq_setup_metadata WHERE setup_id = $1")
                                    .execute(Tuple.of(setupIdA))
                                    .compose(rows -> {
                                        if (!rows.iterator().hasNext()) {
                                            return Future.failedFuture(new AssertionError(
                                                    "setup A metadata must survive the refused second create — "
                                                    + "the database must not have been dropped"));
                                        }
                                        return Future.succeededFuture();
                                    }));
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> setupService.destroySetup(setupIdA))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(11)
    @DisplayName("dropSetupDatabase - guarded destroy: wrong confirm is non-destructive, correct confirm drops the DB")
    void dropSetupDatabase_guardedDestroy(Vertx vertx, VertxTestContext ctx) {
        // The single destructive path. A wrong type-to-confirm token must be refused with the database
        // left intact; the exact database name must drop it. Verified against pg_database via a connection
        // to the container's DEFAULT database (the setup's own DB is the one being dropped).
        String schema = PostgreSQLTestConstants.TEST_SCHEMA;
        String dbName = "drop_test_db_" + System.currentTimeMillis();
        String setupId = "drop-" + System.currentTimeMillis();

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
        QueueConfig queue = new QueueConfig.Builder().queueName("dropq").maxRetries(3).build();
        DatabaseSetupRequest createReq = new DatabaseSetupRequest(setupId, dbConfig, List.of(queue), List.of(), Map.of());

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);
        PgConnectionConfig adminConn = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        setupService.createCompleteSetup(createReq)
                .compose(created -> {
                    verifyMgr.getOrCreateReactivePool("verify-drop", adminConn,
                            new PgPoolConfig.Builder().maxSize(1).build());
                    // A wrong confirmation must be refused (IllegalArgumentException), dropping nothing.
                    return setupService.dropSetupDatabase(setupId, "definitely-not-the-db").transform(ar -> {
                        assertTrue(ar.failed(), "drop with a wrong confirmation must fail");
                        boolean isArg = false;
                        for (Throwable t = ar.cause(); t != null; t = t.getCause()) {
                            if (t instanceof IllegalArgumentException) isArg = true;
                        }
                        assertTrue(isArg, "wrong-confirmation drop must fail with IllegalArgumentException, got: " + ar.cause());
                        return Future.succeededFuture();
                    });
                })
                // The database must still exist after the refused drop (non-destructive).
                .compose(v -> dbExists(verifyMgr, dbName))
                .map(exists -> {
                    assertTrue(exists, "database must survive a wrong-confirmation drop");
                    return (Void) null;
                })
                // The exact database name drops it.
                .compose(v -> setupService.dropSetupDatabase(setupId, dbName))
                // The database must now be gone.
                .compose(v -> dbExists(verifyMgr, dbName))
                .map(exists -> {
                    assertFalse(exists, "database must be dropped after a confirmed drop");
                    return (Void) null;
                })
                .eventually(() -> verifyMgr.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    private Future<Boolean> dbExists(PgConnectionManager mgr, String dbName) {
        return mgr.withConnection("verify-drop", conn ->
                conn.preparedQuery("SELECT 1 FROM pg_database WHERE datname = $1")
                        .execute(Tuple.of(dbName))
                        .map(rows -> rows.iterator().hasNext()));
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

