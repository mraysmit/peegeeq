# Streaming Consumer Examples - Complete Implementation Plan

## ⚠️ Current Status: PLANNED - NOT YET IMPLEMENTED

**Last Updated**: 2025-11-06
**Review Date**: 2025-11-06

### Implementation Status
- ❌ **None of the 6 example classes exist yet**
- ✅ Backend infrastructure (WebSocketHandler, ServerSentEventsHandler) is partially implemented
- ✅ Test simulation exists (RestApiStreamingExampleTest.java) but uses mocked streaming
- ⚠️ Backend streaming has TODO items that need completion before examples can be fully functional

### Prerequisites
Before implementing these examples, the following backend work must be completed:
1. Complete actual message streaming in `WebSocketHandler.java` (line 268 TODO)
2. Complete actual message streaming in `ServerSentEventsHandler.java` (line 296 TODO)
3. Implement consumer group coordination for streaming
4. Implement message filtering and batching in streaming handlers

## Executive Summary

Implement streaming capabilities for PeeGeeQ in **2 stages** with multiple phases each:
- **Stage 1**: Server-Sent Events (SSE) - simpler unidirectional streaming
- **Stage 2**: WebSocket - bidirectional streaming with advanced features
- **Stage 3**: Documentation and polish

Each stage includes both **backend implementation** (peegeeq-rest module) and **client examples** (peegeeq-examples module).

**Total Estimated Time**: 25-34 hours
**Total Deliverables**:
- 2 backend handlers (ServerSentEventsHandler, WebSocketHandler)
- 7 client example classes
- pom.xml updates
- comprehensive documentation

## Quick Reference

### Stage 1: Server-Sent Events (SSE)

| # | Component | Type | Complexity | Time | Status |
|---|-----------|------|-----------|------|--------|
| 1.1 | ServerSentEventsHandler (backend) | Server | Medium | 3-4h | ❌ Not Started |
| 1.2 | ServerSentEventsConsumerExample | Client | Low | 2-3h | ❌ Not Started |
| 1.3 | SSE Filtering Example | Client | Medium | 1-2h | ❌ Not Started |
| 1.4 | SSE Connection Management Example | Client | Medium | 1-2h | ❌ Not Started |
| 1.5 | SSE Error Handling Example | Client | Medium | 2h | ❌ Not Started |

**Stage 1 Total**: 9-13 hours

### Stage 2: WebSocket

| # | Component | Type | Complexity | Time | Status |
|---|-----------|------|-----------|------|--------|
| 2.1 | WebSocketHandler (backend) | Server | Medium | 3-4h | ❌ Not Started |
| 2.2 | WebSocketConsumerExample | Client | Low | 2-3h | ❌ Not Started |
| 2.3 | WebSocket Filtering Example | Client | Medium | 1-2h | ❌ Not Started |
| 2.4 | WebSocket Connection Management Example | Client | Medium | 1-2h | ❌ Not Started |
| 2.5 | WebSocket Error Handling Example | Client | Medium | 2h | ❌ Not Started |
| 2.6 | RestApiStreamingExample (integrated) | Client | High | 4-5h | ❌ Not Started |

**Stage 2 Total**: 13-17 hours

### Stage 3: Documentation

| # | Component | Type | Time | Status |
|---|-----------|------|------|--------|
| 3.1 | Documentation Updates | Docs | 3-4h | ❌ Not Started |

**Overall Total**: 25-34 hours (includes backend + client + docs)

## Module Architecture

### Client vs Server Separation

**These examples are CLIENT-SIDE consumers** that connect to the PeeGeeQ REST API server.

| Component | Module | Role | Location |
|-----------|--------|------|----------|
| **Examples (Client)** | `peegeeq-examples` | HTTP/WebSocket/SSE **consumers** | `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/` |
| **REST API (Server)** | `peegeeq-rest` | HTTP/WebSocket/SSE **server** | `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/` |

**What goes where:**
- ✅ **peegeeq-examples**: Client code that connects TO the API (WebSocketClient, HttpClient, WebClient)
- ✅ **peegeeq-rest**: Server code that handles requests FROM clients (WebSocketHandler, ServerSentEventsHandler)

**Important**: The 6 examples in this plan are **client implementations**. They use Vert.x WebClient/WebSocketClient to connect to the server endpoints implemented in peegeeq-rest.

## API Reference

