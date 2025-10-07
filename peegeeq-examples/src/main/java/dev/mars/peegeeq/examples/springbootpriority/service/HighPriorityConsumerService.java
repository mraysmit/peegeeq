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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High Priority Consumer Service (Pattern 2).
 * 
 * Demonstrates filtered consumer processing HIGH and CRITICAL priorities.
 * Uses MessageFilter.byHeaderIn() to filter for HIGH and CRITICAL priority messages.
 * 
 * Key Features:
 * - Filters for HIGH and CRITICAL priorities using MessageFilter.byHeaderIn()
 * - Tracks metrics for processed and filtered messages by priority
 * - Stores high-priority trades in database
 * - Updates consumer metrics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Service
@ConditionalOnProperty(prefix = "priority.consumers.high", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HighPriorityConsumerService {
    
    private static final Logger log = LoggerFactory.getLogger(HighPriorityConsumerService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final MessageConsumer<TradeSettlementEvent> consumer;
    private final DatabaseService databaseService;
    private final String consumerInstanceId;
    
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong criticalProcessed = new AtomicLong(0);
    private final AtomicLong highProcessed = new AtomicLong(0);
    private final AtomicLong messagesFiltered = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    
    public HighPriorityConsumerService(
            @Qualifier("highPriorityTradesConsumer") MessageConsumer<TradeSettlementEvent> consumer,
            DatabaseService databaseService,
            @Value("${priority.consumers.high.instance-id:high-priority-consumer-1}") String consumerInstanceId) {
        this.consumer = consumer;
        this.databaseService = databaseService;
        this.consumerInstanceId = consumerInstanceId;
        
        log.info("HighPriorityConsumerService created with instance ID: {}", consumerInstanceId);
    }
    
    /**
     * Start consuming messages when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        log.info("Starting high-priority consumer: {}", consumerInstanceId);

        // Subscribe to messages (filtering done in handler)
        consumer.subscribe(this::processMessage);

        // Update consumer status in database
        updateConsumerStatus("RUNNING");

        log.info("High-priority consumer {} started successfully with filter: priority IN (HIGH, CRITICAL)", consumerInstanceId);
    }
    
    /**
     * Stop consuming messages when the application is shutting down.
     */
    @PreDestroy
    public void stopConsuming() {
        log.info("Stopping high-priority consumer: {}", consumerInstanceId);
        
        try {
            // Update consumer status
            updateConsumerStatus("STOPPED");
            
            // Close consumer
            consumer.close();
            
            log.info("High-priority consumer {} stopped successfully. Total messages processed: {}, filtered: {}", 
                consumerInstanceId, messagesProcessed.get(), messagesFiltered.get());
        } catch (Exception e) {
            log.error("Error stopping high-priority consumer", e);
        }
    }
    
    /**
     * Process a single message.
     *
     * Pattern 2: Only HIGH and CRITICAL priority messages with cutoff checking.
     */
    private CompletableFuture<Void> processMessage(Message<TradeSettlementEvent> message) {
        TradeSettlementEvent event = message.getPayload();
        Priority priority = event.getPriority();

        // This should only be HIGH or CRITICAL due to filter
        if (priority != Priority.HIGH && priority != Priority.CRITICAL) {
            log.warn("⚠️ [HIGH+] Non-high-priority message received (filter bypass?): tradeId={}, priority={}",
                event.getTradeId(), priority);
            messagesFiltered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        log.debug("High-priority consumer received: tradeId={}, priority={}, status={}",
            event.getTradeId(), priority, event.getStatus());

        // Check settlement cutoff for HIGH priority
        if (priority == Priority.HIGH) {
            checkSettlementCutoff(event);
        }

        // Process message in transaction
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                return storeTrade(connection, event)
                    .compose(result -> {
                        log.info("✅ [HIGH+] Trade processed: tradeId={}, priority={}, status={}, processedBy={}",
                            event.getTradeId(), priority, event.getStatus(), consumerInstanceId);

                        // Update metrics by priority
                        messagesProcessed.incrementAndGet();
                        if (priority == Priority.CRITICAL) {
                            criticalProcessed.incrementAndGet();
                        } else if (priority == Priority.HIGH) {
                            highProcessed.incrementAndGet();
                        }

                        // Update consumer metrics
                        return updateConsumerMetrics(connection);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> {
                // Alert if approaching cutoff
                if (priority == Priority.HIGH) {
                    alertIfApproachingCutoff(event);
                }
                return (Void) null;
            })
            .exceptionally(ex -> {
                log.error("❌ [HIGH+] Failed to process message: tradeId={}, priority={}",
                    event.getTradeId(), priority, ex);
                messagesFailed.incrementAndGet();
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException("Message processing failed", ex);
            });
    }

    /**
     * Check if settlement date is approaching cutoff.
     */
    private void checkSettlementCutoff(TradeSettlementEvent event) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate settlementDate = event.getSettlementDate();

        long daysUntilSettlement = java.time.temporal.ChronoUnit.DAYS.between(today, settlementDate);

        if (daysUntilSettlement <= 0) {
            log.warn("⚠️ SETTLEMENT CUTOFF: Trade {} settles TODAY - Immediate processing required",
                event.getTradeId());
        } else if (daysUntilSettlement == 1) {
            log.warn("⚠️ SETTLEMENT CUTOFF: Trade {} settles TOMORROW - Priority processing required",
                event.getTradeId());
        }
    }

    /**
     * Alert if approaching settlement cutoff.
     */
    private void alertIfApproachingCutoff(TradeSettlementEvent event) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate settlementDate = event.getSettlementDate();

        long daysUntilSettlement = java.time.temporal.ChronoUnit.DAYS.between(today, settlementDate);

        if (daysUntilSettlement <= 1) {
            // In production: send alert to operations team
            log.error("🔔 CUTOFF ALERT: Trade {} processed with {} days until settlement - Monitor closely",
                event.getTradeId(), daysUntilSettlement);
        }
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
                                "messages_filtered = EXCLUDED.messages_filtered, " +
                                "updated_at = EXCLUDED.updated_at, " +
                                "status = EXCLUDED.status";

                    return connection.preparedQuery(sql)
                        .execute(Tuple.of(
                            consumerInstanceId,
                            "HIGH_PRIORITY",
                            "HIGH,CRITICAL",
                            messagesProcessed.get(),
                            criticalProcessed.get(),
                            highProcessed.get(),
                            0L,  // No normal processed
                            messagesFiltered.get(),
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
                    "messages_filtered = $4, " +
                    "last_message_at = $5, " +
                    "updated_at = $6 " +
                    "WHERE consumer_id = $7";
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                messagesProcessed.get(),
                criticalProcessed.get(),
                highProcessed.get(),
                messagesFiltered.get(),
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

