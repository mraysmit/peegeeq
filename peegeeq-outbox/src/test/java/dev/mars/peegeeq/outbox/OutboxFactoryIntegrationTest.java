package dev.mars.peegeeq.outbox;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OutboxFactory.
 * Tests constructor variants, creation methods (basic validation), lifecycle management.
 * Uses TestContainers for real database connectivity following standardized patterns.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-02
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxFactoryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxFactoryIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
            .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
            .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private DatabaseService databaseService;
    private ObjectMapper objectMapper;
    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for OutboxFactory integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure system properties for TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.schema", "public");

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create DatabaseService from manager
        databaseService = new PgDatabaseService(manager);
        objectMapper = new ObjectMapper();

        logger.info("OutboxFactory integration test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            try {
                manager.stop();
            } catch (Exception e) {
                logger.warn("Error stopping manager: {}", e.getMessage());
            }
        }
        logger.info("OutboxFactory integration test teardown completed");
    }

    // ============================================================
    // Constructor Tests (DatabaseService mode)
    // ============================================================

    @Test
    void testConstructor_DatabaseServiceOnly() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        assertNotNull(factory);
        assertNotNull(factory.getObjectMapper());
        assertEquals("outbox", factory.getImplementationType());
    }

    @Test
    void testConstructor_DatabaseServiceWithObjectMapper() {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper);

        assertNotNull(factory);
        assertSame(objectMapper, factory.getObjectMapper());
        assertEquals("outbox", factory.getImplementationType());
    }

    @Test
    void testConstructor_DatabaseServiceWithObjectMapperAndMetrics() {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper, null);

        assertNotNull(factory);
        assertSame(objectMapper, factory.getObjectMapper());
        assertEquals("outbox", factory.getImplementationType());
    }

    @Test
    void testConstructor_DatabaseServiceWithNullObjectMapper() {
        // Should create default ObjectMapper when null provided
        OutboxFactory factory = new OutboxFactory(databaseService, (ObjectMapper) null);

        assertNotNull(factory);
        assertNotNull(factory.getObjectMapper());
        assertEquals("outbox", factory.getImplementationType());
    }

    // ============================================================
    // createProducer Tests
    // ============================================================

    @Test
    void testCreateProducer_DatabaseServiceMode() {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper, null);

        var producer = factory.createProducer("test-topic", String.class);

        assertNotNull(producer);
        assertInstanceOf(OutboxProducer.class, producer);
    }

    @Test
    void testCreateProducer_NullTopic_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createProducer(null, String.class));

        assertTrue(exception.getMessage().contains("Topic cannot be null or empty"));
    }

    @Test
    void testCreateProducer_EmptyTopic_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createProducer("   ", String.class));

        assertTrue(exception.getMessage().contains("Topic cannot be null or empty"));
    }

    @Test
    void testCreateProducer_NullPayloadType_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createProducer("test-topic", null));

        assertTrue(exception.getMessage().contains("Payload type cannot be null"));
    }

    @Test
    void testCreateProducer_MultipleProducers() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var producer1 = factory.createProducer("topic1", String.class);
        var producer2 = factory.createProducer("topic2", Integer.class);

        assertNotNull(producer1);
        assertNotNull(producer2);
        assertNotSame(producer1, producer2);
    }

    @Test
    void testCreateProducer_DifferentPayloadTypes() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var stringProducer = factory.createProducer("topic", String.class);
        var objectProducer = factory.createProducer("topic", TestPayload.class);

        assertNotNull(stringProducer);
        assertNotNull(objectProducer);
    }

    // ============================================================
    // createConsumer Tests
    // ============================================================

    @Test
    void testCreateConsumer_DatabaseServiceMode() {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper, null);

        var consumer = factory.createConsumer("test-topic", String.class);

        assertNotNull(consumer);
        assertInstanceOf(OutboxConsumer.class, consumer);
    }

    @Test
    void testCreateConsumer_NullTopic_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumer(null, String.class));

        assertTrue(exception.getMessage().contains("Topic cannot be null or empty"));
    }

    @Test
    void testCreateConsumer_EmptyTopic_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumer("", String.class));

        assertTrue(exception.getMessage().contains("Topic cannot be null or empty"));
    }

    @Test
    void testCreateConsumer_NullPayloadType_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumer("test-topic", null));

        assertTrue(exception.getMessage().contains("Payload type cannot be null"));
    }

    @Test
    void testCreateConsumer_MultipleConsumers() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var consumer1 = factory.createConsumer("topic1", String.class);
        var consumer2 = factory.createConsumer("topic2", String.class);

        assertNotNull(consumer1);
        assertNotNull(consumer2);
        assertNotSame(consumer1, consumer2);
    }

    // ============================================================
    // createConsumerGroup Tests
    // ============================================================

    @Test
    void testCreateConsumerGroup_DatabaseServiceMode() {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper, null);

        var group = factory.createConsumerGroup("test-group", "test-topic", String.class);

        assertNotNull(group);
        assertInstanceOf(OutboxConsumerGroup.class, group);
    }

    @Test
    void testCreateConsumerGroup_NullGroupName_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumerGroup(null, "test-topic", String.class));

        assertTrue(exception.getMessage().contains("Group name cannot be null or empty"));
    }

    @Test
    void testCreateConsumerGroup_EmptyGroupName_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumerGroup("  ", "test-topic", String.class));

        assertTrue(exception.getMessage().contains("Group name cannot be null or empty"));
    }

    @Test
    void testCreateConsumerGroup_NullTopic_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumerGroup("test-group", null, String.class));

        assertTrue(exception.getMessage().contains("Topic cannot be null or empty"));
    }

    @Test
    void testCreateConsumerGroup_EmptyTopic_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumerGroup("test-group", "", String.class));

        assertTrue(exception.getMessage().contains("Topic cannot be null or empty"));
    }

    @Test
    void testCreateConsumerGroup_NullPayloadType_ThrowsException() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> factory.createConsumerGroup("test-group", "test-topic", null));

        assertTrue(exception.getMessage().contains("Payload type cannot be null"));
    }

    @Test
    void testCreateConsumerGroup_MultipleGroups() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var group1 = factory.createConsumerGroup("group1", "topic1", String.class);
        var group2 = factory.createConsumerGroup("group2", "topic2", String.class);

        assertNotNull(group1);
        assertNotNull(group2);
        assertNotSame(group1, group2);
    }

    // ============================================================
    // getImplementationType Tests
    // ============================================================

    @Test
    void testGetImplementationType_ReturnsOutbox() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        String type = factory.getImplementationType();

        assertEquals("outbox", type);
    }

    // ============================================================
    // isHealthy Tests
    // ============================================================

    @Test
    void testIsHealthy_DatabaseServiceMode_Healthy() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        boolean healthy = factory.isHealthy();

        assertTrue(healthy);
    }

    @Test
    void testIsHealthy_AfterClose_ReturnsFalse() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);
        factory.close();

        boolean healthy = factory.isHealthy();

        assertFalse(healthy);
    }

    // ============================================================
    // close Tests
    // ============================================================

    @Test
    void testClose_SuccessfullyClosesFactory() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.close();

        // Verify factory is closed by checking isHealthy
        assertFalse(factory.isHealthy());
    }

    @Test
    void testClose_IdempotentClose() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.close();
        factory.close(); // Second close should not throw

        assertFalse(factory.isHealthy());
    }

    @Test
    void testClose_AfterClose_CannotCreateProducer() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);
        factory.close();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> factory.createProducer("topic", String.class));

        assertTrue(exception.getMessage().contains("Queue factory is closed"));
    }

    @Test
    void testClose_AfterClose_CannotCreateConsumer() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);
        factory.close();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> factory.createConsumer("topic", String.class));

        assertTrue(exception.getMessage().contains("Queue factory is closed"));
    }

    @Test
    void testClose_AfterClose_CannotCreateConsumerGroup() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);
        factory.close();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> factory.createConsumerGroup("group", "topic", String.class));

        assertTrue(exception.getMessage().contains("Queue factory is closed"));
    }

    @Test
    void testCloseLegacy_SwallowsExceptions() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        // closeLegacy should not throw even if close() throws
        factory.closeLegacy();

        // Verify factory is closed
        assertFalse(factory.isHealthy());
    }

    @Test
    void testCloseLegacy_IdempotentClose() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.closeLegacy();
        factory.closeLegacy(); // Second close should not throw

        assertFalse(factory.isHealthy());
    }

    // ============================================================
    // getObjectMapper Tests
    // ============================================================

    @Test
    void testGetObjectMapper_ReturnsProvidedMapper() {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper);

        ObjectMapper mapper = factory.getObjectMapper();

        assertSame(objectMapper, mapper);
    }

    @Test
    void testGetObjectMapper_ReturnsDefaultMapperWhenNotProvided() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        ObjectMapper mapper = factory.getObjectMapper();

        assertNotNull(mapper);
        // Verify it has JavaTimeModule registered by attempting to use it
        assertDoesNotThrow(() -> mapper.writeValueAsString(java.time.Instant.now()));
    }

    @Test
    void testGetObjectMapper_DefaultMapperHasJavaTimeModule() {
        OutboxFactory factory = new OutboxFactory(databaseService);
        ObjectMapper mapper = factory.getObjectMapper();

        // Verify JavaTimeModule is registered by checking if it can serialize Instant
        assertDoesNotThrow(() -> mapper.writeValueAsString(java.time.Instant.now()));
    }

    // ============================================================
    // Integration Tests (multiple operations)
    // ============================================================

    @Test
    void testMultipleOperations_CreateProducerAndConsumer() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var producer = factory.createProducer("topic", String.class);
        var consumer = factory.createConsumer("topic", String.class);

        assertNotNull(producer);
        assertNotNull(consumer);
        assertTrue(factory.isHealthy());
    }

    @Test
    void testMultipleOperations_CreateAllResourceTypes() {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var producer = factory.createProducer("topic", String.class);
        var consumer = factory.createConsumer("topic", String.class);
        var group = factory.createConsumerGroup("group", "topic", String.class);

        assertNotNull(producer);
        assertNotNull(consumer);
        assertNotNull(group);
        assertTrue(factory.isHealthy());
    }

    @Test
    void testMultipleOperations_CreateAndClose() throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.createProducer("topic1", String.class);
        factory.createConsumer("topic2", String.class);
        factory.createConsumerGroup("group", "topic3", String.class);

        factory.close();

        assertFalse(factory.isHealthy());
    }

    // ============================================================
    // Helper Classes
    // ============================================================

    static class TestPayload {
        private String data;

        public TestPayload() {}

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}

