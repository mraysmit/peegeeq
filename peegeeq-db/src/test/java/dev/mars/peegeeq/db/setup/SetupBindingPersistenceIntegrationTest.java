package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.credentials.CredentialProvider;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Phase R wiring of {@link SetupBindingRegistry} into
 * {@link PeeGeeQDatabaseSetupService}: persist-on-create/connect behind the request's
 * {@code persistBinding} flag, registry-row removal on detach, and startup reload
 * ({@code reloadPersistedSetups}) with skip-and-log resilience.
 *
 * All setups are provisioned with zero queues/event stores — binding persistence is orthogonal to
 * object contents, and zero-object setups need no factory registrations.
 */
@Tag(TestCategories.INTEGRATION)
class SetupBindingPersistenceIntegrationTest extends BaseIntegrationTest {

    private String uniqueSchema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private DatabaseConfig registryDb(String registrySchema) {
        return new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName(getPostgres().getDatabaseName())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema(registrySchema)
                .build();
    }

    private DatabaseConfig setupDb(String dbName) {
        return new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName(dbName)
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();
    }

    /** Independent SQL check of the bindings table, via the sanctioned PgConnectionManager route. */
    private Future<RowSet<Row>> queryBinding(PgConnectionManager verifyMgr, String poolId,
                                             String registrySchema, String setupId) {
        PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .database(getPostgres().getDatabaseName())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema(registrySchema)
                .build();
        verifyMgr.getOrCreateReactivePool(poolId, connConfig,
                new PgPoolConfig.Builder().maxSize(1).shared(false).build());
        return verifyMgr.withConnection(poolId, conn ->
                conn.preparedQuery("SELECT setup_id, host, port, database_name, schema_name, username, "
                                + "ssl_enabled, credential_ref FROM " + registrySchema
                                + ".peegeeq_setup_bindings WHERE setup_id = $1")
                        .execute(Tuple.of(setupId)));
    }

