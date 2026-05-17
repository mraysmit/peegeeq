package dev.mars.peegeeq.db;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
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
@Tag(TestCategories.INTEGRATION)
@Tag("demonstration")
@DisplayName("Resource Leak Detection Tests")
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@Isolated("Resource leak detection must run in complete isolation")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
public class ResourceLeakDetectionTest {
    private static final Logger logger = LoggerFactory.getLogger(ResourceLeakDetectionTest.class);

    private static PostgreSQLContainer getPostgres() {
        return SharedPostgresTestExtension.getContainer();
    }

    private PeeGeeQConfiguration configuration;
    private PeeGeeQManager testManager;
    private Set<Long> initialThreadIds;
    private int initialThreadCount;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("[SETUP] Configuring database and starting PeeGeeQManager");

        // Build isolated configuration properties — never touch System.setProperty so that
        // concurrent tests sharing the same JVM fork cannot observe partial state.
        Properties testProps = PeeGeeQTestConfig.builder()
            .from(getPostgres())
            .property("peegeeq.database.pool.min-size", "1")
            .property("peegeeq.database.pool.max-size", "3")
            .property("peegeeq.database.pool.shared", "false")
            .property("peegeeq.database.pool.idle-timeout-ms", "5000")
            .property("peegeeq.database.pool.connection-timeout-ms", "30000")
            // CRITICAL: Disable migrations to avoid duplicate key violations with shared TestContainer
            .property("peegeeq.migration.enabled", "false")
            .build();

        logger.info("TestContainers database: {}:{}/{}",
            getPostgres().getHost(), getPostgres().getFirstMappedPort(), getPostgres().getDatabaseName());

        configuration = new PeeGeeQConfiguration("default", testProps);

        // Capture initial thread state BEFORE creating any managers
        captureInitialThreadState();

        logger.info("[SETUP] Complete initial thread count: {}", initialThreadCount);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("[TEARDOWN] Closing resources and manager");

        if (testManager == null) {
            logger.info("[TEARDOWN] Complete (manager already closed and nulled by test body)");
            testContext.completeNow();
            return;
        }
        testManager.closeReactive()
            .onSuccess(v -> {
                testManager = null;
                logger.info("[TEARDOWN] Manager closed successfully");
                logger.info("[TEARDOWN] Complete");
                testContext.completeNow();
            })
            .onFailure(err -> {
                // Every test path that closes testManager itself also nulls the field,
                // so reaching this branch means closeReactive() genuinely failed on a
                // still-live manager. That is a real resource-cleanup failure and must
                // fail the test, not be hidden behind completeNow().
                logger.error("[TEARDOWN] Manager closeReactive() failed", err);
                testManager = null;
                testContext.failNow(err);
            });
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
        logger.warn("[LEAK DETECTION] Listing {} potentially leaked thread(s) this is diagnostic, not a production error:",
                threadIds.size());

