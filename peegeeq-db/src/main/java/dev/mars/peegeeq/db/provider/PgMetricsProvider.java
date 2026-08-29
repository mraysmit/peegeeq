package dev.mars.peegeeq.db.provider;

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

import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * PostgreSQL implementation of MetricsProvider.
 *
 * This class wraps the existing PeeGeeQMetrics to provide a clean interface
 * for metrics collection that matches what producers and consumers actually use.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgMetricsProvider implements MetricsProvider {

    private static final Logger logger = LoggerFactory.getLogger(PgMetricsProvider.class);

    private final PeeGeeQMetrics metrics;

    public PgMetricsProvider(PeeGeeQMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        logger.info("Initialized PgMetricsProvider with instance ID: {}", metrics.getInstanceId());
    }

    @Override
    public void recordMessageSent(String topic) {
        try {
            metrics.recordMessageSent(topic);
        } catch (Exception e) {
            logger.warn("Failed to record message sent metric for topic: {}", topic, e);
        }
    }

    @Override
    public void recordMessageSent(String topic, long durationMs) {
        try {
            metrics.recordMessageSent(topic, durationMs);
        } catch (Exception e) {
            logger.warn("Failed to record timed message sent metric for topic: {}", topic, e);
        }
    }

    @Override
    public void recordMessageDuplicate(String topic) {
        try {
            metrics.recordMessageDuplicate(topic);
        } catch (Exception e) {
            logger.warn("Failed to record duplicate message metric for topic: {}", topic, e);
        }
    }

    @Override
    public void recordMessageReceived(String topic) {
        try {
            metrics.recordMessageReceived(topic);
        } catch (Exception e) {
            logger.warn("Failed to record message received metric for topic: {}", topic, e);
        }
    }

    @Override
    public void recordMessageProcessed(String topic, Duration processingTime) {
        try {
            metrics.recordMessageProcessed(topic, processingTime);
        } catch (Exception e) {
            logger.warn("Failed to record message processed metric for topic: {}", topic, e);
        }
    }

    @Override
    public void recordMessageFailed(String topic, String reason) {
        try {
            metrics.recordMessageFailed(topic, reason);
        } catch (Exception e) {
            logger.warn("Failed to record message failed metric for topic: {}", topic, e);
        }
    }

    @Override
    public void recordMessageDeadLettered(String topic, String reason) {
        try {
            metrics.recordMessageDeadLettered(topic, reason);
        } catch (Exception e) {
            logger.warn("Failed to record message dead-lettered metric for topic: {}", topic, e);
        }
    }

    @Override
    public dev.mars.peegeeq.api.messaging.DurationPercentiles getProcessingTimePercentiles(String topic) {
        try {
            return metrics.getProcessingTimePercentiles(topic);
        } catch (Exception e) {
            logger.warn("Failed to get processing-time percentiles for topic: {}", topic, e);
            return null;
        }
    }

    @Override
    public void recordMessageDeliveryLatency(String topic, String implementationType, Duration latency) {
        try {
            metrics.recordMessageDeliveryLatency(topic, implementationType, latency);
        } catch (Exception e) {
            logger.warn("Failed to record delivery latency for topic: {}", topic, e);
        }
    }

    @Override
    public dev.mars.peegeeq.api.messaging.DurationPercentiles getDeliveryLatencyPercentiles(String topic) {
        try {
            return metrics.getDeliveryLatencyPercentiles(topic);
        } catch (Exception e) {
            logger.warn("Failed to get delivery-latency percentiles for topic: {}", topic, e);
            return null;
        }
    }

    @Override
    public String getInstanceId() {
        return metrics.getInstanceId();
    }
}