    private static boolean chainContains(Throwable error, Class<? extends Throwable> type, String messagePart) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t) && t.getMessage() != null && t.getMessage().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void createWithPersistBindingPersistsCoordinatesAndRef(Vertx vertx, VertxTestContext ctx) {
        String registrySchema = uniqueSchema("wire_create");
        String dbName = "bindwire_create_db_" + System.currentTimeMillis();
        String setupId = "bindwire-create-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(null, registryDb(registrySchema), null);
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of(), true, "mem://create-ref");

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        service.createCompleteSetup(request)
                .compose(result -> {
                    assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "setup should be ACTIVE");
                    return queryBinding(verifyMgr, "verify-wire-create", registrySchema, setupId);
                })
                .compose(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        return Future.failedFuture(new AssertionError(
                                "a binding row must exist after create with persistBinding=true"));
                    }
                    Row row = it.next();
                    assertEquals(getPostgres().getHost(), row.getString("host"));
                    assertEquals(getPostgres().getFirstMappedPort(), row.getInteger("port"));
                    assertEquals(dbName, row.getString("database_name"));
                    assertEquals(PostgreSQLTestConstants.TEST_SCHEMA, row.getString("schema_name"));
                    assertEquals(getPostgres().getUsername(), row.getString("username"));
                    assertEquals("mem://create-ref", row.getString("credential_ref"),
                            "the opaque credential reference must be stored verbatim");
                    return Future.succeededFuture();
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> service.destroySetup(setupId))
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void createWithoutPersistFlagWritesNoBindingRow(Vertx vertx, VertxTestContext ctx) {
        String registrySchema = uniqueSchema("wire_noflag");
        String dbName = "bindwire_noflag_db_" + System.currentTimeMillis();
        String setupId = "bindwire-noflag-" + System.currentTimeMillis();

        DatabaseConfig registryConfig = registryDb(registrySchema);
        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(null, registryConfig, null);
        // No persist flag — the registry must stay untouched. The table is pre-created here so the
        // absence of a row is distinguishable from the absence of the table.
        SetupBindingRegistry preEnsure = new SetupBindingRegistry(vertx, registryConfig);
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of());

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        preEnsure.ensureSchema()
                .compose(v -> service.createCompleteSetup(request))
                .compose(result -> {
                    assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "setup should be ACTIVE");
                    return queryBinding(verifyMgr, "verify-wire-noflag", registrySchema, setupId);
                })
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Future.failedFuture(new AssertionError(
                                "no binding row may be written when persistBinding is false"));
                    }
                    return Future.succeededFuture();
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> service.destroySetup(setupId))
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: bindwire-noreg-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void persistRequestedWithoutConfiguredRegistryFailsAndCleansUp(VertxTestContext ctx) {
        String dbName = "bindwire_noreg_db_" + System.currentTimeMillis();
        String setupId = "bindwire-noreg-" + System.currentTimeMillis();

        // Service WITHOUT a registry: asking it to remember a setup must fail loudly, and the
        // failed create must clean up — no half-active setup whose persistence silently never happened.
        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of(), true, null);

        service.createCompleteSetup(request)
                .transform(ar -> {
                    assertTrue(ar.failed(), "create with persistBinding but no registry must fail");
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, "binding registry"),
                            "failure must explain the missing registry, got: " + ar.cause());
                    return Future.succeededFuture();
                })
                .compose(v -> service.getSetupStatus(setupId).transform(ar -> {
                    assertTrue(ar.failed(), "the setup must not remain active after the failed create");
                    return Future.succeededFuture();
                }))
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void connectWithPersistBindingPersistsRecoveredSetup(Vertx vertx, VertxTestContext ctx) {
        String registrySchema = uniqueSchema("wire_connect");
        String dbName = "bindwire_connect_db_" + System.currentTimeMillis();
        String setupId = "bindwire-connect-" + System.currentTimeMillis();

        // Instance A provisions without persisting; a separate instance B connects with
        // persistBinding=true — the binding must be written for the RECOVERED setup id.
        PeeGeeQDatabaseSetupService serviceA = new PeeGeeQDatabaseSetupService();
        PeeGeeQDatabaseSetupService serviceB =
                new PeeGeeQDatabaseSetupService(null, registryDb(registrySchema), null);

        DatabaseSetupRequest createReq = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of());
        DatabaseSetupRequest connectReq = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of(), true, "mem://connect-ref");

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        serviceA.createCompleteSetup(createReq)
                .compose(created -> serviceB.connectToExistingSetup(connectReq))
                .compose(connected -> {
                    assertEquals(setupId, connected.getSetupId(), "connect should recover the setup id");
                    return queryBinding(verifyMgr, "verify-wire-connect", registrySchema, setupId);
                })
                .compose(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        return Future.failedFuture(new AssertionError(
                                "a binding row must exist after connect with persistBinding=true"));
                    }
                    assertEquals("mem://connect-ref", it.next().getString("credential_ref"));
                    return Future.succeededFuture();
                })
                .eventually(() -> verifyMgr.close())
                .compose(v -> serviceB.destroySetup(setupId))
                .eventually(() -> serviceB.close())
                .compose(v -> serviceA.destroySetup(setupId))
                .eventually(() -> serviceA.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void detachSetupRemovesTheBindingRow(Vertx vertx, VertxTestContext ctx) {
        String registrySchema = uniqueSchema("wire_detach");
        String dbName = "bindwire_detach_db_" + System.currentTimeMillis();
        String setupId = "bindwire-detach-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(null, registryDb(registrySchema), null);
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of(), true, null);

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        service.createCompleteSetup(request)
                .compose(result -> queryBinding(verifyMgr, "verify-wire-detach", registrySchema, setupId))
                .compose(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return Future.failedFuture(new AssertionError(
                                "precondition: the binding row must exist before detach"));
                    }
                    return service.detachSetup(setupId);
                })
                .compose(v -> queryBinding(verifyMgr, "verify-wire-detach", registrySchema, setupId))
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Future.failedFuture(new AssertionError(
                                "detach must remove the binding row — a detached setup must not resurrect on restart"));
                    }
                    // The in-memory binding must be gone too.
                    return service.getSetupStatus(setupId).transform(ar -> {
                        assertTrue(ar.failed(), "the setup must no longer be active after detach");
                        return Future.succeededFuture();
                    });
                })
                .eventually(() -> verifyMgr.close())
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to connect to existing setup 'bindwire-ghost-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Skipping persisted setup 'bindwire-ghost-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Skipping persisted setup 'bindwire-unresolvable-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    void reloadReconnectsPersistedSetupsAndSkipsBadEntries(Vertx vertx, VertxTestContext ctx) {
        String registrySchema = uniqueSchema("wire_reload");
        String dbName = "bindwire_reload_db_" + System.currentTimeMillis();
        String setupId = "bindwire-reload-" + System.currentTimeMillis();
        String ghostId = "bindwire-ghost-" + System.currentTimeMillis();
        String unresolvableId = "bindwire-unresolvable-" + System.currentTimeMillis();

        DatabaseConfig registryConfig = registryDb(registrySchema);

        // The provider resolves exactly one reference; everything else fails — reload must treat an
        // unresolvable credential exactly like an unreachable database: skip, record, continue.
        CredentialProvider provider = ref -> "mem://reload".equals(ref)
                ? Future.succeededFuture(getPostgres().getPassword())
                : Future.failedFuture(new IllegalStateException("unknown credential ref: " + ref));

        PeeGeeQDatabaseSetupService serviceA =
                new PeeGeeQDatabaseSetupService(null, registryConfig, null);
        PeeGeeQDatabaseSetupService serviceB =
                new PeeGeeQDatabaseSetupService(null, registryConfig, provider);
        SetupBindingRegistry seeding = new SetupBindingRegistry(vertx, registryConfig);

        DatabaseSetupRequest createReq = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of(), true, "mem://reload");

        serviceA.createCompleteSetup(createReq)
                // Simulate the restart: in-memory teardown only — the binding row and database survive.
                // serviceA is CLOSED AT THE END, not here. It owns its own Vert.x, and this chain is
                // running on that Vert.x: closing it mid-chain ends the event loop every later step
                // would be dispatched on, so the reload steps below would be dropped and the test
                // would hang. The restart is simulated by destroySetup plus reloading through the
                // separate serviceB instance; closing serviceA early adds nothing to that.
                .compose(v -> serviceA.destroySetup(setupId))
                // Seed two bad bindings: a resolvable ref pointing at a database that does not exist,
                // and real coordinates whose credential reference the provider cannot resolve.
                .compose(v -> seeding.saveBinding(new SetupBinding(
                        ghostId, getPostgres().getHost(), getPostgres().getFirstMappedPort(),
                        "ghost_db_" + System.currentTimeMillis(), PostgreSQLTestConstants.TEST_SCHEMA,
                        getPostgres().getUsername(), false, "mem://reload")))
                .compose(v -> seeding.saveBinding(new SetupBinding(
                        unresolvableId, getPostgres().getHost(), getPostgres().getFirstMappedPort(),
                        dbName, PostgreSQLTestConstants.TEST_SCHEMA,
                        getPostgres().getUsername(), false, "mem://unknown")))
                .compose(v -> serviceB.reloadPersistedSetups())
                .compose(report -> {
                    assertEquals(List.of(setupId), report.getReconnectedSetupIds(),
                            "exactly the persisted, reachable setup must be reconnected");
                    assertEquals(2, report.getSkippedSetups().size(),
                            "both bad bindings must be skipped, not abort the reload: " + report.getSkippedSetups());
                    assertTrue(report.getSkippedSetups().containsKey(ghostId),
                            "the missing-database binding must be skipped with a reason");
                    assertTrue(report.getSkippedSetups().containsKey(unresolvableId),
                            "the unresolvable-credential binding must be skipped with a reason");
                    report.getSkippedSetups().values().forEach(reason ->
                            assertFalse(reason == null || reason.isBlank(), "every skip must carry a reason"));
                    // The reconnected setup must actually be active on the reloading service.
                    return serviceB.getSetupStatus(setupId);
                })
                .compose(status -> {
                    assertEquals(DatabaseSetupStatus.ACTIVE, status,
                            "the reloaded setup must be ACTIVE with no manual step");
                    return serviceB.destroySetup(setupId);
                })
                // Close serviceA first: this chain is running on serviceB's Vert.x, so ending
                // serviceA's loop here strands nothing. Then close serviceB, which leaves exactly one
                // continuation — the terminal handlers below — for its close to deliver.
                .eventually(() -> serviceA.close())
                .eventually(() -> serviceB.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void reloadWithEmptyRegistryReturnsEmptyReport(VertxTestContext ctx) {
        // A fresh deployment that never persisted anything: reload must succeed with an empty
        // report (this also exercises schema creation on the reload path).
        String registrySchema = uniqueSchema("wire_empty");
        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(null, registryDb(registrySchema), null);

        service.reloadPersistedSetups()
                .compose(report -> {
                    assertTrue(report.getReconnectedSetupIds().isEmpty(), "nothing to reconnect");
                    assertTrue(report.getSkippedSetups().isEmpty(), "nothing to skip");
                    return Future.<Void>succeededFuture();
                })
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void reloadWithoutConfiguredRegistryFailsClearly(VertxTestContext ctx) {
        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();

        service.reloadPersistedSetups()
                .transform(ar -> {
                    assertTrue(ar.failed(), "reload without a registry must fail, not return an empty report");
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, "binding registry"),
                            "failure must explain the missing registry, got: " + ar.cause());
                    return Future.succeededFuture();
                })
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }
}
