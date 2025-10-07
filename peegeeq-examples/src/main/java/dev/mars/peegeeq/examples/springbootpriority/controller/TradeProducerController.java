package dev.mars.peegeeq.examples.springbootpriority.controller;

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

import dev.mars.peegeeq.examples.springbootpriority.events.TradeSettlementEvent;
import dev.mars.peegeeq.examples.springbootpriority.model.Priority;
import dev.mars.peegeeq.examples.springbootpriority.service.TradeProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Trade Producer Controller.
 * 
 * REST API for sending trade settlement events with different priorities.
 * Provides endpoints for testing priority-based message processing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@RestController
@RequestMapping("/api/trades")
public class TradeProducerController {
    
    private static final Logger log = LoggerFactory.getLogger(TradeProducerController.class);
    
    private final TradeProducerService producerService;
    
    public TradeProducerController(TradeProducerService producerService) {
        this.producerService = producerService;
    }
    
    /**
     * Send a settlement fail event (CRITICAL priority).
     * 
     * @param request Trade request
     * @return Response with event ID
     */
    @PostMapping("/settlement-fail")
    public ResponseEntity<Map<String, String>> sendSettlementFail(@RequestBody TradeRequest request) {
        log.info("Received settlement fail request: tradeId={}", request.tradeId);
        
        String eventId = UUID.randomUUID().toString();
        TradeSettlementEvent event = new TradeSettlementEvent(
            eventId,
            request.tradeId,
            request.counterparty,
            request.amount,
            request.currency,
            request.settlementDate,
            "FAIL",
            Priority.CRITICAL.getLevel(),
            request.failureReason,
            Instant.now()
        );
        
        producerService.sendTradeEvent(event).join();
        
        return ResponseEntity.ok(Map.of(
            "eventId", eventId,
            "tradeId", request.tradeId,
            "priority", Priority.CRITICAL.getLevel(),
            "status", "FAIL",
            "message", "Settlement fail event sent successfully"
        ));
    }
    
    /**
     * Send a trade amendment event (HIGH priority).
     * 
     * @param request Trade request
     * @return Response with event ID
     */
    @PostMapping("/amendment")
    public ResponseEntity<Map<String, String>> sendAmendment(@RequestBody TradeRequest request) {
        log.info("Received amendment request: tradeId={}", request.tradeId);
        
        String eventId = UUID.randomUUID().toString();
        TradeSettlementEvent event = new TradeSettlementEvent(
            eventId,
            request.tradeId,
            request.counterparty,
            request.amount,
            request.currency,
            request.settlementDate,
            "AMEND",
            Priority.HIGH.getLevel(),
            null,
            Instant.now()
        );
        
        producerService.sendTradeEvent(event).join();
        
        return ResponseEntity.ok(Map.of(
            "eventId", eventId,
            "tradeId", request.tradeId,
            "priority", Priority.HIGH.getLevel(),
            "status", "AMEND",
            "message", "Amendment event sent successfully"
        ));
    }
    
    /**
     * Send a new trade confirmation event (NORMAL priority).
     * 
     * @param request Trade request
     * @return Response with event ID
     */
    @PostMapping("/confirmation")
    public ResponseEntity<Map<String, String>> sendConfirmation(@RequestBody TradeRequest request) {
        log.info("Received confirmation request: tradeId={}", request.tradeId);
        
        String eventId = UUID.randomUUID().toString();
        TradeSettlementEvent event = new TradeSettlementEvent(
            eventId,
            request.tradeId,
            request.counterparty,
            request.amount,
            request.currency,
            request.settlementDate,
            "NEW",
            Priority.NORMAL.getLevel(),
            null,
            Instant.now()
        );
        
        producerService.sendTradeEvent(event).join();
        
        return ResponseEntity.ok(Map.of(
            "eventId", eventId,
            "tradeId", request.tradeId,
            "priority", Priority.NORMAL.getLevel(),
            "status", "NEW",
            "message", "Confirmation event sent successfully"
        ));
    }
    
    /**
     * Get producer metrics.
     * 
     * @return Producer metrics
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(Map.of(
            "totalSent", producerService.getTotalSent(),
            "criticalSent", producerService.getCriticalSent(),
            "highSent", producerService.getHighSent(),
            "normalSent", producerService.getNormalSent()
        ));
    }
    
    /**
     * Trade request DTO.
     */
    public static class TradeRequest {
        public String tradeId;
        public String counterparty;
        public BigDecimal amount;
        public String currency;
        public LocalDate settlementDate;
        public String failureReason;  // For settlement fails
    }
}

