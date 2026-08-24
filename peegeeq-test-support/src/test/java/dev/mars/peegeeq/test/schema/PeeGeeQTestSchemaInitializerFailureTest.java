package dev.mars.peegeeq.test.schema;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@Tag(TestCategories.INTEGRATION)
class PeeGeeQTestSchemaInitializerFailureTest {

    @Container
    private static final PostgreSQLContainer postgres =
        PostgreSQLTestConstants.createStandardContainer();

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer",
        message = "Failed to cleanup test data for schema 'peegeeq_test'",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = SQLException.class)
    void cleanupPropagatesConnectionFailure() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
            PeeGeeQTestSchemaInitializer.cleanupTestData(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword() + "-invalid",
                PostgreSQLTestConstants.TEST_SCHEMA,
                PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE));

        assertEquals(
            "PeeGeeQ test data cleanup failed for schema 'peegeeq_test'",
            failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
    }
}
