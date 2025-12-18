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
package dev.mars.peegeeq.api.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServerSideFilter SQL generation and validation.
 */
@Tag("core")
class ServerSideFilterTest {

    @Nested
    @DisplayName("Factory Method Validation")
    class FactoryMethodValidation {

        @Test
        @DisplayName("headerEquals rejects null key")
        void headerEquals_rejectsNullKey() {
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.headerEquals(null, "value"));
        }

        @Test
        @DisplayName("headerEquals rejects empty key")
        void headerEquals_rejectsEmptyKey() {
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.headerEquals("", "value"));
        }

        @Test
        @DisplayName("headerEquals rejects invalid key pattern")
        void headerEquals_rejectsInvalidKeyPattern() {
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.headerEquals("123invalid", "value"));
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.headerEquals("key with spaces", "value"));
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.headerEquals("key'injection", "value"));
        }

        @Test
        @DisplayName("headerEquals rejects null value")
        void headerEquals_rejectsNullValue() {
            assertThrows(NullPointerException.class, 
                () -> ServerSideFilter.headerEquals("type", null));
        }

        @Test
        @DisplayName("headerIn rejects empty set")
        void headerIn_rejectsEmptySet() {
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.headerIn("type", Set.of()));
        }

        @Test
        @DisplayName("and rejects fewer than 2 filters")
        void and_rejectsFewerThan2Filters() {
            ServerSideFilter f1 = ServerSideFilter.headerEquals("type", "ORDER");
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.and(f1));
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.and());
        }

        @Test
        @DisplayName("or rejects fewer than 2 filters")
        void or_rejectsFewerThan2Filters() {
            ServerSideFilter f1 = ServerSideFilter.headerEquals("type", "ORDER");
            assertThrows(IllegalArgumentException.class, 
                () -> ServerSideFilter.or(f1));
        }
    }

    @Nested
    @DisplayName("SQL Generation - Simple Operators")
    class SqlGenerationSimple {

        @Test
        @DisplayName("EQUALS generates NULL-safe SQL")
        void equals_generatesNullSafeSql() {
            ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
            String sql = filter.toSqlCondition(4);
            
            assertEquals("(headers ? 'type' AND headers->>'type' = $4)", sql);
            assertEquals(List.of("ORDER"), filter.getParameters());
            assertEquals(1, filter.getParameterCount());
        }

        @Test
        @DisplayName("NOT_EQUALS generates NULL-safe SQL")
        void notEquals_generatesNullSafeSql() {
            ServerSideFilter filter = ServerSideFilter.headerNotEquals("status", "CANCELLED");
            String sql = filter.toSqlCondition(5);
            
            assertEquals("(NOT headers ? 'status' OR headers->>'status' != $5)", sql);
            assertEquals(List.of("CANCELLED"), filter.getParameters());
            assertEquals(1, filter.getParameterCount());
        }

        @Test
        @DisplayName("LIKE generates NULL-safe SQL")
        void like_generatesNullSafeSql() {
            ServerSideFilter filter = ServerSideFilter.headerLike("eventType", "order-%");
            String sql = filter.toSqlCondition(3);
            
            assertEquals("(headers ? 'eventType' AND headers->>'eventType' LIKE $3)", sql);
            assertEquals(List.of("order-%"), filter.getParameters());
            assertEquals(1, filter.getParameterCount());
        }

        @Test
        @DisplayName("IN generates SQL with multiple parameters")
        void in_generatesMultiParamSql() {
            ServerSideFilter filter = ServerSideFilter.headerIn("region", Set.of("US", "EU", "APAC"));
            String sql = filter.toSqlCondition(4);

            // IN clause should have 3 parameters
            assertTrue(sql.contains("headers ? 'region'"));
            assertTrue(sql.contains("headers->>'region' IN"));
            assertEquals(3, filter.getParameterCount());
            assertEquals(3, filter.getParameters().size());
            assertTrue(filter.getParameters().containsAll(List.of("US", "EU", "APAC")));
        }
    }

    @Nested
    @DisplayName("SQL Generation - Compound Operators")
    class SqlGenerationCompound {

        @Test
        @DisplayName("AND combines filters with correct parameter offsets")
        void and_combinesWithCorrectOffsets() {
            ServerSideFilter filter = ServerSideFilter.and(
                ServerSideFilter.headerEquals("type", "ORDER"),
                ServerSideFilter.headerEquals("priority", "HIGH")
            );
            String sql = filter.toSqlCondition(4);

            // Should have both conditions with correct parameter numbers
            assertTrue(sql.contains("$4"), "First param should be $4");
            assertTrue(sql.contains("$5"), "Second param should be $5");
            assertTrue(sql.contains(" AND "), "Should use AND joiner");
            assertEquals(2, filter.getParameterCount());
            assertEquals(List.of("ORDER", "HIGH"), filter.getParameters());
        }

        @Test
        @DisplayName("OR combines filters with correct parameter offsets")
        void or_combinesWithCorrectOffsets() {
            ServerSideFilter filter = ServerSideFilter.or(
                ServerSideFilter.headerEquals("type", "ORDER"),
                ServerSideFilter.headerEquals("type", "REFUND")
            );
            String sql = filter.toSqlCondition(4);

            assertTrue(sql.contains("$4"), "First param should be $4");
            assertTrue(sql.contains("$5"), "Second param should be $5");
            assertTrue(sql.contains(" OR "), "Should use OR joiner");
            assertEquals(2, filter.getParameterCount());
        }

        @Test
        @DisplayName("Nested AND/OR generates correct SQL")
        void nestedAndOr_generatesCorrectSql() {
            // (type=ORDER AND priority=HIGH) OR (type=REFUND)
            ServerSideFilter filter = ServerSideFilter.or(
                ServerSideFilter.and(
                    ServerSideFilter.headerEquals("type", "ORDER"),
                    ServerSideFilter.headerEquals("priority", "HIGH")
                ),
                ServerSideFilter.headerEquals("type", "REFUND")
            );
            String sql = filter.toSqlCondition(4);

            // Should have 3 parameters: ORDER, HIGH, REFUND
            assertEquals(3, filter.getParameterCount());
            assertEquals(List.of("ORDER", "HIGH", "REFUND"), filter.getParameters());
            assertTrue(sql.contains("$4"));
            assertTrue(sql.contains("$5"));
            assertTrue(sql.contains("$6"));
        }

        @Test
        @DisplayName("AND with IN generates correct parameter count")
        void andWithIn_generatesCorrectParamCount() {
            ServerSideFilter filter = ServerSideFilter.and(
                ServerSideFilter.headerEquals("type", "ORDER"),
                ServerSideFilter.headerIn("region", Set.of("US", "EU"))
            );

            // 1 for EQUALS + 2 for IN = 3 total
            assertEquals(3, filter.getParameterCount());
            assertEquals(3, filter.getParameters().size());
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Equal filters have same hashCode")
        void equalFilters_haveSameHashCode() {
            ServerSideFilter f1 = ServerSideFilter.headerEquals("type", "ORDER");
            ServerSideFilter f2 = ServerSideFilter.headerEquals("type", "ORDER");

            assertEquals(f1, f2);
            assertEquals(f1.hashCode(), f2.hashCode());
        }

        @Test
        @DisplayName("Different filters are not equal")
        void differentFilters_areNotEqual() {
            ServerSideFilter f1 = ServerSideFilter.headerEquals("type", "ORDER");
            ServerSideFilter f2 = ServerSideFilter.headerEquals("type", "PAYMENT");
            ServerSideFilter f3 = ServerSideFilter.headerEquals("status", "ORDER");

            assertNotEquals(f1, f2);
            assertNotEquals(f1, f3);
        }
    }

    @Nested
    @DisplayName("Valid Header Key Patterns")
    class ValidHeaderKeyPatterns {

        @Test
        @DisplayName("Accepts valid header keys")
        void acceptsValidHeaderKeys() {
            assertDoesNotThrow(() -> ServerSideFilter.headerEquals("type", "v"));
            assertDoesNotThrow(() -> ServerSideFilter.headerEquals("eventType", "v"));
            assertDoesNotThrow(() -> ServerSideFilter.headerEquals("event_type", "v"));
            assertDoesNotThrow(() -> ServerSideFilter.headerEquals("event-type", "v"));
            assertDoesNotThrow(() -> ServerSideFilter.headerEquals("Type123", "v"));
        }
    }
}

