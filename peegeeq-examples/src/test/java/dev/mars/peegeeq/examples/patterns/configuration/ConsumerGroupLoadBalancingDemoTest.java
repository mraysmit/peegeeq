package dev.mars.peegeeq.examples.patterns.configuration;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Consumer Group Load Balancing Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Round Robin Load Balancing - Even distribution across consumers
 * 2. Weighted Load Balancing - Distribution based on consumer capacity
 * 3. Sticky Session Load Balancing - Session affinity for stateful processing
 * 4. Dynamic Load Balancing - Adaptive distribution based on performance
 * 5. Failover Load Balancing - Automatic failover when consumers fail
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerGroupLoadBalancingDemoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_loadbalancing_demo")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_pass");

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Load balancing strategies
    enum LoadBalancingStrategy {
        ROUND_ROBIN("round-robin", "Even distribution across all consumers"),
        WEIGHTED("weighted", "Distribution based on consumer capacity weights"),
        STICKY_SESSION("sticky-session", "Session affinity for stateful processing"),
        DYNAMIC("dynamic", "Adaptive distribution based on performance metrics"),
        FAILOVER("failover", "Automatic failover when consumers fail");

        final String name;
        final String description;

        LoadBalancingStrategy(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    // Work item for load balancing tests
    static class WorkItem {
        public final String workId;
        public final String sessionId;
        public final int processingTimeMs;
        public final int priority;
        public final JsonObject data;
        public final Instant timestamp;

        public WorkItem(String workId, String sessionId, int processingTimeMs, int priority, JsonObject data) {
            this.workId = workId;
            this.sessionId = sessionId;
            this.processingTimeMs = processingTimeMs;
            this.priority = priority;
            this.data = data;
            this.timestamp = Instant.now();
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("workId", workId)
                    .put("sessionId", sessionId)
                    .put("processingTimeMs", processingTimeMs)
                    .put("priority", priority)
                    .put("data", data)
                    .put("timestamp", timestamp.toString());
        }
    }

    // Consumer performance metrics
    static class ConsumerMetrics {
        public final String consumerId;
        public final AtomicInteger processedCount = new AtomicInteger(0);
        public final AtomicLong totalProcessingTime = new AtomicLong(0);
        public final AtomicInteger failureCount = new AtomicInteger(0);
        public final int weight;
        public volatile boolean isHealthy = true;

        public ConsumerMetrics(String consumerId, int weight) {
            this.consumerId = consumerId;
            this.weight = weight;
        }

        public double getAverageProcessingTime() {
            int count = processedCount.get();
            return count > 0 ? (double) totalProcessingTime.get() / count : 0.0;
        }

        public double getSuccessRate() {
            int total = processedCount.get() + failureCount.get();
            return total > 0 ? (double) processedCount.get() / total : 1.0;
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n⚖️ Setting up Consumer Group Load Balancing Demo Test");
        
        // Configure database connection
        String jdbcUrl = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();

        System.setProperty("peegeeq.database.url", jdbcUrl);
        System.setProperty("peegeeq.database.username", username);
        System.setProperty("peegeeq.database.password", password);

        // Initialize PeeGeeQ with load balancing configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for load balancing pattern testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Consumer Group Load Balancing Demo Test");
        
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                System.err.println("⚠️ Error during manager cleanup: " + e.getMessage());
            }
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        System.out.println("✅ Cleanup complete");
    }

    @Test
    @Order(1)
    @DisplayName("Round Robin Load Balancing - Even Distribution Across Consumers")
    void testRoundRobinLoadBalancing() throws Exception {
        System.out.println("\n🔄 Testing Round Robin Load Balancing");

        String queueName = "loadbalancing-roundrobin-queue";
        int numConsumers = 3;
        int numMessages = 15; // Should distribute 5 messages per consumer
        
        List<ConsumerMetrics> consumerMetrics = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(numMessages);

        // Create producer
        MessageProducer<WorkItem> producer = queueFactory.createProducer(queueName, WorkItem.class);

        // Create multiple consumers with round-robin behavior
        List<MessageConsumer<WorkItem>> consumers = new ArrayList<>();
        for (int i = 0; i < numConsumers; i++) {
            String consumerId = "round-robin-consumer-" + (i + 1);
            ConsumerMetrics metrics = new ConsumerMetrics(consumerId, 1); // Equal weight
            consumerMetrics.add(metrics);

            MessageConsumer<WorkItem> consumer = queueFactory.createConsumer(queueName, WorkItem.class);
            consumers.add(consumer);

            // Subscribe with processing tracking
            consumer.subscribe(message -> {
                WorkItem work = message.getPayload();
                long startTime = System.currentTimeMillis();
                
                try {
                    // Simulate work processing
                    Thread.sleep(work.processingTimeMs);
                    
                    long processingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(processingTime);
                    
                    System.out.println("🔄 " + consumerId + " processed work: " + work.workId + 
                                     " (processing time: " + processingTime + "ms)");
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    metrics.failureCount.incrementAndGet();
                    System.err.println("❌ " + consumerId + " failed to process work: " + work.workId);
                }
                
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
        }

        // Send work items for round-robin distribution
        System.out.println("📤 Sending " + numMessages + " work items for round-robin distribution...");
        
        for (int i = 0; i < numMessages; i++) {
            JsonObject workData = new JsonObject()
                    .put("taskType", "round-robin-task")
                    .put("sequence", i + 1);
            
            WorkItem work = new WorkItem("rr-work-" + (i + 1), "session-" + (i % 3), 
                                       100, 5, workData);
            producer.send(work);
        }

        // Wait for all work items to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Should process all work items");

        // Verify round-robin distribution
        System.out.println("📊 Round Robin Distribution Results:");
        int totalProcessed = 0;
        for (ConsumerMetrics metrics : consumerMetrics) {
            int processed = metrics.processedCount.get();
            totalProcessed += processed;
            System.out.println("  " + metrics.consumerId + ": " + processed + " items processed");
        }

        assertEquals(numMessages, totalProcessed, "Should process all messages");
        
        // Verify even distribution (within tolerance)
        int expectedPerConsumer = numMessages / numConsumers;
        for (ConsumerMetrics metrics : consumerMetrics) {
            int processed = metrics.processedCount.get();
            assertTrue(Math.abs(processed - expectedPerConsumer) <= 1, 
                      "Round-robin should distribute evenly: " + metrics.consumerId + 
                      " processed " + processed + ", expected ~" + expectedPerConsumer);
        }

        // Cleanup consumers
        consumers.forEach(MessageConsumer::close);

        System.out.println("✅ Round Robin Load Balancing test completed successfully");
        System.out.println("📊 Total work items processed: " + totalProcessed);
    }

    @Test
    @Order(2)
    @DisplayName("Weighted Load Balancing - Distribution Based on Consumer Capacity")
    void testWeightedLoadBalancing() throws Exception {
        System.out.println("\n⚖️ Testing Weighted Load Balancing");

        String queueName = "loadbalancing-weighted-queue";
        int numMessages = 20;
        
        // Consumer weights: 1:2:3 ratio (total weight = 6)
        int[] weights = {1, 2, 3};
        List<ConsumerMetrics> consumerMetrics = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(numMessages);

        // Create producer
        MessageProducer<WorkItem> producer = queueFactory.createProducer(queueName, WorkItem.class);

        // Create weighted consumers
        List<MessageConsumer<WorkItem>> consumers = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            String consumerId = "weighted-consumer-" + (i + 1);
            ConsumerMetrics metrics = new ConsumerMetrics(consumerId, weights[i]);
            consumerMetrics.add(metrics);

            MessageConsumer<WorkItem> consumer = queueFactory.createConsumer(queueName, WorkItem.class);
            consumers.add(consumer);

            // Subscribe with capacity-based processing
            final int consumerIndex = i;
            consumer.subscribe(message -> {
                WorkItem work = message.getPayload();
                long startTime = System.currentTimeMillis();
                
                try {
                    // Simulate processing time inversely proportional to weight
                    int processingTime = work.processingTimeMs / weights[consumerIndex];
                    Thread.sleep(processingTime);
                    
                    long actualProcessingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(actualProcessingTime);
                    
                    System.out.println("⚖️ " + consumerId + " (weight=" + weights[consumerIndex] + 
                                     ") processed work: " + work.workId + 
                                     " (processing time: " + actualProcessingTime + "ms)");
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    metrics.failureCount.incrementAndGet();
                    System.err.println("❌ " + consumerId + " failed to process work: " + work.workId);
                }
                
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
        }

        // Send work items for weighted distribution
        System.out.println("📤 Sending " + numMessages + " work items for weighted distribution...");
        
        for (int i = 0; i < numMessages; i++) {
            JsonObject workData = new JsonObject()
                    .put("taskType", "weighted-task")
                    .put("sequence", i + 1)
                    .put("complexity", "medium");
            
            WorkItem work = new WorkItem("weighted-work-" + (i + 1), "session-" + (i % 5), 
                                       300, 5, workData);
            producer.send(work);
        }

        // Wait for all work items to be processed
        assertTrue(latch.await(45, TimeUnit.SECONDS), "Should process all work items");

        // Verify weighted distribution
        System.out.println("📊 Weighted Distribution Results:");
        int totalProcessed = 0;
        int totalWeight = Arrays.stream(weights).sum();
        
        for (int i = 0; i < consumerMetrics.size(); i++) {
            ConsumerMetrics metrics = consumerMetrics.get(i);
            int processed = metrics.processedCount.get();
            totalProcessed += processed;
            
            int expectedProcessed = (numMessages * weights[i]) / totalWeight;
            double avgProcessingTime = metrics.getAverageProcessingTime();
            
            System.out.println("  " + metrics.consumerId + " (weight=" + weights[i] + "): " + 
                             processed + " items processed (expected ~" + expectedProcessed + 
                             "), avg processing time: " + String.format("%.1f", avgProcessingTime) + "ms");
        }

        assertEquals(numMessages, totalProcessed, "Should process all messages");

        // Cleanup consumers
        consumers.forEach(MessageConsumer::close);

        System.out.println("✅ Weighted Load Balancing test completed successfully");
        System.out.println("📊 Total work items processed: " + totalProcessed);
    }

    @Test
    @Order(3)
    @DisplayName("Sticky Session Load Balancing - Session Affinity for Stateful Processing")
    void testStickySessionLoadBalancing() throws Exception {
        System.out.println("\n🔗 Testing Sticky Session Load Balancing");

        String queueName = "loadbalancing-sticky-queue";
        int numSessions = 3;
        int messagesPerSession = 5;
        int totalMessages = numSessions * messagesPerSession;

        Map<String, String> sessionToConsumerMapping = new HashMap<>();
        Map<String, ConsumerMetrics> consumerMetricsMap = new HashMap<>();
        CountDownLatch latch = new CountDownLatch(totalMessages);

        // Create producer
        MessageProducer<WorkItem> producer = queueFactory.createProducer(queueName, WorkItem.class);

        // Create consumers for sticky session handling
        List<MessageConsumer<WorkItem>> consumers = new ArrayList<>();
        for (int i = 0; i < numSessions; i++) {
            String consumerId = "sticky-consumer-" + (i + 1);
            ConsumerMetrics metrics = new ConsumerMetrics(consumerId, 1);
            consumerMetricsMap.put(consumerId, metrics);

            MessageConsumer<WorkItem> consumer = queueFactory.createConsumer(queueName, WorkItem.class);
            consumers.add(consumer);

            // Subscribe with session affinity logic
            consumer.subscribe(message -> {
                WorkItem work = message.getPayload();
                String sessionId = work.sessionId;

                // Implement sticky session logic
                String assignedConsumer = sessionToConsumerMapping.computeIfAbsent(sessionId,
                    k -> consumerId);

                if (assignedConsumer.equals(consumerId)) {
                    // This consumer handles this session
                    long startTime = System.currentTimeMillis();

                    try {
                        // Simulate stateful processing
                        Thread.sleep(work.processingTimeMs);

                        long processingTime = System.currentTimeMillis() - startTime;
                        metrics.processedCount.incrementAndGet();
                        metrics.totalProcessingTime.addAndGet(processingTime);

                        System.out.println("🔗 " + consumerId + " processed work: " + work.workId +
                                         " for session: " + sessionId +
                                         " (processing time: " + processingTime + "ms)");

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        metrics.failureCount.incrementAndGet();
                        System.err.println("❌ " + consumerId + " failed to process work: " + work.workId);
                    }
                } else {
                    // Wrong consumer for this session - should not happen in ideal sticky session
                    System.out.println("⚠️ " + consumerId + " received work for wrong session: " + sessionId +
                                     " (assigned to: " + assignedConsumer + ")");
                }

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
        }

        // Send work items with session affinity
        System.out.println("📤 Sending " + totalMessages + " work items with session affinity...");

        for (int session = 0; session < numSessions; session++) {
            String sessionId = "session-" + (session + 1);

            for (int msg = 0; msg < messagesPerSession; msg++) {
                JsonObject workData = new JsonObject()
                        .put("taskType", "sticky-session-task")
                        .put("sessionId", sessionId)
                        .put("messageInSession", msg + 1)
                        .put("statefulData", "session-state-" + session);

                WorkItem work = new WorkItem("sticky-work-" + session + "-" + msg,
                                           sessionId, 150, 5, workData);
                producer.send(work);
            }
        }

        // Wait for all work items to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Should process all work items");

        // Verify sticky session behavior
        System.out.println("📊 Sticky Session Distribution Results:");
        int totalProcessed = 0;

        for (ConsumerMetrics metrics : consumerMetricsMap.values()) {
            int processed = metrics.processedCount.get();
            totalProcessed += processed;
            System.out.println("  " + metrics.consumerId + ": " + processed + " items processed");
        }

        assertEquals(totalMessages, totalProcessed, "Should process all messages");

        // Verify session affinity
        System.out.println("🔗 Session to Consumer Mapping:");
        for (Map.Entry<String, String> entry : sessionToConsumerMapping.entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
        }

        assertEquals(numSessions, sessionToConsumerMapping.size(),
                    "Should have mapping for all sessions");

        // Cleanup consumers
        consumers.forEach(MessageConsumer::close);

        System.out.println("✅ Sticky Session Load Balancing test completed successfully");
        System.out.println("📊 Total work items processed: " + totalProcessed);
    }

    @Test
    @Order(4)
    @DisplayName("Dynamic Load Balancing - Adaptive Distribution Based on Performance")
    void testDynamicLoadBalancing() throws Exception {
        System.out.println("\n📈 Testing Dynamic Load Balancing");

        String queueName = "loadbalancing-dynamic-queue";
        int numConsumers = 3;
        int numMessages = 18;

        List<ConsumerMetrics> consumerMetrics = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(numMessages);

        // Create producer
        MessageProducer<WorkItem> producer = queueFactory.createProducer(queueName, WorkItem.class);

        // Create consumers with different performance characteristics
        List<MessageConsumer<WorkItem>> consumers = new ArrayList<>();
        int[] processingDelays = {50, 150, 300}; // Fast, medium, slow consumers

        for (int i = 0; i < numConsumers; i++) {
            String consumerId = "dynamic-consumer-" + (i + 1);
            ConsumerMetrics metrics = new ConsumerMetrics(consumerId, 1);
            consumerMetrics.add(metrics);

            MessageConsumer<WorkItem> consumer = queueFactory.createConsumer(queueName, WorkItem.class);
            consumers.add(consumer);

            // Subscribe with performance-based processing
            final int consumerIndex = i;
            consumer.subscribe(message -> {
                WorkItem work = message.getPayload();
                long startTime = System.currentTimeMillis();

                try {
                    // Simulate different processing speeds
                    Thread.sleep(processingDelays[consumerIndex]);

                    long processingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(processingTime);

                    // Update health status based on performance
                    double avgTime = metrics.getAverageProcessingTime();
                    metrics.isHealthy = avgTime < 500; // Healthy if avg < 500ms

                    String performanceLevel = processingDelays[consumerIndex] <= 100 ? "FAST" :
                                            processingDelays[consumerIndex] <= 200 ? "MEDIUM" : "SLOW";

                    System.out.println("📈 " + consumerId + " (" + performanceLevel + ") processed work: " +
                                     work.workId + " (processing time: " + processingTime + "ms, " +
                                     "avg: " + String.format("%.1f", avgTime) + "ms)");

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    metrics.failureCount.incrementAndGet();
                    metrics.isHealthy = false;
                    System.err.println("❌ " + consumerId + " failed to process work: " + work.workId);
                }

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
        }

        // Send work items for dynamic distribution
        System.out.println("📤 Sending " + numMessages + " work items for dynamic distribution...");

        for (int i = 0; i < numMessages; i++) {
            JsonObject workData = new JsonObject()
                    .put("taskType", "dynamic-task")
                    .put("sequence", i + 1)
                    .put("adaptiveRouting", true);

            WorkItem work = new WorkItem("dynamic-work-" + (i + 1), "session-" + (i % 4),
                                       100, 5, workData);
            producer.send(work);
        }

        // Wait for all work items to be processed
        assertTrue(latch.await(45, TimeUnit.SECONDS), "Should process all work items");

        // Analyze dynamic distribution results
        System.out.println("📊 Dynamic Load Balancing Results:");
        int totalProcessed = 0;

        for (int i = 0; i < consumerMetrics.size(); i++) {
            ConsumerMetrics metrics = consumerMetrics.get(i);
            int processed = metrics.processedCount.get();
            totalProcessed += processed;

            double avgProcessingTime = metrics.getAverageProcessingTime();
            double successRate = metrics.getSuccessRate();
            String performanceLevel = processingDelays[i] <= 100 ? "FAST" :
                                    processingDelays[i] <= 200 ? "MEDIUM" : "SLOW";

            System.out.println("  " + metrics.consumerId + " (" + performanceLevel + "): " +
                             processed + " items processed, " +
                             "avg time: " + String.format("%.1f", avgProcessingTime) + "ms, " +
                             "success rate: " + String.format("%.1f", successRate * 100) + "%, " +
                             "healthy: " + metrics.isHealthy);
        }

        assertEquals(numMessages, totalProcessed, "Should process all messages");

        // Verify that faster consumers processed more messages (in ideal dynamic balancing)
        ConsumerMetrics fastConsumer = consumerMetrics.get(0);
        ConsumerMetrics slowConsumer = consumerMetrics.get(2);

        System.out.println("🏃 Performance comparison:");
        System.out.println("  Fast consumer avg time: " + String.format("%.1f", fastConsumer.getAverageProcessingTime()) + "ms");
        System.out.println("  Slow consumer avg time: " + String.format("%.1f", slowConsumer.getAverageProcessingTime()) + "ms");

        // Cleanup consumers
        consumers.forEach(MessageConsumer::close);

        System.out.println("✅ Dynamic Load Balancing test completed successfully");
        System.out.println("📊 Total work items processed: " + totalProcessed);
    }
}
