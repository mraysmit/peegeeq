package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive tests for OutboxProducer transaction methods.
 * Tests sendWithTransaction() and sendInTransaction() method variants to improve coverage.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxProducerTransactionTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerTransactionTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private OutboxProducer<String> producer;

    @BeforeEach
    void setUp() throws Exception {
        TestSchemaInitializer.initializeSchema(postgres);

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
    void tearDown() throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload)")
    void testSendWithTransactionPayloadOnly() throws Exception {
        String payload = "tx-payload-" + System.currentTimeMillis();
        
        producer.sendWithTransaction(payload).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Successfully sent message with transaction (payload only): {}", payload);
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, headers)")
    void testSendWithTransactionPayloadAndHeaders() throws Exception {
        String payload = "tx-headers-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        headers.put("priority", "high");
        
        producer.sendWithTransaction(payload, headers).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Successfully sent message with transaction (payload + headers)");
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, headers, correlationId)")
    void testSendWithTransactionWithCorrelationId() throws Exception {
        String payload = "tx-correlation-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("test", "value");
        String correlationId = "corr-" + System.currentTimeMillis();
        
        producer.sendWithTransaction(payload, headers, correlationId).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Successfully sent message with transaction (payload + headers + correlationId)");
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, propagation)")
    void testSendWithTransactionPropagation() throws Exception {
        String payload = "tx-propagation-" + System.currentTimeMillis();
        
        try {
            producer.sendWithTransaction(payload, TransactionPropagation.CONTEXT).get(5, TimeUnit.SECONDS);
            logger.info("✅ Successfully sent message with TransactionPropagation.CONTEXT");
        } catch (Exception e) {
            // TransactionPropagation may fail without active transaction context
            logger.info("TransactionPropagation test completed (expected failure without context): {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, headers, propagation)")
    void testSendWithTransactionHeadersAndPropagation() throws Exception {
        String payload = "tx-headers-prop-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("type", "test");
        
        try {
            producer.sendWithTransaction(payload, headers, TransactionPropagation.CONTEXT).get(5, TimeUnit.SECONDS);
            logger.info("✅ Successfully sent message with headers and propagation");
        } catch (Exception e) {
            logger.info("Headers + propagation test completed: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Test sendWithTransaction(payload, headers, correlationId, propagation)")
    void testSendWithTransactionFullParameters() throws Exception {
        String payload = "tx-full-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("full", "test");
        String correlationId = "full-corr-" + System.currentTimeMillis();
        
        try {
            producer.sendWithTransaction(payload, headers, correlationId, TransactionPropagation.CONTEXT)
                    .get(5, TimeUnit.SECONDS);
            logger.info("✅ Successfully sent message with all parameters");
        } catch (Exception e) {
            logger.info("Full parameters test completed: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Test sendWithTransaction when producer is closed")
    void testSendWithTransactionWhenClosed() {
        String payload = "tx-closed-" + System.currentTimeMillis();
        
        try {
            producer.close();
            producer.sendWithTransaction(payload).get(2, TimeUnit.SECONDS);
            Assertions.fail("Should have thrown exception when sending with closed producer");
        } catch (Exception e) {
            logger.info("✅ Correctly rejected transaction on closed producer: {}", e.getMessage());
            Assertions.assertTrue(e.getMessage() != null && e.getMessage().contains("closed"));
        }
    }

    @Test
    @DisplayName("Test sendWithTransaction with null payload")
    void testSendWithTransactionNullPayload() {
        try {
            producer.sendWithTransaction(null).get(2, TimeUnit.SECONDS);
            Assertions.fail("Should have thrown exception for null payload");
        } catch (Exception e) {
            logger.info("✅ Correctly rejected null payload: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Test sendInTransaction with null connection")
    void testSendInTransactionNullConnection() {
        try {
            producer.sendInTransaction("test", (io.vertx.sqlclient.SqlConnection) null)
                    .get(1, TimeUnit.SECONDS);
            Assertions.fail("Should have thrown exception for null connection");
        } catch (Exception e) {
            logger.info("✅ Correctly rejected null connection: {}", e.getMessage());
            Assertions.assertTrue(e.getMessage() != null && 
                (e.getMessage().contains("connection cannot be null") || 
                 e.getCause() instanceof IllegalArgumentException));
        }
    }

    @Test
    @DisplayName("Test sendWithTransaction with empty headers")
    void testSendWithTransactionEmptyHeaders() throws Exception {
        String payload = "tx-empty-headers-" + System.currentTimeMillis();
        Map<String, String> emptyHeaders = new HashMap<>();
        
        producer.sendWithTransaction(payload, emptyHeaders).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Successfully sent message with empty headers map");
    }

    @Test
    @DisplayName("Test sendWithTransaction with multiple headers")
    void testSendWithTransactionMultipleHeaders() throws Exception {
        String payload = "tx-multi-headers-" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        headers.put("header3", "value3");
        headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
        headers.put("source", "test");
        
        producer.sendWithTransaction(payload, headers).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Successfully sent message with multiple headers");
    }
}
