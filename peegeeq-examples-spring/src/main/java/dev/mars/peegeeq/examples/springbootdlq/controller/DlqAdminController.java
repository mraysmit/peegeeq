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
import org.springframework.web.context.request.async.DeferredResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


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
    public DeferredResult<ResponseEntity<Map<String, Object>>> getDlqDepth() {
        DeferredResult<ResponseEntity<Map<String, Object>>> result = new DeferredResult<>();
        dlqService.getDlqDepth()
            .map(depth -> {
                Map<String, Object> response = new HashMap<>();
                response.put("depth", depth);
                return (ResponseEntity<Map<String, Object>>) ResponseEntity.ok(response);
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
    }
    
    /**
     * Get all DLQ messages.
     */
    @GetMapping("/messages")
    public DeferredResult<ResponseEntity<List<Map<String, Object>>>> getDlqMessages() {
        DeferredResult<ResponseEntity<List<Map<String, Object>>>> result = new DeferredResult<>();
        dlqService.getDlqMessages()
            .map(messages -> (ResponseEntity<List<Map<String, Object>>>) ResponseEntity.ok(messages))
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
    }
    
    /**
     * Reprocess a DLQ message.
     */
    @PostMapping("/reprocess/{id}")
    public DeferredResult<ResponseEntity<Map<String, Object>>> reprocessMessage(@PathVariable Long id) {
        DeferredResult<ResponseEntity<Map<String, Object>>> result = new DeferredResult<>();
        dlqService.reprocessDlqMessage(id)
            .map(success -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", success);
                response.put("messageId", id);
                return (ResponseEntity<Map<String, Object>>) ResponseEntity.ok(response);
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
    }
    
    /**
     * Delete a DLQ message.
     */
    @DeleteMapping("/messages/{id}")
    public DeferredResult<ResponseEntity<Map<String, Object>>> deleteMessage(@PathVariable Long id) {
        DeferredResult<ResponseEntity<Map<String, Object>>> result = new DeferredResult<>();
        dlqService.deleteDlqMessage(id)
            .map(success -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", success);
                response.put("messageId", id);
                return (ResponseEntity<Map<String, Object>>) ResponseEntity.ok(response);
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
    }
    
    /**
     * Get DLQ statistics.
     */
    @GetMapping("/stats")
    public DeferredResult<ResponseEntity<Map<String, Object>>> getDlqStats() {
        DeferredResult<ResponseEntity<Map<String, Object>>> result = new DeferredResult<>();
        dlqService.getDlqStats()
            .map(stats -> (ResponseEntity<Map<String, Object>>) ResponseEntity.ok(stats))
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
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