### Endpoints (Implemented in peegeeq-rest module)
- **WebSocket**: `ws://localhost:8080/ws/queues/{setupId}/{queueName}`
- **SSE**: `GET /api/v1/queues/{setupId}/{queueName}/stream`

### Message Format (JSON)
```json
{
  "type": "data|welcome|configured|error|heartbeat",
  "connectionId": "ws-123|sse-456",
  "messageId": "msg-789",
  "payload": { /* business data */ },
  "messageType": "OrderCreated",
  "timestamp": 1234567890,
  "headers": { "source": "order-service" }
}
```

### Query Parameters
- `consumerGroup`: Consumer group for load balancing
- `batchSize`: Messages per batch (1-100, default: 1)
- `maxWaitTime`: Max wait in milliseconds (1000-60000, default: 5000)
- `messageType`: Filter by message type
- `headers.*`: Filter by header values

## Implementation Stages

This implementation is split into **2 stages** with multiple phases each, allowing for incremental delivery and testing.

### Visual Overview

```
STAGE 1: SSE (9-13 hours)
├── Phase 1: Backend (3-4h)
│   └── ServerSentEventsHandler.java (peegeeq-rest)
├── Phase 2: Basic Client (2-3h)
│   └── ServerSentEventsConsumerExample.java (peegeeq-examples)
└── Phase 3: Advanced Clients (4-6h)
    ├── SSE Filtering Example
    ├── SSE Connection Management Example
    └── SSE Error Handling Example

STAGE 2: WebSocket (13-17 hours)
├── Phase 1: Backend (3-4h)
│   └── WebSocketHandler.java (peegeeq-rest)
├── Phase 2: Basic Client (2-3h)
│   └── WebSocketConsumerExample.java (peegeeq-examples)
├── Phase 3: Advanced Clients (4-6h)
│   ├── WebSocket Filtering Example
│   ├── WebSocket Connection Management Example
│   └── WebSocket Error Handling Example
└── Phase 4: Integrated Demo (4-5h)
    └── RestApiStreamingExample.java (both SSE + WebSocket)

STAGE 3: Documentation (3-4 hours)
└── Phase 1: Docs & Config
    ├── Update PEEGEEQ_EXAMPLES_GUIDE.md
    ├── Update pom.xml
    └── Create quick start guide
```

### Stage 1: Server-Sent Events (SSE) Implementation
**Total Time**: 9-13 hours
**Focus**: Simpler unidirectional streaming, easier to implement and test

#### Stage 1, Phase 1: SSE Backend (3-4 hours)
**Module**: `peegeeq-rest`
- Complete actual message streaming in `ServerSentEventsHandler.java` (line 296 TODO)
- Implement real-time message forwarding to SSE clients
- Add message filtering by type and headers
- Implement batching support
- Add heartbeat mechanism
- Implement backpressure handling
- **Add reconnection support**:
  - Support `Last-Event-ID` header (standard SSE feature)
  - Include message ID in every SSE event (`id:` field)
  - Resume from last known position on reconnection
  - Handle case where position is no longer available

**Deliverables**:
- ✅ Functional SSE streaming endpoint with reconnection support
- ✅ Unit tests for ServerSentEventsHandler
- ✅ Integration tests for SSE streaming and reconnection
- ✅ Documentation of Last-Event-ID behavior

#### Stage 1, Phase 2: SSE Client Example (2-3 hours)
**Module**: `peegeeq-examples`
- Implement `ServerSentEventsConsumerExample.java`
- Basic SSE connection and event parsing
- Handle connection, data, error, heartbeat events
- Proper resource cleanup

**Deliverables**:
- ✅ ServerSentEventsConsumerExample.java
- ✅ Maven exec configuration
- ✅ Example documentation

#### Stage 1, Phase 3: SSE Advanced Examples (4-6 hours)
**Module**: `peegeeq-examples`
- Implement SSE-specific filtering example
- Implement SSE connection management example
- Implement SSE error handling example with **reconnection**:
  - Demonstrate Last-Event-ID automatic reconnection
  - Show manual reconnection with exponential backoff
  - Handle position resumption
  - Detect and handle message gaps

**Deliverables**:
- ✅ 3 advanced SSE examples with reconnection patterns
- ✅ Maven exec configurations
- ✅ Integration tests including reconnection scenarios

---

