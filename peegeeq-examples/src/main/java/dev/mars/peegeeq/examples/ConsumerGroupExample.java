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

import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating consumer groups with message filtering and routing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class ConsumerGroupExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupExample.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Consumer Group Example ===");
        
        // Initialize PeeGeeQ Manager
        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"), 
                new SimpleMeterRegistry())) {
            
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Create database service and factory provider
            DatabaseService databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();
            
            // Create native queue factory
            QueueFactory nativeFactory = provider.createFactory("native", databaseService);
            
            // Create producer for sending test messages
            MessageProducer<OrderEvent> producer = nativeFactory.createProducer("order-events", OrderEvent.class);
            
            // Create consumer groups with different filtering strategies
            createOrderProcessingGroup(nativeFactory);
            createPaymentProcessingGroup(nativeFactory);
            createAnalyticsGroup(nativeFactory);
            
            // Send test messages with different routing headers
            sendTestMessages(producer);
            
            // Wait for message processing
            logger.info("Waiting for message processing...");
            Thread.sleep(10000);
            
            logger.info("Consumer Group Example completed successfully!");
        }
    }
    
    private static void createOrderProcessingGroup(QueueFactory factory) throws Exception {
        logger.info("Creating Order Processing consumer group...");
        
        ConsumerGroup<OrderEvent> orderGroup = factory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);
        
        // Add region-specific consumers
        orderGroup.addConsumer("US-Consumer", 
            createOrderHandler("US"), 
            MessageFilter.byRegion(Set.of("US")));
        
        orderGroup.addConsumer("EU-Consumer", 
            createOrderHandler("EU"), 
            MessageFilter.byRegion(Set.of("EU")));
        
        orderGroup.addConsumer("ASIA-Consumer", 
            createOrderHandler("ASIA"), 
            MessageFilter.byRegion(Set.of("ASIA")));
        
        orderGroup.start();
        logger.info("Order Processing group started with {} consumers", orderGroup.getActiveConsumerCount());
    }
    
    private static void createPaymentProcessingGroup(QueueFactory factory) throws Exception {
        logger.info("Creating Payment Processing consumer group...");
        
        ConsumerGroup<OrderEvent> paymentGroup = factory.createConsumerGroup(
            "PaymentProcessing", "order-events", OrderEvent.class);
        
        // Add priority-based consumers
        paymentGroup.addConsumer("HighPriority-Consumer", 
            createPaymentHandler("HIGH"), 
            MessageFilter.byPriority("HIGH"));
        
        paymentGroup.addConsumer("Normal-Consumer", 
            createPaymentHandler("NORMAL"), 
            MessageFilter.byPriority("NORMAL"));
        
        paymentGroup.start();
        logger.info("Payment Processing group started with {} consumers", paymentGroup.getActiveConsumerCount());
    }
    
    private static void createAnalyticsGroup(QueueFactory factory) throws Exception {
        logger.info("Creating Analytics consumer group...");
        
        ConsumerGroup<OrderEvent> analyticsGroup = factory.createConsumerGroup(
            "Analytics", "order-events", OrderEvent.class);
        
        // Add consumers for different message types
        analyticsGroup.addConsumer("Premium-Consumer", 
            createAnalyticsHandler("PREMIUM"), 
            MessageFilter.byType(Set.of("PREMIUM")));
        
        analyticsGroup.addConsumer("Standard-Consumer", 
            createAnalyticsHandler("STANDARD"), 
            MessageFilter.byType(Set.of("STANDARD")));
        
        // Add a consumer that accepts all messages for audit
        analyticsGroup.addConsumer("Audit-Consumer", 
            createAnalyticsHandler("ALL"), 
            MessageFilter.acceptAll());
        
        analyticsGroup.start();
        logger.info("Analytics group started with {} consumers", analyticsGroup.getActiveConsumerCount());
    }
    
    private static MessageHandler<OrderEvent> createOrderHandler(String region) {
        return message -> {
            OrderEvent event = message.getPayload();
            logger.info("[OrderProcessing-{}] Processing order: {} (amount: ${:.2f})", 
                region, event.getOrderId(), event.getAmount());
            
            // Simulate processing time
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return CompletableFuture.completedFuture(null);
        };
    }
    
    private static MessageHandler<OrderEvent> createPaymentHandler(String priority) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();
            
            logger.info("[PaymentProcessing-{}] Processing payment for order: {} (priority: {})", 
                priority, event.getOrderId(), headers.get("priority"));
            
            // High priority messages process faster
            int processingTime = "HIGH".equals(priority) ? 50 : 200;
            try {
                Thread.sleep(processingTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return CompletableFuture.completedFuture(null);
        };
    }
    
    private static MessageHandler<OrderEvent> createAnalyticsHandler(String type) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();
            
            logger.info("[Analytics-{}] Analyzing order: {} (type: {}, region: {})", 
                type, event.getOrderId(), headers.get("type"), headers.get("region"));
            
            // Analytics processing is typically fast
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return CompletableFuture.completedFuture(null);
        };
    }
    
    private static void sendTestMessages(MessageProducer<OrderEvent> producer) throws Exception {
        logger.info("Sending test messages with different routing headers...");
        
        String[] regions = {"US", "EU", "ASIA"};
        String[] priorities = {"HIGH", "NORMAL"};
        String[] types = {"PREMIUM", "STANDARD"};
        
        for (int i = 1; i <= 20; i++) {
            OrderEvent event = new OrderEvent(
                "ORDER-" + i,
                "CREATED",
                100.0 + (i * 10),
                "customer-" + i
            );
            
            // Create routing headers
            String region = regions[i % regions.length];
            String priority = priorities[i % priorities.length];
            String type = types[i % types.length];
            
            Map<String, String> headers = Map.of(
                "region", region,
                "priority", priority,
                "type", type,
                "source", "order-service",
                "version", "1.0"
            );
            
            producer.send(event, headers, "correlation-" + i, region + "-" + priority)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        logger.error("Failed to send message for order {}: {}", event.getOrderId(), error.getMessage());
                    } else {
                        logger.debug("Sent message for order {} with headers: {}", event.getOrderId(), headers);
                    }
                });
            
            // Small delay between messages
            Thread.sleep(100);
        }
        
        logger.info("Finished sending test messages");
    }
    
    /**
     * Simple order event class for testing.
     */
    public static class OrderEvent {
        private String orderId;
        private String status;
        private Double amount;
        private String customerId;
        
        public OrderEvent() {}
        
        public OrderEvent(String orderId, String status, Double amount, String customerId) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
            this.customerId = customerId;
        }
        
        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }
        
        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
    }
}
