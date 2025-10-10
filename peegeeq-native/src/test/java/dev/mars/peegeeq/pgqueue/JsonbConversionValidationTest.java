package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that JSONB conversion is working correctly for Native Queue.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonbConversionValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(JsonbConversionValidationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Set system properties for test configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize schema before starting PeeGeeQ Manager
        initializeSchema();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("jsonb-native-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory
        factory = new PgNativeQueueFactory(
            manager.getClientFactory(),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            manager.getMetrics()
        );
    }

    private void initializeSchema() {
        // Initialize schema using JDBC before starting PeeGeeQManager
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {

            // Create queue_messages table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    lock_id BIGINT,
                    lock_until TIMESTAMP WITH TIME ZONE,
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                )
                """);

            // Create outbox table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    processed_at TIMESTAMP WITH TIME ZONE,
                    processing_started_at TIMESTAMP WITH TIME ZONE,
                    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    next_retry_at TIMESTAMP WITH TIME ZONE,
                    version INT DEFAULT 0,
                    headers JSONB DEFAULT '{}',
                    error_message TEXT,
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                )
                """);

            // Create dead_letter_queue table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS dead_letter_queue (
                    id BIGSERIAL PRIMARY KEY,
                    original_table VARCHAR(50) NOT NULL,
                    original_id BIGINT NOT NULL,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    failure_reason TEXT NOT NULL,
                    retry_count INT NOT NULL,
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255)
                )
                """);

            // Clear existing data
            stmt.execute("TRUNCATE TABLE queue_messages, outbox, dead_letter_queue");

        } catch (Exception e) {
            logger.error("Failed to initialize schema", e);
            throw new RuntimeException("Schema initialization failed", e);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    /**
     * Test that simple string payloads are stored as proper JSONB objects.
     */
    @Test
    @Order(1)
    void testSimpleStringPayloadStoredAsJsonb() throws Exception {
        logger.info("=== Testing Native Queue Simple String Payload JSONB Storage ===");

        String testMessage = "Hello, Native JSONB World!";
        String topic = "jsonb-native-test-simple";

        // Send message
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Verify JSONB storage directly in database
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, jsonb_typeof(payload) as payload_type, 
                       payload->>'value' as extracted_value
                FROM queue_messages 
                WHERE topic = ? 
                ORDER BY id DESC 
                LIMIT 1
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "Should find the inserted message");
                    
                    // Verify it's stored as JSONB object, not string
                    String payloadType = rs.getString("payload_type");
                    assertEquals("object", payloadType, "Payload should be stored as JSONB object, not string");
                    
                    // Verify we can extract the value using JSON operators
                    String extractedValue = rs.getString("extracted_value");
                    assertEquals(testMessage, extractedValue, "Should be able to extract value using JSON operators");
                    
                    logger.info("✅ Native queue simple string payload correctly stored as JSONB object");
                    logger.info("   Payload type: {}", payloadType);
                    logger.info("   Extracted value: {}", extractedValue);
                }
            }
        }

        producer.close();
    }

    /**
     * Test that headers are stored as proper JSONB objects.
     */
    @Test
    @Order(2)
    void testHeadersStoredAsJsonb() throws Exception {
        logger.info("=== Testing Native Queue Headers JSONB Storage ===");

        String testMessage = "Message with headers";
        String topic = "jsonb-native-test-headers";

        // Send message with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "jsonb-native-test");
        headers.put("version", "1.0");
        headers.put("correlationId", "test-correlation-456");

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        producer.send(testMessage, headers).get(5, TimeUnit.SECONDS);

        // Verify JSONB storage directly in database
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, headers,
                       jsonb_typeof(payload) as payload_type,
                       jsonb_typeof(headers) as headers_type,
                       headers->>'correlationId' as correlation_id,
                       headers->>'source' as source_header
                FROM queue_messages 
                WHERE topic = ? 
                ORDER BY id DESC 
                LIMIT 1
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "Should find the inserted message");
                    
                    // Verify both payload and headers are stored as JSONB objects
                    String payloadType = rs.getString("payload_type");
                    String headersType = rs.getString("headers_type");
                    assertEquals("object", payloadType, "Payload should be stored as JSONB object");
                    assertEquals("object", headersType, "Headers should be stored as JSONB object");
                    
                    // Verify we can extract values using JSON operators
                    String correlationId = rs.getString("correlation_id");
                    String sourceHeader = rs.getString("source_header");
                    
                    assertEquals("test-correlation-456", correlationId, "Should extract correlationId from headers");
                    assertEquals("jsonb-native-test", sourceHeader, "Should extract source from headers");
                    
                    logger.info("✅ Native queue headers correctly stored as JSONB object");
                    logger.info("   Payload type: {}, Headers type: {}", payloadType, headersType);
                    logger.info("   Extracted correlationId: {}, source: {}", correlationId, sourceHeader);
                }
            }
        }

        producer.close();
    }

    /**
     * Test that consumers can properly read and parse JSONB objects.
     */
    @Test
    @Order(3)
    void testConsumerCanReadJsonbObjects() throws Exception {
        logger.info("=== Testing Native Queue Consumer JSONB Object Reading ===");

        String topic = "jsonb-native-test-consumer";
        String testMessage = "Consumer test message";
        
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "consumer-test");
        headers.put("priority", "HIGH");

        // Send message
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        producer.send(testMessage, headers).get(5, TimeUnit.SECONDS);

        // Consume message
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        consumer.subscribe(message -> {
            try {
                // Verify the payload was correctly deserialized from JSONB
                String receivedMessage = message.getPayload();
                assertNotNull(receivedMessage, "Payload should not be null");
                assertEquals(testMessage, receivedMessage, "Message should match");
                
                // Verify headers were correctly deserialized from JSONB
                Map<String, String> receivedHeaders = message.getHeaders();
                assertNotNull(receivedHeaders, "Headers should not be null");
                assertEquals("consumer-test", receivedHeaders.get("source"), "Source header should match");
                assertEquals("HIGH", receivedHeaders.get("priority"), "Priority header should match");
                
                processedCount.incrementAndGet();
                latch.countDown();
                
                logger.info("✅ Native queue consumer successfully read JSONB objects");
                logger.info("   Received message: {}", receivedMessage);
                logger.info("   Received headers: {}", receivedHeaders.size());
                
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                logger.error("Error processing message", e);
                throw new RuntimeException(e);
            }
        });

        // Wait for message processing
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be processed within 10 seconds");
        assertEquals(1, processedCount.get(), "Should have processed exactly 1 message");

        consumer.close();
        producer.close();
    }
}