### Stage 2: WebSocket Implementation
**Total Time**: 10-13 hours
**Focus**: Bidirectional streaming with more complex lifecycle management

#### Stage 2, Phase 1: WebSocket Backend (3-4 hours)
**Module**: `peegeeq-rest`
- Complete actual message streaming in `WebSocketHandler.java` (line 268 TODO)
- Implement real-time message forwarding to WebSocket clients
- Add message filtering by type and headers
- Implement batching support
- Implement consumer group coordination for WebSocket
- Handle bidirectional communication (ping/pong, configure messages)
- **Add reconnection support**:
  - Accept `configure` message with `lastMessageId` and `resumeFromPosition`
  - Resume from last known position on reconnection
  - Send `configured` response with resumption status
  - Handle consumer group rejoin logic
  - Implement duplicate detection window
  - Track connection state and notify client of state changes

**Deliverables**:
- ✅ Functional WebSocket streaming endpoint with reconnection support
- ✅ Unit tests for WebSocketHandler including reconnection scenarios
- ✅ Integration tests for WebSocket streaming and reconnection
- ✅ Documentation of configure message protocol

#### Stage 2, Phase 2: WebSocket Client Example (2-3 hours)
**Module**: `peegeeq-examples`
- Implement `WebSocketConsumerExample.java`
- Basic WebSocket connection and message handling
- Handle welcome, data, error, heartbeat messages
- Send ping/pong and configure messages
- Proper resource cleanup

**Deliverables**:
- ✅ WebSocketConsumerExample.java
- ✅ Maven exec configuration
- ✅ Example documentation

#### Stage 2, Phase 3: WebSocket Advanced Examples (4-6 hours)
**Module**: `peegeeq-examples`
- Implement WebSocket-specific filtering example
- Implement WebSocket connection management example
- Implement WebSocket error handling example with **reconnection**:
  - Demonstrate manual reconnection with exponential backoff
  - Send configure message with lastMessageId for resumption
  - Handle consumer group rejoin
  - Implement duplicate message detection
  - Detect and handle message gaps

**Deliverables**:
- ✅ 3 advanced WebSocket examples with reconnection patterns
- ✅ Maven exec configurations
- ✅ Integration tests including reconnection scenarios

#### Stage 2, Phase 4: Integrated Demo (4-5 hours)
**Module**: `peegeeq-examples`
- Implement `RestApiStreamingExample.java`
- Demonstrate both SSE and WebSocket streaming
- Compare performance characteristics
- Show best practices for choosing between SSE and WebSocket
- Comprehensive error handling and recovery

**Deliverables**:
- ✅ RestApiStreamingExample.java
- ✅ Maven exec configuration
- ✅ Performance comparison documentation

---

### Stage 3: Documentation and Polish (3-4 hours)
**Module**: Documentation updates across modules

#### Phase 1: Documentation Updates
- Update PEEGEEQ_EXAMPLES_GUIDE.md
  - **Note**: Currently lists RestApiStreamingExample as "100% complete" - needs correction to "Planned"
  - Add streaming examples section
  - Add SSE vs WebSocket decision guide
- Update pom.xml with all exec configurations
  - **Note**: Currently `run-rest-streaming-example` exists in pom.xml (line 375-382) but the class doesn't exist yet
  - Uncomment and verify existing configuration
  - Add remaining 5 configurations
- Create streaming examples quick start guide
- Document performance characteristics

**Deliverables**:
- ✅ Updated PEEGEEQ_EXAMPLES_GUIDE.md
- ✅ Updated pom.xml
- ✅ Streaming quick start guide
- ✅ Performance comparison documentation

---

## Implementation Summary

| Stage | Focus | Time | Examples | Backend Work |
|-------|-------|------|----------|--------------|
| **Stage 1** | SSE | 9-13h | 3 examples | ServerSentEventsHandler |
| **Stage 2** | WebSocket | 10-13h | 3 examples + integrated | WebSocketHandler |
| **Stage 3** | Documentation | 3-4h | - | - |
| **Total** | Both | 22-30h | 7 examples | Both handlers |

## Benefits of Staged Approach

1. **Incremental Delivery**: SSE examples available earlier (simpler to implement)
2. **Easier Testing**: Test SSE thoroughly before tackling WebSocket complexity
3. **Learning Curve**: Team learns streaming patterns with simpler SSE first
4. **Risk Mitigation**: Issues discovered in Stage 1 inform Stage 2 implementation
5. **Parallel Work**: Different developers can work on SSE and WebSocket simultaneously after Stage 1, Phase 1

