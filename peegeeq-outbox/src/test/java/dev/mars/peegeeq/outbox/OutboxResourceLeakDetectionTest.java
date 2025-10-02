package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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
@Testcontainers
public class OutboxResourceLeakDetectionTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxResourceLeakDetectionTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("peegeeq_test")
        .withUsername("peegeeq")
        .withPassword("peegeeq");
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private Set<Long> initialThreadIds;
    private int initialThreadCount;
    
    @BeforeEach
    void setUp() throws Exception {
        System.err.println("=== OutboxResourceLeakDetectionTest.setUp() STARTED ===");
        System.err.flush();
        
        // Capture initial thread state
        captureInitialThreadState();
        
        // Configure system properties
        System.setProperty("peegeeq.db.host", postgres.getHost());
        System.setProperty("peegeeq.db.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.db.database", postgres.getDatabaseName());
        System.setProperty("peegeeq.db.username", postgres.getUsername());
        System.setProperty("peegeeq.db.password", postgres.getPassword());
        
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
    void tearDown() throws Exception {
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
            try {
                manager.close();
            } catch (Exception e) {
                logger.error("Error closing manager", e);
            }
        }
        
        System.err.println("=== OutboxResourceLeakDetectionTest.tearDown() COMPLETED ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Should not leak threads after producer close")
    void testNoThreadLeaksAfterProducerClose() throws Exception {
        System.err.println("=== TEST: testNoThreadLeaksAfterProducerClose STARTED ===");
        System.err.flush();
        
        // Capture threads before creating producer
        Set<Long> beforeThreadIds = getCurrentThreadIds();
        int beforeCount = beforeThreadIds.size();
        logger.info("Thread count before producer: {}", beforeCount);
        
        // Create producer
        MessageProducer<String> producer = queueFactory.createProducer("leak-test-producer", String.class);
        
        // Send a message to ensure producer is fully initialized
        producer.send("test message").get();
        Thread.sleep(500);
        
        // Capture threads while producer is active
        Set<Long> activeThreadIds = getCurrentThreadIds();
        int activeCount = activeThreadIds.size();
        logger.info("Thread count with active producer: {}", activeCount);
        
        // Close producer
        producer.close();
        Thread.sleep(1000);
        
        // Capture threads after close
        Set<Long> afterThreadIds = getCurrentThreadIds();
        int afterCount = afterThreadIds.size();
        logger.info("Thread count after producer close: {}", afterCount);
        
        // Find leaked threads
        Set<Long> leakedThreadIds = new HashSet<>(afterThreadIds);
        leakedThreadIds.removeAll(beforeThreadIds);
        
        if (!leakedThreadIds.isEmpty()) {
            logger.error("LEAKED THREADS AFTER PRODUCER CLOSE: {}", leakedThreadIds.size());
            logThreadDetails(leakedThreadIds);
        }
        
        assertEquals(0, leakedThreadIds.size(), 
            "No threads should leak after producer.close(). Leaked: " + leakedThreadIds.size());
        
        System.err.println("=== TEST: testNoThreadLeaksAfterProducerClose COMPLETED ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Should not leak threads after consumer close")
    void testNoThreadLeaksAfterConsumerClose() throws Exception {
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
        });
        
        Thread.sleep(1000); // Let consumer start polling
        
        // Capture threads while consumer is active
        Set<Long> activeThreadIds = getCurrentThreadIds();
        int activeCount = activeThreadIds.size();
        logger.info("Thread count with active consumer: {}", activeCount);
        
        // Verify consumer created threads
        assertTrue(activeCount > beforeCount, 
            "Consumer should create polling threads (before: " + beforeCount + ", active: " + activeCount + ")");
        
        // Close consumer
        consumer.close();
        Thread.sleep(2000); // Give scheduler time to shut down
        
        // Capture threads after close
        Set<Long> afterThreadIds = getCurrentThreadIds();
        int afterCount = afterThreadIds.size();
        logger.info("Thread count after consumer close: {}", afterCount);
        
        // Find leaked threads
        Set<Long> leakedThreadIds = new HashSet<>(afterThreadIds);
        leakedThreadIds.removeAll(beforeThreadIds);
        
        if (!leakedThreadIds.isEmpty()) {
            logger.error("LEAKED THREADS AFTER CONSUMER CLOSE: {}", leakedThreadIds.size());
            logThreadDetails(leakedThreadIds);
        }
        
        assertEquals(0, leakedThreadIds.size(), 
            "No threads should leak after consumer.close(). Leaked: " + leakedThreadIds.size());
        
        System.err.println("=== TEST: testNoThreadLeaksAfterConsumerClose COMPLETED ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Should not leak threads with multiple producer/consumer cycles")
    void testNoThreadLeaksWithMultipleCycles() throws Exception {
        System.err.println("=== TEST: testNoThreadLeaksWithMultipleCycles STARTED ===");
        System.err.flush();
        
        // Capture initial state
        Set<Long> initialIds = getCurrentThreadIds();
        
        // Create and close 3 producer/consumer pairs
        for (int i = 1; i <= 3; i++) {
            logger.info("Creating producer/consumer pair {}", i);
            
            MessageProducer<String> producer = queueFactory.createProducer("cycle-test-" + i, String.class);
            MessageConsumer<String> consumer = queueFactory.createConsumer("cycle-test-" + i, String.class);
            
            consumer.subscribe(message -> {});
            producer.send("test").get();
            
            Thread.sleep(500);
            
            consumer.close();
            producer.close();
            
            logger.info("Closed producer/consumer pair {}", i);
            Thread.sleep(1000);
        }
        
        // Force garbage collection
        System.gc();
        Thread.sleep(500);
        
        // Verify no threads leaked
        Set<Long> finalIds = getCurrentThreadIds();
        Set<Long> leakedIds = new HashSet<>(finalIds);
        leakedIds.removeAll(initialIds);
        
        if (!leakedIds.isEmpty()) {
            logger.error("LEAKED THREADS AFTER MULTIPLE CYCLES: {}", leakedIds.size());
            logThreadDetails(leakedIds);
        }
        
        assertEquals(0, leakedIds.size(), 
            "No threads should leak after multiple cycles. Leaked: " + leakedIds.size());
        
        System.err.println("=== TEST: testNoThreadLeaksWithMultipleCycles COMPLETED ===");
        System.err.flush();
    }
    
    @Test
    @DisplayName("Should close shared Vert.x instances when manager closes")
    void testSharedVertxInstancesClosed() throws Exception {
        System.err.println("=== TEST: testSharedVertxInstancesClosed STARTED ===");
        System.err.flush();
        
        // Create producer and consumer to trigger shared Vert.x creation
        MessageProducer<String> producer = queueFactory.createProducer("shared-test", String.class);
        MessageConsumer<String> consumer = queueFactory.createConsumer("shared-test", String.class);
        
        consumer.subscribe(message -> {});
        producer.send("test").get();
        
        Thread.sleep(500);
        
        // Verify Vert.x threads exist
        Set<String> vertxThreads = getVertxThreadNames();
        logger.info("Vert.x threads while running: {}", vertxThreads);
        assertTrue(vertxThreads.size() > 0, "Should have Vert.x threads running");
        
        // Close resources
        consumer.close();
        producer.close();
        queueFactory.close();
        queueFactory = null;
        
        manager.close();
        manager = null;
        
        // Give time for shutdown
        Thread.sleep(3000);
        
        // Verify all Vert.x threads are gone
        Set<String> remainingVertxThreads = getVertxThreadNames();
        logger.info("Vert.x threads after close: {}", remainingVertxThreads);
        
        if (!remainingVertxThreads.isEmpty()) {
            logger.error("LEAKED VERT.X THREADS: {}", remainingVertxThreads);
        }
        
        assertEquals(0, remainingVertxThreads.size(), 
            "All Vert.x threads should be stopped. Remaining: " + remainingVertxThreads);
        
        System.err.println("=== TEST: testSharedVertxInstancesClosed COMPLETED ===");
        System.err.flush();
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
}

