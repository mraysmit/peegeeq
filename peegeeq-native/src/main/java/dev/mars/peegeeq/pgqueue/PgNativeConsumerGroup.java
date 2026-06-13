package dev.mars.peegeeq.pgqueue;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.ConsumerMemberStats;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.PartitionedConsumerEngine;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Native PostgreSQL implementation of a consumer group.
 * Provides load balancing and message filtering across multiple consumers.
 * 
 * @param <T> The type of message payload
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class PgNativeConsumerGroup<T> implements dev.mars.peegeeq.api.messaging.ConsumerGroup<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(PgNativeConsumerGroup.class);

    /**
     * Lifecycle states for the consumer group. Transitions:
     * <pre>
     *   NEW  STARTING  ACTIVE  STOPPING  NEW (restartable)
     *   Any state  CLOSED (terminal)
     * </pre>
     */
    enum State { NEW, STARTING, ACTIVE, STOPPING, CLOSED }
    
    private final String groupName;
    private final String topic;
    private final Class<T> payloadType;
    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final MetricsProvider metrics;
    private final PeeGeeQConfiguration configuration;
    private final DatabaseService databaseService;
    private final Instant createdAt;

    private final Map<String, PgNativeConsumerGroupMember<T>> members = new ConcurrentHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicLong totalMessagesProcessed = new AtomicLong(0);
    private final AtomicLong totalMessagesFailed = new AtomicLong(0);
    private final AtomicLong totalMessagesFiltered = new AtomicLong(0);

    private volatile Predicate<Message<T>> groupFilter;
    private volatile MessageConsumer<T> underlyingConsumer;
    private volatile boolean startedWithSubscription;

    // Partitioned consumption (OFFSET_WATERMARK mode)
    private final PgConnectionManager connectionManager;
    private final String connectionServiceId;
    private volatile PartitionedConsumerEngine<T> partitionedEngine;

    // Single canonical constructor. The earlier telescoping overloads (6-, 7-, 8-arg) were
    // removed: production builds groups only through this full form (PgNativeQueueFactory),
    // and the short overloads existed only to let tests pass null for configuration /
    // databaseService / connectionManager — the optional-via-null pattern that PeeGeeQ
    // has eliminated (a null configuration now fails fast when the group starts).
    public PgNativeConsumerGroup(String groupName, String topic, Class<T> payloadType,
                                VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                MetricsProvider metrics, PeeGeeQConfiguration configuration,
                                DatabaseService databaseService,
                                PgConnectionManager connectionManager, String connectionServiceId) {
        this.groupName = groupName;
        this.topic = topic;
        this.payloadType = payloadType;
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.databaseService = databaseService;
        this.connectionManager = connectionManager;
        this.connectionServiceId = connectionServiceId;
        this.createdAt = Instant.now();

        logger.info("Created consumer group '{}' for topic '{}' with configuration: {}, partitioned: {}",
            groupName, topic, configuration != null ? "enabled" : "disabled",
            connectionManager != null ? "enabled" : "disabled");
    }

    // -- Package-private accessors for testing --

    State getState() {
        return state.get();
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
    public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler) {
        return addConsumer(consumerId, handler, null);
    }
    
    @Override
    public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler, 
                                             Predicate<Message<T>> messageFilter) {
        if (state.get() == State.CLOSED) {
            throw new IllegalStateException("Consumer group is closed");
        }
        
        PgNativeConsumerGroupMember<T> member = new PgNativeConsumerGroupMember<>(
            consumerId, groupName, topic, handler, messageFilter, this
        );
        
        PgNativeConsumerGroupMember<T> existing = members.putIfAbsent(consumerId, member);
        if (existing != null) {
            throw new IllegalArgumentException("Consumer with ID '" + consumerId + "' already exists in group");
        }
        
        // If the group is already active, start the new member
        if (state.get() == State.ACTIVE) {
            member.start();
        }
        
        logger.info("Added consumer '{}' to group '{}' for topic '{}'", consumerId, groupName, topic);
        return member;
    }
    
    @Override
    public boolean removeConsumer(String consumerId) {
        PgNativeConsumerGroupMember<T> member = members.remove(consumerId);
        if (member != null) {
            member.stop();
            member.close();
            logger.info("Removed consumer '{}' from group '{}' for topic '{}'", consumerId, groupName, topic);
            return true;
        }
        return false;
    }
    
    @Override
    public Set<String> getConsumerIds() {
        return new HashSet<>(members.keySet());
    }
    
    @Override
    public int getActiveConsumerCount() {
        return (int) members.values().stream()
            .filter(PgNativeConsumerGroupMember::isActive)
            .count();
    }
    
    @Override
    public Future<Void> start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            State current = state.get();
            if (current == State.CLOSED) {
                return Future.failedFuture(new IllegalStateException("Consumer group is closed"));
            }
            if (current == State.ACTIVE || current == State.STARTING) {
                // Already started or starting - idempotent
                return Future.succeededFuture();
            }
            return Future.failedFuture(new IllegalStateException("Cannot start consumer group in state: " + current));
        }

        return startInternal()
                .onFailure(err -> {
                    String msg = err.getMessage();
                    if (msg != null && msg.contains("Consumer group closed during startup")) {
                        logger.debug("Consumer group '{}' startup aborted - closed before startup completed", groupName);
                    } else {
                        logger.error("Failed to start consumer group '{}': {}", groupName, msg);
                    }
                });
    }

    /**
     * Starts the partitioned consumption engine for OFFSET_WATERMARK topics.
     * The engine handles join, fetch, dispatch, and commit internally.
     *
     * @return future completing when the engine is started and members are active
     */
    private Future<Void> startPartitioned() {
        String instanceId = groupName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        partitionedEngine = new PartitionedConsumerEngine<>(
                poolAdapter.getVertx(), connectionManager, connectionServiceId,
                topic, groupName, instanceId, payloadType, objectMapper
        );

        // Collect the first member's handler (or the distributeMessage handler)
        MessageHandler<T> handler = this::distributeMessage;

        return partitionedEngine.start(handler)
                .compose(v -> {
                    // CAS: only transition if still STARTING (close() may have set CLOSED)
                    if (!state.compareAndSet(State.STARTING, State.ACTIVE)) {
                        logger.warn("Consumer group '{}' was closed during startup, aborting", groupName);
                        return partitionedEngine.stop()
                                .onFailure(stopErr ->
                                    logger.warn("Failed to stop engine during startup abort: {}", stopErr.getMessage()))
                                .transform(ar -> Future.<Void>succeededFuture())
                                .compose(v2 -> Future.<Void>failedFuture(
                                        new IllegalStateException("Consumer group closed during startup")));
                    }
                    // Start all existing members only after engine is ready
                    members.values().forEach(PgNativeConsumerGroupMember::start);
                    logger.info("Consumer group '{}' started in OFFSET_WATERMARK mode with {} members",
                            groupName, members.size());
                    return Future.succeededFuture();
                })
                .onFailure(err -> {
                    String msg = err.getMessage();
                    if (msg != null && msg.contains("Consumer group closed during startup")) {
                        logger.debug("Partitioned engine startup aborted for group '{}' - closed before startup completed", groupName);
                    } else {
                        logger.error("Failed to start partitioned engine for group '{}': {}", groupName, msg);
                    }
                    state.compareAndSet(State.STARTING, State.NEW);
                });
    }

    /**
     * Core startup logic. Assumes state is already STARTING.
     * Detects topic mode, starts the appropriate engine, and sets state to ACTIVE on success
     * or resets to NEW on failure.
     *
     * @return future completing when the consumer group is fully started
     */
    private Future<Void> startInternal() {
        logger.info("Starting consumer group '{}' for topic '{}'", groupName, topic);

        if (connectionManager != null) {
            return PartitionedConsumerEngine.isOffsetWatermarkTopic(connectionManager, connectionServiceId, topic)
                    .transform(ar -> {
                        if (ar.failed()) {
                            logger.warn("Failed to detect topic mode for '{}', falling back to reference counting: {}",
                                    topic, ar.cause().getMessage());
                            return Future.succeededFuture(false);
                        }
                        return Future.succeededFuture(ar.result());
                    })
                    .compose(isOffsetWatermark -> {
                        if (isOffsetWatermark) {
                            return startPartitioned();
                        } else {
                            return startReferenceCounting();
                        }
                    });
        } else {
            return startReferenceCounting();
        }
    }

    /**
     * Starts in reference-counting mode. Synchronous sets ACTIVE immediately.
     */
    private Future<Void> startReferenceCounting() {
        try {
            // CAS: only transition if still STARTING (close() may have set CLOSED)
            if (!state.compareAndSet(State.STARTING, State.ACTIVE)) {
                return Future.failedFuture(
                        new IllegalStateException("Consumer group closed during startup"));
            }
            startReferenceCountingInternal();
            return Future.succeededFuture();
        } catch (Exception e) {
            state.compareAndSet(State.ACTIVE, State.NEW);
            return Future.failedFuture(e);
        }
    }

    /**
     * Original start path for REFERENCE_COUNTING topics.
     */
    private void startReferenceCountingInternal() {
        // Create the underlying consumer that will receive all messages
        // The configuration is required: the consumer's LISTEN channel derives from
        // the configured schema — PeeGeeQ has no default schema.
        underlyingConsumer = new PgNativeQueueConsumer<>(
            poolAdapter, objectMapper, topic, payloadType, metrics, configuration
        );

        // Subscribe to messages and distribute them to group members
        underlyingConsumer.subscribe(this::distributeMessage)
                .onFailure(err -> {
                    if (state.get() == State.CLOSED || state.get() == State.STOPPING) {
                        logger.debug("Consumer group subscription aborted for group '{}' topic '{}' - consumer closed",
                                groupName, topic);
                    } else {
                        logger.error("Failed to subscribe consumer group '{}' for topic '{}': {}",
                                groupName, topic, err.getMessage(), err);
                    }
                });

        // Start all existing members
        members.values().forEach(PgNativeConsumerGroupMember::start);

        logger.info("Consumer group '{}' started in REFERENCE_COUNTING mode with {} members", groupName, members.size());
    }
    
    @Override
    public Future<Void> start(SubscriptionOptions subscriptionOptions) {
        if (subscriptionOptions == null) {
            throw new IllegalArgumentException("subscriptionOptions cannot be null");
        }

        State current = state.get();
        if (current == State.CLOSED) {
            return Future.failedFuture(
                    new IllegalStateException("Consumer group '" + groupName + "' has been closed"));
        }
        if (current == State.ACTIVE || current == State.STARTING) {
            // Idempotent matches the no-arg start() behaviour
            return Future.succeededFuture();
        }

        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            // Lost CAS race another thread is starting or state changed
            return Future.failedFuture(
                    new IllegalStateException("Consumer group state changed concurrently"));
        }

        logger.info("Starting consumer group '{}' for topic '{}' with subscription options: {}",
                   groupName, topic, subscriptionOptions);

        if (databaseService != null) {
            logger.debug("Creating subscription for group '{}' on topic '{}' with options: {}",
                       groupName, topic, subscriptionOptions);

            return databaseService.getSubscriptionService()
                .subscribe(topic, groupName, subscriptionOptions)
                .compose(v -> {
                    logger.info("Subscription created successfully for group '{}' on topic '{}'", groupName, topic);
                    startedWithSubscription = true;
                    return startInternal();
                })
                .onFailure(err -> {
                    state.compareAndSet(State.STARTING, State.NEW);
                });
        } else {
            logger.warn("DatabaseService is null - cannot create subscription. " +
                       "Subscription must be created manually via SubscriptionManager before starting.");
            return startInternal();
        }
    }
    
    @Override
    public Future<Void> stop() {
        if (!state.compareAndSet(State.ACTIVE, State.STOPPING)) {
            // Not active nothing to stop
            return Future.succeededFuture();
        }
        return stopInternal()
                .onFailure(err -> logger.error("Failed to stop consumer group '{}': {}",
                        groupName, err.getMessage()));
    }

    @Override
    public Future<Void> stopGracefully() {
        if (!state.compareAndSet(State.ACTIVE, State.STOPPING)) {
            // Not active nothing to stop
            return Future.succeededFuture();
        }

        if (startedWithSubscription && databaseService != null) {
            logger.info("Gracefully stopping consumer group '{}': cancelling subscription for topic '{}'",
                    groupName, topic);
            return databaseService.getSubscriptionService()
                    .cancel(topic, groupName)
                    .onFailure(err ->
                        logger.warn("Failed to cancel subscription for group '{}' on topic '{}': {}",
                                groupName, topic, err.getMessage()))
                    .transform(ar -> Future.<Void>succeededFuture())
                    .compose(v -> stopInternal());
        }

        return stopInternal();
    }

    /**
     * Internal stop logic assumes state is already STOPPING.
     * Transitions state to NEW on completion (or failure).
     *
     * @return a future that completes when the partitioned engine leave-group finishes
     *         (or succeeds immediately for reference-counting mode)
     */
    private Future<Void> stopInternal() {
        try {
            logger.info("Stopping consumer group '{}' for topic '{}'", groupName, topic);

            // Stop all members
            members.values().forEach(PgNativeConsumerGroupMember::stop);

            // Capture and stop the partitioned engine if active
            Future<Void> engineStop;
            if (partitionedEngine != null) {
                engineStop = partitionedEngine.stop()
                        .onFailure(err -> logger.warn("Error stopping partitioned engine for group '{}': {}",
                                groupName, err.getMessage()));
                partitionedEngine = null;
            } else {
                engineStop = Future.succeededFuture();
            }

            // Stop the underlying consumer
            if (underlyingConsumer != null) {
                underlyingConsumer.unsubscribe();
                underlyingConsumer.close();
                underlyingConsumer = null;
            }

            startedWithSubscription = false;

            return engineStop.eventually(() -> {
                state.compareAndSet(State.STOPPING, State.NEW);
                logger.info("Consumer group '{}' stopped", groupName);
                return Future.succeededFuture();
            });
        } catch (Exception e) {
            state.compareAndSet(State.STOPPING, State.NEW);
            return Future.failedFuture(e);
        }
    }
    
    @Override
    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }
    
    @Override
    public ConsumerGroupStats getStats() {
        Map<String, ConsumerMemberStats> memberStats = new HashMap<>();
        Instant lastActiveAt = createdAt;
        
        for (PgNativeConsumerGroupMember<T> member : members.values()) {
            ConsumerMemberStats stats = member.getStats();
            memberStats.put(member.getConsumerId(), stats);
            
            if (stats.getLastActiveAt() != null && stats.getLastActiveAt().isAfter(lastActiveAt)) {
                lastActiveAt = stats.getLastActiveAt();
            }
        }
        
        // Calculate aggregate statistics
        long totalProcessed = members.values().stream()
            .mapToLong(PgNativeConsumerGroupMember::getProcessedMessageCount)
            .sum();
        
        long totalFailed = members.values().stream()
            .mapToLong(PgNativeConsumerGroupMember::getFailedMessageCount)
            .sum();
        
        double avgProcessingTime = members.values().stream()
            .mapToDouble(member -> member.getStats().getAverageProcessingTimeMs())
            .average()
            .orElse(0.0);
        
        // Calculate messages per second (rough estimate)
        double messagesPerSecond = members.values().stream()
            .mapToDouble(member -> member.getStats().getMessagesPerSecond())
            .sum();
        
        return new ConsumerGroupStats(
            groupName, topic, getActiveConsumerCount(), members.size(),
            totalProcessed, totalFailed, totalMessagesFiltered.get(),
            avgProcessingTime, messagesPerSecond, createdAt, lastActiveAt, memberStats
        );
    }
    
    @Override
    public ConsumerGroupMember<T> setMessageHandler(MessageHandler<T> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        
        // Check if a default consumer already exists
        String defaultConsumerId = groupName + "-default-consumer";
        if (members.containsKey(defaultConsumerId)) {
            throw new IllegalStateException(
                "A message handler has already been set for this consumer group. " +
                "Use addConsumer() for multiple consumers."
            );
        }
        
        logger.info("Setting default message handler for consumer group '{}'", groupName);
        return addConsumer(defaultConsumerId, handler);
    }
    
    @Override
    public void setGroupFilter(Predicate<Message<T>> groupFilter) {
        this.groupFilter = groupFilter;
        logger.info("Set group filter for consumer group '{}'", groupName);
    }
    
    @Override
    public Predicate<Message<T>> getGroupFilter() {
        return groupFilter;
    }
    
    @Override
    public Future<Void> close() {
        State prev = state.getAndSet(State.CLOSED);
        if (prev == State.CLOSED) {
            return Future.succeededFuture(); // already closed
        }

        logger.info("Closing consumer group '{}' for topic '{}'", groupName, topic);

        // If we were active, stop first
        if (prev == State.ACTIVE) {
            // Stop the underlying consumer directly (no state transition since we're going to CLOSED)
            members.values().forEach(PgNativeConsumerGroupMember::stop);

            if (partitionedEngine != null) {
                partitionedEngine.stop()
                        .onFailure(err -> logger.warn("Error stopping partitioned engine for group '{}': {}",
                                groupName, err.getMessage()));
                partitionedEngine = null;
            }

            if (underlyingConsumer != null) {
                underlyingConsumer.unsubscribe();
                underlyingConsumer.close();
                underlyingConsumer = null;
            }
        }

        // Close all members
        members.values().forEach(PgNativeConsumerGroupMember::close);
        members.clear();

        logger.info("Consumer group '{}' closed", groupName);
        return Future.succeededFuture();
    }
    
    /**
     * Distributes a message to the appropriate consumer group member.
     * Applies group-level filtering and load balancing.
     * Creates a child span from the message's trace context for fan-out visibility.
     */
    Future<Void> distributeMessage(Message<T> message) {
        // Create a child span from the message's traceparent for fan-out trace propagation.
        // The underlying PgNativeQueueConsumer already sets MDC from headers; here we
        // derive a per-group child span so that parallel group deliveries form a span tree.
        Map<String, String> headers = message.getHeaders();
        String traceparent = headers != null ? headers.get("traceparent") : null;
        TraceCtx parentTrace = TraceCtx.parseOrCreate(traceparent);
        TraceCtx groupTrace = parentTrace.childSpan("consumer-group:" + groupName + "/process");
        TraceContextUtil.mdcScope(groupTrace);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_CONSUMER_GROUP, groupName);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, topic);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, message.getId());

        // Apply group-level filter first
        if (groupFilter != null && !groupFilter.test(message)) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} filtered out by group filter", message.getId());
            return Future.<Void>succeededFuture()
                    .eventually(() -> {
                        TraceContextUtil.clearTraceMDC();
                        return Future.succeededFuture();
                    });
        }
        
        // Find eligible consumers (those whose filters accept the message)
        List<PgNativeConsumerGroupMember<T>> eligibleConsumers = members.values().stream()
            .filter(PgNativeConsumerGroupMember::isActive)
            .filter(member -> member.acceptsMessage(message))
            .toList();
        
        if (eligibleConsumers.isEmpty()) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} has no eligible consumers in group '{}'", message.getId(), groupName);
            return Future.<Void>succeededFuture()
                    .eventually(() -> {
                        TraceContextUtil.clearTraceMDC();
                        return Future.succeededFuture();
                    });
        }
        
        // Simple round-robin load balancing
        PgNativeConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);
        
        logger.debug("Distributing message {} to consumer '{}' in group '{}'", 
            message.getId(), selectedConsumer.getConsumerId(), groupName);
        
        return selectedConsumer.processMessage(message)
            .onSuccess(result -> totalMessagesProcessed.incrementAndGet())
            .onFailure(error -> totalMessagesFailed.incrementAndGet())
            .eventually(() -> {
                TraceContextUtil.clearTraceMDC();
                return Future.succeededFuture();
            });
    }
    
    /**
     * Selects a consumer from the eligible consumers using load balancing strategy.
     * Currently implements simple round-robin based on message ID hash.
     */
    private PgNativeConsumerGroupMember<T> selectConsumer(List<PgNativeConsumerGroupMember<T>> eligibleConsumers, 
                                                          Message<T> message) {
        // Use floorMod to safely handle Integer.MIN_VALUE hash edge case.
        int index = selectConsumerIndex(message.getId().hashCode(), eligibleConsumers.size());
        return eligibleConsumers.get(index);
    }

    static int selectConsumerIndex(int messageIdHash, int consumerCount) {
        return Math.floorMod(messageIdHash, consumerCount);
    }
}
