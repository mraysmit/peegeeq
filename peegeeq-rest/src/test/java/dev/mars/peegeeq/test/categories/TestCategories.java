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

package dev.mars.peegeeq.test.categories;

/**
 * Test category constants for JUnit 5 @Tag annotations.
 * 
 * <p>This class defines standardized test categories used throughout the peegeeq-rest module
 * to enable selective test execution through Maven profiles. The categorization system
 * provides fast development feedback cycles while maintaining comprehensive test coverage.</p>
 * 
 * <h3>Category Guidelines:</h3>
 * <ul>
 *   <li><strong>CORE</strong>: Fast unit tests (&lt; 30 seconds total, &lt; 1 second per test), no external dependencies</li>
 *   <li><strong>INTEGRATION</strong>: Tests with real infrastructure (TestContainers, REST servers), 1-3 minutes total</li>
 *   <li><strong>PERFORMANCE</strong>: Load and throughput tests, 2-5 minutes</li>
 *   <li><strong>SMOKE</strong>: Ultra-fast basic verification (&lt; 10 seconds total)</li>
 *   <li><strong>SLOW</strong>: Long-running comprehensive tests (5+ minutes)</li>
 *   <li><strong>FLAKY</strong>: Unstable tests requiring investigation (excluded by default)</li>
 * </ul>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Core unit test
 * @Tag(TestCategories.CORE)
 * public class ConfigurationValidationTest { ... }
 * 
 * // Integration test with TestContainers
 * @Tag(TestCategories.INTEGRATION)
 * @Testcontainers
 * public class RestServerIntegrationTest { ... }
 * 
 * // Performance benchmark
 * @Tag(TestCategories.PERFORMANCE)
 * public class RestApiPerformanceTest { ... }
 * }</pre>
 * 
 * <h3>Maven Profile Execution:</h3>
 * <pre>{@code
 * mvn test                    # CORE tests only (default, ~10 seconds)
 * mvn test -Pintegration-tests # Integration tests (~3 minutes)
 * mvn test -Pperformance-tests # Performance tests (~5 minutes)
 * mvn test -Pall-tests        # All tests except flaky (~8 minutes)
 * }</pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-26
 * @version 1.0
 * @see org.junit.jupiter.api.Tag
 */
public final class TestCategories {
    
    /**
     * Fast unit tests for immediate development feedback.
     * 
     * <p><strong>Characteristics:</strong></p>
     * <ul>
     *   <li>Execution time: &lt; 30 seconds total, &lt; 1 second per test</li>
     *   <li>Dependencies: None (mocked dependencies only)</li>
     *   <li>Parallel execution: Methods (4 threads)</li>
     *   <li>Purpose: Daily development workflow</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Configuration validation, JSON serialization, 
     * request/response mapping, business logic validation</p>
     */
    public static final String CORE = "core";
    
    /**
     * Tests with real infrastructure dependencies.
     * 
     * <p><strong>Characteristics:</strong></p>
     * <ul>
     *   <li>Execution time: 1-3 minutes total</li>
     *   <li>Dependencies: TestContainers, PostgreSQL, REST servers</li>
     *   <li>Parallel execution: Classes (2 threads)</li>
     *   <li>Purpose: Pre-commit validation</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> REST API endpoints, database integration, 
     * end-to-end workflows, server startup/shutdown</p>
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Load and throughput tests.
     * 
     * <p><strong>Characteristics:</strong></p>
     * <ul>
     *   <li>Execution time: 2-5 minutes</li>
     *   <li>Dependencies: TestContainers, PostgreSQL, may require specific hardware</li>
     *   <li>Parallel execution: None (sequential to avoid resource contention)</li>
     *   <li>Purpose: Performance validation and benchmarking</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> REST API throughput, concurrent request handling, 
     * database connection pooling performance</p>
     */
    public static final String PERFORMANCE = "performance";
    
    /**
     * Ultra-fast basic verification tests.
     * 
     * <p><strong>Characteristics:</strong></p>
     * <ul>
     *   <li>Execution time: &lt; 10 seconds total</li>
     *   <li>Dependencies: None</li>
     *   <li>Parallel execution: Methods (8 threads)</li>
     *   <li>Purpose: Pre-commit hooks, CI/CD fast feedback</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Basic configuration loading, constant validation, 
     * simple object creation</p>
     */
    public static final String SMOKE = "smoke";
    
    /**
     * Long-running comprehensive tests.
     * 
     * <p><strong>Characteristics:</strong></p>
     * <ul>
     *   <li>Execution time: 5+ minutes</li>
     *   <li>Dependencies: Various</li>
     *   <li>Parallel execution: None</li>
     *   <li>Purpose: Comprehensive validation, nightly builds</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Endurance tests, comprehensive integration scenarios, 
     * stress testing</p>
     */
    public static final String SLOW = "slow";
    
    /**
     * Unstable tests requiring investigation.
     * 
     * <p><strong>Characteristics:</strong></p>
     * <ul>
     *   <li>Execution: Excluded from all profiles by default</li>
     *   <li>Dependencies: Various</li>
     *   <li>Purpose: Isolation of problematic tests</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong> Tests that intermittently fail due to timing issues, 
     * external service dependencies, or other non-deterministic factors should be 
     * tagged as FLAKY until the root cause is identified and resolved.</p>
     */
    public static final String FLAKY = "flaky";
    
    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static constants.
     */
    private TestCategories() {
        // Utility class - no instantiation
    }
}
