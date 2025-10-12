package dev.mars.peegeeq.db.deadletter;

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
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that JSONB conversion is working correctly for Dead Letter Queue Manager.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers
class JsonbConversionValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(JsonbConversionValidationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private Vertx vertx;
    private PgConnectionManager connectionManager;
    private Pool pool;
    private DeadLetterQueueManager dlqManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
        objectMapper = new ObjectMapper();

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .build();

        pool = connectionManager.getOrCreateReactivePool("test-pool", connectionConfig, poolConfig);
        dlqManager = new DeadLetterQueueManager(pool, objectMapper);

        // Initialize schema
        initializeSchema(postgres);

        logger.info("✅ Test setup complete - Dead Letter Queue Manager ready for JSONB validation");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (vertx != null) {
            vertx.close();
        }
        logger.info("✅ Test cleanup complete");
    }

    @Test
    @Order(1)
    void testSimpleStringPayloadStoredAsJsonb() throws Exception {
        logger.info("🧪 Testing simple string payload JSONB storage in dead letter queue...");

        String testMessage = "Hello Dead Letter JSONB World!";
        String topic = "test.simple.dlq";
        String originalTable = "queue_messages";
        long originalId = 1L;
        Instant originalCreatedAt = Instant.now().minusSeconds(300);
        String failureReason = "Test failure for JSONB validation";
        int retryCount = 3;

        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", "dlq-test-correlation-123");
        headers.put("source", "jsonb-dlq-test");

        // Move message to dead letter queue
        dlqManager.moveToDeadLetterQueue(originalTable, originalId, topic, testMessage, 
                                       originalCreatedAt, failureReason, retryCount, 
                                       headers, "dlq-correlation-123", "test-group");

        logger.info("✅ Message moved to dead letter queue successfully");

        // Validate JSONB storage using direct database query
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, jsonb_typeof(payload) as payload_type, 
                       payload->>'value' as extracted_value,
                       headers, jsonb_typeof(headers) as headers_type,
                       headers->>'correlationId' as extracted_correlation_id
                FROM dead_letter_queue 
                WHERE topic = ? 
                ORDER BY failed_at DESC 
                LIMIT 1
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                ResultSet rs = stmt.executeQuery();

                assertTrue(rs.next(), "Should find the dead letter message");

                // Verify payload is stored as JSONB object, not string
                String payloadType = rs.getString("payload_type");
                assertEquals("object", payloadType, "Payload should be stored as JSONB object, not string");

                // Verify we can extract the value using JSON operators
                String extractedValue = rs.getString("extracted_value");
                assertEquals(testMessage, extractedValue, "Should be able to extract value using JSON operators");

                // Verify headers are stored as JSONB object
                String headersType = rs.getString("headers_type");
                assertEquals("object", headersType, "Headers should be stored as JSONB object");

                // Verify we can extract header values using JSON operators
                String extractedCorrelationId = rs.getString("extracted_correlation_id");
                assertEquals("dlq-test-correlation-123", extractedCorrelationId, "Should extract correlationId using JSON operators");

                logger.info("✅ Dead Letter Queue JSONB validation successful - payload type: {}, headers type: {}", 
                          payloadType, headersType);
            }
        }
    }

    @Test
    @Order(2)
    void testComplexObjectPayloadStoredAsJsonb() throws Exception {
        logger.info("🧪 Testing complex object payload JSONB storage in dead letter queue...");

        // Create a complex test object
        TestOrder testOrder = new TestOrder("DLQ-ORDER-001", "FAILED", 2500.00);
        String topic = "order.processing.failed";
        String originalTable = "outbox";
        long originalId = 2L;
        Instant originalCreatedAt = Instant.now().minusSeconds(600);
        String failureReason = "Order processing failed - validation error";
        int retryCount = 5;

        Map<String, String> headers = new HashMap<>();
        headers.put("orderId", testOrder.orderId);
        headers.put("priority", "high");
        headers.put("region", "US");

        // Move message to dead letter queue
        dlqManager.moveToDeadLetterQueue(originalTable, originalId, topic, testOrder, 
                                       originalCreatedAt, failureReason, retryCount, 
                                       headers, "order-correlation-456", "order-group");

        logger.info("✅ Complex object moved to dead letter queue successfully");

        // Validate JSONB storage using direct database query
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, jsonb_typeof(payload) as payload_type, 
                       payload->>'orderId' as extracted_order_id,
                       payload->>'status' as extracted_status,
                       headers->>'priority' as extracted_priority
                FROM dead_letter_queue 
                WHERE topic = ? 
                ORDER BY failed_at DESC 
                LIMIT 1
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                ResultSet rs = stmt.executeQuery();

                assertTrue(rs.next(), "Should find the dead letter message");

                // Verify payload is stored as JSONB object
                String payloadType = rs.getString("payload_type");
                assertEquals("object", payloadType, "Payload should be stored as JSONB object");

                // Verify we can extract complex object fields using JSON operators
                String extractedOrderId = rs.getString("extracted_order_id");
                String extractedStatus = rs.getString("extracted_status");
                String extractedPriority = rs.getString("extracted_priority");
                
                assertEquals(testOrder.orderId, extractedOrderId, "Should extract orderId using JSON operators");
                assertEquals(testOrder.status, extractedStatus, "Should extract status using JSON operators");
                assertEquals("high", extractedPriority, "Should extract priority from headers using JSON operators");

                logger.info("✅ Complex Dead Letter Queue JSONB validation successful - orderId: {}, status: {}, priority: {}", 
                          extractedOrderId, extractedStatus, extractedPriority);
            }
        }
    }

    @Test
    @Order(3)
    void testReprocessingWithJsonbData() throws Exception {
        logger.info("🧪 Testing dead letter message reprocessing with JSONB data...");

        String testMessage = "Reprocess test message";
        String topic = "test.reprocess.dlq";
        String originalTable = "queue_messages";
        long originalId = 3L;
        Instant originalCreatedAt = Instant.now().minusSeconds(900);
        String failureReason = "Temporary failure - retry needed";
        int retryCount = 1;

        Map<String, String> headers = new HashMap<>();
        headers.put("retryable", "true");
        headers.put("maxRetries", "5");

        // Move message to dead letter queue
        dlqManager.moveToDeadLetterQueue(originalTable, originalId, topic, testMessage, 
                                       originalCreatedAt, failureReason, retryCount, 
                                       headers, "reprocess-correlation-789", "reprocess-group");

        // Get the dead letter message ID
        long deadLetterMessageId;
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String sql = "SELECT id FROM dead_letter_queue WHERE topic = ? ORDER BY failed_at DESC LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                ResultSet rs = stmt.executeQuery();
                assertTrue(rs.next(), "Should find the dead letter message");
                deadLetterMessageId = rs.getLong("id");
            }
        }

        // Reprocess the message
        boolean reprocessed = dlqManager.reprocessDeadLetterMessage(deadLetterMessageId, "Manual reprocessing test");
        assertTrue(reprocessed, "Message should be reprocessed successfully");

        logger.info("✅ Dead letter message reprocessing with JSONB data successful");
    }

    private void initializeSchema(PostgreSQLContainer<?> postgres) {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // Create dead_letter_queue table with JSONB columns
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS dead_letter_queue (
                    id BIGSERIAL PRIMARY KEY,
                    original_table VARCHAR(255) NOT NULL,
                    original_id BIGINT NOT NULL,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    original_created_at TIMESTAMPTZ NOT NULL,
                    failed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    failure_reason TEXT,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    headers JSONB NOT NULL DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255)
                )
                """;

            // Create queue_messages table for reprocessing tests
            String createQueueTableSql = """
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    status VARCHAR(50) NOT NULL DEFAULT 'AVAILABLE',
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    headers JSONB NOT NULL DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """;

            // Create outbox table for reprocessing tests
            String createOutboxTableSql = """
                CREATE TABLE IF NOT EXISTS outbox (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    headers JSONB NOT NULL DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """;

            try (PreparedStatement stmt1 = conn.prepareStatement(createTableSql);
                 PreparedStatement stmt2 = conn.prepareStatement(createQueueTableSql);
                 PreparedStatement stmt3 = conn.prepareStatement(createOutboxTableSql)) {
                stmt1.execute();
                stmt2.execute();
                stmt3.execute();
                logger.info("✅ Dead letter queue schema initialized successfully");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    // Test data class
    public static class TestOrder {
        public String orderId;
        public String status;
        public Double amount;

        public TestOrder() {}

        public TestOrder(String orderId, String status, Double amount) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
        }
    }
}
