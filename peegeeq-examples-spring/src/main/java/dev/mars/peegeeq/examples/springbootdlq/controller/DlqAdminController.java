package dev.mars.peegeeq.examples.springbootdlq.controller;

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

import dev.mars.peegeeq.examples.springbootdlq.service.DlqManagementService;
import dev.mars.peegeeq.examples.springbootdlq.service.PaymentProcessorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DLQ Admin REST Controller.
 * 
 * Provides REST endpoints for DLQ management:
 * - GET /api/dlq/depth - Get DLQ depth
 * - GET /api/dlq/messages - Get all DLQ messages
 * - POST /api/dlq/reprocess/{id} - Reprocess a DLQ message
 * - DELETE /api/dlq/messages/{id} - Delete a DLQ message
 * - GET /api/dlq/stats - Get DLQ statistics
 * - GET /api/dlq/metrics - Get processing metrics
 */
@RestController
@RequestMapping("/api/dlq")
public class DlqAdminController {
    
    private final DlqManagementService dlqService;
    private final PaymentProcessorService processorService;
    
    public DlqAdminController(
            DlqManagementService dlqService,
            PaymentProcessorService processorService) {
        this.dlqService = dlqService;
        this.processorService = processorService;
    }
    
    /**
     * Get DLQ depth.
     */
    @GetMapping("/depth")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getDlqDepth() {
        return dlqService.getDlqDepth().thenApply(depth -> {
            Map<String, Object> response = new HashMap<>();
            response.put("depth", depth);
            return ResponseEntity.ok(response);
        });
    }
    
    /**
     * Get all DLQ messages.
     */
    @GetMapping("/messages")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getDlqMessages() {
        return dlqService.getDlqMessages().thenApply(ResponseEntity::ok);
    }
    
    /**
     * Reprocess a DLQ message.
     */
    @PostMapping("/reprocess/{id}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> reprocessMessage(@PathVariable Long id) {
        return dlqService.reprocessDlqMessage(id).thenApply(success -> {
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("messageId", id);
            return ResponseEntity.ok(response);
        });
    }
    
    /**
     * Delete a DLQ message.
     */
    @DeleteMapping("/messages/{id}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteMessage(@PathVariable Long id) {
        return dlqService.deleteDlqMessage(id).thenApply(success -> {
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("messageId", id);
            return ResponseEntity.ok(response);
        });
    }
    
    /**
     * Get DLQ statistics.
     */
    @GetMapping("/stats")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getDlqStats() {
        return dlqService.getDlqStats().thenApply(ResponseEntity::ok);
    }
    
    /**
     * Get processing metrics.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("paymentsProcessed", processorService.getPaymentsProcessed());
        metrics.put("paymentsFailed", processorService.getPaymentsFailed());
        metrics.put("paymentsRetried", processorService.getPaymentsRetried());
        return ResponseEntity.ok(metrics);
    }
}

