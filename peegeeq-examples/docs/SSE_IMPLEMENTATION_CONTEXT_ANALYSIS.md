# SSE Implementation Context Analysis
**Date**: 2025-11-06  
**Purpose**: Comprehensive analysis of PeeGeeQ codebase context for implementing SSE message streaming

---

## Executive Summary

This document provides a thorough analysis of the PeeGeeQ codebase to understand the context needed for implementing Server-Sent Events (SSE) message streaming in `ServerSentEventsHandler.java` (line 296 TODO).

**Key Findings**:
1. ✅ Consumer groups are **separate entities** - not a parameter to `createConsumer()`
2. ✅ `messageType` is stored in **message headers** (key: "messageType")
3. ⚠️ **No existing Last-Event-ID handling** - needs to be implemented
4. ✅ SSE event format already supports `id:` field for reconnection

---

## 1. Consumer vs Consumer Group Architecture

### Finding: Consumer Groups Are Separate Entities

**QueueFactory API**:
```java
// Create individual consumer (no consumer group parameter)
<T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType);

// Create consumer with configuration (mode: LISTEN_NOTIFY_ONLY, POLLING_ONLY, HYBRID)
<T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig);

// Create consumer group (separate entity)
<T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType);
```

**Source**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/QueueFactory.java` (lines 56-82)

### Consumer Group Pattern

Consumer groups work differently than individual consumers:

```java
// Consumer group creates an underlying consumer internally
ConsumerGroup<T> group = factory.createConsumerGroup("MyGroup", "orders", Order.class);

// Add members to the group
group.addConsumer("consumer-1", message -> {...});
group.addConsumer("consumer-2", message -> {...});

// Start the group (creates underlying consumer and distributes messages)
group.start();
```

**Source**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeConsumerGroup.java` (lines 164-186)

### Implication for SSE Implementation

The `consumerGroup` field in `SSEConnection` is **NOT used to create the consumer**. Instead:

**Option 1: Ignore Consumer Groups for SSE (Recommended for Phase 1)**
- Each SSE connection creates its own individual `MessageConsumer`
- Consumer group coordination happens at the REST API layer (separate from queue consumers)
- The `consumerGroup` field is metadata for tracking/monitoring

**Option 2: Use REST API Consumer Groups (Complex)**
- Use the existing `ConsumerGroup` and `ConsumerGroupMember` classes in `peegeeq-rest`
- Coordinate partition assignment across SSE connections
- Requires integration with `ConsumerGroupHandler.java`

**Recommendation**: Start with Option 1 (individual consumers per SSE connection). Consumer group coordination can be added later as an enhancement.

---

## 2. Message Type Storage

### Finding: messageType is Stored in Headers

**Evidence from QueueHandler.java**:
```java
// When sending a message via REST API
Map<String, String> headers = new HashMap<>();
String detectedType = request.detectMessageType();
headers.put("messageType", detectedType);  // ← Stored in headers!
```

**Source**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java` (line 348)

### Message Type Detection

The REST API automatically detects message type from payload structure:

```java
public String detectMessageType() {
    if (messageType != null) return messageType;  // Explicit type
    
    // Auto-detect from payload
    if (payload instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) payload;
        if (map.containsKey("eventType")) return "Event";
        if (map.containsKey("commandType")) return "Command";
        if (map.containsKey("orderId")) return "Order";
        // ... more detection logic
    }
    if (payload instanceof String) return "Text";
    if (payload instanceof Number) return "Numeric";
    return "Unknown";
}
```

**Source**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java` (lines 780-820)

### Accessing messageType in Consumer

```java
consumer.subscribe(message -> {
    // Get messageType from headers
    String messageType = message.getHeaders().get("messageType");
    
    // Apply filter
    if (connection.getFilters() != null) {
        String requiredType = connection.getFilters().getString("messageType");
        if (!requiredType.equals(messageType)) {
            return CompletableFuture.completedFuture(null); // Skip
        }
    }
    
    // Send to SSE client
    sendDataEvent(connection, message.getPayload(), message.getId(), 
                  new JsonObject(message.getHeaders()), messageType);
    
    return CompletableFuture.completedFuture(null);
});
```

