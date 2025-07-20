package dev.mars.peegeeq.api.database;

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


import java.time.Duration;
import java.util.Map;

/**
 * Abstract interface for providing metrics collection and reporting.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Abstract interface for providing metrics collection and reporting.
 * This interface allows different implementations to collect metrics
 * without exposing implementation-specific details.
 */
public interface MetricsProvider {
    
    /**
     * Records a message sent event.
     * 
     * @param topic The topic the message was sent to
     * @param success Whether the send was successful
     * @param duration The time taken to send the message
     */
    void recordMessageSent(String topic, boolean success, Duration duration);
    
    /**
     * Records a message received event.
     * 
     * @param topic The topic the message was received from
     * @param success Whether the receive was successful
     * @param duration The time taken to receive the message
     */
    void recordMessageReceived(String topic, boolean success, Duration duration);
    
    /**
     * Records a message acknowledgment event.
     * 
     * @param topic The topic the message was acknowledged from
     * @param success Whether the acknowledgment was successful
     * @param duration The time taken to acknowledge the message
     */
    void recordMessageAcknowledged(String topic, boolean success, Duration duration);
    
    /**
     * Increments a counter metric.
     * 
     * @param name The name of the counter
     * @param tags Additional tags for the metric
     */
    void incrementCounter(String name, Map<String, String> tags);
    
    /**
     * Records a timer metric.
     * 
     * @param name The name of the timer
     * @param duration The duration to record
     * @param tags Additional tags for the metric
     */
    void recordTimer(String name, Duration duration, Map<String, String> tags);
    
    /**
     * Records a gauge metric.
     * 
     * @param name The name of the gauge
     * @param value The value to record
     * @param tags Additional tags for the metric
     */
    void recordGauge(String name, double value, Map<String, String> tags);
    
    /**
     * Gets the current queue depth for a topic.
     * 
     * @param topic The topic to check
     * @return The current queue depth
     */
    long getQueueDepth(String topic);
    
    /**
     * Gets all current metrics as a map.
     * 
     * @return A map of metric names to their current values
     */
    Map<String, Object> getAllMetrics();
    
    /**
     * Gets the instance ID for this metrics provider.
     * 
     * @return The instance ID
     */
    String getInstanceId();
}
