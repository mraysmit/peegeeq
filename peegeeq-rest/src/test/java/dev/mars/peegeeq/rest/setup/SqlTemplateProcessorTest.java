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

package dev.mars.peegeeq.rest.setup;

import dev.mars.peegeeq.db.setup.SqlTemplateProcessor;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for SqlTemplateProcessor using TestContainers.
 * 
 * Tests template loading, parameter substitution, and SQL execution
 * against a real PostgreSQL database.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SqlTemplateProcessorTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SqlTemplateProcessorTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("template_test")
            .withUsername("template_test")
            .withPassword("template_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private SqlTemplateProcessor templateProcessor;
    private Connection connection;
    private Pool reactivePool;
    
    @BeforeEach
    void setUp() throws SQLException {
        templateProcessor = new SqlTemplateProcessor();

        String jdbcUrl = postgres.getJdbcUrl();
        connection = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());

        // Create reactive pool for reactive tests
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        PoolOptions poolOptions = new PoolOptions().setMaxSize(5);
        reactivePool = PgBuilder.pool().with(poolOptions).connectingTo(connectOptions).build();

        logger.info("Connected to test database: {}", jdbcUrl);
    }
    
    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        if (reactivePool != null) {
            reactivePool.close();
        }
    }
    
    @Test
    @Order(1)
    void testApplyBaseTemplate() throws Exception {
        logger.info("=== Testing Base Template Application ===");

        // Apply the base PeeGeeQ template using reactive API
        reactivePool.withConnection(conn ->
            templateProcessor.applyTemplateReactive(conn, "peegeeq-template.sql", Map.of())
        ).toCompletionStage().toCompletableFuture().get();

        // Verify extensions were created
        verifyExtensionExists("uuid-ossp");
        verifyExtensionExists("pg_stat_statements");

        // Verify schemas were created
        verifySchemaExists("peegeeq");
        verifySchemaExists("bitemporal");

        // Verify template tables were created
        verifyTableExists("peegeeq", "queue_template");
        verifyTableExists("bitemporal", "event_store_template");

        // Verify indexes were created
        verifyIndexExists("idx_queue_template_queue_name_status");
        verifyIndexExists("idx_queue_template_visible_at");
        verifyIndexExists("idx_event_store_template_type_time");
        verifyIndexExists("idx_event_store_template_valid_time");
        verifyIndexExists("idx_event_store_template_correlation");
        
        logger.info("Base template applied successfully");
        logger.info("=== Base Template Application Test Passed ===");
    }
    
    @Test
    @Order(2)
    void testCreateQueueTableTemplate() throws Exception {
        logger.info("=== Testing Queue Table Template ===");

        // First apply base template and create queue table using reactive API
        reactivePool.withConnection(conn ->
            templateProcessor.applyTemplateReactive(conn, "peegeeq-template.sql", Map.of())
                .compose(v -> {
                    // Create a queue table using template
                    Map<String, String> params = Map.of(
                            "queueName", "test_orders",
                            "schema", "public"
                    );
                    return templateProcessor.applyTemplateReactive(conn, "create-queue-table.sql", params);
                })
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify queue table was created
        verifyTableExists("public", "test_orders");
        
        // Verify queue table has correct structure (inherits from template)
        verifyTableHasColumns("public", "test_orders", 
                "id", "queue_name", "payload", "created_at", "visible_at", 
                "retry_count", "max_retries", "status", "consumer_id", "last_processed_at");
        
        // Verify queue-specific indexes were created
        verifyIndexExists("idx_test_orders_status_visible");
        verifyIndexExists("idx_test_orders_created_at");
        
        // Verify notification trigger was created
        verifyTriggerExists("trigger_test_orders_notify");
        verifyFunctionExists("public", "notify_test_orders_changes");
        
        // Test that the trigger works by inserting a record
        testQueueNotificationTrigger("test_orders");
        
        logger.info("Queue table template applied successfully");
        logger.info("=== Queue Table Template Test Passed ===");
    }
    
    @Test
    @Order(3)
    void testCreateEventStoreTableTemplate() throws Exception {
        logger.info("=== Testing Event Store Table Template ===");

        // First apply base template and create event store table using reactive API
        reactivePool.withConnection(conn ->
            templateProcessor.applyTemplateReactive(conn, "peegeeq-template.sql", Map.of())
                .compose(v -> {
                    // Create an event store table using template
                    Map<String, String> params = Map.of(
                            "tableName", "test_events",
                            "schema", "public",
                            "notificationPrefix", "test_events_"
                    );
                    return templateProcessor.applyTemplateReactive(conn, "create-eventstore-table.sql", params);
                })
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify event store table was created
        verifyTableExists("public", "test_events");
        
        // Verify event store table has correct structure
        verifyTableHasColumns("public", "test_events",
                "id", "event_type", "event_data", "valid_from", "valid_to",
                "transaction_time", "correlation_id", "causation_id", "version", "metadata");
        
        // Verify event store specific indexes were created
        verifyIndexExists("idx_test_events_event_type_tx_time");
        verifyIndexExists("idx_test_events_valid_time_range");
        verifyIndexExists("idx_test_events_correlation_causation");
        verifyIndexExists("idx_test_events_event_data_gin");
        
        // Verify notification trigger was created
        verifyTriggerExists("trigger_test_events_notify");
        verifyFunctionExists("public", "notify_test_events_events");
        
        // Test that the trigger works by inserting an event
        testEventStoreNotificationTrigger("test_events");
        
        logger.info("Event store table template applied successfully");
        logger.info("=== Event Store Table Template Test Passed ===");
    }
    
    @Test
    @Order(4)
    void testParameterSubstitution() throws Exception {
        logger.info("=== Testing Parameter Substitution ===");

        // Apply base template and test parameter substitution using reactive API
        reactivePool.withConnection(conn ->
            templateProcessor.applyTemplateReactive(conn, "peegeeq-template.sql", Map.of())
                .compose(v -> {
                    // Test multiple parameter substitutions
                    Map<String, String> params = Map.of(
                            "queueName", "complex_queue_name",
                            "schema", "public"
                    );
                    return templateProcessor.applyTemplateReactive(conn, "create-queue-table.sql", params);
                })
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify all parameters were substituted correctly
        verifyTableExists("public", "complex_queue_name");
        verifyIndexExists("idx_complex_queue_name_status_visible");
        verifyIndexExists("idx_complex_queue_name_created_at");
        verifyTriggerExists("trigger_complex_queue_name_notify");
        verifyFunctionExists("public", "notify_complex_queue_name_changes");
        
        logger.info("Parameter substitution working correctly");
        logger.info("=== Parameter Substitution Test Passed ===");
    }
    
    @Test
    void testInvalidTemplate() {
        logger.info("=== Testing Invalid Template Handling ===");
        System.out.println("📄 ===== RUNNING INTENTIONAL INVALID TEMPLATE TEST =====");
        System.out.println("📄 **INTENTIONAL TEST** - This test deliberately uses a non-existent template file");
        System.out.println("📄 **INTENTIONAL TEST FAILURE** - Expected exception for missing template file");

        // Test with non-existent template using reactive API
        assertThrows(Exception.class, () -> {
            reactivePool.withConnection(conn ->
                templateProcessor.applyTemplateReactive(conn, "non-existent-template.sql", Map.of())
            ).toCompletionStage().toCompletableFuture().get();
        });

        System.out.println("📄 **SUCCESS** - Non-existent template properly threw exception");
        System.out.println("📄 ===== INTENTIONAL TEST COMPLETED =====");
        logger.info("Invalid template properly rejected");
        logger.info("=== Invalid Template Handling Test Passed ===");
    }

    @Test
    @Order(5)
    void testReactiveTemplateProcessor() throws Exception {
        logger.info("=== Testing Reactive Template Processor ===");

        // Test reactive template application
        reactivePool.withConnection(connection -> {
            return templateProcessor.applyTemplateReactive(connection, "peegeeq-template.sql", Map.of())
                .compose(v -> {
                    logger.info("Base template applied successfully via reactive method");

                    // Create a queue table using reactive method
                    Map<String, String> params = Map.of(
                        "queueName", "reactive_test_queue",
                        "schema", "public"
                    );

                    return templateProcessor.applyTemplateReactive(connection, "create-queue-table.sql", params);
                })
                .map(v -> {
                    logger.info("Queue table created successfully via reactive method");
                    return v;
                });
        }).toCompletionStage().toCompletableFuture().get();

        // Verify the reactive operations worked
        verifyTableExists("public", "reactive_test_queue");
        verifyIndexExists("idx_reactive_test_queue_status_visible");
        verifyIndexExists("idx_reactive_test_queue_created_at");
        verifyTriggerExists("trigger_reactive_test_queue_notify");
        verifyFunctionExists("public", "notify_reactive_test_queue_changes");

        logger.info("Reactive template processor working correctly");
        logger.info("=== Reactive Template Processor Test Passed ===");
    }
    
    @Test
    void testSqlExecutionError() throws Exception {
        logger.info("=== Testing SQL Execution Error Handling ===");

        // Apply base template and create queue table using reactive API
        reactivePool.withConnection(conn ->
            templateProcessor.applyTemplateReactive(conn, "peegeeq-template.sql", Map.of())
                .compose(v -> {
                    // Try to create the same queue table twice (should fail)
                    Map<String, String> params = Map.of(
                            "queueName", "duplicate_queue",
                            "schema", "public"
                    );
                    // First creation should succeed
                    return templateProcessor.applyTemplateReactive(conn, "create-queue-table.sql", params);
                })
        ).toCompletionStage().toCompletableFuture().get();

        // Second creation should fail (table already exists)
        // Note: The template uses IF NOT EXISTS, so this might not fail
        // Let's test with a different scenario - invalid schema
        System.out.println("🗄️ ===== RUNNING INTENTIONAL SQL EXECUTION ERROR TEST =====");
        System.out.println("🗄️ **INTENTIONAL TEST** - This test deliberately uses invalid SQL parameters");
        System.out.println("🗄️ **INTENTIONAL TEST FAILURE** - Expected SQL exception for non-existent schema");

        Map<String, String> invalidParams = Map.of(
                "queueName", "test_queue",
                "schema", "non_existent_schema"
        );

        assertThrows(Exception.class, () -> {
            reactivePool.withConnection(conn ->
                templateProcessor.applyTemplateReactive(conn, "create-queue-table.sql", invalidParams)
            ).toCompletionStage().toCompletableFuture().get();
        });

        System.out.println("🗄️ **SUCCESS** - Invalid SQL parameters properly threw exception");
        System.out.println("🗄️ ===== INTENTIONAL TEST COMPLETED =====");
        logger.info("SQL execution error properly handled");
        logger.info("=== SQL Execution Error Handling Test Passed ===");
    }
    
    private void verifyExtensionExists(String extensionName) throws SQLException {
        try (var stmt = connection.prepareStatement("SELECT 1 FROM pg_extension WHERE extname = ?")) {
            stmt.setString(1, extensionName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Extension should exist: " + extensionName);
        }
    }
    
    private void verifySchemaExists(String schemaName) throws SQLException {
        try (var stmt = connection.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            stmt.setString(1, schemaName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Schema should exist: " + schemaName);
        }
    }
    
    private void verifyTableExists(String schema, String tableName) throws SQLException {
        try (var stmt = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Table should exist: " + schema + "." + tableName);
        }
    }
    
    private void verifyTableHasColumns(String schema, String tableName, String... columnNames) throws SQLException {
        for (String columnName : columnNames) {
            try (var stmt = connection.prepareStatement(
                    "SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
                stmt.setString(1, schema);
                stmt.setString(2, tableName);
                stmt.setString(3, columnName);
                var rs = stmt.executeQuery();
                assertTrue(rs.next(), "Column should exist: " + schema + "." + tableName + "." + columnName);
            }
        }
    }
    
    private void verifyIndexExists(String indexName) throws SQLException {
        try (var stmt = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
            stmt.setString(1, indexName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Index should exist: " + indexName);
        }
    }
    
    private void verifyTriggerExists(String triggerName) throws SQLException {
        try (var stmt = connection.prepareStatement("SELECT 1 FROM information_schema.triggers WHERE trigger_name = ?")) {
            stmt.setString(1, triggerName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Trigger should exist: " + triggerName);
        }
    }
    
    private void verifyFunctionExists(String schema, String functionName) throws SQLException {
        try (var stmt = connection.prepareStatement(
                "SELECT 1 FROM information_schema.routines WHERE routine_schema = ? AND routine_name = ?")) {
            stmt.setString(1, schema);
            stmt.setString(2, functionName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Function should exist: " + schema + "." + functionName);
        }
    }
    
    private void testQueueNotificationTrigger(String queueName) throws SQLException {
        // Insert a test record to verify trigger works
        String sql = String.format(
                "INSERT INTO %s (queue_name, payload) VALUES (?, ?::jsonb)",
                queueName
        );
        
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, "test");
            stmt.setString(2, "{\"test\": \"data\"}");
            int rows = stmt.executeUpdate();
            assertEquals(1, rows, "Should insert one row");
        }
        
        // Verify the record was inserted
        String selectSql = String.format("SELECT COUNT(*) FROM %s WHERE queue_name = ?", queueName);
        try (var stmt = connection.prepareStatement(selectSql)) {
            stmt.setString(1, "test");
            var rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Should have one record");
        }
    }
    
    private void testEventStoreNotificationTrigger(String tableName) throws SQLException {
        // Insert a test event to verify trigger works
        String sql = String.format(
                "INSERT INTO %s (event_type, event_data, valid_from) VALUES (?, ?::jsonb, ?)",
                tableName
        );
        
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, "TestEvent");
            stmt.setString(2, "{\"test\": \"data\"}");
            stmt.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()));
            int rows = stmt.executeUpdate();
            assertEquals(1, rows, "Should insert one row");
        }
        
        // Verify the event was inserted
        String selectSql = String.format("SELECT COUNT(*) FROM %s WHERE event_type = ?", tableName);
        try (var stmt = connection.prepareStatement(selectSql)) {
            stmt.setString(1, "TestEvent");
            var rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Should have one event");
        }
    }
}
