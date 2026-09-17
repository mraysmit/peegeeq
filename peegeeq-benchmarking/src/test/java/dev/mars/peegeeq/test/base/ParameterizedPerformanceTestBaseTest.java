package dev.mars.peegeeq.test.base;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the parameterized benchmark base.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class ParameterizedPerformanceTestBaseTest extends ParameterizedPerformanceTestBase {

    private static final Logger logger = LoggerFactory.getLogger(ParameterizedPerformanceTestBaseTest.class);

    @ParameterizedTest
    @EnumSource(value = PerformanceProfile.class, names = {"BASIC", "STANDARD", "HIGH_PERFORMANCE"})
    void testPerformanceAcrossProfiles(PerformanceProfile profile, Vertx vertx) {
        logger.info("Testing performance framework with profile: {}", profile.getDisplayName());

        PerformanceTestResult result = runTestWithProfile(profile, () -> createPerformanceMetrics(
            1000.0,
            50.0,
            75.0,
            0.0
        ));

        assertNotNull(result, "Result should not be null");
        assertEquals(profile, result.getProfile(), "Profile should match");
        assertTrue(result.isSuccess(), "Test should be successful");
        assertEquals(1000.0, result.getThroughput(), 0.1, "Throughput should match the supplied measurement");
        validatePerformanceThresholds(result, profile);
    }

    @Test
    void testPerformanceMetricsCreation() {
        Map<String, Object> metrics = createPerformanceMetrics(500.0, 25.0, 40.0, 1.5);

        assertEquals(500.0, metrics.get("throughput"), "Throughput should match");
        assertEquals(25.0, metrics.get("average_latency"), "Average latency should match");
        assertEquals(40.0, metrics.get("p95_latency"), "P95 latency should match");
        assertEquals(1.5, metrics.get("error_rate"), "Error rate should match");
    }
}
