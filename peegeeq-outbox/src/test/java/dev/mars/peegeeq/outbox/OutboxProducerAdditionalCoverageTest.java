package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Additional coverage tests for OutboxProducer edge cases and error paths.
 * Uses API-based approach following established patterns.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxProducerAdditionalCoverageTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerAdditionalCoverageTest.class);

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

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
    void cleanup(VertxTestContext testContext) throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithNullPayload(VertxTestContext testContext) {
        // Test null payload handling
        producer.send(null)
            .onSuccess(v -> testContext.failNow("Should have failed for null payload"))
            .onFailure(err -> testContext.verify(() -> {
                assertTrue(err instanceof IllegalArgumentException,
                        "Should throw IllegalArgumentException for null payload");
                assertTrue(err.getMessage().contains("payload cannot be null"),
                        "Error message should indicate null payload");
                logger.info("Null payload validation working correctly");
                testContext.completeNow();
            }));
        assertDoesNotThrow(() -> assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS)));
    }

    @Test
    void testSendWithVeryLargePayload(VertxTestContext testContext) throws Exception {
        // Test with large payload (PostgreSQL can handle large JSONB)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeContent.append("Large message content segment ").append(i).append(". ");
        }
        
        String largePayload = largeContent.toString();
        producer.send(largePayload)
            .onSuccess(v -> {
                logger.info("Large payload ({} chars) sent successfully", largePayload.length());
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithSpecialCharactersInPayload(VertxTestContext testContext) throws Exception {
        // Test special characters and Unicode
        String specialPayload = "Message with special chars: 你好 مرحبا здравствуйте emoji:🎉🚀 \n\t\"quotes\" 'apostrophes'";
        producer.send(specialPayload)
            .onSuccess(v -> {
                logger.info("Special characters in payload handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithEmptyStringPayload(VertxTestContext testContext) throws Exception {
        // Test empty string (valid but edge case)
        producer.send("")
            .onSuccess(v -> {
                logger.info("Empty string payload accepted");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithNullHeadersValue(VertxTestContext testContext) throws Exception {
        // Test with null headers (should use empty map)
        producer.send("test-payload", null)
            .onSuccess(v -> {
                logger.info("Null headers handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithEmptyHeadersMap(VertxTestContext testContext) throws Exception {
        // Test with empty headers map
        Map<String, String> emptyHeaders = new HashMap<>();
        producer.send("test-payload", emptyHeaders)
            .onSuccess(v -> {
                logger.info("Empty headers map handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithNullHeaderValues(VertxTestContext testContext) throws Exception {
        // Test headers containing null values
        Map<String, String> headersWithNulls = new HashMap<>();
        headersWithNulls.put("valid-header", "valid-value");
        headersWithNulls.put("null-header", null);
        
        producer.send("test-payload", headersWithNulls)
            .onSuccess(v -> {
                logger.info("Headers with null values handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithVeryLongHeaderValues(VertxTestContext testContext) throws Exception {
        // Test with very long header values
        Map<String, String> longHeaders = new HashMap<>();
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longValue.append("HeaderValue-").append(i).append("-");
        }
        longHeaders.put("long-header", longValue.toString());
        
        producer.send("test-payload", longHeaders)
            .onSuccess(v -> {
                logger.info("Very long header values handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithSpecialCharactersInHeaders(VertxTestContext testContext) throws Exception {
        // Test special characters in header keys and values
        Map<String, String> specialHeaders = new HashMap<>();
        specialHeaders.put("x-special-chars", "value with: tabs\t newlines\n quotes\"");
        specialHeaders.put("x-unicode", "你好世界 🌍");
        
        producer.send("test-payload", specialHeaders)
            .onSuccess(v -> {
                logger.info("Special characters in headers handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithNullCorrelationId(VertxTestContext testContext) throws Exception {
        // Test with explicit null correlation ID
        producer.send("test-payload", new HashMap<>(), null)
            .onSuccess(v -> {
                logger.info("Null correlation ID handled correctly (auto-generated)");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithEmptyCorrelationId(VertxTestContext testContext) throws Exception {
        // Test with empty string correlation ID
        producer.send("test-payload", new HashMap<>(), "")
            .onSuccess(v -> {
                logger.info("Empty correlation ID handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithDuplicateCorrelationId(VertxTestContext testContext) throws Exception {
        // Test sending multiple messages with same correlation ID
        String correlationId = "duplicate-correlation-" + System.currentTimeMillis();
        
        producer.send("payload-1", new HashMap<>(), correlationId)
            .compose(v -> producer.send("payload-2", new HashMap<>(), correlationId))
            .onSuccess(v -> {
                logger.info("Duplicate correlation IDs allowed (by design)");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithPriorityHeaders(VertxTestContext testContext) throws Exception {
        // Test sending messages with priority in headers (application-level priority)
        Map<String, String> highPriority = Map.of("priority", "HIGH");
        Map<String, String> lowPriority = Map.of("priority", "LOW");
        Map<String, String> noPriority = new HashMap<>();
        
        producer.send("test-1", highPriority)
            .compose(v -> producer.send("test-2", lowPriority))
            .compose(v -> producer.send("test-3", noPriority))
            .onSuccess(v -> {
                logger.info("Priority headers handled correctly");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testMultipleProducersToSameTopic(VertxTestContext testContext) throws Exception {
        // Test multiple producers sending to same topic
        MessageProducer<String> producer2 = outboxFactory.createProducer(testTopic, String.class);
        MessageProducer<String> producer3 = outboxFactory.createProducer(testTopic, String.class);
        
        producer.send("from-producer-1")
            .compose(v -> producer2.send("from-producer-2"))
            .compose(v -> producer3.send("from-producer-3"))
            .onSuccess(v -> {
                logger.info("Multiple producers to same topic working correctly");
                producer2.close();
                producer3.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                producer2.close();
                producer3.close();
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testProducerCloseIdempotency(VertxTestContext testContext) throws Exception {
        // Test that closing multiple times is safe
        MessageProducer<String> testProducer = outboxFactory.createProducer(testTopic + "-close-test", String.class);
        
        testProducer.send("test-before-close")
            .onSuccess(v -> {
                testProducer.close();
                testProducer.close(); // Second close should be safe
                testProducer.close(); // Third close should be safe
                logger.info("Producer close is idempotent");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testSendAfterClose(VertxTestContext testContext) {
        // Test that send after close fails gracefully
        MessageProducer<String> testProducer = outboxFactory.createProducer(testTopic + "-after-close", String.class);
        testProducer.close();
        
        // Should return a failed future when trying to send after close
        testProducer.send("should-fail")
            .onSuccess(v -> testContext.failNow("Expected send after close to fail"))
            .onFailure(err -> {
                logger.info("Send after close fails gracefully");
                testContext.completeNow();
            });
        assertDoesNotThrow(() -> assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS)));
    }

    @Test
    void testSendWithComplexPayloadObject(VertxTestContext testContext) throws Exception {
        // Test with a complex object (will be serialized to JSON)
        MessageProducer<ComplexPayload> complexProducer = 
            outboxFactory.createProducer(testTopic + "-complex", ComplexPayload.class);
        
        ComplexPayload payload = new ComplexPayload(
            "test-id",
            Map.of("key1", "value1", "key2", "value2"),
            new String[]{"item1", "item2", "item3"}
        );
        
        complexProducer.send(payload)
            .onSuccess(v -> {
                logger.info("Complex payload object serialization working");
                complexProducer.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                complexProducer.close();
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testConcurrentSends(VertxTestContext testContext) throws Exception {
        // Test concurrent sends from same producer
        int concurrentCount = 10;
        var futureList = new java.util.ArrayList<Future<Void>>();
        
        for (int i = 0; i < concurrentCount; i++) {
            futureList.add(producer.send("concurrent-message-" + i));
        }
        
        // Wait for all to complete
        Future.all(futureList)
            .onSuccess(v -> {
                logger.info("Concurrent sends ({}) completed successfully", concurrentCount);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithMessageGroup(VertxTestContext testContext) throws Exception {
        // Test message group functionality (4-param send method)
        String messageGroup = "test-group-" + System.currentTimeMillis();
        
        producer.send("group-msg-1", new HashMap<>(), null, messageGroup)
            .compose(v -> producer.send("group-msg-2", new HashMap<>(), "corr-id-2", messageGroup))
            .compose(v -> producer.send("group-msg-3", new HashMap<>(), "corr-id-3", messageGroup))
            .onSuccess(v -> {
                logger.info("Message group functionality working");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
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


