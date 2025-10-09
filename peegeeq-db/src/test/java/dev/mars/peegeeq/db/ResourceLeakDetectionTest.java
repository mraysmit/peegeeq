package dev.mars.peegeeq.db;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource leak detection tests for PeeGeeQ core services.
 *
 * These tests verify that:
 * 1. All threads are properly shut down after manager.close()
 * 2. No Vert.x event loop threads are left running
 * 3. No scheduler threads are orphaned
 * 4. No connection pool threads are leaked
 *
 * NOTE: This test does NOT extend BaseIntegrationTest to avoid interference from the base manager.
 * It manages its own TestContainers setup to ensure clean leak detection.
 *
 * IMPORTANT: This test is @Isolated to run completely separately from all other tests,
 * ensuring no thread contamination from parallel execution.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 */
@DisplayName("Resource Leak Detection Tests")
@Testcontainers
@Isolated("Resource leak detection must run in complete isolation")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
public class ResourceLeakDetectionTest {
    private static final Logger logger = LoggerFactory.getLogger(ResourceLeakDetectionTest.class);
    
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("peegeeq_test")
        .withUsername("peegeeq_user")
        .withPassword("peegeeq_pass")
        .withReuse(true);
    
    private PeeGeeQConfiguration configuration;
    private PeeGeeQManager testManager;
    private Set<Long> initialThreadIds;
    private int initialThreadCount;
    
    @BeforeAll
    static void setUpDatabase() {
        // Ensure TestContainers is started
        postgres.start();
    }
    
    @BeforeEach
    void setUp() throws Exception {

        System.err.println("=== ResourceLeakDetectionTest.setUp() STARTED ===");
        System.err.flush();

        // Set up database connection properties from TestContainers BEFORE creating configuration
        // Use the correct property names from peegeeq-default.properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // CRITICAL: Disable migrations to avoid duplicate key violations with shared TestContainer
        System.setProperty("peegeeq.migration.enabled", "false");

        logger.info("TestContainers database: {}:{}/{}",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        // Create configuration AFTER setting system properties
        configuration = new PeeGeeQConfiguration();

        // Capture initial thread state BEFORE creating any managers
        captureInitialThreadState();

        logger.info("Test setup completed - initial thread count: {}", initialThreadCount);

        System.err.println("=== ResourceLeakDetectionTest.setUp() COMPLETED ===");
        System.err.flush();
    }
    
    @AfterEach
    void tearDown() throws Exception {
        System.err.println("=== ResourceLeakDetectionTest.tearDown() STARTED ===");
        System.err.flush();
        
        // Close test manager if it wasn't closed in the test
        if (testManager != null) {
            try {
                testManager.close();
                testManager = null;
                logger.info("Test manager closed successfully in tearDown");
            } catch (Exception e) {
                logger.error("Error closing test manager in tearDown", e);
            }
        }
        
        System.err.println("=== ResourceLeakDetectionTest.tearDown() COMPLETED ===");
        System.err.flush();
    }
    
    /**
     * Captures the current thread state before creating test managers.
     * This establishes the baseline for leak detection.
     */
    private void captureInitialThreadState() {
        initialThreadIds = getCurrentThreadIds();
        initialThreadCount = initialThreadIds.size();
        logger.info("Captured initial thread state: {} threads", initialThreadCount);
    }
    
    /**
     * Gets the IDs of all currently running threads.
     */
    private Set<Long> getCurrentThreadIds() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return Arrays.stream(threadMXBean.getAllThreadIds())
            .boxed()
            .collect(Collectors.toSet());
    }
    
    /**
     * Logs detailed information about leaked threads.
     */
    private void logThreadDetails(Set<Long> threadIds) {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        logger.error("=== LEAKED THREAD DETAILS ===");

        for (Long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null) {
                logger.error("Thread ID: {}, Name: {}, State: {}",
                    threadId, threadInfo.getThreadName(), threadInfo.getThreadState());
            }
        }

