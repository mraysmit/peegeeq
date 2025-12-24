# Distributed Tracing Guide for PeeGeeQ

## Overview

PeeGeeQ provides **built-in distributed tracing** support using **W3C Trace Context** standard and **SLF4J MDC (Mapped Diagnostic Context)**. This enables you to track requests across your entire system - from HTTP ingress through message queues to final processing.

## What is Distributed Tracing?

Distributed tracing allows you to follow a single request as it flows through multiple services and components. Each log statement automatically includes:

- **Trace ID**: A unique identifier for the entire request flow (32 hex characters)
- **Span ID**: A unique identifier for a specific operation within the trace (16 hex characters)
- **Correlation ID**: A business-level identifier (e.g., order ID, customer ID)

## Why Use Distributed Tracing?

### Without Distributed Tracing
```
21:32:34.059 [vert.x-eventloop-thread-0] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] Validating database connectivity...
21:32:34.061 [vert.x-eventloop-thread-0] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] Starting all PeeGeeQ components...
```

**Problem**: You can't correlate logs across different services or find all logs related to a specific request.

### With Distributed Tracing
```
23:28:17.561 [outbox-processor-1] INFO  OutboxConsumer - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] MDC set for message 1
23:28:17.562 [outbox-processor-1] INFO  OutboxConsumer - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] Processing message 1
23:28:17.562 [outbox-processor-1] INFO  OrderService - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] Processing order
```

**Benefit**: You can search for `traceId=52a0d3705aba4122aa266f1216f87e10` and see ALL logs related to this request across all services!

## Understanding Asynchronous vs Synchronous Tracing

### Message Queues Are Asynchronous (Fire-and-Forget)

**Important**: Message queues use **asynchronous** communication. The producer sends a message and moves on - there's no synchronous "response" that flows back to the producer.

```
Producer Thread                    Consumer Thread
─────────────────                  ───────────────
[traceId=abc...]
  │
  ├─ Send message ──────────────►  [traceId=abc...] ← Gets trace from message
  │                                  │
  │                                  ├─ Process message
  │                                  │
  └─ send() returns                  └─ Complete processing
     (producer done)                    (consumer done)

Later (cleanup):
[traceId= ] ← No trace context
  │
  └─ Unsubscribe (administrative operation, not part of traced request)
```

**This is correct!** The trace context lives within the message processing lifecycle, not in administrative operations like unsubscribe.

### HTTP Is Synchronous (Request-Response)

In contrast, HTTP uses **synchronous** request-response where trace context flows both ways:

```
HTTP Client                        HTTP Server
───────────                        ───────────
[traceId=abc...]
  │
  ├─ HTTP Request ──────────────►  [traceId=abc...] ← Gets trace from header
  │                                  │
  │                                  ├─ Process request
  │                                  │
  │ ◄──────────── HTTP Response ───┤ [traceId=abc...] ← Trace in response
  │
[traceId=abc...] ← Trace continues in client
  │
  └─ Handle response
```

### Full Distributed Tracing Flow

The **real power** of distributed tracing is seeing the **entire flow** across multiple services:

```
HTTP Client → REST API → Message Queue → Consumer → External Service
     ↓            ↓            ↓             ↓              ↓
  traceId=abc  traceId=abc  traceId=abc  traceId=abc  traceId=abc
```

All logs across all services show the **same traceId**, allowing you to:
- Search for `traceId=abc` and see the complete request flow
- Track a request from HTTP ingress through async processing to external calls
- Debug issues by seeing all related logs in one place

See `FullDistributedTracingExample.java` for a complete working example.

## How It Works

### 1. W3C Trace Context Format

PeeGeeQ uses the W3C Trace Context standard:

```
traceparent: 00-{trace-id}-{parent-id}-{trace-flags}
```

Example:
```
traceparent: 00-52a0d3705aba4122aa266f1216f87e10-d6ae23cb58e1467d-01
             │  │                                │                  │
             │  │                                │                  └─ Trace flags (01 = sampled)
             │  │                                └─ Span ID (16 hex chars)
             │  └─ Trace ID (32 hex chars)
             └─ Version (00)
```

