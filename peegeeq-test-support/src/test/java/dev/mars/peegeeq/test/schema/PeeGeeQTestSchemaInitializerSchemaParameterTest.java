package dev.mars.peegeeq.test.schema;

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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for schema parameter support in PeeGeeQTestSchemaInitializer.
 */
@Testcontainers
@Tag("integration")
class PeeGeeQTestSchemaInitializerSchemaParameterTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_password");

    @BeforeAll
    static void setUp() {
        // Container is automatically started by Testcontainers
    }

    @AfterAll
    static void tearDown() {
        // Container is automatically stopped by Testcontainers
    }

    @Test
    void testInitializeSchemaWithCustomSchema() throws Exception {
        String customSchema = "tenant_abc";

        // Initialize schema with custom schema name
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, customSchema, 
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);

        // Verify schema was created
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Check schema exists
            ResultSet rs = stmt.executeQuery(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = '" + customSchema + "'");
            assertTrue(rs.next(), "Custom schema should exist");
            assertEquals(customSchema, rs.getString("schema_name"));

            // Check table exists in custom schema
            rs = stmt.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + customSchema + "' AND table_name = 'queue_messages'");
            assertTrue(rs.next(), "queue_messages table should exist in custom schema");
        }
    }

    @Test
    void testInitializeSchemaWithPublicSchema() throws Exception {
        // Initialize schema with default "public" schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "public", 
            PeeGeeQTestSchemaInitializer.SchemaComponent.OUTBOX);

        // Verify table exists in public schema
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'outbox'");
            assertTrue(rs.next(), "outbox table should exist in public schema");
        }
    }

    @Test
    void testSchemaValidation_NullSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, (String) null,
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        });
        assertTrue(exception.getMessage().contains("Schema parameter is required"));
    }

    @Test
    void testSchemaValidation_BlankSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "  ", 
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        });
        assertTrue(exception.getMessage().contains("Schema parameter is required"));
    }

    @Test
    void testSchemaValidation_InvalidSchemaName() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "test'; DROP TABLE users; --", 
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        });
        assertTrue(exception.getMessage().contains("Invalid schema name"));
    }

    @Test
    void testSchemaValidation_ReservedSchemaName_PgPrefix() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "pg_catalog", 
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        });
        assertTrue(exception.getMessage().contains("Reserved schema name"));
    }

    @Test
    void testSchemaValidation_ReservedSchemaName_InformationSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "information_schema", 
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        });
        assertTrue(exception.getMessage().contains("Reserved schema name"));
    }

    @Test
    void testMultiTenantSchemaIsolation() throws Exception {
        String schema1 = "tenant_a";
        String schema2 = "tenant_b";

        // Initialize both schemas
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema1, 
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema2, 
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);

        // Verify both schemas exist and have their own tables
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Check schema1 has queue_messages table
            ResultSet rs1 = stmt.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema1 + "' AND table_name = 'queue_messages'");
            assertTrue(rs1.next(), "queue_messages table should exist in schema1");

            // Check schema2 has queue_messages table
            ResultSet rs2 = stmt.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema2 + "' AND table_name = 'queue_messages'");
            assertTrue(rs2.next(), "queue_messages table should exist in schema2");
        }
    }

    @Test
    void testMultiTenantDataIsolationForBitemporalEvents() throws Exception {
        String schema1 = "tenant_iso_a";
        String schema2 = "tenant_iso_b";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema1,
            PeeGeeQTestSchemaInitializer.SchemaComponent.BITEMPORAL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema2,
            PeeGeeQTestSchemaInitializer.SchemaComponent.BITEMPORAL);

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Same event_id in different tenant schemas must remain isolated.
            stmt.execute("""
                INSERT INTO tenant_iso_a.bitemporal_event_log (event_id, event_type, valid_time, payload)
                VALUES ('event-1', 'TenantEvent', NOW(), '{"tenant":"A"}'::jsonb)
                """);

            stmt.execute("""
                INSERT INTO tenant_iso_b.bitemporal_event_log (event_id, event_type, valid_time, payload)
                VALUES ('event-1', 'TenantEvent', NOW(), '{"tenant":"B"}'::jsonb)
                """);

            ResultSet rsA = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM tenant_iso_a.bitemporal_event_log WHERE event_id = 'event-1'");
            assertTrue(rsA.next());
            assertEquals(1, rsA.getInt("cnt"), "Schema A should contain exactly its own event");

            ResultSet rsB = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM tenant_iso_b.bitemporal_event_log WHERE event_id = 'event-1'");
            assertTrue(rsB.next());
            assertEquals(1, rsB.getInt("cnt"), "Schema B should contain exactly its own event");

            ResultSet rsPayloadA = stmt.executeQuery("SELECT payload->>'tenant' AS tenant FROM tenant_iso_a.bitemporal_event_log WHERE event_id = 'event-1'");
            assertTrue(rsPayloadA.next());
            assertEquals("A", rsPayloadA.getString("tenant"));

            ResultSet rsPayloadB = stmt.executeQuery("SELECT payload->>'tenant' AS tenant FROM tenant_iso_b.bitemporal_event_log WHERE event_id = 'event-1'");
            assertTrue(rsPayloadB.next());
            assertEquals("B", rsPayloadB.getString("tenant"));
        }
    }

    @Test
    void testCleanupTestDataOnlyAffectsTargetSchema() throws Exception {
        String schema1 = "tenant_cleanup_a";
        String schema2 = "tenant_cleanup_b";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema1,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema2,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE);

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                INSERT INTO tenant_cleanup_a.queue_messages (topic, payload)
                VALUES ('orders', '{"id":"a1"}'::jsonb)
                """);

            stmt.execute("""
                INSERT INTO tenant_cleanup_b.queue_messages (topic, payload)
                VALUES ('orders', '{"id":"b1"}'::jsonb)
                """);
        }

        PeeGeeQTestSchemaInitializer.cleanupTestData(
            postgres,
            schema1,
            PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE
        );

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            ResultSet rsA = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM tenant_cleanup_a.queue_messages");
            assertTrue(rsA.next());
            assertEquals(0, rsA.getInt("cnt"), "Cleanup should truncate only target schema");

            ResultSet rsB = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM tenant_cleanup_b.queue_messages");
            assertTrue(rsB.next());
            assertEquals(1, rsB.getInt("cnt"), "Non-target tenant schema data must remain intact");
        }
    }
}