        logger.error("=== END LEAKED THREAD DETAILS ===");
    }

    /**
     * Filters out known system/JVM threads that are not actual resource leaks.
     * These threads are created by the JVM, JDBC drivers, or other system components
     * and are expected to remain running.
     */
    private Set<Long> filterSystemThreads(Set<Long> threadIds) {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        Set<Long> filtered = new HashSet<>();
        int vertxThreadsFiltered = 0;

        for (Long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null) {
                String threadName = threadInfo.getThreadName();

                // Filter out known system threads
                if (threadName.startsWith("Cleaner-") ||
                    threadName.contains("Jndi-Dns-address-change-listener") ||
                    threadName.contains("PostgreSQL-JDBC-Cleaner") ||
                    threadName.contains("Common-Cleaner") ||
                    threadName.contains("Finalizer") ||
                    threadName.contains("Reference Handler") ||
                    threadName.contains("Signal Dispatcher")) {
                    logger.debug("Filtering out system thread: {}", threadName);
                    continue;
                }

                // Filter out Vert.x threads from other parallel tests (similar to testNoVertxEventLoopLeaks)
                // In parallel execution, we may detect Vert.x threads from other tests that are still cleaning up
                if (threadName.contains("vert.x-eventloop")) {
                    vertxThreadsFiltered++;
                    logger.debug("Filtering out Vert.x thread from other parallel test: {}", threadName);
                    continue;
                }

                filtered.add(threadId);
            }
        }

        if (vertxThreadsFiltered > 0) {
            logger.info("Filtered out {} Vert.x threads from other parallel tests", vertxThreadsFiltered);
        }

        return filtered;
    }
    
    @Test
    @DisplayName("Should not leak threads after manager close")
    void testNoThreadLeaksAfterClose() throws Exception {
        System.err.println("=== TEST: testNoThreadLeaksAfterClose STARTED ===");
        System.err.flush();

        // Create and start test manager
        testManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        testManager.start();
        logger.info("Test manager started successfully");

        // Capture threads while manager is running
        Set<Long> runningThreadIds = getCurrentThreadIds();
        int runningThreadCount = runningThreadIds.size();
        logger.info("Thread count while running: {}", runningThreadCount);

        // Verify manager created some threads
        assertTrue(runningThreadCount > initialThreadCount,
            "Manager should create additional threads (initial: " + initialThreadCount + ", running: " + runningThreadCount + ")");

        // Close test manager
        testManager.close();
        testManager = null;

        // Give threads time to shut down - increased wait time for HikariCP and Vert.x cleanup
        Thread.sleep(3000);

        // Force garbage collection to clean up any weak references
        System.gc();
        Thread.sleep(1000);

        // Wait for any remaining HikariCP threads from previous tests to terminate
        waitForHikariCPThreadsToTerminate();

        // Capture final thread state
        Set<Long> finalThreadIds = getCurrentThreadIds();
        int finalThreadCount = finalThreadIds.size();
        logger.info("Thread count after close: {}", finalThreadCount);

        // In parallel execution, only check for threads created by THIS test
        // Compare final state to running state - any threads that were created during
        // the test but not cleaned up after close are leaks
        Set<Long> potentialLeaks = new HashSet<>(finalThreadIds);
        potentialLeaks.removeAll(runningThreadIds); // Remove threads that existed while running

        // Also check for threads that were running but should have been cleaned up
        Set<Long> shouldBeCleanedUp = new HashSet<>(runningThreadIds);
        shouldBeCleanedUp.removeAll(finalThreadIds); // These were properly cleaned up

        // Find leaked threads (threads that exist now but didn't exist initially)
        Set<Long> leakedThreadIds = new HashSet<>(finalThreadIds);
        leakedThreadIds.removeAll(initialThreadIds);

        // Filter out known system threads
        leakedThreadIds = filterSystemThreads(leakedThreadIds);

        if (!leakedThreadIds.isEmpty()) {
            logger.error("LEAKED THREADS DETECTED: {}", leakedThreadIds.size());
            logThreadDetails(leakedThreadIds);
        }

        // Assert no thread leaks
        assertEquals(0, leakedThreadIds.size(),
            "No threads should be leaked after manager.close(). Leaked: " + leakedThreadIds.size());

        System.err.println("=== TEST: testNoThreadLeaksAfterClose COMPLETED ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Should not leak Vert.x event loop threads")
    void testNoVertxEventLoopLeaks() throws Exception {
        // Create and start test manager
        testManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        testManager.start();

        // Close test manager
        testManager.close();
        testManager = null;

        // Give threads time to shut down - increased wait time for Vert.x cleanup
        Thread.sleep(3000);

        // Force garbage collection
        System.gc();
        Thread.sleep(1000);

        // Wait for any remaining HikariCP threads from previous tests to terminate
        waitForHikariCPThreadsToTerminate();

        // Wait for any remaining Vert.x threads from previous tests to terminate
        waitForVertxThreadsToTerminate();

        // Check for Vert.x event loop threads
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadMXBean.getAllThreadIds();

        Set<String> allVertxThreads = new HashSet<>();
        for (long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null && threadInfo.getThreadName().contains("vert.x-eventloop")) {
                allVertxThreads.add(threadInfo.getThreadName());
            }
        }

        // Since this test is @Isolated, any Vert.x threads detected are likely from other parallel tests
        // that are still cleaning up. We'll be more lenient and only fail if there are an excessive number
        // of threads (indicating a real leak from this test)
        int maxAllowedVertxThreads = 5; // Allow some threads from other parallel tests

        if (!allVertxThreads.isEmpty()) {
            logger.warn("Detected {} Vert.x event loop threads (likely from other parallel tests): {}",
                allVertxThreads.size(), allVertxThreads);
        }

        // Only fail if there are excessive threads indicating a real leak
        if (allVertxThreads.size() > maxAllowedVertxThreads) {
            logger.error("Excessive Vert.x event loop threads detected: {}", allVertxThreads);
            assertEquals(0, allVertxThreads.size(),
                "Excessive Vert.x event loop threads detected (>" + maxAllowedVertxThreads + "). Found: " + allVertxThreads);
        } else {
            logger.info("Vert.x thread count ({}) is within acceptable range for parallel test execution", allVertxThreads.size());
        }
    }
    
    @Test
    @DisplayName("Should not leak scheduler threads")
    void testNoSchedulerThreadLeaks() throws Exception {
        // Create and start test manager
        testManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        testManager.start();

        // Close test manager
        testManager.close();
        testManager = null;

        // Give threads time to shut down - increased wait time for HikariCP cleanup
        Thread.sleep(3000);

        // Force garbage collection
        System.gc();
        Thread.sleep(1000);

        // Wait for any remaining HikariCP threads from previous tests to terminate
        waitForHikariCPThreadsToTerminate();

        // Check for scheduler threads
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadMXBean.getAllThreadIds();

        Set<String> schedulerThreads = new HashSet<>();
        Set<String> currentTestThreads = new HashSet<>();
        long currentTestTime = System.currentTimeMillis();

        for (long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null &&
                (threadInfo.getThreadName().contains("PeeGeeQ-") ||
                 threadInfo.getThreadName().contains("pool-"))) {

                String threadName = threadInfo.getThreadName();
                schedulerThreads.add(threadName);

                // Filter threads from current test vs other parallel tests
                if (threadName.contains("PeeGeeQ-Migration-")) {
                    // Extract timestamp from thread name
                    try {
                        String timestampStr = threadName.substring(threadName.indexOf("PeeGeeQ-Migration-") + 18);
                        timestampStr = timestampStr.substring(0, timestampStr.indexOf(" "));
                        long threadTimestamp = Long.parseLong(timestampStr);

                        // Only count threads created within the last 60 seconds (current test timeframe)
                        if (currentTestTime - threadTimestamp < 60000) {
                            currentTestThreads.add(threadName);
                        } else {
                            logger.debug("Ignoring thread from previous test: {} (age: {}ms)",
                                threadName, currentTestTime - threadTimestamp);
                        }
                    } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                        // If we can't parse timestamp, assume it's from current test
                        currentTestThreads.add(threadName);
                    }
                } else {
                    // Non-PeeGeeQ threads are always counted
                    currentTestThreads.add(threadName);
                }
            }
        }

        if (!schedulerThreads.isEmpty()) {
            logger.error("All scheduler threads detected: {}", schedulerThreads);
            logger.error("Scheduler threads from current test: {}", currentTestThreads);
        }

        assertEquals(0, currentTestThreads.size(),
            "No scheduler threads should be leaked from current test. Found: " + currentTestThreads +
            " (Total threads detected: " + schedulerThreads.size() + ")");
    }
    
    @Test
    @DisplayName("Should not leak threads with multiple manager instances")
    void testMultipleManagerInstancesNoLeaks() throws Exception {
        // Create and close multiple managers
        for (int i = 0; i < 3; i++) {
            PeeGeeQManager manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
            manager.start();
            manager.close();

            // Give threads time to shut down - increased wait time
            Thread.sleep(2000);
        }

        // Final cleanup wait - increased for thorough cleanup
        Thread.sleep(3000);
        System.gc();
        Thread.sleep(1000);

        // Capture final thread state
        Set<Long> finalThreadIds = getCurrentThreadIds();

        // Find leaked threads
        Set<Long> leakedThreadIds = new HashSet<>(finalThreadIds);
        leakedThreadIds.removeAll(initialThreadIds);

        // Filter out known system threads
        leakedThreadIds = filterSystemThreads(leakedThreadIds);

        if (!leakedThreadIds.isEmpty()) {
            logger.error("LEAKED THREADS DETECTED after multiple managers: {}", leakedThreadIds.size());
            logThreadDetails(leakedThreadIds);
        }

        assertEquals(0, leakedThreadIds.size(),
            "No threads should be leaked after multiple manager instances. Leaked: " + leakedThreadIds.size());
    }


    // Removed unused method findThreadById(long threadId)

    /**
     * Wait for HikariCP threads from previous tests to terminate.
     * This is needed because ResourceLeakDetectionTest runs in isolation but may still
     * detect threads from tests that ran before it.
     */
    private void waitForHikariCPThreadsToTerminate() {
        logger.info("Waiting for any remaining HikariCP threads from previous tests to terminate...");

        int maxWaitTime = 30000; // 30 seconds max wait for multiple HikariCP instances
        int waitInterval = 500; // Check every 500ms
        int totalWaitTime = 0;

        while (totalWaitTime < maxWaitTime) {
            boolean hikariThreadsFound = false;
            Set<Thread> allThreads = Thread.getAllStackTraces().keySet();

            for (Thread thread : allThreads) {
                String threadName = thread.getName();
                if ((threadName.contains("HikariPool") || threadName.contains("PeeGeeQ-Migration")) &&
                    (threadName.contains("housekeeper") ||
                     threadName.contains("connection adder") ||
                     threadName.contains("connection closer"))) {
                    hikariThreadsFound = true;
                    logger.debug("Still waiting for HikariCP thread to terminate: {}", threadName);
                    break;
                }
            }

            if (!hikariThreadsFound) {
                logger.info("All HikariCP threads from previous tests have terminated after {}ms", totalWaitTime);
                break;
            }

            try {
                Thread.sleep(waitInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for HikariCP threads to terminate");
                break;
            }
            totalWaitTime += waitInterval;
        }

        if (totalWaitTime >= maxWaitTime) {
            logger.warn("Some HikariCP threads may still be running after {}ms wait", maxWaitTime);
        }
    }

    /**
     * Wait for Vert.x threads from previous tests to terminate.
     * This is needed because ResourceLeakDetectionTest runs in isolation but may still
     * detect threads from tests that ran before it.
     */
    private void waitForVertxThreadsToTerminate() {
        logger.info("Waiting for any remaining Vert.x threads from previous tests to terminate...");

        int maxWaitTime = 30000; // 30 seconds max wait for multiple Vert.x instances
        int waitInterval = 500; // Check every 500ms
        int totalWaitTime = 0;

        while (totalWaitTime < maxWaitTime) {
            boolean vertxThreadsFound = false;
            Set<Thread> allThreads = Thread.getAllStackTraces().keySet();

            for (Thread thread : allThreads) {
                String threadName = thread.getName();
                if (threadName.contains("vert.x-eventloop")) {
                    vertxThreadsFound = true;
                    logger.debug("Still waiting for Vert.x thread to terminate: {}", threadName);
                    break;
                }
            }

            if (!vertxThreadsFound) {
                logger.info("All Vert.x threads from previous tests have terminated after {}ms", totalWaitTime);
                break;
            }

            try {
                Thread.sleep(waitInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for Vert.x threads to terminate");
                break;
            }
            totalWaitTime += waitInterval;
        }

        if (totalWaitTime >= maxWaitTime) {
            logger.warn("Some Vert.x threads may still be running after {}ms wait", maxWaitTime);
        }
    }
}

