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
package dev.mars.peegeeq.test.consumer;

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.pgqueue.ConsumerMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ConsumerModeTestScenario functionality.
 * 
 * This test validates that the scenario configuration class correctly:
 * - Builds scenarios with different configurations
 * - Validates input parameters
 * - Provides consistent string representations
 * - Handles edge cases properly
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
@DisplayName("Consumer Mode Test Scenario Tests")
class ConsumerModeTestScenarioTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeTestScenarioTest.class);
    
    @Test
    @DisplayName("Test basic scenario creation with defaults")
    void testBasicScenarioCreation() {
        System.err.println("=== TEST METHOD STARTED: testBasicScenarioCreation ===");
        System.err.flush();
        
        logger.info("Testing basic scenario creation with defaults");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
            .consumerMode(ConsumerMode.HYBRID)
            .build();
        
        assertNotNull(scenario, "Scenario should be created");
        assertEquals(PerformanceProfile.HIGH_PERFORMANCE, scenario.getPerformanceProfile());
        assertEquals(ConsumerMode.HYBRID, scenario.getConsumerMode());
        assertEquals(Duration.ofSeconds(1), scenario.getPollingInterval());
        assertEquals(1, scenario.getThreadCount());
        assertEquals(100, scenario.getMessageCount());
        assertEquals(10, scenario.getBatchSize());
        
        logger.info("✅ Basic scenario creation test passed: {}", scenario);
        
        System.err.println("=== TEST METHOD COMPLETED: testBasicScenarioCreation ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test scenario creation with custom values")
    void testCustomScenarioCreation() {
        System.err.println("=== TEST METHOD STARTED: testCustomScenarioCreation ===");
        System.err.flush();
        
        logger.info("Testing scenario creation with custom values");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.MAXIMUM_PERFORMANCE)
            .consumerMode(ConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ofMillis(50))
            .threadCount(8)
            .messageCount(1000)
            .batchSize(25)
            .description("High-throughput test scenario")
            .build();
        
        assertEquals(PerformanceProfile.MAXIMUM_PERFORMANCE, scenario.getPerformanceProfile());
        assertEquals(ConsumerMode.LISTEN_NOTIFY_ONLY, scenario.getConsumerMode());
        assertEquals(Duration.ofMillis(50), scenario.getPollingInterval());
        assertEquals(8, scenario.getThreadCount());
        assertEquals(1000, scenario.getMessageCount());
        assertEquals(25, scenario.getBatchSize());
        assertEquals("High-throughput test scenario", scenario.getDescription());
        
        logger.info("✅ Custom scenario creation test passed: {}", scenario);
        
        System.err.println("=== TEST METHOD COMPLETED: testCustomScenarioCreation ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test basic factory method")
    void testBasicFactoryMethod() {
        System.err.println("=== TEST METHOD STARTED: testBasicFactoryMethod ===");
        System.err.flush();
        
        logger.info("Testing basic factory method");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.basic(
            PerformanceProfile.BASIC, ConsumerMode.POLLING_ONLY);
        
        assertEquals(PerformanceProfile.BASIC, scenario.getPerformanceProfile());
        assertEquals(ConsumerMode.POLLING_ONLY, scenario.getConsumerMode());
        
        logger.info("✅ Basic factory method test passed: {}", scenario);
        
        System.err.println("=== TEST METHOD COMPLETED: testBasicFactoryMethod ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test validation - null performance profile")
    void testValidationNullPerformanceProfile() {
        System.err.println("=== TEST METHOD STARTED: testValidationNullPerformanceProfile ===");
        System.err.flush();
        
        logger.info("Testing validation for null performance profile");
        
        assertThrows(NullPointerException.class, () -> {
            ConsumerModeTestScenario.builder()
                .performanceProfile(null)
                .consumerMode(ConsumerMode.HYBRID)
                .build();
        }, "Should throw NullPointerException for null performance profile");
        
        logger.info("✅ Null performance profile validation test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testValidationNullPerformanceProfile ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test validation - null consumer mode")
    void testValidationNullConsumerMode() {
        System.err.println("=== TEST METHOD STARTED: testValidationNullConsumerMode ===");
        System.err.flush();
        
        logger.info("Testing validation for null consumer mode");
        
        assertThrows(NullPointerException.class, () -> {
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.STANDARD)
                .consumerMode(null)
                .build();
        }, "Should throw NullPointerException for null consumer mode");
        
        logger.info("✅ Null consumer mode validation test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testValidationNullConsumerMode ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test validation - zero polling interval for POLLING_ONLY mode")
    void testValidationZeroPollingInterval() {
        System.err.println("=== TEST METHOD STARTED: testValidationZeroPollingInterval ===");
        System.err.flush();
        
        logger.info("Testing validation for zero polling interval with POLLING_ONLY mode");
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.STANDARD)
                .consumerMode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ZERO)
                .build();
        }, "Should throw IllegalArgumentException for zero polling interval with POLLING_ONLY mode");
        
        logger.info("✅ Zero polling interval validation test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testValidationZeroPollingInterval ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test validation - negative values")
    void testValidationNegativeValues() {
        System.err.println("=== TEST METHOD STARTED: testValidationNegativeValues ===");
        System.err.flush();
        
        logger.info("Testing validation for negative values");
        
        // Negative thread count
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerModeTestScenario.builder()
                .threadCount(-1)
                .build();
        }, "Should throw IllegalArgumentException for negative thread count");
        
        // Negative message count
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerModeTestScenario.builder()
                .messageCount(-1)
                .build();
        }, "Should throw IllegalArgumentException for negative message count");
        
        // Negative batch size
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerModeTestScenario.builder()
                .batchSize(-1)
                .build();
        }, "Should throw IllegalArgumentException for negative batch size");
        
        logger.info("✅ Negative values validation test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testValidationNegativeValues ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test scenario name and description methods")
    void testScenarioNameAndDescription() {
        System.err.println("=== TEST METHOD STARTED: testScenarioNameAndDescription ===");
        System.err.flush();
        
        logger.info("Testing scenario name and description methods");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
            .consumerMode(ConsumerMode.HYBRID)
            .description("Custom test description")
            .build();
        
        String scenarioName = scenario.getScenarioName();
        assertEquals("HIGH_PERFORMANCE-HYBRID", scenarioName);
        
        String description = scenario.getDetailedDescription();
        assertEquals("Custom test description", description);
        
        // Test auto-generated description
        ConsumerModeTestScenario scenarioNoDesc = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.BASIC)
            .consumerMode(ConsumerMode.POLLING_ONLY)
            .build();
        
        String autoDescription = scenarioNoDesc.getDetailedDescription();
        assertTrue(autoDescription.contains("Basic Testing"));
        assertTrue(autoDescription.contains("POLLING_ONLY"));
        
        logger.info("✅ Scenario name and description test passed");
        logger.info("Scenario name: {}", scenarioName);
        logger.info("Custom description: {}", description);
        logger.info("Auto description: {}", autoDescription);
        
        System.err.println("=== TEST METHOD COMPLETED: testScenarioNameAndDescription ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Test equals and hashCode")
    void testEqualsAndHashCode() {
        System.err.println("=== TEST METHOD STARTED: testEqualsAndHashCode ===");
        System.err.flush();
        
        logger.info("Testing equals and hashCode methods");
        
        ConsumerModeTestScenario scenario1 = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.STANDARD)
            .consumerMode(ConsumerMode.HYBRID)
            .threadCount(2)
            .build();
        
        ConsumerModeTestScenario scenario2 = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.STANDARD)
            .consumerMode(ConsumerMode.HYBRID)
            .threadCount(2)
            .build();
        
        ConsumerModeTestScenario scenario3 = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
            .consumerMode(ConsumerMode.HYBRID)
            .threadCount(2)
            .build();
        
        assertEquals(scenario1, scenario2, "Identical scenarios should be equal");
        assertEquals(scenario1.hashCode(), scenario2.hashCode(), "Identical scenarios should have same hash code");
        assertNotEquals(scenario1, scenario3, "Different scenarios should not be equal");
        
        logger.info("✅ Equals and hashCode test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testEqualsAndHashCode ===");
        System.err.flush();
    }
}
