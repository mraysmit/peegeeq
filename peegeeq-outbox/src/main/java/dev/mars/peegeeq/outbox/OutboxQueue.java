package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.Message;
import dev.mars.peegeeq.api.PgQueue;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Implementation of the PgQueue interface using the Outbox pattern.
 * This class provides a way to reliably send messages to other systems
 * by first storing them in a PostgreSQL database.
 */
public class OutboxQueue<T> implements PgQueue<T> {
    
    private final HikariDataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final Class<T> messageType;
    
    /**
     * Creates a new OutboxQueue with the given parameters.
     *
     * @param dataSource The data source for connecting to the PostgreSQL database
     * @param objectMapper The object mapper for serializing and deserializing messages
     * @param tableName The name of the table to use for storing messages
     * @param messageType The class of the message payload
     */
    public OutboxQueue(HikariDataSource dataSource, ObjectMapper objectMapper, 
                       String tableName, Class<T> messageType) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
        this.messageType = messageType;
    }
    
    @Override
    public Mono<Void> send(T message) {
        // In a real implementation, this would serialize the message and store it in the database
        return Mono.fromRunnable(() -> {
            // Placeholder for actual implementation
            System.out.println("Sending message: " + message);
        });
    }
    
    @Override
    public Flux<T> receive() {
        // In a real implementation, this would query the database for messages
        return Flux.empty();
    }
    
    @Override
    public Mono<Void> acknowledge(String messageId) {
        // In a real implementation, this would mark the message as processed in the database
        return Mono.fromRunnable(() -> {
            // Placeholder for actual implementation
            System.out.println("Acknowledging message: " + messageId);
        });
    }
    
    @Override
    public Mono<Void> close() {
        // In a real implementation, this would close the database connection
        return Mono.fromRunnable(() -> {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        });
    }
    
    /**
     * Creates a new message with a random ID.
     *
     * @param payload The payload of the message
     * @return A new message
     */
    public Message<T> createMessage(T payload) {
        return new OutboxMessage<>(UUID.randomUUID().toString(), payload);
    }
}