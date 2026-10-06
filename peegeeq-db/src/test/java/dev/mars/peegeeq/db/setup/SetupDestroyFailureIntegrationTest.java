package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactory;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PeeGeeQDatabaseSetupService#destroySetup} when a setup-owned
 * resource fails to close.
 *
 * destroySetup removes the setup from the service's maps before it closes anything. The manager
 * must therefore be closed on every path, because nothing can reach it afterwards.
 */
@Tag(TestCategories.INTEGRATION)
class SetupDestroyFailureIntegrationTest extends BaseIntegrationTest {

    private static final String CLOSE_FAILURE = "queue factory close rejected synchronously";

    private DatabaseConfig setupDb() {
        return new DatabaseConfig.Builder()
                .host(getPostgres().getHost())
                .port(getPostgres().getFirstMappedPort())
                .databaseName(getPostgres().getDatabaseName())
                .username(getPostgres().getUsername())
                .password(getPostgres().getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
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
            message = "Failed to close queue factory for setup destroy-failure-sync-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    void destroyClosesManagerWhenQueueFactoryCloseThrowsSynchronously(VertxTestContext ctx) {
        String setupId = "destroy-failure-sync-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        // A second manager on the base configuration stands in for the setup's own manager.
        PeeGeeQManager setupManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        setupManager.start()
                .compose(v -> {
                    Map<String, QueueFactory> factories = new HashMap<>();
                    factories.put("destroy_failure_queue",
                            new SyncThrowingCloseQueueFactory(new PgDatabaseService(setupManager)));
                    DatabaseSetupResult result = new DatabaseSetupResult(
                            setupId, factories, new HashMap<>(), DatabaseSetupStatus.ACTIVE);
                    service.registerSetupForTesting(setupId, result, setupDb(), setupManager);
                    return service.destroySetup(setupId);
                })
                .transform(ar -> {
                    assertTrue(ar.failed(), "destroySetup must report the queue factory close failure");
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, CLOSE_FAILURE),
                            "failure must carry the close exception, got: " + ar.cause());
                    assertFalse(setupManager.isStarted(),
                            "the manager must be closed even though a queue factory close threw");
                    return Future.succeededFuture();
                })
                .eventually(() -> setupManager.closeReactive())
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to close queue factory for setup destroy-failure-nullentry-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = NullPointerException.class)
    void destroyClosesManagerWhenQueueFactoryEntryIsNull(VertxTestContext ctx) {
        String setupId = "destroy-failure-nullentry-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        PeeGeeQManager setupManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        setupManager.start()
                .compose(v -> {
                    Map<String, QueueFactory> factories = new HashMap<>();
                    factories.put("destroy_failure_queue", null);
                    DatabaseSetupResult result = new DatabaseSetupResult(
                            setupId, factories, new HashMap<>(), DatabaseSetupStatus.ACTIVE);
                    service.registerSetupForTesting(setupId, result, setupDb(), setupManager);
                    return service.destroySetup(setupId);
                })
                .transform(ar -> {
                    assertTrue(ar.failed(), "destroySetup must report the null queue factory entry");
                    assertFalse(setupManager.isStarted(),
                            "the manager must be closed even though a queue factory entry was null");
                    return Future.succeededFuture();
                })
                .eventually(() -> setupManager.closeReactive())
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to close queue factory for setup destroy-failure-nullfuture-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    void destroyClosesManagerWhenQueueFactoryCloseReturnsNull(VertxTestContext ctx) {
        String setupId = "destroy-failure-nullfuture-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        PeeGeeQManager setupManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        setupManager.start()
                .compose(v -> {
                    Map<String, QueueFactory> factories = new HashMap<>();
                    factories.put("destroy_failure_queue",
                            new NullCloseQueueFactory(new PgDatabaseService(setupManager)));
                    DatabaseSetupResult result = new DatabaseSetupResult(
                            setupId, factories, new HashMap<>(), DatabaseSetupStatus.ACTIVE);
                    service.registerSetupForTesting(setupId, result, setupDb(), setupManager);
                    return service.destroySetup(setupId);
                })
                .transform(ar -> {
                    assertTrue(ar.failed(), "destroySetup must report a close call that returned no Future");
                    assertFalse(setupManager.isStarted(),
                            "the manager must be closed even though a queue factory close returned null");
                    return Future.succeededFuture();
                })
                .eventually(() -> setupManager.closeReactive())
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    /** Queue factory whose close() returns null instead of a Future. */
    private static class NullCloseQueueFactory extends SyncThrowingCloseQueueFactory {
        NullCloseQueueFactory(DatabaseService databaseService) {
            super(databaseService);
        }

        @Override
        public String getImplementationType() {
            return "null-close";
        }

        @Override
        public Future<Void> close() {
            return null;
        }
    }

    /** Queue factory whose close() throws instead of returning a failed Future. */
    private static class SyncThrowingCloseQueueFactory extends PgQueueFactory {
        SyncThrowingCloseQueueFactory(DatabaseService databaseService) {
            super(databaseService);
        }

        @Override
        public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public <T> Future<QueueBrowser<T>> createBrowser(String topic, Class<T> payloadType) {
            return Future.failedFuture(new UnsupportedOperationException("not exercised by this test"));
        }

        @Override
        public String getImplementationType() {
            return "sync-throwing-close";
        }

        @Override
        public Future<Void> close() {
            throw new IllegalStateException(CLOSE_FAILURE);
        }

        @Override
        protected Future<Void> closeResources() {
            return Future.succeededFuture();
        }
    }
}
