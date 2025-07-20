/**
 * Core messaging contracts for the PeeGeeQ message queue system.
 * 
 * <p>This package contains the fundamental interfaces for message production,
 * consumption, and handling in the PostgreSQL-based message queue system.
 * It provides clean abstractions that hide implementation details while
 * offering production-ready messaging capabilities.</p>
 * 
 * <h2>Key Interfaces:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageProducer} - For publishing messages</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageConsumer} - For consuming messages</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.Message} - Core message abstraction</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageHandler} - Message processing callback</li>
 *   <li>{@link dev.mars.peegeeq.api.messaging.MessageFilter} - Message filtering utilities</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create a producer
 * MessageProducer<String> producer = queueFactory.createProducer("my-topic", String.class);
 * 
 * // Send a message
 * producer.send("Hello, World!").join();
 * 
 * // Create a consumer
 * MessageConsumer<String> consumer = queueFactory.createConsumer("my-topic", String.class);
 * 
 * // Subscribe to messages
 * consumer.subscribe(message -> {
 *     System.out.println("Received: " + message.getPayload());
 * });
 * }</pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 1.0
 * @version 1.0
 */
package dev.mars.peegeeq.api.messaging;
