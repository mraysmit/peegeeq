package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxConsumerCoverageTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() throws Exception {
        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test
        testTopic = "cov-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("cov-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @AfterEach
    void cleanup() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
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
    void testHandlerReturnsNull() throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            latch.countDown();
            return null; // Return null to trigger error handling
        });

        producer.send("test-null-return").get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should attempt to process message");
        
        // Wait a bit for error handling to complete
        Thread.sleep(500);
        
        // Verify message is retried (we can check by subscribing with a valid handler and seeing if we get it again)
        // Or check DB status. For now, just ensuring no crash and coverage of that path.
    }

    @Test
    void testHandlerThrowsDirectException() throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            latch.countDown();
            throw new RuntimeException("Direct exception");
        });

        producer.send("test-exception").get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should attempt to process message");
        Thread.sleep(500);
    }

    @Test
    void testMessageDeletedDuringProcessing() throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch continueLatch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            startLatch.countDown();
            try {
                continueLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        producer.send("test-delete").get(5, TimeUnit.SECONDS);

        assertTrue(startLatch.await(5, TimeUnit.SECONDS), "Should start processing");

        // Query DB for ID
        long id;
        try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT id FROM outbox ORDER BY id DESC LIMIT 1");
            rs.next();
            id = rs.getLong(1);
        }
        String messageId = String.valueOf(id);

        // Delete message from DB directly
        try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.prepareStatement("DELETE FROM outbox WHERE id = ?")) {
            stmt.setLong(1, Long.parseLong(messageId));
            stmt.executeUpdate();
        }

        continueLatch.countDown(); // Resume processing

        // Wait for completion attempt (which should fail gracefully)
        Thread.sleep(500);
    }

    @Test
    void testConfigurationNull() throws Exception {
        // Manually create consumer without configuration
        DatabaseService databaseService = new PgDatabaseService(manager);
        
        consumer = new OutboxConsumer<>(databaseService, objectMapper, testTopic, String.class, null, null);
        
        CountDownLatch latch = new CountDownLatch(1);
        consumer.subscribe(message -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        producer.send("test-no-config").get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should process message without config");
    }

    @Test
    void testComplexPayloadParsing() throws Exception {
        MessageConsumer<ComplexPayload> complexConsumer = outboxFactory.createConsumer(testTopic, ComplexPayload.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ComplexPayload> received = new AtomicReference<>();

        complexConsumer.subscribe(message -> {
            received.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        ComplexPayload payload = new ComplexPayload("test", 123);
        
        // We need a producer for ComplexPayload
        MessageProducer<ComplexPayload> complexProducer = outboxFactory.createProducer(testTopic, ComplexPayload.class);
        complexProducer.send(payload).get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should process complex payload");
        assertEquals("test", received.get().getName());
        assertEquals(123, received.get().getValue());
        
        complexProducer.close();
        complexConsumer.close();
    }
    
    @Test
    void testHeadersParsing() throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedHeaders.set(message.getHeaders());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");
        
        producer.send("test-headers", headers).get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should process message with headers");
        assertEquals("value1", receivedHeaders.get().get("key1"));
        assertEquals("value2", receivedHeaders.get().get("key2"));
    }

    public static class ComplexPayload {
        private String name;
        private int value;

        public ComplexPayload() {}

        public ComplexPayload(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
