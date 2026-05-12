package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerCoverageTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerCoverageTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test
        testTopic = "cov-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @AfterEach
    void cleanup(VertxTestContext tearDownContext) throws Exception {
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
            manager.closeReactive()
                    .onSuccess(v -> tearDownContext.completeNow())
                    .onFailure(tearDownContext::failNow);
            assertTrue(tearDownContext.awaitCompletion(10, TimeUnit.SECONDS));
        } else {
            tearDownContext.completeNow();
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

        producer.send("test-null-return").onFailure(testContext::failNow);

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

        producer.send("test-exception").onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should attempt to process message");
    }

    @Test
    void testMessageDeletedDuringProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        io.vertx.core.Promise<Void> startSignal = io.vertx.core.Promise.promise();
        io.vertx.core.Promise<Void> continueGate = io.vertx.core.Promise.promise();

        consumer.subscribe(message -> {
            startSignal.tryComplete();
            continueGate.future().await();
            return Future.succeededFuture();
        });

        producer.send("test-delete").onFailure(testContext::failNow);

        startSignal.future().await();

        // Query DB for ID
        long id = manager.getPool()
                .preparedQuery("SELECT id FROM outbox ORDER BY id DESC LIMIT 1")
                .execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await();
        String messageId = String.valueOf(id);

        // Delete message from DB directly
        manager.getPool()
                .preparedQuery("DELETE FROM outbox WHERE id = $1")
                .execute(Tuple.of(id))
                .await();

        continueGate.tryComplete(); // Resume processing

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
            return Future.succeededFuture();
        });

        producer.send("test-no-config").onFailure(testContext::failNow);

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
            return Future.succeededFuture();
        });

        ComplexPayload payload = new ComplexPayload("test", 123);
        
        // We need a producer for ComplexPayload
        MessageProducer<ComplexPayload> complexProducer = outboxFactory.createProducer(testTopic, ComplexPayload.class);
        complexProducer.send(payload).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process complex payload");
        assertEquals("test", received.get().getName());
        assertEquals(123, received.get().getValue());
        
        complexProducer.close();
        complexConsumer.close();
    }

    @Test
    void testCompletionPersistenceBlocksNextMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        producer.send("first-blocked").await();
        producer.send("second-waits").await();

        var firstRows = manager.getPool()
                .preparedQuery("SELECT id FROM outbox WHERE topic = $1 ORDER BY id ASC LIMIT 1")
                .execute(Tuple.of(testTopic))
                .await();
        assertTrue(firstRows.iterator().hasNext(), "Expected first outbox message row to exist");
        long firstMessageId = firstRows.iterator().next().getLong(0);

        io.vertx.core.Promise<Void> firstHandled = io.vertx.core.Promise.promise();
        io.vertx.core.Promise<Void> bothHandled = io.vertx.core.Promise.promise();
        AtomicInteger handledCount = new AtomicInteger(0);

        OutboxConsumerConfig singleThreadConfig = OutboxConsumerConfig.builder()
                .consumerThreads(1)
                .build();
        consumer = outboxFactory.createConsumer(testTopic, String.class, singleThreadConfig);

        SqlConnection lockConn = manager.getPool().getConnection().await();
        try {
            Transaction tx = lockConn.begin().await();
            lockConn.preparedQuery("SELECT id FROM outbox WHERE id = $1 FOR UPDATE")
                    .execute(Tuple.of(firstMessageId))
                    .await();

            consumer.subscribe(message -> {
                int count = handledCount.incrementAndGet();
                firstHandled.tryComplete();
                if (count >= 2) {
                    bothHandled.tryComplete();
                }
                return Future.succeededFuture();
            });

            firstHandled.future().await();
            vertx.timer(800).await();
            assertFalse(bothHandled.future().isComplete(),
                    "Second message must wait while first completion update is blocked");

            tx.commit().await();
        } finally {
            lockConn.close();
        }

        bothHandled.future().await();
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
            return Future.succeededFuture();
        });

        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");
        
        producer.send("test-headers", headers).onFailure(testContext::failNow);

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


