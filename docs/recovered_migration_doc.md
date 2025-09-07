
# Migrating from Reactor-Core to Vert.x 5.x in PeeGeeQ Project

## Overview of Current Implementation

Your project currently uses Project Reactor (reactor-core) for handling concurrency and asynchronous operations. The main reactive components in your codebase are:

1. `PgQueue` interface that defines methods returning `Mono<Void>` and `Flux<T>`
2. Two implementations: `OutboxQueue` and `PgNativeQueue` that use reactor-core's reactive types

## Why Migrate to Vert.x?

Vert.x offers several advantages:

- A complete toolkit for building reactive applications, not just a reactive library
- Built-in support for PostgreSQL via the Vert.x PostgreSQL client
- Event-driven architecture that works well with PostgreSQL's LISTEN/NOTIFY
- Non-blocking I/O operations with a simple programming model
- Polyglot support if you need to expand beyond Java in the future

## Migration Strategy

### 1. Add Vert.x Dependencies

Update your `pom.xml` to include Vert.x dependencies:

```xml
<properties>
    <!-- Add Vert.x version property -->
    <vertx.version>4.4.5</vertx.version>
    <!-- Keep other properties -->
</properties>

<dependencyManagement>
    <dependencies>
        <!-- Replace reactor-core with Vert.x -->
        <dependency>
            <groupId>io.vertx</groupId>
            <artifactId>vertx-core</artifactId>
            <version>${vertx.version}</version>
        </dependency>
        <dependency>
            <groupId>io.vertx</groupId>
            <artifactId>vertx-pg-client</artifactId>
            <version>${vertx.version}</version>
        </dependency>
        <dependency>
            <groupId>io.vertx</groupId>
            <artifactId>vertx-sql-client</artifactId>
            <version>${vertx.version}</version>
        </dependency>
        <!-- Keep other dependencies -->
    </dependencies>
</dependencyManagement>
```

### 2. Refactor the PgQueue Interface

Replace reactor-core types with Vert.x types:

```java
package dev.mars.peegeeq.api;

import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;

/**
 * Core interface for the PostgreSQL Message Queue.
 * Defines operations for sending and receiving messages.
 */
public interface PgQueue<T> {
    
    /**
     * Sends a message to the queue.
     *
     * @param message The message to send
     * @return A Future that completes when the message is sent
     */
    Future<Void> send(T message);
    
    /**
     * Receives messages from the queue.
     *
     * @return A ReadStream of messages from the queue
     */
    ReadStream<T> receive();
    
    /**
     * Acknowledges that a message has been processed.
     *
     * @param messageId The ID of the message to acknowledge
     * @return A Future that completes when the message is acknowledged
     */
    Future<Void> acknowledge(String messageId);
    
    /**
     * Closes the queue connection.
     *
     * @return A Future that completes when the connection is closed
     */
    Future<Void> close();
}
```

### 3. Refactor the OutboxQueue Implementation

```java
package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.Message;
import dev.mars.peegeeq.api.PgQueue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Implementation of the PgQueue interface using the Outbox pattern.
 * This class provides a way to reliably send messages to other systems
 * by first storing them in a PostgreSQL database.
 */
public class OutboxQueue<T> implements PgQueue<T> {
    
    private final Vertx vertx;
    private final Pool pool;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final Class<T> messageType;
    
    /**
     * Creates a new OutboxQueue with the given parameters.
     *
     * @param vertx The Vertx instance
     * @param connectOptions The PostgreSQL connection options
     * @param poolOptions The pool options
     * @param objectMapper The object mapper for serializing and deserializing messages
     * @param tableName The name of the table to use for storing messages
     * @param messageType The class of the message payload
     */
    public OutboxQueue(Vertx vertx, PgConnectOptions connectOptions, PoolOptions poolOptions,
                       ObjectMapper objectMapper, String tableName, Class<T> messageType) {
        this.vertx = vertx;
        this.pool = Pool.pool(vertx, connectOptions, poolOptions);
        this.objectMapper = objectMapper;
        this.tableName = tableName;
        this.messageType = messageType;
    }
    
    @Override
    public Future<Void> send(T message) {
        // In a real implementation, this would serialize the message and store it in the database
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(v -> {
            // Placeholder for actual implementation
            System.out.println("Sending message: " + message);
            promise.complete();
        });
        return promise.future();
    }
    
    @Override
    public ReadStream<T> receive() {
        // In a real implementation, this would query the database for messages
        // For now, return an empty stream
        return new EmptyReadStream<>();
    }
    
    @Override
    public Future<Void> acknowledge(String messageId) {
        // In a real implementation, this would mark the message as processed in the database
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(v -> {
            // Placeholder for actual implementation
            System.out.println("Acknowledging message: " + messageId);
            promise.complete();
        });
        return promise.future();
    }
    
    @Override
    public Future<Void> close() {
        // In a real implementation, this would close the database connection
        return pool.close();
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
```

