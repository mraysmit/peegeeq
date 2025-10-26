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
 * Test category constants for peegeeq-service-manager module.
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
 * class PeeGeeQInstanceTest {
 *     @Test
 *     void testInstanceCreation() {
 *         // Fast model validation without external dependencies
 *     }
 * }
 * 
 * // Integration test - uses TestContainers with real Consul
 * @Tag(TestCategories.INTEGRATION)
 * @Testcontainers
 * class PeeGeeQServiceManagerIntegrationTest {
 *     @Container
 *     static ConsulContainer consul = new ConsulContainer("hashicorp/consul:1.15.3");
 *     
 *     @Test
 *     void testServiceRegistration() {
 *         // Real Consul integration test
 *     }
 * }
 * 
 * // Flaky test - disabled tests that need investigation
 * @Tag(TestCategories.FLAKY)
 * @Disabled("Requires manual Consul setup - use integration test instead")
 * class ConsulServiceDiscoveryTest {
 *     @Test
 *     void testServiceDiscovery() {
 *         // Test that requires manual Consul setup
 *     }
 * }
 * }</pre>
 * 
 * <h3>Maven Profile Usage:</h3>
 * <pre>{@code
 * # Daily development (core tests only)
 * mvn test                           # ~5 seconds
 * 
 * # Pre-commit validation
 * mvn test -Pintegration-tests       # ~1-2 minutes
 * 
 * # Performance benchmarks
 * mvn test -Pperformance-tests       # ~3-5 minutes
 * 
 * # Comprehensive testing
 * mvn test -Pall-tests              # ~5-10 minutes
 * }</pre>
 * 
 * <h3>Service Manager Module Specific Guidelines:</h3>
 * <ul>
 *   <li><strong>CORE</strong>: Model classes, load balancer logic, utility functions</li>
 *   <li><strong>INTEGRATION</strong>: Consul integration, service discovery, HTTP endpoints</li>
 *   <li><strong>PERFORMANCE</strong>: Load balancing performance, service discovery throughput</li>
 *   <li><strong>FLAKY</strong>: Tests requiring manual infrastructure setup</li>
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
     *   <li>Test model classes, load balancer logic, utility functions</li>
     * </ul>
     * 
     * <p>Examples: PeeGeeQInstance model tests, LoadBalancer logic tests</p>
     */
    public static final String CORE = "core";
    
    /**
     * Integration tests - Tests with TestContainers and real infrastructure.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 1-3 minutes total</li>
     *   <li>Use TestContainers with real Consul</li>
     *   <li>Test complete integration scenarios</li>
     *   <li>Validate service discovery and registration</li>
     *   <li>Test HTTP endpoints and health checks</li>
     * </ul>
     * 
     * <p>Examples: Consul integration tests, service manager integration tests, health check tests</p>
     */
    public static final String INTEGRATION = "integration";
    
    /**
     * Performance tests - Load and throughput tests.
     * 
     * <p>These tests should:</p>
     * <ul>
     *   <li>Run in 2-5 minutes total</li>
     *   <li>Measure throughput and latency</li>
     *   <li>Test load balancing performance</li>
     *   <li>Validate service discovery scalability</li>
     *   <li>Monitor resource usage</li>
     * </ul>
     * 
     * <p>Examples: Load balancing performance tests, service discovery throughput tests</p>
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
     * <p>Examples: Basic model validation, simple utility tests</p>
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
     * <p>Examples: Comprehensive service discovery tests, stress tests</p>
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
     *   <li>Often require manual infrastructure setup</li>
     * </ul>
     * 
     * <p>Examples: Tests requiring manual Consul setup, environment-dependent tests</p>
     */
    public static final String FLAKY = "flaky";
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private TestCategories() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