---

## Detailed Implementation Guide

### 1. WebSocketConsumerExample.java

**Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/WebSocketConsumerExample.java`
**Estimated Time**: 2-3 hours
**Complexity**: Low
**Dependencies**: None

**Purpose**: Demonstrate basic WebSocket streaming with connection lifecycle management

**Features**:
- Connect to WebSocket endpoint
- Receive and handle welcome message
- Stream messages in real-time
- Send ping/pong for keep-alive
- Graceful disconnect and cleanup

**Key Methods**:
```java
public static void main(String[] args)
private static void demonstrateWebSocketStreaming()
private static void connectWebSocket()
private static void handleWelcomeMessage(JsonObject message)
private static void handleDataMessage(JsonObject message)
private static void sendPing(WebSocket webSocket)
private static void closeConnection(WebSocket webSocket)
```

**Code Pattern** (follow CloudEventsExample.java):
```java
public class WebSocketConsumerExample {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketConsumerExample.class);

    public static void main(String[] args) {
        logger.info("Starting WebSocket Consumer Example");

        Vertx vertx = null;
        WebSocketClient client = null;

        try {
            vertx = Vertx.vertx();
            client = vertx.createWebSocketClient();

            demonstrateWebSocketStreaming(client);

            logger.info("WebSocket example completed successfully");
        } catch (Exception e) {
            logger.error("Error in WebSocket example", e);
        } finally {
            // Cleanup
            if (client != null) client.close();
            if (vertx != null) vertx.close();
        }
    }
}
```

---

### 2. ServerSentEventsConsumerExample.java

**Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/ServerSentEventsConsumerExample.java`
**Estimated Time**: 2-3 hours
**Complexity**: Low
**Dependencies**: None

**Purpose**: Demonstrate SSE streaming for one-way server-to-client communication

**Features**:
- Connect to SSE endpoint
- Parse SSE event stream (event: data: format)
- Handle connection events
- Process data events
- Handle error events and reconnection

**Key Methods**:
```java
public static void main(String[] args)
private static void demonstrateSSEStreaming()
private static void connectSSE()
private static void parseSSEEvent(String eventLine)
private static void handleConnectionEvent(JsonObject event)
private static void handleDataEvent(JsonObject event)
private static void handleErrorEvent(JsonObject event)
```

**SSE Event Format**:
```
event: connection
data: {"type":"connection","connectionId":"sse-123",...}

event: data
data: {"type":"data","messageId":"msg-456","payload":{...}}
```

---

### 3. StreamingWithFilteringExample.java

**Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/StreamingWithFilteringExample.java`
**Estimated Time**: 2-3 hours
**Complexity**: Medium
**Dependencies**: WebSocketConsumerExample, ServerSentEventsConsumerExample

**Purpose**: Demonstrate filtering, batching, and consumer groups

**Features**:
- Filter by message type
- Filter by headers
- Configure batch size
- Configure max wait time
- Use consumer groups for load balancing
- Compare filtered vs unfiltered performance

**Key Methods**:
```java
public static void main(String[] args)
private static void demonstrateFiltering()
private static void demonstrateMessageTypeFilter()
private static void demonstrateHeaderFilter()
private static void demonstrateBatching()
private static void demonstrateConsumerGroups()
private static void compareFilteredVsUnfiltered()
```

**Example Query Strings**:
```
?messageType=OrderCreated
?headers.region=EU&headers.priority=HIGH
?batchSize=10&maxWaitTime=2000
?consumerGroup=order-processors
```

---

### 4. StreamingConnectionManagementExample.java

**Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/StreamingConnectionManagementExample.java`
**Estimated Time**: 3-4 hours
**Complexity**: Medium
**Dependencies**: StreamingWithFilteringExample

**Purpose**: Demonstrate proper lifecycle management and resource cleanup

**Features**:
- Connection state tracking (CONNECTING, ACTIVE, IDLE, ERROR, CLOSED)
- Metrics collection (messages received, latency, throughput)
- Resource cleanup patterns
- Connection timeout handling
- Activity monitoring and idle detection

