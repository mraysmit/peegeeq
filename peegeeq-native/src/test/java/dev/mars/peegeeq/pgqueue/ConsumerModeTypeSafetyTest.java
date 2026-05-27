package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeTypeSafetyTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeTypeSafetyTest.class);


    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.polling-interval", "PT2S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("native", databaseService);
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(err -> {
                    logger.error("Teardown close failed", err);
                    ctx.failNow(err);
                });
        assertTrue(ctx.awaitCompletion(30, TimeUnit.SECONDS));
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
        public int hashCode() {
            return java.util.Objects.hash(name, age, email);
        }

        @Override
        public String toString() {
            return "TestPerson{name='" + name + "', age=" + age + ", email='" + email + "'}";
        }
    }

    @Test
    void testStringTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("Test: string type safety across consumer modes");
        String topicName = "test-string-type-safety";
        String testMessage = "Hello, Type Safety! 🚀";

        testTypeSafetyForMode(topicName + "-listen", String.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);
        testTypeSafetyForMode(topicName + "-polling", String.class, testMessage, ConsumerMode.POLLING_ONLY);
        testTypeSafetyForMode(topicName + "-hybrid", String.class, testMessage, ConsumerMode.HYBRID);
    }

    @Test
    void testIntegerTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("Test: integer type safety across consumer modes");
        String topicName = "test-integer-type-safety";
        Integer testMessage = 42;

        testTypeSafetyForMode(topicName + "-listen", Integer.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);
        testTypeSafetyForMode(topicName + "-polling", Integer.class, testMessage, ConsumerMode.POLLING_ONLY);
        testTypeSafetyForMode(topicName + "-hybrid", Integer.class, testMessage, ConsumerMode.HYBRID);
    }

    @Test
    void testComplexObjectTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("Test: complex object type safety across consumer modes");
        String topicName = "test-complex-object-type-safety";
        TestPerson testMessage = new TestPerson("Alice Johnson", 30, "alice@example.com");

        testTypeSafetyForMode(topicName + "-listen", TestPerson.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);
        testTypeSafetyForMode(topicName + "-polling", TestPerson.class, testMessage, ConsumerMode.POLLING_ONLY);
        testTypeSafetyForMode(topicName + "-hybrid", TestPerson.class, testMessage, ConsumerMode.HYBRID);
    }

    @Test
    void testListTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("Test: list type safety across consumer modes");
        String topicName = "test-list-type-safety";
        @SuppressWarnings("unchecked")
        Class<List<String>> listClass = (Class<List<String>>) (Class<?>) List.class;
        List<String> testMessage = new ArrayList<>(List.of("item1", "item2", "item3"));

        testTypeSafetyForMode(topicName + "-listen", listClass, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);
        testTypeSafetyForMode(topicName + "-polling", listClass, testMessage, ConsumerMode.POLLING_ONLY);
        testTypeSafetyForMode(topicName + "-hybrid", listClass, testMessage, ConsumerMode.HYBRID);
    }

    @Test
    void testMapTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("Test: map type safety across consumer modes");
        String topicName = "test-map-type-safety";
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        Map<String, Object> testMessage = new LinkedHashMap<>();
        testMessage.put("name", "Test Map");
        testMessage.put("count", 123);
        testMessage.put("active", true);

        testTypeSafetyForMode(topicName + "-listen", mapClass, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);
        testTypeSafetyForMode(topicName + "-polling", mapClass, testMessage, ConsumerMode.POLLING_ONLY);
        testTypeSafetyForMode(topicName + "-hybrid", mapClass, testMessage, ConsumerMode.HYBRID);
    }

    @Test
    void testBigDecimalTypeSafetyAcrossConsumerModes() throws Exception {
        logger.info("Test: big decimal type safety across consumer modes");
        String topicName = "test-bigdecimal-type-safety";
        BigDecimal testMessage = new BigDecimal("123.456789");

        testTypeSafetyForMode(topicName + "-listen", BigDecimal.class, testMessage, ConsumerMode.LISTEN_NOTIFY_ONLY);
        testTypeSafetyForMode(topicName + "-polling", BigDecimal.class, testMessage, ConsumerMode.POLLING_ONLY);
        testTypeSafetyForMode(topicName + "-hybrid", BigDecimal.class, testMessage, ConsumerMode.HYBRID);
    }

    @Test
    void testNullValueHandlingAcrossConsumerModes(VertxTestContext testContext) throws Exception {
        logger.info("Test: null value handling across consumer modes");
        String topicName = "test-null-value-handling";

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());

        CountDownLatch nullLatch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            nullLatch.countDown();
            return Future.succeededFuture();
        });

        try {
            producer.send(null).onFailure(err -> logger.debug("Expected null send failure: {}", err.getMessage()));

            boolean received = nullLatch.await(5, TimeUnit.SECONDS);
            assertFalse(received, "Consumer should not receive null payload (moved to dead letter queue)");
        } finally {
            consumer.close();
            producer.close();
        }

        testContext.completeNow();
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

        try {
            AtomicReference<T> receivedMessage = new AtomicReference<>();
            CountDownLatch modeLatch = new CountDownLatch(1);

            consumer.subscribe(message -> {
                receivedMessage.set(message.getPayload());
                modeLatch.countDown();
                return Future.succeededFuture();
            });

            producer.send(expectedMessage).onFailure(err -> logger.warn("send failed in type-safety test: {}", err.getMessage()));

            boolean received = modeLatch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Should receive message in " + mode + " mode");

            T actualMessage = receivedMessage.get();
            assertNotNull(actualMessage, "Should receive non-null message");
            assertEquals(expectedMessage, actualMessage,
                "Message content should match exactly for " + payloadType.getSimpleName());

            if (payloadType != List.class && payloadType != Map.class) {
                assertEquals(expectedMessage.getClass(), actualMessage.getClass(),
                    "Message type should match exactly for non-collection types");
            }
        } finally {
            consumer.close();
            producer.close();
        }
    }
}


