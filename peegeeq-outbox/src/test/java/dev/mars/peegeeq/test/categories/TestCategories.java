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
 * Test category constants for JUnit 5 @Tag annotations.
 * 
 * <p>These categories enable selective test execution through Maven profiles:
 * <ul>
 *   <li><strong>CORE</strong> - Fast unit tests (< 30 seconds total, < 1 second per test)</li>
 *   <li><strong>INTEGRATION</strong> - Tests with real infrastructure (TestContainers, PostgreSQL)</li>
 *   <li><strong>PERFORMANCE</strong> - Load and throughput tests (2-5 minutes)</li>
 *   <li><strong>SMOKE</strong> - Ultra-fast basic verification (< 10 seconds total)</li>
 *   <li><strong>SLOW</strong> - Long-running comprehensive tests (5+ minutes)</li>
 *   <li><strong>FLAKY</strong> - Unstable tests needing investigation</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * @Test
 * @Tag(TestCategories.CORE)
 * void testBasicFunctionality() { ... }
 * 
 * @Test
 * @Tag(TestCategories.INTEGRATION)
 * @Testcontainers
 * void testWithDatabase() { ... }
 * 
 * @Test
 * @Tag(TestCategories.PERFORMANCE)
 * void testThroughput() { ... }
 * }</pre>
 * 
 * <p><strong>Maven Profile Usage:</strong>
 * <pre>
 * mvn test                           # Core tests only (default)
 * mvn test -Pintegration-tests       # Integration tests only
 * mvn test -Pperformance-tests       # Performance tests only
 * mvn test -Pall-tests               # All tests except flaky
 * </pre>
 * 
 * @see org.junit.jupiter.api.Tag
 */
public final class TestCategories {
    
    /**
     * Core functionality tests - fast unit tests with no external dependencies.
     * 
     * <p><strong>Characteristics:</strong>
     * <ul>
     *   <li>No TestContainers or external services</li>
     *   <li>Pure unit tests and mocked dependencies</li>
     *   <li>< 1 second per test, < 30 seconds total</li>
     *   <li>Suitable for rapid development feedback</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Configuration validation, message filtering logic,
     * retry mechanism unit tests, circuit breaker logic tests.
     */
    public static final String CORE = "core";
    
    /**
     * Integration tests with real infrastructure dependencies.
     * 
     * <p><strong>Characteristics:</strong>
     * <ul>
     *   <li>Uses TestContainers with PostgreSQL</li>
     *   <li>Tests complete workflows end-to-end</li>
     *   <li>1-3 minutes total execution time</li>
     *   <li>Validates real database interactions</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Outbox pattern tests, transaction management,
     * consumer group functionality, database schema validation.
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Performance and load tests.
     * 
     * <p><strong>Characteristics:</strong>
     * <ul>
     *   <li>Measures throughput, latency, resource usage</li>
     *   <li>2-5 minutes execution time</li>
     *   <li>May require specific hardware/environment</li>
     *   <li>Sequential execution to avoid resource contention</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Message throughput benchmarks, concurrent processing
     * tests, memory usage validation, connection pool performance.
     */
    public static final String PERFORMANCE = "performance";
    
    /**
     * Ultra-fast smoke tests for basic verification.
     * 
     * <p><strong>Characteristics:</strong>
     * <ul>
     *   <li>< 1 second per test, < 10 seconds total</li>
     *   <li>Basic sanity checks and configuration validation</li>
     *   <li>No external dependencies</li>
     *   <li>Suitable for pre-commit hooks</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Configuration loading, basic object creation,
     * simple validation logic, constant verification.
     */
    public static final String SMOKE = "smoke";
    
    /**
     * Long-running comprehensive tests.
     * 
     * <p><strong>Characteristics:</strong>
     * <ul>
     *   <li>5+ minutes execution time</li>
     *   <li>Comprehensive end-to-end scenarios</li>
     *   <li>Resource-intensive operations</li>
     *   <li>Typically run in CI/CD only</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong> Long-running stability tests, comprehensive
     * integration scenarios, stress tests, endurance tests.
     */
    public static final String SLOW = "slow";
    
    /**
     * Flaky or unstable tests requiring investigation.
     * 
     * <p><strong>Characteristics:</strong>
     * <ul>
     *   <li>Intermittent failures</li>
     *   <li>Environment-dependent behavior</li>
     *   <li>Excluded from regular test runs</li>
     *   <li>Requires investigation and stabilization</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong> Tag tests that fail intermittently until they
     * can be investigated and fixed. These tests are excluded from all profiles
     * by default.
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
