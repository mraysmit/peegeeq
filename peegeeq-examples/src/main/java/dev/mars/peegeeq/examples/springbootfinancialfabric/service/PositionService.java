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
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.PositionUpdateEvent;
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
 * Service for managing position update events.
 * 
 * Handles:
 * - Position updates from trades
 * - Position reconciliation
 * - Position snapshots
 * 
 * All events are stored in the position event store with full bi-temporal tracking.
 */
@Service
public class PositionService {
    
    private static final Logger log = LoggerFactory.getLogger(PositionService.class);
    
    private final PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public PositionService(
            @Qualifier("positionEventStore") PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore,
            FinancialCloudEventBuilder cloudEventBuilder) {
        this.positionEventStore = positionEventStore;
        this.cloudEventBuilder = cloudEventBuilder;
    }
    
    /**
     * Record a position update.
     *
     * @param positionUpdateEvent The position update event
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> recordPositionUpdate(
            PositionUpdateEvent positionUpdateEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Recording position update: updateId={}, instrument={}, account={}, quantityChange={}",
            positionUpdateEvent.getUpdateId(), positionUpdateEvent.getInstrument(),
            positionUpdateEvent.getAccount(), positionUpdateEvent.getQuantityChange());

        try {
            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.position.update.completed.v1",
                positionUpdateEvent,
                correlationId,
                causationId,
                validTime
            );

            return positionEventStore
                .appendInTransaction(
                    "position.update.completed",
                    positionUpdateEvent,  // Store the PositionUpdateEvent payload
                    validTime,
                    Map.of(
                        "correlationId", correlationId,
                        "instrument", positionUpdateEvent.getInstrument(),
                        "account", positionUpdateEvent.getAccount(),
                        "cloudEventId", cloudEvent.getId()
                    ),
                    correlationId,
                    positionUpdateEvent.getUpdateId(),
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Position update recorded successfully: updateId={}, eventId={}, cloudEventId={}",
                        positionUpdateEvent.getUpdateId(), biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize position update: updateId={}", positionUpdateEvent.getUpdateId(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize position update", e));
        }
    }
}

