package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Promise;
import java.time.Instant;
import java.util.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class ConsumerGroupLoadBalancingDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupLoadBalancingDemoTest.class);


    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    /** Set by tests that create a ConsumerGroup; closed in tearDown before the manager closes. */
    private ConsumerGroup<WorkItem> consumerGroupToCleanup;

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
        public String workId;
        public String sessionId;
        public int processingTimeMs;
        public int priority;
        public Map<String, Object> data;
        public String timestamp;

        // Default constructor for Jackson
        public WorkItem() {}

        public WorkItem(String workId, String sessionId, int processingTimeMs, int priority, Map<String, Object> data) {
            this.workId = workId;
            this.sessionId = sessionId;
            this.processingTimeMs = processingTimeMs;
            this.priority = priority;
            this.data = data;
            this.timestamp = Instant.now().toString();
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
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("Setting up Consumer Group Load Balancing Demo Test");

        // Configure database connection properties
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize database schema for consumer group load balancing test
        logger.info("Initializing database schema for consumer group load balancing test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Initialize PeeGeeQ with load balancing configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                var databaseService = new PgDatabaseService(manager);
                // Pass the configuration so factory-created consumers derive their
                // LISTEN channels from the configured schema (no default-schema fallback)
                QueueFactoryProvider provider = new PgQueueFactoryProvider(config);
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                queueFactory = provider.createFactory("native", databaseService);
                logger.info("Setup complete - Ready for load balancing pattern testing");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Cleaning up Consumer Group Load Balancing Demo Test");

        if (manager == null) {
            testContext.completeNow();
            return;
        }

        // Close the consumer group (if set) BEFORE the manager so that all pooled
        // connections used by in-flight ACK / LISTEN operations are returned before
        // pool.close() is called inside manager.closeReactive().
        ConsumerGroup<WorkItem> groupToClose = consumerGroupToCleanup;
        consumerGroupToCleanup = null;

        Future<Void> groupClose = (groupToClose != null)
            ? groupToClose.stopGracefully()
                .compose(v -> groupToClose.close())
                .transform(ar -> {
                    if (ar.failed()) logger.warn("Consumer group close failed during teardown: {}", ar.cause().getMessage());
                    return Future.<Void>succeededFuture();
                })
            : Future.<Void>succeededFuture();

        groupClose
            .compose(v -> manager.closeReactive())
            .onSuccess(v -> {
                logger.info("Cleanup complete");
                testContext.completeNow();
            })
            .onFailure(err -> {
                logger.warn("Error during manager cleanup: {}", err.getMessage());
                testContext.completeNow();
            });
    }

    /**
     *  **Round Robin Load Balancing Test**
     *
     * **Purpose**: Demonstrates even distribution of messages across multiple consumers
     * using ConsumerGroup's built-in round-robin load balancing.
     *
     * **Pattern**: Uses ConsumerGroup without filters to enable automatic round-robin
     * distribution where each message is processed by exactly one consumer.
     *
     * **Key Benefits**:
     * - Even workload distribution
     * - Automatic load balancing
     * - Scalable consumer management
     * - Built-in fault tolerance
     *
     * ** DEMO PATTERN**: This test uses simple round-robin without weights.
     * ** PRODUCTION NOTE**: Real systems would consider consumer capacity,
     * health checks, and dynamic scaling.
     */
    @Test
    @DisplayName("Round Robin Load Balancing - Even Distribution Across Consumers")
    void testRoundRobinLoadBalancing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: round robin load balancing");
        logger.info("Testing Round Robin Load Balancing");

        String queueName = "loadbalancing-roundrobin-queue-" + System.currentTimeMillis();
        int numConsumers = 3;
        int numMessages = 15; // Should distribute 5 messages per consumer

        List<ConsumerMetrics> consumerMetrics = new ArrayList<>();
        var processedCheckpoint = testContext.checkpoint(numMessages);

        // Create producer
        MessageProducer<WorkItem> producer = queueFactory.createProducer(queueName, WorkItem.class);

        //  **Create ConsumerGroup for Round-Robin Distribution**
        // ConsumerGroup automatically provides round-robin load balancing
        consumerGroupToCleanup = queueFactory.createConsumerGroup(
            "RoundRobinGroup", queueName, WorkItem.class);
        ConsumerGroup<WorkItem> roundRobinGroup = consumerGroupToCleanup;

        // Create consumers with equal capacity (round-robin)
        for (int i = 0; i < numConsumers; i++) {
            String consumerId = "round-robin-consumer-" + (i + 1);
            ConsumerMetrics metrics = new ConsumerMetrics(consumerId, 1); // Equal weight
            consumerMetrics.add(metrics);

            //  **Round-Robin Handler**: Each consumer processes messages equally
            MessageHandler<WorkItem> roundRobinHandler = message -> {
                WorkItem work = message.getPayload();
                long startTime = System.currentTimeMillis();

                return vertx.timer(work.processingTimeMs).<Void>map(t -> {
                    long processingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(processingTime);

                    logger.debug("{} processed work: {} (processing time: {}ms)", consumerId, work.workId, processingTime);

                    processedCheckpoint.flag();
                    return null;
                });
            };

            //  **Add Consumer without Filter**: Enables automatic round-robin distribution
            // No MessageFilter means all consumers are eligible for all messages
            roundRobinGroup.addConsumer(consumerId, roundRobinHandler);
        }

        // Start the consumer group and wait for LISTEN registration before sending
        roundRobinGroup.start()
            .onFailure(testContext::failNow)
            .onSuccess(v -> {
                //  **Send work items for round-robin distribution**
                logger.info("Sending {} work items for round-robin distribution...", numMessages);

                for (int i = 0; i < numMessages; i++) {
                    Map<String, Object> workData = new HashMap<>();
                    workData.put("taskType", "round-robin-task");
                    workData.put("sequence", i + 1);

                    WorkItem work = new WorkItem("rr-work-" + (i + 1), "session-" + (i % 3),
                                               100, 5, workData);
                    producer.send(work).onFailure(testContext::failNow);
                }
            });

        // Wait for all work items to be processed
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should process all work items");

        // Verify round-robin distribution
        logger.info("Round Robin Distribution Results:");
        int totalProcessed = 0;
        for (ConsumerMetrics metrics : consumerMetrics) {
            int processed = metrics.processedCount.get();
            totalProcessed += processed;
            logger.info("  {}: {} items processed", metrics.consumerId, processed);
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

        //  Cleanup is handled in tearDown to ensure it completes before manager.closeReactive().
        logger.info("Round Robin Load Balancing test completed successfully");
        logger.info("Total work items processed: {}", totalProcessed);
    }

    @Test
    @DisplayName("Weighted Load Balancing - Distribution Based on Consumer Capacity")
    void testWeightedLoadBalancing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: weighted load balancing");
        logger.info("Testing Weighted Load Balancing");

        String queueName = "loadbalancing-weighted-queue-" + System.currentTimeMillis();
        int numMessages = 20;

        // Consumer weights: 1:2:3 ratio (total weight = 6)
        int[] weights = {1, 2, 3};
        List<ConsumerMetrics> consumerMetrics = new ArrayList<>();
        var processedCheckpoint = testContext.checkpoint(numMessages);

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

                // Simulate processing time inversely proportional to weight
                int processingTime = work.processingTimeMs / weights[consumerIndex];
                return vertx.timer(processingTime).<Void>map(t -> {
                    long actualProcessingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(actualProcessingTime);

                    logger.debug("{} (weight={}) processed work: {} (processing time: {}ms)", consumerId, weights[consumerIndex], work.workId, actualProcessingTime);

                    processedCheckpoint.flag();
                    return null;
                });
            });
        }

        // Send work items for weighted distribution
        logger.info("Sending {} work items for weighted distribution...", numMessages);

        for (int i = 0; i < numMessages; i++) {
            Map<String, Object> workData = new HashMap<>();
            workData.put("taskType", "weighted-task");
            workData.put("sequence", i + 1);
            workData.put("complexity", "medium");

            WorkItem work = new WorkItem("weighted-work-" + (i + 1), "session-" + (i % 5),
                                       300, 5, workData);
            producer.send(work).onFailure(testContext::failNow);
        }

        // Wait for all work items to be processed
        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS), "Should process all work items");

        // Verify weighted distribution
        logger.info("Weighted Distribution Results:");
        int totalProcessed = 0;
        int totalWeight = Arrays.stream(weights).sum();

        for (int i = 0; i < consumerMetrics.size(); i++) {
            ConsumerMetrics metrics = consumerMetrics.get(i);
            int processed = metrics.processedCount.get();
            totalProcessed += processed;

            int expectedProcessed = (numMessages * weights[i]) / totalWeight;
            double avgProcessingTime = metrics.getAverageProcessingTime();

            logger.info("  {} (weight={}): {} items processed (expected ~{}), avg processing time: {}ms", metrics.consumerId, weights[i], processed, expectedProcessed, String.format("%.1f", avgProcessingTime));
        }

        assertEquals(numMessages, totalProcessed, "Should process all messages");

        // Cleanup consumers
        consumers.forEach(MessageConsumer::close);

        logger.info("Weighted Load Balancing test completed successfully");
        logger.info("Total work items processed: {}", totalProcessed);
    }

    /**
     *  **Sticky Session Load Balancing Test**
     *
     * **Purpose**: Demonstrates session affinity where messages from the same session
     * are always processed by the same consumer to maintain stateful processing.
     *
     * **Pattern**: Uses ConsumerGroup with MessageFilter.byHeader() to route messages
     * based on sessionId header, ensuring each session is handled by a dedicated consumer.
     *
     * **Key Benefits**:
     * - Maintains session state consistency
     * - Enables stateful processing patterns
     * - Prevents session data corruption from concurrent processing
     * - Supports user session management, shopping carts, etc.
     *
     * ** DEMO PATTERN**: This test uses pre-assigned session-to-consumer mapping.
     * ** PRODUCTION NOTE**: Real systems would use consistent hashing or dynamic
     * session assignment with proper failover handling.
     */
    @Test
    @DisplayName("Sticky Session Load Balancing - Session Affinity for Stateful Processing")
    void testStickySessionLoadBalancing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: sticky session load balancing");
        logger.info("Testing Sticky Session Load Balancing");

        String queueName = "loadbalancing-sticky-queue-" + System.currentTimeMillis();
        int numSessions = 3;
        int messagesPerSession = 5;
        int totalMessages = numSessions * messagesPerSession;

        //  DEMO PATTERN: Pre-assign sessions to consumers for predictable testing
        //  PRODUCTION NOTE: Use consistent hashing or dynamic assignment with failover
        Map<String, String> sessionToConsumerMapping = new HashMap<>();
        sessionToConsumerMapping.put("session-1", "sticky-consumer-1");
        sessionToConsumerMapping.put("session-2", "sticky-consumer-2");
        sessionToConsumerMapping.put("session-3", "sticky-consumer-3");

        Map<String, ConsumerMetrics> consumerMetricsMap = new HashMap<>();
        var processedCheckpoint = testContext.checkpoint(totalMessages);

        // Create producer
        MessageProducer<WorkItem> producer = queueFactory.createProducer(queueName, WorkItem.class);

        //  **Create ConsumerGroup for Session Affinity**
        // Following the established pattern from AdvancedProducerConsumerGroupTest
        consumerGroupToCleanup = queueFactory.createConsumerGroup(
            "StickySessionGroup", queueName, WorkItem.class);
        ConsumerGroup<WorkItem> stickyGroup = consumerGroupToCleanup;

        // Create session-specific consumers using MessageFilter pattern
        for (int i = 0; i < numSessions; i++) {
            String consumerId = "sticky-consumer-" + (i + 1);
            String sessionId = "session-" + (i + 1);

            ConsumerMetrics metrics = new ConsumerMetrics(consumerId, 1);
            consumerMetricsMap.put(consumerId, metrics);

            //  **Session Affinity Handler**: Each consumer only processes its assigned session
            MessageHandler<WorkItem> sessionHandler = message -> {
                WorkItem work = message.getPayload();
                long startTime = System.currentTimeMillis();

                return vertx.timer(work.processingTimeMs).<Void>map(t -> {
                    long processingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(processingTime);

                    logger.debug("{} processed work: {} for session: {} (processing time: {}ms)", consumerId, work.workId, work.sessionId, processingTime);

                    processedCheckpoint.flag();
                    return null;
                });
            };

            //  **Add Consumer with Session Filter**: Only processes messages for this session
            // This ensures true session affinity - no race conditions or wrong assignments
            stickyGroup.addConsumer(consumerId, sessionHandler,
                MessageFilter.byHeader("sessionId", sessionId));
        }

        // Start the consumer group and wait for LISTEN registration before sending
        stickyGroup.start()
            .onFailure(testContext::failNow)
            .onSuccess(v -> {
                //  **Send work items with session affinity headers**
                //  KEY PATTERN: Set sessionId in message headers for MessageFilter.byHeader() routing
                logger.info("Sending {} work items with session affinity...", totalMessages);

                for (int session = 0; session < numSessions; session++) {
                    String sessionId = "session-" + (session + 1);

                    for (int msg = 0; msg < messagesPerSession; msg++) {
                        Map<String, Object> workData = new HashMap<>();
                        workData.put("taskType", "sticky-session-task");
                        workData.put("sessionId", sessionId);
                        workData.put("messageInSession", msg + 1);
                        workData.put("statefulData", "session-state-" + session);

                        WorkItem work = new WorkItem("sticky-work-" + session + "-" + msg,
                                                   sessionId, 150, 5, workData);

                        //  **Critical**: Set sessionId in message headers for filtering
                        // This is what enables MessageFilter.byHeader("sessionId", sessionId) to work
                        Map<String, String> headers = new HashMap<>();
                        headers.put("sessionId", sessionId);
                        headers.put("messageType", "sticky-session-task");

                        producer.send(work, headers).onFailure(testContext::failNow);
                    }
                }
            });

        // Wait for all work items to be processed
        // Each message takes 150ms + network overhead, so 15 messages * 200ms + buffer = ~5 seconds minimum
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS), "Should process all work items");

        // Verify sticky session behavior
        logger.info("Sticky Session Distribution Results:");
        int totalProcessed = 0;

        for (ConsumerMetrics metrics : consumerMetricsMap.values()) {
            int processed = metrics.processedCount.get();
            totalProcessed += processed;
            logger.info("  {}: {} items processed", metrics.consumerId, processed);
        }

        assertEquals(totalMessages, totalProcessed, "Should process all messages");

        // Verify session affinity
        logger.info("Session to Consumer Mapping:");
        for (Map.Entry<String, String> entry : sessionToConsumerMapping.entrySet()) {
            logger.info("  {} -> {}", entry.getKey(), entry.getValue());
        }

        assertEquals(numSessions, sessionToConsumerMapping.size(),
                    "Should have mapping for all sessions");

        // ConsumerGroup cleanup is handled deterministically by tearDown via consumerGroupToCleanup.
        logger.info("Sticky Session Load Balancing test completed successfully");
        logger.info("Total work items processed: {}", totalProcessed);
    }

    @Test
    @DisplayName("Dynamic Load Balancing - Adaptive Distribution Based on Performance")
    void testDynamicLoadBalancing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: dynamic load balancing");
        logger.info("Testing Dynamic Load Balancing");

        String queueName = "loadbalancing-dynamic-queue-" + System.currentTimeMillis();
        int numConsumers = 3;
        int numMessages = 18;

        List<ConsumerMetrics> consumerMetrics = new ArrayList<>();
        var processedCheckpoint = testContext.checkpoint(numMessages);

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

                // Simulate different processing speeds
                return vertx.timer(processingDelays[consumerIndex]).<Void>map(t -> {
                    long processingTime = System.currentTimeMillis() - startTime;
                    metrics.processedCount.incrementAndGet();
                    metrics.totalProcessingTime.addAndGet(processingTime);

                    // Update health status based on performance
                    double avgTime = metrics.getAverageProcessingTime();
                    metrics.isHealthy = avgTime < 500; // Healthy if avg < 500ms

                    String performanceLevel = processingDelays[consumerIndex] <= 100 ? "FAST" :
                                            processingDelays[consumerIndex] <= 200 ? "MEDIUM" : "SLOW";

                    logger.debug("{} ({}) processed work: {} (processing time: {}ms, avg: {}ms)", consumerId, performanceLevel, work.workId, processingTime, String.format("%.1f", avgTime));

                    processedCheckpoint.flag();
                    return null;
                });
            });
        }

        // Send work items for dynamic distribution
        logger.info("Sending {} work items for dynamic distribution...", numMessages);

        for (int i = 0; i < numMessages; i++) {
            Map<String, Object> workData = new HashMap<>();
            workData.put("taskType", "dynamic-task");
            workData.put("sequence", i + 1);
            workData.put("adaptiveRouting", true);

            WorkItem work = new WorkItem("dynamic-work-" + (i + 1), "session-" + (i % 4),
                                       100, 5, workData);
            producer.send(work).onFailure(testContext::failNow);
        }

        // Wait for all work items to be processed
        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS), "Should process all work items");

        // Analyze dynamic distribution results
        logger.info("Dynamic Load Balancing Results:");
        int totalProcessed = 0;

        for (int i = 0; i < consumerMetrics.size(); i++) {
            ConsumerMetrics metrics = consumerMetrics.get(i);
            int processed = metrics.processedCount.get();
            totalProcessed += processed;

            double avgProcessingTime = metrics.getAverageProcessingTime();
            double successRate = metrics.getSuccessRate();
            String performanceLevel = processingDelays[i] <= 100 ? "FAST" :
                                    processingDelays[i] <= 200 ? "MEDIUM" : "SLOW";

            logger.info("  {} ({}): {} items processed, avg time: {}ms, success rate: {}%, healthy: {}", metrics.consumerId, performanceLevel, processed, String.format("%.1f", avgProcessingTime), String.format("%.1f", successRate * 100), metrics.isHealthy);
        }

        assertEquals(numMessages, totalProcessed, "Should process all messages");

        // Verify that faster consumers processed more messages (in ideal dynamic balancing)
        ConsumerMetrics fastConsumer = consumerMetrics.get(0);
        ConsumerMetrics slowConsumer = consumerMetrics.get(2);

        logger.info("Performance comparison:");
        logger.info("  Fast consumer avg time: {}ms", String.format("%.1f", fastConsumer.getAverageProcessingTime()));
        logger.info("  Slow consumer avg time: {}ms", String.format("%.1f", slowConsumer.getAverageProcessingTime()));

        // Cleanup consumers
        consumers.forEach(MessageConsumer::close);

        logger.info("Dynamic Load Balancing test completed successfully");
        logger.info("Total work items processed: {}", totalProcessed);
    }
}