### 2. Automatic MDC Population

When a message is consumed, PeeGeeQ automatically:
1. Extracts the `traceparent` header from the message
2. Parses the trace ID and span ID
3. Sets them in SLF4J MDC
4. All subsequent logs in that thread automatically include the trace context
5. Clears MDC after processing to prevent leakage

### 3. Log Pattern Configuration

Configure your `logback.xml` to display MDC values:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

The `%X{traceId:-}` syntax means:
- `%X{traceId}` - Extract MDC value for key "traceId"
- `:-` - If not set, show empty string (instead of "null")

## Quick Start

### Step 1: Send a Message with Trace Context

```java
// Generate W3C Trace Context IDs
String traceId = generateTraceId();  // 32 hex characters
String spanId = generateSpanId();    // 16 hex characters
String correlationId = "order-12345";

// Create traceparent header
String traceparent = String.format("00-%s-%s-01", traceId, spanId);

// Prepare message headers
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", traceparent);
headers.put("correlationId", correlationId);

// Send message
producer.send(orderPayload, headers, correlationId).get();
```

### Step 2: Consumer Automatically Gets Trace Context

```java
consumer.subscribe(message -> {
    // MDC is automatically populated by PeeGeeQ!
    // All logs in this handler will show trace IDs
    
    logger.info("Processing order");  
    // Output: [traceId=52a0d3705aba... spanId=d6ae23cb... correlationId=order-12345] Processing order
    
    processOrder(message.getPayload());
    
    logger.info("Order processed successfully");
    // Output: [traceId=52a0d3705aba... spanId=d6ae23cb... correlationId=order-12345] Order processed successfully
    
    return CompletableFuture.completedFuture(null);
});
```

### Step 3: Search Logs by Trace ID

```bash
# Find all logs for a specific request
grep "traceId=52a0d3705aba4122aa266f1216f87e10" application.log

# Find all logs for a specific order
grep "correlationId=order-12345" application.log
```

## Complete Examples

### Example 1: Basic Trace Propagation (Test)

See the working test: `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/DistributedTracingTest.java`

This test demonstrates:
- ✅ Generating W3C Trace Context IDs
- ✅ Sending messages with trace headers
- ✅ Automatic MDC population in consumer
- ✅ Trace context propagation verification
- ✅ Logs showing populated trace IDs

Run the test:
```bash
mvn test -Dtest=DistributedTracingTest -Pintegration-tests -pl peegeeq-outbox
```

### Example 2: Full Distributed Tracing Flow

See the complete example: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java`

This example demonstrates the **full distributed tracing flow**:
1. **HTTP API** receives request with `traceparent` header
2. **API** sends message to queue with trace context
3. **Consumer** processes message (trace context auto-populated in MDC)
4. **Consumer** calls external service with trace context
5. **All logs** across all services show the same `traceId`

This shows how trace context flows through:
```
HTTP Client → REST API → Message Queue → Consumer → External Service
     ↓            ↓            ↓             ↓              ↓
  traceId=abc  traceId=abc  traceId=abc  traceId=abc  traceId=abc
```

Run the example:
```bash
cd peegeeq-examples
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.FullDistributedTracingExample"
```

Then send a request:
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-$(uuidgen | tr -d '-' | cut -c1-32)-$(uuidgen | tr -d '-' | cut -c1-16)-01" \
  -d '{"orderId":"12345","amount":100.00}'
```

Watch the logs - you'll see the **same traceId** in:
- REST API logs
- Queue consumer logs
- External service logs

## Helper Methods

### Generate Trace ID (32 hex characters)
```java
private String generateTraceId() {
    return UUID.randomUUID().toString().replace("-", "") + 
           UUID.randomUUID().toString().replace("-", "").substring(0, 32 - 32);
}
```

### Generate Span ID (16 hex characters)
```java
private String generateSpanId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
}
```

