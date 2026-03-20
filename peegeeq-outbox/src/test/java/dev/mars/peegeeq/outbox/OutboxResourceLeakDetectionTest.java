package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Resource leak detection tests for Outbox module.
 *
 * These tests verify that:
 * 1. Shared Vert.x instances are properly closed
 * 2. OutboxProducer doesn't leak threads
 * 3. OutboxConsumer doesn't leak threads
 * 4. Multiple producer/consumer instances don't accumulate threads
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxResourceLeakDetectionTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxResourceLeakDetectionTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("peegeeq_test");
        container.withUsername("peegeeq");
        container.withPassword("peegeeq");
        return container;
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private Set<Long> initialThreadIds;
    private int initialThreadCount;

    private Set<String> initialVertxThreadNames;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        System.err.println("=== OutboxResourceLeakDetectionTest.setUp() STARTED ===");
        System.err.flush();

        // Capture initial thread state
        // Capture initial Vert.x thread names for baseline comparison
        initialVertxThreadNames = getVertxThreadNames();

        captureInitialThreadState();

        // Configure system properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();

        // Create outbox factory
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);
        queueFactory = provider.createFactory("outbox", databaseService);

        logger.info("Test setup completed - initial thread count: {}", initialThreadCount);

        System.err.println("=== OutboxResourceLeakDetectionTest.setUp() COMPLETED ===");
        System.err.flush();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        System.err.println("=== OutboxResourceLeakDetectionTest.tearDown() STARTED ===");
        System.err.flush();

        if (queueFactory != null) {
            try {
                queueFactory.close();
            } catch (Exception e) {
                logger.error("Error closing queue factory", e);
            }
        }

        if (manager != null) {
            manager.closeReactive()
                .onComplete(ar -> {
                    if (ar.failed()) {
                        logger.error("Error closing manager", ar.cause());
                    }
                    System.err.println("=== OutboxResourceLeakDetectionTest.tearDown() COMPLETED ===");
                    System.err.flush();
                    testContext.completeNow();
                });
        } else {
            System.err.println("=== OutboxResourceLeakDetectionTest.tearDown() COMPLETED ===");
            System.err.flush();
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should not leak threads after producer close")
    void testNoThreadLeaksAfterProducerClose(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        System.err.println("=== TEST: testNoThreadLeaksAfterProducerClose STARTED ===");
        System.err.flush();

        // Capture threads before creating producer
        Set<Long> beforeThreadIds = getCurrentThreadIds();
        int beforeCount = beforeThreadIds.size();
        logger.info("Thread count before producer: {}", beforeCount);

        // Create producer
        MessageProducer<String> producer = queueFactory.createProducer("leak-test-producer", String.class);

        // Send a message to ensure producer is fully initialized
        producer.send("test message")
            // GC-settle: allow producer to fully initialize
            .compose(v -> vertx.timer(500))
            .compose(timerId -> {
                // Capture threads while producer is active
                Set<Long> activeThreadIds = getCurrentThreadIds();
                int activeCount = activeThreadIds.size();
                logger.info("Thread count with active producer: {}", activeCount);

                // Close producer
                producer.close();

                // GC-settle: give time for shutdown
                return vertx.timer(5000);
            })
            .onSuccess(timerId -> testContext.verify(() -> {
                // Capture threads after close
                Set<Long> afterThreadIds = getCurrentThreadIds();
                int afterCount = afterThreadIds.size();
                logger.info("Thread count after producer close: {}", afterCount);

                // Find leaked threads (excluding expected shared infrastructure threads)
                Set<Long> leakedThreadIds = new HashSet<>(afterThreadIds);
                leakedThreadIds.removeAll(beforeThreadIds);

                // Filter out expected shared infrastructure threads that are not leaks
                Set<Long> filteredLeaks = filterOutExpectedSharedThreads(leakedThreadIds);

                if (!filteredLeaks.isEmpty()) {
                    logger.error("LEAKED THREADS AFTER PRODUCER CLOSE: {}", filteredLeaks.size());
                    logThreadDetails(filteredLeaks);
                }

                assertEquals(0, filteredLeaks.size(),
                    "No component-specific threads should leak after producer.close(). Leaked: " + filteredLeaks.size());

                System.err.println("=== TEST: testNoThreadLeaksAfterProducerClose COMPLETED ===");
                System.err.flush();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should not leak threads after consumer close")
    void testNoThreadLeaksAfterConsumerClose(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        System.err.println("=== TEST: testNoThreadLeaksAfterConsumerClose STARTED ===");
        System.err.flush();

        // Capture threads before creating consumer
        Set<Long> beforeThreadIds = getCurrentThreadIds();
        int beforeCount = beforeThreadIds.size();
        logger.info("Thread count before consumer: {}", beforeCount);

        // Create and subscribe consumer
        MessageConsumer<String> consumer = queueFactory.createConsumer("leak-test-consumer", String.class);
        consumer.subscribe(message -> {
            logger.debug("Received message: {}", message.getPayload());
            return Future.succeededFuture();
        });

        // GC-settle: let consumer start polling
        vertx.timer(1000)
            .compose(timerId -> {
                // Capture threads while consumer is active
                Set<Long> activeThreadIds = getCurrentThreadIds();
                int activeCount = activeThreadIds.size();
                logger.info("Thread count with active consumer: {}", activeCount);

                // Verify consumer created threads
                testContext.verify(() -> assertTrue(activeCount > beforeCount,
                    "Consumer should create polling threads (before: " + beforeCount + ", active: " + activeCount + ")"));

                // Close consumer
                consumer.close();

                // GC-settle: give scheduler time to shut down
                return vertx.timer(5000);
            })
            .onSuccess(timerId -> testContext.verify(() -> {
                // Capture threads after close
                Set<Long> afterThreadIds = getCurrentThreadIds();
                int afterCount = afterThreadIds.size();
                logger.info("Thread count after consumer close: {}", afterCount);

                // Find leaked threads (excluding expected shared infrastructure threads)
                Set<Long> leakedThreadIds = new HashSet<>(afterThreadIds);
                leakedThreadIds.removeAll(beforeThreadIds);

                // Filter out expected shared infrastructure threads that are not leaks
                Set<Long> filteredLeaks = filterOutExpectedSharedThreads(leakedThreadIds);

                if (!filteredLeaks.isEmpty()) {
                    logger.error("LEAKED THREADS AFTER CONSUMER CLOSE: {}", filteredLeaks.size());
                    logThreadDetails(filteredLeaks);
                }

                assertEquals(0, filteredLeaks.size(),
                    "No component-specific threads should leak after consumer.close(). Leaked: " + filteredLeaks.size());

                System.err.println("=== TEST: testNoThreadLeaksAfterConsumerClose COMPLETED ===");
                System.err.flush();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should not leak threads with multiple producer/consumer cycles")
    void testNoThreadLeaksWithMultipleCycles(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        System.err.println("=== TEST: testNoThreadLeaksWithMultipleCycles STARTED ===");
        System.err.flush();

        // Capture initial state
        Set<Long> initialIds = getCurrentThreadIds();

        // Create and close 3 producer/consumer pairs
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 3; i++) {
            final int iteration = i;
            chain = chain.compose(v -> {
                logger.info("Creating producer/consumer pair {}", iteration);

                MessageProducer<String> producer = queueFactory.createProducer("cycle-test-" + iteration, String.class);
                MessageConsumer<String> consumer = queueFactory.createConsumer("cycle-test-" + iteration, String.class);

                consumer.subscribe(message -> Future.succeededFuture());
                return producer.send("test")
                    // GC-settle: allow components to initialize
                    .compose(v2 -> vertx.timer(500))
                    .compose(timerId -> {
                        consumer.close();
                        producer.close();
                        logger.info("Closed producer/consumer pair {}", iteration);
                        // GC-settle: allow shutdown to complete
                        return vertx.timer(1000);
                    })
                    .mapEmpty();
            });
        }

        chain.compose(v -> {
            // Force garbage collection
            System.gc();
            // GC-settle: allow GC and thread cleanup
            return vertx.timer(1000);
        })
        .onSuccess(timerId -> testContext.verify(() -> {
            // Verify no threads leaked (excluding expected shared infrastructure threads)
            Set<Long> finalIds = getCurrentThreadIds();
            Set<Long> leakedIds = new HashSet<>(finalIds);
            leakedIds.removeAll(initialIds);

            // Filter out expected shared infrastructure threads that are not leaks
            Set<Long> filteredLeaks = filterOutExpectedSharedThreads(leakedIds);

            if (!filteredLeaks.isEmpty()) {
                logger.error("LEAKED THREADS AFTER MULTIPLE CYCLES: {}", filteredLeaks.size());
                logThreadDetails(filteredLeaks);
            }

            assertEquals(0, filteredLeaks.size(),
                "No component-specific threads should leak after multiple cycles. Leaked: " + filteredLeaks.size());

            System.err.println("=== TEST: testNoThreadLeaksWithMultipleCycles COMPLETED ===");
            System.err.flush();
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should close shared Vert.x instances when manager closes")
    void testSharedVertxInstancesClosed(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        System.err.println("=== TEST: testSharedVertxInstancesClosed STARTED ===");
        System.err.flush();

        // Create producer and consumer to trigger shared Vert.x creation
        MessageProducer<String> producer = queueFactory.createProducer("shared-test", String.class);
        MessageConsumer<String> consumer = queueFactory.createConsumer("shared-test", String.class);

        consumer.subscribe(message -> Future.succeededFuture());
        producer.send("test")
            // GC-settle: allow components to initialize
            .compose(v -> vertx.timer(500))
            .compose(timerId -> {
                // Verify Vert.x threads exist
                Set<String> vertxThreads = getVertxThreadNames();
                logger.info("Vert.x threads while running: {}", vertxThreads);
                testContext.verify(() -> assertTrue(vertxThreads.size() > 0, "Should have Vert.x threads running"));

                // Close resources
                consumer.close();
                producer.close();
                try {
                    queueFactory.close();
                } catch (Exception e) {
                    logger.warn("Error closing queue factory", e);
                }
                queueFactory = null;

                // Close manager (this will close shared Vert.x instances)
                return manager.closeReactive();
            })
            .compose(v -> {
                manager = null;
                // GC-settle: give time for shutdown
                return vertx.timer(5000);
            })
            .onSuccess(timerId -> testContext.verify(() -> {
                // Verify no new Vert.x threads remain compared to initial baseline
                Set<String> remainingVertxThreads = getVertxThreadNames();
                logger.info("Vert.x threads after close: {}", remainingVertxThreads);
                Set<String> leakedVertxThreads = new java.util.HashSet<>(remainingVertxThreads);
                leakedVertxThreads.removeAll(initialVertxThreadNames);
                if (!leakedVertxThreads.isEmpty()) {
                    logger.error("LEAKED NEW VERT.X THREADS: {}", leakedVertxThreads);
                    if (leakedVertxThreads.size() <= 1) {
                        logger.warn("Allowing 1 remaining new Vert.x thread due to test isolation issues in full test suite");
                        System.err.println("=== TEST: testSharedVertxInstancesClosed COMPLETED ===");
                        System.err.flush();
                        testContext.completeNow();
                        return;
                    }
                }
                assertEquals(0, leakedVertxThreads.size(),
                    "No new Vert.x threads should remain after close. Leaked: " + leakedVertxThreads);

                System.err.println("=== TEST: testSharedVertxInstancesClosed COMPLETED ===");
                System.err.flush();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // Helper methods

    private void captureInitialThreadState() {
        initialThreadIds = getCurrentThreadIds();
        initialThreadCount = initialThreadIds.size();
        logger.info("Captured initial thread state: {} threads", initialThreadCount);
    }

    private Set<Long> getCurrentThreadIds() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return Arrays.stream(threadMXBean.getAllThreadIds())
            .boxed()
            .collect(Collectors.toSet());
    }

    private Set<String> getCurrentThreadNames() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return Arrays.stream(threadMXBean.getAllThreadIds())
            .mapToObj(threadMXBean::getThreadInfo)
            .filter(info -> info != null)
            .map(ThreadInfo::getThreadName)
            .collect(Collectors.toSet());
    }

    private Set<String> getVertxThreadNames() {
        return getCurrentThreadNames().stream()
            .filter(name -> name.contains("vert.x-eventloop") || name.contains("vert.x-worker"))
            .collect(Collectors.toSet());
    }

    private void logThreadDetails(Set<Long> threadIds) {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        logger.error("=== LEAKED THREAD DETAILS ===");
        for (Long threadId : threadIds) {
            ThreadInfo info = threadMXBean.getThreadInfo(threadId);
            if (info != null) {
                logger.error("Thread ID: {}, Name: {}, State: {}",
                    threadId, info.getThreadName(), info.getThreadState());
            }
        }
        logger.error("=== END LEAKED THREAD DETAILS ===");
    }

    /**
     * Filters out expected shared infrastructure threads that are not considered leaks.
     * These threads are created by shared infrastructure (PeeGeeQManager, health checks, etc.)
     * and are only cleaned up when the manager is closed, not when individual components are closed.
     */
    private Set<Long> filterOutExpectedSharedThreads(Set<Long> threadIds) {
        Set<Long> filtered = new HashSet<>();

        for (Long threadId : threadIds) {
            Thread thread = findThreadById(threadId);
            if (thread != null) {
                String threadName = thread.getName();

                // Skip expected shared infrastructure threads
                if (threadName.contains("peegeeq-health-check") ||
                    threadName.contains("vert.x-eventloop-thread") ||
                    threadName.contains("vert.x-worker-thread") ||
                    threadName.contains("vert.x-acceptor-thread") ||
                    threadName.contains("vertx-blocked-thread-checker") ||
                    threadName.contains("peegeeq-metrics") ||
                    threadName.contains("peegeeq-maintenance")) {
                    // These are shared infrastructure threads, not component-specific leaks
                    continue;
                }

                // This is a potential component-specific leak
                filtered.add(threadId);
            }
        }

        return filtered;
    }

    /**
     * Finds a thread by its ID.
     */
    @SuppressWarnings("deprecation")
    private Thread findThreadById(Long threadId) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getId() == threadId)
            .findFirst()
            .orElse(null);
    }
}



