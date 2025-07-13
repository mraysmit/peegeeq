package dev.mars.peegeeq.db;

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


import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Comprehensive test suite for all PeeGeeQ components.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Suite
@SuiteDisplayName("PeeGeeQ Comprehensive Test Suite")
@SelectPackages({
    "dev.mars.peegeeq.db.config",
    "dev.mars.peegeeq.db.migration", 
    "dev.mars.peegeeq.db.metrics",
    "dev.mars.peegeeq.db.health",
    "dev.mars.peegeeq.db.resilience",
    "dev.mars.peegeeq.db.deadletter",
    "dev.mars.peegeeq.db.client",
    "dev.mars.peegeeq.db.connection",
    "dev.mars.peegeeq.db.transaction"
})
@IncludeClassNamePatterns(".*Test.*")
public class PeeGeeQTestSuite {
    // Test suite configuration class - no implementation needed
}
