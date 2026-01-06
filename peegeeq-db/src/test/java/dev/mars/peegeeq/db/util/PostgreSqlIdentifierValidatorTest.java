package dev.mars.peegeeq.db.util;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgreSqlIdentifierValidator.
 */
@Tag(TestCategories.CORE)
class PostgreSqlIdentifierValidatorTest {

    @Test
    void testValidIdentifiers() {
        // Valid identifiers should not throw exceptions
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("my_table", "Table"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("_leading_underscore", "Table"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("Table123", "Table"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("UPPERCASE_TABLE", "Table"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("a", "Table")); // Single char
    }

    @Test
    void testInvalidIdentifiers_NullOrEmpty() {
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate(null, "Table"));
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("", "Table"));
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("   ", "Table"));
    }

    @Test
    void testInvalidIdentifiers_InvalidCharacters() {
        // Hyphens not allowed
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("my-table", "Table"));
        
        // Spaces not allowed
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("my table", "Table"));
        
        // Special characters not allowed
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("table@name", "Table"));
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("table#name", "Table"));
        
        // Cannot start with number
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("123table", "Table"));
    }

    @Test
    void testInvalidIdentifiers_ReservedNames() {
        // Reserved PostgreSQL prefixes/names
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("pg_catalog", "Schema"));
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("pg_temp", "Schema"));
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("information_schema", "Schema"));
        
        // Case insensitive check
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("PG_CATALOG", "Schema"));
        assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate("INFORMATION_SCHEMA", "Schema"));
    }

    @Test
    void testInvalidIdentifiers_TooLong() {
        // Exactly 63 chars should be valid
        String exactly63 = "a".repeat(63);
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate(exactly63, "Table"));
        
        // 64 chars should fail
        String tooLong = "a".repeat(64);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, 
            () -> PostgreSqlIdentifierValidator.validate(tooLong, "Table"));
        assertTrue(ex.getMessage().contains("exceeds PostgreSQL maximum length of 63 characters"));
        assertTrue(ex.getMessage().contains("length: 64"));
    }

    @Test
    void testSanitize() {
        // Replace hyphens with underscores
        assertEquals("my_table", PostgreSqlIdentifierValidator.sanitize("my-table"));
        
        // Remove invalid characters
        assertEquals("mytable", PostgreSqlIdentifierValidator.sanitize("my@table"));
        assertEquals("my_table", PostgreSqlIdentifierValidator.sanitize("my table"));
        
        // Prefix with underscore if starts with number
        assertEquals("_123table", PostgreSqlIdentifierValidator.sanitize("123table"));
        
        // Trim whitespace
        assertEquals("my_table", PostgreSqlIdentifierValidator.sanitize("  my_table  "));
        
        // Null/empty handling
        assertNull(PostgreSqlIdentifierValidator.sanitize(null));
        assertEquals("", PostgreSqlIdentifierValidator.sanitize(""));
    }

    @Test
    void testTruncateWithHash() {
        // Short name stays as-is
        String shortName = "my_table";
        assertEquals(shortName, PostgreSqlIdentifierValidator.truncateWithHash(shortName));
        
        // Exactly 63 chars stays as-is
        String exactly63 = "a".repeat(63);
        assertEquals(exactly63, PostgreSqlIdentifierValidator.truncateWithHash(exactly63));
        
        // 64+ chars gets truncated with hash
        String longName = "tenant_acme_corporation_very_long_company_name_orders_queue_v234";
        String truncated = PostgreSqlIdentifierValidator.truncateWithHash(longName);
        
        // Result should be exactly 63 characters
        assertEquals(63, truncated.length());
        
        // Should end with underscore + 8 hex chars
        assertTrue(truncated.matches("^.*_[a-f0-9]{8}$"));
        
        // Should start with original prefix
        assertTrue(truncated.startsWith("tenant_acme_corporation"));
        
        // Truncation should be deterministic (same input = same output)
        String truncated2 = PostgreSqlIdentifierValidator.truncateWithHash(longName);
        assertEquals(truncated, truncated2);
        
        // Different inputs should produce different hashes
        String anotherLongName = "tenant_acme_corporation_very_long_company_name_payments_queue_v234";
        String truncated3 = PostgreSqlIdentifierValidator.truncateWithHash(anotherLongName);
        assertNotEquals(truncated, truncated3);
    }

    @Test
    void testTruncateWithHash_NullHandling() {
        assertNull(PostgreSqlIdentifierValidator.truncateWithHash(null));
    }

    @Test
    void testMakeSafe() {
        // Combines sanitize and truncate
        String unsafe = "my-table-with-hyphens";
        String safe = PostgreSqlIdentifierValidator.makeSafe(unsafe);
        assertEquals("my_table_with_hyphens", safe);
        
        // Long name with invalid characters
        String longUnsafe = "tenant-acme-corporation-very-long-company-name-orders-queue-v2-with-even-more-text";
        String safeLong = PostgreSqlIdentifierValidator.makeSafe(longUnsafe);
        assertEquals(63, safeLong.length());
        assertTrue(safeLong.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")); // Valid identifier
        assertTrue(safeLong.matches("^.*_[a-f0-9]{8}$")); // Ends with hash
    }

    @Test
    void testIsValid() {
        // Valid identifiers
        assertTrue(PostgreSqlIdentifierValidator.isValid("my_table"));
        assertTrue(PostgreSqlIdentifierValidator.isValid("_leading"));
        assertTrue(PostgreSqlIdentifierValidator.isValid("Table123"));
        
        // Invalid identifiers
        assertFalse(PostgreSqlIdentifierValidator.isValid(null));
        assertFalse(PostgreSqlIdentifierValidator.isValid(""));
        assertFalse(PostgreSqlIdentifierValidator.isValid("   "));
        assertFalse(PostgreSqlIdentifierValidator.isValid("my-table"));
        assertFalse(PostgreSqlIdentifierValidator.isValid("123table"));
        assertFalse(PostgreSqlIdentifierValidator.isValid("pg_catalog"));
        assertFalse(PostgreSqlIdentifierValidator.isValid("information_schema"));
        assertFalse(PostgreSqlIdentifierValidator.isValid("a".repeat(64)));
    }

    @Test
    void testMaxIdentifierLength_Constant() {
        assertEquals(63, PostgreSqlIdentifierValidator.MAX_IDENTIFIER_LENGTH);
    }

    @Test
    void testErrorMessages() {
        // Check error message content for different validation failures
        try {
            PostgreSqlIdentifierValidator.validate("my-table", "Queue");
            fail("Should have thrown exception");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Invalid Queue name"));
            assertTrue(e.getMessage().contains("my-table"));
            assertTrue(e.getMessage().contains("alphanumeric"));
        }

        try {
            PostgreSqlIdentifierValidator.validate("a".repeat(64), "Table");
            fail("Should have thrown exception");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("exceeds PostgreSQL maximum length"));
            assertTrue(e.getMessage().contains("63 characters"));
            assertTrue(e.getMessage().contains("length: 64"));
        }

        try {
            PostgreSqlIdentifierValidator.validate("pg_temp", "Schema");
            fail("Should have thrown exception");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Reserved Schema name"));
            assertTrue(e.getMessage().contains("pg_temp"));
            assertTrue(e.getMessage().contains("pg_*"));
        }
    }

    @Test
    void testRealWorldExamples() {
        // Real-world queue names
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("orders_queue", "Queue"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("tenant_123_orders", "Queue"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("workflow_step_1_events", "Queue"));
        
        // Real-world schema names
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("public", "Schema"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("tenant_acme_corp", "Schema"));
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate("my_app_queue", "Schema"));
        
        // Edge case: exactly at limit
        String maxLength = "tenant_acme_corp_orders_queue_for_processing_and_analytics_1234";
        assertEquals(63, maxLength.length());
        assertDoesNotThrow(() -> PostgreSqlIdentifierValidator.validate(maxLength, "Queue"));
    }
}
