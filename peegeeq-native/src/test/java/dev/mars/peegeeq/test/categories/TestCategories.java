package dev.mars.peegeeq.test.categories;

/**
 * Test categories for organizing PeeGeeQ Native tests by execution time and importance.
 * 
 * Usage:
 * - @Tag(TestCategories.CORE) - Fast unit tests, critical functionality
 * - @Tag(TestCategories.INTEGRATION) - Integration tests with real infrastructure
 * - @Tag(TestCategories.PERFORMANCE) - Performance and load tests
 * - @Tag(TestCategories.SLOW) - Long-running tests
 * - @Tag(TestCategories.FLAKY) - Tests that may be unstable
 * 
 * Maven execution examples:
 * - mvn test -Dgroups="core" (fast core tests only)
 * - mvn test -Dgroups="core,integration" (core + integration)
 * - mvn test -DexcludedGroups="slow,flaky" (exclude slow/flaky tests)
 */
public final class TestCategories {
    
    /**
     * CORE - Fast unit tests that validate critical functionality.
     * These should run in under 30 seconds total and test:
     * - Configuration objects (ConsumerConfig, ConsumerMode)
     * - Message objects (PgNativeMessage)
     * - Utility classes (EmptyReadStream)
     * - Builder patterns and validation
     * - Enum behavior and constants
     * 
     * Target: < 30 seconds total execution time
     */
    public static final String CORE = "core";
    
    /**
     * INTEGRATION - Tests that require real infrastructure (PostgreSQL, TestContainers).
     * These test end-to-end functionality but should be reasonably fast:
     * - Native queue producer/consumer operations
     * - LISTEN/NOTIFY functionality
     * - Database schema interactions
     * - Connection management
     * 
     * Target: 1-3 minutes total execution time
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * PERFORMANCE - Performance, load, and throughput tests.
     * These may take longer but validate system performance:
     * - Consumer mode performance comparisons
     * - High-frequency message processing
     * - Concurrent consumer testing
     * - Throughput benchmarks
     * 
     * Target: 2-5 minutes execution time
     */
    public static final String PERFORMANCE = "performance";
    
    /**
     * SLOW - Long-running tests that are comprehensive but time-consuming.
     * These include:
     * - Extended reliability tests
     * - Multi-consumer coordination tests
     * - Resource leak detection tests
     * - Fault injection tests
     * 
     * Target: 5+ minutes execution time
     */
    public static final String SLOW = "slow";
    
    /**
     * FLAKY - Tests that may be unstable due to timing, external dependencies, etc.
     * These should be fixed but can be excluded from CI pipelines:
     * - Tests with timing dependencies
     * - Tests sensitive to system load
     * - Network-dependent tests
     * 
     * Target: Variable execution time
     */
    public static final String FLAKY = "flaky";
    
    /**
     * SMOKE - Minimal smoke tests for basic functionality verification.
     * Ultra-fast tests that verify the system starts and basic operations work:
     * - Basic configuration loading
     * - Simple object creation
     * - Basic validation
     * 
     * Target: < 10 seconds total execution time
     */
    public static final String SMOKE = "smoke";
    
    private TestCategories() {
        // Utility class - no instantiation
    }
}
