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

import dev.mars.peegeeq.api.messaging.DurationPercentiles;

import java.time.Duration;

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
     * Records that a message was sent to a topic, with the write's duration — submission to
     * completed INSERT, the write latency the caller experienced (telemetry §4 gap G3).
     *
     * <p>Deliberately abstract, not a default: a default delegating to the untimed overload
     * would let an implementation silently drop the duration — the exact signal this method
     * exists to carry — by forgetting to override. Every implementation states what it does
     * with the measurement.
     *
     * @param topic      The topic the message was sent to
     * @param durationMs Submission-to-completion time of the write, in milliseconds
     */
    void recordMessageSent(String topic, long durationMs);

    /**
     * Records that an idempotency-keyed send was rejected as a duplicate.
     *
     * @param topic The topic targeted by the duplicate send
     */
    void recordMessageDuplicate(String topic);

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

    // recordMessageRetried was DELETED 2026-08-09 (metrics-stack review backlog): no
    // producer or consumer ever called it, so the peegeeq.messages.retried counter
    // permanently reported 0. Native retry accounting lives in the retry_count column;
    // re-add the method only together with the production call that feeds it.

    /**
     * Records a message's delivery latency — enqueue to claim, measured on
     * the DATABASE clock inside the claim statement (telemetry G2). Tagged by
     * implementation type because the delivery mechanism is the
     * native-vs-outbox difference this measures.
     *
     * Default no-op: recording is optional exactly like the histogram in
     * {@link #recordMessageProcessed(String, Duration)}.
     *
     * @param topic The topic the message was claimed from
     * @param implementationType "native" or "outbox"
     * @param latency Time from enqueue (created_at) to claim
     */
    default void recordMessageDeliveryLatency(String topic, String implementationType, Duration latency) {
    }

    // ========================================================================
    // Query methods
    // ========================================================================

    // getQueueDepth(String topic) was DELETED 2026-08-09 (metrics-stack review backlog):
    // every implementation ignored the topic argument and returned a global figure, and no
    // production code called it. Live depth signals are the peegeeq.queue.depth.* gauges.
    //
    // The generic pass-through methods (incrementCounter, recordTimer, recordGauge,
    // getAllMetrics) were DELETED the same day: zero production callers — a speculative
    // surface that let anything register unnamed meters outside this contract. The interface
    // now carries exactly the message-lifecycle signals producers and consumers feed.

    /**
     * Gets the processing-time distribution recorded for a topic via
     * {@link #recordMessageProcessed(String, Duration)} (telemetry G1).
     *
     * Default null: an implementation that keeps no histogram (including
     * {@link NoOpMetricsProvider}) reports "no data" by absence, never by
     * zeroed values.
     *
     * @param topic The topic to query
     * @return The distribution, or null when nothing has been recorded
     */
    default DurationPercentiles getProcessingTimePercentiles(String topic) {
        return null;
    }

    /**
     * Gets the delivery-latency distribution recorded for a topic via
     * {@link #recordMessageDeliveryLatency(String, String, Duration)}
     * (telemetry G2). Same null-means-no-data contract as
     * {@link #getProcessingTimePercentiles(String)}.
     *
     * @param topic The topic to query
     * @return The distribution, or null when nothing has been recorded
     */
    default DurationPercentiles getDeliveryLatencyPercentiles(String topic) {
        return null;
    }

    /**
     * Gets the instance ID for this metrics provider.
     *
     * @return The instance ID
     */
    String getInstanceId();
}
