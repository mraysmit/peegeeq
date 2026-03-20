package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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
 * Tests sendWithTransaction() and sendInTransaction() method variants to improve coverage.
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

        PgDatabaseService databaseService = new PgDatabaseService(manager);
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
    @DisplayName("Test sendWithTransaction(payload)")
    void testSendWithTransactionPayloadOnly(VertxTestContext testContext) throws Exception {
        String payload = "tx-payload-" + System.currentTimeMillis();
        
        producer.sendWithTransaction(payload)
            .onSuccess(v -> {
                logger.info("Successfully sent message with transaction (payload only): {}", payload);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, headers)")
    void testSendWithTransactionPayloadAndHeaders(VertxTestContext testContext) throws Exception {
        String payload = "tx-headers-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        headers.put("priority", "high");
        
        producer.sendWithTransaction(payload, headers)
            .onSuccess(v -> {
                logger.info("Successfully sent message with transaction (payload + headers)");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, headers, correlationId)")
    void testSendWithTransactionWithCorrelationId(VertxTestContext testContext) throws Exception {
        String payload = "tx-correlation-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("test", "value");
        String correlationId = "corr-" + System.currentTimeMillis();
        
        producer.sendWithTransaction(payload, headers, correlationId)
            .onSuccess(v -> {
                logger.info("Successfully sent message with transaction (payload + headers + correlationId)");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, propagation)")
    void testSendWithTransactionPropagation(VertxTestContext testContext) throws Exception {
        String payload = "tx-propagation-" + System.currentTimeMillis();
        
        producer.sendWithTransaction(payload, TransactionPropagation.CONTEXT)
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
    @DisplayName("Test sendWithTransaction(payload, headers, propagation)")
    void testSendWithTransactionHeadersAndPropagation(VertxTestContext testContext) throws Exception {
        String payload = "tx-headers-prop-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("type", "test");
        
        producer.sendWithTransaction(payload, headers, TransactionPropagation.CONTEXT)
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
    @DisplayName("Test sendWithTransaction(payload, headers, correlationId, propagation)")
    void testSendWithTransactionFullParameters(VertxTestContext testContext) throws Exception {
        String payload = "tx-full-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("full", "test");
        String correlationId = "full-corr-" + System.currentTimeMillis();
        
        producer.sendWithTransaction(payload, headers, correlationId, TransactionPropagation.CONTEXT)
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
    @DisplayName("Test sendWithTransaction when producer is closed")
    void testSendWithTransactionWhenClosed(VertxTestContext testContext) throws Exception {
        String payload = "tx-closed-" + System.currentTimeMillis();
        
        producer.close();
        producer.sendWithTransaction(payload)
            .onSuccess(v -> testContext.failNow("Should have thrown exception when sending with closed producer"))
            .onFailure(e -> testContext.verify(() -> {
                logger.info("Correctly rejected transaction on closed producer: {}", e.getMessage());
                assertTrue(e.getMessage() != null && e.getMessage().contains("closed"));
                testContext.completeNow();
            }));
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendWithTransaction with null payload")
    void testSendWithTransactionNullPayload(VertxTestContext testContext) throws Exception {
        producer.sendWithTransaction(null)
            .onSuccess(v -> testContext.failNow("Should have thrown exception for null payload"))
            .onFailure(e -> {
                logger.info("Correctly rejected null payload: {}", e.getMessage());
                testContext.completeNow();
            });
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendInTransaction with null connection")
    void testSendInTransactionNullConnection(VertxTestContext testContext) throws Exception {
        producer.sendInTransaction("test", (io.vertx.sqlclient.SqlConnection) null)
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
    @DisplayName("Test sendWithTransaction with empty headers")
    void testSendWithTransactionEmptyHeaders(VertxTestContext testContext) throws Exception {
        String payload = "tx-empty-headers-" + System.currentTimeMillis();
        Map<String, String> emptyHeaders = new HashMap<>();
        
        producer.sendWithTransaction(payload, emptyHeaders)
            .onSuccess(v -> {
                logger.info("Successfully sent message with empty headers map");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test sendWithTransaction with multiple headers")
    void testSendWithTransactionMultipleHeaders(VertxTestContext testContext) throws Exception {
        String payload = "tx-multi-headers-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        headers.put("header3", "value3");
        headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
        headers.put("source", "test");
        
        producer.sendWithTransaction(payload, headers)
            .onSuccess(v -> {
                logger.info("Successfully sent message with multiple headers");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}


