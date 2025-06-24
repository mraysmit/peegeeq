package dev.mars.peegeeq.db;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Comprehensive test suite for all PeeGeeQ components.
 * This suite runs all unit and integration tests across all modules.
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
