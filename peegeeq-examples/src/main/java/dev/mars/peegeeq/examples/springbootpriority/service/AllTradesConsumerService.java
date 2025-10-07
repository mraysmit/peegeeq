package dev.mars.peegeeq.examples.springbootpriority.service;

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
import dev.mars.peegeeq.examples.springbootpriority.events.TradeSettlementEvent;
import dev.mars.peegeeq.examples.springbootpriority.model.Priority;
import dev.mars.peegeeq.examples.springbootpriority.model.Trade;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All Trades Consumer Service (Pattern 1).
 * 
 * Demonstrates single consumer processing all priorities with metrics tracking.
 * This pattern processes ALL messages regardless of priority but tracks
 * metrics separately by priority level.
 * 
 * Key Features:
 * - No filtering - processes all messages
 * - Tracks metrics by priority (CRITICAL, HIGH, NORMAL)
 * - Stores trades in database
 * - Updates consumer metrics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Service
@ConditionalOnProperty(prefix = "priority.consumers.all", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AllTradesConsumerService {
    
    private static final Logger log = LoggerFactory.getLogger(AllTradesConsumerService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final MessageConsumer<TradeSettlementEvent> consumer;
    private final DatabaseService databaseService;
    private final String consumerInstanceId;
    
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong criticalProcessed = new AtomicLong(0);
    private final AtomicLong highProcessed = new AtomicLong(0);
    private final AtomicLong normalProcessed = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    
    public AllTradesConsumerService(
            @Qualifier("allTradesConsumer") MessageConsumer<TradeSettlementEvent> consumer,
            DatabaseService databaseService,
            @Value("${priority.consumers.all.instance-id:all-trades-consumer-1}") String consumerInstanceId) {
        this.consumer = consumer;
        this.databaseService = databaseService;
        this.consumerInstanceId = consumerInstanceId;
        
        log.info("AllTradesConsumerService created with instance ID: {}", consumerInstanceId);
    }
    
    /**
     * Start consuming messages when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        log.info("Starting all-trades consumer: {}", consumerInstanceId);
        
        // Subscribe to messages (no filter - accepts all)
        consumer.subscribe(this::processMessage);
        
        // Update consumer status in database
        updateConsumerStatus("RUNNING");
        
        log.info("All-trades consumer {} started successfully", consumerInstanceId);
    }
    
    /**
     * Stop consuming messages when the application is shutting down.
     */
    @PreDestroy
    public void stopConsuming() {
        log.info("Stopping all-trades consumer: {}", consumerInstanceId);
        
        try {
            // Update consumer status
            updateConsumerStatus("STOPPED");
            
            // Close consumer
            consumer.close();
            
            log.info("All-trades consumer {} stopped successfully. Total messages processed: {}", 
                consumerInstanceId, messagesProcessed.get());
        } catch (Exception e) {
            log.error("Error stopping all-trades consumer", e);
        }
    }
    
    /**
     * Process a single message.
     *
     * Pattern 1: Process all messages with SLA-based processing.
     */
    private CompletableFuture<Void> processMessage(Message<TradeSettlementEvent> message) {
        TradeSettlementEvent event = message.getPayload();
        Priority priority = event.getPriority();
        Instant messageArrival = Instant.parse(message.getHeaders().get("timestamp"));

        log.debug("All-trades consumer received: tradeId={}, priority={}, status={}",
            event.getTradeId(), priority, event.getStatus());

        // Process based on priority with different SLAs
        return switch (priority) {
            case CRITICAL -> processCritical(event, messageArrival);
            case HIGH -> processHigh(event, messageArrival);
            case NORMAL -> processNormal(event, messageArrival);
        };
    }

    /**
     * Process CRITICAL priority message (SLA: < 1 minute).
     */
    private CompletableFuture<Void> processCritical(TradeSettlementEvent event, Instant messageArrival) {
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                return storeTrade(connection, event)
                    .compose(v -> {
                        // Track SLA
                        Instant processingComplete = Instant.now();
                        trackSLA(event, messageArrival, processingComplete, 60);

                        // Update metrics
                        messagesProcessed.incrementAndGet();
                        criticalProcessed.incrementAndGet();

                        log.info("✅ [ALL-CRITICAL] Trade processed: tradeId={}, priority={}, status={}, processedBy={}",
                            event.getTradeId(), event.getPriority(), event.getStatus(), consumerInstanceId);

                        return updateConsumerMetrics(connection);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> (Void) null)
            .exceptionally(ex -> {
                log.error("❌ [ALL-CRITICAL] Failed to process message: tradeId={}", event.getTradeId(), ex);
                messagesFailed.incrementAndGet();
                throw new RuntimeException("Message processing failed", ex);
            });
    }

    /**
     * Process HIGH priority message (SLA: < 5 minutes).
     */
    private CompletableFuture<Void> processHigh(TradeSettlementEvent event, Instant messageArrival) {
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                return storeTrade(connection, event)
                    .compose(v -> {
                        // Track SLA
                        Instant processingComplete = Instant.now();
                        trackSLA(event, messageArrival, processingComplete, 300);

                        // Update metrics
                        messagesProcessed.incrementAndGet();
                        highProcessed.incrementAndGet();

                        log.info("✅ [ALL-HIGH] Trade processed: tradeId={}, priority={}, status={}, processedBy={}",
                            event.getTradeId(), event.getPriority(), event.getStatus(), consumerInstanceId);

                        return updateConsumerMetrics(connection);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> (Void) null)
            .exceptionally(ex -> {
                log.error("❌ [ALL-HIGH] Failed to process message: tradeId={}", event.getTradeId(), ex);
                messagesFailed.incrementAndGet();
                throw new RuntimeException("Message processing failed", ex);
            });
    }

    /**
     * Process NORMAL priority message (SLA: < 30 minutes).
     */
    private CompletableFuture<Void> processNormal(TradeSettlementEvent event, Instant messageArrival) {
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                return storeTrade(connection, event)
                    .compose(v -> {
                        // Track SLA
                        Instant processingComplete = Instant.now();
                        trackSLA(event, messageArrival, processingComplete, 1800);

                        // Update metrics
                        messagesProcessed.incrementAndGet();
                        normalProcessed.incrementAndGet();

                        log.info("✅ [ALL-NORMAL] Trade processed: tradeId={}, priority={}, status={}, processedBy={}",
                            event.getTradeId(), event.getPriority(), event.getStatus(), consumerInstanceId);

                        return updateConsumerMetrics(connection);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> (Void) null)
            .exceptionally(ex -> {
                log.error("❌ [ALL-NORMAL] Failed to process message: tradeId={}", event.getTradeId(), ex);
                messagesFailed.incrementAndGet();
                throw new RuntimeException("Message processing failed", ex);
            });
    }

    /**
     * Store trade in database.
     */
    private Future<Void> storeTrade(io.vertx.sqlclient.SqlConnection connection, TradeSettlementEvent event) {
        Trade trade = new Trade(
            event.getEventId(),
            event.getTradeId(),
            event.getCounterparty(),
            event.getAmount(),
            event.getCurrency(),
            event.getSettlementDate(),
            event.getStatus(),
            event.getPriority()
        );
        trade.setFailureReason(event.getFailureReason());
        trade.setCreatedAt(event.getTimestamp());
        trade.setProcessedAt(Instant.now());
        trade.setProcessedBy(consumerInstanceId);

        LocalDateTime createdAt = LocalDateTime.ofInstant(trade.getCreatedAt(), ZoneOffset.UTC);
        LocalDateTime processedAt = LocalDateTime.ofInstant(trade.getProcessedAt(), ZoneOffset.UTC);

        String sql = "INSERT INTO trade_settlements " +
                    "(id, trade_id, counterparty, amount, currency, settlement_date, status, priority, " +
                    "failure_reason, created_at, processed_at, processed_by) " +
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) " +
                    "ON CONFLICT (id) DO UPDATE SET " +
                    "status = EXCLUDED.status, " +
                    "processed_at = EXCLUDED.processed_at, " +
                    "processed_by = EXCLUDED.processed_by";

        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                trade.getId(),
                trade.getTradeId(),
                trade.getCounterparty(),
                trade.getAmount(),
                trade.getCurrency(),
                trade.getSettlementDate(),
                trade.getStatus(),
                trade.getPriority().getLevel(),
                trade.getFailureReason(),
                createdAt,
                processedAt,
                trade.getProcessedBy()
            ))
            .map(result -> null);
    }

    /**
     * Track SLA compliance.
     */
    private void trackSLA(TradeSettlementEvent event, Instant messageArrival,
            Instant processingComplete, long slaSeconds) {
        java.time.Duration processingTime = java.time.Duration.between(messageArrival, processingComplete);

        if (processingTime.toSeconds() > slaSeconds) {
            log.error("SLA BREACH: {} message took {} seconds (SLA: {} seconds) - tradeId={}",
                event.getPriority(), processingTime.toSeconds(), slaSeconds, event.getTradeId());
        } else {
            log.debug("SLA OK: {} message took {} seconds (SLA: {} seconds) - tradeId={}",
                event.getPriority(), processingTime.toSeconds(), slaSeconds, event.getTradeId());
        }
    }
    
    /**
     * Update consumer status in database.
     */
    private void updateConsumerStatus(String status) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            databaseService.getConnectionProvider()
                .withTransaction(CLIENT_ID, connection -> {
                    String sql = "INSERT INTO priority_consumer_metrics " +
                                "(consumer_id, consumer_type, priority_filter, messages_processed, " +
                                "critical_processed, high_processed, normal_processed, messages_filtered, " +
                                "started_at, updated_at, status) " +
                                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) " +
                                "ON CONFLICT (consumer_id) DO UPDATE SET " +
                                "messages_processed = EXCLUDED.messages_processed, " +
                                "critical_processed = EXCLUDED.critical_processed, " +
                                "high_processed = EXCLUDED.high_processed, " +
                                "normal_processed = EXCLUDED.normal_processed, " +
                                "updated_at = EXCLUDED.updated_at, " +
                                "status = EXCLUDED.status";

                    return connection.preparedQuery(sql)
                        .execute(Tuple.of(
                            consumerInstanceId,
                            "ALL_TRADES",
                            null,  // No filter
                            messagesProcessed.get(),
                            criticalProcessed.get(),
                            highProcessed.get(),
                            normalProcessed.get(),
                            0L,  // No filtering
                            now,
                            now,
                            status
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
        String sql = "UPDATE priority_consumer_metrics SET " +
                    "messages_processed = $1, " +
                    "critical_processed = $2, " +
                    "high_processed = $3, " +
                    "normal_processed = $4, " +
                    "last_message_at = $5, " +
                    "updated_at = $6 " +
                    "WHERE consumer_id = $7";
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                messagesProcessed.get(),
                criticalProcessed.get(),
                highProcessed.get(),
                normalProcessed.get(),
                now,
                now,
                consumerInstanceId
            ))
            .map(result -> null);
    }
    
    // Metrics getters
    
    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }
    
    public long getCriticalProcessed() {
        return criticalProcessed.get();
    }
    
    public long getHighProcessed() {
        return highProcessed.get();
    }
    
    public long getNormalProcessed() {
        return normalProcessed.get();
    }
    
    public long getMessagesFailed() {
        return messagesFailed.get();
    }
    
    public String getConsumerInstanceId() {
        return consumerInstanceId;
    }
}

