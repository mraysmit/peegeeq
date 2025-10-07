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
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.RegulatoryReportEvent;
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
 * Service for managing regulatory reporting events.
 * 
 * Handles:
 * - Transaction reporting (MiFID II, Dodd-Frank)
 * - Regulatory submissions
 * - Audit trail for compliance
 * 
 * All events are stored in the regulatory event store with full bi-temporal tracking.
 */
@Service
public class RegulatoryReportingService {
    
    private static final Logger log = LoggerFactory.getLogger(RegulatoryReportingService.class);
    
    private final PgBiTemporalEventStore<RegulatoryReportEvent> regulatoryEventStore;
    private final FinancialCloudEventBuilder cloudEventBuilder;

    public RegulatoryReportingService(
            @Qualifier("regulatoryEventStore") PgBiTemporalEventStore<RegulatoryReportEvent> regulatoryEventStore,
            FinancialCloudEventBuilder cloudEventBuilder) {
        this.regulatoryEventStore = regulatoryEventStore;
        this.cloudEventBuilder = cloudEventBuilder;
    }
    
    /**
     * Submit a regulatory report.
     *
     * @param reportEvent The regulatory report event
     * @param correlationId Correlation ID for tracking the business workflow
     * @param causationId Causation ID linking to the parent event
     * @param validTime The business valid time for the event
     * @param connection Database connection for transactional consistency
     * @return CompletableFuture containing the created CloudEvent
     */
    public CompletableFuture<CloudEvent> submitRegulatoryReport(
            RegulatoryReportEvent reportEvent,
            String correlationId,
            String causationId,
            Instant validTime,
            SqlConnection connection) {

        log.debug("Submitting regulatory report: reportId={}, tradeId={}, reportType={}, regime={}",
            reportEvent.getReportId(), reportEvent.getTradeId(),
            reportEvent.getReportType(), reportEvent.getRegulatoryRegime());

        try {
            CloudEvent cloudEvent = cloudEventBuilder.buildCloudEvent(
                "com.fincorp.regulatory.transaction.reported.v1",
                reportEvent,
                correlationId,
                causationId,
                validTime
            );

            return regulatoryEventStore
                .appendInTransaction(
                    "regulatory.transaction.reported",
                    reportEvent,  // Store the RegulatoryReportEvent payload
                    validTime,
                    Map.of(
                        "correlationId", correlationId,
                        "reportType", reportEvent.getReportType(),
                        "regulatoryRegime", reportEvent.getRegulatoryRegime(),
                        "priority", "high",  // Regulatory reports are high priority
                        "cloudEventId", cloudEvent.getId()
                    ),
                    correlationId,
                    reportEvent.getReportId(),
                    connection
                )
                .thenApply(biTemporalEvent -> {
                    log.info("Regulatory report submitted successfully: reportId={}, eventId={}, cloudEventId={}",
                        reportEvent.getReportId(), biTemporalEvent.getEventId(), cloudEvent.getId());
                    return cloudEvent;  // Return the CloudEvent for external use
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize regulatory report: reportId={}", reportEvent.getReportId(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to serialize regulatory report", e));
        }
    }
}

