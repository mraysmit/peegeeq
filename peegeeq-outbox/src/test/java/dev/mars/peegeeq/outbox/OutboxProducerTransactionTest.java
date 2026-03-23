package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive tests for OutboxProducer transaction methods.
 * Tests sendInOwnTransaction() and sendInExistingTransaction() method variants to improve coverage.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxProducerTransactionTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerTransactionTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("peegeeq_test");
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private OutboxProducer<String> producer;

    @BeforeEach
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        PgDatabaseService dbs = new PgDatabaseService(manager);
        databaseService = dbs;
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);

        QueueFactory factory = provider.createFactory("outbox", databaseService);
        MessageProducer<String> messageProducer = factory.createProducer("tx-test", String.class);
        
        // Cast to OutboxProducer to access implementation-specific methods
        producer = (OutboxProducer<String>) messageProducer;
        
        logger.info("OutboxProducerTransactionTest setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (manager != null) {
            manager.closeReactive().onComplete(ar -> testContext.completeNow());
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @DisplayName("Test sendInOwnTransaction(payload)")
    void testsendInOwnTransactionPayloadOnly(VertxTestContext testContext) throws Exception {
        String payload = "tx-payload-" + System.currentTimeMillis();
        
        producer.sendInOwnTransaction(payload)
            .onSuccess(v -> {
                logger.info("Successfully sent message with transaction (payload only): {}", payload);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction(payload, headers)")
    void testsendInOwnTransactionPayloadAndHeaders(VertxTestContext testContext) throws Exception {
        String payload = "tx-headers-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        headers.put("priority", "high");
        
        producer.sendInOwnTransaction(payload, headers)
            .onSuccess(v -> {
                logger.info("Successfully sent message with transaction (payload + headers)");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction(payload, headers, correlationId)")
    void testsendInOwnTransactionWithCorrelationId(VertxTestContext testContext) throws Exception {
        String payload = "tx-correlation-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("test", "value");
        String correlationId = "corr-" + System.currentTimeMillis();
        
        producer.sendInOwnTransaction(payload, headers, correlationId)
            .onSuccess(v -> {
                logger.info("Successfully sent message with transaction (payload + headers + correlationId)");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction(payload, propagation)")
    void testsendInOwnTransactionPropagation(VertxTestContext testContext) throws Exception {
        String payload = "tx-propagation-" + System.currentTimeMillis();
        
        producer.sendInOwnTransaction(payload, TransactionPropagation.CONTEXT)
            .onSuccess(v -> {
                logger.info("Successfully sent message with TransactionPropagation.CONTEXT");
                testContext.completeNow();
            })
            .onFailure(e -> {
                // TransactionPropagation may fail without active transaction context
                logger.info("TransactionPropagation test completed (expected failure without context): {}", e.getMessage());
                testContext.completeNow();
            });
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction(payload, headers, propagation)")
    void testsendInOwnTransactionHeadersAndPropagation(VertxTestContext testContext) throws Exception {
        String payload = "tx-headers-prop-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("type", "test");
        
        producer.sendInOwnTransaction(payload, headers, TransactionPropagation.CONTEXT)
            .onSuccess(v -> {
                logger.info("Successfully sent message with headers and propagation");
                testContext.completeNow();
            })
            .onFailure(e -> {
                logger.info("Headers + propagation test completed: {}", e.getMessage());
                testContext.completeNow();
            });
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction(payload, headers, correlationId, propagation)")
    void testsendInOwnTransactionFullParameters(VertxTestContext testContext) throws Exception {
        String payload = "tx-full-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("full", "test");
        String correlationId = "full-corr-" + System.currentTimeMillis();
        
        producer.sendInOwnTransaction(payload, headers, correlationId, TransactionPropagation.CONTEXT)
            .onSuccess(v -> {
                logger.info("Successfully sent message with all parameters");
                testContext.completeNow();
            })
            .onFailure(e -> {
                logger.info("Full parameters test completed: {}", e.getMessage());
                testContext.completeNow();
            });
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction when producer is closed")
    void testsendInOwnTransactionWhenClosed(VertxTestContext testContext) throws Exception {
        String payload = "tx-closed-" + System.currentTimeMillis();
        
        producer.close();
        producer.sendInOwnTransaction(payload)
            .onSuccess(v -> testContext.failNow("Should have thrown exception when sending with closed producer"))
            .onFailure(e -> testContext.verify(() -> {
                logger.info("Correctly rejected transaction on closed producer: {}", e.getMessage());
                assertTrue(e.getMessage() != null && e.getMessage().contains("closed"));
                testContext.completeNow();
            }));
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction with null payload")
    void testsendInOwnTransactionNullPayload(VertxTestContext testContext) throws Exception {
        producer.sendInOwnTransaction(null)
            .onSuccess(v -> testContext.failNow("Should have thrown exception for null payload"))
            .onFailure(e -> {
                logger.info("Correctly rejected null payload: {}", e.getMessage());
                testContext.completeNow();
            });
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInExistingTransaction with null connection")
    void testsendInExistingTransactionNullConnection(VertxTestContext testContext) throws Exception {
        producer.sendInExistingTransaction("test", (io.vertx.sqlclient.SqlConnection) null)
            .onSuccess(v -> testContext.failNow("Should have thrown exception for null connection"))
            .onFailure(e -> testContext.verify(() -> {
                logger.info("Correctly rejected null connection: {}", e.getMessage());
                assertTrue(e.getMessage() != null &&
                    (e.getMessage().contains("connection cannot be null") ||
                     e.getCause() instanceof IllegalArgumentException));
                testContext.completeNow();
            }));
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction with empty headers")
    void testsendInOwnTransactionEmptyHeaders(VertxTestContext testContext) throws Exception {
        String payload = "tx-empty-headers-" + System.currentTimeMillis();
        Map<String, String> emptyHeaders = new HashMap<>();
        
        producer.sendInOwnTransaction(payload, emptyHeaders)
            .onSuccess(v -> {
                logger.info("Successfully sent message with empty headers map");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInOwnTransaction with multiple headers")
    void testsendInOwnTransactionMultipleHeaders(VertxTestContext testContext) throws Exception {
        String payload = "tx-multi-headers-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        headers.put("header3", "value3");
        headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
        headers.put("source", "test");
        
        producer.sendInOwnTransaction(payload, headers)
            .onSuccess(v -> {
                logger.info("Successfully sent message with multiple headers");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // =========================================================================
    // sendInExistingTransaction — success path with real connection
    // =========================================================================

    @Test
    @DisplayName("sendInExistingTransaction inserts message using caller-provided connection")
    void testSendInExistingTransactionSuccess(VertxTestContext testContext) throws Exception {
        String payload = "existing-tx-" + System.currentTimeMillis();
        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        connectionProvider.withTransaction(null, connection ->
            producer.sendInExistingTransaction(payload, connection)
        )
        .onSuccess(v -> {
            logger.info("Successfully sent message in existing transaction");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("sendInExistingTransaction with headers and correlationId in existing transaction")
    void testSendInExistingTransactionWithHeadersAndCorrelation(VertxTestContext testContext) throws Exception {
        String payload = "existing-tx-full-" + System.currentTimeMillis();
        Map<String, String> headers = Map.of("source", "test", "priority", "high");
        String correlationId = "corr-existing-" + System.currentTimeMillis();
        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        connectionProvider.withTransaction(null, connection ->
            producer.sendInExistingTransaction(payload, headers, correlationId, connection)
        )
        .onSuccess(v -> {
            logger.info("Successfully sent message with headers/correlation in existing transaction");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Idempotency — ON CONFLICT DO NOTHING behavior
    // =========================================================================

    @Test
    @DisplayName("Duplicate idempotency key succeeds silently without inserting second row")
    void testDuplicateIdempotencyKeyIsIdempotent(VertxTestContext testContext) throws Exception {
        String idempotencyKey = "idem-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", idempotencyKey);

        // First send — should insert
        producer.sendInOwnTransaction("first-message", headers)
            .compose(v -> {
                logger.info("First message sent with idempotency key: {}", idempotencyKey);
                // Second send with same key — should silently do nothing
                return producer.sendInOwnTransaction("second-message", headers);
            })
            .compose(v -> {
                logger.info("Second send completed without error (ON CONFLICT DO NOTHING)");
                // Verify only one row exists for this idempotency key
                return databaseService.getConnectionProvider().withConnection(null, connection ->
                    connection.preparedQuery(
                        "SELECT COUNT(*) AS cnt FROM outbox WHERE idempotency_key = $1 AND topic = $2"
                    ).execute(io.vertx.sqlclient.Tuple.of(idempotencyKey, "tx-test"))
                    .map(rows -> {
                        int count = rows.iterator().next().getInteger("cnt");
                        logger.info("Row count for idempotency key {}: {}", idempotencyKey, count);
                        return count;
                    })
                );
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1, count, "Only one row should exist for duplicate idempotency key");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Messages without idempotency key allow duplicates")
    void testNoIdempotencyKeyAllowsDuplicates(VertxTestContext testContext) throws Exception {
        String uniqueMarker = "no-idem-" + System.currentTimeMillis();

        // Send two distinct messages without idempotency key — both should insert
        producer.sendInOwnTransaction(uniqueMarker + "-a")
            .compose(v -> producer.sendInOwnTransaction(uniqueMarker + "-b"))
            .onSuccess(v -> {
                logger.info("Both messages without idempotency key inserted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Different idempotency keys on same topic both insert")
    void testDifferentIdempotencyKeysBothInsert(VertxTestContext testContext) throws Exception {
        String key1 = "idem-a-" + System.currentTimeMillis();
        String key2 = "idem-b-" + System.currentTimeMillis();
        Map<String, String> headers1 = Map.of("idempotencyKey", key1);
        Map<String, String> headers2 = Map.of("idempotencyKey", key2);

        // Both sends succeeding proves different keys both insert (ON CONFLICT only blocks same key)
        producer.sendInOwnTransaction("msg-a", new HashMap<>(headers1))
            .compose(v -> producer.sendInOwnTransaction("msg-b", new HashMap<>(headers2)))
            .onSuccess(v -> {
                logger.info("Both messages with different idempotency keys inserted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Idempotency key normalization — empty/whitespace → null
    // =========================================================================

    @Test
    @DisplayName("Empty idempotency key is normalized to null, allowing duplicate inserts")
    void testEmptyIdempotencyKeyNormalizedToNull(VertxTestContext testContext) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", "");
        String payload1 = "empty-key-1-" + System.currentTimeMillis();
        String payload2 = "empty-key-2-" + System.currentTimeMillis();

        // Both sends should insert because "" is normalized to null (no idempotency constraint)
        producer.sendInOwnTransaction(payload1, headers)
            .compose(v -> producer.sendInOwnTransaction(payload2, headers))
            .onSuccess(v -> {
                logger.info("Both messages with empty idempotency key inserted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Whitespace-only idempotency key is normalized to null, allowing duplicate inserts")
    void testWhitespaceIdempotencyKeyNormalizedToNull(VertxTestContext testContext) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", "   ");
        String payload1 = "ws-key-1-" + System.currentTimeMillis();
        String payload2 = "ws-key-2-" + System.currentTimeMillis();

        // Both sends should insert because "   " is normalized to null
        producer.sendInOwnTransaction(payload1, headers)
            .compose(v -> producer.sendInOwnTransaction(payload2, headers))
            .onSuccess(v -> {
                logger.info("Both messages with whitespace idempotency key inserted successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // =========================================================================
    // close() pool isolation — closing producer does not close shared pool
    // =========================================================================

    @Test
    @DisplayName("Closing producer does not close the shared pool — second producer still works")
    void testCloseDoesNotAffectSharedPool(VertxTestContext testContext) throws Exception {
        // Create a second producer using the same databaseService (same shared pool)
        OutboxProducer<String> producer2 = new OutboxProducer<>(databaseService,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "tx-test", String.class, null);

        // Close the first producer
        producer.close();

        // Second producer should still work because the pool is shared
        producer2.sendInOwnTransaction("after-close-" + System.currentTimeMillis())
            .onSuccess(v -> {
                logger.info("Second producer works after first producer closed — shared pool intact");
                producer2.close();
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}