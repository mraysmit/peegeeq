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
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.SettlementInstructionEvent;
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
 * Service for managing settlement instruction lifecycle events.
 * 
 * Handles:
 * - Settlement instruction submission
 * - Settlement confirmation
 * - Settlement failures and retries
 * 
 * All events are stored in the settlement event store with full bi-temporal tracking.
 */
@Service
public class SettlementService {
    
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PgBiTemporalEventStore<SettlementInstructionEvent> settlementEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public SettlementService(
            @Qualifier("settlementEventStore") PgBiTemporalEventStore<SettlementInstructionEvent> settlementEventStore,
            FinancialCloudEventBuilder cloudEventBuilder) {
        this.settlementEventStore = settlementEventStore;
        this.cloudEventBuilder = cloudEventBuilder;
    }
    
    /**
     * Submit a settlement instruction.
     *
     * @param instructionEvent The settlement instruction event
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> submitSettlementInstruction(
            SettlementInstructionEvent instructionEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Submitting settlement instruction: instructionId={}, tradeId={}, custodian={}",
            instructionEvent.getInstructionId(), instructionEvent.getTradeId(), instructionEvent.getCustodian());

        try {
            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.instruction.settlement.submitted.v1",
                instructionEvent,
                correlationId,
                causationId,
                validTime
            );

            return settlementEventStore.appendInTransaction(
                    "instruction.settlement.submitted",
                    instructionEvent,  // Store the payload, not the CloudEvent
                    validTime,
                    Map.of("correlationId", correlationId, "custodian", instructionEvent.getCustodian(), "cloudEventId", cloudEvent.getId()),
                    correlationId,
                    instructionEvent.getInstructionId(),
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Settlement instruction submitted successfully: instructionId={}, eventId={}, cloudEventId={}",
                        instructionEvent.getInstructionId(), biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize settlement instruction: instructionId={}", instructionEvent.getInstructionId(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize settlement instruction", e));
        }
    }
    
    /**
     * Confirm a settlement instruction.
     *
     * @param instructionId The instruction ID to confirm
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> confirmSettlement(
            String instructionId,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Confirming settlement: instructionId={}", instructionId);

        try {
            // Create confirmation event - minimal SettlementInstructionEvent with just the instructionId
            SettlementInstructionEvent confirmationEvent = new SettlementInstructionEvent(
                instructionId,
                null,  // tradeId
                null,  // instrument
                null,  // quantity
                null,  // custodian
                null,  // settlementDate
                validTime  // instructionTime
            );

            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.instruction.settlement.confirmed.v1",
                confirmationEvent,
                correlationId,
                causationId,
                validTime
            );

            return settlementEventStore.appendInTransaction(
                    "instruction.settlement.confirmed",
                    confirmationEvent,  // Store the SettlementInstructionEvent payload
                    validTime,
                    Map.of("correlationId", correlationId, "cloudEventId", cloudEvent.getId(), "status", "CONFIRMED"),
                    correlationId,
                    instructionId,
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Settlement confirmed successfully: instructionId={}, eventId={}, cloudEventId={}",
                        instructionId, biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize settlement confirmation: instructionId={}", instructionId, e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize settlement confirmation", e));
        }
    }
}

