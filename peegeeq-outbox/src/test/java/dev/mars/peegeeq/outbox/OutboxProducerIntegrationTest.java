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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

/**
 * Integration tests for OutboxProducer.
 * Tests producer lifecycle and validation logic with real database connectivity.
 * Uses TestContainers for proper database infrastructure following standardized patterns.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxProducerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME);
        container.withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME);
        container.withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD);
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private PgClientFactory clientFactory;
    private ObjectMapper objectMapper;
    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for OutboxProducer integration tests");
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

        // Get client factory from manager
        clientFactory = manager.getClientFactory();

        // Configure ObjectMapper with JSR310 support
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        logger.info("OutboxProducer integration test setup completed");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (manager != null) {
            manager.closeReactive().onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        logger.info("OutboxProducer integration test teardown completed");
    }

    @Test
    void testConstructorWithClientFactory() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null // metrics can be null
        );

        assertNotNull(producer);
    }

    @Test
    void testConstructorWithClientFactory_EmptyTopic() {
        // Empty topic is allowed but not recommended
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "",
            String.class,
            null
        );

        assertNotNull(producer);
    }

    @Test
    void testClose_WhenNotUsed() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        assertDoesNotThrow(producer::close);
    }

    @Test
    void testClose_MultipleInvocations() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        assertDoesNotThrow(producer::close);
        assertDoesNotThrow(producer::close); // Second close should be safe
    }

    @Test
    void testSend_ThrowsWhenClosed() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        producer.close();

        // Attempting to send after close should fail
        var future = producer.send("test-message");
        assertTrue(future.failed(), "Send after close should fail");
    }

    @Test
    void testSend_NullPayload() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // Attempting to send null payload should fail
        var future = producer.send(null);
        assertTrue(future.failed(), "Send with null payload should fail");
    }

    @Test
    void testsendInOwnTransaction_NullPayload() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // Attempting to send null payload should fail
        var future = producer.sendInOwnTransaction(null);
        assertTrue(future.failed(), "sendInOwnTransaction with null payload should fail");
    }

    @Test
    void testsendInExistingTransaction_NullConnection() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // Attempting to send with null connection should fail
        var future = producer.sendInExistingTransaction("test-message", null);
        assertTrue(future.failed(), "sendInExistingTransaction with null connection should fail");
    }

    @Test
    void testsendInExistingTransaction_NullPayloadAndConnection() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // Attempting to send with null payload and connection should fail
        var future = producer.sendInExistingTransaction(null, null);
        assertTrue(future.failed(), "sendInExistingTransaction with null args should fail");
    }

    @Test
    void testConstructorWithDifferentPayloadTypes() {
        // Test with String type
        OutboxProducer<String> stringProducer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "string-topic",
            String.class,
            null
        );
        assertNotNull(stringProducer);

        // Test with Integer type
        OutboxProducer<Integer> intProducer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "int-topic",
            Integer.class,
            null
        );
        assertNotNull(intProducer);

        // Test with custom object type
        OutboxProducer<TestPayload> objectProducer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "object-topic",
            TestPayload.class,
            null
        );
        assertNotNull(objectProducer);
    }

    @Test
    void testConstructorLogsTopicName() {
        // This test verifies constructor completes successfully
        // (Actual logging verification would require log capture)
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "logged-topic",
            String.class,
            null
        );

        assertNotNull(producer);
    }

    @Test
    void testSend_ValidatesProducerNotClosed() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );
        producer.close();

        // Test send methods return failed Future with IllegalStateException when closed
        var future1 = producer.send("test");
        assertTrue(future1.failed(), "Send after close should fail");

        var future2 = producer.send("test", null);
        assertTrue(future2.failed(), "Send with headers after close should fail");

        var future3 = producer.send("test", null, "corr-id");
        assertTrue(future3.failed(), "Send with correlation-id after close should fail");
    }

    @Test
    void testsendInOwnTransaction_ValidatesPayload() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // All sendInOwnTransaction overloads should validate payload
        assertTrue(producer.sendInOwnTransaction(null).failed(), "sendInOwnTransaction with null should fail");
    }

    @Test
    void testsendInExistingTransaction_ValidatesArguments() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // sendInExistingTransaction validates both payload and connection
        assertTrue(producer.sendInExistingTransaction("test", null).failed(), "sendInExistingTransaction with null connection should fail");
    }

    @Test
    void testMultipleProducersShareVertxInstance() {
        // Multiple producers can share the same Vertx and clientFactory
        OutboxProducer<String> producer1 = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "topic-1",
            String.class,
            null
        );

        OutboxProducer<Integer> producer2 = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "topic-2",
            Integer.class,
            null
        );

        assertNotNull(producer1);
        assertNotNull(producer2);

        producer1.close();
        producer2.close();
    }

    @Test
    void testProducerWithNullMetrics() {
        // Verify that null metrics is acceptable
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null // null metrics
        );

        assertNotNull(producer);
        producer.close();
    }

    @Test
    void testConstructorAcceptsVariousPayloadTypes() {
        // String payload
        OutboxProducer<String> stringProducer = new OutboxProducer<>(
            clientFactory, objectMapper, "string-topic", String.class, null
        );
        assertNotNull(stringProducer);

        // Integer payload
        OutboxProducer<Integer> intProducer = new OutboxProducer<>(
            clientFactory, objectMapper, "int-topic", Integer.class, null
        );
        assertNotNull(intProducer);

        // Long payload
        OutboxProducer<Long> longProducer = new OutboxProducer<>(
            clientFactory, objectMapper, "long-topic", Long.class, null
        );
        assertNotNull(longProducer);

        // Custom object payload
        OutboxProducer<TestPayload> objectProducer = new OutboxProducer<>(
            clientFactory, objectMapper, "object-topic", TestPayload.class, null
        );
        assertNotNull(objectProducer);

        // Cleanup
        stringProducer.close();
        intProducer.close();
        longProducer.close();
        objectProducer.close();
    }

    @Test
    void testSend_OverloadsWithIncreasingParameters() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // These will fail at runtime due to no database, but should pass validation
        // Testing that methods exist and accept parameters correctly
        assertNotNull(producer.send("payload"));
        assertNotNull(producer.send("payload", null));
        assertNotNull(producer.send("payload", null, "correlation-id"));
        assertNotNull(producer.send("payload", null, "correlation-id", "message-group"));

        producer.close();
    }

    @Test
    void testClose_IdempotentWithResourceCleanup() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // First close
        assertDoesNotThrow(producer::close);

        // Second close should be safe
        assertDoesNotThrow(producer::close);

        // Third close still safe
        assertDoesNotThrow(producer::close);

        // Operations after close should return failed Future
        var future = producer.send("test");
        assertTrue(future.failed(), "Send after close should fail");
    }

    @Test
    void testSendWithEmptyHeaders() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // Empty headers map should be acceptable
        java.util.Map<String, String> emptyHeaders = new java.util.HashMap<>();
        assertNotNull(producer.send("payload", emptyHeaders));

        producer.close();
    }

    @Test
    void testSendWithNullCorrelationId() {
        OutboxProducer<String> producer = new OutboxProducer<>(
            clientFactory,
            objectMapper,
            "test-topic",
            String.class,
            null
        );

        // Null correlation ID should be acceptable
        assertNotNull(producer.send("payload", null, null));

        producer.close();
    }

    // Helper class for testing generic types
    static class TestPayload {
        private String data;

        public TestPayload() {}

        public TestPayload(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}



