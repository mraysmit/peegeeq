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
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
@Testcontainers
@DisplayName("Causation ID Schema Validation - CRITICAL for Production Parity")
public class CausationIdSchemaValidationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(CausationIdSchemaValidationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("causation_id_test_" + System.currentTimeMillis())
            .withUsername("peegeeq")
            .withPassword("peegeeq_test_password");
    
    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;
    
    @BeforeEach
    void setUp() throws Exception {
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
        peeGeeQManager.start();

        // Create the bitemporal event store
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(peeGeeQManager);
        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = (PgBiTemporalEventStore<Map<String, Object>>) factory.createEventStore(mapClass);

        logger.info("✅ Setup completed successfully");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up CausationIdSchemaValidationTest");

        if (eventStore != null) {
            eventStore.close();
        }

        if (peeGeeQManager != null) {
            peeGeeQManager.close();
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        logger.info("✅ Cleanup completed successfully");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate causation_id column exists in bitemporal_event_log table")
    void testCausationIdColumnExists() throws Exception {
        logger.info("🧪 Validating causation_id column exists in test schema");

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            
            // Query information_schema to check if causation_id column exists
            ResultSet rs = stmt.executeQuery("""
                SELECT column_name, data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_name = 'bitemporal_event_log'
                AND column_name = 'causation_id'
                """);
            
            assertTrue(rs.next(), "causation_id column MUST exist in bitemporal_event_log table");
            
            String columnName = rs.getString("column_name");
            String dataType = rs.getString("data_type");
            Integer maxLength = rs.getInt("character_maximum_length");
            
            assertEquals("causation_id", columnName, "Column name should be causation_id");
            assertEquals("character varying", dataType, "Data type should be VARCHAR");
            assertEquals(255, maxLength, "Max length should be 255");
            
            logger.info("✅ causation_id column exists with correct schema: VARCHAR(255)");
        }
    }
    
    @Test
    @DisplayName("CRITICAL: Validate trigger function references causation_id without error")
    void testTriggerFunctionCanReferenceCausationId() throws Exception {
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
        BiTemporalEvent<Map<String, Object>> event = eventStore.append(
            eventType, 
            payload, 
            validTime,
            Map.of("test-header", "value"),
            correlationId,
            causationId,
            aggregateId
        ).get(10, TimeUnit.SECONDS);

        assertNotNull(event, "Event should be successfully appended");
        assertEquals(causationId, event.getCausationId(), "Causation ID should match");
        
        // Validate causation_id was actually stored in the database
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.prepareStatement(
                 "SELECT causation_id FROM bitemporal_event_log WHERE event_id = ?")) {
            
            stmt.setString(1, event.getEventId());
            ResultSet rs = stmt.executeQuery();
            
            assertTrue(rs.next(), "Event should exist in database");
            String storedCausationId = rs.getString("causation_id");
            assertEquals(causationId, storedCausationId, "Stored causation_id should match");
            
            logger.info("✅ Trigger executed successfully and causation_id was stored: {}", storedCausationId);
        }
    }
    
    @Test
    @DisplayName("CRITICAL: Validate appendWithTransaction includes causation_id in interface")
    void testAppendWithTransactionMethodSignature() throws Exception {
        logger.info("🧪 Validating appendWithTransaction method signature includes causation_id");

        // This test ensures the EventStore interface has the appendWithTransaction method
        // with causation_id parameter - compilation proves the method signature exists
        
        // Test with TransactionPropagation (modern method)
        String eventType = "signature.test.event";
        Map<String, Object> payload = Map.of("test", "signature");
        Instant validTime = Instant.now();
        String causationId = "SIGNATURE-TEST-" + System.currentTimeMillis();

        BiTemporalEvent<Map<String, Object>> event = eventStore.appendWithTransaction(
            eventType,
            payload,
            validTime,
            Map.of(),
            "CORR-123",
            causationId,
            "AGG-123",
            io.vertx.sqlclient.TransactionPropagation.CONTEXT
        ).get(10, TimeUnit.SECONDS);

        assertNotNull(event, "Event should be successfully appended");
        assertEquals(causationId, event.getCausationId(), "Causation ID should match");
        
        logger.info("✅ appendWithTransaction method signature validated with causation_id parameter");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate test schema matches production schema columns")
    void testSchemaColumnParity() throws Exception {
        logger.info("🧪 Validating test schema has all expected columns matching production");

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            
            // Get all columns from bitemporal_event_log table
            ResultSet rs = stmt.executeQuery("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'bitemporal_event_log'
                ORDER BY ordinal_position
                """);
            
            // Expected columns based on V001 + V012 migrations
            String[] expectedColumns = {
                "id", "event_id", "event_type", "valid_time", "transaction_time",
                "payload", "headers", "version", "previous_version_id", "is_correction",
                "correction_reason", "correlation_id", "causation_id", "aggregate_id", "created_at"
            };
            
            StringBuilder actualColumns = new StringBuilder();
            while (rs.next()) {
                if (actualColumns.length() > 0) {
                    actualColumns.append(", ");
                }
                actualColumns.append(rs.getString("column_name"));
            }
            
            String actualColumnsStr = actualColumns.toString();
            logger.info("Actual columns: {}", actualColumnsStr);
            
            for (String expectedColumn : expectedColumns) {
                assertTrue(
                    actualColumnsStr.contains(expectedColumn),
                    "Test schema MUST have column '" + expectedColumn + "' to match production. " +
                    "Actual columns: " + actualColumnsStr
                );
            }
            
            logger.info("✅ Test schema has all required columns matching production");
        }
    }
}