**Key Methods**:
```java
public static void main(String[] args)
private static void demonstrateConnectionManagement()
private static void trackConnectionState()
private static void collectMetrics()
private static void handleTimeout()
private static void monitorActivity()
private static void cleanupResources()
```

**Metrics to Track**:
- Total messages received
- Average latency (ms)
- Throughput (messages/sec)
- Connection uptime
- Last activity timestamp

---

### 5. StreamingErrorHandlingExample.java

**Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/StreamingErrorHandlingExample.java`
**Estimated Time**: 3-4 hours
**Complexity**: High
**Dependencies**: StreamingConnectionManagementExample

**Purpose**: Demonstrate robust error handling and recovery patterns

**Features**:
- Error classification (transient vs permanent)
- Exponential backoff reconnection with jitter
- Circuit breaker pattern (CLOSED, OPEN, HALF_OPEN)
- Graceful degradation
- Comprehensive error logging with context

**Key Methods**:
```java
public static void main(String[] args)
private static void demonstrateErrorHandling()
private static ErrorType classifyError(Exception error)
private static void reconnectWithBackoff(int attemptNumber)
private static void implementCircuitBreaker()
private static void handleTransientError(Exception error)
private static void handlePermanentError(Exception error)
```

**Error Classification**:
- **Transient**: Network timeout, connection refused, temporary unavailability
- **Permanent**: Authentication failure, invalid setup/queue, authorization error

**Exponential Backoff Formula**:
```
delay = min(maxDelay, baseDelay * 2^attemptNumber) + random(0, jitter)
```

**Reconnection and Resubscription Strategy**:

This is a **critical aspect** of streaming reliability. The plan includes:

1. **Message Position Tracking**
   - Track last successfully processed message ID
   - Store position locally (in-memory for examples, persistent for production)
   - Use position to resume consumption after reconnection
   - Handle case where position is no longer available (message expired)

2. **Resubscription Process**
   ```
   1. Detect disconnection (connection closed, error, timeout)
   2. Classify error (transient vs permanent)
   3. If transient:
      a. Wait with exponential backoff
      b. Reconnect to same endpoint
      c. Resubscribe with same consumer group
      d. Resume from last known position (if available)
      e. Handle gap detection (missing messages)
   4. If permanent:
      a. Log error with full context
      b. Alert/notify
      c. Do NOT retry automatically
   ```

3. **Consumer Group Coordination**
   - On reconnection, rejoin same consumer group
   - Server may reassign partitions/messages
   - Client should handle duplicate messages gracefully
   - Track consumer group membership state

4. **Duplicate Message Handling**
   - Messages may be delivered more than once during reconnection
   - Client should implement idempotency where possible
   - Track recently processed message IDs (sliding window)
   - Log duplicates for monitoring

5. **Gap Detection**
   - Track message sequence numbers (if available)
   - Detect gaps in sequence after reconnection
   - Options:
     - Request missing messages from REST API
     - Log gap and continue (acceptable for some use cases)
     - Fail and alert (critical for others)

6. **Connection State Machine**
   ```
   DISCONNECTED → CONNECTING → CONNECTED → SUBSCRIBING → ACTIVE
        ↑              ↓              ↓            ↓          ↓
        └──────────────┴──────────────┴────────────┴──────────┘
                         (on error/timeout)
   ```

7. **Backoff Configuration**
   ```java
   private static final int BASE_DELAY_MS = 1000;      // 1 second
   private static final int MAX_DELAY_MS = 60000;      // 60 seconds
   private static final int MAX_ATTEMPTS = 10;         // Give up after 10 attempts
   private static final int JITTER_MS = 500;           // Random jitter up to 500ms
   ```

8. **Example Reconnection Code Pattern**
   ```java
   private static void reconnectWithBackoff(int attemptNumber, String lastMessageId) {
       if (attemptNumber > MAX_ATTEMPTS) {
           logger.error("Max reconnection attempts reached, giving up");
           return;
       }

       int delay = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1 << attemptNumber));
       delay += ThreadLocalRandom.current().nextInt(JITTER_MS);

       logger.info("Reconnecting in {}ms (attempt {}/{})", delay, attemptNumber, MAX_ATTEMPTS);

       vertx.setTimer(delay, id -> {
           connectAndSubscribe(lastMessageId)
               .onSuccess(conn -> {
                   logger.info("Reconnected successfully");
                   resetBackoff();
               })
               .onFailure(error -> {
                   logger.warn("Reconnection failed: {}", error.getMessage());
                   reconnectWithBackoff(attemptNumber + 1, lastMessageId);
               });
       });
   }
   ```

**Important Notes**:
- SSE has built-in reconnection via `Last-Event-ID` header (use this!)
- WebSocket requires manual reconnection logic
- Consumer groups complicate reconnection (partition reassignment)
- Examples should demonstrate both simple and complex scenarios

---

### 6. RestApiStreamingExample.java

**Location**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/RestApiStreamingExample.java`
**Estimated Time**: 4-5 hours
**Complexity**: High
**Dependencies**: All previous examples

