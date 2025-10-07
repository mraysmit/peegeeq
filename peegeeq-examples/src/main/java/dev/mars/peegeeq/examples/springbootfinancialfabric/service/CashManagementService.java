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
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.CashMovementEvent;
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
 * Service for managing cash movement events.
 * 
 * Handles:
 * - Cash movements (debits and credits)
 * - Cash reconciliation
 * - Cash position tracking
 * 
 * All events are stored in the cash event store with full bi-temporal tracking.
 */
@Service
public class CashManagementService {
    
    private static final Logger log = LoggerFactory.getLogger(CashManagementService.class);

    private final PgBiTemporalEventStore<CashMovementEvent> cashEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public CashManagementService(
            @Qualifier("cashEventStore") PgBiTemporalEventStore<CashMovementEvent> cashEventStore,
            FinancialCloudEventBuilder cloudEventBuilder) {
        this.cashEventStore = cashEventStore;
        this.cloudEventBuilder = cloudEventBuilder;
    }
    
    /**
     * Record a cash movement.
     *
     * @param cashMovementEvent The cash movement event
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> recordCashMovement(
            CashMovementEvent cashMovementEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Recording cash movement: movementId={}, amount={}, currency={}, type={}",
            cashMovementEvent.getMovementId(), cashMovementEvent.getAmount(),
            cashMovementEvent.getCurrency(), cashMovementEvent.getMovementType());

        try {
            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.cash.movement.completed.v1",
                cashMovementEvent,
                correlationId,
                causationId,
                validTime
            );

            return cashEventStore.appendInTransaction(
                    "cash.movement.completed",
                    cashMovementEvent,  // Store the CashMovementEvent payload
                    validTime,
                    Map.of(
                        "correlationId", correlationId,
                        "currency", cashMovementEvent.getCurrency(),
                        "movementType", cashMovementEvent.getMovementType(),
                        "cloudEventId", cloudEvent.getId()
                    ),
                    correlationId,
                    cashMovementEvent.getMovementId(),
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Cash movement recorded successfully: movementId={}, eventId={}, cloudEventId={}",
                        cashMovementEvent.getMovementId(), biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cash movement: movementId={}", cashMovementEvent.getMovementId(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize cash movement", e));
        }
    }
}

