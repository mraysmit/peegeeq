package dev.mars.peegeeq.test;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PostgreSQLTestConstants to ensure the centralized version management works correctly.
 */
@Tag(TestCategories.CORE)
class PostgreSQLTestConstantsTest {

    @Test
    void testPostgreSQLImageConstant() {
        // Verify the constant is set to the expected standardized version
        assertEquals(PostgreSQLTestConstants.POSTGRES_IMAGE, PostgreSQLTestConstants.POSTGRES_IMAGE);
    }

    @Test
    void testDefaultConstants() {
        // Verify all default constants are properly set
        assertEquals("peegeeq_test", PostgreSQLTestConstants.DEFAULT_DATABASE_NAME);
        assertEquals("peegeeq_test", PostgreSQLTestConstants.DEFAULT_USERNAME);
        assertEquals("peegeeq_test", PostgreSQLTestConstants.DEFAULT_PASSWORD);
        assertEquals(256 * 1024 * 1024L, PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        assertEquals(512 * 1024 * 1024L, PostgreSQLTestConstants.HIGH_PERFORMANCE_SHARED_MEMORY_SIZE);
    }

    @Test
    void testTestSchemaConstant() {
        // PeeGeeQ has no default schema: every test states its schema explicitly, and
        // shared-container suites all reference this single constant
        assertEquals("peegeeq_test", PostgreSQLTestConstants.TEST_SCHEMA);

        // Must be a valid unquoted PostgreSQL identifier (the project-wide whitelist)
        assertTrue(PostgreSQLTestConstants.TEST_SCHEMA.matches("^[a-zA-Z_][a-zA-Z0-9_]*$"),
            "TEST_SCHEMA must satisfy the PostgreSQL identifier whitelist, got: "
                + PostgreSQLTestConstants.TEST_SCHEMA);
    }

    @Test
    void testCreateStandardContainer() {
        // Test that the standard container factory method works
        PostgreSQLContainer container = PostgreSQLTestConstants.createStandardContainer();
        
        assertNotNull(container);
        assertEquals(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME, container.getDatabaseName());
        assertEquals(PostgreSQLTestConstants.DEFAULT_USERNAME, container.getUsername());
        assertEquals(PostgreSQLTestConstants.DEFAULT_PASSWORD, container.getPassword());
        
        // Verify it uses the correct Docker image
        assertTrue(container.getDockerImageName().contains(PostgreSQLTestConstants.POSTGRES_IMAGE));
    }

    @Test
    void testCreateCustomContainer() {
        // Test that the custom container factory method works
        String customDb = "custom_db";
        String customUser = "custom_user";
        String customPassword = "custom_password";
        
        PostgreSQLContainer container = PostgreSQLTestConstants.createContainer(
            customDb, customUser, customPassword
        );
        
        assertNotNull(container);
        assertEquals(customDb, container.getDatabaseName());
        assertEquals(customUser, container.getUsername());
        assertEquals(customPassword, container.getPassword());
        
        // Verify it uses the correct Docker image
        assertTrue(container.getDockerImageName().contains(PostgreSQLTestConstants.POSTGRES_IMAGE));
    }

    @Test
    void testCreateHighPerformanceContainer() {
        // Test that the high-performance container factory method works
        String perfDb = "perf_db";
        String perfUser = "perf_user";
        String perfPassword = "perf_password";
        
        PostgreSQLContainer container = PostgreSQLTestConstants.createHighPerformanceContainer(
            perfDb, perfUser, perfPassword
        );
        
        assertNotNull(container);
        assertEquals(perfDb, container.getDatabaseName());
        assertEquals(perfUser, container.getUsername());
        assertEquals(perfPassword, container.getPassword());
        
        // Verify it uses the correct Docker image
        assertTrue(container.getDockerImageName().contains(PostgreSQLTestConstants.POSTGRES_IMAGE));
    }

    @Test
    void testVersionConsistency() {
        // Test that all factory methods use the same PostgreSQL version
        PostgreSQLContainer standard = PostgreSQLTestConstants.createStandardContainer();
        PostgreSQLContainer custom = PostgreSQLTestConstants.createContainer("db", "user", "pass");
        PostgreSQLContainer performance = PostgreSQLTestConstants.createHighPerformanceContainer("db", "user", "pass");
        
        // All should use the same Docker image
        String expectedImage = PostgreSQLTestConstants.POSTGRES_IMAGE;
        assertTrue(standard.getDockerImageName().contains(expectedImage));
        assertTrue(custom.getDockerImageName().contains(expectedImage));
        assertTrue(performance.getDockerImageName().contains(expectedImage));
    }
}
