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

import dev.mars.peegeeq.examples.springbootpriority.service.AllTradesConsumerService;
import dev.mars.peegeeq.examples.springbootpriority.service.CriticalTradeConsumerService;
import dev.mars.peegeeq.examples.springbootpriority.service.HighPriorityConsumerService;
import dev.mars.peegeeq.examples.springbootpriority.service.TradeProducerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Priority Monitoring Controller.
 * 
 * REST API for monitoring priority-based message processing metrics.
 * Provides endpoints to view producer and consumer metrics.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@RestController
@RequestMapping("/api/monitoring")
public class PriorityMonitoringController {
    
    private final TradeProducerService producerService;
    
    @Autowired(required = false)
    private AllTradesConsumerService allTradesConsumer;
    
    @Autowired(required = false)
    private CriticalTradeConsumerService criticalConsumer;
    
    @Autowired(required = false)
    private HighPriorityConsumerService highPriorityConsumer;
    
    public PriorityMonitoringController(TradeProducerService producerService) {
        this.producerService = producerService;
    }
    
    /**
     * Get overall system metrics.
     * 
     * @return System metrics including producer and all consumers
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Producer metrics
        metrics.put("producer", Map.of(
            "totalSent", producerService.getTotalSent(),
            "criticalSent", producerService.getCriticalSent(),
            "highSent", producerService.getHighSent(),
            "normalSent", producerService.getNormalSent()
        ));
        
        // Consumer metrics
        Map<String, Object> consumers = new HashMap<>();
        
        if (allTradesConsumer != null) {
            consumers.put("allTrades", Map.of(
                "instanceId", allTradesConsumer.getConsumerInstanceId(),
                "messagesProcessed", allTradesConsumer.getMessagesProcessed(),
                "criticalProcessed", allTradesConsumer.getCriticalProcessed(),
                "highProcessed", allTradesConsumer.getHighProcessed(),
                "normalProcessed", allTradesConsumer.getNormalProcessed(),
                "messagesFailed", allTradesConsumer.getMessagesFailed()
            ));
        }
        
        if (criticalConsumer != null) {
            consumers.put("critical", Map.of(
                "instanceId", criticalConsumer.getConsumerInstanceId(),
                "messagesProcessed", criticalConsumer.getMessagesProcessed(),
                "criticalProcessed", criticalConsumer.getCriticalProcessed(),
                "messagesFiltered", criticalConsumer.getMessagesFiltered(),
                "messagesFailed", criticalConsumer.getMessagesFailed()
            ));
        }
        
        if (highPriorityConsumer != null) {
            consumers.put("highPriority", Map.of(
                "instanceId", highPriorityConsumer.getConsumerInstanceId(),
                "messagesProcessed", highPriorityConsumer.getMessagesProcessed(),
                "criticalProcessed", highPriorityConsumer.getCriticalProcessed(),
                "highProcessed", highPriorityConsumer.getHighProcessed(),
                "messagesFiltered", highPriorityConsumer.getMessagesFiltered(),
                "messagesFailed", highPriorityConsumer.getMessagesFailed()
            ));
        }
        
        metrics.put("consumers", consumers);
        
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * Get producer metrics only.
     * 
     * @return Producer metrics
     */
    @GetMapping("/producer")
    public ResponseEntity<Map<String, Object>> getProducerMetrics() {
        return ResponseEntity.ok(Map.of(
            "totalSent", producerService.getTotalSent(),
            "criticalSent", producerService.getCriticalSent(),
            "highSent", producerService.getHighSent(),
            "normalSent", producerService.getNormalSent()
        ));
    }
    
    /**
     * Get all-trades consumer metrics.
     * 
     * @return All-trades consumer metrics
     */
    @GetMapping("/consumers/all-trades")
    public ResponseEntity<Map<String, Object>> getAllTradesConsumerMetrics() {
        if (allTradesConsumer == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "instanceId", allTradesConsumer.getConsumerInstanceId(),
            "messagesProcessed", allTradesConsumer.getMessagesProcessed(),
            "criticalProcessed", allTradesConsumer.getCriticalProcessed(),
            "highProcessed", allTradesConsumer.getHighProcessed(),
            "normalProcessed", allTradesConsumer.getNormalProcessed(),
            "messagesFailed", allTradesConsumer.getMessagesFailed()
        ));
    }
    
    /**
     * Get critical consumer metrics.
     * 
     * @return Critical consumer metrics
     */
    @GetMapping("/consumers/critical")
    public ResponseEntity<Map<String, Object>> getCriticalConsumerMetrics() {
        if (criticalConsumer == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "instanceId", criticalConsumer.getConsumerInstanceId(),
            "messagesProcessed", criticalConsumer.getMessagesProcessed(),
            "criticalProcessed", criticalConsumer.getCriticalProcessed(),
            "messagesFiltered", criticalConsumer.getMessagesFiltered(),
            "messagesFailed", criticalConsumer.getMessagesFailed()
        ));
    }
    
    /**
     * Get high-priority consumer metrics.
     * 
     * @return High-priority consumer metrics
     */
    @GetMapping("/consumers/high-priority")
    public ResponseEntity<Map<String, Object>> getHighPriorityConsumerMetrics() {
        if (highPriorityConsumer == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "instanceId", highPriorityConsumer.getConsumerInstanceId(),
            "messagesProcessed", highPriorityConsumer.getMessagesProcessed(),
            "criticalProcessed", highPriorityConsumer.getCriticalProcessed(),
            "highProcessed", highPriorityConsumer.getHighProcessed(),
            "messagesFiltered", highPriorityConsumer.getMessagesFiltered(),
            "messagesFailed", highPriorityConsumer.getMessagesFailed()
        ));
    }
    
    /**
     * Get health check status.
     * 
     * @return Health status
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("producer", "RUNNING");
        
        Map<String, String> consumers = new HashMap<>();
        if (allTradesConsumer != null) {
            consumers.put("allTrades", "RUNNING");
        }
        if (criticalConsumer != null) {
            consumers.put("critical", "RUNNING");
        }
        if (highPriorityConsumer != null) {
            consumers.put("highPriority", "RUNNING");
        }
        
        health.put("consumers", consumers);
        
        return ResponseEntity.ok(health);
    }
}

