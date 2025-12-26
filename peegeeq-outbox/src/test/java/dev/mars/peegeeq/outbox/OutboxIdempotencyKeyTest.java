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
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for idempotency key functionality in OutboxProducer.
 * Tests Phase 2: Message Deduplication feature.
 */
@Testcontainers
@Tag(TestCategories.INTEGRATION)
class OutboxIdempotencyKeyTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxIdempotencyKeyTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

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
    void tearDown() throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.stop();
        }
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    @Test
    void testSendWithIdempotencyKey_FirstSend_Success() throws Exception {
        logger.info("=== Testing first send with idempotency key succeeds ===");

        Map<String, String> headers = new HashMap<>();
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        headers.put("idempotencyKey", idempotencyKey);

        // First send should succeed
        producer.send("test-payload-1", headers).get(5, TimeUnit.SECONDS);

        // Verify message was inserted
        int count = getMessageCountForIdempotencyKey(idempotencyKey);
        assertEquals(1, count, "Should have exactly 1 message with this idempotency key");

        logger.info("✅ First send with idempotency key succeeded");
    }

    @Test
    void testSendWithIdempotencyKey_DuplicateSend_Ignored() throws Exception {
        logger.info("=== Testing duplicate send with same idempotency key is ignored ===");

        Map<String, String> headers = new HashMap<>();
        String idempotencyKey = "duplicate-key-" + UUID.randomUUID();
        headers.put("idempotencyKey", idempotencyKey);

        // First send
        producer.send("test-payload-1", headers).get(5, TimeUnit.SECONDS);

        // Second send with same idempotency key but different payload
        producer.send("test-payload-2", headers).get(5, TimeUnit.SECONDS);

        // Third send with same idempotency key
        producer.send("test-payload-3", headers).get(5, TimeUnit.SECONDS);

        // Verify only one message was inserted
        int count = getMessageCountForIdempotencyKey(idempotencyKey);
        assertEquals(1, count, "Should have exactly 1 message despite 3 send attempts");

        // Verify the payload is from the first send
        // Note: String payloads are wrapped in {"value": "..."} to ensure JSONB object storage
        String payload = getPayloadForIdempotencyKey(idempotencyKey);
        assertEquals("{\"value\": \"test-payload-1\"}", payload, "Should have payload from first send");

        logger.info("✅ Duplicate sends were correctly ignored");
    }

    @Test
    void testSendWithoutIdempotencyKey_AllowsDuplicates() throws Exception {
        logger.info("=== Testing sends without idempotency key allows duplicates ===");

        Map<String, String> headers = new HashMap<>();
        headers.put("custom-header", "value");

        // Send same payload multiple times without idempotency key
        producer.send("duplicate-payload", headers).get(5, TimeUnit.SECONDS);
        producer.send("duplicate-payload", headers).get(5, TimeUnit.SECONDS);
        producer.send("duplicate-payload", headers).get(5, TimeUnit.SECONDS);

        // All messages should be inserted
        int count = getMessageCountForTopic(testTopic);
        assertTrue(count >= 3, "Should have at least 3 messages when no idempotency key is used");

        logger.info("✅ Messages without idempotency key allow duplicates as expected");
    }

    @Test
    void testSendWithDifferentIdempotencyKeys_AllSucceed() throws Exception {
        logger.info("=== Testing sends with different idempotency keys all succeed ===");

        for (int i = 0; i < 5; i++) {
            Map<String, String> headers = new HashMap<>();
            headers.put("idempotencyKey", "unique-key-" + i);
            producer.send("payload-" + i, headers).get(5, TimeUnit.SECONDS);
        }

        // All 5 messages should be inserted
        int count = getMessageCountForTopic(testTopic);
        assertEquals(5, count, "Should have 5 messages with different idempotency keys");

        logger.info("✅ All messages with different idempotency keys were inserted");
    }

    @Test
    void testSendWithIdempotencyKey_ConcurrentDuplicates_OnlyOneInserted() throws Exception {
        logger.info("=== Testing concurrent duplicate sends with same idempotency key ===");

        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Launch multiple threads trying to send with same idempotency key
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    Map<String, String> headers = new HashMap<>();
                    headers.put("idempotencyKey", idempotencyKey);
                    producer.send("payload-" + index, headers).get(5, TimeUnit.SECONDS);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    logger.debug("Thread {} failed (expected for duplicates): {}", index, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Start all threads at once
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");

        // All should succeed (duplicates are silently ignored)
        assertEquals(threadCount, successCount.get(), "All sends should succeed (duplicates ignored)");
        assertEquals(0, failureCount.get(), "No failures expected");

        // But only one message should be in the database
        Thread.sleep(500); // Give time for all operations to complete
        int count = getMessageCountForIdempotencyKey(idempotencyKey);
        assertEquals(1, count, "Should have exactly 1 message despite concurrent sends");

        logger.info("✅ Concurrent duplicate sends handled correctly");
    }

    @Test
    void testSendWithIdempotencyKey_ConsumerReceivesOnlyOnce() throws Exception {
        logger.info("=== Testing consumer receives message only once with idempotency key ===");

        String idempotencyKey = "consumer-test-key-" + UUID.randomUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", idempotencyKey);

        // Send same message 3 times
        producer.send("test-payload", headers).get(5, TimeUnit.SECONDS);
        producer.send("test-payload", headers).get(5, TimeUnit.SECONDS);
        producer.send("test-payload", headers).get(5, TimeUnit.SECONDS);

        // Create consumer
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for message
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive message");

        // Give extra time to ensure no duplicates
        Thread.sleep(2000);

        // Should receive only 1 message
        assertEquals(1, receivedCount.get(), "Consumer should receive message only once");

        consumer.close();
        logger.info("✅ Consumer received message only once despite duplicate sends");
    }

    @Test
    void testSendWithNullIdempotencyKey_AllowsDuplicates() throws Exception {
        logger.info("=== Testing sends with null idempotency key allows duplicates ===");

        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", null);

        // Send multiple times with null idempotency key
        producer.send("payload-1", headers).get(5, TimeUnit.SECONDS);
        producer.send("payload-2", headers).get(5, TimeUnit.SECONDS);

        int count = getMessageCountForTopic(testTopic);
        assertEquals(2, count, "Should have 2 messages when idempotency key is null");

        logger.info("✅ Null idempotency key allows duplicates");
    }

    @Test
    void testSendWithEmptyIdempotencyKey_AllowsDuplicates() throws Exception {
        logger.info("=== Testing sends with empty idempotency key allows duplicates ===");

        Map<String, String> headers = new HashMap<>();
        headers.put("idempotencyKey", "");

        // Send multiple times with empty idempotency key
        producer.send("payload-1", headers).get(5, TimeUnit.SECONDS);
        producer.send("payload-2", headers).get(5, TimeUnit.SECONDS);

        int count = getMessageCountForTopic(testTopic);
        assertEquals(2, count, "Should have 2 messages when idempotency key is empty");

        logger.info("✅ Empty idempotency key allows duplicates");
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
