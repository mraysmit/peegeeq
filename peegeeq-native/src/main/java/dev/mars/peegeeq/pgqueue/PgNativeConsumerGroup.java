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
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.MessageConsumer;

import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.ConsumerMemberStats;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    
    private final String groupName;
    private final String topic;
    private final Class<T> payloadType;
    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final PeeGeeQMetrics metrics;
    private final Instant createdAt;
    
    private final Map<String, PgNativeConsumerGroupMember<T>> members = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong totalMessagesProcessed = new AtomicLong(0);
    private final AtomicLong totalMessagesFailed = new AtomicLong(0);
    private final AtomicLong totalMessagesFiltered = new AtomicLong(0);
    
    private volatile Predicate<Message<T>> groupFilter;
    private volatile MessageConsumer<T> underlyingConsumer;
    
    public PgNativeConsumerGroup(String groupName, String topic, Class<T> payloadType,
                                VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                PeeGeeQMetrics metrics) {
        this.groupName = groupName;
        this.topic = topic;
        this.payloadType = payloadType;
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.createdAt = Instant.now();
        
        logger.info("Created consumer group '{}' for topic '{}'", groupName, topic);
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
        if (closed.get()) {
            throw new IllegalStateException("Consumer group is closed");
        }
        
        if (members.containsKey(consumerId)) {
            throw new IllegalArgumentException("Consumer with ID '" + consumerId + "' already exists in group");
        }
        
        PgNativeConsumerGroupMember<T> member = new PgNativeConsumerGroupMember<>(
            consumerId, groupName, topic, handler, messageFilter, this
        );
        
        members.put(consumerId, member);
        
        // If the group is already active, start the new member
        if (active.get()) {
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
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Consumer group is closed");
        }
        
        if (active.compareAndSet(false, true)) {
            logger.info("Starting consumer group '{}' for topic '{}'", groupName, topic);
            
            // Create the underlying consumer that will receive all messages
            underlyingConsumer = new PgNativeQueueConsumer<>(
                poolAdapter, objectMapper, topic, payloadType, metrics
            );
            
            // Subscribe to messages and distribute them to group members
            underlyingConsumer.subscribe(this::distributeMessage);
            
            // Start all existing members
            members.values().forEach(PgNativeConsumerGroupMember::start);
            
            logger.info("Consumer group '{}' started with {} members", groupName, members.size());
        }
    }
    
    @Override
    public void stop() {
        if (active.compareAndSet(true, false)) {
            logger.info("Stopping consumer group '{}' for topic '{}'", groupName, topic);
            
            // Stop all members
            members.values().forEach(PgNativeConsumerGroupMember::stop);
            
            // Stop the underlying consumer
            if (underlyingConsumer != null) {
                underlyingConsumer.unsubscribe();
                underlyingConsumer.close();
                underlyingConsumer = null;
            }
            
            logger.info("Consumer group '{}' stopped", groupName);
        }
    }
    
    @Override
    public boolean isActive() {
        return active.get() && !closed.get();
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
    public void setGroupFilter(Predicate<Message<T>> groupFilter) {
        this.groupFilter = groupFilter;
        logger.info("Set group filter for consumer group '{}'", groupName);
    }
    
    @Override
    public Predicate<Message<T>> getGroupFilter() {
        return groupFilter;
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing consumer group '{}' for topic '{}'", groupName, topic);
            
            stop();
            
            // Close all members
            members.values().forEach(PgNativeConsumerGroupMember::close);
            members.clear();
            
            logger.info("Consumer group '{}' closed", groupName);
        }
    }
    
    /**
     * Distributes a message to the appropriate consumer group member.
     * Applies group-level filtering and load balancing.
     */
    private java.util.concurrent.CompletableFuture<Void> distributeMessage(Message<T> message) {
        // Apply group-level filter first
        if (groupFilter != null && !groupFilter.test(message)) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} filtered out by group filter", message.getId());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        // Find eligible consumers (those whose filters accept the message)
        List<PgNativeConsumerGroupMember<T>> eligibleConsumers = members.values().stream()
            .filter(PgNativeConsumerGroupMember::isActive)
            .filter(member -> member.acceptsMessage(message))
            .toList();
        
        if (eligibleConsumers.isEmpty()) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} has no eligible consumers in group '{}'", message.getId(), groupName);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        // Simple round-robin load balancing
        PgNativeConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);
        
        logger.debug("Distributing message {} to consumer '{}' in group '{}'", 
            message.getId(), selectedConsumer.getConsumerId(), groupName);
        
        return selectedConsumer.processMessage(message)
            .whenComplete((result, error) -> {
                if (error != null) {
                    totalMessagesFailed.incrementAndGet();
                } else {
                    totalMessagesProcessed.incrementAndGet();
                }
            });
    }
    
    /**
     * Selects a consumer from the eligible consumers using load balancing strategy.
     * Currently implements simple round-robin based on message ID hash.
     */
    private PgNativeConsumerGroupMember<T> selectConsumer(List<PgNativeConsumerGroupMember<T>> eligibleConsumers, 
                                                          Message<T> message) {
        // Use message ID hash for consistent distribution
        int index = Math.abs(message.getId().hashCode()) % eligibleConsumers.size();
        return eligibleConsumers.get(index);
    }
}
