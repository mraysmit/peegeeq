package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for the reactive OutboxProducer implementation.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class ReactiveOutboxProducerTest {

    private static final Logger logger = LoggerFactory.getLogger(ReactiveOutboxProducerTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;
    private io.vertx.core.Vertx testVertx;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "3")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);

        // Initialize manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                // Create outbox factory and producer
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith(provider);

                QueueFactory factory = provider.createFactory("outbox", databaseService);
                producer = factory.createProducer("reactive-test", String.class);

                // Create test-specific Vert.x instance and connection manager for verification queries
                testVertx = io.vertx.core.Vertx.vertx();
                connectionManager = new PgConnectionManager(testVertx);
                PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                        .host(postgres.getHost())
                        .port(postgres.getFirstMappedPort())
                        .database(postgres.getDatabaseName())
                        .username(postgres.getUsername())
                        .password(postgres.getPassword())
                        .build();
                PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
                testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);

                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws Exception {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing producer: {}", e.getMessage());
            }
        }
        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .eventually(() -> connectionManager != null ? connectionManager.close() : Future.<Void>succeededFuture())
            .eventually(() -> testVertx != null ? testVertx.close() : Future.<Void>succeededFuture())
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
        assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS), "Teardown should complete within 15s");
    }

    @Test
    @DisplayName("BASELINE: Current JDBC OutboxProducer behavior")
    void testCurrentJdbcBehavior(VertxTestContext ctx) throws Exception {
        String testMessage = "baseline-test-message-" + System.currentTimeMillis();

        producer.send(testMessage)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                assertTrue(exists, "Message should exist in outbox table");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PREPARATION: Verify test infrastructure")
    void testInfrastructure(VertxTestContext ctx) throws Exception {

        testReactivePool.withConnection(connection ->
            connection.query("SELECT COUNT(*) FROM outbox")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger(0))
        ).onSuccess(count -> ctx.verify(() -> {
            assertNotNull(count, "outbox table should be queryable");
            ctx.completeNow();
        })).onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 1: Reactive OutboxProducer basic functionality")
    void testReactiveOutboxProducer(VertxTestContext ctx) throws Exception {
        String testMessage = "reactive-test-message-" + System.currentTimeMillis();

        producer.send(testMessage)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                assertTrue(exists, "Reactive message should exist in outbox table");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("Sequential sends: two messages both persist in outbox")
    void testReactiveVsJdbcComparison(VertxTestContext ctx) throws Exception {
        String firstMessage = "first-message-" + System.currentTimeMillis();
        String secondMessage = "second-message-" + System.currentTimeMillis();

        producer.send(firstMessage)
            .compose(v -> producer.send(secondMessage))
            .compose(v -> verifyOutboxMessageExists(firstMessage))
            .compose(firstExists -> verifyOutboxMessageExists(secondMessage)
                .map(secondExists -> new boolean[]{firstExists, secondExists}))
            .onSuccess(results -> ctx.verify(() -> {
                assertTrue(results[0], "First message should exist in outbox");
                assertTrue(results[1], "Second message should exist in outbox");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 2: Transactional method signatures validation")
    void testTransactionalMethodSignatures(VertxTestContext ctx) throws Exception {
        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;

        outboxProducer.sendInExistingTransaction("test", (io.vertx.sqlclient.SqlConnection) null)
            .onSuccess(v -> ctx.failNow(new AssertionError("Should fail with null connection")))
            .onFailure(e -> ctx.verify(() -> {
                assertTrue(e.getMessage().contains("connection cannot be null") || e instanceof IllegalArgumentException,
                    "Exception should relate to null connection");
                ctx.completeNow();
            }));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 3: Production-grade transactional methods")
    void testProductionGradeTransactionalMethods(VertxTestContext ctx) throws Exception {
        String testMessage = "production-tx-message-" + System.currentTimeMillis();
        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;

        outboxProducer.sendInOwnTransaction(testMessage)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                assertTrue(exists, "Production-grade transactional message should exist in outbox table");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 4: TransactionPropagation support")
    void testTransactionPropagationSupport(VertxTestContext ctx) throws Exception {
        String testMessage = "propagation-test-message-" + System.currentTimeMillis();
        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;

        // TransactionPropagation.CONTEXT requires a Vert.x context on the calling thread;
        // dispatch onto testVertx's event loop so currentContext() is non-null.
        Promise<Void> sendOnContext = Promise.promise();
        testVertx.runOnContext(v ->
            outboxProducer.sendInOwnTransaction(testMessage, io.vertx.sqlclient.TransactionPropagation.CONTEXT)
                .onSuccess(sendOnContext::complete)
                .onFailure(sendOnContext::fail)
        );

        sendOnContext.future()
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                assertTrue(exists, "TransactionPropagation message should exist in outbox table");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS), "Test should complete within 15s");
    }

    /**
     * Verify that a message exists in the outbox table.
     */
    private Future<Boolean> verifyOutboxMessageExists(String message) {
        return testReactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT COUNT(*) FROM outbox WHERE payload::text LIKE $1")
                .execute(io.vertx.sqlclient.Tuple.of("%" + message + "%"))
                .map(rowSet -> rowSet.iterator().next().getInteger(0))
        ).map(count -> count != null && count > 0);
    }
}


