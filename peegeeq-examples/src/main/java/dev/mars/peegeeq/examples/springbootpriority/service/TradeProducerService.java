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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.springbootpriority.events.TradeSettlementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trade Producer Service.
 * 
 * Demonstrates the CORRECT way to send messages with priority headers
 * using PeeGeeQ's outbox pattern.
 * 
 * Key Features:
 * - Sets priority header for application-level filtering
 * - Sets additional metadata headers (trade-status, event-type)
 * - Tracks metrics for sent messages by priority
 * - Uses MessageProducer from QueueFactory
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Service
public class TradeProducerService {
    
    private static final Logger log = LoggerFactory.getLogger(TradeProducerService.class);
    
    private final MessageProducer<TradeSettlementEvent> producer;
    
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong criticalSent = new AtomicLong(0);
    private final AtomicLong highSent = new AtomicLong(0);
    private final AtomicLong normalSent = new AtomicLong(0);
    
    public TradeProducerService(MessageProducer<TradeSettlementEvent> tradeSettlementProducer) {
        this.producer = tradeSettlementProducer;
        log.info("TradeProducerService initialized");
    }
    
    /**
     * Send a trade settlement event with priority headers.
     *
     * This method demonstrates the CORRECT pattern for sending messages
     * with priority metadata:
     * 1. Create headers map
     * 2. Set priority header from event
     * 3. Set additional metadata headers
     * 4. Send message with headers
     * 5. Track metrics
     *
     * @param event Trade settlement event to send
     * @return CompletableFuture that completes when message is sent
     */
    public CompletableFuture<Void> sendTradeEvent(TradeSettlementEvent event) {
        // Create headers with priority and metadata
        Map<String, String> headers = new HashMap<>();
        headers.put("priority", event.getPriority().getLevel());
        headers.put("trade-status", event.getStatus());
        headers.put("event-type", "trade-settlement");
        headers.put("counterparty", event.getCounterparty());
        headers.put("timestamp", event.getTimestamp().toString());

        log.debug("Sending trade event: tradeId={}, priority={}, status={}",
            event.getTradeId(), event.getPriority(), event.getStatus());

        return producer.send(event, headers)
            .thenAccept(v -> {
                // Update metrics
                totalSent.incrementAndGet();
                switch (event.getPriority()) {
                    case CRITICAL -> criticalSent.incrementAndGet();
                    case HIGH -> highSent.incrementAndGet();
                    case NORMAL -> normalSent.incrementAndGet();
                }

                log.info("✅ Trade event sent: tradeId={}, priority={}, status={}",
                    event.getTradeId(), event.getPriority(), event.getStatus());
            })
            .exceptionally(ex -> {
                log.error("❌ Failed to send trade event: tradeId={}, priority={}",
                    event.getTradeId(), event.getPriority(), ex);
                throw new RuntimeException("Failed to send trade event", ex);
            });
    }

    /**
     * Send a settlement fail event (CRITICAL priority).
     *
     * @param tradeId Trade ID
     * @param counterparty Counterparty
     * @param amount Amount
     * @param currency Currency
     * @param settlementDate Settlement date
     * @param failureReason Reason for settlement failure
     * @return CompletableFuture that completes when message is sent
     */
    public CompletableFuture<Void> sendSettlementFail(String tradeId, String counterparty,
            java.math.BigDecimal amount, String currency, java.time.LocalDate settlementDate,
            String failureReason) {

        TradeSettlementEvent event = new TradeSettlementEvent(
            java.util.UUID.randomUUID().toString(),
            tradeId,
            counterparty,
            amount,
            currency,
            settlementDate,
            "FAIL",
            dev.mars.peegeeq.examples.springbootpriority.model.Priority.CRITICAL.getLevel(),
            failureReason,
            java.time.Instant.now()
        );

        return sendTradeEvent(event);
    }

    /**
     * Send a trade amendment event (HIGH priority).
     *
     * @param tradeId Trade ID
     * @param counterparty Counterparty
     * @param amount Amount
     * @param currency Currency
     * @param settlementDate Settlement date
     * @return CompletableFuture that completes when message is sent
     */
    public CompletableFuture<Void> sendAmendment(String tradeId, String counterparty,
            java.math.BigDecimal amount, String currency, java.time.LocalDate settlementDate) {

        TradeSettlementEvent event = new TradeSettlementEvent(
            java.util.UUID.randomUUID().toString(),
            tradeId,
            counterparty,
            amount,
            currency,
            settlementDate,
            "AMEND",
            dev.mars.peegeeq.examples.springbootpriority.model.Priority.HIGH.getLevel(),
            null,
            java.time.Instant.now()
        );

        return sendTradeEvent(event);
    }

    /**
     * Send a new trade confirmation event (NORMAL priority).
     *
     * @param tradeId Trade ID
     * @param counterparty Counterparty
     * @param amount Amount
     * @param currency Currency
     * @param settlementDate Settlement date
     * @return CompletableFuture that completes when message is sent
     */
    public CompletableFuture<Void> sendConfirmation(String tradeId, String counterparty,
            java.math.BigDecimal amount, String currency, java.time.LocalDate settlementDate) {

        TradeSettlementEvent event = new TradeSettlementEvent(
            java.util.UUID.randomUUID().toString(),
            tradeId,
            counterparty,
            amount,
            currency,
            settlementDate,
            "NEW",
            dev.mars.peegeeq.examples.springbootpriority.model.Priority.NORMAL.getLevel(),
            null,
            java.time.Instant.now()
        );

        return sendTradeEvent(event);
    }
    
    // Metrics getters
    
    public long getTotalSent() {
        return totalSent.get();
    }
    
    public long getCriticalSent() {
        return criticalSent.get();
    }
    
    public long getHighSent() {
        return highSent.get();
    }
    
    public long getNormalSent() {
        return normalSent.get();
    }
}

