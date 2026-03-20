package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for idempotency key functionality in OutboxProducer.
 * Tests Phase 2: Message Deduplication feature.
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
class OutboxIdempotencyKeyTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxIdempotencyKeyTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15-alpine");
        container.withDatabaseName("testdb");
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "idempotency-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("idempotency-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);

        producer = outboxFactory.createProducer(testTopic, String.class);

        logger.info("=== Test setup complete for topic: {} ===", testTopic);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        Future<Void> closeFuture = (manager != null)
            ? manager.closeReactive()
            : Future.succeededFuture();

        closeFuture.onComplete(ar -> {
            System.clearProperty("peegeeq.database.host");
            System.clearProperty("peegeeq.database.port");
            System.clearProperty("peegeeq.database.name");
            System.clearProperty("peegeeq.database.username");
            System.clearProperty("peegeeq.database.password");
            testContext.completeNow();
        });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithIdempotencyKey_FirstSend_Success(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing first send with idempotency key succeeds ===");

        Map<String, String> headers = new HashMap<>();
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        headers.put("idempotencyKey", idempotencyKey);

        // First send should succeed
        producer.send("test-payload-1", headers)
            .onSuccess(v -> {
                testContext.verify(() -> {
                    // Verify message was inserted
                    int count = getMessageCountForIdempotencyKey(idempotencyKey);
                    assertEquals(1, count, "Should have exactly 1 message with this idempotency key");
                    logger.info("First send with idempotency key succeeded");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithIdempotencyKey_DuplicateSend_Ignored(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing duplicate send with same idempotency key is ignored ===");

        Map<String, String> headers = new HashMap<>();
        String idempotencyKey = "duplicate-key-" + UUID.randomUUID();
        headers.put("idempotencyKey", idempotencyKey);

        // Send 3 times with same idempotency key but different payloads
        producer.send("test-payload-1", headers)
            .compose(v -> producer.send("test-payload-2", headers))
            .compose(v -> producer.send("test-payload-3", headers))
            .onSuccess(v -> {
                testContext.verify(() -> {
                    // Verify only one message was inserted
                    int count = getMessageCountForIdempotencyKey(idempotencyKey);
                    assertEquals(1, count, "Should have exactly 1 message despite 3 send attempts");

                    // Verify the payload is from the first send
                    String payload = getPayloadForIdempotencyKey(idempotencyKey);
                    assertEquals("{\"value\": \"test-payload-1\"}", payload, "Should have payload from first send");
                    logger.info("Duplicate sends were correctly ignored");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithoutIdempotencyKey_AllowsDuplicates(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing sends without idempotency key allows duplicates ===");

        Map<String, String> headers = new HashMap<>();
        headers.put("custom-header", "value");

        // Send same payload multiple times without idempotency key
        producer.send("duplicate-payload", headers)
            .compose(v -> producer.send("duplicate-payload", headers))
            .compose(v -> producer.send("duplicate-payload", headers))
            .onSuccess(v -> {
                testContext.verify(() -> {
                    // All messages should be inserted
                    int count = getMessageCountForTopic(testTopic);
                    assertTrue(count >= 3, "Should have at least 3 messages when no idempotency key is used");
                    logger.info("Messages without idempotency key allow duplicates as expected");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithDifferentIdempotencyKeys_AllSucceed(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing sends with different idempotency keys all succeed ===");

        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            final int index = i;
            chain = chain.compose(v -> {
                Map<String, String> headers = new HashMap<>();
                headers.put("idempotencyKey", "unique-key-" + index);
                return producer.send("payload-" + index, headers);
            });
        }

        chain.onSuccess(v -> {
                testContext.verify(() -> {
                    // All 5 messages should be inserted
                    int count = getMessageCountForTopic(testTopic);
                    assertEquals(5, count, "Should have 5 messages with different idempotency keys");
                    logger.info("All messages with different idempotency keys were inserted");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithIdempotencyKey_ConcurrentDuplicates_OnlyOneInserted(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing concurrent duplicate sends with same idempotency key ===");

        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();
        int sendCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Fire all sends concurrently
        List<Future<?>> sendFutures = new ArrayList<>();
        for (int i = 0; i < sendCount; i++) {
            final int index = i;
            Map<String, String> headers = new HashMap<>();
            headers.put("idempotencyKey", idempotencyKey);
            sendFutures.add(producer.send("payload-" + index, headers)
                .onSuccess(v -> successCount.incrementAndGet())
                .onFailure(e -> {
                    failureCount.incrementAndGet();
                    logger.debug("Send {} failed (expected for duplicates): {}", index, e.getMessage());
                }));
        }

        // Wait for all concurrent sends to complete, then verify
        Future.all(sendFutures)
            .compose(cf -> vertx.timer(500))
            .onSuccess(v -> {
                testContext.verify(() -> {
                    assertEquals(sendCount, successCount.get(), "All sends should succeed (duplicates ignored)");
                    assertEquals(0, failureCount.get(), "No failures expected");

                    int count = getMessageCountForIdempotencyKey(idempotencyKey);
                    assertEquals(1, count, "Should have exactly 1 message despite concurrent sends");
                    logger.info("Concurrent duplicate sends handled correctly");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithIdempotencyKey_ConsumerReceivesOnlyOnce(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing consumer receives message only once with idempotency key ===");

        String idempotencyKey = "consumer-test-key-" + UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", idempotencyKey);

        // Send same message 3 times, then subscribe and verify
        producer.send("test-payload", headers)
            .compose(v -> producer.send("test-payload", headers))
            .compose(v -> producer.send("test-payload", headers))
            .compose(v -> {
                // Create consumer after sends complete
                MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
                AtomicInteger receivedCount = new AtomicInteger(0);
                Checkpoint messageReceived = testContext.checkpoint();

                consumer.subscribe(message -> {
                    receivedCount.incrementAndGet();
                    messageReceived.flag();
                    return Future.succeededFuture();
                });

                // Wait for message processing, then verify no duplicates
                return vertx.timer(3000)
                    .onSuccess(id -> {
                        testContext.verify(() -> {
                            assertEquals(1, receivedCount.get(), "Consumer should receive message only once");
                        });
                        consumer.close();
                        logger.info("Consumer received message only once despite duplicate sends");
                    });
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithNullIdempotencyKey_AllowsDuplicates(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing sends with null idempotency key allows duplicates ===");

        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", null);

        // Send multiple times with null idempotency key
        producer.send("payload-1", headers)
            .compose(v -> producer.send("payload-2", headers))
            .onSuccess(v -> {
                testContext.verify(() -> {
                    int count = getMessageCountForTopic(testTopic);
                    assertEquals(2, count, "Should have 2 messages when idempotency key is null");
                    logger.info("Null idempotency key allows duplicates");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSendWithEmptyIdempotencyKey_AllowsDuplicates(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing sends with empty idempotency key allows duplicates ===");

        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", "");

        // Send multiple times with empty idempotency key
        producer.send("payload-1", headers)
            .compose(v -> producer.send("payload-2", headers))
            .onSuccess(v -> {
                testContext.verify(() -> {
                    int count = getMessageCountForTopic(testTopic);
                    assertEquals(2, count, "Should have 2 messages when idempotency key is empty");
                    logger.info("Empty idempotency key allows duplicates");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // Helper methods

    private int getMessageCountForIdempotencyKey(String idempotencyKey) throws Exception {
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        try (Connection conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            String sql = String.format(
                    "SELECT COUNT(*) FROM outbox WHERE topic = '%s' AND idempotency_key = '%s'",
                    testTopic, idempotencyKey);

            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    private String getPayloadForIdempotencyKey(String idempotencyKey) throws Exception {
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        try (Connection conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            String sql = String.format(
                    "SELECT payload::text FROM outbox WHERE topic = '%s' AND idempotency_key = '%s'",
                    testTopic, idempotencyKey);

            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        }
    }

    private int getMessageCountForTopic(String topic) throws Exception {
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        try (Connection conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            String sql = String.format("SELECT COUNT(*) FROM outbox WHERE topic = '%s'", topic);

            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }
}


