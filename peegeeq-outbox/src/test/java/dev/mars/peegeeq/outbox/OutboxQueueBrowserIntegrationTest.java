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
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
@ExtendWith(VertxExtension.class)
public class OutboxQueueBrowserIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueBrowserIntegrationTest.class);

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
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

        // Create browser using manager's pool with "public" schema (matching the test schema setup)
        browser = new OutboxQueueBrowser<>(TEST_TOPIC, String.class, manager.getPool(), objectMapper, "public");

        // Create producer for test setup
        producer = new OutboxProducer<>(clientFactory, objectMapper, TEST_TOPIC, String.class, null);

        logger.info("OutboxQueueBrowser integration test setup completed");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
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
            manager.closeReactive()
                .onComplete(ar -> {
                    if (ar.failed()) {
                        logger.warn("Error stopping manager: {}", ar.cause().getMessage());
                    }
                    logger.info("OutboxQueueBrowser integration test teardown completed");
                    testContext.completeNow();
                });
        } else {
            logger.info("OutboxQueueBrowser integration test teardown completed");
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));

        // Clear trace context
        MDC.clear();
    }

    @Test
    void testBrowseEmptyQueue(VertxTestContext testContext) throws InterruptedException {
        // When - browse empty queue
        browser.browse(10, 0)
            .onSuccess(messages -> testContext.verify(() -> {
                // Then
                assertNotNull(messages);
                assertTrue(messages.isEmpty());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseMessages(VertxTestContext testContext) throws InterruptedException {
        // Given - produce 3 messages
        producer.send("message-1")
            .compose(v -> producer.send("message-2"))
            .compose(v -> producer.send("message-3"))
            // When - browse messages
            .compose(v -> browser.browse(10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                // Then
                assertNotNull(messages);
                assertEquals(3, messages.size());
                
                // Messages returned in DESC order (newest first by default)
                assertEquals("message-3", messages.get(0).getPayload());
                assertEquals("message-2", messages.get(1).getPayload());
                assertEquals("message-1", messages.get(2).getPayload());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseWithLimit(VertxTestContext testContext) throws InterruptedException {
        // Given - produce 5 messages
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            chain = chain.compose(v -> producer.send("message-" + idx));
        }

        // When - browse with limit of 2
        chain.compose(v -> browser.browse(2, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                // Then - should only return 2 messages (newest first)
                assertEquals(2, messages.size());
                assertEquals("message-5", messages.get(0).getPayload());
                assertEquals("message-4", messages.get(1).getPayload());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseWithOffset(VertxTestContext testContext) throws InterruptedException {
        // Given - produce 5 messages
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            chain = chain.compose(v -> producer.send("message-" + idx));
        }

        // When - browse with offset of 2
        chain.compose(v -> browser.browse(10, 2))
            .onSuccess(messages -> testContext.verify(() -> {
                // Then - should skip first 2 messages
                assertEquals(3, messages.size());
                assertEquals("message-3", messages.get(0).getPayload());
                assertEquals("message-2", messages.get(1).getPayload());
                assertEquals("message-1", messages.get(2).getPayload());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowsePagination(VertxTestContext testContext) throws InterruptedException {
        // Given - produce 5 messages
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            chain = chain.compose(v -> producer.send("message-" + idx));
        }

        // When/Then - browse pages
        chain.compose(v -> browser.browse(2, 0))
            .compose(page1 -> {
                // Verify first page
                testContext.verify(() -> {
                    assertEquals(2, page1.size());
                    assertEquals("message-5", page1.get(0).getPayload());
                    assertEquals("message-4", page1.get(1).getPayload());
                });
                return browser.browse(2, 2);
            })
            .compose(page2 -> {
                // Verify second page
                testContext.verify(() -> {
                    assertEquals(2, page2.size());
                    assertEquals("message-3", page2.get(0).getPayload());
                    assertEquals("message-2", page2.get(1).getPayload());
                });
                return browser.browse(2, 4);
            })
            .onSuccess(page3 -> testContext.verify(() -> {
                // Verify last page
                assertEquals(1, page3.size());
                assertEquals("message-1", page3.get(0).getPayload());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseWithHeaders(VertxTestContext testContext) throws InterruptedException {
        // Given - produce message with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "test");
        headers.put("type", "integration-test");
        headers.put("version", "1.0");
        
        producer.send("test-payload", headers)
            // When - browse messages
            .compose(v -> browser.browse(10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                // Then
                assertEquals(1, messages.size());
                Message<String> msg = messages.get(0);
                assertEquals("test-payload", msg.getPayload());
                
                Map<String, String> retrievedHeaders = msg.getHeaders();
                assertNotNull(retrievedHeaders);
                assertEquals("test", retrievedHeaders.get("source"));
                assertEquals("integration-test", retrievedHeaders.get("type"));
                assertEquals("1.0", retrievedHeaders.get("version"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseWithCorrelationId(VertxTestContext testContext) throws InterruptedException {
        // Given - produce message with correlation ID
        String correlationId = "correlation-123-456";
        producer.send("test-payload", null, correlationId)
            // When - browse messages
            .compose(v -> browser.browse(10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                // Then
                assertEquals(1, messages.size());
                Message<String> msg = messages.get(0);
                assertTrue(msg instanceof OutboxMessage, "Message should be an instance of OutboxMessage");
                assertEquals(correlationId, ((OutboxMessage<String>) msg).getCorrelationId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseAfterClose(VertxTestContext testContext) throws InterruptedException {
        // Given
        browser.close();

        // When/Then - browsing after close should fail
        try {
            browser.browse(10, 0)
                .onFailure(err -> testContext.completeNow())
                .onSuccess(result -> testContext.failNow("Expected failure after close"));
        } catch (Exception e) {
            // browse() threw synchronously after close
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseWithDifferentPayloadType(VertxTestContext testContext) throws InterruptedException {
        // Given - create browser and producer for Integer type
        String intTopic = "int-topic";
        OutboxQueueBrowser<Integer> intBrowser = new OutboxQueueBrowser<>(
            intTopic, Integer.class, manager.getPool(), objectMapper, "public"
        );
        OutboxProducer<Integer> intProducer = new OutboxProducer<>(
            clientFactory, objectMapper, intTopic, Integer.class, null
        );

        // Produce integer messages
        intProducer.send(42)
            .compose(v -> intProducer.send(100))
            // When - browse messages
            .compose(v -> intBrowser.browse(10, 0))
            .onComplete(ar -> {
                intProducer.close();
                intBrowser.close();
                if (ar.succeeded()) {
                    testContext.verify(() -> {
                        List<Message<Integer>> messages = ar.result();
                        assertEquals(2, messages.size());
                        assertEquals(100, messages.get(0).getPayload());
                        assertEquals(42, messages.get(1).getPayload());
                        testContext.completeNow();
                    });
                } else {
                    testContext.failNow(ar.cause());
                }
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseMetadata(VertxTestContext testContext) throws InterruptedException {
        // Given - produce a message
        producer.send("test-message")
            // When - browse messages
            .compose(v -> browser.browse(10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                // Then - verify message metadata
                assertEquals(1, messages.size());
                Message<String> msg = messages.get(0);
                
                assertNotNull(msg.getId(), "Message ID should not be null");
                assertNotNull(msg.getCreatedAt(), "Created timestamp should not be null");
                
                assertTrue(msg instanceof OutboxMessage, "Message should be an instance of OutboxMessage");
                assertEquals("test-message", msg.getPayload(), "Payload should match");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBrowseDoesNotConsumeMessages(VertxTestContext testContext) throws InterruptedException {
        // Given - produce 2 messages
        producer.send("message-1")
            .compose(v -> producer.send("message-2"))
            // When - browse messages multiple times
            .compose(v -> browser.browse(10, 0))
            .compose(firstBrowse -> browser.browse(10, 0).map(secondBrowse -> {
                // Then - both browses should return same messages
                testContext.verify(() -> {
                    assertEquals(2, firstBrowse.size());
                    assertEquals(2, secondBrowse.size());
                    assertEquals(firstBrowse.get(0).getPayload(), secondBrowse.get(0).getPayload());
                    assertEquals(firstBrowse.get(1).getPayload(), secondBrowse.get(1).getPayload());
                });
                return null;
            }))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
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


