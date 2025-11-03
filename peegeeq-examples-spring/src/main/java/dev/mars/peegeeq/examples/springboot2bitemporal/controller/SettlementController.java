package dev.mars.peegeeq.examples.springboot2bitemporal.controller;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.examples.springboot2bitemporal.events.SettlementEvent;
import dev.mars.peegeeq.examples.springboot2bitemporal.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * REST controller for settlement instruction operations.
 * 
 * <p>This controller demonstrates reactive endpoints using Spring WebFlux (Mono/Flux)
 * integrated with PeeGeeQ's bi-temporal event store.
 * 
 * <p><b>Event Naming Pattern:</b> {entity}.{action}.{state}
 * 
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>POST /api/settlements/submit - Submit settlement instruction</li>
 *   <li>POST /api/settlements/match - Record settlement matched</li>
 *   <li>POST /api/settlements/confirm - Record settlement confirmed</li>
 *   <li>POST /api/settlements/fail - Record settlement failed</li>
 *   <li>POST /api/settlements/correct - Correct settlement data</li>
 *   <li>GET /api/settlements/history/{instructionId} - Get settlement history</li>
 *   <li>GET /api/settlements/state-at/{instructionId} - State at specific time</li>
 *   <li>GET /api/settlements/corrections/{instructionId} - View corrections</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {
    
    private static final Logger logger = LoggerFactory.getLogger(SettlementController.class);
    
    private final SettlementService settlementService;
    
    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }
    
    /**
     * Submits a settlement instruction.
     * 
     * <p>Event: instruction.settlement.submitted
     * 
     * <p>Example:
     * <pre>
     * POST /api/settlements/submit
     * {
     *   "instructionId": "SSI-12345",
     *   "tradeId": "TRD-67890",
     *   "counterparty": "CUSTODIAN-XYZ",
     *   "amount": "1000000.00",
     *   "currency": "USD",
     *   "settlementDate": "2025-10-10",
     *   "status": "SUBMITTED",
     *   "failureReason": null,
     *   "eventTime": "2025-10-07T10:00:00Z"
     * }
     * </pre>
     * 
     * @param event Settlement event data
     * @return Mono with the recorded event
     */
    @PostMapping("/submit")
    public Mono<ResponseEntity<BiTemporalEvent<SettlementEvent>>> submitSettlement(
            @RequestBody SettlementEvent event) {
        
        logger.info("REST: Submitting settlement instruction: {}", event.getInstructionId());
        
        return settlementService.recordSettlement("instruction.settlement.submitted", event)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                logger.error("REST: Failed to submit settlement instruction", error);
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * Records settlement matched with counterparty.
     * 
     * <p>Event: instruction.settlement.matched
     * 
     * @param event Settlement event data
     * @return Mono with the recorded event
     */
    @PostMapping("/match")
    public Mono<ResponseEntity<BiTemporalEvent<SettlementEvent>>> matchSettlement(
            @RequestBody SettlementEvent event) {
        
        logger.info("REST: Recording settlement matched: {}", event.getInstructionId());
        
        return settlementService.recordSettlement("instruction.settlement.matched", event)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                logger.error("REST: Failed to record settlement matched", error);
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * Records settlement confirmed by custodian.
     * 
     * <p>Event: instruction.settlement.confirmed
     * 
     * @param event Settlement event data
     * @return Mono with the recorded event
     */
    @PostMapping("/confirm")
    public Mono<ResponseEntity<BiTemporalEvent<SettlementEvent>>> confirmSettlement(
            @RequestBody SettlementEvent event) {
        
        logger.info("REST: Recording settlement confirmed: {}", event.getInstructionId());
        
        return settlementService.recordSettlement("instruction.settlement.confirmed", event)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                logger.error("REST: Failed to record settlement confirmed", error);
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * Records settlement failed.
     * 
     * <p>Event: instruction.settlement.failed
     * 
     * @param event Settlement event data (with failureReason)
     * @return Mono with the recorded event
     */
    @PostMapping("/fail")
    public Mono<ResponseEntity<BiTemporalEvent<SettlementEvent>>> failSettlement(
            @RequestBody SettlementEvent event) {
        
        logger.info("REST: Recording settlement failed: {} reason: {}", 
            event.getInstructionId(), event.getFailureReason());
        
        return settlementService.recordSettlement("instruction.settlement.failed", event)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                logger.error("REST: Failed to record settlement failed", error);
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * Corrects settlement instruction data.
     * 
     * <p>Event: instruction.settlement.corrected
     * 
     * <p>This creates a new version of the settlement event with corrected data,
     * preserving the complete audit trail.
     * 
     * @param event Corrected settlement event data
     * @return Mono with the correction event
     */
    @PostMapping("/correct")
    public Mono<ResponseEntity<BiTemporalEvent<SettlementEvent>>> correctSettlement(
            @RequestBody SettlementEvent event) {
        
        logger.info("REST: Correcting settlement instruction: {}", event.getInstructionId());
        
        return settlementService.correctSettlement(event.getInstructionId(), event)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                logger.error("REST: Failed to correct settlement instruction", error);
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * Retrieves settlement history for an instruction.
     * 
     * <p>Returns all events for the settlement instruction in chronological order.
     * 
     * <p>Example:
     * <pre>
     * GET /api/settlements/history/SSI-12345
     * </pre>
     * 
     * @param instructionId Settlement instruction ID
     * @return Flux of bi-temporal events
     */
    @GetMapping("/history/{instructionId}")
    public Flux<BiTemporalEvent<SettlementEvent>> getSettlementHistory(
            @PathVariable String instructionId) {
        
        logger.info("REST: Retrieving settlement history for: {}", instructionId);
        
        return settlementService.getSettlementHistory(instructionId)
            .doOnError(error -> logger.error("REST: Failed to retrieve settlement history", error));
    }
    
    /**
     * Gets settlement state at a specific point in time (bi-temporal query).
     * 
     * <p>This demonstrates the power of bi-temporal event store - reconstructing
     * the state of a settlement instruction as it was known at any point in time.
     * 
     * <p>Example:
     * <pre>
     * GET /api/settlements/state-at/SSI-12345?pointInTime=2025-10-07T10:00:00Z
     * </pre>
     * 
     * @param instructionId Settlement instruction ID
     * @param pointInTime Point in time for state reconstruction (ISO-8601 format)
     * @return Mono with the settlement event at that point in time
     */
    @GetMapping("/state-at/{instructionId}")
    public Mono<ResponseEntity<SettlementEvent>> getSettlementStateAt(
            @PathVariable String instructionId,
            @RequestParam Instant pointInTime) {
        
        logger.info("REST: Getting settlement state for: {} at: {}", instructionId, pointInTime);
        
        return settlementService.getSettlementStateAt(instructionId, pointInTime)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .onErrorResume(error -> {
                logger.error("REST: Failed to get settlement state at point in time", error);
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * Gets all correction events for a settlement instruction.
     * 
     * <p>Returns only events with type "instruction.settlement.corrected".
     * 
     * <p>Example:
     * <pre>
     * GET /api/settlements/corrections/SSI-12345
     * </pre>
     * 
     * @param instructionId Settlement instruction ID
     * @return Flux of correction events
     */
    @GetMapping("/corrections/{instructionId}")
    public Flux<BiTemporalEvent<SettlementEvent>> getCorrections(
            @PathVariable String instructionId) {
        
        logger.info("REST: Retrieving corrections for: {}", instructionId);
        
        return settlementService.getCorrections(instructionId)
            .doOnError(error -> logger.error("REST: Failed to retrieve corrections", error));
    }
}

