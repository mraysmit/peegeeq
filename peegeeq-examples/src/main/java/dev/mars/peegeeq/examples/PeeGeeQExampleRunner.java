package dev.mars.peegeeq.examples;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive runner for all PeeGeeQ examples and demonstrations.
 * 
 * This runner executes all available examples in a logical order, providing:
 * - Sequential execution of all examples
 * - Error handling and recovery
 * - Execution timing and statistics
 * - Selective example execution
 * - Comprehensive logging and reporting
 * 
 * Usage:
 * - Run all examples: java dev.mars.peegeeq.examples.PeeGeeQExampleRunner
 * - Run specific examples: java dev.mars.peegeeq.examples.PeeGeeQExampleRunner self-contained rest-api
 * - List available examples: java dev.mars.peegeeq.examples.PeeGeeQExampleRunner --list
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-02
 * @version 1.0
 */
public class PeeGeeQExampleRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExampleRunner.class);
    
    /**
     * Registry of all available examples with their metadata.
     */
    private static final Map<String, ExampleInfo> EXAMPLES = new LinkedHashMap<>();
    
    static {
        // Core examples (recommended order)
        EXAMPLES.put("self-contained", new ExampleInfo(
            "PeeGeeQSelfContainedDemo", 
            "Self-contained demo with Docker PostgreSQL",
            "Comprehensive demo showing all PeeGeeQ features with automatic setup",
            ExampleCategory.CORE,
            true // recommended first
        ));
        
        EXAMPLES.put("traditional", new ExampleInfo(
            "PeeGeeQExample",
            "Traditional example with external PostgreSQL",
            "Production readiness features with external database setup",
            ExampleCategory.CORE,
            false
        ));
        
        EXAMPLES.put("bitemporal", new ExampleInfo(
            "BiTemporalEventStoreExample",
            "Bi-temporal event store capabilities",
            "Event sourcing, temporal queries, and historical data reconstruction",
            ExampleCategory.CORE,
            false
        ));
        
        EXAMPLES.put("consumer-groups", new ExampleInfo(
            "ConsumerGroupExample",
            "Consumer groups and message routing",
            "Message filtering, load balancing, and consumer coordination",
            ExampleCategory.CORE,
            false
        ));
        
        EXAMPLES.put("multi-config", new ExampleInfo(
            "MultiConfigurationExample",
            "Multiple queue configurations",
            "Different queue types and configuration management patterns",
            ExampleCategory.CORE,
            false
        ));
        
        EXAMPLES.put("transactional", new ExampleInfo(
            "TransactionalBiTemporalExample",
            "Advanced transactional patterns",
            "Integration between queues and event stores with ACID guarantees",
            ExampleCategory.CORE,
            false
        ));
        
        // REST API examples
        EXAMPLES.put("rest-api", new ExampleInfo(
            "RestApiExample",
            "Comprehensive REST API usage",
            "Database setup, queue operations, and health checks via HTTP",
            ExampleCategory.REST_API,
            false
        ));
        
        EXAMPLES.put("rest-streaming", new ExampleInfo(
            "RestApiStreamingExample",
            "Real-time streaming capabilities",
            "WebSocket streaming and Server-Sent Events for real-time updates",
            ExampleCategory.REST_API,
            false
        ));
        
        // Service discovery examples
        EXAMPLES.put("service-discovery", new ExampleInfo(
            "ServiceDiscoveryExample",
            "Service discovery and federation",
            "Service registration, health monitoring, and load balancing",
            ExampleCategory.SERVICE_DISCOVERY,
            false
        ));
        
        // Implementation comparison
        EXAMPLES.put("native-vs-outbox", new ExampleInfo(
            "NativeVsOutboxComparisonExample",
            "Implementation comparison and benchmarking",
            "Side-by-side comparison of Native LISTEN/NOTIFY vs Outbox Pattern",
            ExampleCategory.COMPARISON,
            false
        ));
        
        // Advanced examples
        EXAMPLES.put("advanced-config", new ExampleInfo(
            "AdvancedConfigurationExample",
            "Production-ready configuration patterns",
            "Environment-specific configurations and best practices",
            ExampleCategory.ADVANCED,
            false
        ));
        
        EXAMPLES.put("message-priority", new ExampleInfo(
            "MessagePriorityExample",
            "Message priority handling",
            "Priority-based message ordering and processing",
            ExampleCategory.ADVANCED,
            false
        ));
        
        EXAMPLES.put("error-handling", new ExampleInfo(
            "EnhancedErrorHandlingExample",
            "Sophisticated error handling patterns",
            "Retry strategies, circuit breakers, and dead letter queues",
            ExampleCategory.ADVANCED,
            false
        ));
        
        EXAMPLES.put("security", new ExampleInfo(
            "SecurityConfigurationExample",
            "Security best practices and SSL/TLS",
            "SSL/TLS configuration and security monitoring",
            ExampleCategory.ADVANCED,
            false
        ));
        
        EXAMPLES.put("performance", new ExampleInfo(
            "PerformanceTuningExample",
            "Performance optimization techniques",
            "Connection pooling, throughput optimization, and monitoring",
            ExampleCategory.ADVANCED,
            false
        ));
        
        EXAMPLES.put("integration-patterns", new ExampleInfo(
            "IntegrationPatternsExample",
            "Distributed system integration patterns",
            "Microservices communication and event-driven architecture",
            ExampleCategory.ADVANCED,
            false
        ));
    }
    
    public static void main(String[] args) {
        // Display PeeGeeQ logo
        System.out.println();
        System.out.println("    ____            ______            ____");
        System.out.println("   / __ \\___  ___  / ____/__  ___    / __ \\");
        System.out.println("  / /_/ / _ \\/ _ \\/ / __/ _ \\/ _ \\  / / / /");
        System.out.println(" / ____/  __/  __/ /_/ /  __/ / /_/ /");
        System.out.println("/_/    \\___/\\___/\\____/\\___/\\___/  \\___\\_\\");
        System.out.println();
        System.out.println("PostgreSQL Event-Driven Queue System");
        System.out.println("Example Runner - All Demonstrations");
        System.out.println();

        logger.info("=== PeeGeeQ Example Runner ===");
        logger.info("Comprehensive runner for all PeeGeeQ examples and demonstrations");
        
        try {
            if (args.length == 0) {
                runAllExamples();
            } else if (args.length == 1 && "--list".equals(args[0])) {
                listAvailableExamples();
            } else {
                runSelectedExamples(Arrays.asList(args));
            }
        } catch (Exception e) {
            logger.error("Failed to run examples", e);
            System.exit(1);
        }
        
        logger.info("=== PeeGeeQ Example Runner Completed ===");
    }
    
    /**
     * Runs all available examples in the recommended order.
     */
    private static void runAllExamples() {
        logger.info("Running ALL PeeGeeQ examples in recommended order...");
        logger.info("Total examples to run: {}", EXAMPLES.size());
        
        List<String> exampleOrder = getRecommendedExecutionOrder();
        runExamplesInOrder(exampleOrder);
    }
    
    /**
     * Runs selected examples specified by the user.
     */
    private static void runSelectedExamples(List<String> selectedExamples) {
        logger.info("Running SELECTED PeeGeeQ examples: {}", selectedExamples);
        
        // Validate all examples exist
        List<String> invalidExamples = new ArrayList<>();
        for (String example : selectedExamples) {
            if (!EXAMPLES.containsKey(example)) {
                invalidExamples.add(example);
            }
        }
        
        if (!invalidExamples.isEmpty()) {
            logger.error("Invalid example names: {}", invalidExamples);
            logger.info("Available examples:");
            listAvailableExamples();
            return;
        }
        
        runExamplesInOrder(selectedExamples);
    }
    
    /**
     * Lists all available examples with their descriptions.
     */
    private static void listAvailableExamples() {
        logger.info("Available PeeGeeQ Examples:");
        logger.info("=" .repeat(50));
        
        Map<ExampleCategory, List<Map.Entry<String, ExampleInfo>>> categorized = new EnumMap<>(ExampleCategory.class);
        
        for (Map.Entry<String, ExampleInfo> entry : EXAMPLES.entrySet()) {
            categorized.computeIfAbsent(entry.getValue().category, k -> new ArrayList<>()).add(entry);
        }
        
        for (ExampleCategory category : ExampleCategory.values()) {
            List<Map.Entry<String, ExampleInfo>> examples = categorized.get(category);
            if (examples != null && !examples.isEmpty()) {
                logger.info("\n{} Examples:", category.getDisplayName());
                logger.info("-".repeat(30));
                
                for (Map.Entry<String, ExampleInfo> entry : examples) {
                    String key = entry.getKey();
                    ExampleInfo info = entry.getValue();
                    String marker = info.recommendedFirst ? " (RECOMMENDED FIRST)" : "";
                    logger.info("  {} - {}{}", key, info.shortDescription, marker);
                    logger.info("    {}", info.detailedDescription);
                }
            }
        }
        
        logger.info("\nUsage Examples:");
        logger.info("  Run all examples: java {} ", PeeGeeQExampleRunner.class.getName());
        logger.info("  Run specific: java {} self-contained rest-api", PeeGeeQExampleRunner.class.getName());
        logger.info("  List examples: java {} --list", PeeGeeQExampleRunner.class.getName());
    }

    /**
     * Gets the recommended execution order for examples.
     */
    private static List<String> getRecommendedExecutionOrder() {
        List<String> order = new ArrayList<>();

        // Start with recommended first example
        EXAMPLES.entrySet().stream()
            .filter(entry -> entry.getValue().recommendedFirst)
            .map(Map.Entry::getKey)
            .forEach(order::add);

        // Add examples by category in logical order
        for (ExampleCategory category : ExampleCategory.values()) {
            EXAMPLES.entrySet().stream()
                .filter(entry -> entry.getValue().category == category && !entry.getValue().recommendedFirst)
                .map(Map.Entry::getKey)
                .forEach(order::add);
        }

        return order;
    }

    /**
     * Runs examples in the specified order with comprehensive error handling and reporting.
     */
    private static void runExamplesInOrder(List<String> exampleOrder) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<ExampleResult> results = new ArrayList<>();

        Instant overallStart = Instant.now();

        for (int i = 0; i < exampleOrder.size(); i++) {
            String exampleKey = exampleOrder.get(i);
            ExampleInfo info = EXAMPLES.get(exampleKey);

            logger.info("");
            logger.info("=" .repeat(80));
            logger.info("Running Example {}/{}: {} ({})",
                i + 1, exampleOrder.size(), exampleKey, info.className);
            logger.info("Description: {}", info.shortDescription);
            logger.info("=" .repeat(80));

            ExampleResult result = runSingleExample(exampleKey, info);
            results.add(result);

            if (result.success) {
                successCount.incrementAndGet();
                logger.info(" Example '{}' completed successfully in {}",
                    exampleKey, formatDuration(result.duration));
            } else {
                failureCount.incrementAndGet();
                logger.error(" Example '{}' failed after {}: {}",
                    exampleKey, formatDuration(result.duration), result.errorMessage);
            }

            // Add a pause between examples to allow cleanup
            if (i < exampleOrder.size() - 1) {
                logger.info("Pausing 3 seconds before next example...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted during pause between examples");
                }
            }
        }

        Duration overallDuration = Duration.between(overallStart, Instant.now());

        // Print comprehensive summary
        printExecutionSummary(results, successCount.get(), failureCount.get(), overallDuration);
    }

    /**
     * Runs a single example with error handling and timing.
     */
    private static ExampleResult runSingleExample(String exampleKey, ExampleInfo info) {
        Instant start = Instant.now();

        try {
            // Load the example class
            String fullClassName = "dev.mars.peegeeq.examples." + info.className;
            Class<?> exampleClass = Class.forName(fullClassName);

            // Find and invoke the main method
            Method mainMethod = exampleClass.getMethod("main", String[].class);

            logger.info("Invoking {}.main()...", info.className);
            mainMethod.invoke(null, (Object) new String[0]);

            Duration duration = Duration.between(start, Instant.now());
            return new ExampleResult(exampleKey, info, true, duration, null);

        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            logger.error("Failed to run example '{}': {}", exampleKey, errorMessage, e);
            return new ExampleResult(exampleKey, info, false, duration, errorMessage);
        }
    }

    /**
     * Prints a comprehensive execution summary.
     */
    private static void printExecutionSummary(List<ExampleResult> results, int successCount,
                                            int failureCount, Duration overallDuration) {
        logger.info("");
        logger.info("=" .repeat(80));
        logger.info("EXECUTION SUMMARY");
        logger.info("=" .repeat(80));
        logger.info("Total Examples: {}", results.size());
        logger.info(" Successful: {}", successCount);
        logger.info(" Failed: {}", failureCount);
        logger.info(" Total Time: {}", formatDuration(overallDuration));
        logger.info(" Success Rate: {}%", String.format("%.1f", (successCount * 100.0) / results.size()));

        if (failureCount > 0) {
            logger.info("");
            logger.info("FAILED EXAMPLES:");
            logger.info("-".repeat(40));
            results.stream()
                .filter(r -> !r.success)
                .forEach(r -> logger.info(" {} - {}", r.exampleKey, r.errorMessage));
        }

        logger.info("");
        logger.info("DETAILED RESULTS:");
        logger.info("-".repeat(40));
        for (ExampleResult result : results) {
            String status = result.success ? "" : "";
            logger.info("{} {} - {} ({})",
                status, result.exampleKey, formatDuration(result.duration), result.info.shortDescription);
        }

        logger.info("");
        logger.info("PERFORMANCE ANALYSIS:");
        logger.info("-".repeat(40));

        // Find fastest and slowest
        ExampleResult fastest = results.stream()
            .filter(r -> r.success)
            .min(Comparator.comparing(r -> r.duration))
            .orElse(null);

        ExampleResult slowest = results.stream()
            .filter(r -> r.success)
            .max(Comparator.comparing(r -> r.duration))
            .orElse(null);

        if (fastest != null) {
            logger.info(" Fastest: {} ({})", fastest.exampleKey, formatDuration(fastest.duration));
        }
        if (slowest != null) {
            logger.info(" Slowest: {} ({})", slowest.exampleKey, formatDuration(slowest.duration));
        }

        // Calculate average duration for successful examples
        double avgSeconds = results.stream()
            .filter(r -> r.success)
            .mapToLong(r -> r.duration.toMillis())
            .average()
            .orElse(0.0) / 1000.0;
        logger.info(" Average: {} seconds", String.format("%.1f", avgSeconds));
    }

    /**
     * Formats a duration for human-readable display.
     */
    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long millis = duration.toMillis() % 1000;

        if (seconds >= 60) {
            long minutes = seconds / 60;
            seconds = seconds % 60;
            return String.format("%dm %ds", minutes, seconds);
        } else if (seconds > 0) {
            return String.format("%d.%03ds", seconds, millis);
        } else {
            return String.format("%dms", millis);
        }
    }

    /**
     * Represents metadata about an example.
     */
    private static class ExampleInfo {
        final String className;
        final String shortDescription;
        final String detailedDescription;
        final ExampleCategory category;
        final boolean recommendedFirst;

        ExampleInfo(String className, String shortDescription, String detailedDescription,
                   ExampleCategory category, boolean recommendedFirst) {
            this.className = className;
            this.shortDescription = shortDescription;
            this.detailedDescription = detailedDescription;
            this.category = category;
            this.recommendedFirst = recommendedFirst;
        }
    }

    /**
     * Represents the result of running an example.
     */
    private static class ExampleResult {
        final String exampleKey;
        final ExampleInfo info;
        final boolean success;
        final Duration duration;
        final String errorMessage;

        ExampleResult(String exampleKey, ExampleInfo info, boolean success,
                     Duration duration, String errorMessage) {
            this.exampleKey = exampleKey;
            this.info = info;
            this.success = success;
            this.duration = duration;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * Categories for organizing examples.
     */
    private enum ExampleCategory {
        CORE("Core"),
        REST_API("REST API"),
        SERVICE_DISCOVERY("Service Discovery"),
        COMPARISON("Implementation Comparison"),
        ADVANCED("Advanced");

        private final String displayName;

        ExampleCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
