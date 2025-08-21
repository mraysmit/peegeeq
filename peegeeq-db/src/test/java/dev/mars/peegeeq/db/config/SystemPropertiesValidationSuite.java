package dev.mars.peegeeq.db.config;

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

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Test suite that runs all system properties validation tests.
 * 
 * This suite validates that the following system properties work as expected:
 * - peegeeq.queue.max-retries: Controls actual retry attempts before dead letter
 * - peegeeq.consumer.threads: Controls actual number of consumer threads created  
 * - peegeeq.queue.polling-interval: Controls actual polling frequency timing
 * - peegeeq.queue.batch-size: Controls actual batch processing behavior
 * - Property override behavior: System properties override configuration files
 * 
 * Usage:
 * Run this test suite to validate all system properties behavior in one go.
 * Individual test classes can also be run separately for focused testing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
@Suite
@SuiteDisplayName("System Properties Validation Test Suite")
@SelectClasses({
    SystemPropertiesValidationTest.class,
    SystemPropertiesValidationTestPart2.class
})
public class SystemPropertiesValidationSuite {
    // This class serves as a test suite runner
    // The actual tests are in the selected classes
}
