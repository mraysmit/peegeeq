package dev.mars.peegeeq.examples.springboot.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 Spring Boot Integration Tests - Message Ordering
 * 
 * Tests message ordering guarantees in the outbox pattern:
 * - FIFO ordering by created_at timestamp
 * - Message group ordering (messages with same message_group)
 * - Concurrent processing of different message groups
 * 
 * Note: Current implementation orders by created_at ASC (FIFO).
 * Priority-based ordering would require modifying the SQL ORDER BY clause.
 */
@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
public class OutboxMessageOrderingSpringBootTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxMessageOrderingSpringBootTest.class);
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxMessageOrdering test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @Autowired
    private OutboxFactory outboxFactory;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();
    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        logger.info("🚀 Setting up Message Ordering Spring Boot Test");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        logger.info("🧹 Cleaning up Message Ordering Spring Boot Test");
        
        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            try {
                consumer.close();
                logger.info("✅ Closed consumer");
            } catch (Exception e) {
                logger.error("⚠️ Error closing consumer: {}", e.getMessage());
            }
        }
        activeConsumers.clear();
        
        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
                logger.info("✅ Closed producer");
            } catch (Exception e) {
                logger.error("⚠️ Error closing producer: {}", e.getMessage());
            }
        }
        activeProducers.clear();
        
        // Wait for connections to be fully released before next test
        logger.info("⏳ Waiting for connections to be released...");
        Thread.sleep(2000);
        
        logger.info("✅ Cleanup complete");
    }

    /**
     * Test 1: FIFO Message Ordering
     * 
     * Verifies that messages are processed in the order they were created (FIFO).
     * The outbox consumer orders by created_at ASC, ensuring first-in-first-out processing.
     */
    @Test
    void testFIFOMessageOrdering() throws Exception {
        logger.info("=== Testing FIFO Message Ordering ===");
        logger.info("This test verifies messages are processed in creation order (FIFO)");

        String topic = "ordering-fifo-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Track processing order
        List<Integer> processedOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(10);
        
        // Create consumer
        MessageConsumer<OrderMessage> consumer = outboxFactory.createConsumer(topic, OrderMessage.class);
        activeConsumers.add(consumer);
        
        consumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            processedOrder.add(order.getSequence());
            logger.info("📦 Processed message sequence: {}", order.getSequence());
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        
        // Create producer
        MessageProducer<OrderMessage> producer = outboxFactory.createProducer(topic, OrderMessage.class);
        activeProducers.add(producer);
        
        // Send messages in sequence with small delays to ensure different created_at timestamps
        logger.info("📤 Sending 10 messages in sequence");
        for (int i = 1; i <= 10; i++) {
            OrderMessage message = new OrderMessage("order-" + i, i, "Item " + i);
            producer.send(message).join();
            Thread.sleep(10); // Small delay to ensure different timestamps
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All messages should be processed within 15 seconds");
        
        // Verify FIFO ordering
        logger.info("📊 FIFO Ordering Results:");
        logger.info("   Expected order: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]");
        logger.info("   Actual order:   {}", processedOrder);
        
        assertEquals(10, processedOrder.size(), "Should have processed 10 messages");
        
        // Verify messages were processed in order
        for (int i = 0; i < processedOrder.size(); i++) {
            assertEquals(i + 1, processedOrder.get(i), 
                "Message at position " + i + " should have sequence " + (i + 1));
        }
        
        logger.info("✅ FIFO Message Ordering test passed");
        logger.info("✅ Messages processed in correct FIFO order");
    }

    /**
     * Test 2: Message Group Ordering
     * 
     * Verifies that messages within the same message_group maintain FIFO ordering.
     * Messages are sent with message_group parameter to test group-based ordering.
     */
    @Test
    void testMessageGroupOrdering() throws Exception {
        logger.info("=== Testing Message Group Ordering ===");
        logger.info("This test verifies messages within same group maintain FIFO order");

        String topic = "ordering-group-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Track processing order per group
        Map<String, List<Integer>> groupOrders = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(15);
        
        // Create consumer
        MessageConsumer<OrderMessage> consumer = outboxFactory.createConsumer(topic, OrderMessage.class);
        activeConsumers.add(consumer);
        
        consumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            String group = order.getCustomerId();
            groupOrders.computeIfAbsent(group, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(order.getSequence());
            logger.info("📦 Processed message sequence: {} for group: {}", order.getSequence(), group);
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        
        // Create producer
        MessageProducer<OrderMessage> producer = outboxFactory.createProducer(topic, OrderMessage.class);
        activeProducers.add(producer);
        
        // Send messages for 3 different groups (customer IDs)
        logger.info("📤 Sending messages for 3 different customer groups");
        String[] customers = {"customer-A", "customer-B", "customer-C"};
        int sequence = 1;
        
        for (int round = 1; round <= 5; round++) {
            for (String customer : customers) {
                OrderMessage message = new OrderMessage("order-" + sequence, sequence, "Item " + sequence);
                message.setCustomerId(customer);
                // Send with message_group parameter
                producer.send(message, null, null, customer).join();
                logger.info("   Sent sequence {} for {}", sequence, customer);
                sequence++;
                Thread.sleep(10); // Small delay to ensure different timestamps
            }
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(20, TimeUnit.SECONDS), "All messages should be processed within 20 seconds");
        
        // Verify ordering within each group
        logger.info("📊 Message Group Ordering Results:");
        for (String customer : customers) {
            List<Integer> order = groupOrders.get(customer);
            logger.info("   Group {}: {}", customer, order);
            
            assertNotNull(order, "Group " + customer + " should have messages");
            assertEquals(5, order.size(), "Group " + customer + " should have 5 messages");
            
            // Verify messages within group are in order
            for (int i = 1; i < order.size(); i++) {
                assertTrue(order.get(i) > order.get(i - 1), 
                    "Messages in group " + customer + " should be in ascending order");
            }
        }
        
        logger.info("✅ Message Group Ordering test passed");
        logger.info("✅ Messages within each group maintained FIFO order");
    }

    /**
     * Test 3: Concurrent Processing of Different Message Groups
     * 
     * Verifies that messages from different groups can be processed concurrently
     * without strict ordering guarantees across groups (only within groups).
     */
    @Test
    void testConcurrentGroupProcessing() throws Exception {
        logger.info("=== Testing Concurrent Processing of Different Message Groups ===");
        logger.info("This test verifies different groups can be processed concurrently");

        String topic = "ordering-concurrent-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Track processing
        AtomicInteger totalProcessed = new AtomicInteger(0);
        Map<String, AtomicInteger> groupCounts = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(20);
        
        // Create consumer
        MessageConsumer<OrderMessage> consumer = outboxFactory.createConsumer(topic, OrderMessage.class);
        activeConsumers.add(consumer);
        
        consumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            String group = order.getCustomerId();
            groupCounts.computeIfAbsent(group, k -> new AtomicInteger(0)).incrementAndGet();
            totalProcessed.incrementAndGet();
            logger.info("📦 Processed message for group: {} (total: {})", group, totalProcessed.get());
            latch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        
        // Create producer
        MessageProducer<OrderMessage> producer = outboxFactory.createProducer(topic, OrderMessage.class);
        activeProducers.add(producer);
        
        // Send messages for 4 different groups rapidly
        logger.info("📤 Sending 20 messages across 4 groups rapidly");
        String[] groups = {"group-1", "group-2", "group-3", "group-4"};
        
        for (int i = 1; i <= 20; i++) {
            String group = groups[i % groups.length];
            OrderMessage message = new OrderMessage("order-" + i, i, "Item " + i);
            message.setCustomerId(group);
            producer.send(message, null, null, group).join();
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(20, TimeUnit.SECONDS), "All messages should be processed within 20 seconds");
        
        // Verify all messages were processed
        logger.info("📊 Concurrent Processing Results:");
        logger.info("   Total messages processed: {}", totalProcessed.get());
        assertEquals(20, totalProcessed.get(), "Should have processed 20 messages");
        
        // Verify each group got its messages
        for (String group : groups) {
            int count = groupCounts.get(group).get();
            logger.info("   Group {}: {} messages", group, count);
            assertEquals(5, count, "Each group should have processed 5 messages");
        }
        
        logger.info("✅ Concurrent Group Processing test passed");
        logger.info("✅ All groups processed their messages successfully");
    }

    /**
     * Simple order message for testing
     */
    public static class OrderMessage {
        private String orderId;
        private int sequence;
        private String item;
        private String customerId;

        public OrderMessage() {}

        public OrderMessage(String orderId, int sequence, String item) {
            this.orderId = orderId;
            this.sequence = sequence;
            this.item = item;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        
        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }
        
        public String getItem() { return item; }
        public void setItem(String item) { this.item = item; }
        
        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
    }
}

