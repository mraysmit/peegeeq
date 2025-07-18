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

import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for DatabaseSetupService using TestContainers.
 * 
 * Tests the complete database setup functionality including:
 * - Template database creation
 * - Schema migrations
 * - Queue table creation
 * - Event store table creation
 * - Resource cleanup
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DatabaseSetupServiceIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseSetupServiceIntegrationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_setup_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private DatabaseSetupService setupService;
    private String testSetupId;
    
    @BeforeEach
    void setUp() {
        setupService = new PeeGeeQDatabaseSetupService();
        testSetupId = "test-setup-" + System.currentTimeMillis();
        logger.info("Starting test with setup ID: {}", testSetupId);
    }
    
    @Test
    @Order(1)
    void testCreateCompleteSetupWithQueuesAndEventStores() throws Exception {
        logger.info("=== Testing Complete Database Setup Creation ===");
        
        // Create database configuration
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("test_app_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();
        
        // Create queue configurations
        List<QueueConfig> queues = List.of(
                new QueueConfig.Builder()
                        .queueName("orders")
                        .maxRetries(3)
                        .visibilityTimeoutSeconds(30)
                        .deadLetterEnabled(true)
                        .build(),
                new QueueConfig.Builder()
                        .queueName("notifications")
                        .maxRetries(5)
                        .visibilityTimeoutSeconds(60)
                        .deadLetterEnabled(true)
                        .build()
        );
        
        // Create event store configurations
        List<EventStoreConfig> eventStores = List.of(
                new EventStoreConfig.Builder()
                        .eventStoreName("order-events")
                        .tableName("order_events")
                        .biTemporalEnabled(true)
                        .notificationPrefix("order_events_")
                        .build(),
                new EventStoreConfig.Builder()
                        .eventStoreName("user-events")
                        .tableName("user_events")
                        .biTemporalEnabled(true)
                        .notificationPrefix("user_events_")
                        .build()
        );
        
        // Create setup request
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                queues,
                eventStores,
                Map.of("test", "true")
        );
        
        // Execute setup
        CompletableFuture<DatabaseSetupResult> future = setupService.createCompleteSetup(request);
        DatabaseSetupResult result = future.get(60, TimeUnit.SECONDS);
        
        // Verify result
        assertNotNull(result);
        assertEquals(testSetupId, result.getSetupId());
        assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus());
        assertNotNull(result.getQueueFactories());
        assertNotNull(result.getEventStores());
        assertTrue(result.getCreatedAt() > 0);
        
        logger.info("Setup created successfully: {}", result.getSetupId());
        
        // Verify database was created
        verifyDatabaseExists(dbConfig.getDatabaseName());
        
        // Verify schemas and tables were created
        verifySchemaStructure(dbConfig);
        
        // Verify queue tables were created
        verifyQueueTables(dbConfig, queues);
        
        // Verify event store tables were created
        verifyEventStoreTables(dbConfig, eventStores);
        
        logger.info("=== Complete Database Setup Creation Test Passed ===");
    }
    
    @Test
    @Order(2)
    void testGetSetupStatus() throws Exception {
        logger.info("=== Testing Setup Status Retrieval ===");
        
        // First create a setup
        DatabaseSetupRequest request = createMinimalSetupRequest();
        setupService.createCompleteSetup(request).get(30, TimeUnit.SECONDS);
        
        // Get status
        CompletableFuture<DatabaseSetupStatus> future = setupService.getSetupStatus(testSetupId);
        DatabaseSetupStatus status = future.get(10, TimeUnit.SECONDS);
        
        assertNotNull(status);
        assertEquals(DatabaseSetupStatus.ACTIVE, status);
        
        logger.info("Setup status retrieved successfully: {}", status);
        logger.info("=== Setup Status Retrieval Test Passed ===");
    }
    
    @Test
    @Order(3)
    void testAddQueueToExistingSetup() throws Exception {
        logger.info("=== Testing Add Queue to Existing Setup ===");
        
        // First create a minimal setup
        DatabaseSetupRequest request = createMinimalSetupRequest();
        setupService.createCompleteSetup(request).get(30, TimeUnit.SECONDS);
        
        // Add a new queue
        QueueConfig newQueue = new QueueConfig.Builder()
                .queueName("payments")
                .maxRetries(5)
                .visibilityTimeoutSeconds(45)
                .deadLetterEnabled(true)
                .build();
        
        CompletableFuture<Void> future = setupService.addQueue(testSetupId, newQueue);
        future.get(30, TimeUnit.SECONDS);
        
        // Verify queue was added by checking database
        verifyQueueTables(request.getDatabaseConfig(), List.of(newQueue));
        
        logger.info("Queue added successfully to existing setup");
        logger.info("=== Add Queue to Existing Setup Test Passed ===");
    }
    
    @Test
    @Order(4)
    void testAddEventStoreToExistingSetup() throws Exception {
        logger.info("=== Testing Add Event Store to Existing Setup ===");
        
        // First create a minimal setup
        DatabaseSetupRequest request = createMinimalSetupRequest();
        setupService.createCompleteSetup(request).get(30, TimeUnit.SECONDS);
        
        // Add a new event store
        EventStoreConfig newEventStore = new EventStoreConfig.Builder()
                .eventStoreName("payment-events")
                .tableName("payment_events")
                .biTemporalEnabled(true)
                .notificationPrefix("payment_events_")
                .build();
        
        CompletableFuture<Void> future = setupService.addEventStore(testSetupId, newEventStore);
        future.get(30, TimeUnit.SECONDS);
        
        // Verify event store was added by checking database
        verifyEventStoreTables(request.getDatabaseConfig(), List.of(newEventStore));
        
        logger.info("Event store added successfully to existing setup");
        logger.info("=== Add Event Store to Existing Setup Test Passed ===");
    }
    
    @Test
    @Order(5)
    void testDestroySetup() throws Exception {
        logger.info("=== Testing Setup Destruction ===");
        
        // First create a setup
        DatabaseSetupRequest request = createMinimalSetupRequest();
        setupService.createCompleteSetup(request).get(30, TimeUnit.SECONDS);
        
        // Destroy the setup
        CompletableFuture<Void> future = setupService.destroySetup(testSetupId);
        future.get(30, TimeUnit.SECONDS);
        
        // Verify setup is no longer accessible
        assertThrows(Exception.class, () -> {
            setupService.getSetupStatus(testSetupId).get(10, TimeUnit.SECONDS);
        });
        
        logger.info("Setup destroyed successfully");
        logger.info("=== Setup Destruction Test Passed ===");
    }
    
    @Test
    void testInvalidSetupRequest() {
        logger.info("=== Testing Invalid Setup Request Handling ===");
        
        // Test with invalid database config
        DatabaseConfig invalidConfig = new DatabaseConfig.Builder()
                .host("invalid-host")
                .port(9999)
                .databaseName("invalid_db")
                .username("invalid_user")
                .password("invalid_pass")
                .build();
        
        DatabaseSetupRequest invalidRequest = new DatabaseSetupRequest(
                "invalid-setup",
                invalidConfig,
                List.of(),
                List.of(),
                Map.of()
        );
        
        // Should fail with connection error
        assertThrows(Exception.class, () -> {
            setupService.createCompleteSetup(invalidRequest).get(10, TimeUnit.SECONDS);
        });
        
        logger.info("Invalid setup request properly rejected");
        logger.info("=== Invalid Setup Request Handling Test Passed ===");
    }
    
    private DatabaseSetupRequest createMinimalSetupRequest() {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("minimal_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();
        
        return new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(),
                List.of(),
                Map.of()
        );
    }
    
    private void verifyDatabaseExists(String databaseName) throws SQLException {
        String adminUrl = String.format("jdbc:postgresql://%s:%d/postgres", 
                postgres.getHost(), postgres.getFirstMappedPort());
        
        try (Connection conn = DriverManager.getConnection(adminUrl, 
                postgres.getUsername(), postgres.getPassword());
             var stmt = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            
            stmt.setString(1, databaseName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Database should exist: " + databaseName);
        }
    }
    
    private void verifySchemaStructure(DatabaseConfig dbConfig) throws SQLException {
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), dbConfig.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, 
                postgres.getUsername(), postgres.getPassword())) {
            
            // Verify extensions
            verifyExtensionExists(conn, "uuid-ossp");
            verifyExtensionExists(conn, "pg_stat_statements");
            
            // Verify schemas
            verifySchemaExists(conn, "peegeeq");
            verifySchemaExists(conn, "bitemporal");
            
            // Verify template tables
            verifyTableExists(conn, "peegeeq", "queue_template");
            verifyTableExists(conn, "bitemporal", "event_store_template");
        }
    }
    
    private void verifyQueueTables(DatabaseConfig dbConfig, List<QueueConfig> queues) throws SQLException {
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), dbConfig.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, 
                postgres.getUsername(), postgres.getPassword())) {
            
            for (QueueConfig queue : queues) {
                verifyTableExists(conn, dbConfig.getSchema(), queue.getQueueName());
                verifyTriggerExists(conn, "trigger_" + queue.getQueueName() + "_notify");
            }
        }
    }
    
    private void verifyEventStoreTables(DatabaseConfig dbConfig, List<EventStoreConfig> eventStores) throws SQLException {
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), dbConfig.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, 
                postgres.getUsername(), postgres.getPassword())) {
            
            for (EventStoreConfig eventStore : eventStores) {
                verifyTableExists(conn, dbConfig.getSchema(), eventStore.getTableName());
                verifyTriggerExists(conn, "trigger_" + eventStore.getTableName() + "_notify");
            }
        }
    }
    
    private void verifyExtensionExists(Connection conn, String extensionName) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT 1 FROM pg_extension WHERE extname = ?")) {
            stmt.setString(1, extensionName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Extension should exist: " + extensionName);
        }
    }
    
    private void verifySchemaExists(Connection conn, String schemaName) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            stmt.setString(1, schemaName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Schema should exist: " + schemaName);
        }
    }
    
    private void verifyTableExists(Connection conn, String schema, String tableName) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Table should exist: " + schema + "." + tableName);
        }
    }
    
    private void verifyTriggerExists(Connection conn, String triggerName) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT 1 FROM information_schema.triggers WHERE trigger_name = ?")) {
            stmt.setString(1, triggerName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Trigger should exist: " + triggerName);
        }
    }
}
