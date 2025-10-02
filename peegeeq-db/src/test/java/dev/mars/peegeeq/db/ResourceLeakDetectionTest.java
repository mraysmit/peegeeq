package dev.mars.peegeeq.db;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 */
@DisplayName("Resource Leak Detection Tests")
@Testcontainers
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

                filtered.add(threadId);
            }
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

        // Give threads time to shut down
        Thread.sleep(2000);

        // Force garbage collection to clean up any weak references
        System.gc();
        Thread.sleep(500);

        // Capture final thread state
        Set<Long> finalThreadIds = getCurrentThreadIds();
        int finalThreadCount = finalThreadIds.size();
        logger.info("Thread count after close: {}", finalThreadCount);

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

        // Give threads time to shut down
        Thread.sleep(2000);

        // Check for Vert.x event loop threads
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadMXBean.getAllThreadIds();
        
        Set<String> vertxThreads = new HashSet<>();
        for (long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null && threadInfo.getThreadName().contains("vert.x-eventloop")) {
                vertxThreads.add(threadInfo.getThreadName());
            }
        }

        if (!vertxThreads.isEmpty()) {
            logger.error("Leaked Vert.x event loop threads: {}", vertxThreads);
        }

        assertEquals(0, vertxThreads.size(),
            "No Vert.x event loop threads should be leaked. Found: " + vertxThreads);
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

        // Give threads time to shut down
        Thread.sleep(2000);

        // Check for scheduler threads
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadMXBean.getAllThreadIds();
        
        Set<String> schedulerThreads = new HashSet<>();
        for (long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null && 
                (threadInfo.getThreadName().contains("PeeGeeQ-") || 
                 threadInfo.getThreadName().contains("pool-"))) {
                schedulerThreads.add(threadInfo.getThreadName());
            }
        }

        if (!schedulerThreads.isEmpty()) {
            logger.error("Leaked scheduler threads: {}", schedulerThreads);
        }

        assertEquals(0, schedulerThreads.size(),
            "No scheduler threads should be leaked. Found: " + schedulerThreads);
    }
    
    @Test
    @DisplayName("Should not leak threads with multiple manager instances")
    void testMultipleManagerInstancesNoLeaks() throws Exception {
        // Create and close multiple managers
        for (int i = 0; i < 3; i++) {
            PeeGeeQManager manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
            manager.start();
            manager.close();

            // Give threads time to shut down
            Thread.sleep(1000);
        }

        // Final cleanup wait
        Thread.sleep(2000);
        System.gc();
        Thread.sleep(500);

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
}

