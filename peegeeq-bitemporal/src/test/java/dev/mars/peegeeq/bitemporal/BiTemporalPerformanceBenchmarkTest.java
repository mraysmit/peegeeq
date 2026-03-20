package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DEPRECATED: This class has been superseded by focused performance tests.
 *
 * This placeholder intentionally contains no blocking wrappers, latches, or bridge chains.
 * Use BiTemporalPerformanceParityTest, PgBiTemporalEventStorePerformanceTest,
 * and VertxPerformanceOptimization* tests instead.
 */
@Tag(TestCategories.PERFORMANCE)
@Disabled("DEPRECATED: Use BiTemporalPerformanceParityTest, PgBiTemporalEventStorePerformanceTest, and VertxPerformanceOptimization* tests instead")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BiTemporalPerformanceBenchmarkTest {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalPerformanceBenchmarkTest.class);

    @Container
    static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_benchmark_test")
            .withUsername("test")
            .withPassword("test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withCommand("postgres", "-c", "max_connections=300");
        return container;
    }

    private void deprecatedPlaceholder(String benchmarkName) {
        logger.info("Skipping deprecated benchmark placeholder: {}", benchmarkName);
        Assumptions.assumeTrue(false,
            "Deprecated benchmark class; use BiTemporalPerformanceParityTest, PgBiTemporalEventStorePerformanceTest, and VertxPerformanceOptimization* tests.");
    }

    @Test
    @Order(1)
    @DisplayName("BENCHMARK: Sequential vs Concurrent Event Appends")
    void benchmarkSequentialVsConcurrentAppends() {
        deprecatedPlaceholder("benchmarkSequentialVsConcurrentAppends");
    }

    @Test
    @Order(2)
    @DisplayName("BENCHMARK: Query Performance with Large Dataset")
    void benchmarkQueryPerformance() {
        deprecatedPlaceholder("benchmarkQueryPerformance");
    }

    @Test
    @Order(3)
    @DisplayName("BENCHMARK: Memory Usage and Resource Management")
    void benchmarkMemoryUsageAndResourceManagement() {
        deprecatedPlaceholder("benchmarkMemoryUsageAndResourceManagement");
    }

    @Test
    @Order(4)
    @DisplayName("BENCHMARK: Reactive Notification Performance")
    void benchmarkReactiveNotificationPerformance() {
        deprecatedPlaceholder("benchmarkReactiveNotificationPerformance");
    }

    @Test
    @Order(5)
    @DisplayName("BENCHMARK: Target Throughput Validation (1000+ msg/sec)")
    void benchmarkTargetThroughputValidation() {
        deprecatedPlaceholder("benchmarkTargetThroughputValidation");
    }

    @Test
    @Order(6)
    @DisplayName("BENCHMARK: Latency Performance Analysis")
    void benchmarkLatencyPerformance() {
        deprecatedPlaceholder("benchmarkLatencyPerformance");
    }

    @Test
    @Order(7)
    @DisplayName("BENCHMARK: Batch vs Individual Operations")
    void benchmarkBatchVsIndividualOperations() {
        deprecatedPlaceholder("benchmarkBatchVsIndividualOperations");
    }

    @Test
    @Order(8)
    @DisplayName("BENCHMARK: Memory Usage Under Load")
    void benchmarkMemoryUsageUnderLoad() {
        deprecatedPlaceholder("benchmarkMemoryUsageUnderLoad");
    }

    @Test
    @Order(9)
    @DisplayName("BENCHMARK: Resource Utilization Analysis")
    void benchmarkResourceUtilization() {
        deprecatedPlaceholder("benchmarkResourceUtilization");
    }

    @Test
    @Order(10)
    @DisplayName("BENCHMARK: High-Throughput Validation (Batched Processing - Realistic)")
    void benchmarkHighThroughputValidation() {
        deprecatedPlaceholder("benchmarkHighThroughputValidation");
    }
}