**Purpose**: Comprehensive integration of all patterns (main entry point referenced in pom.xml)

**Features**:
- Demonstrate WebSocket streaming
- Demonstrate SSE streaming
- Show filtering and configuration
- Show connection management
- Show error handling and recovery
- Compare WebSocket vs SSE performance
- Document best practices

**Key Methods**:
```java
public static void main(String[] args)
private static void demonstrateAllPatterns()
private static void demonstrateWebSocketStreaming()
private static void demonstrateSSEStreaming()
private static void demonstrateFiltering()
private static void demonstrateConnectionManagement()
private static void demonstrateErrorHandling()
private static void compareWebSocketVsSSE()
private static void showBestPractices()
```

**Performance Comparison Metrics**:
- Connection establishment time
- Message throughput (messages/sec)
- Latency (p50, p95, p99)
- Memory usage
- CPU usage
- Network overhead

---

## Code Structure

### Common Pattern (All Examples)
```java
public class StreamingExample {
    private static final Logger logger = LoggerFactory.getLogger(StreamingExample.class);
    
    public static void main(String[] args) {
        logger.info("Starting streaming example");
        try {
            // Initialize
            // Demonstrate
            // Cleanup
        } catch (Exception e) {
            logger.error("Error", e);
        }
    }
    
    private static void demonstrate() throws Exception {
        // Implementation
    }
}
```

### Dependencies
- Vert.x 5.x (WebSocket/HTTP client) - version managed by parent POM
- SLF4J (logging)
- Jackson (JSON parsing)
- Java 21 (as configured in parent POM)

**Note**: These examples are **client-side consumers** that connect to the PeeGeeQ REST API. They do not implement the server-side streaming logic (which is in the peegeeq-rest module).

## Testing Strategy

### Unit Tests
- Message parsing
- Filter logic
- Error handling
- Metrics collection

### Integration Tests
- Real WebSocket connections
- Real SSE connections
- Connection lifecycle
- Error recovery

### Performance Tests
- Throughput (messages/sec)
- Latency (p50, p95, p99)
- Memory usage
- Connection stability

## Documentation Updates

### PEEGEEQ_EXAMPLES_GUIDE.md
Add new section:
- Overview of streaming examples
- When to use WebSocket vs SSE
- Quick start guide
- Configuration options
- Performance considerations
- Troubleshooting guide

### pom.xml
Add Maven exec configurations:
```xml
<execution>
  <id>run-websocket-consumer-example</id>
  <mainClass>dev.mars.peegeeq.examples.WebSocketConsumerExample</mainClass>
</execution>
<!-- Similar for other examples -->
```

## Recommended Implementation Order

### Stage 1: SSE (Do First - Simpler)
1. **ServerSentEventsHandler** (backend) - Complete TODO at line 296
2. **ServerSentEventsConsumerExample** - Basic SSE client
3. **SSE Filtering Example** - Message filtering with SSE
4. **SSE Connection Management Example** - Lifecycle management
5. **SSE Error Handling Example** - Error recovery patterns
6. **Test and validate** - Ensure SSE works end-to-end

### Stage 2: WebSocket (Do Second - More Complex)
7. **WebSocketHandler** (backend) - Complete TODO at line 268
8. **WebSocketConsumerExample** - Basic WebSocket client
9. **WebSocket Filtering Example** - Message filtering with WebSocket
10. **WebSocket Connection Management Example** - Lifecycle management
11. **WebSocket Error Handling Example** - Error recovery patterns
12. **RestApiStreamingExample** - Integrated demo of both SSE and WebSocket
13. **Test and validate** - Ensure WebSocket works end-to-end

