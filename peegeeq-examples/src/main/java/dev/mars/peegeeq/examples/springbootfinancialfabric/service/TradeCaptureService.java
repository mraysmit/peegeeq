package dev.mars.peegeeq.examples.springbootfinancialfabric.service;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.cloudevents.FinancialCloudEventBuilder;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.TradeEvent;
import io.cloudevents.CloudEvent;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for capturing and managing trade lifecycle events.
 * 
 * Handles:
 * - Trade capture (initial trade entry)
 * - Trade confirmation (matching and confirmation)
 * - Trade amendments and corrections
 * 
 * All events are stored in the trading event store with full bi-temporal tracking.
 */
@Service
public class TradeCaptureService {
    
    private static final Logger log = LoggerFactory.getLogger(TradeCaptureService.class);

    private final PgBiTemporalEventStore<TradeEvent> tradingEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public TradeCaptureService(
            @Qualifier("tradingEventStore") PgBiTemporalEventStore<TradeEvent> tradingEventStore,
            FinancialCloudEventBuilder cloudEventBuilder) {
        this.tradingEventStore = tradingEventStore;
        this.cloudEventBuilder = cloudEventBuilder;
    }
    
    /**
     * Capture a new trade event.
     *
     * @param tradeEvent The trade event to capture
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> captureTrade(
            TradeEvent tradeEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Capturing trade: tradeId={}, instrument={}, quantity={}",
            tradeEvent.getTradeId(), tradeEvent.getInstrument(), tradeEvent.getQuantity());

        try {
            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.trading.equities.capture.completed.v1",
                tradeEvent,
                correlationId,
                causationId,
                validTime
            );

            // Store the payload (TradeEvent) in the BiTemporalEventStore, not the CloudEvent
            // The CloudEvent is used for messaging/external communication
            return tradingEventStore.appendInTransaction(
                    "trading.equities.capture.completed",
                    tradeEvent,  // Store the payload, not the CloudEvent
                    validTime,
                    Map.of("correlationId", correlationId, "priority", "normal", "cloudEventId", cloudEvent.getId()),
                    correlationId,
                    tradeEvent.getTradeId(),
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Trade captured successfully: tradeId={}, eventId={}, cloudEventId={}",
                        tradeEvent.getTradeId(), biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize trade event: tradeId={}", tradeEvent.getTradeId(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize trade event", e));
        }
    }
    
    /**
     * Confirm a trade (matching and confirmation).
     *
     * @param tradeId The trade ID to confirm
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> confirmTrade(
            String tradeId,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Confirming trade: tradeId={}", tradeId);

        try {
            // Create confirmation event - minimal TradeEvent with just the tradeId
            TradeEvent confirmationEvent = new TradeEvent(
                tradeId,
                null,  // instrument
                null,  // quantity
                null,  // price
                null,  // counterparty
                null,  // assetClass
                null,  // tradeType
                validTime  // tradeTime
            );

            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.trading.equities.confirmation.matched.v1",
                confirmationEvent,
                correlationId,
                causationId,
                validTime
            );

            return tradingEventStore.appendInTransaction(
                    "trading.equities.confirmation.matched",
                    confirmationEvent,  // Store the TradeEvent payload
                    validTime,
                    Map.of("correlationId", correlationId, "cloudEventId", cloudEvent.getId(), "status", "MATCHED"),
                    correlationId,
                    tradeId,
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Trade confirmed successfully: tradeId={}, eventId={}, cloudEventId={}",
                        tradeId, biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize trade confirmation: tradeId={}", tradeId, e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize trade confirmation", e));
        }
    }
}

