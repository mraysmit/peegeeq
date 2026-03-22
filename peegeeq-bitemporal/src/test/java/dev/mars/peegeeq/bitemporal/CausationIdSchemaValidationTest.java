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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRITICAL TEST: Validates that causation_id column exists in bitemporal_event_log table
 * and that the trigger function can successfully reference it.
 * 
 * This test ensures the test schema (PeeGeeQTestSchemaInitializer) matches the production
 * schema created by Flyway migrations (V001 + V012).
 * 
 * BACKGROUND: The causation_id feature was added in migration V012 but the trigger function
 * in V001 already referenced it, creating a schema mismatch between test and production.
 * This test prevents regression by validating the complete schema including triggers.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Testcontainers
@DisplayName("Causation ID Schema Validation - CRITICAL for Production Parity")
public class CausationIdSchemaValidationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(CausationIdSchemaValidationTest.class);
    
    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("causation_id_test_" + System.currentTimeMillis());
        container.withUsername("peegeeq");
        container.withPassword("peegeeq_test_password");
        return container;
    }
    
    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
    
    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("🧪 Setting up CausationIdSchemaValidationTest");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating bitemporal schema using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);
        logger.info("Bitemporal schema created successfully");

        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ Manager
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        peeGeeQManager.start()
                .map(v -> {
                    BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(peeGeeQManager);
                    Class<Map<String, Object>> mapClass = mapClass();
                    eventStore = (PgBiTemporalEventStore<Map<String, Object>>) factory.createEventStore(mapClass, "bitemporal_event_log");
                    logger.info("Setup completed successfully");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("🧹 Cleaning up CausationIdSchemaValidationTest");

        Future<Void> closeStoreFuture = eventStore != null ? eventStore.closeFuture() : Future.succeededFuture();
        Future<Void> closeManagerFuture = peeGeeQManager != null ? peeGeeQManager.closeReactive() : Future.succeededFuture();

        closeStoreFuture
                .recover(error -> Future.<Void>succeededFuture())
                .compose(v -> closeManagerFuture.recover(error -> Future.<Void>succeededFuture()))
                .onSuccess(v -> {
                    System.clearProperty("peegeeq.database.host");
                    System.clearProperty("peegeeq.database.port");
                    System.clearProperty("peegeeq.database.name");
                    System.clearProperty("peegeeq.database.username");
                    System.clearProperty("peegeeq.database.password");
                    logger.info("Cleanup completed successfully");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("CRITICAL: Validate causation_id column exists in bitemporal_event_log table")
    void testCausationIdColumnExists(VertxTestContext testContext) {
        logger.info("🧪 Validating causation_id column exists in test schema");

        querySingleRow(
                """
                SELECT column_name, data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_name = $1
                  AND column_name = $2
                """,
                Tuple.of("bitemporal_event_log", "causation_id"))
                .onSuccess(row -> testContext.verify(() -> {
                    assertEquals("causation_id", row.getString("column_name"), "Column name should be causation_id");
                    assertEquals("character varying", row.getString("data_type"), "Data type should be VARCHAR");
                    assertEquals(255, row.getInteger("character_maximum_length"), "Max length should be 255");
                    logger.info("causation_id column exists with correct schema: VARCHAR(255)");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("CRITICAL: Validate trigger function references causation_id without error")
    void testTriggerFunctionCanReferenceCausationId(VertxTestContext testContext) {
        logger.info("🧪 Testing that notify_bitemporal_event trigger can execute with causation_id");

        // Test data with causation_id
        String eventType = "causation.test.event";
        Map<String, Object> payload = Map.of("test", "data", "timestamp", System.currentTimeMillis());
        Instant validTime = Instant.now();
        String correlationId = "CORR-" + System.currentTimeMillis();
        String causationId = "CAUSE-" + System.currentTimeMillis();
        String aggregateId = "AGG-" + System.currentTimeMillis();

        // This will INSERT into bitemporal_event_log which will trigger notify_bitemporal_event()
        // If the trigger references causation_id and the column doesn't exist, this will fail with:
        // "ERROR: record "new" has no field "causation_id""
        eventStore.appendBuilder().eventType(eventType).payload(payload).validTime(validTime)
                .headers(Map.of("test-header", "value")).correlationId(correlationId)
                .causationId(causationId).aggregateId(aggregateId).execute()
                .compose(event -> querySingleRow(
                        "SELECT causation_id FROM bitemporal_event_log WHERE event_id = $1",
                        Tuple.of(event.getEventId()))
                        .map(row -> Map.entry(event, row.getString("causation_id"))))
                .onSuccess(result -> testContext.verify(() -> {
                    BiTemporalEvent<Map<String, Object>> event = result.getKey();
                    String storedCausationId = result.getValue();
                    assertNotNull(event, "Event should be successfully appended");
                    assertEquals(causationId, event.getCausationId(), "Causation ID should match");
                    assertEquals(causationId, storedCausationId, "Stored causation_id should match");
                    logger.info("Trigger executed successfully and causation_id was stored: {}", storedCausationId);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("CRITICAL: Validate appendWithTransaction includes causation_id in interface")
    void testAppendWithTransactionMethodSignature(VertxTestContext testContext) {
        logger.info("🧪 Validating appendWithTransaction method signature includes causation_id");

        // This test ensures the EventStore interface has the appendWithTransaction method
        // with causation_id parameter - compilation proves the method signature exists
        
        // Test with TransactionPropagation (modern method)
        String eventType = "signature.test.event";
        Map<String, Object> payload = Map.of("test", "signature");
        Instant validTime = Instant.now();
        String causationId = "SIGNATURE-TEST-" + System.currentTimeMillis();

        eventStore.appendWithTransaction(
                        eventType,
                        payload,
                        validTime,
                        Map.of(),
                        "CORR-123",
                        causationId,
                        "AGG-123",
                        io.vertx.sqlclient.TransactionPropagation.CONTEXT)
                .onSuccess(event -> testContext.verify(() -> {
                    assertNotNull(event, "Event should be successfully appended");
                    assertEquals(causationId, event.getCausationId(), "Causation ID should match");
                    logger.info("appendWithTransaction method signature validated with causation_id parameter");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("CRITICAL: Validate test schema matches production schema columns")
    void testSchemaColumnParity(VertxTestContext testContext) {
        logger.info("🧪 Validating test schema has all expected columns matching production");

        listColumnNames("bitemporal_event_log")
                .onSuccess(actualColumns -> testContext.verify(() -> {
                    String[] expectedColumns = {
                            "id", "event_id", "event_type", "valid_time", "transaction_time",
                            "payload", "headers", "version", "previous_version_id", "is_correction",
                            "correction_reason", "correlation_id", "causation_id", "aggregate_id", "created_at"
                    };
                    String actualColumnsStr = String.join(", ", actualColumns);
                    logger.info("Actual columns: {}", actualColumnsStr);
                    for (String expectedColumn : expectedColumns) {
                        assertTrue(
                                actualColumns.contains(expectedColumn),
                                "Test schema MUST have column '" + expectedColumn + "' to match production. Actual columns: " + actualColumnsStr
                        );
                    }
                    logger.info("Test schema has all required columns matching production");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Row> querySingleRow(String sql, Tuple params) {
        return peeGeeQManager.getPool()
                .preparedQuery(sql)
                .execute(params)
                .compose(rows -> {
                    Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
                    return row != null ? Future.succeededFuture(row) : Future.failedFuture("Expected query to return a row");
                });
    }

    private Future<List<String>> listColumnNames(String tableName) {
        return peeGeeQManager.getPool()
                .preparedQuery(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_name = $1
                        ORDER BY ordinal_position
                        """)
                .execute(Tuple.of(tableName))
                .map(rows -> {
                    List<String> columns = new ArrayList<>();
                    for (Row row : rows) {
                        columns.add(row.getString("column_name"));
                    }
                    return columns;
                });
    }
}


