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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

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
@ExtendWith(VertxExtension.class)
class OutboxFactoryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxFactoryIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private DatabaseService databaseService;
    private ObjectMapper objectMapper;
    private PeeGeeQManager manager;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for OutboxFactory integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure system properties for TestContainer
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create DatabaseService from manager
            databaseService = new PgDatabaseService(manager);
            objectMapper = new ObjectMapper();
            logger.info("OutboxFactory integration test setup completed");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (manager != null) {
            manager.closeReactive()
                    .onSuccess(v -> {
                        logger.info("OutboxFactory integration test teardown completed");
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
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
    // createBrowser Tests
    // ============================================================

    @Test
    void testCreateBrowser_DatabaseServiceMode(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService, objectMapper, null);

        factory.<String>createBrowser("test-topic", String.class)
                .onSuccess(browser -> testContext.verify(() -> {
                    assertNotNull(browser);
                    assertInstanceOf(OutboxQueueBrowser.class, browser);
                    try { browser.close(); } catch (Exception ignored) { }
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testCreateBrowser_NullTopic_FailsFuture(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.<String>createBrowser(null, String.class)
                .onSuccess(b -> testContext.failNow("Expected failure but got browser: " + b))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    assertTrue(err.getMessage().contains("Topic cannot be null or empty"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testCreateBrowser_EmptyTopic_FailsFuture(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.<String>createBrowser("   ", String.class)
                .onSuccess(b -> testContext.failNow("Expected failure but got browser: " + b))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    assertTrue(err.getMessage().contains("Topic cannot be null or empty"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testCreateBrowser_NullPayloadType_FailsFuture(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.createBrowser("test-topic", null)
                .onSuccess(b -> testContext.failNow("Expected failure but got browser: " + b))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err);
                    assertTrue(err.getMessage().contains("Payload type cannot be null"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testCreateBrowser_AfterClose_FailsFuture(VertxTestContext testContext) throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);
        factory.close();

        factory.<String>createBrowser("test-topic", String.class)
                .onSuccess(b -> testContext.failNow("Expected failure but got browser: " + b))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertTrue(err.getMessage().contains("Queue factory is closed"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testCreateBrowser_MultipleBrowsers(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        io.vertx.core.Future.all(
                factory.<String>createBrowser("topic-a", String.class),
                factory.<String>createBrowser("topic-b", String.class)
        ).onSuccess(cf -> testContext.verify(() -> {
            Object b1 = cf.resultAt(0);
            Object b2 = cf.resultAt(1);
            assertNotNull(b1);
            assertNotNull(b2);
            assertNotSame(b1, b2);
            try { ((AutoCloseable) b1).close(); } catch (Exception ignored) { }
            try { ((AutoCloseable) b2).close(); } catch (Exception ignored) { }
            testContext.completeNow();
        })).onFailure(testContext::failNow);
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
    void testIsHealthy_DatabaseServiceMode_Healthy(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertTrue(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testIsHealthy_AfterClose_ReturnsFalse(VertxTestContext testContext) throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);
        factory.close();

        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertFalse(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ============================================================
    // close Tests
    // ============================================================

    @Test
    void testClose_SuccessfullyClosesFactory(VertxTestContext testContext) throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.close();

        // Verify factory is closed by checking isHealthy
        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertFalse(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testClose_IdempotentClose(VertxTestContext testContext) throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.close();
        factory.close(); // Second close should not throw

        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertFalse(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
    void testCloseLegacy_SwallowsExceptions(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        // closeLegacy should not throw even if close() throws
        factory.closeLegacy();

        // Verify factory is closed
        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertFalse(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testCloseLegacy_IdempotentClose(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.closeLegacy();
        factory.closeLegacy(); // Second close should not throw

        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertFalse(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
    void testMultipleOperations_CreateProducerAndConsumer(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var producer = factory.createProducer("topic", String.class);
        var consumer = factory.createConsumer("topic", String.class);

        assertNotNull(producer);
        assertNotNull(consumer);
        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertTrue(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testMultipleOperations_CreateAllResourceTypes(VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(databaseService);

        var producer = factory.createProducer("topic", String.class);
        var consumer = factory.createConsumer("topic", String.class);
        var group = factory.createConsumerGroup("group", "topic", String.class);

        assertNotNull(producer);
        assertNotNull(consumer);
        assertNotNull(group);
        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertTrue(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testMultipleOperations_CreateAndClose(VertxTestContext testContext) throws Exception {
        OutboxFactory factory = new OutboxFactory(databaseService);

        factory.createProducer("topic1", String.class);
        factory.createConsumer("topic2", String.class);
        factory.createConsumerGroup("group", "topic3", String.class);

        factory.close();

        factory.isHealthy()
                .onSuccess(healthy -> testContext.verify(() -> {
                    assertFalse(healthy);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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



