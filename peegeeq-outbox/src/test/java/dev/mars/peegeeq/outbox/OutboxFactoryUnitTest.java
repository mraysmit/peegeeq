package dev.mars.peegeeq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OutboxFactory focusing on validation logic.
 * Tests constructor validation and method parameter validation without database dependencies.
 */
@DisplayName("OutboxFactory Unit Tests")
@SuppressWarnings({"resource", "unused"})
class OutboxFactoryUnitTest {

    private TestDatabaseService testDatabaseService;
    private ObjectMapper testObjectMapper;
    private PeeGeeQConfiguration testConfiguration;

    @BeforeEach
    void setUp() {
        testDatabaseService = new TestDatabaseService();
        testObjectMapper = new ObjectMapper();
        testConfiguration = new PeeGeeQConfiguration("test");
    }

    // Constructor Tests

    @Test
    @DisplayName("Should create factory with DatabaseService only")
    void testConstructorWithDatabaseServiceOnly() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);
        assertNotNull(factory);
    }

    @Test
    @DisplayName("Should create factory with DatabaseService and ObjectMapper")
    void testConstructorWithObjectMapper() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService, testObjectMapper);
        assertNotNull(factory);
    }

    @Test
    @DisplayName("Should create factory with DatabaseService and Configuration")
    void testConstructorWithConfiguration() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService, testConfiguration);
        assertNotNull(factory);
    }

    @Test
    @DisplayName("Should create factory with DatabaseService, ObjectMapper, and Configuration")
    void testConstructorWithObjectMapperAndConfiguration() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService, testObjectMapper, testConfiguration);
        assertNotNull(factory);
    }

    @Test
    @DisplayName("Should create factory with all parameters")
    void testConstructorWithAllParameters() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService, testObjectMapper, testConfiguration, "test-client");
        assertNotNull(factory);
    }

    @Test
    @DisplayName("Should handle null ObjectMapper by creating default")
    void testConstructorWithNullObjectMapper() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService, (ObjectMapper) null);
        assertNotNull(factory);
    }

    // createProducer Tests

    @Test
    @DisplayName("Should reject null topic in createProducer")
    void testCreateProducerWithNullTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createProducer(null, String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject empty topic in createProducer")
    void testCreateProducerWithEmptyTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createProducer("", String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject whitespace topic in createProducer")
    void testCreateProducerWithWhitespaceTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createProducer("   ", String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject null payload type in createProducer")
    void testCreateProducerWithNullPayloadType() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createProducer("test-topic", null)
        );
        assertTrue(ex.getMessage().contains("Payload type"));
    }

    @Test
    @DisplayName("Should create producer with valid topic and payload type")
    void testCreateProducerWithValidParameters() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        MessageProducer<String> producer = factory.createProducer("test-topic", String.class);
        assertNotNull(producer);
    }

    // createConsumer Tests

    @Test
    @DisplayName("Should reject null topic in createConsumer")
    void testCreateConsumerWithNullTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumer(null, String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject empty topic in createConsumer")
    void testCreateConsumerWithEmptyTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumer("", String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject null payload type in createConsumer")
    void testCreateConsumerWithNullPayloadType() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumer("test-topic", null)
        );
        assertTrue(ex.getMessage().contains("Payload type"));
    }

    @Test
    @DisplayName("Should create consumer with valid parameters")
    void testCreateConsumerWithValidParameters() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        MessageConsumer<String> consumer = factory.createConsumer("test-topic", String.class);
        assertNotNull(consumer);
    }

    // createConsumerGroup Tests

    @Test
    @DisplayName("Should reject null topic in createConsumerGroup")
    void testCreateConsumerGroupWithNullTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumerGroup(null, "test-group", String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject empty topic in createConsumerGroup")
    void testCreateConsumerGroupWithEmptyTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumerGroup("", "test-group", String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject null groupName in createConsumerGroup")
    void testCreateConsumerGroupWithNullGroupName() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumerGroup("test-topic", null, String.class)
        );
        assertTrue(ex.getMessage().contains("Group name"));
    }

    @Test
    @DisplayName("Should reject empty groupName in createConsumerGroup")
    void testCreateConsumerGroupWithEmptyGroupName() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumerGroup("test-topic", "", String.class)
        );
        assertTrue(ex.getMessage().contains("Group name"));
    }

    @Test
    @DisplayName("Should reject null payload type in createConsumerGroup")
    void testCreateConsumerGroupWithNullPayloadType() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createConsumerGroup("test-topic", "test-group", null)
        );
        assertTrue(ex.getMessage().contains("Payload type"));
    }

    @Test
    @DisplayName("Should create consumer group with valid parameters")
    void testCreateConsumerGroupWithValidParameters() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup("test-topic", "test-group", String.class);
        assertNotNull(consumerGroup);
    }

    // createBrowser Tests

    @Test
    @DisplayName("Should reject null topic in createBrowser")
    void testCreateBrowserWithNullTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createBrowser(null, String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject empty topic in createBrowser")
    void testCreateBrowserWithEmptyTopic() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createBrowser("", String.class)
        );
        assertTrue(ex.getMessage().contains("Topic"));
    }

    @Test
    @DisplayName("Should reject null payload type in createBrowser")
    void testCreateBrowserWithNullPayloadType() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                factory.createBrowser("test-topic", null)
        );
        assertTrue(ex.getMessage().contains("Payload type"));
    }

    @Test
    @DisplayName("Should create browser with valid parameters")
    void testCreateBrowserWithValidParameters() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        QueueBrowser<String> browser = factory.createBrowser("test-topic", String.class);
        assertNotNull(browser);
    }

    // close() Tests

    @Test
    @DisplayName("Should allow multiple close calls")
    void testMultipleCloseCallsAreIdempotent() throws Exception {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        factory.close();
        assertDoesNotThrow(() -> {
            try {
                factory.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("Should reject operations after close - createProducer")
    void testCreateProducerAfterClose() throws Exception {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);
        factory.close();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factory.createProducer("test-topic", String.class)
        );
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    @DisplayName("Should reject operations after close - createConsumer")
    void testCreateConsumerAfterClose() throws Exception {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);
        factory.close();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factory.createConsumer("test-topic", String.class)
        );
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    @DisplayName("Should reject operations after close - createConsumerGroup")
    void testCreateConsumerGroupAfterClose() throws Exception {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);
        factory.close();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factory.createConsumerGroup("test-topic", "test-group", String.class)
        );
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    @DisplayName("Should reject operations after close - createBrowser")
    void testCreateBrowserAfterClose() throws Exception {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);
        factory.close();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factory.createBrowser("test-topic", String.class)
        );
        assertTrue(ex.getMessage().contains("closed"));
    }

    // Payload Type Tests

    @Test
    @DisplayName("Should support Integer payload types")
    void testIntegerPayloadType() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        MessageProducer<Integer> producer = factory.createProducer("test-topic", Integer.class);
        assertNotNull(producer);
    }

    @Test
    @DisplayName("Should support custom object payload types")
    void testCustomObjectPayloadType() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        MessageProducer<CustomPayload> producer = factory.createProducer("test-topic", CustomPayload.class);
        assertNotNull(producer);
    }

    @Test
    @DisplayName("Should support different payload types in different producers")
    void testMultiplePayloadTypes() {
        OutboxFactory factory = new OutboxFactory(testDatabaseService);

        MessageProducer<String> stringProducer = factory.createProducer("topic-1", String.class);
        MessageProducer<Integer> intProducer = factory.createProducer("topic-2", Integer.class);
        MessageProducer<CustomPayload> customProducer = factory.createProducer("topic-3", CustomPayload.class);

        assertNotNull(stringProducer);
        assertNotNull(intProducer);
        assertNotNull(customProducer);
    }

    // Test Helpers

    private static class CustomPayload {
        private String field;
        
        public CustomPayload() {}
        
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
    }

    /**
     * Minimal test implementation of DatabaseService for validation tests.
     * Returns null/default values since we're only testing validation logic.
     */
    private static class TestDatabaseService implements DatabaseService {
        @Override
        public java.util.concurrent.CompletableFuture<Void> initialize() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> start() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> stop() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public boolean isRunning() { return true; }
        
        @Override
        public boolean isHealthy() { return true; }
        
        @Override
        public dev.mars.peegeeq.api.database.ConnectionProvider getConnectionProvider() { return null; }
        
        @Override
        public MetricsProvider getMetricsProvider() { return null; }
        
        @Override
        public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionService() { return null; }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> runMigrations() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public java.util.concurrent.CompletableFuture<Boolean> performHealthCheck() { return java.util.concurrent.CompletableFuture.completedFuture(true); }
        
        @Override
        public io.vertx.core.Vertx getVertx() { return null; }
        
        @Override
        public io.vertx.sqlclient.Pool getPool() { return null; }
        
        @Override
        public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }
        
        @Override
        public void close() { }
    }
}
