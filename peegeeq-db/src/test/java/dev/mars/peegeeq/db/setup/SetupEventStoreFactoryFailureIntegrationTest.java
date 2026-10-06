package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventStoreFactory;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for event store creation failures in {@link PeeGeeQDatabaseSetupService}.
 *
 * A setup that requests an event store must either hold that event store or fail. Each test
 * supplies an {@link EventStoreFactory} through the service constructor and drives one failure
 * mode of {@code EventStoreFactory.createEventStore}: the factory throws, or the factory
 * returns null.
 */
@Tag(TestCategories.INTEGRATION)
class SetupEventStoreFactoryFailureIntegrationTest extends BaseIntegrationTest {

    private static final String THROWING_STORE = "store_failure_throwing_events";
    private static final String NULL_STORE = "store_failure_null_events";
    private static final String ADDED_STORE = "store_failure_added_events";
    private static final String FACTORY_FAILURE = "event store factory rejected the configuration";

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

    private static EventStoreConfig eventStore(String name) {
        return new EventStoreConfig.Builder()
                .eventStoreName(name)
                .tableName(name)
                .notificationPrefix(name + "_")
                .build();
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
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create event store for: " + THROWING_STORE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: store-failure-throwing-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void createFailsWhenEventStoreFactoryThrows(VertxTestContext ctx) {
        String dbName = "store_failure_throwing_db_" + System.currentTimeMillis();
        String setupId = "store-failure-throwing-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(manager -> new ThrowingEventStoreFactory());
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(eventStore(THROWING_STORE)), Map.of());

        service.createCompleteSetup(request)
                .transform(ar -> {
                    if (ar.succeeded()) {
                        // The create reported success without the requested event store.
                        // Remove the provisioned database before failing the test.
                        return service.destroySetup(setupId).transform(destroyed -> Future.failedFuture(
                                new AssertionError("create must fail when the event store factory throws, "
                                        + "but returned status " + ar.result().getStatus()
                                        + " with event stores " + ar.result().getEventStores().keySet())));
                    }
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, FACTORY_FAILURE),
                            "failure must carry the factory's exception, got: " + ar.cause());
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
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create event store for: " + NULL_STORE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: store-failure-null-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void createFailsWhenEventStoreFactoryReturnsNull(VertxTestContext ctx) {
        String dbName = "store_failure_null_db_" + System.currentTimeMillis();
        String setupId = "store-failure-null-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(manager -> new NullEventStoreFactory());
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(eventStore(NULL_STORE)), Map.of());

        service.createCompleteSetup(request)
                .transform(ar -> {
                    if (ar.succeeded()) {
                        // The create reported success with a null event store.
                        // Remove the provisioned database before failing the test.
                        return service.destroySetup(setupId).transform(destroyed -> Future.failedFuture(
                                new AssertionError("create must fail when the event store factory returns null, "
                                        + "but returned status " + ar.result().getStatus()
                                        + " with event stores " + ar.result().getEventStores())));
                    }
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, NULL_STORE),
                            "failure must name the event store that was null, got: " + ar.cause());
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
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create event store for: " + ADDED_STORE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to add event store '" + ADDED_STORE + "' to setup 'store-failure-added-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    void addEventStoreFailsWhenEventStoreFactoryThrows(VertxTestContext ctx) {
        String dbName = "store_failure_added_db_" + System.currentTimeMillis();
        String setupId = "store-failure-added-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service =
                new PeeGeeQDatabaseSetupService(manager -> new ThrowingEventStoreFactory());
        // The setup is created with no event stores, so the factory is first used by addEventStore.
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of());

        service.createCompleteSetup(request)
                .compose(result -> service.addEventStore(setupId, eventStore(ADDED_STORE)).transform(ar -> {
                    assertTrue(ar.failed(), "addEventStore must fail when the event store factory throws");
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, FACTORY_FAILURE),
                            "failure must carry the factory's exception, got: " + ar.cause());
                    return Future.succeededFuture();
                }))
                .eventually(() -> service.destroySetup(setupId))
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    /** Event store factory whose creation call throws. */
    private static class ThrowingEventStoreFactory implements EventStoreFactory {
        @Override
        public <T> EventStore<T> createEventStore(Class<T> payloadType, String tableName) {
            throw new IllegalStateException(FACTORY_FAILURE);
        }

        @Override
        public String getFactoryName() {
            return "throwing-event-store-factory";
        }
    }

    /** Event store factory whose creation call returns null. */
    private static class NullEventStoreFactory implements EventStoreFactory {
        @Override
        public <T> EventStore<T> createEventStore(Class<T> payloadType, String tableName) {
            return null;
        }

        @Override
        public String getFactoryName() {
            return "null-event-store-factory";
        }
    }
}
