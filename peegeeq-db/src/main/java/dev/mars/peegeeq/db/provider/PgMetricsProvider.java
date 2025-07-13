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


import dev.mars.peegeeq.api.MetricsProvider;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL implementation of MetricsProvider.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of MetricsProvider.
 * This class wraps the existing PeeGeeQMetrics to provide
 * a clean interface for metrics collection.
 */
public class PgMetricsProvider implements MetricsProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(PgMetricsProvider.class);
    
    private final PeeGeeQMetrics metrics;
    
    public PgMetricsProvider(PeeGeeQMetrics metrics) {
        this.metrics = metrics;
        logger.info("Initialized PgMetricsProvider with instance ID: {}", metrics.getInstanceId());
    }
    
    @Override
    public void recordMessageSent(String topic, boolean success, Duration duration) {
        try {
            if (success) {
                metrics.recordMessageSent(topic, duration.toMillis());
            } else {
                metrics.recordMessageSendError(topic);
            }
        } catch (Exception e) {
            logger.warn("Failed to record message sent metric for topic: {}", topic, e);
        }
    }
    
    @Override
    public void recordMessageReceived(String topic, boolean success, Duration duration) {
        try {
            if (success) {
                metrics.recordMessageReceived(topic, duration.toMillis());
            } else {
                metrics.recordMessageReceiveError(topic);
            }
        } catch (Exception e) {
            logger.warn("Failed to record message received metric for topic: {}", topic, e);
        }
    }
    
    @Override
    public void recordMessageAcknowledged(String topic, boolean success, Duration duration) {
        try {
            if (success) {
                metrics.recordMessageAcknowledged(topic, duration.toMillis());
            } else {
                metrics.recordMessageAckError(topic);
            }
        } catch (Exception e) {
            logger.warn("Failed to record message acknowledged metric for topic: {}", topic, e);
        }
    }
    
    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        try {
            metrics.incrementCounter(name, tags);
        } catch (Exception e) {
            logger.warn("Failed to increment counter: {}", name, e);
        }
    }
    
    @Override
    public void recordTimer(String name, Duration duration, Map<String, String> tags) {
        try {
            metrics.recordTimer(name, duration.toMillis(), tags);
        } catch (Exception e) {
            logger.warn("Failed to record timer: {}", name, e);
        }
    }
    
    @Override
    public void recordGauge(String name, double value, Map<String, String> tags) {
        try {
            metrics.recordGauge(name, value, tags);
        } catch (Exception e) {
            logger.warn("Failed to record gauge: {}", name, e);
        }
    }
    
    @Override
    public long getQueueDepth(String topic) {
        try {
            return metrics.getQueueDepth(topic);
        } catch (Exception e) {
            logger.warn("Failed to get queue depth for topic: {}", topic, e);
            return -1;
        }
    }
    
    @Override
    public Map<String, Object> getAllMetrics() {
        try {
            return metrics.getAllMetrics();
        } catch (Exception e) {
            logger.warn("Failed to get all metrics", e);
            return new HashMap<>();
        }
    }
    
    @Override
    public String getInstanceId() {
        return metrics.getInstanceId();
    }
}
