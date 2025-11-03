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
 * Critical Trade Consumer Service (Pattern 2).
 * 
 * Demonstrates filtered consumer processing CRITICAL priority only.
 * Uses MessageFilter.byHeader() to filter for CRITICAL priority messages.
 * 
 * Key Features:
 * - Filters for CRITICAL priority only using MessageFilter.byHeader()
 * - Tracks metrics for processed and filtered messages
 * - Stores critical trades in database
 * - Updates consumer metrics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Service
@ConditionalOnProperty(prefix = "priority.consumers.critical", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CriticalTradeConsumerService {
    
    private static final Logger log = LoggerFactory.getLogger(CriticalTradeConsumerService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final MessageConsumer<TradeSettlementEvent> consumer;
    private final DatabaseService databaseService;
    private final String consumerInstanceId;
    
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong criticalProcessed = new AtomicLong(0);
    private final AtomicLong messagesFiltered = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    
    public CriticalTradeConsumerService(
            @Qualifier("criticalTradesConsumer") MessageConsumer<TradeSettlementEvent> consumer,
            DatabaseService databaseService,
            @Value("${priority.consumers.critical.instance-id:critical-consumer-1}") String consumerInstanceId) {
        this.consumer = consumer;
        this.databaseService = databaseService;
        this.consumerInstanceId = consumerInstanceId;
        
        log.info("CriticalTradeConsumerService created with instance ID: {}", consumerInstanceId);
    }
    
    /**
     * Start consuming messages when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        log.info("Starting critical-trades consumer: {}", consumerInstanceId);

        // Subscribe to messages (filtering done in handler)
        consumer.subscribe(this::processMessage);

        // Update consumer status in database
        updateConsumerStatus("RUNNING");

        log.info("Critical-trades consumer {} started successfully with filter: priority=CRITICAL", consumerInstanceId);
    }
    
    /**
     * Stop consuming messages when the application is shutting down.
     */
    @PreDestroy
    public void stopConsuming() {
        log.info("Stopping critical-trades consumer: {}", consumerInstanceId);
        
        try {
            // Update consumer status
            updateConsumerStatus("STOPPED");
            
            // Close consumer
            consumer.close();
            
            log.info("Critical-trades consumer {} stopped successfully. Total messages processed: {}, filtered: {}", 
                consumerInstanceId, messagesProcessed.get(), messagesFiltered.get());
        } catch (Exception e) {
            log.error("Error stopping critical-trades consumer", e);
        }
    }
    
    /**
     * Process a single message.
     *
     * Pattern 2: Only CRITICAL priority messages with immediate alerting.
     */
    private CompletableFuture<Void> processMessage(Message<TradeSettlementEvent> message) {
        TradeSettlementEvent event = message.getPayload();
        Priority priority = event.getPriority();

        // This should always be CRITICAL due to filter
        if (priority != Priority.CRITICAL) {
            log.warn("⚠️ [CRITICAL] Non-critical message received (filter bypass?): tradeId={}, priority={}",
                event.getTradeId(), priority);
            messagesFiltered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Critical-trades consumer received: tradeId={}, priority={}, status={}",
            event.getTradeId(), priority, event.getStatus());

        // STEP 1: IMMEDIATE ALERT - Page on-call team
        sendPagerAlert(event);

        // STEP 2: Create P1 incident ticket
        String incidentId = createP1Incident(event);

        // STEP 3: Update trade settlement table with FAIL status
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                return storeTrade(connection, event, incidentId)
                    .compose(result -> {
                        log.info("✅ [CRITICAL] Trade processed: tradeId={}, priority={}, status={}, incidentId={}, processedBy={}",
                            event.getTradeId(), priority, event.getStatus(), incidentId, consumerInstanceId);

                        // Update metrics
                        messagesProcessed.incrementAndGet();
                        criticalProcessed.incrementAndGet();

                        // Update consumer metrics
                        return updateConsumerMetrics(connection);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> {
                // STEP 4: Notify trading desk immediately
                notifyTradingDesk(event, incidentId);

                // STEP 5: Start investigation workflow
                startInvestigation(event, incidentId);

                return (Void) null;
            })
            .exceptionally(ex -> {
                log.error("❌ [CRITICAL] Failed to process message: tradeId={}, priority={}",
                    event.getTradeId(), priority, ex);
                messagesFailed.incrementAndGet();
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException("Message processing failed", ex);
            });
    }

    /**
     * Send pager alert to on-call team.
     */
    private void sendPagerAlert(TradeSettlementEvent event) {
        // In production: integrate with PagerDuty, OpsGenie, etc.
        log.error("🚨 PAGER ALERT: Settlement fail - tradeId={}, counterparty={}, amount={} {}, reason={}",
            event.getTradeId(), event.getCounterparty(), event.getAmount(), event.getCurrency(),
            event.getFailureReason());
    }

    /**
     * Create P1 incident ticket.
     */
    private String createP1Incident(TradeSettlementEvent event) {
        // In production: integrate with Jira, ServiceNow, etc.
        String incidentId = "INC-" + System.currentTimeMillis();
        log.error("🎫 P1 INCIDENT CREATED: {} - Settlement fail for trade {}: {}",
            incidentId, event.getTradeId(), event.getFailureReason());
        return incidentId;
    }

    /**
     * Notify trading desk immediately.
     */
    private void notifyTradingDesk(TradeSettlementEvent event, String incidentId) {
        // In production: send email, Slack message, etc.
        log.error("📧 TRADING DESK NOTIFIED: Settlement fail for trade {} - Incident: {} - Reason: {}",
            event.getTradeId(), incidentId, event.getFailureReason());
    }

    /**
     * Start investigation workflow.
     */
    private void startInvestigation(TradeSettlementEvent event, String incidentId) {
        // In production: trigger workflow in incident management system
        log.info("🔍 INVESTIGATION STARTED: Incident {} for trade {} - Assigned to on-call team",
            incidentId, event.getTradeId());
    }

    /**
     * Store trade in database.
     */
    private Future<Void> storeTrade(io.vertx.sqlclient.SqlConnection connection,
            TradeSettlementEvent event, String incidentId) {
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
        trade.setProcessedBy(consumerInstanceId + " [" + incidentId + "]");

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
                                "messages_filtered = EXCLUDED.messages_filtered, " +
                                "updated_at = EXCLUDED.updated_at, " +
                                "status = EXCLUDED.status";

                    return connection.preparedQuery(sql)
                        .execute(Tuple.of(
                            consumerInstanceId,
                            "CRITICAL_ONLY",
                            Priority.CRITICAL.getLevel(),
                            messagesProcessed.get(),
                            criticalProcessed.get(),
                            0L,  // No high processed
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
                    "messages_filtered = $3, " +
                    "last_message_at = $4, " +
                    "updated_at = $5 " +
                    "WHERE consumer_id = $6";
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                messagesProcessed.get(),
                criticalProcessed.get(),
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