### Implication for SSE Implementation

- ✅ `messageType` is available in `message.getHeaders().get("messageType")`
- ✅ Existing `SSEConnection.shouldSendMessage()` already checks messageType filter
- ✅ No changes needed to message structure

---

## 3. SSE Reconnection and Last-Event-ID

### Finding: No Existing Last-Event-ID Handling

**Current SSE Event Format** (ServerSentEventsHandler.java):
```java
private void sendSSEEvent(SSEConnection connection, String eventType, JsonObject data) {
    StringBuilder sseEvent = new StringBuilder();
    
    // Add event type
    if (eventType != null) {
        sseEvent.append("event: ").append(eventType).append("\n");
    }
    
    // Add data
    sseEvent.append("data: ").append(data.encode()).append("\n");
    
    // ⚠️ NO 'id:' field - needs to be added!
    
    sseEvent.append("\n");
    connection.getResponse().write(sseEvent.toString());
}
```

**Source**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ServerSentEventsHandler.java` (lines 164-193)

### SSE Standard Reconnection Mechanism

**How SSE Last-Event-ID Works**:
1. Server sends events with `id:` field
2. Client stores last received ID
3. On reconnect, browser automatically sends `Last-Event-ID` header
4. Server resumes from that position

**Standard SSE Format**:
```
event: message
id: msg-12345-67890
data: {"messageId":"msg-12345-67890","payload":{...}}

```

### What Needs to Be Implemented

**1. Add `id:` field to SSE events**:
```java
private void sendSSEEvent(SSEConnection connection, String eventType, 
                         JsonObject data, String messageId) {
    StringBuilder sseEvent = new StringBuilder();
    
    if (eventType != null) {
        sseEvent.append("event: ").append(eventType).append("\n");
    }
    
    // Add message ID for reconnection
    if (messageId != null) {
        sseEvent.append("id: ").append(messageId).append("\n");
    }
    
    sseEvent.append("data: ").append(data.encode()).append("\n\n");
    connection.getResponse().write(sseEvent.toString());
}
```

**2. Handle Last-Event-ID header on connection**:
```java
public void handleQueueStream(RoutingContext ctx) {
    // ... existing code ...
    
    // Check for Last-Event-ID header (SSE reconnection)
    String lastEventId = ctx.request().getHeader("Last-Event-ID");
    if (lastEventId != null) {
        connection.setLastEventId(lastEventId);
        logger.info("SSE reconnection detected, Last-Event-ID: {}", lastEventId);
    }
    
    // ... continue with streaming ...
}
```

**3. Resume from last position** (Complex - requires queue position tracking):
```java
// Option A: Skip messages until we reach lastEventId
consumer.subscribe(message -> {
    if (connection.getLastEventId() != null) {
        if (!message.getId().equals(connection.getLastEventId())) {
            return CompletableFuture.completedFuture(null); // Skip
        } else {
            connection.setLastEventId(null); // Found it, resume normal processing
        }
    }
    // ... normal processing ...
});

// Option B: Use consumer seek (if available)
if (lastEventId != null) {
    consumer.seekToMessageId(lastEventId); // ⚠️ Not currently available in API
}
```

### Challenges with Last-Event-ID

**Problem**: PeeGeeQ consumers don't support seeking to a specific message ID

**Current Consumer API**:
```java
public interface MessageConsumer<T> {
    void subscribe(MessageHandler<T> handler);  // No seek method
    void unsubscribe();
    void close();
}
```

**Workaround Options**:

1. **Skip-Until Pattern** (Simple, works now):
   - Track last sent message ID in SSEConnection
   - Skip messages until we find the last sent ID
   - Resume normal processing after that
   - ⚠️ Inefficient if many messages were sent during disconnect

2. **Database Query Pattern** (Better, requires changes):
   - Query messages WHERE id > lastEventId
   - Send missed messages first
   - Then subscribe to new messages
   - ✅ Efficient, no wasted processing

3. **Consumer Seek API** (Best, requires API changes):
   - Add `seekToMessageId(String messageId)` to MessageConsumer
   - Implement in PgNativeQueueConsumer
   - Use in SSE handler
   - ✅ Clean, efficient, reusable

**Recommendation for Phase 1**: Use Skip-Until Pattern (Option 1) to get basic reconnection working. Plan for Option 2 or 3 in future phases.

---

## 4. Message Processing Flow

### Complete Flow from Consumer to SSE Client

```
1. Consumer subscribes
   └─> consumer.subscribe(handler)

