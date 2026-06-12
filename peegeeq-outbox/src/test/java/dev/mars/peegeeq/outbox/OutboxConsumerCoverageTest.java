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
    void setup(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        testTopic = "cov-test-" + UUID.randomUUID().toString().substring(0, 8);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        manager.start()
                .onSuccess(v -> testContext.verify(() -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void cleanup(VertxTestContext tearDownContext) throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (manager != null) {
            (outboxFactory != null ? outboxFactory.close() : Future.succeededFuture())
                    .eventually(() -> manager.closeReactive())
                    .onSuccess(v -> tearDownContext.completeNow())
                    .onFailure(tearDownContext::failNow);
            assertTrue(tearDownContext.awaitCompletion(10, TimeUnit.SECONDS));
        } else {
            tearDownContext.completeNow();
        }
    }

    @Test
    void testHandlerReturnsNull(VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        // Verify the null-return error path by confirming the message is retried:
        // null return  IllegalStateException  incrementRetryAndReset  status reset to PENDING
        //  message re-delivered on next poll. Two handler invocations prove the retry cycle.
        AtomicInteger invocationCount = new AtomicInteger(0);
        Checkpoint retried = testContext.checkpoint();

        consumer.subscribe(message -> {
            if (invocationCount.incrementAndGet() == 2) {
                retried.flag();
            }
            return null; // Return null to trigger error handling
        }).compose(v -> producer.send("test-null-return"))
          .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "Handler should be invoked twice: once initially, once after null-return retry");
    }

    @Test
    void testHandlerThrowsDirectException(VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        // Verify the thrown-exception error path by confirming the message is retried:
        // thrown exception  Future.failedFuture(e)  incrementRetryAndReset  status reset to PENDING
        //  message re-delivered on next poll. Two handler invocations prove the retry cycle.
        AtomicInteger invocationCount = new AtomicInteger(0);
        Checkpoint retried = testContext.checkpoint();

        consumer.subscribe(message -> {
            if (invocationCount.incrementAndGet() == 2) {
                retried.flag();
            }
            throw new RuntimeException("Direct exception");
        }).compose(v -> producer.send("test-exception"))
          .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "Handler should be invoked twice: once initially, once after exception-based retry");
    }

    @Test
    void testMessageDeletedDuringProcessing(VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        io.vertx.core.Promise<Void> startSignal = io.vertx.core.Promise.promise();
        io.vertx.core.Promise<Void> continueGate = io.vertx.core.Promise.promise();

        consumer.subscribe(message -> {
            startSignal.tryComplete();
            return continueGate.future();
        }).compose(v -> producer.send("test-delete"))
          .onFailure(testContext::failNow);

        startSignal.future()
                .compose(v -> manager.getPool()
                        .preparedQuery("SELECT id FROM outbox ORDER BY id DESC LIMIT 1")
                        .execute()
                        .map(rows -> rows.iterator().next().getLong(0)))
                .compose(id -> manager.getPool()
                        .preparedQuery("DELETE FROM outbox WHERE id = $1")
                        .execute(Tuple.of(id)))
                .onSuccess(v -> {
                    continueGate.tryComplete();
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testConfigurationNull(VertxTestContext testContext) throws Exception {
        // Manually create consumer without configuration
        DatabaseService databaseService = new PgDatabaseService(manager);
        
        consumer = new OutboxConsumer<>(databaseService, objectMapper, testTopic, String.class, null, null);
        
        Checkpoint latch = testContext.checkpoint();
        consumer.subscribe(message -> {
            latch.flag();
            return Future.succeededFuture();
        }).compose(v -> producer.send("test-no-config"))
          .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process message without config");
    }

    @Test
    void testComplexPayloadParsing(VertxTestContext testContext) throws Exception {
        MessageConsumer<ComplexPayload> complexConsumer = outboxFactory.createConsumer(testTopic, ComplexPayload.class);
        MessageProducer<ComplexPayload> complexProducer = outboxFactory.createProducer(testTopic, ComplexPayload.class);
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<ComplexPayload> received = new AtomicReference<>();

        ComplexPayload payload = new ComplexPayload("test", 123);
        complexConsumer.subscribe(message -> {
            received.set(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        }).compose(v -> complexProducer.send(payload))
          .onFailure(testContext::failNow);

        // awaitCompletion blocks until the checkpoint fires (message processed) or timeout.
        // close() is synchronous/void  safe to call in finally once all async work is settled.
        try {
            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process complex payload");
            assertEquals("test", received.get().getName());
            assertEquals(123, received.get().getValue());
        } finally {
            complexProducer.close();
            complexConsumer.close();
        }
    }

    @Test
    void testCompletionPersistenceBlocksNextMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        io.vertx.core.Promise<Void> firstHandled = io.vertx.core.Promise.promise();
        io.vertx.core.Promise<Void> bothHandled = io.vertx.core.Promise.promise();
        AtomicInteger handledCount = new AtomicInteger(0);

        OutboxConsumerConfig singleThreadConfig = OutboxConsumerConfig.builder()
                .consumerThreads(1)
                .build();
        consumer = outboxFactory.createConsumer(testTopic, String.class, singleThreadConfig);

        producer.send("first-blocked")
                .compose(v -> producer.send("second-waits"))
                .compose(v -> manager.getPool()
                        .preparedQuery("SELECT id FROM outbox WHERE topic = $1 ORDER BY id ASC LIMIT 1")
                        .execute(Tuple.of(testTopic)))
                .compose(firstRows -> {
                    var iter = firstRows.iterator();
                    assertTrue(iter.hasNext(), "Expected first outbox message row to exist");
                    long firstMessageId = iter.next().getLong(0);
                    return manager.getPool().getConnection()
                            .compose(lockConn -> {
                                AtomicReference<Transaction> txRef = new AtomicReference<>();
                                return lockConn.begin()
                                        .compose(tx -> {
                                            txRef.set(tx);
                                            return lockConn.preparedQuery("SELECT id FROM outbox WHERE id = $1 FOR UPDATE")
                                                    .execute(Tuple.of(firstMessageId));
                                        })
                                        .compose(v -> consumer.subscribe(message -> {
                                            int count = handledCount.incrementAndGet();
                                            firstHandled.tryComplete();
                                            if (count >= 2) {
                                                bothHandled.tryComplete();
                                            }
                                            return Future.succeededFuture();
                                        }))
                                        .compose(v -> firstHandled.future())
                                        .compose(v -> vertx.timer(800))
                                        .compose(v -> {
                                            assertFalse(bothHandled.future().isComplete(),
                                                    "Second message must wait while first completion update is blocked");
                                            return txRef.get().commit();
                                        })
                                        .eventually(() -> lockConn.close());
                            });
                })
                .compose(v -> bothHandled.future())
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(2, handledCount.get(), "Both messages should be processed eventually");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @Test
    void testHeadersParsing(VertxTestContext testContext) throws Exception {
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");

        consumer.subscribe(message -> {
            receivedHeaders.set(message.getHeaders());
            latch.flag();
            return Future.succeededFuture();
        }).compose(v -> producer.send("test-headers", headers))
          .onFailure(testContext::failNow);

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


