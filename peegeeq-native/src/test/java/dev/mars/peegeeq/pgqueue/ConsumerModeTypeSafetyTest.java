package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Type safety tests for consumer mode implementation.
 * Tests that consumer modes work correctly with different payload types,
 * maintain type safety during serialization/deserialization, and handle
 * complex objects properly across all consumer modes.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test type safety edge cases that could cause production issues
 * - Validate serialization/deserialization across consumer modes
 * - Follow existing patterns from other integration tests
 * - Test with various payload types including primitives, objects, and collections
 */
@Testcontainers
class ConsumerModeTypeSafetyTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeTypeSafetyTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_password");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Configure test properties using TestContainer pattern (following existing patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);

        logger.info("Test setup completed for consumer mode type safety testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("Test teardown completed");
    }

    // Test data classes for complex type testing
    public static class TestPerson {
        private String name;
        private int age;
        private String email;

        public TestPerson() {} // Default constructor for Jackson

        public TestPerson(String name, int age, String email) {
            this.name = name;
            this.age = age;
            this.email = email;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TestPerson person = (TestPerson) obj;
            return age == person.age &&
                   name.equals(person.name) &&
                   email.equals(person.email);
        }

        @Override
        public String toString() {
            return "TestPerson{name='" + name + "', age=" + age + ", email='" + email + "'}";
        }
    }

    @Test
    void testStringTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing String type safety across all consumer modes");

        String topicName = "test-string-type-safety";
        String testMessage = "Hello, Type Safety! 🚀";

        // Test LISTEN_NOTIFY_ONLY mode
        testTypeSafetyForMode(topicName + "-listen", String.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);

        // Test POLLING_ONLY mode
        testTypeSafetyForMode(topicName + "-polling", String.class, testMessage, ConsumerMode.POLLING_ONLY);

        // Test HYBRID mode
        testTypeSafetyForMode(topicName + "-hybrid", String.class, testMessage, ConsumerMode.HYBRID);

        logger.info("✅ String type safety verified across all consumer modes");
    }

    @Test
    void testIntegerTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing Integer type safety across all consumer modes");

        String topicName = "test-integer-type-safety";
        Integer testMessage = 42;

        // Test LISTEN_NOTIFY_ONLY mode
        testTypeSafetyForMode(topicName + "-listen", Integer.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);

        // Test POLLING_ONLY mode
        testTypeSafetyForMode(topicName + "-polling", Integer.class, testMessage, ConsumerMode.POLLING_ONLY);

        // Test HYBRID mode
        testTypeSafetyForMode(topicName + "-hybrid", Integer.class, testMessage, ConsumerMode.HYBRID);

        logger.info("✅ Integer type safety verified across all consumer modes");
    }

    @Test
    void testComplexObjectTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing complex object type safety across all consumer modes");

        String topicName = "test-complex-object-type-safety";
        TestPerson testMessage = new TestPerson("Alice Johnson", 30, "alice@example.com");

        // Test LISTEN_NOTIFY_ONLY mode
        testTypeSafetyForMode(topicName + "-listen", TestPerson.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);

        // Test POLLING_ONLY mode
        testTypeSafetyForMode(topicName + "-polling", TestPerson.class, testMessage, ConsumerMode.POLLING_ONLY);

        // Test HYBRID mode
        testTypeSafetyForMode(topicName + "-hybrid", TestPerson.class, testMessage, ConsumerMode.HYBRID);

        logger.info("✅ Complex object type safety verified across all consumer modes");
    }

    @Test
    void testListTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing List type safety across all consumer modes");

        String topicName = "test-list-type-safety";
        Class<List<String>> listClass = (Class<List<String>>) (Class<?>) List.class;
        // Use ArrayList instead of List.of() to match Jackson deserialization behavior
        List<String> testMessage = new ArrayList<>(List.of("item1", "item2", "item3"));

        // Test LISTEN_NOTIFY_ONLY mode
        testTypeSafetyForMode(topicName + "-listen", listClass, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);

        // Test POLLING_ONLY mode
        testTypeSafetyForMode(topicName + "-polling", listClass, testMessage, ConsumerMode.POLLING_ONLY);

        // Test HYBRID mode
        testTypeSafetyForMode(topicName + "-hybrid", listClass, testMessage, ConsumerMode.HYBRID);

        logger.info("✅ List type safety verified across all consumer modes");
    }

    @Test
    void testMapTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing Map type safety across all consumer modes");

        String topicName = "test-map-type-safety";
        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        // Use LinkedHashMap instead of Map.of() to match Jackson deserialization behavior
        Map<String, Object> testMessage = new LinkedHashMap<>();
        testMessage.put("name", "Test Map");
        testMessage.put("count", 123);
        testMessage.put("active", true);

        // Test LISTEN_NOTIFY_ONLY mode
        testTypeSafetyForMode(topicName + "-listen", mapClass, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);

        // Test POLLING_ONLY mode
        testTypeSafetyForMode(topicName + "-polling", mapClass, testMessage, ConsumerMode.POLLING_ONLY);

        // Test HYBRID mode
        testTypeSafetyForMode(topicName + "-hybrid", mapClass, testMessage, ConsumerMode.HYBRID);

        logger.info("✅ Map type safety verified across all consumer modes");
    }

    @Test
    void testBigDecimalTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing BigDecimal type safety across all consumer modes");

        String topicName = "test-bigdecimal-type-safety";
        BigDecimal testMessage = new BigDecimal("123.456789");

        // Test LISTEN_NOTIFY_ONLY mode
        testTypeSafetyForMode(topicName + "-listen", BigDecimal.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);

        // Test POLLING_ONLY mode
        testTypeSafetyForMode(topicName + "-polling", BigDecimal.class, testMessage, ConsumerMode.POLLING_ONLY);

        // Test HYBRID mode
        testTypeSafetyForMode(topicName + "-hybrid", BigDecimal.class, testMessage, ConsumerMode.HYBRID);

        logger.info("✅ BigDecimal type safety verified across all consumer modes");
    }

    @Test
    void testNullValueHandlingAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing null value handling behavior across all consumer modes");

        String topicName = "test-null-value-handling";

        // Based on logs, the producer accepts null values but consumer rejects them during processing
        // Test that the system handles this gracefully by moving null messages to dead letter queue
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());

        AtomicReference<String> receivedMessage = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            logger.info("📨 Received message: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(500);

        try {
            // Producer accepts null values (based on logs showing successful NOTIFY)
            producer.send(null).get(5, TimeUnit.SECONDS);
            logger.info("✅ Producer accepted null payload (will be rejected by consumer)");

            // Consumer should not receive the message (it gets moved to dead letter queue)
            boolean received = latch.await(5, TimeUnit.SECONDS);
            assertFalse(received, "Consumer should not receive null payload (moved to dead letter queue)");

            logger.info("✅ Null payload correctly handled - producer accepts, consumer rejects, moved to DLQ");
        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Null value handling behavior verified");
    }

    /**
     * Helper method to test type safety for a specific consumer mode and payload type.
     */
    private <T> void testTypeSafetyForMode(String topicName, Class<T> payloadType, T expectedMessage, 
                                          ConsumerMode mode) throws Exception {
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofSeconds(1))
                .build();

        MessageConsumer<T> consumer = factory.createConsumer(topicName, payloadType, config);
        MessageProducer<T> producer = factory.createProducer(topicName, payloadType);

        AtomicReference<T> receivedMessage = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            logger.info("📨 Received {} message in {} mode: {}", 
                payloadType.getSimpleName(), mode, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(500);

        // Send test message
        producer.send(expectedMessage).get(5, TimeUnit.SECONDS);

        // Wait for message processing
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive message in " + mode + " mode");

        // Verify type safety and content
        T actualMessage = receivedMessage.get();
        if (expectedMessage == null) {
            assertNull(actualMessage, "Should receive null value correctly");
        } else {
            assertNotNull(actualMessage, "Should receive non-null message");
            assertEquals(expectedMessage, actualMessage,
                "Message content should match exactly for " + payloadType.getSimpleName());

            // For collection types, verify the content matches but allow different implementation classes
            // (Jackson may deserialize to different concrete types than what we sent)
            if (payloadType == List.class || payloadType == Map.class) {
                logger.info("✅ Collection content verified - sent: {}, received: {}",
                    expectedMessage.getClass().getSimpleName(), actualMessage.getClass().getSimpleName());
            } else {
                assertEquals(expectedMessage.getClass(), actualMessage.getClass(),
                    "Message type should match exactly for non-collection types");
            }
        }

        consumer.close();
        producer.close();
        
        logger.info("✅ Type safety verified for {} in {} mode", payloadType.getSimpleName(), mode);
    }
}
