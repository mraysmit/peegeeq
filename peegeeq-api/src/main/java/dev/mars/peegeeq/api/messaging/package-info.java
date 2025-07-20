/**
 * Messaging and queue management contracts for the PeeGeeQ message queue system.
 *
 * <p>This package contains all interfaces for message production, consumption,
 * queue management, consumer groups, and message filtering in the PostgreSQL-based
 * message queue system. It provides clean abstractions that hide implementation
 * details while offering production-ready messaging capabilities.</p>
 *
 * <h2>Core Messaging:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.messaging.Message} - Core message abstraction</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageProducer} - For publishing messages</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageConsumer} - For consuming messages</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageHandler} - Message processing callback</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageFilter} - Message filtering utilities</li>
 * </ul>
 *
 * <h2>Queue Management:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.messaging.QueueFactory} - Factory for creating queues</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.ConsumerGroup} - Consumer group management</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.ConsumerGroupMember} - Individual consumer in group</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.ConsumerGroupStats} - Consumer group statistics</li>
 * </ul>
 *
 * <h2>Reference Implementations:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.messaging.SimpleMessage} - Basic message implementation</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create a queue factory
 * QueueFactory factory = queueFactoryProvider.createFactory("native", databaseService);
 *
 * // Create a producer
 * MessageProducer<String> producer = factory.createProducer("my-topic", String.class);
 *
 * // Send a message
 * producer.send("Hello, World!").join();
 *
 * // Create a consumer group
 * ConsumerGroup<String> group = factory.createConsumerGroup("my-group", "my-topic", String.class);
 *
 * // Add members to the group
 * group.addConsumer("consumer-1", message -> {
 *     System.out.println("Consumer 1: " + message.getPayload());
 *     return CompletableFuture.completedFuture(null);
 * });
 * }</pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 1.0
 * @version 1.0
 */
package dev.mars.peegeeq.api.messaging;