### 4. Refactor the PgNativeQueue Implementation

```java
package dev.mars.peegeeq.pg;

import dev.mars.peegeeq.api.Message;
import dev.mars.peegeeq.api.PgQueue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgNotification;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Implementation of the PgQueue interface using native PostgreSQL features.
 * This class provides a queue implementation using PostgreSQL's LISTEN/NOTIFY
 * mechanism and advisory locks for reliable message delivery.
 */
public class PgNativeQueue<T> implements PgQueue<T> {
    
    private final Vertx vertx;
    private final Pool pool;
    private final ObjectMapper objectMapper;
    private final String channelName;
    private final Class<T> messageType;
    private PgConnection listenConnection;
    
    /**
     * Creates a new PgNativeQueue with the given parameters.
     *
     * @param vertx The Vertx instance
     * @param connectOptions The PostgreSQL connection options
     * @param poolOptions The pool options
     * @param objectMapper The object mapper for serializing and deserializing messages
     * @param channelName The name of the LISTEN/NOTIFY channel to use
     * @param messageType The class of the message payload
     */
    public PgNativeQueue(Vertx vertx, PgConnectOptions connectOptions, PoolOptions poolOptions,
                         ObjectMapper objectMapper, String channelName, Class<T> messageType) {
        this.vertx = vertx;
        this.pool = Pool.pool(vertx, connectOptions, poolOptions);
        this.objectMapper = objectMapper;
        this.channelName = channelName;
        this.messageType = messageType;
    }
    
    @Override
    public Future<Void> send(T message) {
        // In a real implementation, this would serialize the message and use NOTIFY to send it
        return pool.getConnection()
            .compose(conn -> {
                return conn.query("NOTIFY " + channelName + ", '" + message + "'")
                    .execute()
                    .onComplete(ar -> conn.close())
                    .mapEmpty();
            });
    }
    
    @Override
    public ReadStream<T> receive() {
        // Create a custom ReadStream implementation that listens for notifications
        PgNotificationStream<T> stream = new PgNotificationStream<>(vertx, messageType, objectMapper);
        
        // Set up the LISTEN connection
        PgConnectOptions options = new PgConnectOptions(pool.options());
        PgConnection.connect(vertx, options)
            .onSuccess(conn -> {
                this.listenConnection = conn;
                conn.notificationHandler(notification -> {
                    if (channelName.equals(notification.getChannel())) {
                        try {
                            // Parse the notification payload and push to the stream
                            T message = objectMapper.readValue(notification.getPayload(), messageType);
                            stream.handleNotification(message);
                        } catch (Exception e) {
                            stream.handleError(e);
                        }
                    }
                });
                
                // Start listening on the channel
                conn.query("LISTEN " + channelName).execute()
                    .onFailure(err -> stream.handleError(err));
            })
            .onFailure(err -> stream.handleError(err));
        
        return stream;
    }
    
    @Override
    public Future<Void> acknowledge(String messageId) {
        // In a real implementation, this would release any advisory locks
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(v -> {
            // Placeholder for actual implementation
            System.out.println("Releasing advisory lock for message: " + messageId);
            promise.complete();
        });
        return promise.future();
    }
    
    @Override
    public Future<Void> close() {
        // Close the listen connection and the pool
        Promise<Void> promise = Promise.promise();
        
        if (listenConnection != null) {
            listenConnection.close()
                .compose(v -> pool.close())
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        promise.complete();
                    } else {
                        promise.fail(ar.cause());
                    }
                });
        } else {
            pool.close().onComplete(ar -> {
                if (ar.succeeded()) {
                    promise.complete();
                } else {
                    promise.fail(ar.cause());
                }
            });
        }
        
        return promise.future();
    }
    
    /**
     * Creates a new message with a random ID.
     *
     * @param payload The payload of the message
     * @return A new message
     */
    public Message<T> createMessage(T payload) {
        return new PgNativeMessage<>(UUID.randomUUID().toString(), payload);
    }
}
```

