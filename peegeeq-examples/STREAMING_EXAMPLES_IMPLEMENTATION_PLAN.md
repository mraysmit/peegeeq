# Streaming Consumer Examples Implementation Plan

## Overview
Create comprehensive examples demonstrating WebSocket and Server-Sent Events (SSE) streaming for consuming messages from PeeGeeQ queues. Each example will be a standalone, runnable class that developers can use as a reference.

## Architecture

### API Endpoints
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

### Query Parameters (Both WebSocket & SSE)
- `consumerGroup`: Consumer group for load balancing
- `batchSize`: Messages per batch (1-100, default: 1)
- `maxWaitTime`: Max wait in milliseconds (1000-60000, default: 5000)
- `messageType`: Filter by message type
- `headers.*`: Filter by header values

## Implementation Plan

### 1. WebSocketConsumerExample.java
**Purpose**: Basic WebSocket streaming with connection lifecycle

**Features**:
- Connect to WebSocket endpoint
- Receive welcome message
- Stream messages in real-time
- Send ping/pong for keep-alive
- Graceful disconnect

**Key Methods**:
- `connectWebSocket()` - Establish connection
- `handleWelcomeMessage()` - Process welcome
- `handleDataMessage()` - Process incoming messages
- `sendPing()` - Keep-alive heartbeat
- `closeConnection()` - Graceful shutdown

### 2. ServerSentEventsConsumerExample.java
**Purpose**: SSE streaming for one-way server-to-client communication

**Features**:
- Connect to SSE endpoint
- Parse SSE event stream
- Handle connection events
- Process data events
- Handle errors and reconnection

**Key Methods**:
- `connectSSE()` - Establish SSE connection
- `parseSSEEvent()` - Parse event stream
- `handleConnectionEvent()` - Process connection events
- `handleDataEvent()` - Process messages
- `handleErrorEvent()` - Handle errors

### 3. StreamingWithFilteringExample.java
**Purpose**: Demonstrate filtering, batching, and consumer groups

**Features**:
- Filter by message type
- Filter by headers
- Configure batch size
- Configure max wait time
- Use consumer groups for load balancing

**Key Methods**:
- `connectWithFilters()` - Connect with query parameters
- `demonstrateMessageTypeFilter()` - Filter by type
- `demonstrateHeaderFilter()` - Filter by headers
- `demonstrateBatching()` - Batch configuration
- `demonstrateConsumerGroups()` - Load balancing

### 4. StreamingConnectionManagementExample.java
**Purpose**: Proper lifecycle management and resource cleanup

**Features**:
- Connection state tracking
- Metrics collection (messages received, latency)
- Resource cleanup
- Connection timeout handling
- Activity monitoring

**Key Methods**:
- `trackConnectionState()` - Monitor connection status
- `collectMetrics()` - Gather performance metrics
- `handleTimeout()` - Timeout management
- `cleanupResources()` - Proper shutdown
- `monitorActivity()` - Activity tracking

### 5. StreamingErrorHandlingExample.java
**Purpose**: Robust error handling and recovery patterns

**Features**:
- Exponential backoff reconnection
- Error classification (transient vs permanent)
- Circuit breaker pattern
- Graceful degradation
- Error logging and monitoring

**Key Methods**:
- `handleConnectionError()` - Error classification
- `reconnectWithBackoff()` - Exponential backoff
- `implementCircuitBreaker()` - Circuit breaker logic
- `handleTransientError()` - Transient error recovery
- `handlePermanentError()` - Permanent error handling

### 6. RestApiStreamingExample.java
**Purpose**: Comprehensive example integrating all patterns

**Features**:
- Demonstrates all streaming patterns
- Compares WebSocket vs SSE
- Shows best practices
- Includes performance metrics
- Serves as main entry point (referenced in pom.xml)

**Key Methods**:
- `demonstrateWebSocketStreaming()` - WebSocket pattern
- `demonstrateSSEStreaming()` - SSE pattern
- `comparePerformance()` - Performance comparison
- `showBestPractices()` - Best practices guide
- `runAllExamples()` - Integrated demo

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
- Vert.x 5.0.4 (WebSocket/HTTP client)
- SLF4J (logging)
- Jackson (JSON parsing)
- Java 11+

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

## Implementation Order

1. **WebSocketConsumerExample** - Foundation for WebSocket patterns
2. **ServerSentEventsConsumerExample** - Foundation for SSE patterns
3. **StreamingWithFilteringExample** - Build on foundations
4. **StreamingConnectionManagementExample** - Advanced patterns
5. **StreamingErrorHandlingExample** - Advanced patterns
6. **RestApiStreamingExample** - Integration of all patterns
7. **Documentation** - Update guides
8. **pom.xml** - Add Maven configurations

## Success Criteria

- ✅ All 6 examples are runnable standalone classes
- ✅ Each example demonstrates its specific pattern clearly
- ✅ Code follows existing CloudEventsExample patterns
- ✅ Comprehensive logging for debugging
- ✅ Proper resource cleanup
- ✅ Documentation is complete
- ✅ pom.xml configurations work
- ✅ Examples can be run via Maven

