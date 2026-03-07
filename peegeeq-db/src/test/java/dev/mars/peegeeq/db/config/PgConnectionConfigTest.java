package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag(TestCategories.CORE)
class PgConnectionConfigTest {

    @Test
    void testPortValidation() {
        assertThrows(IllegalArgumentException.class, () -> new PgConnectionConfig.Builder().port(0));
        assertThrows(IllegalArgumentException.class, () -> new PgConnectionConfig.Builder().port(65536));
    }

    @Test
    void testSchemaIsUrlEncodedInJdbcUrl() {
        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host("localhost")
            .port(5432)
            .database("testdb")
            .username("user")
            .password("pass")
            .schema("tenant one")
            .build();

        assertEquals("jdbc:postgresql://localhost:5432/testdb?currentSchema=tenant+one", config.getJdbcUrl());
    }
}
