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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import dev.mars.peegeeq.test.categories.TestCategories;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that JSONB conversion is working correctly for Bi-Temporal Event Store.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonbConversionValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(JsonbConversionValidationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_bitemporal_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        return container;
    }

    private Vertx vertx;
    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        // Set system properties for test configuration
        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize schema before starting PeeGeeQ Manager
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("jsonb-bitemporal-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
            .compose(v -> initializeSchema())
                .map(v -> {
                    eventStore = new PgBiTemporalEventStore<>(vertx, manager, TestEvent.class, "test_events", new ObjectMapper());
                    logger.info("Test setup complete - Bi-temporal event store ready for JSONB validation");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> closeStoreFuture = eventStore != null ? eventStore.closeFuture() : Future.succeededFuture();
        Future<Void> closeManagerFuture = manager != null ? manager.closeReactive() : Future.succeededFuture();

        closeStoreFuture
                .recover(error -> Future.<Void>succeededFuture())
                .compose(v -> closeManagerFuture.recover(error -> Future.<Void>succeededFuture()))
                .onSuccess(v -> {
                    restoreTestProperties();
                    logger.info("Test cleanup complete");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    private void setTestProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void restoreTestProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        originalProperties.clear();
    }

    @Test
    @Order(1)
    void testSimpleStringPayloadStoredAsJsonb(VertxTestContext testContext) {
        logger.info("🧪 Testing simple string payload JSONB storage...");

        TestEvent testEvent = new TestEvent("ORDER-001", "ACTIVE", 100.0);
        String eventType = "test.simple.message";
        Instant validTime = Instant.now();

        // Append event
        eventStore.appendBuilder().eventType(eventType).payload(testEvent).validTime(validTime).execute()
                .compose(event -> querySingleRow(
                        """
                        SELECT jsonb_typeof(payload) AS payload_type,
                               payload->>'orderId' AS extracted_value
                        FROM test_events
                        WHERE event_type = $1
                        ORDER BY transaction_time DESC
                        LIMIT 1
                        """,
                        Tuple.of(eventType))
                        .map(row -> Map.entry(event, row)))
                .onSuccess(result -> testContext.verify(() -> {
                    BiTemporalEvent<TestEvent> event = result.getKey();
                    Row row = result.getValue();
                    assertNotNull(event);
                    assertEquals(testEvent.orderId, event.getPayload().orderId);
                    assertEquals("object", row.getString("payload_type"), "Payload should be stored as JSONB object, not string");
                    assertEquals(testEvent.orderId, row.getString("extracted_value"), "Should be able to extract orderId using JSON operators");
                    logger.info("Event appended successfully: {}", event.getEventId());
                    logger.info("JSONB validation successful - payload stored as object with type: {}", row.getString("payload_type"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    void testComplexObjectPayloadStoredAsJsonb(VertxTestContext testContext) {
        logger.info("🧪 Testing complex object payload JSONB storage...");

        // Create a complex test object
        TestEvent testEvent = new TestEvent("ORDER-JSONB-001", "PENDING", 1500.00);
        String eventType = "order.created";
        Instant validTime = Instant.now();

        // Append event
        eventStore.appendBuilder().eventType(eventType).payload(testEvent).validTime(validTime).execute()
                .compose(event -> querySingleRow(
                        """
                        SELECT jsonb_typeof(payload) AS payload_type,
                               payload->>'orderId' AS extracted_order_id,
                               payload->>'status' AS extracted_status
                        FROM test_events
                        WHERE event_type = $1
                        ORDER BY transaction_time DESC
                        LIMIT 1
                        """,
                        Tuple.of(eventType))
                        .map(row -> Map.entry(event, row)))
                .onSuccess(result -> testContext.verify(() -> {
                    BiTemporalEvent<TestEvent> event = result.getKey();
                    Row row = result.getValue();
                    assertNotNull(event);
                    assertEquals(testEvent.orderId, event.getPayload().orderId);
                    assertEquals("object", row.getString("payload_type"), "Payload should be stored as JSONB object");
                    assertEquals(testEvent.orderId, row.getString("extracted_order_id"), "Should extract orderId using JSON operators");
                    assertEquals(testEvent.status, row.getString("extracted_status"), "Should extract status using JSON operators");
                    logger.info("Complex event appended successfully: {}", event.getEventId());
                    logger.info("Complex JSONB validation successful - extracted orderId: {}, status: {}",
                            row.getString("extracted_order_id"), row.getString("extracted_status"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    void testHeadersStoredAsJsonb(VertxTestContext testContext) {
        logger.info("🧪 Testing headers JSONB storage...");

        TestEvent testEvent = new TestEvent("ORDER-HEADERS-001", "PROCESSING", 750.0);
        String eventType = "test.headers.message";
        Instant validTime = Instant.now();

        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", "test-correlation-123");
        headers.put("source", "jsonb-test");
        headers.put("priority", "high");

        // Append event with headers
        eventStore.appendBuilder().eventType(eventType).payload(testEvent).validTime(validTime).headers(headers).execute()
                .compose(event -> querySingleRow(
                        """
                        SELECT jsonb_typeof(headers) AS headers_type,
                               headers->>'correlationId' AS extracted_correlation_id,
                               headers->>'source' AS extracted_source
                        FROM test_events
                        WHERE event_type = $1
                        ORDER BY transaction_time DESC
                        LIMIT 1
                        """,
                        Tuple.of(eventType))
                        .map(row -> Map.entry(event, row)))
                .onSuccess(result -> testContext.verify(() -> {
                    BiTemporalEvent<TestEvent> event = result.getKey();
                    Row row = result.getValue();
                    assertNotNull(event);
                    assertEquals(testEvent.orderId, event.getPayload().orderId);
                    assertEquals("object", row.getString("headers_type"), "Headers should be stored as JSONB object");
                    assertEquals("test-correlation-123", row.getString("extracted_correlation_id"), "Should extract correlationId using JSON operators");
                    assertEquals("jsonb-test", row.getString("extracted_source"), "Should extract source using JSON operators");
                    logger.info("Event with headers appended successfully: {}", event.getEventId());
                    logger.info("Headers JSONB validation successful - extracted correlationId: {}, source: {}",
                            row.getString("extracted_correlation_id"), row.getString("extracted_source"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Void> initializeSchema() {
        return manager.getPool().query(
                        """
                        CREATE TABLE IF NOT EXISTS test_events (
                            event_id VARCHAR(255) PRIMARY KEY,
                            event_type VARCHAR(255) NOT NULL,
                            valid_time TIMESTAMPTZ NOT NULL,
                            transaction_time TIMESTAMPTZ NOT NULL,
                            payload JSONB NOT NULL,
                            headers JSONB NOT NULL DEFAULT '{}',
                            version BIGINT NOT NULL DEFAULT 1,
                            correlation_id VARCHAR(255),
                            causation_id VARCHAR(255),
                            aggregate_id VARCHAR(255),
                            is_correction BOOLEAN NOT NULL DEFAULT FALSE,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                        )
                        """)
                .execute()
                .compose(rows -> manager.getPool().query("ALTER TABLE test_events ADD COLUMN IF NOT EXISTS causation_id VARCHAR(255)").execute())
                .map(rows -> {
                    logger.info("Bi-temporal event log table created successfully");
                    return (Void) null;
                });
    }

    private Future<Row> querySingleRow(String sql, Tuple params) {
        return manager.getPool()
                .preparedQuery(sql)
                .execute(params)
                .compose(rows -> {
                    Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
                    return row != null ? Future.succeededFuture(row) : Future.failedFuture("Expected query to return a row");
                });
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


