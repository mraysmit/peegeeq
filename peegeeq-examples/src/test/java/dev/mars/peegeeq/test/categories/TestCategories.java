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
 * Test category constants for peegeeq-examples module.
 * 
 * <p>This class provides standardized test category constants used with JUnit 5 {@code @Tag} annotations
 * to enable selective test execution through Maven profiles. The categorization system allows developers
 * to run different subsets of tests based on their needs:</p>
 * 
 * <h3>Test Categories:</h3>
 * <ul>
 *   <li><strong>CORE</strong> - Fast unit tests for daily development (target: &lt;30s total, &lt;1s per test)</li>
 *   <li><strong>INTEGRATION</strong> - Tests with TestContainers and real infrastructure (5-10 minutes)</li>
 *   <li><strong>PERFORMANCE</strong> - Load and throughput tests (10-15 minutes)</li>
 *   <li><strong>SMOKE</strong> - Ultra-fast basic verification (&lt;30s total)</li>
 *   <li><strong>SLOW</strong> - Long-running comprehensive tests (15+ minutes)</li>
 *   <li><strong>FLAKY</strong> - Unstable tests needing investigation</li>
 * </ul>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Core test - fast unit test with mocked dependencies
 * @Tag(TestCategories.CORE)
 * class CloudEventsObjectMapperTest {
 *     @Test
 *     void testCloudEventsJacksonIntegration() {
 *         // Fast unit test with ObjectMapper
 *     }
 * }
 * 
 * // Integration test - with TestContainers and real PostgreSQL
 * @Tag(TestCategories.INTEGRATION)
 * class SimpleNativeQueueTest {
 *     @Test
 *     void testNativeQueueWithPostgreSQL() {
 *         // Test with real PostgreSQL database
 *     }
 * }
 * 
 * // Performance test - load and throughput testing
 * @Tag(TestCategories.PERFORMANCE)
 * class HighFrequencyProducerConsumerTest {
 *     @Test
 *     void testHighThroughputMessageProcessing() {
 *         // Performance benchmarks with real infrastructure
 *     }
 * }
 * }</pre>
 * 
 * <h3>Maven Profile Usage:</h3>
 * <pre>{@code
 * # Daily development (core tests only)
 * mvn test                           # ~30 seconds
 * 
 * # Quick smoke tests
 * mvn test -Psmoke-tests            # ~30 seconds
 * 
 * # Integration testing
 * mvn test -Pintegration-tests      # ~5-10 minutes
 * 
 * # Performance benchmarks
 * mvn test -Pperformance-tests      # ~10-15 minutes
 * 
 * # Comprehensive testing
 * mvn test -Pall-tests              # ~20+ minutes
 * }</pre>
 * 
 * <h3>Examples Module Specific Guidelines:</h3>
 * <ul>
 *   <li><strong>CORE</strong>: ObjectMapper tests, utility classes, configuration validation</li>
 *   <li><strong>SMOKE</strong>: Basic validation, placeholder tests, quick checks</li>
 *   <li><strong>INTEGRATION</strong>: TestContainers tests, database integration, queue operations</li>
 *   <li><strong>PERFORMANCE</strong>: High-frequency tests, throughput benchmarks, load testing</li>
 *   <li><strong>SLOW</strong>: Comprehensive scenarios, stress tests, long-running operations</li>
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
     *   <li>Test ObjectMapper, utilities, configuration classes</li>
     * </ul>
     * 
     * <p>Examples: CloudEventsObjectMapperTest, configuration validation, utility functions</p>
     */
    public static final String CORE = "core";
    
    /**
     * Integration tests - Tests with TestContainers and real infrastructure.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 5-10 minutes total</li>
     *   <li>Use TestContainers with real databases</li>
     *   <li>Test complete integration scenarios</li>
     *   <li>Validate queue operations with PostgreSQL</li>
     *   <li>Test native queue, outbox, and bitemporal patterns</li>
     * </ul>
     * 
     * <p>Examples: SimpleNativeQueueTest, BiTemporalEventStoreExampleTest, outbox pattern tests</p>
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Performance tests - Load and throughput tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 10-15 minutes total</li>
     *   <li>Measure throughput and latency</li>
     *   <li>Test high-frequency message processing</li>
     *   <li>Validate performance under load</li>
     *   <li>Generate performance metrics</li>
     * </ul>
     * 
     * <p>Examples: HighFrequencyProducerConsumerTest, performance benchmarks, load tests</p>
     */
    public static final String PERFORMANCE = "performance";
    
    /**
     * Smoke tests - Ultra-fast basic verification.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in under 30 seconds total</li>
     *   <li>Each test completes in under 10 seconds</li>
     *   <li>Verify basic functionality only</li>
     *   <li>Use minimal resources</li>
     *   <li>Provide quick feedback on example changes</li>
     * </ul>
     * 
     * <p>Examples: Basic validation, placeholder tests, quick configuration checks</p>
     */
    public static final String SMOKE = "smoke";
    
    /**
     * Slow tests - Long-running comprehensive tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 15+ minutes</li>
     *   <li>Perform comprehensive validation</li>
     *   <li>Test complex scenarios</li>
     *   <li>Include stress testing</li>
     *   <li>Run in CI/CD pipelines only</li>
     * </ul>
     * 
     * <p>Examples: Comprehensive integration tests, stress tests, complex scenarios</p>
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
     * <p>Examples: Environment-dependent tests, timing-sensitive tests, connection issues</p>
     */
    public static final String FLAKY = "flaky";
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private TestCategories() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
