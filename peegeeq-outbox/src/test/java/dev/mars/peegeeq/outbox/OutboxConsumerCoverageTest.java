package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerCoverageTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = createPostgresContainer();

    private static PostgreSQLContainer<?> createPostgresContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

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
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    void testHandlerReturnsNull(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        Checkpoint latch = testContext.checkpoint();

        consumer.subscribe(message -> {
            latch.flag();
            return null; // Return null to trigger error handling
        });

        producer.send("test-null-return").get(5, TimeUnit.SECONDS);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should attempt to process message");
    }

    @Test
    void testHandlerThrowsDirectException(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        Checkpoint latch = testContext.checkpoint();

        consumer.subscribe(message -> {
            latch.flag();
            throw new RuntimeException("Direct exception");
        });

        producer.send("test-exception").get(5, TimeUnit.SECONDS);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should attempt to process message");
    }

    @Test
    void testMessageDeletedDuringProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        CompletableFuture<Void> startSignal = new CompletableFuture<>();
        CompletableFuture<Void> continueGate = new CompletableFuture<>();

        consumer.subscribe(message -> {
            startSignal.complete(null);
            try {
                continueGate.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        producer.send("test-delete").get(5, TimeUnit.SECONDS);

        startSignal.get(5, TimeUnit.SECONDS);

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

        continueGate.complete(null); // Resume processing

        testContext.completeNow();
    }

    @Test
    void testConfigurationNull(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        // Manually create consumer without configuration
        DatabaseService databaseService = new PgDatabaseService(manager);
        
        consumer = new OutboxConsumer<>(databaseService, objectMapper, testTopic, String.class, null, null);
        
        Checkpoint latch = testContext.checkpoint();
        consumer.subscribe(message -> {
            latch.flag();
            return CompletableFuture.completedFuture(null);
        });

        producer.send("test-no-config").get(5, TimeUnit.SECONDS);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process message without config");
    }

    @Test
    void testComplexPayloadParsing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        MessageConsumer<ComplexPayload> complexConsumer = outboxFactory.createConsumer(testTopic, ComplexPayload.class);
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<ComplexPayload> received = new AtomicReference<>();

        complexConsumer.subscribe(message -> {
            received.set(message.getPayload());
            latch.flag();
            return CompletableFuture.completedFuture(null);
        });

        ComplexPayload payload = new ComplexPayload("test", 123);
        
        // We need a producer for ComplexPayload
        MessageProducer<ComplexPayload> complexProducer = outboxFactory.createProducer(testTopic, ComplexPayload.class);
        complexProducer.send(payload).get(5, TimeUnit.SECONDS);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process complex payload");
        assertEquals("test", received.get().getName());
        assertEquals(123, received.get().getValue());
        
        complexProducer.close();
        complexConsumer.close();
    }

    @Test
    void testCompletionPersistenceBlocksNextMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        producer.send("first-blocked").get(5, TimeUnit.SECONDS);
        producer.send("second-waits").get(5, TimeUnit.SECONDS);

        long firstMessageId;
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
                PreparedStatement stmt = conn
                        .prepareStatement("SELECT id FROM outbox WHERE topic = ? ORDER BY id ASC LIMIT 1");) {
            stmt.setString(1, testTopic);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "Expected first outbox message row to exist");
                firstMessageId = rs.getLong(1);
            }
        }

        CompletableFuture<Void> firstHandled = new CompletableFuture<>();
        CompletableFuture<Void> bothHandled = new CompletableFuture<>();
        AtomicInteger handledCount = new AtomicInteger(0);

        OutboxConsumerConfig singleThreadConfig = OutboxConsumerConfig.builder()
                .consumerThreads(1)
                .build();
        consumer = outboxFactory.createConsumer(testTopic, String.class, singleThreadConfig);

        try (Connection lockConn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            lockConn.setAutoCommit(false);

            try (PreparedStatement lockStmt = lockConn.prepareStatement("SELECT id FROM outbox WHERE id = ? FOR UPDATE")) {
                lockStmt.setLong(1, firstMessageId);
                try (ResultSet ignored = lockStmt.executeQuery()) {
                    consumer.subscribe(message -> {
                        int count = handledCount.incrementAndGet();
                        firstHandled.complete(null);
                        if (count >= 2) {
                            bothHandled.complete(null);
                        }
                        return CompletableFuture.completedFuture(null);
                    });

                    firstHandled.get(5, TimeUnit.SECONDS);
                    try {
                        bothHandled.get(800, TimeUnit.MILLISECONDS);
                        fail("Second message must wait while first completion update is blocked");
                    } catch (TimeoutException e) {
                        // Expected - second message should not be processed yet
                    }
                }
            }

            lockConn.commit();
        }

        bothHandled.get(5, TimeUnit.SECONDS);
        assertEquals(2, handledCount.get(), "Both messages should be processed eventually");
        testContext.completeNow();
    }
    
    @Test
    void testHeadersParsing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedHeaders.set(message.getHeaders());
            latch.flag();
            return CompletableFuture.completedFuture(null);
        });

        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");
        
        producer.send("test-headers", headers).get(5, TimeUnit.SECONDS);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process message with headers");
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


