package dev.mars.peegeeq.db.test;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Mock queue factory implementation for testing purposes only.
 * This factory provides no-op implementations for all operations.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-08
 * @version 1.0
 */
public class MockQueueFactory implements QueueFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(MockQueueFactory.class);
    private final DatabaseService databaseService;
    private volatile boolean closed = false;
    
    public MockQueueFactory(DatabaseService databaseService) {
        this.databaseService = databaseService;
        logger.debug("Created mock queue factory for testing");
    }
    
    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        checkNotClosed();
        return new MockMessageProducer<>(topic, payloadType);
    }
    
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
        checkNotClosed();
        return new MockMessageConsumer<>(topic, payloadType);
    }
    
    @Override
    public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
        checkNotClosed();
        return new MockConsumerGroup<>(groupName, topic, payloadType);
    }
    
    @Override
    public String getImplementationType() {
        return "mock";
    }
    
    @Override
    public boolean isHealthy() {
        return !closed && databaseService != null;
    }
    
    @Override
    public void close() throws Exception {
        if (!closed) {
            closed = true;
            logger.debug("Closed mock queue factory");
        }
    }
    
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Mock queue factory is closed");
        }
    }
    
    /**
     * Mock message producer that does nothing.
     */
    private static class MockMessageProducer<T> implements MessageProducer<T> {
        private final String topic;
        private final Class<T> payloadType;
        private volatile boolean closed = false;

        MockMessageProducer(String topic, Class<T> payloadType) {
            this.topic = topic;
            this.payloadType = payloadType;
        }

        @Override
        public CompletableFuture<Void> send(T payload) {
            checkNotClosed();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(T payload, Map<String, String> headers) {
            checkNotClosed();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId) {
            checkNotClosed();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup) {
            checkNotClosed();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        private void checkNotClosed() {
            if (closed) {
                throw new IllegalStateException("Mock producer is closed");
            }
        }
    }
    
    /**
     * Mock message consumer that does nothing.
     */
    private static class MockMessageConsumer<T> implements MessageConsumer<T> {
        private final String topic;
        private final Class<T> payloadType;
        private volatile boolean closed = false;

        MockMessageConsumer(String topic, Class<T> payloadType) {
            this.topic = topic;
            this.payloadType = payloadType;
        }

        @Override
        public void subscribe(MessageHandler<T> handler) {
            checkNotClosed();
            // No-op for mock
        }

        @Override
        public void unsubscribe() {
            checkNotClosed();
            // No-op for mock
        }

        @Override
        public void close() {
            closed = true;
        }

        private void checkNotClosed() {
            if (closed) {
                throw new IllegalStateException("Mock consumer is closed");
            }
        }
    }
    
    /**
     * Mock consumer group that does nothing.
     */
    private static class MockConsumerGroup<T> implements ConsumerGroup<T> {
        private final String groupName;
        private final String topic;
        private final Class<T> payloadType;
        private volatile boolean closed = false;
        private final Set<String> consumerIds = ConcurrentHashMap.newKeySet();
        private Predicate<Message<T>> groupFilter;

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
            checkNotClosed();
            consumerIds.add(consumerId);
            return new MockConsumerGroupMember<>(consumerId);
        }

        @Override
        public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> messageFilter) {
            checkNotClosed();
            consumerIds.add(consumerId);
            return new MockConsumerGroupMember<>(consumerId);
        }

        @Override
        public boolean removeConsumer(String consumerId) {
            checkNotClosed();
            return consumerIds.remove(consumerId);
        }

        @Override
        public Set<String> getConsumerIds() {
            return Set.copyOf(consumerIds);
        }

        @Override
        public int getActiveConsumerCount() {
            return consumerIds.size();
        }

        @Override
        public void start() {
            checkNotClosed();
            // No-op for mock
        }

        @Override
        public void stop() {
            checkNotClosed();
            // No-op for mock
        }

        @Override
        public boolean isActive() {
            return !closed;
        }

        @Override
        public ConsumerGroupStats getStats() {
            return new ConsumerGroupStats(
                groupName, topic, consumerIds.size(), consumerIds.size(),
                0, 0, 0, 0.0, 0.0,
                Instant.now(), Instant.now(), new HashMap<>()
            );
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
            closed = true;
        }

        private void checkNotClosed() {
            if (closed) {
                throw new IllegalStateException("Mock consumer group is closed");
            }
        }
    }

    /**
     * Mock consumer group member.
     */
    private static class MockConsumerGroupMember<T> implements ConsumerGroupMember<T> {
        private final String consumerId;
        private final String groupName;
        private final String topic;
        private volatile boolean closed = false;
        private final Instant createdAt = Instant.now();

        MockConsumerGroupMember(String consumerId) {
            this.consumerId = consumerId;
            this.groupName = "mock-group";
            this.topic = "mock-topic";
        }

        @Override
        public String getConsumerId() {
            return consumerId;
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
        public boolean isActive() {
            return !closed;
        }

        @Override
        public void start() {
            // No-op for mock
        }

        @Override
        public void stop() {
            // No-op for mock
        }

        @Override
        public ConsumerMemberStats getStats() {
            return new ConsumerMemberStats(
                consumerId, groupName, topic, !closed, 0, 0, 0, 0.0, 0.0,
                createdAt, createdAt, null
            );
        }

        @Override
        public Instant getLastActivity() {
            return createdAt;
        }

        @Override
        public ProcessingState getProcessingState() {
            return ProcessingState.IDLE;
        }

        @Override
        public MessageHandler<T> getMessageHandler() {
            return message -> CompletableFuture.completedFuture(null);
        }

        @Override
        public Predicate<Message<T>> getMessageFilter() {
            return message -> true;
        }

        @Override
        public void setMessageFilter(Predicate<Message<T>> messageFilter) {
            // No-op for mock
        }

        @Override
        public Instant getJoinedAt() {
            return createdAt;
        }
    }
}
