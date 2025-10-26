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
 * Test category constants for peegeeq-performance-test-harness module.
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
 * // Smoke test - quick configuration validation
 * @Tag(TestCategories.SMOKE)
 * class PerformanceConfigTest {
 *     @Test
 *     void testConfigurationLoading() {
 *         // Fast configuration validation without execution
 *     }
 * }
 * 
 * // Performance test - comprehensive performance harness execution
 * @Tag(TestCategories.PERFORMANCE)
 * @EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
 * class PerformanceTestHarnessIntegrationTest {
 *     @Test
 *     void testCompletePerformanceTestExecution() {
 *         // Full performance test harness with all modules
 *     }
 * }
 * 
 * // Slow test - long-running stress tests
 * @Tag(TestCategories.SLOW)
 * @EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
 * class StressTestSuite {
 *     @Test
 *     void testLongRunningStressTest() {
 *         // Extended stress testing with high load
 *     }
 * }
 * }</pre>
 * 
 * <h3>Maven Profile Usage:</h3>
 * <pre>{@code
 * # Daily development (smoke tests only)
 * mvn test                           # ~5 seconds
 * 
 * # Performance benchmarks
 * mvn test -Pperformance-tests       # ~3-5 minutes
 * 
 * # Long-running stress tests
 * mvn test -Pslow-tests             # ~15+ minutes
 * 
 * # Comprehensive testing
 * mvn test -Pall-tests              # ~20+ minutes
 * }</pre>
 * 
 * <h3>Performance Test Harness Module Specific Guidelines:</h3>
 * <ul>
 *   <li><strong>SMOKE</strong>: Configuration validation, test suite enumeration, basic setup</li>
 *   <li><strong>PERFORMANCE</strong>: Standard performance tests (5-10 minutes)</li>
 *   <li><strong>SLOW</strong>: Extended stress tests, load tests, comprehensive benchmarks</li>
 *   <li><strong>INTEGRATION</strong>: Tests requiring external infrastructure setup</li>
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
     *   <li>Test configuration classes, utility functions, constants</li>
     * </ul>
     * 
     * <p>Examples: Configuration validation, test suite enumeration, utility tests</p>
     */
    public static final String CORE = "core";
    
    /**
     * Integration tests - Tests with TestContainers and real infrastructure.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 1-3 minutes total</li>
     *   <li>Use TestContainers with real databases</li>
     *   <li>Test complete integration scenarios</li>
     *   <li>Validate infrastructure setup and teardown</li>
     *   <li>Test harness integration with real PeeGeeQ modules</li>
     * </ul>
     * 
     * <p>Examples: Database integration tests, module integration validation</p>
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Performance tests - Load and throughput tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 2-5 minutes total</li>
     *   <li>Measure throughput and latency</li>
     *   <li>Test standard performance scenarios</li>
     *   <li>Validate performance harness execution</li>
     *   <li>Generate performance metrics and reports</li>
     * </ul>
     * 
     * <p>Examples: Standard performance test harness execution, benchmark validation</p>
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
     *   <li>Provide quick feedback on configuration and setup</li>
     * </ul>
     * 
     * <p>Examples: Configuration loading, test suite enumeration, basic validation</p>
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
     * <p>Examples: Extended stress tests, comprehensive load tests, full benchmark suites</p>
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
     *   <li>Often require specific environment setup</li>
     * </ul>
     * 
     * <p>Examples: Environment-dependent tests, timing-sensitive tests</p>
     */
    public static final String FLAKY = "flaky";
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private TestCategories() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
