package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.ConsumerMemberStats;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import dev.mars.peegeeq.outbox.resilience.FilterRetryManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Implementation of a consumer group member for the outbox pattern.
 * 
 * @param <T> The type of message payload
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class OutboxConsumerGroupMember<T> implements dev.mars.peegeeq.api.messaging.ConsumerGroupMember<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupMember.class);
    
    private final String consumerId;
    private final String groupName;
    private final String topic;
    private final MessageHandler<T> messageHandler;
    @SuppressWarnings("unused") // Reserved for future group coordination features
    private final OutboxConsumerGroup<T> parentGroup;
    private final Instant createdAt;
    
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong processedMessageCount = new AtomicLong(0);
    private final AtomicLong failedMessageCount = new AtomicLong(0);
    private final AtomicLong filteredMessageCount = new AtomicLong(0);
    private final AtomicReference<Instant> lastActiveAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    // Concurrency gate: limits the number of in-flight messages per member
    private volatile int maxConcurrency;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    // Tracks in-flight processMessage() futures so stopAsync() can await them all before returning.
    private final Set<Future<Void>> inflightFutures = ConcurrentHashMap.newKeySet();

    private volatile Predicate<Message<T>> messageFilter;

    // Filter error handling
    private final FilterErrorHandlingConfig filterErrorConfig;
    private final FilterCircuitBreaker filterCircuitBreaker;
    private final Vertx vertx;
    private final DeadLetterQueueManager deadLetterQueueManager;
    // Performance tracking
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);
    
    /** Default maximum number of messages processed concurrently by a single member. */
    static final int DEFAULT_MAX_CONCURRENCY = 1;

    public OutboxConsumerGroupMember(String consumerId, String groupName, String topic,
                                    MessageHandler<T> messageHandler,
                                    Predicate<Message<T>> messageFilter,
                                    OutboxConsumerGroup<T> parentGroup) {
        this(consumerId, groupName, topic, messageHandler, messageFilter, parentGroup,
             FilterErrorHandlingConfig.defaultConfig(), DEFAULT_MAX_CONCURRENCY,
             parentGroup != null ? parentGroup.getVertx() : null);
    }

    public OutboxConsumerGroupMember(String consumerId, String groupName, String topic,
                                    MessageHandler<T> messageHandler,
                                    Predicate<Message<T>> messageFilter,
                                    OutboxConsumerGroup<T> parentGroup,
                                    FilterErrorHandlingConfig filterErrorConfig) {
        this(consumerId, groupName, topic, messageHandler, messageFilter, parentGroup,
             filterErrorConfig, DEFAULT_MAX_CONCURRENCY,
             parentGroup != null ? parentGroup.getVertx() : null);
    }

    public OutboxConsumerGroupMember(String consumerId, String groupName, String topic,
                                    MessageHandler<T> messageHandler,
                                    Predicate<Message<T>> messageFilter,
                                    OutboxConsumerGroup<T> parentGroup,
                                    FilterErrorHandlingConfig filterErrorConfig,
                                    int maxConcurrency) {
        this(consumerId, groupName, topic, messageHandler, messageFilter, parentGroup,
             filterErrorConfig, maxConcurrency,
             parentGroup != null ? parentGroup.getVertx() : null);
    }

    OutboxConsumerGroupMember(String consumerId, String groupName, String topic,
                             MessageHandler<T> messageHandler,
                             Predicate<Message<T>> messageFilter,
                             OutboxConsumerGroup<T> parentGroup,
                             FilterErrorHandlingConfig filterErrorConfig,
                             int maxConcurrency,
                             Vertx vertx) {
        this.consumerId = consumerId;
        this.groupName = groupName;
        this.topic = topic;
        this.messageHandler = messageHandler;
        this.messageFilter = messageFilter;
        this.parentGroup = parentGroup;
        this.maxConcurrency = maxConcurrency;
        this.createdAt = Instant.now();
        this.lastActiveAt.set(createdAt);

        // Initialize filter error handling
        this.filterErrorConfig = filterErrorConfig;
        this.filterCircuitBreaker = new FilterCircuitBreaker(
            consumerId + "-filter", filterErrorConfig);
        this.vertx = vertx;

        // Initialize dead letter queue manager if DLQ is enabled
        if (filterErrorConfig.isDeadLetterQueueEnabled()) {
            this.deadLetterQueueManager = new DeadLetterQueueManager(filterErrorConfig);
        } else {
            this.deadLetterQueueManager = null;
        }

        new FilterRetryManager(
            consumerId + "-filter", filterErrorConfig, vertx, deadLetterQueueManager);

        logger.debug("Created outbox consumer group member '{}' in group '{}' for topic '{}' with filter error handling (DLQ enabled: {})",
            consumerId, groupName, topic, filterErrorConfig.isDeadLetterQueueEnabled());
    }
    
    @Override
    public String getConsumerId() {
        return consumerId;
    }

    void setMaxConcurrency(int n) {
        this.maxConcurrency = Math.max(1, n);
    }

    @Override
    public String getGroupName() {
        return groupName;
    }
    
    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public Instant getJoinedAt() {
        return createdAt;
    }

    @Override
    public MessageHandler<T> getMessageHandler() {
        return messageHandler;
    }
    
    @Override
    public Predicate<Message<T>> getMessageFilter() {
        return messageFilter;
    }
    
    @Override
    public void setMessageFilter(Predicate<Message<T>> messageFilter) {
        this.messageFilter = messageFilter;
        logger.debug("Updated message filter for outbox consumer '{}' in group '{}'", consumerId, groupName);
    }
    
    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Consumer member is closed");
        }
        
        if (active.compareAndSet(false, true)) {
            lastActiveAt.set(Instant.now());
            logger.info("Started outbox consumer member '{}' in group '{}' for topic '{}'", 
                consumerId, groupName, topic);
        }
    }
    
    @Override
    public void stop() {
        if (active.compareAndSet(true, false)) {
            logger.info("Stopped outbox consumer member '{}' in group '{}' for topic '{}'", 
                consumerId, groupName, topic);
        }
    }

    /**
     * Stops this member and returns a Future that completes when all in-flight
     * message processing has finished. Safe to call concurrently.
     */
    public Future<Void> stopAsync() {
        stop();
        ArrayList<Future<Void>> pending = new ArrayList<>(inflightFutures);
        if (pending.isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.all(
            pending.stream()
                .map(f -> f.transform(ar -> Future.<Void>succeededFuture()))
                .toList()
        ).mapEmpty();
    }

    @Override
    public boolean isActive() {
        return active.get() && !closed.get();
    }
    
    @Override
    public Instant getLastActivity() {
        return lastActiveAt.get();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt.get();
    }

    public long getProcessedMessageCount() {
        return processedMessageCount.get();
    }

    public long getFailedMessageCount() {
        return failedMessageCount.get();
    }
    
    @Override
    public ConsumerMemberStats getStats() {
        long processed = processedMessageCount.get();
        long failed = failedMessageCount.get();
        long filtered = filteredMessageCount.get();
        
        // Calculate average processing time
        double avgProcessingTime = processed > 0 ? 
            (double) totalProcessingTimeMs.get() / processed : 0.0;
        
        // Calculate messages per second (rough estimate based on recent activity)
        Instant lastActive = lastActiveAt.get();
        double messagesPerSecond = 0.0;
        if (lastActive != null && processed > 0) {
            long secondsSinceCreation = java.time.Duration.between(createdAt, lastActive).getSeconds();
            if (secondsSinceCreation > 0) {
                messagesPerSecond = (double) processed / secondsSinceCreation;
            }
        }
        
        return new ConsumerMemberStats(
            consumerId, groupName, topic, isActive(), processed, failed, filtered,
            avgProcessingTime, messagesPerSecond, createdAt, lastActive, lastError.get()
        );
    }

    @Override
    public ProcessingState getProcessingState() {
        if (closed.get()) {
            return ProcessingState.STOPPED;
        }
        if (!isActive()) {
            return ProcessingState.IDLE;
        }
        return inFlightCount.get() > 0 ? ProcessingState.PROCESSING : ProcessingState.IDLE;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();

            logger.debug("Closed outbox consumer member '{}' in group '{}' for topic '{}'",
                consumerId, groupName, topic);
        }
    }
    
    /**
     * Checks if this consumer accepts the given message based on its filter.
     *
     * <p><strong>Contract:</strong> The supplied {@code Predicate<Message<T>>} filter is
     * executed synchronously on the calling thread (Vert.x event loop). Filters
     * <em>must</em> be non-blocking and CPU-only. Any I/O or blocking operation inside
     * a filter will stall the event loop. If async filter evaluation is required, wrap
     * the filter invocation in {@code vertx.executeBlocking()} before passing it here,
     * or use {@link AsyncFilterRetryManager} directly for retry-capable async execution.
     *
     * @param message The message to check
     * @return true if the message should be processed by this consumer
     */
    public boolean acceptsMessage(Message<T> message) {
        if (!isActive()) {
            return false;
        }

        if (messageFilter == null) {
            return true; // No filter means accept all messages
        }

        // For synchronous compatibility, we need to handle this synchronously
        // In a real implementation, this might be async, but for now we'll use a simplified approach
        try {
            // Check circuit breaker first
            if (!filterCircuitBreaker.allowRequest()) {
                logger.debug("Filter circuit breaker is open for consumer '{}', rejecting message {}",
                    consumerId, message.getId());
                return false;
            }

            logger.debug("Testing message filter for consumer '{}' in group '{}'. Message ID: {}, Headers: {}",
                consumerId, groupName, message.getId(), message.getHeaders());

            boolean result = messageFilter.test(message);
            filterCircuitBreaker.recordSuccess();
            return result;

        } catch (Exception e) {
            filterCircuitBreaker.recordFailure();

            // Classify the error and determine strategy
            FilterErrorHandlingConfig.ErrorClassification classification = filterErrorConfig.classifyError(e);
            FilterErrorHandlingConfig.FilterErrorStrategy strategy = filterErrorConfig.getStrategyForError(classification);

            logger.info("Message filter failed for consumer '{}' in group '{}', rejecting message {}. " +
                       "Classification: {}, Strategy: {}, Error: {}",
                consumerId, groupName, message.getId(), classification, strategy, e.getMessage());
            logger.debug("Filter exception details for consumer '{}' in group '{}'", consumerId, groupName, e);

            // For now, always reject on filter exceptions (synchronous behavior)
            // TODO: Implement async retry logic for transient errors
            return false;
        }
    }
    
    /**
     * Processes a message using this consumer's message handler.
     * 
     * @param message The message to process
     * @return A Future that completes when processing is done
     */
    public Future<Void> processMessage(Message<T> message) {
        if (!isActive()) {
            return Future.failedFuture(
                new IllegalStateException("Consumer member is not active"));
        }

        // Concurrency gate: reject if at capacity
        int current = inFlightCount.get();
        if (current >= maxConcurrency) {
            logger.debug("Consumer '{}' at max concurrency ({}/{}), rejecting message {}",
                consumerId, current, maxConcurrency, message.getId());
            return Future.failedFuture(
                new IllegalStateException("Consumer member '" + consumerId +
                    "' at max concurrency (" + maxConcurrency + ")"));
        }
        // Atomically increment; re-check in case of concurrent increment
        if (inFlightCount.incrementAndGet() > maxConcurrency) {
            inFlightCount.decrementAndGet();
            logger.debug("Consumer '{}' concurrency gate lost race, rejecting message {}",
                consumerId, message.getId());
            return Future.failedFuture(
                new IllegalStateException("Consumer member '" + consumerId +
                    "' at max concurrency (" + maxConcurrency + ")"));
        }
        
        // Check filter again (defensive programming)
        if (!acceptsMessage(message)) {
            inFlightCount.decrementAndGet();
            filteredMessageCount.incrementAndGet();
            logger.debug("Message {} filtered out by outbox consumer '{}' in group '{}'", 
                message.getId(), consumerId, groupName);
            return Future.succeededFuture();
        }
        
        lastActiveAt.set(Instant.now());
        long startTime = System.currentTimeMillis();

        logger.debug("Processing message {} with outbox consumer '{}' in group '{}'",
            message.getId(), consumerId, groupName);

        // Wrap the message handler call in try-catch to handle both:
        // 1. Direct exceptions thrown from the handler method
        // 2. Failed futures returned by the handler method
        // 3. Null returns from the handler method
        Future<Void> processingFuture;
        try {
            processingFuture = messageHandler.handle(message);

            // Handle null return from message handler convert to failed future
            // so the .onFailure handler decrements inFlightCount (no slot leak)
            if (processingFuture == null) {
                logger.error("Message handler returned null Future for message {} in consumer '{}': treating as failure",
                    message.getId(), consumerId);
                processingFuture = Future.failedFuture(
                    new IllegalStateException("Message handler returned null Future"));
            }
        } catch (Exception directException) {
            // Convert direct exceptions to failed futures
            logger.debug("Message handler threw direct exception for message {} in consumer '{}': {}",
                message.getId(), consumerId, directException.getMessage());
            processingFuture = Future.failedFuture(directException);
        }

        // Register this in-flight future so stopAsync() can await it
        Future<Void>[] trackRef = new Future[1];
        Future<Void> tracked = processingFuture
            .onSuccess(result -> {
                inFlightCount.decrementAndGet();
                long processingTime = System.currentTimeMillis() - startTime;
                totalProcessingTimeMs.addAndGet(processingTime);
                processedMessageCount.incrementAndGet();
                lastError.set(null);
                logger.debug("Successfully processed message {} with outbox consumer '{}' in group '{}' ({}ms)",
                    message.getId(), consumerId, groupName, processingTime);
                lastActiveAt.set(Instant.now());
            })
            .onFailure(error -> {
                inFlightCount.decrementAndGet();
                long processingTime = System.currentTimeMillis() - startTime;
                totalProcessingTimeMs.addAndGet(processingTime);
                failedMessageCount.incrementAndGet();
                lastError.set(error.getMessage());
                logger.error("Failed to process message {} with outbox consumer '{}' in group '{}': {}",
                    message.getId(), consumerId, groupName, error.getMessage());
                lastActiveAt.set(Instant.now());
            })
            .eventually(() -> {
                if (trackRef[0] != null) inflightFutures.remove(trackRef[0]);
                return Future.succeededFuture();
            });
        trackRef[0] = tracked;
        inflightFutures.add(tracked);
        return tracked;
    }

    /**
     * Gets the current filter circuit breaker metrics
     */
    public FilterCircuitBreaker.CircuitBreakerMetrics getFilterCircuitBreakerMetrics() {
        return filterCircuitBreaker.getMetrics();
    }

    /**
     * Resets the filter circuit breaker
     */
    public void resetFilterCircuitBreaker() {
        filterCircuitBreaker.reset();
        logger.info("Reset filter circuit breaker for consumer '{}' in group '{}'", consumerId, groupName);
    }
}
