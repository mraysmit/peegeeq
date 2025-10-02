package dev.mars.peegeeq.examples.springbootconsumer.controller;

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

import dev.mars.peegeeq.examples.springbootconsumer.service.OrderConsumerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for monitoring consumer status and metrics.
 * 
 * Provides endpoints to:
 * - View consumer health status
 * - Check message processing metrics
 * - Monitor consumer performance
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@RestController
@RequestMapping("/api/consumer")
public class ConsumerMonitoringController {
    
    private final OrderConsumerService consumerService;
    
    public ConsumerMonitoringController(OrderConsumerService consumerService) {
        this.consumerService = consumerService;
    }
    
    /**
     * Get consumer health status.
     * 
     * @return Consumer health information
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("consumerInstanceId", consumerService.getConsumerInstanceId());
        health.put("messagesProcessed", consumerService.getMessagesProcessed());
        health.put("messagesFiltered", consumerService.getMessagesFiltered());
        health.put("messagesFailed", consumerService.getMessagesFailed());
        
        return ResponseEntity.ok(health);
    }
    
    /**
     * Get consumer metrics.
     * 
     * @return Consumer metrics
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("consumerInstanceId", consumerService.getConsumerInstanceId());
        metrics.put("messagesProcessed", consumerService.getMessagesProcessed());
        metrics.put("messagesFiltered", consumerService.getMessagesFiltered());
        metrics.put("messagesFailed", consumerService.getMessagesFailed());
        
        long total = consumerService.getMessagesProcessed() + 
                    consumerService.getMessagesFiltered() + 
                    consumerService.getMessagesFailed();
        metrics.put("totalMessagesReceived", total);
        
        if (total > 0) {
            double successRate = (double) consumerService.getMessagesProcessed() / total * 100;
            metrics.put("successRate", String.format("%.2f%%", successRate));
        } else {
            metrics.put("successRate", "N/A");
        }
        
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * Get consumer status summary.
     * 
     * @return Consumer status summary
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("consumerInstanceId", consumerService.getConsumerInstanceId());
        status.put("status", "RUNNING");
        status.put("messagesProcessed", consumerService.getMessagesProcessed());
        
        return ResponseEntity.ok(status);
    }
}

