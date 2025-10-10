package dev.mars.peegeeq.bitemporal;

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
import dev.mars.peegeeq.api.BiTemporalEvent;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that JSONB conversion is working correctly for Bi-Temporal Event Store.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonbConversionValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(JsonbConversionValidationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_bitemporal_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test");

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestEvent> eventStore;

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
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("jsonb-bitemporal-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create event store
        eventStore = new PgBiTemporalEventStore<>(manager, TestEvent.class, new ObjectMapper());

        logger.info("✅ Test setup complete - Bi-temporal event store ready for JSONB validation");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (eventStore != null) {
            eventStore.close();
        }
        if (manager != null) {
            manager.close();
        }
        logger.info("✅ Test cleanup complete");
    }

    @Test
    @Order(1)
    void testSimpleStringPayloadStoredAsJsonb() throws Exception {
        logger.info("🧪 Testing simple string payload JSONB storage...");

        TestEvent testEvent = new TestEvent("ORDER-001", "ACTIVE", 100.0);
        String eventType = "test.simple.message";
        Instant validTime = Instant.now();

        // Append event
        CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append(eventType, testEvent, validTime);
        BiTemporalEvent<TestEvent> event = future.get();

        assertNotNull(event);
        assertEquals(testEvent.orderId, event.getPayload().orderId);
        logger.info("✅ Event appended successfully: {}", event.getEventId());

        // Validate JSONB storage using direct database query
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, jsonb_typeof(payload) as payload_type,
                       payload->>'orderId' as extracted_value
                FROM bitemporal_event_log
                WHERE event_type = ?
                ORDER BY transaction_time DESC
                LIMIT 1
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, eventType);
                ResultSet rs = stmt.executeQuery();

                assertTrue(rs.next(), "Should find the inserted event");

                // Verify it's stored as JSONB object, not string
                String payloadType = rs.getString("payload_type");
                assertEquals("object", payloadType, "Payload should be stored as JSONB object, not string");

                // Verify we can extract the value using JSON operators
                String extractedValue = rs.getString("extracted_value");
                assertEquals(testEvent.orderId, extractedValue, "Should be able to extract orderId using JSON operators");

                logger.info("✅ JSONB validation successful - payload stored as object with type: {}", payloadType);
            }
        }
    }

    @Test
    @Order(2)
    void testComplexObjectPayloadStoredAsJsonb() throws Exception {
        logger.info("🧪 Testing complex object payload JSONB storage...");

        // Create a complex test object
        TestEvent testEvent = new TestEvent("ORDER-JSONB-001", "PENDING", 1500.00);
        String eventType = "order.created";
        Instant validTime = Instant.now();

        // Append event
        CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append(eventType, testEvent, validTime);
        BiTemporalEvent<TestEvent> event = future.get();

        assertNotNull(event);
        assertEquals(testEvent.orderId, event.getPayload().orderId);
        logger.info("✅ Complex event appended successfully: {}", event.getEventId());

        // Validate JSONB storage using direct database query
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, jsonb_typeof(payload) as payload_type, 
                       payload->>'orderId' as extracted_order_id,
                       payload->>'status' as extracted_status
                FROM bitemporal_event_log 
                WHERE event_type = ? 
                ORDER BY transaction_time DESC 
                LIMIT 1
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, eventType);
                ResultSet rs = stmt.executeQuery();

                assertTrue(rs.next(), "Should find the inserted event");

                // Verify it's stored as JSONB object
                String payloadType = rs.getString("payload_type");
                assertEquals("object", payloadType, "Payload should be stored as JSONB object");

                // Verify we can extract complex object fields using JSON operators
                String extractedOrderId = rs.getString("extracted_order_id");
                String extractedStatus = rs.getString("extracted_status");
                assertEquals(testEvent.orderId, extractedOrderId, "Should extract orderId using JSON operators");
                assertEquals(testEvent.status, extractedStatus, "Should extract status using JSON operators");

                logger.info("✅ Complex JSONB validation successful - extracted orderId: {}, status: {}", 
                          extractedOrderId, extractedStatus);
            }
        }
    }

    @Test
    @Order(3)
    void testHeadersStoredAsJsonb() throws Exception {
        logger.info("🧪 Testing headers JSONB storage...");

        TestEvent testEvent = new TestEvent("ORDER-HEADERS-001", "PROCESSING", 750.0);
        String eventType = "test.headers.message";
        Instant validTime = Instant.now();

        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", "test-correlation-123");
        headers.put("source", "jsonb-test");
        headers.put("priority", "high");

        // Append event with headers
        CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append(eventType, testEvent, validTime, headers);
        BiTemporalEvent<TestEvent> event = future.get();

        assertNotNull(event);
        assertEquals(testEvent.orderId, event.getPayload().orderId);
        logger.info("✅ Event with headers appended successfully: {}", event.getEventId());

        // Validate headers JSONB storage using direct database query
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT headers, jsonb_typeof(headers) as headers_type,
                       headers->>'correlationId' as extracted_correlation_id,
                       headers->>'source' as extracted_source
                FROM bitemporal_event_log 
                WHERE event_type = ? 
                ORDER BY transaction_time DESC 
                LIMIT 1
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, eventType);
                ResultSet rs = stmt.executeQuery();

                assertTrue(rs.next(), "Should find the inserted event");

                // Verify headers are stored as JSONB object
                String headersType = rs.getString("headers_type");
                assertEquals("object", headersType, "Headers should be stored as JSONB object");

                // Verify we can extract header values using JSON operators
                String extractedCorrelationId = rs.getString("extracted_correlation_id");
                String extractedSource = rs.getString("extracted_source");
                assertEquals("test-correlation-123", extractedCorrelationId, "Should extract correlationId using JSON operators");
                assertEquals("jsonb-test", extractedSource, "Should extract source using JSON operators");

                logger.info("✅ Headers JSONB validation successful - extracted correlationId: {}, source: {}", 
                          extractedCorrelationId, extractedSource);
            }
        }
    }

    private void initializeSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // Create bitemporal_event_log table with JSONB columns
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS bitemporal_event_log (
                    event_id VARCHAR(255) PRIMARY KEY,
                    event_type VARCHAR(255) NOT NULL,
                    valid_time TIMESTAMPTZ NOT NULL,
                    transaction_time TIMESTAMPTZ NOT NULL,
                    payload JSONB NOT NULL,
                    headers JSONB NOT NULL DEFAULT '{}',
                    version BIGINT NOT NULL DEFAULT 1,
                    correlation_id VARCHAR(255),
                    aggregate_id VARCHAR(255),
                    is_correction BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """;

            try (PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
                stmt.execute();
                logger.info("✅ Bi-temporal event log table created successfully");
            }
        }
    }

    // Test data class
    public static class TestEvent {
        public String orderId;
        public String status;
        public Double amount;

        public TestEvent() {}

        public TestEvent(String orderId, String status, Double amount) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
        }
    }
}