2. Message arrives (LISTEN/NOTIFY or polling)
   └─> handler.handle(message) called

3. Handler processes message
   ├─> Apply filters (messageType, headers)
   ├─> Check if should send (connection.shouldSendMessage())
   └─> Send to SSE client (sendDataEvent())

4. Send SSE event
   ├─> Format SSE event (event:, id:, data:)
   ├─> Write to HTTP response
   └─> Update connection stats

5. Return CompletableFuture
   └─> CompletableFuture.completedFuture(null)
```

### Batching Flow

If `batchSize > 1`:

```
1. Accumulate messages in buffer
   └─> List<Message> batch = new ArrayList<>();

2. When batch is full OR timeout
   ├─> Send batch event (sendBatchEvent())
   └─> Clear buffer

3. Batch event format
   event: batch
   id: msg-last-in-batch
   data: {"messages":[...], "count":10}
```

---

## 5. Implementation Checklist

### Phase 1: Basic Streaming (No Reconnection)

- [ ] Subscribe to consumer in `startMessageStreaming()`
- [ ] Implement message handler
  - [ ] Get messageType from headers
  - [ ] Apply filters using `connection.shouldSendMessage()`
  - [ ] Send individual messages using `sendDataEvent()`
  - [ ] Return `CompletableFuture.completedFuture(null)`
- [ ] Handle errors in message processing
- [ ] Test with simple producer/consumer

### Phase 2: Add Reconnection Support

- [ ] Add `id:` field to `sendSSEEvent()`
- [ ] Parse `Last-Event-ID` header in `handleQueueStream()`
- [ ] Store last sent message ID in `SSEConnection`
- [ ] Implement skip-until pattern in message handler
- [ ] Test reconnection scenarios

### Phase 3: Add Batching

- [ ] Implement message buffering
- [ ] Add batch timeout timer
- [ ] Send batch events when full or timeout
- [ ] Test batching with different batch sizes

### Phase 4: Consumer Groups (Optional)

- [ ] Integrate with REST API consumer groups
- [ ] Handle partition assignment
- [ ] Coordinate across SSE connections
- [ ] Test multi-consumer scenarios

---

## 6. Code Examples

### Example 1: Basic Message Streaming

```java
private void startMessageStreaming(SSEConnection connection) {
    // ... existing setup code ...
    
    MessageConsumer<Object> consumer = queueFactory.createConsumer(
        connection.getQueueName(), Object.class);
    connection.setConsumer(consumer);
    
    // Subscribe to messages
    consumer.subscribe(message -> {
        try {
            // Get messageType from headers
            String messageType = message.getHeaders() != null ? 
                message.getHeaders().get("messageType") : "Unknown";
            
            // Convert headers to JsonObject
            JsonObject headers = message.getHeaders() != null ?
                new JsonObject(message.getHeaders()) : new JsonObject();
            
            // Apply filters
            if (!connection.shouldSendMessage(message.getPayload(), headers, messageType)) {
                return CompletableFuture.completedFuture(null);
            }
            
            // Send to SSE client
            sendDataEvent(connection, message.getPayload(), message.getId(), 
                         headers, messageType);
            
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            logger.error("Error processing message for SSE connection {}: {}", 
                        connection.getConnectionId(), e.getMessage(), e);
            sendErrorEvent(connection, "Error processing message: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    });
    
    // ... rest of setup ...
}
```

### Example 2: With Reconnection Support

```java
// In handleQueueStream()
String lastEventId = ctx.request().getHeader("Last-Event-ID");
if (lastEventId != null) {
    connection.setResumeFromMessageId(lastEventId);
    logger.info("SSE reconnection: resuming from message {}", lastEventId);
}

// In message handler
consumer.subscribe(message -> {
    // Handle reconnection
    String resumeFrom = connection.getResumeFromMessageId();
    if (resumeFrom != null) {
        if (!message.getId().equals(resumeFrom)) {
            // Skip messages until we find the resume point
            return CompletableFuture.completedFuture(null);
        } else {
            // Found resume point, clear it and continue
            connection.setResumeFromMessageId(null);
            logger.info("Resumed SSE stream at message {}", message.getId());
        }
    }
    
    // ... normal processing ...
});
```

---

## 7. Complete Implementation Guide

### Phase 1: Basic Message Streaming (No Reconnection, No Batching)

**File**: `ServerSentEventsHandler.java` (line 275)

**Implementation**:

```java
private void startMessageStreaming(SSEConnection connection) {
    try {
        // Create consumer for streaming
        MessageConsumer<Object> consumer = queueFactory.createConsumer(
            connection.getQueueName(), Object.class);
        connection.setConsumer(consumer);

        // Subscribe to messages
        consumer.subscribe(message -> {
            try {
                // Get messageType from headers
                String messageType = message.getHeaders() != null ?
                    message.getHeaders().get("messageType") : "Unknown";

                // Convert headers to JsonObject
                JsonObject headersJson = headersToJsonObject(message.getHeaders());

                // Apply filters using existing method
                if (!connection.shouldSendMessage(message.getPayload(), headersJson, messageType)) {
                    return CompletableFuture.completedFuture(null);
                }

                // Send to SSE client (payload is already deserialized)
                sendDataEvent(connection, message.getPayload(), message.getId(),
                             headersJson, messageType);

                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing message for SSE connection {}: {}",
                            connection.getConnectionId(), e.getMessage(), e);
                sendErrorEvent(connection, "Error processing message: " + e.getMessage());
                return CompletableFuture.failedFuture(e);
            }
        });

        // Start heartbeat timer to keep connection alive
        startHeartbeatTimer(connection);

        logger.info("Started message streaming for SSE connection {} on queue {}",
                   connection.getConnectionId(), connection.getQueueName());

    } catch (Exception e) {
        logger.error("Failed to start message streaming for SSE connection {}: {}",
                    connection.getConnectionId(), e.getMessage(), e);
        sendErrorEvent(connection, "Failed to start streaming: " + e.getMessage());
        connection.cleanup();
    }
}

/**
 * Convert headers map to JsonObject for SSE events.
 */
private JsonObject headersToJsonObject(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) return new JsonObject();
    // Convert Map<String, String> to Map<String, Object> for JsonObject constructor
    Map<String, Object> objectMap = new java.util.HashMap<>(headers);
    return new JsonObject(objectMap);
}
```

**Testing**:
1. Create a producer and send messages to a queue
2. Open SSE connection to `/api/queues/{queueName}/stream`
3. Verify messages are received in SSE format
4. Test with filters (messageType, headers)
5. Test connection cleanup on close

---

### Phase 2: Add Reconnection Support

**Changes Needed**:

**1. Update `sendSSEEvent()` to include message ID** (line 164):

```java
private void sendSSEEvent(SSEConnection connection, String eventType, JsonObject data) {
    sendSSEEvent(connection, eventType, data, null);
}

private void sendSSEEvent(SSEConnection connection, String eventType, JsonObject data, String messageId) {
    StringBuilder sseEvent = new StringBuilder();

    // Add event type
    if (eventType != null) {
        sseEvent.append("event: ").append(eventType).append("\n");
    }

    // Add message ID for reconnection (SSE standard)
    if (messageId != null) {
        sseEvent.append("id: ").append(messageId).append("\n");
    }

    // Add data
    sseEvent.append("data: ").append(data.encode()).append("\n");

    // Empty line to complete the event
    sseEvent.append("\n");

    connection.getResponse().write(sseEvent.toString());
}
```

**2. Update `sendDataEvent()` to pass message ID** (line 198):

```java
private void sendDataEvent(SSEConnection connection, Object payload, String messageId,
                          JsonObject headers, String messageType) {
    JsonObject dataEvent = new JsonObject()
        .put("type", "data")
        .put("connectionId", connection.getConnectionId())
        .put("messageId", messageId)
        .put("payload", payload)
        .put("messageType", messageType)
        .put("timestamp", System.currentTimeMillis());

    if (headers != null && !headers.isEmpty()) {
        dataEvent.put("headers", headers);
    }

    // Pass messageId to sendSSEEvent for SSE 'id:' field
    sendSSEEvent(connection, "message", dataEvent, messageId);
    connection.incrementMessagesReceived();
}
```

**3. Add Last-Event-ID handling in `handleQueueStream()`** (line 50):

```java
public void handleQueueStream(RoutingContext ctx) {
    // ... existing code ...

    // Check for Last-Event-ID header (SSE reconnection)
    String lastEventId = ctx.request().getHeader("Last-Event-ID");
    if (lastEventId != null) {
        connection.setResumeFromMessageId(lastEventId);
        logger.info("SSE reconnection detected for connection {}, Last-Event-ID: {}",
                   connection.getConnectionId(), lastEventId);
    }

    // ... continue with streaming ...
}
```

**4. Add resume logic to message handler**:

```java
consumer.subscribe(message -> {
    try {
        // Handle reconnection - skip messages until we reach resume point
        String resumeFrom = connection.getResumeFromMessageId();
        if (resumeFrom != null) {
            if (!message.getId().equals(resumeFrom)) {
                // Skip this message, we haven't reached the resume point yet
                return CompletableFuture.completedFuture(null);
            } else {
                // Found the resume point, clear it and continue from next message
                connection.setResumeFromMessageId(null);
                logger.info("Resumed SSE stream for connection {} at message {}",
                           connection.getConnectionId(), message.getId());
                // Skip this message (already sent before disconnect)
                return CompletableFuture.completedFuture(null);
            }
        }

        // ... normal processing ...
    } catch (Exception e) {
        // ... error handling ...
    }
});
```

**5. Add field to SSEConnection**:

```java
private String resumeFromMessageId;

public String getResumeFromMessageId() {
    return resumeFromMessageId;
}

public void setResumeFromMessageId(String resumeFromMessageId) {
    this.resumeFromMessageId = resumeFromMessageId;
}
```

**Testing**:
1. Start SSE connection and receive messages
2. Disconnect client
3. Reconnect with Last-Event-ID header
4. Verify messages resume from correct position
5. Test with no Last-Event-ID (new connection)

---

### Phase 3: Add Batching Support

**Implementation** (deferred to later phase):
- Add message buffer to SSEConnection
- Add batch timeout timer
- Accumulate messages in buffer
- Send batch when full or timeout expires
- Use `sendBatchEvent()` method

---

## 8. Next Steps

1. ✅ **Context investigation complete** - All necessary information gathered
2. **Implement Phase 1** - Basic message streaming
3. **Test Phase 1** - Verify basic streaming works
4. **Implement Phase 2** - Add reconnection support
5. **Test Phase 2** - Verify reconnection works
6. **Update documentation** - Document SSE API for clients
7. **Move to Stage 1, Phase 2** - Create client examples

---

## 8. Header Conversion Utilities

### Map<String, String> to JsonObject

**Pattern from codebase**:
```java
private JsonObject headersToJsonObject(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) return new JsonObject();
    // Convert Map<String, String> to Map<String, Object> for JsonObject constructor
    Map<String, Object> objectMap = new java.util.HashMap<>(headers);
    return new JsonObject(objectMap);
}
```

**Source**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueProducer.java` (lines 113-118)

### JsonObject to Map<String, String>

**Pattern from codebase**:
```java
private Map<String, String> parseHeadersFromJsonObject(JsonObject headers) {
    if (headers == null || headers.isEmpty()) return new HashMap<>();

    Map<String, String> result = new HashMap<>();
    for (String key : headers.fieldNames()) {
        Object value = headers.getValue(key);
        result.put(key, value != null ? value.toString() : null);
    }
    return result;
}
```

**Source**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java` (lines 1165-1174)

### Usage in SSE Handler

```java
consumer.subscribe(message -> {
    // Message headers are Map<String, String>
    Map<String, String> headers = message.getHeaders();

    // Convert to JsonObject for SSE event
    JsonObject headersJson = headersToJsonObject(headers);

    // Get messageType from headers
    String messageType = headers != null ? headers.get("messageType") : "Unknown";

    // Send to SSE client
    sendDataEvent(connection, message.getPayload(), message.getId(),
                 headersJson, messageType);

    return CompletableFuture.completedFuture(null);
});
```

---

## 9. Critical Implementation Notes

### 1. Don't Use JsonObject.mapTo() for Complex Objects

**Problem**: Vert.x's internal ObjectMapper doesn't handle JSR310 types (LocalDateTime, Instant) correctly.

**Solution**: Use the configured ObjectMapper from the factory:
```java
// ❌ Wrong - uses Vert.x's ObjectMapper
T payload = jsonObject.mapTo(payloadType);

// ✅ Correct - uses configured ObjectMapper
String jsonString = jsonObject.encode();
T payload = objectMapper.readValue(jsonString, payloadType);
```

**Source**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java` (lines 1148-1160)

### 2. Message Payload is Already Deserialized

When you receive a `Message<Object>` from the consumer, the payload is already deserialized:

```java
consumer.subscribe(message -> {
    Object payload = message.getPayload();  // Already deserialized!

    // For SSE, we can pass it directly to sendDataEvent()
    // The method signature is: sendDataEvent(connection, Object payload, ...)
    // JsonObject.put("payload", payload) handles the conversion automatically

    sendDataEvent(connection, payload, message.getId(), headersJson, messageType);
});
```

**How JsonObject.put() Handles Object Values**:

```java
// In sendDataEvent():
JsonObject dataEvent = new JsonObject()
    .put("payload", payload);  // ← Handles any Object type!

// JsonObject.put() automatically handles:
// - Primitives (String, Number, Boolean) → stored as-is
// - JsonObject → stored as-is
// - JsonArray → stored as-is
// - Map → converted to JsonObject
// - List → converted to JsonArray
// - Complex objects → uses JsonObject.mapFrom() internally
```

**Source**: Vert.x JsonObject implementation

**Important**: The existing `sendDataEvent()` method already accepts `Object payload`, so we can pass the message payload directly without conversion!

### 3. SSE Event Format Must Include id: Field

**Standard SSE format**:
```
event: message
id: msg-12345-67890
data: {"messageId":"msg-12345-67890","payload":{...}}

```

**Note**: The empty line at the end is REQUIRED to complete the event.

---

## 10. Implementation Decision Summary

Based on the investigation, here are the key decisions for Phase 1:

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Consumer Groups** | Use individual consumers (ignore consumerGroup field) | Consumer groups are separate entities, not a parameter to createConsumer() |
| **Message Type** | Read from `message.getHeaders().get("messageType")` | messageType is stored in headers by REST API |
| **Reconnection** | Implement skip-until pattern | No seek API available, skip-until is simplest working solution |
| **Batching** | Defer to Phase 3 | Focus on basic streaming first |
| **Header Conversion** | Use established patterns from codebase | `headersToJsonObject()` and `parseHeadersFromJsonObject()` |
| **Payload Handling** | Payload is already deserialized | No need to parse, just convert to JSON for SSE |
| **SSE Event ID** | Use `message.getId()` | Unique message ID from database |

---

## References

- **QueueFactory API**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/QueueFactory.java`
- **MessageConsumer**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/MessageConsumer.java`
- **Message Interface**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/Message.java`
- **SSE Handler**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ServerSentEventsHandler.java`
- **SSE Connection**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SSEConnection.java`
- **Native Consumer**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`
- **Native Producer**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueProducer.java`
- **Outbox Consumer**: `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java`
- **Implementation Plan**: `peegeeq-examples/docs/STREAMING_EXAMPLES_IMPLEMENTATION_PLAN.md`

