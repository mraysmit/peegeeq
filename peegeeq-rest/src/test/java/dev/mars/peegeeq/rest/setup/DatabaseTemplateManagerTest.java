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

import dev.mars.peegeeq.db.setup.DatabaseTemplateManager;
import io.vertx.core.Vertx;
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
 * Comprehensive tests for DatabaseTemplateManager using TestContainers.
 * 
 * Tests PostgreSQL template database creation functionality including:
 * - Creating databases from templates
 * - Template inheritance
 * - Encoding specification
 * - Error handling
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DatabaseTemplateManagerTest {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseTemplateManagerTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("template_manager_test")
            .withUsername("template_test")
            .withPassword("template_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private DatabaseTemplateManager templateManager;
    private Connection adminConnection;
    private Vertx vertx;

    @BeforeEach
    void setUp() throws SQLException {
        vertx = Vertx.vertx();
        templateManager = new DatabaseTemplateManager(vertx);

        // Connect to postgres database for admin operations (for verification queries)
        String adminUrl = String.format("jdbc:postgresql://%s:%d/postgres",
                postgres.getHost(), postgres.getFirstMappedPort());
        adminConnection = DriverManager.getConnection(adminUrl,
                postgres.getUsername(), postgres.getPassword());

        logger.info("Connected to admin database: {}", adminUrl);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (adminConnection != null && !adminConnection.isClosed()) {
            adminConnection.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }
    
    @Test
    @Order(1)
    void testCreateDatabaseFromTemplate0() throws Exception {
        logger.info("=== Testing Database Creation from template0 ===");

        String newDatabaseName = "test_db_" + System.currentTimeMillis();

        // Create database from template0 using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                newDatabaseName,
                "template0",
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify database was created
        verifyDatabaseExists(newDatabaseName);
        verifyDatabaseEncoding(newDatabaseName, "UTF8");
        
        logger.info("Database created successfully from template0: {}", newDatabaseName);
        logger.info("=== Database Creation from template0 Test Passed ===");
    }
    
    @Test
    @Order(2)
    void testCreateDatabaseFromTemplate1() throws Exception {
        logger.info("=== Testing Database Creation from template1 ===");
        
        String newDatabaseName = "test_db_template1_" + System.currentTimeMillis();
        
        // Create database from template1 using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                newDatabaseName,
                "template1",
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify database was created
        verifyDatabaseExists(newDatabaseName);
        verifyDatabaseEncoding(newDatabaseName, "UTF8");
        
        logger.info("Database created successfully from template1: {}", newDatabaseName);
        logger.info("=== Database Creation from template1 Test Passed ===");
    }
    
    @Test
    @Order(3)
    void testCreateDatabaseWithCustomEncoding() throws Exception {
        logger.info("=== Testing Database Creation with Custom Encoding ===");

        String newDatabaseName = "test_db_utf8_" + System.currentTimeMillis();

        // Create database with explicit UTF8 encoding (compatible with container locale) using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                newDatabaseName,
                "template0",
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();

        // Verify database was created with correct encoding
        verifyDatabaseExists(newDatabaseName);
        verifyDatabaseEncoding(newDatabaseName, "UTF8");

        logger.info("Database created successfully with UTF8 encoding: {}", newDatabaseName);
        logger.info("=== Database Creation with Custom Encoding Test Passed ===");
    }
    
    @Test
    @Order(4)
    void testCreateDatabaseWithoutTemplate() throws Exception {
        logger.info("=== Testing Database Creation without Template ===");
        
        String newDatabaseName = "test_db_no_template_" + System.currentTimeMillis();
        
        // Create database without specifying template (should use default) using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                newDatabaseName,
                null,
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify database was created
        verifyDatabaseExists(newDatabaseName);
        verifyDatabaseEncoding(newDatabaseName, "UTF8");
        
        logger.info("Database created successfully without template: {}", newDatabaseName);
        logger.info("=== Database Creation without Template Test Passed ===");
    }
    
    @Test
    @Order(5)
    void testCreateDatabaseWithoutEncoding() throws Exception {
        logger.info("=== Testing Database Creation without Encoding ===");
        
        String newDatabaseName = "test_db_no_encoding_" + System.currentTimeMillis();
        
        // Create database without specifying encoding (should use default) using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                newDatabaseName,
                "template0",
                null,
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify database was created
        verifyDatabaseExists(newDatabaseName);
        
        logger.info("Database created successfully without encoding: {}", newDatabaseName);
        logger.info("=== Database Creation without Encoding Test Passed ===");
    }
    
    @Test
    @Order(6)
    void testCreateTemplateDatabase() throws Exception {
        logger.info("=== Testing Template Database Creation ===");
        
        String templateDatabaseName = "custom_template_" + System.currentTimeMillis();
        String newDatabaseName = "test_from_custom_template_" + System.currentTimeMillis();
        
        // First create a template database using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                templateDatabaseName,
                "template0",
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();

        // Add some structure to the template database
        addStructureToTemplateDatabase(templateDatabaseName);

        // Now create a database from our custom template using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                newDatabaseName,
                templateDatabaseName,
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();
        
        // Verify both databases exist
        verifyDatabaseExists(templateDatabaseName);
        verifyDatabaseExists(newDatabaseName);
        
        // Verify the new database inherited structure from template
        verifyTemplateStructureInherited(newDatabaseName);
        
        logger.info("Template database and derived database created successfully");
        logger.info("=== Template Database Creation Test Passed ===");
    }
    
    @Test
    void testCreateDatabaseWithInvalidTemplate() {
        logger.info("=== Testing Database Creation with Invalid Template ===");
        
        String newDatabaseName = "test_invalid_template_" + System.currentTimeMillis();
        
        // Try to create database from non-existent template using reactive API
        assertThrows(Exception.class, () -> {
            templateManager.createDatabaseFromTemplate(
                    postgres.getHost(),
                    postgres.getFirstMappedPort(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    newDatabaseName,
                    "non_existent_template",
                    "UTF8",
                    Map.of()
            ).toCompletionStage().toCompletableFuture().get();
        });
        
        logger.info("Invalid template properly rejected");
        logger.info("=== Database Creation with Invalid Template Test Passed ===");
    }
    
    @Test
    void testCreateDatabaseWithInvalidName() {
        logger.info("=== Testing Database Creation with Invalid Name ===");
        
        // Try to create database with invalid name using reactive API
        assertThrows(Exception.class, () -> {
            templateManager.createDatabaseFromTemplate(
                    postgres.getHost(),
                    postgres.getFirstMappedPort(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    "invalid-database-name-with-hyphens",
                    "template0",
                    "UTF8",
                    Map.of()
            ).toCompletionStage().toCompletableFuture().get();
        });
        
        logger.info("Invalid database name properly rejected");
        logger.info("=== Database Creation with Invalid Name Test Passed ===");
    }
    
    @Test
    void testCreateDuplicateDatabase() throws Exception {
        logger.info("=== Testing Duplicate Database Creation ===");

        String databaseName = "duplicate_test_" + System.currentTimeMillis();

        // Create database first time using reactive API
        templateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                databaseName,
                "template0",
                "UTF8",
                Map.of()
        ).toCompletionStage().toCompletableFuture().get();

        // Verify database exists
        verifyDatabaseExists(databaseName);

        // Try to create same database again (should succeed by dropping and recreating) using reactive API
        assertDoesNotThrow(() -> {
            templateManager.createDatabaseFromTemplate(
                    postgres.getHost(),
                    postgres.getFirstMappedPort(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    databaseName,
                    "template0",
                    "UTF8",
                    Map.of()
            ).toCompletionStage().toCompletableFuture().get();
        });

        // Verify database still exists after recreation
        verifyDatabaseExists(databaseName);

        logger.info("Duplicate database creation handled gracefully (drop and recreate)");
        logger.info("=== Duplicate Database Creation Test Passed ===");
    }
    
    private void verifyDatabaseExists(String databaseName) throws SQLException {
        try (var stmt = adminConnection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            stmt.setString(1, databaseName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Database should exist: " + databaseName);
        }
    }
    
    private void verifyDatabaseEncoding(String databaseName, String expectedEncoding) throws SQLException {
        try (var stmt = adminConnection.prepareStatement(
                "SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = ?")) {
            stmt.setString(1, databaseName);
            var rs = stmt.executeQuery();
            assertTrue(rs.next(), "Database should exist: " + databaseName);
            assertEquals(expectedEncoding, rs.getString(1), "Database encoding should match");
        }
    }
    
    private void addStructureToTemplateDatabase(String templateDatabaseName) throws SQLException {
        String templateUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), templateDatabaseName);
        
        try (Connection templateConn = DriverManager.getConnection(templateUrl, 
                postgres.getUsername(), postgres.getPassword());
             var stmt = templateConn.createStatement()) {
            
            // Add some structure to the template
            stmt.execute("CREATE SCHEMA test_schema");
            stmt.execute("CREATE TABLE test_schema.test_table (id SERIAL PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("INSERT INTO test_schema.test_table (name) VALUES ('template_data')");
        }
    }
    
    private void verifyTemplateStructureInherited(String databaseName) throws SQLException {
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), databaseName);
        
        try (Connection dbConn = DriverManager.getConnection(dbUrl, 
                postgres.getUsername(), postgres.getPassword())) {
            
            // Verify schema exists
            try (var stmt = dbConn.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
                stmt.setString(1, "test_schema");
                var rs = stmt.executeQuery();
                assertTrue(rs.next(), "Template schema should be inherited");
            }
            
            // Verify table exists
            try (var stmt = dbConn.prepareStatement(
                    "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
                stmt.setString(1, "test_schema");
                stmt.setString(2, "test_table");
                var rs = stmt.executeQuery();
                assertTrue(rs.next(), "Template table should be inherited");
            }
            
            // Verify data exists
            try (var stmt = dbConn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_schema.test_table WHERE name = 'template_data'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Template data should be inherited");
            }
        }
    }
}