### Stage 3: Documentation
14. **Update PEEGEEQ_EXAMPLES_GUIDE.md** - Add streaming examples section
15. **Update pom.xml** - Add/uncomment all Maven exec configurations
16. **Create quick start guide** - Help users choose SSE vs WebSocket
17. **Document performance** - Comparison and best practices

### Why This Order?

1. **SSE First**: Simpler protocol, easier to implement and debug
2. **Learn from SSE**: Patterns learned in SSE apply to WebSocket
3. **Incremental Value**: SSE examples available sooner
4. **Risk Reduction**: Issues found in SSE inform WebSocket implementation
5. **Testing**: Each stage can be fully tested before moving to next

## Success Criteria

### Stage 1: SSE Complete When...
- ✅ ServerSentEventsHandler streams actual messages from queue
- ✅ SSE backend has unit and integration tests
- ✅ ServerSentEventsConsumerExample connects and receives messages
- ✅ SSE filtering, connection management, and error handling examples work
- ✅ All SSE examples have Maven exec configurations
- ✅ SSE examples are documented

### Stage 2: WebSocket Complete When...
- ✅ WebSocketHandler streams actual messages from queue
- ✅ WebSocket backend has unit and integration tests
- ✅ WebSocketConsumerExample connects and receives messages
- ✅ WebSocket filtering, connection management, and error handling examples work
- ✅ RestApiStreamingExample demonstrates both SSE and WebSocket
- ✅ All WebSocket examples have Maven exec configurations
- ✅ WebSocket examples are documented

### Stage 3: Documentation Complete When...
- ✅ PEEGEEQ_EXAMPLES_GUIDE.md updated with streaming section
- ✅ SSE vs WebSocket decision guide created
- ✅ Performance comparison documented
- ✅ Quick start guide available
- ✅ All pom.xml configurations verified working

### Overall Success Criteria
- ✅ All 7 client examples are runnable standalone classes
- ✅ Both backend handlers stream messages in real-time
- ✅ Each example demonstrates its specific pattern clearly
- ✅ Code follows existing CloudEventsExample patterns
- ✅ Comprehensive logging for debugging
- ✅ Proper resource cleanup in all examples
- ✅ Documentation is complete and accurate
- ✅ All pom.xml configurations work

## Backend Implementation Status

### Current State (peegeeq-rest module)

**✅ Implemented:**
- WebSocketHandler.java - Connection handling, ping/pong, configuration messages
- ServerSentEventsHandler.java - SSE connection handling, event formatting
- WebSocketConnection.java - Connection state management, statistics
- SSEConnection.java - SSE connection state management, statistics
- PeeGeeQRestServer.java - Routes configured for both WebSocket and SSE endpoints

**⚠️ Partially Implemented (TODO items):**
- WebSocketHandler.java line 268: "TODO: Implement actual message streaming from consumer"
  - Currently creates consumer but doesn't subscribe to message stream
  - Missing: Real-time message forwarding to WebSocket clients
  - Missing: Filter application, batching, consumer group coordination

- ServerSentEventsHandler.java line 296: "Note: This is a simplified implementation"
  - Currently creates consumer but doesn't implement full streaming
  - Missing: Real-time message forwarding to SSE clients
  - Missing: Filter application, batching, heartbeats, backpressure handling

**❌ Not Implemented:**
- Consumer group coordination for streaming scenarios
- Message filtering by type and headers in streaming context
- Batching support in streaming handlers
- Heartbeat mechanism for long-lived connections
- Backpressure and flow control
- **Reconnection support (critical for production)**:
  - Message position tracking and resumption
  - Last-Event-ID support for SSE (standard SSE feature)
  - Consumer group rejoin after disconnection
  - Duplicate message detection/prevention
  - Gap detection and handling

### Backend Requirements for Reconnection/Resubscription

To support the reconnection patterns in the examples, the backend needs:

#### 1. Message Position Tracking (SSE)
**ServerSentEventsHandler.java needs:**
```java
// Support Last-Event-ID header for resumption
String lastEventId = request.getHeader("Last-Event-ID");
if (lastEventId != null) {
    // Resume from this position
    consumer.seekToMessageId(lastEventId);
}

// Include message ID in every SSE event
event: data
id: msg-12345-67890
data: {"messageId": "msg-12345-67890", ...}
```

**Benefits:**
- Standard SSE reconnection mechanism
- Browser/client automatically sends Last-Event-ID on reconnect
- Server can resume from exact position