## Integration with Observability Tools

### OpenTelemetry

PeeGeeQ's W3C Trace Context is fully compatible with OpenTelemetry:

```java
// OpenTelemetry will automatically inject traceparent headers
Span span = tracer.spanBuilder("send-order").startSpan();
try (Scope scope = span.makeCurrent()) {
    // PeeGeeQ will pick up the trace context from OpenTelemetry
    producer.send(order, headers, correlationId).get();
} finally {
    span.end();
}
```

### Datadog APM

Configure Datadog to use W3C Trace Context:

```yaml
# datadog.yaml
apm_config:
  propagation_style_extract:
    - tracecontext  # W3C Trace Context
    - datadog
```

### Elastic APM

```properties
# elasticapm.properties
trace_context_propagation_style=tracecontext
```

## Advanced Usage

### Propagating Trace Context to External Services

```java
consumer.subscribe(message -> {
    // Get current trace context from MDC
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");
    
    // Generate new span ID for downstream call
    String newSpanId = generateSpanId();
    String traceparent = String.format("00-%s-%s-01", traceId, newSpanId);
    
    // Call external service with trace context
    httpClient.post("/api/external")
        .putHeader("traceparent", traceparent)
        .send();
    
    return CompletableFuture.completedFuture(null);
});
```

### Using Correlation IDs for Business Tracking

```java
// Use business identifiers as correlation IDs
String correlationId = "order-" + orderId;

Map<String, String> headers = new HashMap<>();
headers.put("traceparent", traceparent);
headers.put("correlationId", correlationId);

producer.send(order, headers, correlationId).get();
```

Now you can search logs by business ID:
```bash
grep "correlationId=order-12345" application.log
```

## Troubleshooting

### Blank Trace IDs in Logs

**Symptom:**
```
[traceId= spanId= correlationId=] Initializing PeeGeeQ Manager
```

**Cause**: This is **normal and expected** for logs that occur outside of message processing (e.g., initialization, shutdown).

**Solution**: No action needed. Trace IDs only appear when processing messages that include trace headers.

### Trace IDs Not Showing in Consumer Logs

**Cause**: Logback configuration doesn't include MDC placeholders.

**Solution**: Update your `logback.xml`:
```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

### Trace Context Lost in Async Operations

**Cause**: MDC is thread-local and doesn't automatically propagate to new threads.

**Solution**: PeeGeeQ handles this automatically for message processing. If you create your own threads:

```java
// Capture MDC before async operation
Map<String, String> mdcContext = MDC.getCopyOfContextMap();

CompletableFuture.runAsync(() -> {
    // Restore MDC in new thread
    if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
    }
    
    // Your code here
    
    // Clear MDC when done
    MDC.clear();
});
```

## Best Practices

1. **Always include `traceparent` header** when sending messages via REST API or programmatically
2. **Use structured logging** (JSON) to make MDC fields searchable in log aggregation tools
3. **Generate new span IDs** when calling downstream services to create proper trace hierarchy
4. **Use correlation IDs** for business-level tracking (order ID, customer ID, transaction ID)
5. **Don't modify MDC manually** in message handlers - PeeGeeQ manages it automatically
6. **Enable trace sampling** in production to reduce overhead (use trace flags in traceparent)

## Performance Considerations

- **Minimal overhead**: MDC operations are very fast (< 1μs per operation)
- **No network calls**: Trace context is propagated via message headers only
- **Automatic cleanup**: MDC is cleared after each message to prevent memory leaks
- **Thread-safe**: MDC uses ThreadLocal storage, no synchronization needed

## Next Steps

- See [MDC_DISTRIBUTED_TRACING.md](MDC_DISTRIBUTED_TRACING.md) for detailed API documentation
- See [DistributedTracingTest.java](../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/DistributedTracingTest.java) for working examples
- Integrate with your observability platform (OpenTelemetry, Datadog, Elastic APM)
- Configure structured logging (JSON) for better searchability

