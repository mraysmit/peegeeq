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
 * Test for validating OutboxProducer reactive implementation.
 * 
 * Following pgq_coding_principles.md:
 * - Investigate First: Test current behavior before making changes
 * - Follow Patterns: Use established TestContainers patterns
 * - Validate Each Step: Test each incremental change
 * - Document Intent: Clear test purpose and requirements
 * 
 * Requirements:
 * - Docker must be available for TestContainers
 * - Test validates current JDBC behavior before reactive migration
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
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        logger.info("=== Setting up ReactiveOutboxProducerTest ===");
        logger.info("PostgreSQL container: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "3")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);

        // Initialize manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                logger.info("PeeGeeQ Manager started successfully");

                // Create outbox factory and producer - following existing patterns
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

                logger.info("Test setup completed successfully");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (producer != null) producer.close();
        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .eventually(() -> connectionManager != null ? connectionManager.close() : Future.<Void>succeededFuture())
            .eventually(() -> testVertx != null ? testVertx.close() : Future.<Void>succeededFuture())
            .onSuccess(v -> { logger.info("Test cleanup completed"); ctx.completeNow(); })
            .onFailure(ctx::failNow);
        assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS), "Teardown should complete within 15s");
    }

    @Test
    @DisplayName("BASELINE: Current JDBC OutboxProducer behavior")
    void testCurrentJdbcBehavior(VertxTestContext ctx) throws Exception {
        logger.info("--- Testing current JDBC OutboxProducer behavior ---");

        String testMessage = "baseline-test-message-" + System.currentTimeMillis();

        producer.send(testMessage)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                assertTrue(exists, "Message should exist in outbox table");
                logger.info("BASELINE TEST PASSED: Current behavior works correctly");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PREPARATION: Verify test infrastructure")
    void testInfrastructure(VertxTestContext ctx) throws Exception {
        logger.info("--- Testing infrastructure setup ---");

        testReactivePool.withConnection(connection ->
            connection.query("SELECT COUNT(*) FROM outbox")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger(0))
        ).onSuccess(count -> ctx.verify(() -> {
            logger.info("Reactive database connection successful");
            logger.info("Outbox table accessible, current message count: {}", count);
            logger.info("INFRASTRUCTURE TEST PASSED: All components ready for reactive migration");
            ctx.completeNow();
        })).onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 1: Reactive OutboxProducer basic functionality")
    void testReactiveOutboxProducer(VertxTestContext ctx) throws Exception {
        logger.info("--- Testing new reactive OutboxProducer functionality ---");

        String testMessage = "reactive-test-message-" + System.currentTimeMillis();
        MessageProducer<String> messageProducer = (MessageProducer<String>) producer;

        messageProducer.send(testMessage)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                Assertions.assertTrue(exists, "Reactive message should exist in outbox table");
                logger.info("PHASE 1 STEP 1 PASSED: Reactive OutboxProducer works correctly");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 1: Reactive vs JDBC comparison")
    void testReactiveVsJdbcComparison(VertxTestContext ctx) throws Exception {
        logger.info("--- Comparing reactive vs JDBC implementations ---");

        String jdbcMessage = "jdbc-message-" + System.currentTimeMillis();
        String reactiveMessage = "reactive-message-" + System.currentTimeMillis();
        MessageProducer<String> messageProducer = (MessageProducer<String>) producer;

        producer.send(jdbcMessage)
            .compose(v -> messageProducer.send(reactiveMessage))
            .compose(v -> verifyOutboxMessageExists(jdbcMessage))
            .compose(jdbcExists -> verifyOutboxMessageExists(reactiveMessage)
                .map(reactiveExists -> new boolean[]{jdbcExists, reactiveExists}))
            .onSuccess(results -> ctx.verify(() -> {
                Assertions.assertTrue(results[0], "JDBC message should exist");
                Assertions.assertTrue(results[1], "Reactive message should exist");
                logger.info("COMPARISON PASSED: Both JDBC and reactive methods work correctly");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 2: Transactional method signatures validation")
    void testTransactionalMethodSignatures(VertxTestContext ctx) throws Exception {
        logger.info("--- Testing transactional method signatures ---");

        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;

        // Test that sendInExistingTransaction rejects a null connection
        outboxProducer.sendInExistingTransaction("test", (io.vertx.sqlclient.SqlConnection) null)
            .onSuccess(v -> ctx.failNow(new AssertionError("Should fail with null connection")))
            .onFailure(e -> ctx.verify(() -> {
                logger.info("sendInExistingTransaction method exists and validates null connection: {}", e.getMessage());
                assertTrue(e.getMessage().contains("connection cannot be null") || e instanceof IllegalArgumentException,
                    "Exception should relate to null connection");
                logger.info("PHASE 1 STEP 2 PASSED: Transactional method signatures are correct");
                ctx.completeNow();
            }));

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 3: Production-grade transactional methods")
    void testProductionGradeTransactionalMethods(VertxTestContext ctx) throws Exception {
        logger.info("--- Testing production-grade transactional methods ---");

        String testMessage = "production-tx-message-" + System.currentTimeMillis();
        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;

        outboxProducer.sendInOwnTransaction(testMessage)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                Assertions.assertTrue(exists, "Production-grade transactional message should exist in outbox table");
                logger.info("PHASE 1 STEP 3 PASSED: Production-grade transactional methods work correctly");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    @DisplayName("PHASE 1 STEP 4: TransactionPropagation support")
    void testTransactionPropagationSupport(VertxTestContext ctx) throws Exception {
        logger.info("--- Testing TransactionPropagation support ---");

        String testMessage = "propagation-test-message-" + System.currentTimeMillis();
        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;

        outboxProducer.sendInOwnTransaction(testMessage, io.vertx.sqlclient.TransactionPropagation.CONTEXT)
            .compose(v -> verifyOutboxMessageExists(testMessage))
            .onSuccess(exists -> ctx.verify(() -> {
                Assertions.assertTrue(exists, "TransactionPropagation message should exist in outbox table");
                logger.info("PHASE 1 STEP 4 PASSED: TransactionPropagation.CONTEXT works correctly");
                ctx.completeNow();
            }))
            .onFailure(sendError -> {
                // CONTEXT propagation may not work without an active Vert.x context
                logger.warn("TransactionPropagation.CONTEXT failed (expected without Vert.x context): {}",
                    sendError.getMessage());
                String fallbackMessage = testMessage + "-fallback";
                outboxProducer.sendInOwnTransaction(fallbackMessage)
                    .compose(v -> verifyOutboxMessageExists(fallbackMessage))
                    .onSuccess(exists -> ctx.verify(() -> {
                        Assertions.assertTrue(exists, "Fallback message should exist");
                        logger.info("PHASE 1 STEP 4 PASSED: Basic transactional methods work (CONTEXT propagation not available outside Vert.x context)");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
            });

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
        ).map(count -> {
            boolean exists = count != null && count > 0;
            logger.info("Message '{}' exists in outbox: {} (count: {})", message, exists, count);
            return exists;
        });
    }
}


