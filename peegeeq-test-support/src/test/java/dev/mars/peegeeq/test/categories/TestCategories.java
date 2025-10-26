package dev.mars.peegeeq.test.categories;

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

/**
 * Test category constants for peegeeq-test-support module.
 * 
 * <p>This class provides standardized test category constants used with JUnit 5 {@code @Tag} annotations
 * to enable selective test execution through Maven profiles. The categorization system allows developers
 * to run different subsets of tests based on their needs:</p>
 * 
 * <h3>Test Categories:</h3>
 * <ul>
 *   <li><strong>CORE</strong> - Fast unit tests for daily development (target: &lt;30s total, &lt;1s per test)</li>
 *   <li><strong>INTEGRATION</strong> - Tests with TestContainers and real infrastructure (1-3 minutes)</li>
 *   <li><strong>PERFORMANCE</strong> - Load and throughput tests (2-5 minutes)</li>
 *   <li><strong>SMOKE</strong> - Ultra-fast basic verification (&lt;10s total)</li>
 *   <li><strong>SLOW</strong> - Long-running comprehensive tests (5+ minutes)</li>
 *   <li><strong>FLAKY</strong> - Unstable tests needing investigation</li>
 * </ul>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Core test - fast unit test with mocked dependencies
 * @Tag(TestCategories.CORE)
 * class PostgreSQLTestConstantsTest {
 *     @Test
 *     void testConstantValues() {
 *         // Fast validation without external dependencies
 *     }
 * }
 * 
 * // Integration test - uses TestContainers with real PostgreSQL
 * @Tag(TestCategories.INTEGRATION)
 * @Testcontainers
 * class PeeGeeQTestBaseTest extends PeeGeeQTestBase {
 *     @Test
 *     void testDatabaseConnectivity() {
 *         // Real database integration test
 *     }
 * }
 * 
 * // Performance test - hardware profiling and metrics collection
 * @Tag(TestCategories.PERFORMANCE)
 * class HardwareProfilingIntegrationTest extends PeeGeeQTestBase {
 *     @Test
 *     void testPerformanceMetrics() {
 *         // Performance benchmarking with resource monitoring
 *     }
 * }
 * }</pre>
 * 
 * <h3>Maven Profile Usage:</h3>
 * <pre>{@code
 * # Daily development (core tests only)
 * mvn test                           # ~10 seconds
 * 
 * # Pre-commit validation
 * mvn test -Pintegration-tests       # ~2-3 minutes
 * 
 * # Performance benchmarks
 * mvn test -Pperformance-tests       # ~5 minutes
 * 
 * # Comprehensive testing
 * mvn test -Pall-tests              # ~10 minutes
 * }</pre>
 * 
 * <h3>Test Support Module Specific Guidelines:</h3>
 * <ul>
 *   <li><strong>CORE</strong>: Constants validation, utility class tests, metrics creation</li>
 *   <li><strong>INTEGRATION</strong>: TestContainer factory tests, base test class validation, database connectivity</li>
 *   <li><strong>PERFORMANCE</strong>: Hardware profiling, resource monitoring, performance metrics collection</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-26
 * @version 1.0
 * @see org.junit.jupiter.api.Tag
 */
public final class TestCategories {
    
    /**
     * Core tests - Fast unit tests for daily development.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in under 30 seconds total</li>
     *   <li>Each test completes in under 1 second</li>
     *   <li>Use mocked dependencies only</li>
     *   <li>Not require external infrastructure</li>
     *   <li>Test utility classes, constants, and basic logic</li>
     * </ul>
     * 
     * <p>Examples: Constants validation, utility class tests, metrics creation</p>
     */
    public static final String CORE = "core";
    
    /**
     * Integration tests - Tests with TestContainers and real infrastructure.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 1-3 minutes total</li>
     *   <li>Use TestContainers with real PostgreSQL</li>
     *   <li>Test complete integration scenarios</li>
     *   <li>Validate database connectivity and setup</li>
     *   <li>Test base test class functionality</li>
     * </ul>
     * 
     * <p>Examples: TestContainer factory tests, base test class validation, database connectivity</p>
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Performance tests - Load and throughput tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 2-5 minutes total</li>
     *   <li>Measure throughput and latency</li>
     *   <li>Include hardware profiling</li>
     *   <li>Monitor resource usage</li>
     *   <li>Generate performance metrics</li>
     * </ul>
     * 
     * <p>Examples: Hardware profiling, resource monitoring, performance metrics collection</p>
     */
    public static final String PERFORMANCE = "performance";
    
    /**
     * Smoke tests - Ultra-fast basic verification.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in under 10 seconds total</li>
     *   <li>Each test completes in under 5 seconds</li>
     *   <li>Verify basic functionality only</li>
     *   <li>Use minimal resources</li>
     *   <li>Provide quick feedback</li>
     * </ul>
     * 
     * <p>Examples: Basic constant validation, simple utility tests</p>
     */
    public static final String SMOKE = "smoke";
    
    /**
     * Slow tests - Long-running comprehensive tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 5+ minutes</li>
     *   <li>Perform comprehensive validation</li>
     *   <li>Test complex scenarios</li>
     *   <li>Include stress testing</li>
     *   <li>Run in CI/CD pipelines only</li>
     * </ul>
     * 
     * <p>Examples: Comprehensive performance demos, stress tests</p>
     */
    public static final String SLOW = "slow";
    
    /**
     * Flaky tests - Unstable tests needing investigation.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Be excluded from regular test runs</li>
     *   <li>Be investigated and fixed</li>
     *   <li>Be moved to appropriate category once stable</li>
     *   <li>Include detailed logging for debugging</li>
     * </ul>
     * 
     * <p>Examples: Tests with timing issues, environment-dependent tests</p>
     */
    public static final String FLAKY = "flaky";
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private TestCategories() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
