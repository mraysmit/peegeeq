package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
class PerformanceTestResultsGeneratorTest {

    @Test
    void generatesReportWithSystemInformationAndBenchmarkResult() {
        PerformanceTestResultsGenerator generator = new PerformanceTestResultsGenerator.Builder(
            "Test Suite",
            "Test Environment"
        )
            .addTest("Sample Test", "PASSED", "10.5 seconds")
            .addInfo("Test Info", "Sample information")
            .build();

        String report = generator.generateReport();

        assertNotNull(report, "Report should not be null");
        assertFalse(report.trim().isEmpty(), "Report should not be empty");
        assertTrue(report.contains("# Test Suite Performance Test Results"), "Should contain title");
        assertTrue(report.contains("##  Executive Summary"), "Should contain executive summary");
        assertTrue(report.contains("## System Configuration"), "Should contain system configuration");
        assertTrue(report.contains("##  Detailed Test Results"), "Should contain detailed results");
        assertTrue(report.contains("Sample Test"), "Should contain test name");
    }
}
