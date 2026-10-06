package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.provider.PgQueueFactory;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for queue factory creation failures during
 * {@link PeeGeeQDatabaseSetupService#createCompleteSetup}.
 *
 * A setup that requests a queue must either hold a factory for that queue or fail. Each test
 * registers a queue implementation type through the service's own
 * {@code addFactoryRegistration} extension point and drives one failure mode of
 * {@code QueueFactoryProvider.createFactory}: the creator throws, or the creator returns null.
 * A third test proves that a factory created before the failing one is closed.
 */
@Tag(TestCategories.INTEGRATION)
class SetupQueueFactoryFailureIntegrationTest extends BaseIntegrationTest {

    private static final String THROWING_TYPE = "throwing";
    private static final String NULL_TYPE = "nullfactory";
    private static final String WORKING_TYPE = "working";
    private static final String THROWING_QUEUE = "factory_failure_throwing_queue";
    private static final String EARLIER_WORKING_QUEUE = "factory_failure_earlier_queue";
    private static final String LATER_THROWING_QUEUE = "factory_failure_later_queue";
    private static final String NULL_QUEUE = "factory_failure_null_queue";
    private static final String CREATOR_FAILURE = "queue factory creator rejected the configuration";
    private static final String REGISTRATION_FAILURE = "queue factory registration rejected the registrar";

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
            message = "Failed to create queue factory for queue: " + THROWING_QUEUE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: factory-failure-throwing-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void createFailsWhenQueueFactoryCreatorThrows(VertxTestContext ctx) {
        String dbName = "factory_failure_throwing_db_" + System.currentTimeMillis();
        String setupId = "factory-failure-throwing-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        service.addFactoryRegistration(registrar -> registrar.registerFactory(THROWING_TYPE,
                (databaseService, configuration) -> {
                    throw new IllegalStateException(CREATOR_FAILURE);
                }));
        QueueConfig queue = new QueueConfig.Builder()
                .queueName(THROWING_QUEUE)
                .implementationType(THROWING_TYPE)
                .build();
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(queue), List.of(), Map.of());

        service.createCompleteSetup(request)
                .transform(ar -> {
                    if (ar.succeeded()) {
                        // The create reported success without a factory for the requested queue.
                        // Remove the provisioned database before failing the test.
                        return service.destroySetup(setupId).transform(destroyed -> Future.failedFuture(
                                new AssertionError("create must fail when the queue factory creator throws, "
                                        + "but returned status " + ar.result().getStatus()
                                        + " with queue factories " + ar.result().getQueueFactories().keySet())));
                    }
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, CREATOR_FAILURE),
                            "failure must carry the creator's exception, got: " + ar.cause());
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
            message = "Failed to create queue factory for queue: " + NULL_QUEUE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: factory-failure-null-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void createFailsWhenQueueFactoryCreatorReturnsNull(VertxTestContext ctx) {
        String dbName = "factory_failure_null_db_" + System.currentTimeMillis();
        String setupId = "factory-failure-null-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        service.addFactoryRegistration(registrar -> registrar.registerFactory(NULL_TYPE,
                (databaseService, configuration) -> null));
        QueueConfig queue = new QueueConfig.Builder()
                .queueName(NULL_QUEUE)
                .implementationType(NULL_TYPE)
                .build();
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(queue), List.of(), Map.of());

        service.createCompleteSetup(request)
                .transform(ar -> {
                    if (ar.succeeded()) {
                        // The create reported success with a null factory for the requested queue.
                        // Remove the provisioned database before failing the test.
                        return service.destroySetup(setupId).transform(destroyed -> Future.failedFuture(
                                new AssertionError("create must fail when the queue factory creator returns null, "
                                        + "but returned status " + ar.result().getStatus()
                                        + " with queue factories " + ar.result().getQueueFactories())));
                    }
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, NULL_QUEUE),
                            "failure must name the queue whose factory was null, got: " + ar.cause());
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
            message = "Failed to create queue factory for queue: " + LATER_THROWING_QUEUE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: factory-failure-earlier-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void createClosesEarlierFactoriesWhenLaterCreatorThrows(VertxTestContext ctx) {
        String dbName = "factory_failure_earlier_db_" + System.currentTimeMillis();
        String setupId = "factory-failure-earlier-" + System.currentTimeMillis();

        AtomicReference<QueueFactory> earlierFactory = new AtomicReference<>();
        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        service.addFactoryRegistration(registrar -> {
            registrar.registerFactory(WORKING_TYPE, (databaseService, configuration) -> {
                QueueFactory factory = new WorkingQueueFactory(databaseService);
                earlierFactory.set(factory);
                return factory;
            });
            registrar.registerFactory(THROWING_TYPE, (databaseService, configuration) -> {
                throw new IllegalStateException(CREATOR_FAILURE);
            });
        });
        QueueConfig earlierQueue = new QueueConfig.Builder()
                .queueName(EARLIER_WORKING_QUEUE)
                .implementationType(WORKING_TYPE)
                .build();
        QueueConfig laterQueue = new QueueConfig.Builder()
                .queueName(LATER_THROWING_QUEUE)
                .implementationType(THROWING_TYPE)
                .build();
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(earlierQueue, laterQueue), List.of(), Map.of());

        service.createCompleteSetup(request)
                .<Boolean>transform(ar -> {
                    if (ar.succeeded()) {
                        return service.destroySetup(setupId).transform(destroyed -> Future.<Boolean>failedFuture(
                                new AssertionError("create must fail when a later queue factory creator throws, "
                                        + "but returned status " + ar.result().getStatus())));
                    }
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, CREATOR_FAILURE),
                            "failure must carry the creator's exception, got: " + ar.cause());
                    assertNotNull(earlierFactory.get(), "the earlier queue's factory must have been created");
                    return earlierFactory.get().isHealthy();
                })
                .compose(healthy -> {
                    assertFalse(healthy, "the factory created before the failure must be closed, "
                            + "because the failed setup does not own it");
                    return Future.succeededFuture();
                })
                .eventually(() -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to apply factory registration: " + REGISTRATION_FAILURE,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService",
            message = "Failed to create database setup: factory-failure-registration-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void createFailsWhenFactoryRegistrationThrows(VertxTestContext ctx) {
        String dbName = "factory_failure_registration_db_" + System.currentTimeMillis();
        String setupId = "factory-failure-registration-" + System.currentTimeMillis();

        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        service.addFactoryRegistration(registrar -> {
            throw new IllegalStateException(REGISTRATION_FAILURE);
        });
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, setupDb(dbName), List.of(), List.of(), Map.of());

        service.createCompleteSetup(request)
                .transform(ar -> {
                    if (ar.succeeded()) {
                        // The create reported success although a factory registration failed.
                        // Remove the provisioned database before failing the test.
                        return service.destroySetup(setupId).transform(destroyed -> Future.failedFuture(
                                new AssertionError("create must fail when a factory registration throws, "
                                        + "but returned status " + ar.result().getStatus())));
                    }
                    assertTrue(chainContains(ar.cause(), IllegalStateException.class, REGISTRATION_FAILURE),
                            "failure must carry the registration's exception, got: " + ar.cause());
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

    /** Queue factory that creates successfully. Its messaging operations are not exercised. */
    private static class WorkingQueueFactory extends PgQueueFactory {
        WorkingQueueFactory(DatabaseService databaseService) {
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
            return WORKING_TYPE;
        }

        @Override
        protected Future<Void> closeResources() {
            return Future.succeededFuture();
        }
    }
}