#### 2. Message Position Tracking (WebSocket)
**WebSocketHandler.java needs:**
```java
// Client sends configure message with last known position
{
    "type": "configure",
    "lastMessageId": "msg-12345-67890",
    "resumeFromPosition": true
}

// Server responds with acknowledgment
{
    "type": "configured",
    "resumedFrom": "msg-12345-67890",
    "messagesSkipped": 0
}
```

**Benefits:**
- Explicit control over resumption
- Client can choose to start fresh or resume
- Server can report gaps

#### 3. Consumer Group Persistence
**Both handlers need:**
```java
// Track consumer group membership across connections
// When client reconnects with same consumer group:
// 1. Check if previous consumer is still active
// 2. If not, allow rejoin
// 3. If yes, reject (duplicate consumer in group)
// 4. Handle partition reassignment

private void handleConsumerGroupReconnection(
    String consumerGroup,
    String clientId,
    String lastMessageId
) {
    // Implementation needed
}
```

**Benefits:**
- Prevents duplicate consumers in same group
- Handles partition reassignment gracefully
- Maintains exactly-once semantics (where possible)

#### 4. Duplicate Detection Window
**Both handlers need:**
```java
// Track recently sent message IDs per connection
// Prevent sending duplicates during reconnection window
private final Map<String, Set<String>> recentMessageIds = new ConcurrentHashMap<>();

private boolean isDuplicate(String connectionId, String messageId) {
    Set<String> recent = recentMessageIds.get(connectionId);
    if (recent == null) return false;
    return recent.contains(messageId);
}
```

**Benefits:**
- Reduces duplicate messages during reconnection
- Improves client experience
- Reduces client-side deduplication burden

#### 5. Gap Detection Support
**Both handlers need:**
```java
// Include sequence numbers in messages
{
    "messageId": "msg-12345-67890",
    "sequenceNumber": 12345,
    "partitionId": 0,
    "data": {...}
}

// When resuming, detect and report gaps
{
    "type": "gap_detected",
    "fromSequence": 12340,
    "toSequence": 12344,
    "missingCount": 5,
    "reason": "messages_expired"
}
```

**Benefits:**
- Client knows if messages were missed
- Client can decide how to handle gaps
- Improves observability

#### 6. Connection State Tracking
**Both handlers need:**
```java
// Track connection state and transitions
enum ConnectionState {
    CONNECTING,
    AUTHENTICATING,
    SUBSCRIBING,
    ACTIVE,
    IDLE,
    RECONNECTING,
    CLOSING,
    CLOSED,
    ERROR
}

// Send state change notifications to client
{
    "type": "state_change",
    "from": "ACTIVE",
    "to": "IDLE",
    "reason": "no_messages_available",
    "timestamp": "2025-11-06T10:30:00Z"
}
```

**Benefits:**
- Client knows exact connection state
- Helps with debugging
- Enables better error handling

### Implementation Priority for Backend

**High Priority (Required for basic reconnection):**
1. ✅ Last-Event-ID support in ServerSentEventsHandler
2. ✅ Message ID in every event/message
3. ✅ Configure message handling in WebSocketHandler
4. ✅ Basic position resumption

**Medium Priority (Required for production):**
5. ✅ Consumer group persistence and rejoin logic
6. ✅ Duplicate detection window
7. ✅ Connection state tracking

**Lower Priority (Nice to have):**
8. ⚠️ Gap detection and reporting
9. ⚠️ Sequence number tracking
10. ⚠️ Advanced metrics and monitoring

### Impact on Examples

The examples in this plan are **client-side consumers** that will connect to the REST API endpoints. They can be implemented and will work for:
- Connection establishment
- Receiving welcome/configuration messages
- Connection lifecycle management
- Error handling patterns

However, they will **not receive actual queue messages** until the backend TODO items are completed. The examples can still be valuable for:
- Demonstrating client-side patterns
- Testing connection management
- Showing error handling
- Providing a foundation for when backend streaming is complete

### Recommendation

Consider a two-phase approach:
1. **Phase A**: Implement examples with current backend (connection patterns, lifecycle, error handling)
2. **Phase B**: Complete backend streaming implementation
3. **Phase C**: Update examples to demonstrate full message streaming

Alternatively, complete backend streaming first, then implement all examples with full functionality.
- ✅ Examples can be run via Maven

