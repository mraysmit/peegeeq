package dev.mars.peegeeq.bitemporal;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for integration testing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class IntegrationTestUtils {
    private static final Logger logger = LoggerFactory.getLogger(IntegrationTestUtils.class);
    
    /**
     * Creates a sample order event for testing.
     */
    public static OrderEvent createOrderEvent(String orderId, String customerId, String status, String region) {
        return new OrderEvent(
            orderId,
            customerId,
            new BigDecimal("99.99"),
            status,
            Instant.now().minus(1, ChronoUnit.HOURS).toString(),
            region
        );
    }

    /**
     * Creates a sample order event with specific amount.
     */
    public static OrderEvent createOrderEvent(String orderId, String customerId, BigDecimal amount, String status, String region) {
        return new OrderEvent(
            orderId,
            customerId,
            amount,
            status,
            Instant.now().minus(1, ChronoUnit.HOURS).toString(),
            region
        );
    }

    /**
     * Creates a sample order event with specific time.
     */
    public static OrderEvent createOrderEvent(String orderId, String customerId, String status, String region, Instant orderTime) {
        return new OrderEvent(
            orderId,
            customerId,
            new BigDecimal("99.99"),
            status,
            orderTime.toString(),
            region
        );
    }
    
    /**
     * Waits for a condition to be met with timeout.
     */
    public static boolean waitForCondition(java.util.function.BooleanSupplier condition, long timeoutSeconds) {
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < endTime) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
    
    /**
     * Waits for a latch with timeout and logs progress.
     */
    public static boolean waitForLatch(CountDownLatch latch, long timeoutSeconds, String description) {
        logger.info("Waiting for {} (timeout: {}s)", description, timeoutSeconds);
        try {
            boolean result = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (result) {
                logger.info("Successfully completed: {}", description);
            } else {
                logger.warn("Timeout waiting for: {}", description);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for: {}", description);
            return false;
        }
    }
    
    /**
     * Logs message details for debugging.
     */
    public static void logMessage(Message<OrderEvent> message, String prefix) {
        String correlationId = null;
        if (message instanceof SimpleMessage) {
            correlationId = ((SimpleMessage<OrderEvent>) message).getCorrelationId();
        }
        logger.info("{}: Message ID={}, Payload={}, Headers={}, CorrelationId={}",
                   prefix,
                   message.getId(),
                   message.getPayload(),
                   message.getHeaders(),
                   correlationId);
    }

    /**
     * Gets the correlation ID from a message, handling different message implementations.
     */
    public static String getCorrelationId(Message<?> message) {
        if (message instanceof SimpleMessage) {
            return ((SimpleMessage<?>) message).getCorrelationId();
        }
        // Fallback: try to get from headers
        Map<String, String> headers = message.getHeaders();
        return headers != null ? headers.get("correlation-id") : null;
    }
    
    /**
     * Logs bi-temporal event details for debugging.
     */
    public static void logBiTemporalEvent(BiTemporalEvent<OrderEvent> event, String prefix) {
        logger.info("{}: Event ID={}, Type={}, Payload={}, ValidTime={}, TransactionTime={}, AggregateId={}, CorrelationId={}", 
                   prefix,
                   event.getEventId(),
                   event.getEventType(),
                   event.getPayload(),
                   event.getValidTime(),
                   event.getTransactionTime(),
                   event.getAggregateId(),
                   event.getCorrelationId());
    }
    
    /**
     * Finds a bi-temporal event by correlation ID.
     */
    public static BiTemporalEvent<OrderEvent> findEventByCorrelationId(List<BiTemporalEvent<OrderEvent>> events, String correlationId) {
        return events.stream()
                .filter(event -> correlationId.equals(event.getCorrelationId()))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Finds a bi-temporal event by aggregate ID.
     */
    public static BiTemporalEvent<OrderEvent> findEventByAggregateId(List<BiTemporalEvent<OrderEvent>> events, String aggregateId) {
        return events.stream()
                .filter(event -> aggregateId.equals(event.getAggregateId()))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Creates a thread-safe counter for tracking received messages.
     */
    public static class MessageCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private final CountDownLatch latch;
        
        public MessageCounter(int expectedCount) {
            this.latch = new CountDownLatch(expectedCount);
        }
        
        public void increment() {
            count.incrementAndGet();
            latch.countDown();
        }
        
        public int getCount() {
            return count.get();
        }
        
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
        
        public long getRemaining() {
            return latch.getCount();
        }
    }
}
