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
    void testSchemaRequired() {
        // PeeGeeQ has no default schema: a connection config without an explicit
        // schema must fail at build, like host/database/username
        PgConnectionConfig.Builder builder = new PgConnectionConfig.Builder()
            .host("localhost")
            .port(5432)
            .database("testdb")
            .username("user")
            .password("pass");

        assertThrows(NullPointerException.class, builder::build,
            "build() without an explicit schema must throw — PeeGeeQ has no default schema");
    }

    @Test
    void testBlankSchemaRejected() {
        PgConnectionConfig.Builder builder = new PgConnectionConfig.Builder()
            .host("localhost")
            .port(5432)
            .database("testdb")
            .username("user")
            .password("pass")
            .schema("   ");

        assertThrows(IllegalArgumentException.class, builder::build,
            "A blank schema must be rejected, not passed through");
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