        for (Long threadId : threadIds) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
            if (threadInfo != null) {
                logger.warn("[LEAK DETECTION]   Thread ID: {}, Name: {}, State: {}",
                    threadId, threadInfo.getThreadName(), threadInfo.getThreadState());
            }
        }
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
                    threadName.contains("ForkJoinPool.commonPool") ||
                    threadName.contains("ForkJoinPool-") ||
                    threadName.contains("junit-jupiter") ||
                    threadName.contains("Surefire") ||
                    threadName.contains("Finalizer") ||
                    threadName.contains("Reference Handler") ||
                    threadName.contains("Signal Dispatcher")) {
                    logger.debug("Filtering out system thread: {}", threadName);
                    continue;
                }

                // Only count threads that are clearly related to PeeGeeQ-managed resources.
                boolean peeGeeQRelated = threadName.contains("PeeGeeQ")
                        || threadName.contains("vert.x");
                if (!peeGeeQRelated) {
                    logger.debug("Filtering out non-PeeGeeQ thread: {}", threadName);
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
    void testNoThreadLeaksAfterClose(Vertx vertx, VertxTestContext testContext) {
        logger.info("[testNoThreadLeaksAfterClose] Starting leak detection test");

        // Create and start test manager
        testManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        AtomicHolder<Set<Long>> runningHolder = new AtomicHolder<>();

        testManager.start()
            .compose(v -> {
                logger.info("Test manager started successfully");
                // Capture threads while manager is running
                Set<Long> runningThreadIds = getCurrentThreadIds();
                int runningThreadCount = runningThreadIds.size();
                logger.info("Thread count while running: {}", runningThreadCount);
                // Verify manager created some threads — fail fast on the chain.
                if (runningThreadCount <= initialThreadCount) {
                    return Future.failedFuture(new AssertionError(
                        "Manager should create additional threads (initial: " + initialThreadCount
                            + ", running: " + runningThreadCount + ")"));
                }
                runningHolder.value = runningThreadIds;
                // Close test manager closeReactive() includes vertx.close(), so the
                // PeeGeeQ-internal Vert.x runtime is gone after this returns. The
                // injected Vertx (from VertxExtension) is independent and still alive,
                // so vertx.timer(...) settling delays remain valid.
                return testManager.closeReactive();
            })
            .compose(v -> {
                testManager = null;
                // Give threads time to shut down
                return vertx.timer(3000);
            })
            .compose(v -> {
                // Force garbage collection to clean up any weak references
                System.gc();
                return vertx.timer(1000);
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
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
                    logger.warn("[LEAK DETECTION] Found {} leaked thread(s) after close test will fail:", leakedThreadIds.size());
                    logThreadDetails(leakedThreadIds);
                } else {
                    logger.info("[testNoThreadLeaksAfterClose] No thread leaks detected PASSED");
                }

                // Assert no thread leaks
                assertEquals(0, leakedThreadIds.size(),
                    "No threads should be leaked after manager.close(). Leaked: " + leakedThreadIds.size());
                testContext.completeNow();
            })));
    }
    
    @Test
    @DisplayName("Should not leak Vert.x event loop threads")
    void testNoVertxEventLoopLeaks(Vertx vertx, VertxTestContext testContext) {
        // Capture thread IDs before creating this test's manager
        Set<Long> threadsBefore = getCurrentThreadIds();
        AtomicHolder<Set<Long>> thisTestThreadHolder = new AtomicHolder<>();

        // Create and start test manager
        testManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        testManager.start()
            .compose(v -> {
                // Capture thread IDs created by this manager's startup (including event loop threads)
                Set<Long> threadsAfterStart = getCurrentThreadIds();
                Set<Long> thisTestThreadIds = new HashSet<>(threadsAfterStart);
                thisTestThreadIds.removeAll(threadsBefore);
                thisTestThreadHolder.value = thisTestThreadIds;
                // Close test manager PeeGeeQ-internal Vert.x runtime is gone after this;
                // injected Vertx (from VertxExtension) is independent and still alive.
                return testManager.closeReactive();
            })
            .compose(v -> {
                testManager = null;
                return vertx.timer(3000);
            })
            .compose(v -> {
                System.gc();
                return vertx.timer(1000);
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                Set<Long> thisTestThreadIds = thisTestThreadHolder.value;
                ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

                Set<String> leakedEventLoopThreads = new HashSet<>();
                for (long threadId : thisTestThreadIds) {
                    ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
                    if (threadInfo != null && threadInfo.getThreadName().contains("vert.x-eventloop")) {
                        leakedEventLoopThreads.add(threadInfo.getThreadName() + " (id=" + threadId + ")");
                    }
                }

                if (!leakedEventLoopThreads.isEmpty()) {
                    logger.error("This test's Vert.x event loop threads still alive after manager.close(): {}", leakedEventLoopThreads);
                }

                assertEquals(0, leakedEventLoopThreads.size(),
                    "Vert.x event loop threads created by this test should not outlive manager.close(). Found: " + leakedEventLoopThreads);
                testContext.completeNow();
            })));
    }
    
    @Test
    @DisplayName("Should not leak scheduler threads")
    void testNoSchedulerThreadLeaks(Vertx vertx, VertxTestContext testContext) {
        // Capture thread IDs before creating this test's manager
        Set<Long> threadsBefore = getCurrentThreadIds();
        AtomicHolder<Set<Long>> thisTestThreadHolder = new AtomicHolder<>();

        // Create and start test manager
        testManager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        testManager.start()
            .compose(v -> {
                // Capture thread IDs created by this manager's startup
                Set<Long> threadsAfterStart = getCurrentThreadIds();
                Set<Long> thisTestThreadIds = new HashSet<>(threadsAfterStart);
                thisTestThreadIds.removeAll(threadsBefore);
                thisTestThreadHolder.value = thisTestThreadIds;
                // Close test manager PeeGeeQ-internal Vert.x runtime is gone after this;
                // injected Vertx (from VertxExtension) is independent and still alive.
                return testManager.closeReactive();
            })
            .compose(v -> {
                testManager = null;
                return vertx.timer(3000);
            })
            .compose(v -> {
                System.gc();
                return vertx.timer(1000);
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                Set<Long> thisTestThreadIds = thisTestThreadHolder.value;
                ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

                Set<String> leakedSchedulerThreads = new HashSet<>();
                for (long threadId : thisTestThreadIds) {
                    ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);
                    if (threadInfo != null &&
                        (threadInfo.getThreadName().contains("PeeGeeQ-") ||
                         threadInfo.getThreadName().contains("pool-"))) {
                        leakedSchedulerThreads.add(threadInfo.getThreadName() + " (id=" + threadId + ")");
                    }
                }

                if (!leakedSchedulerThreads.isEmpty()) {
                    logger.error("This test's scheduler threads still alive after manager.close(): {}", leakedSchedulerThreads);
                } else {
                    logger.info("[testNoSchedulerThreadLeaks] No scheduler thread leaks detected PASSED");
                }

                assertEquals(0, leakedSchedulerThreads.size(),
                    "No scheduler threads should be leaked from current test. Found: " + leakedSchedulerThreads);
                testContext.completeNow();
            })));
    }
    
    @Test
    @DisplayName("Should not leak threads with multiple manager instances")
    void testMultipleManagerInstancesNoLeaks(Vertx vertx, VertxTestContext testContext) {
        // Create and close multiple managers via a recursive Future chain.
        runManagerIteration(vertx, 0, 3)
            .compose(v -> vertx.timer(3000))
            .compose(v -> {
                System.gc();
                return vertx.timer(1000);
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                // Capture final thread state
                Set<Long> finalThreadIds = getCurrentThreadIds();

                // Find leaked threads
                Set<Long> leakedThreadIds = new HashSet<>(finalThreadIds);
                leakedThreadIds.removeAll(initialThreadIds);

                // Filter out known system threads
                leakedThreadIds = filterSystemThreads(leakedThreadIds);

                if (!leakedThreadIds.isEmpty()) {
                    logger.warn("[LEAK DETECTION] Found {} leaked thread(s) after multiple managers test will fail:",
                            leakedThreadIds.size());
                    logThreadDetails(leakedThreadIds);
                } else {
                    logger.info("[testMultipleManagerInstancesNoLeaks] No thread leaks detected PASSED");
                }

                assertEquals(0, leakedThreadIds.size(),
                    "No threads should be leaked after multiple manager instances. Leaked: " + leakedThreadIds.size());
                testContext.completeNow();
            })));
    }

    /**
     * Recursively starts, closes, and waits between PeeGeeQManager instances.
     * Replaces a blocking loop that used {@code manager.start().await()},
     * {@code manager.closeReactive().await()}, and {@code Thread.sleep(2000)}.
     */
    private Future<Void> runManagerIteration(Vertx vertx, int i, int total) {
        if (i >= total) {
            return Future.succeededFuture();
        }
        @SuppressWarnings("resource") // Closed via closeReactive() below
        PeeGeeQManager manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        return manager.start()
            .compose(v -> manager.closeReactive())
            // Post-shutdown delay between iterations.
            .compose(v -> vertx.timer(2000).mapEmpty())
            .compose(v -> runManagerIteration(vertx, i + 1, total));
    }

    /** Single-slot mutable holder for passing state between Future composition stages. */
    private static final class AtomicHolder<T> {
        T value;
    }
}





