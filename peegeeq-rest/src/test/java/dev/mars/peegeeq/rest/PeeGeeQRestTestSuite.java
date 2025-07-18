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

package dev.mars.peegeeq.rest;

import org.junit.platform.suite.api.*;

/**
 * Comprehensive test suite for PeeGeeQ REST API module.
 * 
 * This test suite runs all integration tests for the REST API including:
 * - Database setup service tests
 * - SQL template processor tests
 * - Database template manager tests
 * - REST server integration tests
 * 
 * All tests use TestContainers for real PostgreSQL integration testing
 * without mocking, ensuring comprehensive coverage of the complete system.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Suite
@SuiteDisplayName("PeeGeeQ REST API Comprehensive Test Suite")
@SelectPackages({
    "dev.mars.peegeeq.rest",
    "dev.mars.peegeeq.rest.setup"
})
@IncludeClassNamePatterns(".*Test.*")
@ExcludeClassNamePatterns(".*Abstract.*")
public class PeeGeeQRestTestSuite {
    // Test suite configuration class - no implementation needed
    
    /*
     * This test suite will execute the following test classes:
     * 
     * 1. PeeGeeQRestServerTest
     *    - Tests the complete Vert.x REST server functionality
     *    - Verifies all HTTP endpoints work correctly
     *    - Tests CORS, health checks, and error handling
     *    - Uses real PostgreSQL database via TestContainers
     * 
     * 2. DatabaseSetupServiceIntegrationTest
     *    - Tests complete database setup functionality
     *    - Verifies template database creation
     *    - Tests queue and event store creation
     *    - Tests resource cleanup and error handling
     * 
     * 3. SqlTemplateProcessorTest
     *    - Tests SQL template loading and processing
     *    - Verifies parameter substitution
     *    - Tests template execution against real database
     *    - Verifies database schema creation
     * 
     * 4. DatabaseTemplateManagerTest
     *    - Tests PostgreSQL template database functionality
     *    - Verifies CREATE DATABASE TEMPLATE operations
     *    - Tests encoding and inheritance
     *    - Tests error handling for invalid operations
     * 
     * Test Coverage:
     * - API Interfaces: Complete coverage of all setup interfaces
     * - Configuration Classes: All configuration objects tested
     * - Database Templates: All SQL templates tested with real database
     * - Template Processing: Complete parameter substitution testing
     * - REST API: All HTTP endpoints tested end-to-end
     * - Error Handling: Comprehensive error scenario testing
     * 
     * Prerequisites:
     * - Docker must be running for TestContainers
     * - Java 21 or higher
     * - Maven 3.8+
     * 
     * Running the Test Suite:
     * 
     * # Run all REST API tests
     * mvn test -pl peegeeq-rest
     * 
     * # Run specific test suite
     * mvn test -Dtest=PeeGeeQRestTestSuite
     * 
     * # Run with debug logging
     * mvn test -Dtest=PeeGeeQRestTestSuite -Dpeegeeq.logging.level=DEBUG
     * 
     * # Run with specific PostgreSQL version
     * mvn test -Dtest=PeeGeeQRestTestSuite -Dtestcontainers.postgres.image=postgres:15
     * 
     * Expected Results:
     * - All tests should pass with real PostgreSQL database
     * - No mocking is used - all tests are true integration tests
     * - Database operations are verified by querying actual database state
     * - REST endpoints are tested with real HTTP requests
     * - Template processing is verified with actual SQL execution
     * 
     * Performance Notes:
     * - Tests use TestContainers which may take time to start
     * - Each test class uses its own PostgreSQL container for isolation
     * - Tests are ordered to minimize database setup overhead
     * - Shared memory is configured for better PostgreSQL performance
     */
}
