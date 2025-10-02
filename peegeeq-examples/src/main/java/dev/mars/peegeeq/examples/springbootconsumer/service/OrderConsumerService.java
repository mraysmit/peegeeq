package dev.mars.peegeeq.examples.springbootconsumer.service;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.examples.springbootconsumer.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootconsumer.model.Order;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Order Consumer Service.
 * 
 * Demonstrates the CORRECT way to consume messages from PeeGeeQ outbox queues:
 * - Uses MessageConsumer from QueueFactory
 * - Processes messages with proper acknowledgment
 * - Stores processed orders in database using DatabaseService
 * - Supports message filtering based on headers
 * - Tracks consumer metrics
 * 
 * Key Principles:
 * - Uses DatabaseService for database operations
 * - Uses ConnectionProvider.withTransaction() for transactional processing
 * - Proper lifecycle management with @PostConstruct and @PreDestroy
 * - Thread-safe message processing
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@Service
public class OrderConsumerService {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumerService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final MessageConsumer<OrderEvent> consumer;
    private final DatabaseService databaseService;
    private final String consumerInstanceId;
    private final boolean filteringEnabled;
    private final List<String> allowedStatuses;
    
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesFiltered = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    
    public OrderConsumerService(
            MessageConsumer<OrderEvent> consumer,
            DatabaseService databaseService,
            @Value("${consumer.instance-id:consumer-1}") String consumerInstanceId,
            @Value("${consumer.filtering.enabled:false}") boolean filteringEnabled,
            @Value("${consumer.filtering.allowed-statuses:}") String allowedStatusesStr) {
        this.consumer = consumer;
        this.databaseService = databaseService;
        this.consumerInstanceId = consumerInstanceId;
        this.filteringEnabled = filteringEnabled;
        this.allowedStatuses = allowedStatusesStr.isEmpty() ? 
            List.of() : Arrays.asList(allowedStatusesStr.split(","));
        
        log.info("OrderConsumerService created with instance ID: {}", consumerInstanceId);
        if (filteringEnabled) {
            log.info("Message filtering enabled. Allowed statuses: {}", allowedStatuses);
        }
    }
    
    /**
     * Start consuming messages when the application is ready.
     * Uses ApplicationReadyEvent to ensure schema is initialized first.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        log.info("Starting message consumption for consumer: {}", consumerInstanceId);
        
        // Subscribe to messages
        consumer.subscribe(this::processMessage);
        
        // Update consumer status in database
        updateConsumerStatus("RUNNING");
        
        log.info("Consumer {} started successfully", consumerInstanceId);
    }
    
    /**
     * Stop consuming messages when the application is shutting down.
     */
    @PreDestroy
    public void stopConsuming() {
        log.info("Stopping message consumption for consumer: {}", consumerInstanceId);
        
        try {
            // Update consumer status
            updateConsumerStatus("STOPPED");
            
            // Close consumer
            consumer.close();
            
            log.info("Consumer {} stopped successfully. Total messages processed: {}", 
                consumerInstanceId, messagesProcessed.get());
        } catch (Exception e) {
            log.error("Error stopping consumer", e);
        }
    }
    
