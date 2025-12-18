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
 * Interface for metrics collection in PeeGeeQ message queue system.
 *
 * This interface defines the metrics methods that producers and consumers
 * actually use. Implementations must never be null - use {@link NoOpMetricsProvider}
 * if metrics collection is disabled.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public interface MetricsProvider {

    // ========================================================================
    // Message lifecycle methods - these are what producers/consumers use
    // ========================================================================

    /**
     * Records that a message was sent to a topic.
     *
     * @param topic The topic the message was sent to
     */
    void recordMessageSent(String topic);

    /**
     * Records that a message was received from a topic.
     *
     * @param topic The topic the message was received from
     */
    void recordMessageReceived(String topic);

    /**
     * Records that a message was successfully processed.
     *
     * @param topic The topic the message was from
     * @param processingTime The time taken to process the message
     */
    void recordMessageProcessed(String topic, Duration processingTime);

    /**
     * Records that a message processing failed.
     *
     * @param topic The topic the message was from
     * @param reason The reason for the failure (e.g., exception class name)
     */
    void recordMessageFailed(String topic, String reason);

    /**
     * Records that a message was moved to the dead letter queue.
     *
     * @param topic The topic the message was from
     * @param reason The reason for dead-lettering
     */
    void recordMessageDeadLettered(String topic, String reason);

    /**
     * Records that a message was retried.
     *
     * @param topic The topic the message was from
     * @param retryCount The current retry count
     */
    void recordMessageRetried(String topic, int retryCount);

    // ========================================================================
    // Generic metrics methods
    // ========================================================================

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

    // ========================================================================
    // Query methods
    // ========================================================================

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
     * @return A map of metric names to their current numeric values
     */
    Map<String, Number> getAllMetrics();

    /**
     * Gets the instance ID for this metrics provider.
     *
     * @return The instance ID
     */
    String getInstanceId();
}
