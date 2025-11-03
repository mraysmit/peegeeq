package dev.mars.peegeeq.examples.springboot2bitemporal.service;

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
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.springboot2bitemporal.adapter.ReactiveBiTemporalAdapter;
import dev.mars.peegeeq.examples.springboot2bitemporal.events.SettlementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing settlement instruction lifecycle using bi-temporal event store.
 * 
 * <p>This service demonstrates TWO approaches for integrating PeeGeeQ with Spring WebFlux:
 * <ol>
 *   <li><b>Approach A:</b> Using current CompletableFuture API</li>
 *   <li><b>Approach B:</b> Using proposed native Vert.x Future API (commented out - requires API enhancement)</li>
 * </ol>
 * 
 * <p><b>Event Naming Pattern:</b> {entity}.{action}.{state}
 * <ul>
 *   <li>instruction.settlement.submitted</li>
 *   <li>instruction.settlement.matched</li>
 *   <li>instruction.settlement.confirmed</li>
 *   <li>instruction.settlement.failed</li>
 *   <li>instruction.settlement.corrected</li>
 * </ul>
 * 
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Bi-temporal event appending with valid time</li>
 *   <li>Historical settlement queries</li>
 *   <li>Point-in-time state reconstruction</li>
 *   <li>Bi-temporal corrections with audit trail</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Service
public class SettlementService {
    
    private static final Logger logger = LoggerFactory.getLogger(SettlementService.class);
    
    private final EventStore<SettlementEvent> eventStore;
    private final ReactiveBiTemporalAdapter adapter;
    
    public SettlementService(EventStore<SettlementEvent> eventStore, 
                            ReactiveBiTemporalAdapter adapter) {
        this.eventStore = eventStore;
        this.adapter = adapter;
    }
    
    // ========== APPROACH A: Using CompletableFuture API (Current) ==========
    
    /**
     * Records a settlement event using the current CompletableFuture API.
     * 
     * <p>This method uses PeeGeeQ's current public API that returns CompletableFuture.
     * 
     * @param eventType Event type following {entity}.{action}.{state} pattern
     * @param event Settlement event data
     * @return Mono with the recorded bi-temporal event
     */
    public Mono<BiTemporalEvent<SettlementEvent>> recordSettlement(String eventType, SettlementEvent event) {
        logger.info("Recording settlement event: {} for instruction: {}", eventType, event.getInstructionId());
        
        // Use current CompletableFuture API
        CompletableFuture<BiTemporalEvent<SettlementEvent>> future = 
            eventStore.append(eventType, event, event.getEventTime());
        
        return adapter.toMono(future)
            .doOnSuccess(result -> logger.info("Settlement event recorded: {} for instruction: {}", 
                eventType, event.getInstructionId()))
            .doOnError(error -> logger.error("Failed to record settlement event: {} for instruction: {}", 
                eventType, event.getInstructionId(), error));
    }
    
    /**
     * Retrieves settlement history for an instruction using the current CompletableFuture API.
     * 
     * @param instructionId Settlement instruction ID
     * @return Flux of bi-temporal events for the instruction
     */
    public Flux<BiTemporalEvent<SettlementEvent>> getSettlementHistory(String instructionId) {
        logger.info("Retrieving settlement history for instruction: {}", instructionId);
        
        // Use current CompletableFuture API
        CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future = 
            eventStore.query(EventQuery.all());
        
        return adapter.toFlux(future)
            .filter(event -> instructionId.equals(event.getPayload().getInstructionId()))
            .sort(Comparator.comparing(BiTemporalEvent::getValidTime))
            .doOnComplete(() -> logger.info("Retrieved settlement history for instruction: {}", instructionId))
            .doOnError(error -> logger.error("Failed to retrieve settlement history for instruction: {}", 
                instructionId, error));
    }
    
    /**
     * Gets the settlement state at a specific point in time (bi-temporal query).
     * 
     * <p>This demonstrates the power of bi-temporal event store - reconstructing
     * the state of a settlement instruction as it was known at any point in time.
     * 
     * @param instructionId Settlement instruction ID
     * @param pointInTime Point in time for state reconstruction
     * @return Mono with the settlement event at that point in time
     */
    public Mono<SettlementEvent> getSettlementStateAt(String instructionId, Instant pointInTime) {
        logger.info("Getting settlement state for instruction: {} at: {}", instructionId, pointInTime);

        return getSettlementHistory(instructionId)
            .filter(event -> !event.getValidTime().isAfter(pointInTime))
            .sort((a, b) -> b.getValidTime().compareTo(a.getValidTime()))
            .next()
            .map(BiTemporalEvent::getPayload)
            .doOnSuccess(event -> logger.info("Found settlement state for instruction: {} at: {}",
                instructionId, pointInTime))
            .doOnError(error -> logger.error("Failed to get settlement state for instruction: {} at: {}",
                instructionId, pointInTime, error));
    }
    