    /**
     * Process a single message.
     * 
     * This method demonstrates the CORRECT pattern for message processing:
     * 1. Check if message should be filtered
     * 2. Use ConnectionProvider.withTransaction() for transactional processing
     * 3. Store order in database
     * 4. Update consumer metrics
     * 5. Return CompletableFuture for acknowledgment
     */
    private CompletableFuture<Void> processMessage(Message<OrderEvent> message) {
        OrderEvent event = message.getPayload();
        Map<String, String> headers = message.getHeaders();
        
        log.debug("Received message: orderId={}, status={}, headers={}", 
            event.getOrderId(), event.getStatus(), headers);
        
        // Apply filtering if enabled
        if (filteringEnabled && !shouldProcessMessage(event)) {
            log.debug("Message filtered out: orderId={}, status={}", 
                event.getOrderId(), event.getStatus());
            messagesFiltered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        
        // Process message in transaction
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                // Create order from event
                Order order = new Order(
                    event.getOrderId(),
                    event.getCustomerId(),
                    event.getAmount(),
                    event.getStatus()
                );
                order.setProcessedAt(Instant.now());
                order.setProcessedBy(consumerInstanceId);

                // Convert Instant to LocalDateTime for PostgreSQL TIMESTAMP
                LocalDateTime createdAt = LocalDateTime.ofInstant(order.getCreatedAt(), ZoneOffset.UTC);
                LocalDateTime processedAt = LocalDateTime.ofInstant(order.getProcessedAt(), ZoneOffset.UTC);

                // Insert order into database
                String sql = "INSERT INTO orders (id, customer_id, amount, status, created_at, processed_at, processed_by) " +
                            "VALUES ($1, $2, $3, $4, $5, $6, $7) " +
                            "ON CONFLICT (id) DO UPDATE SET " +
                            "status = EXCLUDED.status, " +
                            "processed_at = EXCLUDED.processed_at, " +
                            "processed_by = EXCLUDED.processed_by";

                return connection.preparedQuery(sql)
                    .execute(Tuple.of(
                        order.getId(),
                        order.getCustomerId(),
                        order.getAmount(),
                        order.getStatus(),
                        createdAt,
                        processedAt,
                        order.getProcessedBy()
                    ))
                    .compose(result -> {
                        log.info("✅ Order processed successfully: orderId={}, status={}, processedBy={}", 
                            event.getOrderId(), event.getStatus(), consumerInstanceId);
                        
                        messagesProcessed.incrementAndGet();
                        
                        // Update consumer metrics
                        return updateConsumerMetrics(connection);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> (Void) null)
            .exceptionally(ex -> {
                log.error("❌ Failed to process message: orderId={}", event.getOrderId(), ex);
                messagesFailed.incrementAndGet();
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException("Message processing failed", ex);
            });
    }
    
    /**
     * Check if message should be processed based on filtering rules.
     */
    private boolean shouldProcessMessage(OrderEvent event) {
        if (!filteringEnabled || allowedStatuses.isEmpty()) {
            return true;
        }
        return allowedStatuses.contains(event.getStatus());
    }
    
    /**
     * Update consumer status in database.
     */
    private void updateConsumerStatus(String status) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            databaseService.getConnectionProvider()
                .withTransaction(CLIENT_ID, connection -> {
                    String sql = "INSERT INTO consumer_status (consumer_id, status, messages_processed, started_at, updated_at) " +
                                "VALUES ($1, $2, $3, $4, $5) " +
                                "ON CONFLICT (consumer_id) DO UPDATE SET " +
                                "status = EXCLUDED.status, " +
                                "messages_processed = EXCLUDED.messages_processed, " +
                                "updated_at = EXCLUDED.updated_at";

                    return connection.preparedQuery(sql)
                        .execute(Tuple.of(
                            consumerInstanceId,
                            status,
                            messagesProcessed.get(),
                            now,
                            now
                        ))
                        .map(result -> null);
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        } catch (Exception e) {
            log.warn("Failed to update consumer status", e);
        }
    }
    
    /**
     * Update consumer metrics in database (within existing transaction).
     */
    private Future<Void> updateConsumerMetrics(io.vertx.sqlclient.SqlConnection connection) {
        String sql = "UPDATE consumer_status SET " +
                    "messages_processed = $1, " +
                    "last_message_at = $2, " +
                    "updated_at = $3 " +
                    "WHERE consumer_id = $4";
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                messagesProcessed.get(),
                now,
                now,
                consumerInstanceId
            ))
            .map(result -> null);
    }
    
    // Metrics getters for monitoring
    
    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }
    
    public long getMessagesFiltered() {
        return messagesFiltered.get();
    }
    
    public long getMessagesFailed() {
        return messagesFailed.get();
    }
    
    public String getConsumerInstanceId() {
        return consumerInstanceId;
    }
}

