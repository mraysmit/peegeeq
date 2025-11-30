package dev.mars.peegeeq.api.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

@Tag("core")
class DatabaseConfigTest {

    @Test
    void testConstructorAndGetters() {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig(5, 20, Duration.ofMinutes(60), Duration.ofSeconds(45), Duration.ofMinutes(15));
        DatabaseConfig config = new DatabaseConfig(
            "localhost",
            5432,
            "testdb",
            "user",
            "password",
            "custom_schema",
            true,
            "template_db",
            "UTF-16",
            poolConfig
        );

        assertEquals("localhost", config.getHost());
        assertEquals(5432, config.getPort());
        assertEquals("testdb", config.getDatabaseName());
        assertEquals("user", config.getUsername());
        assertEquals("password", config.getPassword());
        assertEquals("custom_schema", config.getSchema());
        assertTrue(config.isSslEnabled());
        assertEquals("template_db", config.getTemplateDatabase());
        assertEquals("UTF-16", config.getEncoding());
        assertEquals(poolConfig, config.getPoolConfig());
    }

    @Test
    void testDefaults() {
        DatabaseConfig config = new DatabaseConfig(
            "localhost",
            5432,
            "testdb",
            "user",
            "password",
            null, // schema
            false,
            null,
            null, // encoding
            null
        );

        assertEquals("peegeeq", config.getSchema());
        assertEquals("UTF8", config.getEncoding());
    }

    @Test
    void testBuilder() {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        DatabaseConfig config = new DatabaseConfig.Builder()
            .host("builder-host")
            .port(5433)
            .databaseName("builder-db")
            .username("builder-user")
            .password("builder-pass")
            .schema("builder-schema")
            .sslEnabled(true)
            .templateDatabase("builder-template")
            .encoding("LATIN1")
            .poolConfig(poolConfig)
            .build();

        assertEquals("builder-host", config.getHost());
        assertEquals(5433, config.getPort());
        assertEquals("builder-db", config.getDatabaseName());
        assertEquals("builder-user", config.getUsername());
        assertEquals("builder-pass", config.getPassword());
        assertEquals("builder-schema", config.getSchema());
        assertTrue(config.isSslEnabled());
        assertEquals("builder-template", config.getTemplateDatabase());
        assertEquals("LATIN1", config.getEncoding());
        assertEquals(poolConfig, config.getPoolConfig());
    }
}