    /**
     * Corrects a settlement event (bi-temporal correction).
     * 
     * <p>This creates a new version of the settlement event with corrected data,
     * preserving the complete audit trail.
     * 
     * @param instructionId Settlement instruction ID
     * @param correctedEvent Corrected settlement event data
     * @return Mono with the correction event
     */
    public Mono<BiTemporalEvent<SettlementEvent>> correctSettlement(String instructionId, 
                                                                    SettlementEvent correctedEvent) {
        logger.info("Correcting settlement for instruction: {}", instructionId);
        
        return recordSettlement("instruction.settlement.corrected", correctedEvent)
            .doOnSuccess(result -> logger.info("Settlement corrected for instruction: {}", instructionId))
            .doOnError(error -> logger.error("Failed to correct settlement for instruction: {}", 
                instructionId, error));
    }
    
    /**
     * Gets all correction events for a settlement instruction.
     * 
     * @param instructionId Settlement instruction ID
     * @return Flux of correction events
     */
    public Flux<BiTemporalEvent<SettlementEvent>> getCorrections(String instructionId) {
        logger.info("Retrieving corrections for instruction: {}", instructionId);
        
        return getSettlementHistory(instructionId)
            .filter(event -> "instruction.settlement.corrected".equals(event.getEventType()))
            .doOnComplete(() -> logger.info("Retrieved corrections for instruction: {}", instructionId))
            .doOnError(error -> logger.error("Failed to retrieve corrections for instruction: {}", 
                instructionId, error));
    }
    
    // ========== APPROACH B: Using Native Vert.x Future API (Proposed - Commented Out) ==========
    
    /*
     * NOTE: The methods below demonstrate how to use a native Vert.x Future API
     * if PeeGeeQ were to expose it. This approach would be more efficient as it
     * eliminates the intermediate CompletableFuture conversion.
     * 
     * To enable this approach, PeeGeeQ would need to add methods like:
     * - Future<BiTemporalEvent<T>> appendAsync(String eventType, T payload, Instant validTime)
     * - Future<List<BiTemporalEvent<T>>> queryAsync(EventQuery query)
     * 
     * Benefits:
     * - Better performance (one less conversion)
     * - More composable (can use Vert.x operators)
     * - Clearer intent (shows PeeGeeQ is Vert.x-based)
     */
    
    /*
    public Mono<BiTemporalEvent<SettlementEvent>> recordSettlementWithVertxFuture(
            String eventType, SettlementEvent event) {
        
        logger.info("Recording settlement event (Vert.x Future): {} for instruction: {}", 
            eventType, event.getInstructionId());
        
        // Use proposed native Vert.x Future API
        io.vertx.core.Future<BiTemporalEvent<SettlementEvent>> future = 
            eventStore.appendAsync(eventType, event, event.getEventTime());
        
        return adapter.toMonoFromVertxFuture(future)
            .doOnSuccess(result -> logger.info("Settlement event recorded (Vert.x): {} for instruction: {}", 
                eventType, event.getInstructionId()))
            .doOnError(error -> logger.error("Failed to record settlement event (Vert.x): {} for instruction: {}", 
                eventType, event.getInstructionId(), error));
    }
    
    public Flux<BiTemporalEvent<SettlementEvent>> getSettlementHistoryWithVertxFuture(String instructionId) {
        logger.info("Retrieving settlement history (Vert.x Future) for instruction: {}", instructionId);
        
        // Use proposed native Vert.x Future API
        io.vertx.core.Future<List<BiTemporalEvent<SettlementEvent>>> future = 
            eventStore.queryAsync(EventQuery.all());
        
        return adapter.toFluxFromVertxFuture(future)
            .filter(event -> instructionId.equals(event.getPayload().getInstructionId()))
            .sort(Comparator.comparing(BiTemporalEvent::getValidTime))
            .doOnComplete(() -> logger.info("Retrieved settlement history (Vert.x) for instruction: {}", instructionId))
            .doOnError(error -> logger.error("Failed to retrieve settlement history (Vert.x) for instruction: {}", 
                instructionId, error));
    }
    */
}

