package dev.mars.peegeeq.examples.springbootretry.controller;

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

import dev.mars.peegeeq.examples.springbootretry.service.CircuitBreakerService;
import dev.mars.peegeeq.examples.springbootretry.service.TransactionProcessorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Retry Monitoring REST Controller.
 * 
 * Provides REST endpoints for monitoring retry behavior:
 * - GET /api/retry/metrics - Get processing metrics
 * - GET /api/retry/circuit-breaker - Get circuit breaker state
 * - POST /api/retry/circuit-breaker/reset - Reset circuit breaker
 * - GET /api/retry/health - Health check endpoint
 */
@RestController
@RequestMapping("/api/retry")
public class RetryMonitoringController {
    
    private final TransactionProcessorService processorService;
    private final CircuitBreakerService circuitBreaker;
    
    public RetryMonitoringController(
            TransactionProcessorService processorService,
            CircuitBreakerService circuitBreaker) {
        this.processorService = processorService;
        this.circuitBreaker = circuitBreaker;
    }
    
    /**
     * Get processing metrics.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("transactionsProcessed", processorService.getTransactionsProcessed());
        metrics.put("transactionsFailed", processorService.getTransactionsFailed());
        metrics.put("transactionsRetried", processorService.getTransactionsRetried());
        metrics.put("permanentFailures", processorService.getPermanentFailures());
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * Get circuit breaker state.
     */
    @GetMapping("/circuit-breaker")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerState() {
        Map<String, Object> state = new HashMap<>();
        state.put("state", circuitBreaker.getState().toString());
        state.put("failureCount", circuitBreaker.getFailureCount());
        state.put("successCount", circuitBreaker.getSuccessCount());
        state.put("allowingRequests", circuitBreaker.allowRequest());
        return ResponseEntity.ok(state);
    }
    
    /**
     * Reset circuit breaker.
     */
    @PostMapping("/circuit-breaker/reset")
    public ResponseEntity<Map<String, Object>> resetCircuitBreaker() {
        circuitBreaker.reset();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Circuit breaker reset successfully");
        return ResponseEntity.ok(response);
    }
    
    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("circuitBreakerState", circuitBreaker.getState().toString());
        health.put("processorActive", true);
        return ResponseEntity.ok(health);
    }
}

