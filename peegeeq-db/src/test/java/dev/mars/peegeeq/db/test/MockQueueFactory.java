package dev.mars.peegeeq.db.test;

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

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.database.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

/**
 * Mock queue factory for testing purposes.
 * 
 * This factory creates mock implementations that don't actually
 * send or receive messages, but provide the correct interfaces
 * for testing configuration and setup logic.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class MockQueueFactory implements QueueFactory {

    private static final Logger logger = LoggerFactory.getLogger(MockQueueFactory.class);
    private final DatabaseService databaseService;
    private final Map<String, Object> configuration;
    private boolean closed = false;

    // Shared message queues for simulation
    private final Map<String, ConcurrentLinkedQueue<MockMessage<?>>> messageQueues = new HashMap<>();
    private final Map<String, List<MockMessageConsumer<?>>> consumers = new HashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);
    
    public MockQueueFactory(DatabaseService databaseService) {
        this(databaseService, new HashMap<>());
    }

    public MockQueueFactory(DatabaseService databaseService, Map<String, Object> configuration) {
        this.databaseService = databaseService;
        this.configuration = new HashMap<>(configuration);
        logger.info("Created mock queue factory for testing with configuration: {}", configuration);
    }
    
    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating mock producer for topic: {}", topic);
        return new MockMessageProducer<>(topic, payloadType);
    }
    
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating mock consumer for topic: {}", topic);
        return new MockMessageConsumer<>(topic, payloadType);
    }
    
    @Override
    public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating mock consumer group '{}' for topic: {}", groupName, topic);
        return new MockConsumerGroup<>(groupName, topic, payloadType);
    }
    
    @Override
    public String getImplementationType() {
        return "mock";
    }
    
    @Override
    public boolean isHealthy() {
        return !closed && databaseService.isHealthy();
    }
    
    @Override
    public void close() throws Exception {
        if (!closed) {
            logger.info("Closing mock queue factory");
            closed = true;
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Mock queue factory is closed");
        }
    }
    
    // Mock message wrapper
    private static class MockMessage<T> implements Message<T> {
        private final String id;
        private final T payload;
        private final Map<String, String> headers;
        private final String correlationId;
        private final String messageGroup;
        private final Instant createdAt;
        private final AtomicInteger retryCount = new AtomicInteger(0);

        MockMessage(String id, T payload, Map<String, String> headers, String correlationId, String messageGroup) {
            this.id = id;
            this.payload = payload;
            this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
            this.correlationId = correlationId;
            this.messageGroup = messageGroup;
            this.createdAt = Instant.now();
        }

        @Override public String getId() { return id; }
        @Override public T getPayload() { return payload; }
        @Override public Map<String, String> getHeaders() { return new HashMap<>(headers); }
        @Override public Instant getCreatedAt() { return createdAt; }

        // Additional methods for mock functionality (not part of Message interface)
        public String getCorrelationId() { return correlationId; }
        public String getMessageGroup() { return messageGroup; }
        public int getRetryCount() { return retryCount.get(); }
        public void incrementRetryCount() { retryCount.incrementAndGet(); }
    }

    // Mock implementations
    private class MockMessageProducer<T> implements MessageProducer<T> {
        private final String topic;
        private final Class<T> payloadType;

        MockMessageProducer(String topic, Class<T> payloadType) {
            this.topic = topic;
            this.payloadType = payloadType;
        }

        @Override
        public CompletableFuture<Void> send(T payload) {
            return send(payload, null, null, null);
        }

        @Override
        public CompletableFuture<Void> send(T payload, Map<String, String> headers) {
            return send(payload, headers, null, null);
        }

        @Override
        public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId) {
            return send(payload, headers, correlationId, null);
        }

        @Override
        public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
            }

            String messageId = "mock-msg-" + messageIdCounter.incrementAndGet();
            MockMessage<T> message = new MockMessage<>(messageId, payload, headers, correlationId, messageGroup);

            // Add to queue
            messageQueues.computeIfAbsent(topic, k -> new ConcurrentLinkedQueue<>()).offer(message);
            logger.debug("Mock producer queued message {} to topic: {}", messageId, topic);

            // Notify consumers
            notifyConsumers(topic);

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            logger.debug("Closing mock producer for topic: {}", topic);
        }
    }

    private void notifyConsumers(String topic) {
        List<MockMessageConsumer<?>> topicConsumers = consumers.get(topic);
        if (topicConsumers != null) {
            for (MockMessageConsumer<?> consumer : topicConsumers) {
                consumer.processMessages();
            }
        }
    }
    
    private class MockMessageConsumer<T> implements MessageConsumer<T> {
        private final String topic;
        private final Class<T> payloadType;
        private MessageHandler<T> handler;
        private final AtomicBoolean active = new AtomicBoolean(false);

        MockMessageConsumer(String topic, Class<T> payloadType) {
            this.topic = topic;
            this.payloadType = payloadType;
        }

        @Override
        public void subscribe(MessageHandler<T> handler) {
            this.handler = handler;
            active.set(true);

            // Register this consumer
            consumers.computeIfAbsent(topic, k -> new ArrayList<>()).add(this);

            logger.debug("Mock consumer subscribed for topic: {}", topic);

            // Start processing any existing messages
            processMessages();
        }

        @Override
        public void unsubscribe() {
            active.set(false);

            // Unregister this consumer
            List<MockMessageConsumer<?>> topicConsumers = consumers.get(topic);
            if (topicConsumers != null) {
                topicConsumers.remove(this);
            }

            logger.debug("Mock consumer unsubscribed for topic: {}", topic);
        }

        @Override
        public void close() {
            unsubscribe();
            logger.debug("Closing mock consumer for topic: {}", topic);
        }

        @SuppressWarnings("unchecked")
        void processMessages() {
            if (!active.get() || handler == null || closed) {
                return;
            }

            ConcurrentLinkedQueue<MockMessage<?>> queue = messageQueues.get(topic);
            if (queue == null) {
                return;
            }

            MockMessage<?> message;
            while ((message = queue.poll()) != null && active.get()) {
                MockMessage<T> typedMessage = (MockMessage<T>) message;
                processMessage(typedMessage);
            }
        }

        private void processMessage(MockMessage<T> message) {
            executor.submit(() -> {
                try {
                    CompletableFuture<Void> result = handler.handle(message);
                    result.whenComplete((v, throwable) -> {
                        if (throwable != null) {
                            handleMessageFailure(message, throwable);
                        } else {
                            logger.debug("Successfully processed message: {}", message.getId());
                        }
                    });
                } catch (Exception e) {
                    handleMessageFailure(message, e);
                }
            });
        }

        private void handleMessageFailure(MockMessage<T> message, Throwable throwable) {
            message.incrementRetryCount();
            int maxRetries = getMaxRetries();

            logger.debug("Message {} failed (attempt {}): {}",
                message.getId(), message.getRetryCount(), throwable.getMessage());

            if (message.getRetryCount() < maxRetries) {
                // Retry after a short delay
                executor.schedule(() -> {
                    if (active.get()) {
                        processMessage(message);
                    }
                }, 100, TimeUnit.MILLISECONDS);
            } else {
                logger.debug("Message {} exceeded max retries ({}), giving up",
                    message.getId(), maxRetries);
            }
        }
    }

    private int getMaxRetries() {
        // Check configuration first
        logger.debug("Mock factory configuration: {}", configuration);
        if (configuration.containsKey("peeGeeQConfiguration")) {
            Object configObj = configuration.get("peeGeeQConfiguration");
            logger.debug("Found peeGeeQConfiguration: {}", configObj);
            if (configObj instanceof dev.mars.peegeeq.db.config.PeeGeeQConfiguration) {
                dev.mars.peegeeq.db.config.PeeGeeQConfiguration peeGeeQConfig =
                    (dev.mars.peegeeq.db.config.PeeGeeQConfiguration) configObj;
                int maxRetries = peeGeeQConfig.getQueueConfig().getMaxRetries();
                logger.info("Mock factory using configured max retries: {}", maxRetries);
                return maxRetries;
            }
        }

        // Default max retries for mock
        logger.info("Mock factory using default max retries: 3");
        return 3;
    }
    
    private static class MockConsumerGroup<T> implements ConsumerGroup<T> {
        private final String groupName;
        private final String topic;
        private final Class<T> payloadType;
        private final Set<String> consumerIds = new HashSet<>();
        private Predicate<Message<T>> groupFilter;
        private boolean active = false;

        MockConsumerGroup(String groupName, String topic, Class<T> payloadType) {
            this.groupName = groupName;
            this.topic = topic;
            this.payloadType = payloadType;
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
            logger.debug("Mock consumer group '{}' added consumer: {}", groupName, consumerId);
            consumerIds.add(consumerId);
            return new MockConsumerGroupMember<>(consumerId, handler);
        }

        @Override
        public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> messageFilter) {
            logger.debug("Mock consumer group '{}' added consumer with filter: {}", groupName, consumerId);
            consumerIds.add(consumerId);
            return new MockConsumerGroupMember<>(consumerId, handler);
        }

        @Override
        public boolean removeConsumer(String consumerId) {
            logger.debug("Mock consumer group '{}' removed consumer: {}", groupName, consumerId);
            return consumerIds.remove(consumerId);
        }

        @Override
        public Set<String> getConsumerIds() {
            return new HashSet<>(consumerIds);
        }

        @Override
        public int getActiveConsumerCount() {
            return consumerIds.size();
        }

        @Override
        public void start() {
            logger.debug("Starting mock consumer group: {}", groupName);
            active = true;
        }

        @Override
        public void stop() {
            logger.debug("Stopping mock consumer group: {}", groupName);
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public ConsumerGroupStats getStats() {
            return new ConsumerGroupStats(groupName, topic, getActiveConsumerCount(),
                getActiveConsumerCount(), 0L, 0L, 0L, 0.0, 0.0,
                Instant.now(), Instant.now(), new HashMap<>());
        }

        @Override
        public void setGroupFilter(Predicate<Message<T>> groupFilter) {
            this.groupFilter = groupFilter;
        }

        @Override
        public Predicate<Message<T>> getGroupFilter() {
            return groupFilter;
        }

        @Override
        public void close() {
            logger.debug("Closing mock consumer group: {}", groupName);
            stop();
            consumerIds.clear();
        }
    }

    private static class MockConsumerGroupMember<T> implements ConsumerGroupMember<T> {
        private final String consumerId;
        private final MessageHandler<T> handler;
        private final Instant joinedAt;
        private Predicate<Message<T>> messageFilter;
        private boolean active = false;

        MockConsumerGroupMember(String consumerId, MessageHandler<T> handler) {
            this.consumerId = consumerId;
            this.handler = handler;
            this.joinedAt = Instant.now();
        }

        @Override
        public String getConsumerId() {
            return consumerId;
        }

        @Override
        public String getGroupName() {
            return "mock-group";
        }

        @Override
        public String getTopic() {
            return "mock-topic";
        }

        @Override
        public Instant getJoinedAt() {
            return joinedAt;
        }

        @Override
        public MessageHandler<T> getMessageHandler() {
            return handler;
        }

        @Override
        public Predicate<Message<T>> getMessageFilter() {
            return messageFilter;
        }

        @Override
        public void setMessageFilter(Predicate<Message<T>> messageFilter) {
            this.messageFilter = messageFilter;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void start() {
            logger.debug("Starting mock consumer member: {}", consumerId);
            active = true;
        }

        @Override
        public void stop() {
            logger.debug("Stopping mock consumer member: {}", consumerId);
            active = false;
        }

        @Override
        public ConsumerMemberStats getStats() {
            return new ConsumerMemberStats(consumerId, "mock-group", "mock-topic",
                active, 0L, 0L, 0L, 0.0, 0.0, joinedAt, Instant.now(), null);
        }

        @Override
        public Instant getLastActivity() {
            return Instant.now();
        }

        @Override
        public ProcessingState getProcessingState() {
            return active ? ProcessingState.IDLE : ProcessingState.STOPPED;
        }
    }
}
