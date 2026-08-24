package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link SetupBindingRegistry} — the durable binding store behind
 * "remember this setup" and startup auto-reload (Phase R / W-C).
 *
 * The registry database in these tests is the shared container's own database; each test uses a
 * unique schema so parallel execution cannot collide.
 */
@Tag(TestCategories.INTEGRATION)
class SetupBindingRegistryIntegrationTest extends BaseIntegrationTest {

    private String uniqueSchema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private DatabaseConfig registryDb(String schema) {
        return new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName(getPostgres().getDatabaseName())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema(schema)
                .build();
    }

    /**
     * Builds a binding whose coordinates come from the live container — never hard-coded hosts or
     * ports. The registry treats coordinates as data, but test data must still describe endpoints
     * that exist in the test environment.
     */
    private SetupBinding containerBinding(String setupId, String schemaName, boolean sslEnabled,
                                          String credentialRef) {
        return new SetupBinding(
                setupId,
                getPostgres().getHost(),
                getPostgres().getFirstMappedPort(),
                getPostgres().getDatabaseName(),
                schemaName,
                getPostgres().getUsername(),
                sslEnabled,
                credentialRef);
    }

    @Test
    void ensureSchemaIsIdempotentAndBindingsRoundTrip(Vertx vertx, VertxTestContext ctx) {
        String schema = uniqueSchema("binding_roundtrip");
        SetupBindingRegistry registry = new SetupBindingRegistry(vertx, registryDb(schema));

        SetupBinding withRef = containerBinding("orders-prod", "orders_schema", true, "vault://prod/orders-db");
        SetupBinding withoutRef = containerBinding("events-eu", "events_schema", false, null);

        registry.ensureSchema()
                // A second ensureSchema must succeed unchanged (CREATE ... IF NOT EXISTS semantics).
                .compose(v -> registry.ensureSchema())
                .compose(v -> registry.saveBinding(withRef))
                .compose(v -> registry.saveBinding(withoutRef))
                .compose(v -> registry.listBindings())
                .onSuccess(bindings -> ctx.verify(() -> {
                    assertEquals(2, bindings.size(), "both persisted bindings should be listed");
                    // listBindings orders by setup_id: events-eu before orders-prod. Record equality
                    // covers every field, so a stored binding must equal the one persisted.
                    assertEquals(withoutRef, bindings.get(0), "binding without a ref must round-trip unchanged");
                    assertEquals(withRef, bindings.get(1), "binding with a ref must round-trip unchanged");
                    assertNull(bindings.get(0).credentialRef(),
                            "a binding persisted without a ref must read back null");
                    assertEquals("vault://prod/orders-db", bindings.get(1).credentialRef());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    void saveBindingUpsertsOnSameSetupId(Vertx vertx, VertxTestContext ctx) {
        String schema = uniqueSchema("binding_upsert");
        SetupBindingRegistry registry = new SetupBindingRegistry(vertx, registryDb(schema));

        SetupBinding original = containerBinding("moved-setup", "moved_schema", false, null);
        // A relocation derived from the original: same identity, next port, SSL + ref added.
        SetupBinding relocated = new SetupBinding(
                original.setupId(), original.host(), original.port() + 1, original.databaseName(),
                original.schemaName(), original.username(), true, "vault://prod/moved");

        registry.ensureSchema()
                .compose(v -> registry.saveBinding(original))
                .compose(v -> registry.saveBinding(relocated))
                .compose(v -> registry.listBindings())
                .onSuccess(bindings -> ctx.verify(() -> {
                    assertEquals(1, bindings.size(), "re-persisting the same setupId must update, not duplicate");
                    assertEquals(relocated, bindings.get(0),
                            "the stored binding must carry the re-persisted coordinates in full");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    void deleteBindingRemovesRowAndIsIdempotent(Vertx vertx, VertxTestContext ctx) {
        String schema = uniqueSchema("binding_delete");
        SetupBindingRegistry registry = new SetupBindingRegistry(vertx, registryDb(schema));

        SetupBinding binding = containerBinding("detachable", "detach_schema", false, null);

        registry.ensureSchema()
                .compose(v -> registry.saveBinding(binding))
                .compose(v -> registry.deleteBinding("detachable"))
                .compose(v -> registry.listBindings())
                .compose(bindings -> {
                    assertTrue(bindings.isEmpty(), "deleted binding must not be listed");
                    // A second delete of the same id must succeed — a missing row is not an error.
                    return registry.deleteBinding("detachable");
                })
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void bindingsTableStoresNoPassword(Vertx vertx, VertxTestContext ctx) {
        // §11 pinned as a schema contract: the registry holds coordinates + credential_ref ONLY.
        String schema = uniqueSchema("binding_nopass");
        SetupBindingRegistry registry = new SetupBindingRegistry(vertx, registryDb(schema));

        PgConnectionManager verifyMgr = new PgConnectionManager(vertx, null);

        registry.ensureSchema()
                .compose(v -> {
                    PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                            .host(getPostgres().getHost())
                            .port(getPostgres().getFirstMappedPort())
                            .database(getPostgres().getDatabaseName())
                            .username(getPostgres().getUsername())
                            .password(getPostgres().getPassword())
                            .schema(schema)
                            .build();
                    verifyMgr.getOrCreateReactivePool("verify-bindings", connConfig,
                            new PgPoolConfig.Builder().maxSize(1).shared(false).build());

                    return verifyMgr.withConnection("verify-bindings", conn ->
                            conn.preparedQuery("SELECT column_name FROM information_schema.columns "
                                            + "WHERE table_schema = $1 AND table_name = 'peegeeq_setup_bindings'")
                                    .execute(Tuple.of(schema)));
                })
                .onSuccess(rows -> ctx.verify(() -> {
                    Set<String> columns = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                            .map(row -> row.getString("column_name"))
                            .collect(Collectors.toSet());
                    assertEquals(
                            Set.of("setup_id", "host", "port", "database_name", "schema_name",
                                    "username", "ssl_enabled", "credential_ref", "created_at", "updated_at"),
                            columns,
                            "bindings table must hold exactly the coordinate columns — no credential material");
                    assertTrue(columns.stream().noneMatch(c -> c.contains("password")),
                            "the registry must never gain a password column");
                    ctx.completeNow();
                }))
                .eventually(() -> verifyMgr.close())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.SetupBindingRegistry",
            message = "Failed to persist setup binding 'orphan': ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.SetupBindingRegistry",
            message = "Failed to list setup bindings from 'binding_notable_",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    void operationsFailClearlyWhenSchemaWasNeverEnsured(Vertx vertx, VertxTestContext ctx) {
        // Dependency failure mode: the ops must not auto-create the table — an absent table is a
        // loud PgException (undefined relation), never a silent success or an empty result.
        String schema = uniqueSchema("binding_notable");
        SetupBindingRegistry registry = new SetupBindingRegistry(vertx, registryDb(schema));

        SetupBinding binding = containerBinding("orphan", "orphan_schema", false, null);

        registry.saveBinding(binding)
                .transform(ar -> {
                    assertTrue(ar.failed(), "saveBinding without ensureSchema must fail");
                    assertTrue(hasCauseOfType(ar.cause(), PgException.class),
                            "failure must carry a PgException, got: " + ar.cause());
                    return Future.succeededFuture();
                })
                .compose(v -> registry.listBindings().transform(ar -> {
                    assertTrue(ar.failed(), "listBindings without ensureSchema must fail");
                    assertTrue(hasCauseOfType(ar.cause(), PgException.class),
                            "failure must carry a PgException, got: " + ar.cause());
                    return Future.succeededFuture();
                }))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.SetupBindingRegistry",
            message = "Failed to ensure setup-binding registry schema in 'public': ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    void ensureSchemaFailsClearlyWhenRegistryDatabaseIsUnreachable(Vertx vertx, VertxTestContext ctx) {
        // Dependency failure mode: a registry database that does not exist must surface as a failed
        // Future carrying the PostgreSQL error — never be swallowed.
        DatabaseConfig unreachable = new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName("no_such_registry_db_" + System.currentTimeMillis())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema("public")
                .build();
        SetupBindingRegistry registry = new SetupBindingRegistry(vertx, unreachable);

        registry.ensureSchema()
                .transform(ar -> {
                    assertTrue(ar.failed(), "ensureSchema against a missing database must fail");
                    assertTrue(hasCauseOfType(ar.cause(), PgException.class),
                            "failure must carry a PgException, got: " + ar.cause());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void constructorRejectsMissingOrInvalidSchema(Vertx vertx, VertxTestContext ctx) {
        DatabaseConfig noSchema = new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName(getPostgres().getDatabaseName())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .build();
        assertThrows(IllegalArgumentException.class, () -> new SetupBindingRegistry(vertx, noSchema),
                "a registry config without a schema must be rejected at construction");

        DatabaseConfig badSchema = new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName(getPostgres().getDatabaseName())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema("bad-schema; DROP TABLE x")
                .build();
        assertThrows(IllegalArgumentException.class, () -> new SetupBindingRegistry(vertx, badSchema),
                "an invalid schema identifier must be rejected at construction");
        ctx.completeNow();
    }

    private static boolean hasCauseOfType(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
