package dev.mars.peegeeq.test;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PostgreSQLTestConstants to ensure the centralized version management works correctly.
 */
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
    void testCreateStandardContainer() {
        // Test that the standard container factory method works
        PostgreSQLContainer<?> container = PostgreSQLTestConstants.createStandardContainer();
        
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
        
        PostgreSQLContainer<?> container = PostgreSQLTestConstants.createContainer(
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
        
        PostgreSQLContainer<?> container = PostgreSQLTestConstants.createHighPerformanceContainer(
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
    void testUtilityClassCannotBeInstantiated() {
        // Verify that the utility class cannot be instantiated
        var exception = assertThrows(Exception.class, () -> {
            // Use reflection to try to instantiate the class
            var constructor = PostgreSQLTestConstants.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        });

        // The UnsupportedOperationException is wrapped in InvocationTargetException
        assertTrue(exception.getCause() instanceof UnsupportedOperationException);
        assertEquals("This is a utility class and cannot be instantiated", exception.getCause().getMessage());
    }

    @Test
    void testVersionConsistency() {
        // Test that all factory methods use the same PostgreSQL version
        PostgreSQLContainer<?> standard = PostgreSQLTestConstants.createStandardContainer();
        PostgreSQLContainer<?> custom = PostgreSQLTestConstants.createContainer("db", "user", "pass");
        PostgreSQLContainer<?> performance = PostgreSQLTestConstants.createHighPerformanceContainer("db", "user", "pass");
        
        // All should use the same Docker image
        String expectedImage = PostgreSQLTestConstants.POSTGRES_IMAGE;
        assertTrue(standard.getDockerImageName().contains(expectedImage));
        assertTrue(custom.getDockerImageName().contains(expectedImage));
        assertTrue(performance.getDockerImageName().contains(expectedImage));
    }
}
