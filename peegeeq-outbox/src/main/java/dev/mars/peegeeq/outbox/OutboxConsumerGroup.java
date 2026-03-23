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
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
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

    private volatile Predicate<Message<T>> groupFilter;
    private volatile MessageConsumer<T> underlyingConsumer;

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
                    clientFactory, databaseService, objectMapper, metrics, configuration, clientId);
        }
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    // Keep public constructors for backward compatibility with existing callers
    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              PgClientFactory clientFactory, ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration) {
        this(groupName, topic, payloadType, clientFactory, null, objectMapper, metrics, configuration, null);
    }

    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              PgClientFactory clientFactory, ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration, String clientId) {
        this(groupName, topic, payloadType, clientFactory, null, objectMapper, metrics, configuration, clientId);
    }

    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              dev.mars.peegeeq.api.database.DatabaseService databaseService,
                              ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration) {
        this(groupName, topic, payloadType, null, databaseService, objectMapper, metrics, configuration, null);
    }

    public OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                              dev.mars.peegeeq.api.database.DatabaseService databaseService,
                              ObjectMapper objectMapper, MetricsProvider metrics,
                              PeeGeeQConfiguration configuration, String clientId) {
        this(groupName, topic, payloadType, null, databaseService, objectMapper, metrics, configuration, clientId);
    }

    private OutboxConsumerGroup(String groupName, String topic, Class<T> payloadType,
                               PgClientFactory clientFactory,
                               dev.mars.peegeeq.api.database.DatabaseService databaseService,
                               ObjectMapper objectMapper, MetricsProvider metrics,
                               PeeGeeQConfiguration configuration, String clientId) {
        this.groupName = Objects.requireNonNull(groupName, "groupName");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.clientFactory = clientFactory;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.clientId = clientId;
        this.createdAt = Instant.now();

        logger.info("Created outbox consumer group '{}' for topic '{}' (clientId: {})",
                groupName, topic, clientId != null ? clientId : "default");
    }

    // -- Package-private state accessor for testing --

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
                // Already started or starting — idempotent
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
            underlyingConsumer.subscribe(this::distributeMessage);

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
                    // Transition from STARTING to ACTIVE via the internal start path
                    try {
                        startInternal();
                        return Future.<Void>succeededFuture();
                    } catch (Exception e) {
                        state.set(State.NEW);
                        return Future.failedFuture(e);
                    }
                })
                .recover(err -> {
                    state.set(State.NEW);
                    return Future.failedFuture(err);
                });
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
     * Internal start logic — assumes state is already STARTING.
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
        underlyingConsumer.subscribe(this::distributeMessage);
        members.values().forEach(OutboxConsumerGroupMember::start);
        state.set(State.ACTIVE);
        logger.info("Outbox consumer group '{}' started with {} members", groupName, members.size());
    }
    
    @Override
    public void stop() {
        if (!state.compareAndSet(State.ACTIVE, State.STOPPING)) {
            // Not active — nothing to stop
            return;
        }

        try {
            logger.info("Stopping outbox consumer group '{}' for topic '{}'", groupName, topic);
            
            // Stop all members
            members.values().forEach(OutboxConsumerGroupMember::stop);
            
            // Stop the underlying consumer
            if (underlyingConsumer != null) {
                underlyingConsumer.unsubscribe();
                underlyingConsumer.close();
                underlyingConsumer = null;
            }
            
            logger.info("Outbox consumer group '{}' stopped", groupName);
        } finally {
            state.set(State.NEW);
        }
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
        State prev = state.getAndSet(State.CLOSED);
        if (prev == State.CLOSED) {
            return; // already closed
        }

        logger.info("Closing outbox consumer group '{}' for topic '{}'", groupName, topic);

        // If we were active, stop first
        if (prev == State.ACTIVE) {
            // Stop the underlying consumer directly (no state transition since we're going to CLOSED)
            members.values().forEach(OutboxConsumerGroupMember::stop);
            if (underlyingConsumer != null) {
                underlyingConsumer.unsubscribe();
                underlyingConsumer.close();
                underlyingConsumer = null;
            }
        }

        // Close all members
        members.values().forEach(OutboxConsumerGroupMember::close);
        members.clear();

        logger.info("Outbox consumer group '{}' closed", groupName);
    }
    
    /**
     * Distributes a message to the appropriate consumer group member.
     * Applies group-level filtering and deterministic hash-based routing.
     *
     * <p>Failure semantics:</p>
     * <ul>
     *   <li>Group filter permanent rejection → {@link RejectedMessageException} → dead letter queue</li>
     *   <li>No eligible consumer (transient) → {@link MessageFilteredException} → reset to PENDING</li>
     *   <li>Handler processing failure → propagated as-is for retry/DLQ handling by OutboxConsumer</li>
     * </ul>
     */
    private Future<Void> distributeMessage(Message<T> message) {
        // Apply group-level filter first — permanent rejection
        if (groupFilter != null && !groupFilter.test(message)) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} permanently rejected by outbox group filter", message.getId());
            return Future.failedFuture(
                    new RejectedMessageException(message.getId(), groupName, "rejected by group filter"));
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

            return Future.failedFuture(
                    new MessageFilteredException(message.getId(), groupName, "no eligible consumer in group"));
        }
        
        // Deterministic hash-based routing on message ID
        OutboxConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);
        
        logger.debug("Distributing message {} to consumer '{}' in outbox group '{}'", 
            message.getId(), selectedConsumer.getConsumerId(), groupName);
        
        return selectedConsumer.processMessage(message);
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
