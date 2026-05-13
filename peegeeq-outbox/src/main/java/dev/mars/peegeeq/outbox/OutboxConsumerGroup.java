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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.ConsumerMemberStats;
import dev.mars.peegeeq.api.messaging.RejectedMessageException;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.PartitionedConsumerEngine;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Outbox pattern implementation of a consumer group.
 * Provides load balancing and message filtering across multiple consumers using polling.
 * 
 * @param <T> The type of message payload
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class OutboxConsumerGroup<T> implements dev.mars.peegeeq.api.messaging.ConsumerGroup<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroup.class);

    /**
     * Lifecycle states for the consumer group. Transitions:
     * <pre>
     *   NEW → STARTING → ACTIVE → STOPPING → NEW (restartable)
     *   Any state → CLOSED (terminal)
     * </pre>
     */
    enum State { NEW, STARTING, ACTIVE, STOPPING, CLOSED }

    private final String groupName;
    private final String topic;
    private final Class<T> payloadType;
    private final PgClientFactory clientFactory;
    private final dev.mars.peegeeq.api.database.DatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final MetricsProvider metrics;
    private final PeeGeeQConfiguration configuration;
    private final String clientId;
    private final Instant createdAt;

    private final Map<String, OutboxConsumerGroupMember<T>> members = new ConcurrentHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicLong totalMessagesFiltered = new AtomicLong(0);

    // Vert.x instance for scheduling filter retry timers across all members
    private final Vertx vertx;

    private volatile Predicate<Message<T>> groupFilter;
    private volatile MessageConsumer<T> underlyingConsumer;
    private volatile boolean startedWithSubscription;

    // Partitioned consumption (OFFSET_WATERMARK mode) mirrors PgNativeConsumerGroup
    private final PgConnectionManager connectionManager;
    private final String connectionServiceId;
    private volatile PartitionedConsumerEngine<T> partitionedEngine;

    /**
     * Builder for constructing {@link OutboxConsumerGroup} instances.
     *
     * @param <T> The type of message payload
     */
    public static final class Builder<T> {
        private String groupName;
        private String topic;
        private Class<T> payloadType;
        private PgClientFactory clientFactory;
        private dev.mars.peegeeq.api.database.DatabaseService databaseService;
        private ObjectMapper objectMapper;
        private MetricsProvider metrics;
        private PeeGeeQConfiguration configuration;
        private String clientId;

        public Builder<T> groupName(String groupName) {
            this.groupName = groupName;
            return this;
        }

        public Builder<T> topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder<T> payloadType(Class<T> payloadType) {
            this.payloadType = payloadType;
            return this;
        }

        public Builder<T> clientFactory(PgClientFactory clientFactory) {
            this.clientFactory = clientFactory;
            return this;
        }

        public Builder<T> databaseService(dev.mars.peegeeq.api.database.DatabaseService databaseService) {
            this.databaseService = databaseService;
            return this;
        }

        public Builder<T> objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder<T> metrics(MetricsProvider metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder<T> configuration(PeeGeeQConfiguration configuration) {
            this.configuration = configuration;
            return this;
        }

        public Builder<T> clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public OutboxConsumerGroup<T> build() {
            Objects.requireNonNull(groupName, "groupName");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(payloadType, "payloadType");
            if (clientFactory == null && databaseService == null) {
                throw new IllegalStateException("Either clientFactory or databaseService must be provided");
            }
            if (clientFactory != null && databaseService != null) {
                throw new IllegalStateException("Only one of clientFactory or databaseService may be provided");
            }
            return new OutboxConsumerGroup<>(groupName, topic, payloadType,
                    clientFactory, databaseService, objectMapper, metrics, configuration, clientId,
                    null, null);
        }
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    // Keep public constructors for backward compatibility with existing callers
    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              PgClientFactory clientFactory, ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration) {
        this(groupName, topic, payloadType, clientFactory, null, objectMapper, metrics, configuration, null,
                null, null);
    }

    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              PgClientFactory clientFactory, ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration, String clientId) {
        this(groupName, topic, payloadType, clientFactory, null, objectMapper, metrics, configuration, clientId,
                null, null);
    }

    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              dev.mars.peegeeq.api.database.DatabaseService databaseService,
                              ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration) {
        this(groupName, topic, payloadType, null, databaseService, objectMapper, metrics, configuration, null,
                null, null);
    }

    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              dev.mars.peegeeq.api.database.DatabaseService databaseService,
                              ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration, String clientId) {
        this(groupName, topic, payloadType, null, databaseService, objectMapper, metrics, configuration, clientId,
                null, null);
    }

    /**
     * Constructor with partitioned-consumption support. When {@code connectionManager}
     * and {@code connectionServiceId} are non-null, the group will detect OFFSET_WATERMARK
     * topics on start and route through {@link PartitionedConsumerEngine}. Mirrors
     * the corresponding native constructor.
     */
    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              dev.mars.peegeeq.api.database.DatabaseService databaseService,
                              ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration, String clientId,
                              PgConnectionManager connectionManager, String connectionServiceId) {
        this(groupName, topic, payloadType, null, databaseService, objectMapper, metrics, configuration, clientId,
                connectionManager, connectionServiceId);
    }

    private OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                               PgClientFactory clientFactory,
                               dev.mars.peegeeq.api.database.DatabaseService databaseService,
                               ObjectMapper objectMapper, MetricsProvider metrics,
                               PeeGeeQConfiguration configuration, String clientId,
                               PgConnectionManager connectionManager, String connectionServiceId) {
        this.groupName = Objects.requireNonNull(groupName, "groupName");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.clientFactory = clientFactory;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.clientId = clientId;
        this.connectionManager = connectionManager;
        this.connectionServiceId = connectionServiceId;
        this.createdAt = Instant.now();
        if (databaseService != null) {
            this.vertx = databaseService.getVertx();
        } else if (clientFactory != null) {
            this.vertx = clientFactory.getConnectionManager().getVertx();
        } else {
            this.vertx = null;
        }

        logger.info("Created outbox consumer group '{}' for topic '{}' (clientId: {}, partitioned: {})",
                groupName, topic, clientId != null ? clientId : "default",
                connectionManager != null ? "enabled" : "disabled");
    }

    // -- Package-private accessors for testing and member construction --

    State getState() {
        return state.get();
    }

    Vertx getVertx() {
        return vertx;
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
        Objects.requireNonNull(consumerId, "consumerId");
        Objects.requireNonNull(handler, "handler");

        State current = state.get();
        if (current == State.CLOSED) {
            throw new IllegalStateException("Consumer group is closed");
        }
        
        OutboxConsumerGroupMember<T> member = new OutboxConsumerGroupMember<>(
            consumerId, groupName, topic, handler, messageFilter, this
        );
        
        OutboxConsumerGroupMember<T> existing = members.putIfAbsent(consumerId, member);
        if (existing != null) {
            throw new IllegalArgumentException("Consumer with ID '" + consumerId + "' already exists in group");
        }
        
        // If the group is already active, start the new member
        if (state.get() == State.ACTIVE) {
            member.start();
        }
        
        logger.debug("Added consumer '{}' to outbox group '{}' for topic '{}'", consumerId, groupName, topic);
        return member;
    }
    
    @Override
    public boolean removeConsumer(String consumerId) {
        OutboxConsumerGroupMember<T> member = members.remove(consumerId);
        if (member != null) {
            member.stop();
            member.close();
            logger.debug("Removed consumer '{}' from outbox group '{}' for topic '{}'", consumerId, groupName, topic);
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
            .filter(OutboxConsumerGroupMember::isActive)
            .count();
    }
    
    @Override
    public void start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            State current = state.get();
            if (current == State.CLOSED) {
                throw new IllegalStateException("Consumer group is closed");
            }
            if (current == State.ACTIVE || current == State.STARTING) {
                // Already started or starting idempotent
                return;
            }
            throw new IllegalStateException("Cannot start consumer group in state: " + current);
        }

        try {
            logger.info("Starting outbox consumer group '{}' for topic '{}'", groupName, topic);

            // Create the underlying consumer that will receive all messages
            OutboxConsumer<T> outboxConsumer;
            if (clientFactory != null) {
                outboxConsumer = new OutboxConsumer<>(clientFactory, objectMapper, topic, payloadType, metrics, configuration, clientId);
            } else if (databaseService != null) {
                outboxConsumer = new OutboxConsumer<>(databaseService, objectMapper, topic, payloadType, metrics, configuration, clientId);
            } else {
                throw new IllegalStateException("Both clientFactory and databaseService are null");
            }
            underlyingConsumer = outboxConsumer;

            // Set the consumer group name for tracking
            outboxConsumer.setConsumerGroupName(groupName);

            // Subscribe to messages and distribute them to group members
            underlyingConsumer.subscribe(this::distributeMessage)
                    .onFailure(err -> logger.error("Failed to subscribe consumer group '{}' for topic '{}': {}",
                            groupName, topic, err.getMessage(), err));

            // Start all existing members
            members.values().forEach(OutboxConsumerGroupMember::start);

            state.set(State.ACTIVE);
            logger.info("Outbox consumer group '{}' started with {} members", groupName, members.size());
        } catch (Exception e) {
            state.set(State.NEW);
            throw e;
        }
    }
    
    @Override
    public Future<Void> start(SubscriptionOptions subscriptionOptions) {
        if (subscriptionOptions == null) {
            throw new IllegalArgumentException("subscriptionOptions cannot be null");
        }

        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            State current = state.get();
            if (current == State.ACTIVE || current == State.STARTING) {
                return Future.failedFuture(
                        new IllegalStateException("Consumer group is already active or starting"));
            }
            if (current == State.CLOSED) {
                return Future.failedFuture(
                        new IllegalStateException("Consumer group is closed"));
            }
            return Future.failedFuture(
                    new IllegalStateException("Cannot start consumer group in state: " + current));
        }

        logger.info("Starting outbox consumer group '{}' for topic '{}' with subscription options: {}",
                   groupName, topic, subscriptionOptions);

        if (databaseService != null) {
            logger.debug("Creating subscription for group '{}' on topic '{}' with options: {}",
                       groupName, topic, subscriptionOptions);

            return databaseService.getSubscriptionService()
                .subscribe(topic, groupName, subscriptionOptions)
                .compose(v -> {
                    logger.info("Subscription created successfully for group '{}' on topic '{}'", groupName, topic);
                    startedWithSubscription = true;
                    // After the subscription row exists, detect topic mode and route accordingly.
                    // Mirrors PgNativeConsumerGroup#startInternal: if connectionManager is wired
                    // and the topic is OFFSET_WATERMARK, start the partitioned engine; otherwise
                    // fall back to the existing reference-counting (subscribe + distribute) path.
                    if (connectionManager != null) {
                        return PartitionedConsumerEngine.isOffsetWatermarkTopic(
                                        connectionManager, connectionServiceId, topic)
                                .transform(ar -> {
                                    if (ar.failed()) {
                                        logger.warn("Failed to detect topic mode for '{}', falling back to " +
                                                "reference counting: {}", topic, ar.cause().getMessage());
                                        return Future.succeededFuture(false);
                                    }
                                    return Future.succeededFuture(ar.result());
                                })
                                .compose(isOffsetWatermark -> {
                                    if (isOffsetWatermark) {
                                        return startPartitioned();
                                    }
                                    try {
                                        startInternal();
                                        return Future.<Void>succeededFuture();
                                    } catch (Exception e) {
                                        state.set(State.NEW);
                                        return Future.<Void>failedFuture(e);
                                    }
                                });
                    }
                    // No connectionManager wired \u2014 always reference counting.
                    try {
                        startInternal();
                        return Future.<Void>succeededFuture();
                    } catch (Exception e) {
                        state.set(State.NEW);
                        return Future.failedFuture(e);
                    }
                })
                .onFailure(err -> state.set(State.NEW));
        } else {
            logger.warn("DatabaseService is null - cannot create subscription. " +
                       "Subscription must be created manually via SubscriptionManager before starting.");
            try {
                startInternal();
                return Future.succeededFuture();
            } catch (Exception e) {
                state.set(State.NEW);
                return Future.failedFuture(e);
            }
        }
    }

    /**
     * Starts the partitioned consumption engine for OFFSET_WATERMARK topics.
     * The engine handles join, fetch, dispatch, and commit internally.
     * Mirrors {@code PgNativeConsumerGroup#startPartitioned}.
     *
     * @return future completing when the engine is started and members are active
     */
    private Future<Void> startPartitioned() {
        String instanceId = groupName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        partitionedEngine = new PartitionedConsumerEngine<>(
                vertx, connectionManager, connectionServiceId,
                topic, groupName, instanceId, payloadType, objectMapper
        );

        MessageHandler<T> handler = this::distributeMessage;

        return partitionedEngine.start(handler)
                .compose(v -> {
                    if (!state.compareAndSet(State.STARTING, State.ACTIVE)) {
                        logger.warn("Outbox consumer group '{}' was closed during startup, aborting", groupName);
                        return partitionedEngine.stop()
                                .onFailure(stopErr ->
                                    logger.warn("Failed to stop engine during startup abort: {}", stopErr.getMessage()))
                                .transform(ar -> Future.<Void>succeededFuture())
                                .compose(v2 -> Future.<Void>failedFuture(
                                        new IllegalStateException("Consumer group closed during startup")));
                    }
                    // Allow concurrent dispatch from multiple partitions:
                    // each assigned partition may have one message in-flight simultaneously.
                    int partitionCount = partitionedEngine.getAssignedPartitions().size();
                    if (partitionCount > 1) {
                        members.values().forEach(m -> m.setMaxConcurrency(partitionCount));
                    }
                    members.values().forEach(OutboxConsumerGroupMember::start);
                    logger.info("Outbox consumer group '{}' started in OFFSET_WATERMARK mode with {} members",
                            groupName, members.size());
                    return Future.succeededFuture();
                })
                .onFailure(err -> {
                    logger.error("Failed to start partitioned engine for outbox group '{}': {}",
                            groupName, err.getMessage());
                    state.compareAndSet(State.STARTING, State.NEW);
                });
    }

    /**
     * Internal start logic assumes state is already STARTING.
     * Transitions to ACTIVE on success.
     */
    private void startInternal() {
        OutboxConsumer<T> outboxConsumer;
        if (clientFactory != null) {
            outboxConsumer = new OutboxConsumer<>(clientFactory, objectMapper, topic, payloadType, metrics, configuration, clientId);
        } else if (databaseService != null) {
            outboxConsumer = new OutboxConsumer<>(databaseService, objectMapper, topic, payloadType, metrics, configuration, clientId);
        } else {
            throw new IllegalStateException("Both clientFactory and databaseService are null");
        }
        underlyingConsumer = outboxConsumer;
        outboxConsumer.setConsumerGroupName(groupName);
        underlyingConsumer.subscribe(this::distributeMessage)
                .onFailure(err -> logger.error("Failed to subscribe consumer group '{}' for topic '{}': {}",
                        groupName, topic, err.getMessage(), err));
        members.values().forEach(OutboxConsumerGroupMember::start);
        state.set(State.ACTIVE);
        logger.info("Outbox consumer group '{}' started with {} members", groupName, members.size());
    }
    
    @Override
    public void stop() {
        if (!state.compareAndSet(State.ACTIVE, State.STOPPING)) {
            // Not active nothing to stop
            return;
        }
        try {
            stopInternal().await();
        } catch (Exception e) {
            logger.warn("Error while waiting for outbox consumer group '{}' to stop: {}",
                    groupName, e.getMessage());
        }
    }

    @Override
    public Future<Void> stopGracefully() {
        if (!state.compareAndSet(State.ACTIVE, State.STOPPING)) {
            // Not active nothing to stop
            return Future.succeededFuture();
        }

        if (startedWithSubscription && databaseService != null) {
            logger.info("Gracefully stopping outbox consumer group '{}': cancelling subscription for topic '{}'",
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
     * @return a future that completes when the underlying consumer pool is closed
     *         (or succeeds immediately if no async close is needed)
     */
    private Future<Void> stopInternal() {
        try {
            logger.info("Stopping outbox consumer group '{}' for topic '{}'", groupName, topic);

            // Stop members synchronously. User-handler future lifetime is the caller's
            // concern, not the consumer's: we do not await in-flight handler futures.
            members.values().forEach(OutboxConsumerGroupMember::stop);

            startedWithSubscription = false;

            return closeUnderlyingConsumerAsync(
                    "Error closing outbox consumer for group '{}' during stop: {}", groupName)
                .compose(v -> stopPartitionedEngineAsync())
                .eventually(() -> {
                    state.set(State.NEW);
                    logger.info("Outbox consumer group '{}' stopped", groupName);
                    return Future.succeededFuture();
                });
        } catch (Exception e) {
            state.set(State.NEW);
            return Future.failedFuture(e);
        }
    }

    private Future<Void> closeUnderlyingConsumerAsync(String logMessage, String logGroupName) {
        if (underlyingConsumer == null) {
            return Future.succeededFuture();
        }

        underlyingConsumer.unsubscribe();

        try {
            underlyingConsumer.close();
        } catch (Exception err) {
            logger.warn(logMessage, logGroupName, err.getMessage());
        }

        underlyingConsumer = null;
        return Future.succeededFuture();
    }

    /**
     * Stops and clears the partitioned engine (if any). Safe to call when no engine
     * was started \u2014 returns a succeeded future immediately. Always nulls the field
     * so a subsequent {@link #start} starts cleanly.
     */
    private Future<Void> stopPartitionedEngineAsync() {
        PartitionedConsumerEngine<T> engine = partitionedEngine;
        if (engine == null) {
            return Future.succeededFuture();
        }
        partitionedEngine = null;
        return engine.stop()
                .onFailure(err -> logger.warn("Error stopping partitioned engine for group '{}': {}",
                        groupName, err.getMessage()))
                .transform(ar -> Future.<Void>succeededFuture());
    }
    
    @Override
    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }
    
    @Override
    public ConsumerGroupStats getStats() {
        Map<String, ConsumerMemberStats> memberStats = new HashMap<>();
        Instant lastActiveAt = null;
        long totalProcessed = 0;
        long totalFailed = 0;
        double weightedTotalMs = 0.0;
        double messagesPerSecond = 0.0;
        
        for (OutboxConsumerGroupMember<T> member : members.values()) {
            ConsumerMemberStats stats = member.getStats();
            memberStats.put(member.getConsumerId(), stats);
            
            if (stats.getLastActiveAt() != null) {
                if (lastActiveAt == null || stats.getLastActiveAt().isAfter(lastActiveAt)) {
                    lastActiveAt = stats.getLastActiveAt();
                }
            }

            long processed = member.getProcessedMessageCount();
            totalProcessed += processed;
            totalFailed += member.getFailedMessageCount();
            weightedTotalMs += stats.getAverageProcessingTimeMs() * processed;
            messagesPerSecond += stats.getMessagesPerSecond();
        }
        
        double avgProcessingTime = totalProcessed == 0 ? 0.0 : weightedTotalMs / totalProcessed;
        
        return new ConsumerGroupStats(
            groupName, topic, getActiveConsumerCount(), members.size(),
            totalProcessed, totalFailed, totalMessagesFiltered.get(),
            avgProcessingTime, messagesPerSecond, createdAt, lastActiveAt, memberStats
        );
    }
    
    @Override
    public ConsumerGroupMember<T> setMessageHandler(MessageHandler<T> handler) {
        Objects.requireNonNull(handler, "handler");
        
        State current = state.get();
        if (current == State.CLOSED) {
            throw new IllegalStateException("Consumer group is closed");
        }
        
        // Use putIfAbsent to atomically check and set the default consumer
        String defaultConsumerId = groupName + "-default-consumer";

        OutboxConsumerGroupMember<T> member = new OutboxConsumerGroupMember<>(
            defaultConsumerId, groupName, topic, handler, null, this
        );

        OutboxConsumerGroupMember<T> existing = members.putIfAbsent(defaultConsumerId, member);
        if (existing != null) {
            throw new IllegalStateException(
                "A message handler has already been set for this consumer group. " +
                "Use addConsumer() for multiple consumers."
            );
        }

        // If the group is already active, start the new member
        if (state.get() == State.ACTIVE) {
            member.start();
        }
        
        logger.debug("Set default message handler for outbox consumer group '{}'", groupName);
        return member;
    }
    
    @Override
    public void setGroupFilter(Predicate<Message<T>> groupFilter) {
        this.groupFilter = groupFilter;
        logger.debug("Set group filter for outbox consumer group '{}'", groupName);
    }
    
    @Override
    public Predicate<Message<T>> getGroupFilter() {
        return groupFilter;
    }
    
    @Override
    public void close() {
        try {
            closeAsync().await();
        } catch (Exception e) {
            logger.warn("Error while waiting for outbox consumer group '{}' to close: {}",
                    groupName, e.getMessage());
        }
    }

    Future<Void> closeAsync() {
        State prev = state.getAndSet(State.CLOSED);
        if (prev == State.CLOSED) {
            return Future.succeededFuture();
        }

        logger.info("Closing outbox consumer group '{}' for topic '{}'", groupName, topic);

        Future<Void> consumerClose = Future.succeededFuture();

        // If we were active, stop first
        if (prev == State.ACTIVE || prev == State.STARTING || prev == State.STOPPING) {
            // Stop the underlying consumer directly (no state transition since we're going to CLOSED)
            members.values().forEach(OutboxConsumerGroupMember::stop);
            consumerClose = closeUnderlyingConsumerAsync(
                    "Error closing outbox consumer during close for group '{}': {}", groupName);
        }

        // Always tear down the partitioned engine (if any) regardless of prior state.
        Future<Void> engineClose = stopPartitionedEngineAsync();

        startedWithSubscription = false;

        // Close all members
        members.values().forEach(OutboxConsumerGroupMember::close);
        members.clear();

        return consumerClose
                .compose(v -> engineClose)
                .onSuccess(v -> logger.info("Outbox consumer group '{}' closed", groupName));
    }
    
    /**
     * Distributes a message to the appropriate consumer group member.
     * Applies group-level filtering and deterministic hash-based routing.
     *
     * <p>Failure semantics:</p>
     * <ul>
     *   <li>Group filter rejection → {@link RejectedMessageException} → reset to PENDING (group-level decision, other groups may still accept)</li>
     *   <li>No eligible consumer (transient) → {@link MessageFilteredException} → reset to PENDING</li>
     *   <li>Handler processing failure → propagated as-is for retry/DLQ handling by OutboxConsumer</li>
     * </ul>
     */
    Future<Void> distributeMessage(Message<T> message) {
        // Create a child span from the message's traceparent for fan-out trace propagation.
        // Parallel consumer group deliveries form a span tree visible in Jaeger/Zipkin.
        Map<String, String> headers = message.getHeaders();
        String traceparent = headers != null ? headers.get("traceparent") : null;
        TraceCtx parentTrace = TraceCtx.parseOrCreate(traceparent);
        TraceCtx groupTrace = parentTrace.childSpan("consumer-group:" + groupName + "/process");
        TraceContextUtil.mdcScope(groupTrace);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_CONSUMER_GROUP, groupName);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, topic);
        TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, message.getId());

        // Apply group-level filter first permanent rejection.
        // Wrap in try-catch: a throwing filter must not leak MDC context.
        if (groupFilter != null) {
            boolean accepted;
            try {
                accepted = groupFilter.test(message);
            } catch (Exception e) {
                totalMessagesFiltered.incrementAndGet();
                logger.error("Group filter threw exception for message {} in group '{}', treating as rejection: {}",
                        message.getId(), groupName, e.getMessage());
                logger.debug("Group filter exception detail", e);
                return Future.<Void>failedFuture(
                        new RejectedMessageException(message.getId(), groupName,
                                "group filter threw: " + e.getMessage()))
                        .eventually(() -> {
                            TraceContextUtil.clearTraceMDC();
                            return Future.succeededFuture();
                        });
            }
            if (!accepted) {
                totalMessagesFiltered.incrementAndGet();
                logger.debug("Message {} permanently rejected by outbox group filter", message.getId());
                return Future.<Void>failedFuture(
                        new RejectedMessageException(message.getId(), groupName, "rejected by group filter"))
                        .eventually(() -> {
                            TraceContextUtil.clearTraceMDC();
                            return Future.succeededFuture();
                        });
            }
        }
        
        // Find eligible consumers (those whose filters accept the message)
        List<OutboxConsumerGroupMember<T>> eligibleConsumers = members.values().stream()
            .filter(OutboxConsumerGroupMember::isActive)
            .filter(member -> member.acceptsMessage(message))
            .toList();
        
        if (eligibleConsumers.isEmpty()) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} has no eligible consumers in outbox group '{}', resetting to PENDING",
                message.getId(), groupName);

            return Future.<Void>failedFuture(
                    new MessageFilteredException(message.getId(), groupName, "no eligible consumer in group"))
                    .eventually(() -> {
                        TraceContextUtil.clearTraceMDC();
                        return Future.succeededFuture();
                    });
        }
        
        // Deterministic hash-based routing on message ID
        OutboxConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);

        // Verify the selected member is still in the group and active (guards against
        // a concurrent removeConsumer call between the snapshot and dispatch)
        if (!members.containsValue(selectedConsumer) || !selectedConsumer.isActive()) {
            logger.debug("Selected consumer '{}' was removed or deactivated before dispatch, resetting message {} to PENDING",
                selectedConsumer.getConsumerId(), message.getId());
            return Future.<Void>failedFuture(
                    new MessageFilteredException(message.getId(), groupName,
                        "selected consumer removed before dispatch"))
                    .eventually(() -> {
                        TraceContextUtil.clearTraceMDC();
                        return Future.succeededFuture();
                    });
        }
        
        logger.debug("Distributing message {} to consumer '{}' in outbox group '{}'", 
            message.getId(), selectedConsumer.getConsumerId(), groupName);
        
        return selectedConsumer.processMessage(message)
                .eventually(() -> {
                    TraceContextUtil.clearTraceMDC();
                    return Future.succeededFuture();
                });
    }
    
    /**
     * Selects a consumer using deterministic hash-based routing on message ID.
     * Messages with the same ID will consistently route to the same consumer
     * (given a stable eligible consumer set).
     */
    private OutboxConsumerGroupMember<T> selectConsumer(List<OutboxConsumerGroupMember<T>> eligibleConsumers, 
                                                       Message<T> message) {
        int index = Math.floorMod(message.getId().hashCode(), eligibleConsumers.size());
        return eligibleConsumers.get(index);
    }
}