### 5. Create Helper Classes

You'll need to create some helper classes like `EmptyReadStream` and `PgNotificationStream`:

```java
// EmptyReadStream.java
import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

public class EmptyReadStream<T> implements ReadStream<T> {
    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        return this;
    }

    @Override
    public ReadStream<T> pause() {
        return this;
    }

    @Override
    public ReadStream<T> resume() {
        return this;
    }

    @Override
    public ReadStream<T> endHandler(Handler<Void> endHandler) {
        if (endHandler != null) {
            endHandler.handle(null);
        }
        return this;
    }
}

// PgNotificationStream.java
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PgNotificationStream<T> implements ReadStream<T> {
    private final Vertx vertx;
    private final Class<T> messageType;
    private final ObjectMapper objectMapper;
    
    private Handler<T> dataHandler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;
    private boolean paused = false;
    
    public PgNotificationStream(Vertx vertx, Class<T> messageType, ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.messageType = messageType;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> handler(Handler<T> handler) {
        this.dataHandler = handler;
        return this;
    }
    
    @Override
    public ReadStream<T> pause() {
        paused = true;
        return this;
    }
    
    @Override
    public ReadStream<T> resume() {
        paused = false;
        return this;
    }
    
    @Override
    public ReadStream<T> endHandler(Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }
    
    public void handleNotification(T message) {
        if (!paused && dataHandler != null) {
            vertx.runOnContext(v -> dataHandler.handle(message));
        }
    }
    
    public void handleError(Throwable error) {
        if (exceptionHandler != null) {
            vertx.runOnContext(v -> exceptionHandler.handle(error));
        }
    }
    
    public void handleEnd() {
        if (endHandler != null) {
            vertx.runOnContext(v -> endHandler.handle(null));
        }
    }
}
```

## Key Differences Between Reactor and Vert.x

1. **Reactive Types**:
   - Reactor: `Mono<T>` (0-1 items) and `Flux<T>` (0-n items)
   - Vert.x: `Future<T>` (0-1 items) and `ReadStream<T>` (0-n items)

2. **Execution Model**:
   - Reactor: Operator-based pipeline with backpressure
   - Vert.x: Event loop with handlers and futures

3. **Database Access**:
   - Reactor: Often used with R2DBC for reactive database access
   - Vert.x: Has its own reactive PostgreSQL client

## Additional Considerations

1. **Vertx Instance Management**: You'll need to create and manage a Vert.x instance. Typically, you'd have a single Vert.x instance for your application.

2. **Connection Pooling**: Vert.x has its own connection pooling mechanism, so you'll replace HikariCP with Vert.x's built-in pooling.

3. **Error Handling**: Vert.x uses a callback-based error handling approach with `Future.onSuccess()` and `Future.onFailure()` methods.

4. **Testing**: You'll need to adapt your tests to work with Vert.x's asynchronous model, possibly using Vert.x's test utilities.

5. **Deployment**: Vert.x applications can be deployed as verticles, which are the basic units of deployment in Vert.x.

## Conclusion

Migrating from Reactor to Vert.x involves changing your reactive types and adapting to Vert.x's event-driven programming model. The core business logic remains largely the same, but the way you handle asynchronous operations changes.

Vert.x's built-in PostgreSQL client and event-driven architecture make it well-suited for your use case, especially for the PgNativeQueue implementation that uses PostgreSQL's LISTEN/NOTIFY mechanism.