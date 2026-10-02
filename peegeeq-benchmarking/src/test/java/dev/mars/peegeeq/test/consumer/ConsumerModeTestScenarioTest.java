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

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;

import org.junit.jupiter.api.Tag;
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
@Tag(TestCategories.CORE)
@DisplayName("Consumer Mode Test Scenario Tests")
class ConsumerModeTestScenarioTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeTestScenarioTest.class);
    
    @Test
    @DisplayName("Test basic scenario creation with defaults")
    void testBasicScenarioCreation() {
        logger.info("=== TEST METHOD STARTED: testBasicScenarioCreation ===");
        
        logger.info("Testing basic scenario creation with defaults");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
            .consumerMode(TestConsumerMode.HYBRID)
            .build();

        assertNotNull(scenario, "Scenario should be created");
        assertEquals(PerformanceProfile.HIGH_PERFORMANCE, scenario.getPerformanceProfile());
        assertEquals(TestConsumerMode.HYBRID, scenario.getConsumerMode());
        assertEquals(Duration.ofSeconds(1), scenario.getPollingInterval());
        assertEquals(1, scenario.getThreadCount());
        assertEquals(100, scenario.getMessageCount());
        assertEquals(10, scenario.getBatchSize());
        
        logger.info("Basic scenario creation test passed: {}", scenario);
        
        logger.info("=== TEST METHOD COMPLETED: testBasicScenarioCreation ===");
    }
    
    @Test
    @DisplayName("Test scenario creation with custom values")
    void testCustomScenarioCreation() {
        logger.info("=== TEST METHOD STARTED: testCustomScenarioCreation ===");
        
        logger.info("Testing scenario creation with custom values");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.MAXIMUM_PERFORMANCE)
            .consumerMode(TestConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ofMillis(50))
            .threadCount(8)
            .messageCount(1000)
            .batchSize(25)
            .description("High-throughput test scenario")
            .build();

        assertEquals(PerformanceProfile.MAXIMUM_PERFORMANCE, scenario.getPerformanceProfile());
        assertEquals(TestConsumerMode.LISTEN_NOTIFY_ONLY, scenario.getConsumerMode());
        assertEquals(Duration.ofMillis(50), scenario.getPollingInterval());
        assertEquals(8, scenario.getThreadCount());
        assertEquals(1000, scenario.getMessageCount());
        assertEquals(25, scenario.getBatchSize());
        assertEquals("High-throughput test scenario", scenario.getDescription());
        
        logger.info("Custom scenario creation test passed: {}", scenario);
        
        logger.info("=== TEST METHOD COMPLETED: testCustomScenarioCreation ===");
    }
    
    @Test
    @DisplayName("Test basic factory method")
    void testBasicFactoryMethod() {
        logger.info("=== TEST METHOD STARTED: testBasicFactoryMethod ===");
        
        logger.info("Testing basic factory method");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.basic(
            PerformanceProfile.BASIC, TestConsumerMode.POLLING_ONLY);

        assertEquals(PerformanceProfile.BASIC, scenario.getPerformanceProfile());
        assertEquals(TestConsumerMode.POLLING_ONLY, scenario.getConsumerMode());
        
        logger.info("Basic factory method test passed: {}", scenario);
        
        logger.info("=== TEST METHOD COMPLETED: testBasicFactoryMethod ===");
    }
    
    @Test
    @DisplayName("Test validation - null performance profile")
    void testValidationNullPerformanceProfile() {
        logger.info("=== TEST METHOD STARTED: testValidationNullPerformanceProfile ===");
        
        logger.info("Testing validation for null performance profile");
        
        assertThrows(NullPointerException.class, () -> {
            ConsumerModeTestScenario.builder()
                .performanceProfile(null)
                .consumerMode(TestConsumerMode.HYBRID)
                .build();
        }, "Should throw NullPointerException for null performance profile");
        
        logger.info("Null performance profile validation test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testValidationNullPerformanceProfile ===");
    }
    
    @Test
    @DisplayName("Test validation - null consumer mode")
    void testValidationNullConsumerMode() {
        logger.info("=== TEST METHOD STARTED: testValidationNullConsumerMode ===");
        
        logger.info("Testing validation for null consumer mode");
        
        assertThrows(NullPointerException.class, () -> {
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.STANDARD)
                .consumerMode(null)
                .build();
        }, "Should throw NullPointerException for null consumer mode");
        
        logger.info("Null consumer mode validation test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testValidationNullConsumerMode ===");
    }
    
    @Test
    @DisplayName("Test validation - zero polling interval for POLLING_ONLY mode")
    void testValidationZeroPollingInterval() {
        logger.info("=== TEST METHOD STARTED: testValidationZeroPollingInterval ===");
        
        logger.info("Testing validation for zero polling interval with POLLING_ONLY mode");
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.STANDARD)
                .consumerMode(TestConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ZERO)
                .build();
        }, "Should throw IllegalArgumentException for zero polling interval with POLLING_ONLY mode");
        
        logger.info("Zero polling interval validation test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testValidationZeroPollingInterval ===");
    }
    
    @Test
    @DisplayName("Test validation - negative values")
    void testValidationNegativeValues() {
        logger.info("=== TEST METHOD STARTED: testValidationNegativeValues ===");
        
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
        
        logger.info("Negative values validation test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testValidationNegativeValues ===");
    }
    
    @Test
    @DisplayName("Test scenario name and description methods")
    void testScenarioNameAndDescription() {
        logger.info("=== TEST METHOD STARTED: testScenarioNameAndDescription ===");
        
        logger.info("Testing scenario name and description methods");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
            .consumerMode(TestConsumerMode.HYBRID)
            .description("Custom test description")
            .build();
        
        String scenarioName = scenario.getScenarioName();
        assertEquals("HIGH_PERFORMANCE-HYBRID", scenarioName);
        
        String description = scenario.getDetailedDescription();
        assertEquals("Custom test description", description);
        
        // Test auto-generated description
        ConsumerModeTestScenario scenarioNoDesc = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.BASIC)
            .consumerMode(TestConsumerMode.POLLING_ONLY)
            .build();
        
        String autoDescription = scenarioNoDesc.getDetailedDescription();
        assertTrue(autoDescription.contains("Basic Testing"));
        assertTrue(autoDescription.contains("POLLING_ONLY"));
        
        logger.info("Scenario name and description test passed");
        logger.info("Scenario name: {}", scenarioName);
        logger.info("Custom description: {}", description);
        logger.info("Auto description: {}", autoDescription);
        
        logger.info("=== TEST METHOD COMPLETED: testScenarioNameAndDescription ===");
    }
    
    @Test
    @DisplayName("Test equals and hashCode")
    void testEqualsAndHashCode() {
        logger.info("=== TEST METHOD STARTED: testEqualsAndHashCode ===");
        
        logger.info("Testing equals and hashCode methods");
        
        ConsumerModeTestScenario scenario1 = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.STANDARD)
            .consumerMode(TestConsumerMode.HYBRID)
            .threadCount(2)
            .build();

        ConsumerModeTestScenario scenario2 = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.STANDARD)
            .consumerMode(TestConsumerMode.HYBRID)
            .threadCount(2)
            .build();

        ConsumerModeTestScenario scenario3 = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
            .consumerMode(TestConsumerMode.HYBRID)
            .threadCount(2)
            .build();
        
        assertEquals(scenario1, scenario2, "Identical scenarios should be equal");
        assertEquals(scenario1.hashCode(), scenario2.hashCode(), "Identical scenarios should have same hash code");
        assertNotEquals(scenario1, scenario3, "Different scenarios should not be equal");
        
        logger.info("Equals and hashCode test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testEqualsAndHashCode ===");
    }
}
