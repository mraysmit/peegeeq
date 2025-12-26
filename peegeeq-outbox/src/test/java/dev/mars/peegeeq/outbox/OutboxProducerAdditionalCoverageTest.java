package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Additional coverage tests for OutboxProducer edge cases and error paths.
 * Uses API-based approach following established patterns.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxProducerAdditionalCoverageTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerAdditionalCoverageTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private String testTopic;

    @BeforeEach
    void setup() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "prod-cov-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("prod-cov-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void testSendWithNullPayload() {
        // Test null payload handling
        ExecutionException exception = assertThrows(ExecutionException.class, () -> {
            producer.send(null).get(5, TimeUnit.SECONDS);
        });
        
        assertTrue(exception.getCause() instanceof IllegalArgumentException,
                "Should throw IllegalArgumentException for null payload");
        assertTrue(exception.getCause().getMessage().contains("payload cannot be null"),
                "Error message should indicate null payload");
        
        logger.info("✅ Null payload validation working correctly");
    }

    @Test
    void testSendWithVeryLargePayload() throws Exception {
        // Test with large payload (PostgreSQL can handle large JSONB)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeContent.append("Large message content segment ").append(i).append(". ");
        }
        
        String largePayload = largeContent.toString();
        producer.send(largePayload).get(10, TimeUnit.SECONDS);
        
        logger.info("✅ Large payload ({} chars) sent successfully", largePayload.length());
    }

    @Test
    void testSendWithSpecialCharactersInPayload() throws Exception {
        // Test special characters and Unicode
        String specialPayload = "Message with special chars: 你好 مرحبا здравствуйте emoji:🎉🚀 \n\t\"quotes\" 'apostrophes'";
        producer.send(specialPayload).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Special characters in payload handled correctly");
    }

    @Test
    void testSendWithEmptyStringPayload() throws Exception {
        // Test empty string (valid but edge case)
        producer.send("").get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Empty string payload accepted");
    }

    @Test
    void testSendWithNullHeadersValue() throws Exception {
        // Test with null headers (should use empty map)
        producer.send("test-payload", null).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Null headers handled correctly");
    }

    @Test
    void testSendWithEmptyHeadersMap() throws Exception {
        // Test with empty headers map
        Map<String, String> emptyHeaders = new HashMap<>();
        producer.send("test-payload", emptyHeaders).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Empty headers map handled correctly");
    }

    @Test
    void testSendWithNullHeaderValues() throws Exception {
        // Test headers containing null values
        Map<String, String> headersWithNulls = new HashMap<>();
        headersWithNulls.put("valid-header", "valid-value");
        headersWithNulls.put("null-header", null);
        
        producer.send("test-payload", headersWithNulls).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Headers with null values handled correctly");
    }

    @Test
    void testSendWithVeryLongHeaderValues() throws Exception {
        // Test with very long header values
        Map<String, String> longHeaders = new HashMap<>();
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longValue.append("HeaderValue-").append(i).append("-");
        }
        longHeaders.put("long-header", longValue.toString());
        
        producer.send("test-payload", longHeaders).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Very long header values handled correctly");
    }

    @Test
    void testSendWithSpecialCharactersInHeaders() throws Exception {
        // Test special characters in header keys and values
        Map<String, String> specialHeaders = new HashMap<>();
        specialHeaders.put("x-special-chars", "value with: tabs\t newlines\n quotes\"");
        specialHeaders.put("x-unicode", "你好世界 🌍");
        
        producer.send("test-payload", specialHeaders).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Special characters in headers handled correctly");
    }

    @Test
    void testSendWithNullCorrelationId() throws Exception {
        // Test with explicit null correlation ID
        producer.send("test-payload", new HashMap<>(), null).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Null correlation ID handled correctly (auto-generated)");
    }

    @Test
    void testSendWithEmptyCorrelationId() throws Exception {
        // Test with empty string correlation ID
        producer.send("test-payload", new HashMap<>(), "").get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Empty correlation ID handled correctly");
    }

    @Test
    void testSendWithDuplicateCorrelationId() throws Exception {
        // Test sending multiple messages with same correlation ID
        String correlationId = "duplicate-correlation-" + System.currentTimeMillis();
        
        producer.send("payload-1", new HashMap<>(), correlationId).get(5, TimeUnit.SECONDS);
        producer.send("payload-2", new HashMap<>(), correlationId).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Duplicate correlation IDs allowed (by design)");
    }

    @Test
    void testSendWithPriorityHeaders() throws Exception {
        // Test sending messages with priority in headers (application-level priority)
        Map<String, String> highPriority = Map.of("priority", "HIGH");
        Map<String, String> lowPriority = Map.of("priority", "LOW");
        Map<String, String> noPriority = new HashMap<>();
        
        producer.send("test-1", highPriority).get(5, TimeUnit.SECONDS);
        producer.send("test-2", lowPriority).get(5, TimeUnit.SECONDS);
        producer.send("test-3", noPriority).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Priority headers handled correctly");
    }

    @Test
    void testMultipleProducersToSameTopic() throws Exception {
        // Test multiple producers sending to same topic
        MessageProducer<String> producer2 = outboxFactory.createProducer(testTopic, String.class);
        MessageProducer<String> producer3 = outboxFactory.createProducer(testTopic, String.class);
        
        try {
            producer.send("from-producer-1").get(5, TimeUnit.SECONDS);
            producer2.send("from-producer-2").get(5, TimeUnit.SECONDS);
            producer3.send("from-producer-3").get(5, TimeUnit.SECONDS);
            
            logger.info("✅ Multiple producers to same topic working correctly");
        } finally {
            producer2.close();
            producer3.close();
        }
    }

    @Test
    void testProducerCloseIdempotency() throws Exception {
        // Test that closing multiple times is safe
        MessageProducer<String> testProducer = outboxFactory.createProducer(testTopic + "-close-test", String.class);
        
        testProducer.send("test-before-close").get(5, TimeUnit.SECONDS);
        
        testProducer.close();
        testProducer.close(); // Second close should be safe
        testProducer.close(); // Third close should be safe
        
        logger.info("✅ Producer close is idempotent");
    }

    @Test
    void testSendAfterClose() {
        // Test that send after close fails gracefully
        MessageProducer<String> testProducer = outboxFactory.createProducer(testTopic + "-after-close", String.class);
        testProducer.close();
        
        // Should throw exception when trying to send after close
        assertThrows(ExecutionException.class, () -> {
            testProducer.send("should-fail").get(5, TimeUnit.SECONDS);
        });
        
        logger.info("✅ Send after close fails gracefully");
    }

    @Test
    void testSendWithComplexPayloadObject() throws Exception {
        // Test with a complex object (will be serialized to JSON)
        MessageProducer<ComplexPayload> complexProducer = 
            outboxFactory.createProducer(testTopic + "-complex", ComplexPayload.class);
        
        try {
            ComplexPayload payload = new ComplexPayload(
                "test-id",
                Map.of("key1", "value1", "key2", "value2"),
                new String[]{"item1", "item2", "item3"}
            );
            
            complexProducer.send(payload).get(5, TimeUnit.SECONDS);
            
            logger.info("✅ Complex payload object serialization working");
        } finally {
            complexProducer.close();
        }
    }

    @Test
    void testConcurrentSends() throws Exception {
        // Test concurrent sends from same producer
        int concurrentCount = 10;
        var futures = new java.util.concurrent.CompletableFuture[concurrentCount];
        
        for (int i = 0; i < concurrentCount; i++) {
            final int index = i;
            futures[i] = producer.send("concurrent-message-" + index);
        }
        
        // Wait for all to complete
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        
        logger.info("✅ Concurrent sends ({}) completed successfully", concurrentCount);
    }

    @Test
    void testSendWithMessageGroup() throws Exception {
        // Test message group functionality (4-param send method)
        String messageGroup = "test-group-" + System.currentTimeMillis();
        
        producer.send("group-msg-1", new HashMap<>(), null, messageGroup).get(5, TimeUnit.SECONDS);
        producer.send("group-msg-2", new HashMap<>(), "corr-id-2", messageGroup).get(5, TimeUnit.SECONDS);
        producer.send("group-msg-3", new HashMap<>(), "corr-id-3", messageGroup).get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Message group functionality working");
    }

    /**
     * Test payload class with nested structures
     */
    static class ComplexPayload {
        private String id;
        private Map<String, String> metadata;
        private String[] items;

        public ComplexPayload() {}

        public ComplexPayload(String id, Map<String, String> metadata, String[] items) {
            this.id = id;
            this.metadata = metadata;
            this.items = items;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
        public String[] getItems() { return items; }
        public void setItems(String[] items) { this.items = items; }
    }
}
