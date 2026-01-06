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
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OutboxQueueBrowser.
 * Tests browsing messages without consuming them using real database connectivity.
 * Uses TestContainers for proper database infrastructure following standardized patterns.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxQueueBrowserIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueBrowserIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
            .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
            .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private OutboxQueueBrowser<String> browser;
    private OutboxProducer<String> producer;
    private ObjectMapper objectMapper;
    private PgClientFactory clientFactory;
    private PeeGeeQManager manager;
    private static final String TEST_TOPIC = "browser-test-topic";

    @BeforeEach
    void setUp() throws Exception {
        // Initialize trace context for logging
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String correlationId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);
        MDC.put("correlationId", correlationId);

        // Initialize schema using centralized schema initializer
        logger.info("Initializing database schema for OutboxQueueBrowser integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.OUTBOX, SchemaComponent.NATIVE_QUEUE, SchemaComponent.DEAD_LETTER_QUEUE);
        // Ensure clean state for each test execution
        PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, SchemaComponent.OUTBOX, SchemaComponent.NATIVE_QUEUE, SchemaComponent.DEAD_LETTER_QUEUE);

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
        objectMapper = new ObjectMapper();

        // Create browser using manager's pool
        browser = new OutboxQueueBrowser<>(TEST_TOPIC, String.class, manager.getPool(), objectMapper);

        // Create producer for test setup
        producer = new OutboxProducer<>(clientFactory, objectMapper, TEST_TOPIC, String.class, null);

        logger.info("OutboxQueueBrowser integration test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing producer: {}", e.getMessage());
            }
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                logger.warn("Error closing browser: {}", e.getMessage());
            }
        }
        if (manager != null) {
            try {
                manager.stop();
            } catch (Exception e) {
                logger.warn("Error stopping manager: {}", e.getMessage());
            }
        }
        logger.info("OutboxQueueBrowser integration test teardown completed");

        // Clear trace context
        MDC.clear();
    }

    @Test
    void testBrowseEmptyQueue() throws Exception {
        // When - browse empty queue
        List<Message<String>> messages = browser.browse(10, 0).get(5, TimeUnit.SECONDS);

        // Then
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void testBrowseMessages() throws Exception {
        // Given - produce 3 messages
        producer.send("message-1").get(5, TimeUnit.SECONDS);
        producer.send("message-2").get(5, TimeUnit.SECONDS);
        producer.send("message-3").get(5, TimeUnit.SECONDS);

        // When - browse messages
        List<Message<String>> messages = browser.browse(10, 0).get(5, TimeUnit.SECONDS);

        // Then
        assertNotNull(messages);
        assertEquals(3, messages.size());
        
        // Messages returned in DESC order (newest first by default)
        assertEquals("message-3", messages.get(0).getPayload());
        assertEquals("message-2", messages.get(1).getPayload());
        assertEquals("message-1", messages.get(2).getPayload());
    }

    @Test
    void testBrowseWithLimit() throws Exception {
        // Given - produce 5 messages
        for (int i = 1; i <= 5; i++) {
            producer.send("message-" + i).get(5, TimeUnit.SECONDS);
        }

        // When - browse with limit of 2
        List<Message<String>> messages = browser.browse(2, 0).get(5, TimeUnit.SECONDS);

        // Then - should only return 2 messages (newest first)
        assertEquals(2, messages.size());
        assertEquals("message-5", messages.get(0).getPayload());
        assertEquals("message-4", messages.get(1).getPayload());
    }

    @Test
    void testBrowseWithOffset() throws Exception {
        // Given - produce 5 messages
        for (int i = 1; i <= 5; i++) {
            producer.send("message-" + i).get(5, TimeUnit.SECONDS);
        }

        // When - browse with offset of 2
        List<Message<String>> messages = browser.browse(10, 2).get(5, TimeUnit.SECONDS);

        // Then - should skip first 2 messages
        assertEquals(3, messages.size());
        assertEquals("message-3", messages.get(0).getPayload());
        assertEquals("message-2", messages.get(1).getPayload());
        assertEquals("message-1", messages.get(2).getPayload());
    }

    @Test
    void testBrowsePagination() throws Exception {
        // Given - produce 5 messages
        for (int i = 1; i <= 5; i++) {
            producer.send("message-" + i).get(5, TimeUnit.SECONDS);
        }

        // When - browse first page (limit 2, offset 0)
        List<Message<String>> page1 = browser.browse(2, 0).get(5, TimeUnit.SECONDS);

        // Then - verify first page
        assertEquals(2, page1.size());
        assertEquals("message-5", page1.get(0).getPayload());
        assertEquals("message-4", page1.get(1).getPayload());

        // When - browse second page (limit 2, offset 2)
        List<Message<String>> page2 = browser.browse(2, 2).get(5, TimeUnit.SECONDS);

        // Then - verify second page
        assertEquals(2, page2.size());
        assertEquals("message-3", page2.get(0).getPayload());
        assertEquals("message-2", page2.get(1).getPayload());

        // When - browse third page (limit 2, offset 4)
        List<Message<String>> page3 = browser.browse(2, 4).get(5, TimeUnit.SECONDS);

        // Then - verify last page
        assertEquals(1, page3.size());
        assertEquals("message-1", page3.get(0).getPayload());
    }

    @Test
    void testBrowseWithHeaders() throws Exception {
        // Given - produce message with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "test");
        headers.put("type", "integration-test");
        headers.put("version", "1.0");
        
        producer.send("test-payload", headers).get(5, TimeUnit.SECONDS);

        // When - browse messages
        List<Message<String>> messages = browser.browse(10, 0).get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(1, messages.size());
        Message<String> msg = messages.get(0);
        assertEquals("test-payload", msg.getPayload());
        
        Map<String, String> retrievedHeaders = msg.getHeaders();
        assertNotNull(retrievedHeaders);
        assertEquals("test", retrievedHeaders.get("source"));
        assertEquals("integration-test", retrievedHeaders.get("type"));
        assertEquals("1.0", retrievedHeaders.get("version"));
    }

    @Test
    void testBrowseWithCorrelationId() throws Exception {
        // Given - produce message with correlation ID
        String correlationId = "correlation-123-456";
        producer.send("test-payload", null, correlationId).get(5, TimeUnit.SECONDS);

        // When - browse messages
        List<Message<String>> messages = browser.browse(10, 0).get(5, TimeUnit.SECONDS);

        // Then
        assertEquals(1, messages.size());
        Message<String> msg = messages.get(0);
        assertTrue(msg instanceof OutboxMessage, "Message should be an instance of OutboxMessage");
        assertEquals(correlationId, ((OutboxMessage<String>) msg).getCorrelationId());
    }

    @Test
    void testBrowseAfterClose() {
        // Given
        browser.close();

        // When/Then - browsing after close should fail
        assertThrows(Exception.class, () -> {
            browser.browse(10, 0).get(5, TimeUnit.SECONDS);
        });
    }

    @Test
    void testBrowseWithDifferentPayloadType() throws Exception {
        // Given - create browser and producer for Integer type
        String intTopic = "int-topic";
        OutboxQueueBrowser<Integer> intBrowser = new OutboxQueueBrowser<>(
            intTopic, Integer.class, manager.getPool(), objectMapper
        );
        OutboxProducer<Integer> intProducer = new OutboxProducer<>(
            clientFactory, objectMapper, intTopic, Integer.class, null
        );

        try {
            // Produce integer messages
            intProducer.send(42).get(5, TimeUnit.SECONDS);
            intProducer.send(100).get(5, TimeUnit.SECONDS);

            // When - browse messages
            List<Message<Integer>> messages = intBrowser.browse(10, 0).get(5, TimeUnit.SECONDS);

            // Then
            assertEquals(2, messages.size());
            assertEquals(100, messages.get(0).getPayload());
            assertEquals(42, messages.get(1).getPayload());
        } finally {
            intProducer.close();
            intBrowser.close();
        }
    }

    @Test
    void testBrowseMetadata() throws Exception {
        // Given - produce a message
        producer.send("test-message").get(5, TimeUnit.SECONDS);

        // When - browse messages
        List<Message<String>> messages = browser.browse(10, 0).get(5, TimeUnit.SECONDS);

        // Then - verify message metadata
        assertEquals(1, messages.size());
        Message<String> msg = messages.get(0);
        
        assertNotNull(msg.getId(), "Message ID should not be null");
        assertNotNull(msg.getCreatedAt(), "Created timestamp should not be null");
        
        assertTrue(msg instanceof OutboxMessage, "Message should be an instance of OutboxMessage");
        assertEquals("test-message", msg.getPayload(), "Payload should match");
    }

    @Test
    void testBrowseDoesNotConsumeMessages() throws Exception {
        // Given - produce 2 messages
        producer.send("message-1").get(5, TimeUnit.SECONDS);
        producer.send("message-2").get(5, TimeUnit.SECONDS);

        // When - browse messages multiple times
        List<Message<String>> firstBrowse = browser.browse(10, 0).get(5, TimeUnit.SECONDS);
        List<Message<String>> secondBrowse = browser.browse(10, 0).get(5, TimeUnit.SECONDS);

        // Then - both browses should return same messages
        assertEquals(2, firstBrowse.size());
        assertEquals(2, secondBrowse.size());
        assertEquals(firstBrowse.get(0).getPayload(), secondBrowse.get(0).getPayload());
        assertEquals(firstBrowse.get(1).getPayload(), secondBrowse.get(1).getPayload());
    }

    @Test
    void testConstructorWithPool() {
        // When - create browser with pool
        OutboxQueueBrowser<String> customBrowser = new OutboxQueueBrowser<>(
            "custom-topic",
            String.class,
            manager.getPool(),
            objectMapper
        );

        // Then
        assertNotNull(customBrowser);
        customBrowser.close();
    }

    @Test
    void testCloseIdempotency() {
        // When - close multiple times
        browser.close();
        browser.close();

        // Then - should not throw exception
    }
}
