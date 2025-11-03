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
 * Test category constants for peegeeq-api module.
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
 * class MessageFilterTest {
 *     @Test
 *     void testByHeaderInWithNullValues() {
 *         // Fast unit test with mock Message objects
 *     }
 * }
 * 
 * // Smoke test - ultra-fast basic verification
 * @Tag(TestCategories.SMOKE)
 * class MessageTest {
 *     @Test
 *     void testMessageImplementation() {
 *         // Quick placeholder or basic validation
 *     }
 * }
 * 
 * // Integration test - with real infrastructure
 * @Tag(TestCategories.INTEGRATION)
 * class MessageApiIntegrationTest {
 *     @Test
 *     void testMessageProcessingWithDatabase() {
 *         // Test with real PostgreSQL database
 *     }
 * }
 * }</pre>
 * 
 * <h3>Maven Profile Usage:</h3>
 * <pre>{@code
 * # Daily development (core tests only)
 * mvn test                           # ~2 seconds
 * 
 * # Quick smoke tests
 * mvn test -Psmoke-tests            # ~1 second
 * 
 * # Integration testing
 * mvn test -Pintegration-tests      # ~1-3 minutes
 * 
 * # Comprehensive testing
 * mvn test -Pall-tests              # ~5 minutes
 * }</pre>
 * 
 * <h3>API Module Specific Guidelines:</h3>
 * <ul>
 *   <li><strong>CORE</strong>: Interface tests, filter logic, utility classes, constants</li>
 *   <li><strong>SMOKE</strong>: Basic validation, placeholder tests, quick checks</li>
 *   <li><strong>INTEGRATION</strong>: Tests requiring database or external services</li>
 *   <li><strong>PERFORMANCE</strong>: Message processing throughput, filter performance</li>
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
     *   <li>Test API interfaces, filter logic, utility classes</li>
     * </ul>
     * 
     * <p>Examples: MessageFilter tests, interface validation, utility functions</p>
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
     *   <li>Validate API with real implementations</li>
     *   <li>Test message processing with PostgreSQL</li>
     * </ul>
     * 
     * <p>Examples: Database integration tests, API implementation validation</p>
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Performance tests - Load and throughput tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 2-5 minutes total</li>
     *   <li>Measure throughput and latency</li>
     *   <li>Test message processing performance</li>
     *   <li>Validate filter performance</li>
     *   <li>Generate performance metrics</li>
     * </ul>
     * 
     * <p>Examples: Message filter performance, API throughput tests</p>
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
     *   <li>Provide quick feedback on API changes</li>
     * </ul>
     * 
     * <p>Examples: Basic validation, placeholder tests, quick interface checks</p>
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
     * <p>Examples: Comprehensive API validation, stress tests, complex scenarios</p>
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
