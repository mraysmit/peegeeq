# PeeGeeQ Tracing & Logging User Guide

**Version**: 2.0  
**Last Updated**: 2026-01-06  
**Status**: ✅ Production-Ready

---

## Table of Contents

1. [Quick Start (5 Minutes)](#quick-start-5-minutes)
2. [Overview](#overview)
3. [Logback Configuration](#logback-configuration)
4. [Sending Messages with Trace Context](#sending-messages-with-trace-context)
5. [Consuming Messages with Tracing](#consuming-messages-with-tracing)
6. [Searching and Filtering Logs](#searching-and-filtering-logs)
7. [Understanding Log Output](#understanding-log-output)
8. [Configuration Reference](#configuration-reference)
9. [FAQ & Troubleshooting](#faq--troubleshooting)
10. [Integration with External Tools](#integration-with-external-tools)
11. [Best Practices](#best-practices)
12. [Examples](#examples)

---

## Quick Start (5 Minutes)

### 1. Configure Logback

Add custom Vert.x-aware converters to your `logback.xml`:

```xml
<configuration>
    <!-- Register custom converters for Vert.x trace context -->
    <conversionRule conversionWord="vxTrace" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxTraceIdConverter"/>
    <conversionRule conversionWord="vxSpan" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### 2. Send Message with Trace Context

```java
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

// Create root trace
TraceCtx rootSpan = TraceCtx.createNew();
String correlationId = "order-12345";

// Store in Vert.x Context (for async operations)
Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);

// Send message with traceparent header
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
headers.put("correlationId", correlationId);

producer.send(payload, headers, correlationId).get();
```

### 3. Consumer with Automatic Trace Context

```java
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;

// Use tracedConsumer for automatic trace extraction and MDC management
AsyncTraceUtils.tracedConsumer(vertx, "orders.process", message -> {
    // MDC is automatically populated with trace context!
    logger.info("Processing order");  
    // Output: [trace=52a0d370... span=d6ae23cb...] Processing order
});
```

### 4. Search Logs

```bash
# Find all logs for a specific request
grep "trace=52a0d3705aba4122aa266f1216f87e10" application.log

# Find all logs for a specific order
grep "correlationId=order-12345" application.log
```

### 5. Test It

```bash
mvn test -Dtest=TraceIdSpanIdDemoTest -pl peegeeq-integration-tests
```

---

## Overview

### What is Distributed Tracing?

PeeGeeQ provides **built-in distributed tracing** using **W3C Trace Context** and **SLF4J MDC**. This enables you to track requests across your entire system - from HTTP ingress through message queues to final processing.

Each log statement automatically includes:
- **Trace ID**: Unique identifier for the entire request flow (32 hex characters)
- **Span ID**: Unique identifier for a specific operation within the trace (16 hex characters)
- **Correlation ID**: Business-level identifier (e.g., order ID, customer ID)

### Without vs With Tracing

**Without Tracing**:
```
21:32:34.059 [vert.x-eventloop-thread-0] INFO  [trace=- span=-] PeeGeeQManager - Processing message
```

**With Tracing**:
```
23:28:17.561 [outbox-processor-1] INFO  [trace=52a0d3705aba4122aa266f1216f87e10 span=d6ae23cb58e1467d] OutboxConsumer - Processing message 1
```

**Benefit**: Search for `trace=52a0d370...` and see ALL logs for this request across all services!

### W3C Trace Context Format

```
traceparent: 00-{trace-id}-{span-id}-{flags}
             │   │         │         │
             │   │         │         └─ Trace flags (01 = sampled)
             │   │         └─────────── Span ID (16 hex chars)
             │   └───────────────────── Trace ID (32 hex chars)
             └───────────────────────── Version (always 00)
```

**Example**: `traceparent: 00-52a0d3705aba4122aa266f1216f87e10-d6ae23cb58e1467d-01`

### Key Concept: TraceId vs SpanId

| Property | TraceId | SpanId |
|----------|---------|--------|
| **Length** | 32 hex characters | 16 hex characters |
| **Scope** | Entire distributed operation | Single unit of work |
| **Lifetime** | Same across all services/threads | New for each span |
| **Purpose** | Correlate all logs for one request | Track parent-child relationships |

**Visual**:
```
User Request → API Server → Database → Message Queue → Consumer
     │              │            │           │            │
     └──────────────┴────────────┴───────────┴────────────┘
                    traceId: a1b2c3d4... (SAME everywhere)

     spanId: 1111...  spanId: 2222...  spanId: 3333...  spanId: 4444...
```

---

## Logback Configuration

### Basic Configuration

```xml
<configuration>
    <!-- Required: Register custom converters -->
    <conversionRule conversionWord="vxTrace" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxTraceIdConverter"/>
    <conversionRule conversionWord="vxSpan" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### Pattern Options

| What you want | Pattern | Output Example |
|---------------|---------|----------------|
| Both trace and span | `[trace=%vxTrace span=%vxSpan]` | `[trace=68b757f3... span=668899a8...]` |
| Trace only (simpler) | `[trace=%vxTrace]` | `[trace=68b757f3...]` |
| With correlation ID | `[trace=%vxTrace span=%vxSpan corr=%X{correlationId:-}]` | `[trace=... span=... corr=order-123]` |
| Full context | `[trace=%vxTrace span=%vxSpan msg=%X{messageId:-}]` | `[trace=... span=... msg=msg-456]` |

### Recommended Production Pattern

```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [trace=%vxTrace span=%vxSpan] %msg%n</pattern>
```

### Recommended Test Pattern

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
```

### File Appender with Trace Context

```xml
<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/application.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/application.%d{yyyy-MM-dd}.gz</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

### Converter Behavior

Both `%vxTrace` and `%vxSpan` converters follow the same resolution order:

1. **MDC first** - Check SLF4J MDC for `traceId`/`spanId` key (fastest)
2. **Vert.x Context fallback** - Check for `TraceCtx` object in Vert.x Context
3. **Return `-`** - If neither source has the value, return `-` (not empty string)

**Why `-` instead of empty?**
- `[trace=-]` = No trace context exists (startup, background job) - EXPECTED
- `[trace=]` = Ambiguous - is it missing or a bug?

---

## Sending Messages with Trace Context

### Creating a Root Trace

```java
import dev.mars.peegeeq.api.tracing.TraceCtx;

// Create new root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Access fields
String traceId = rootSpan.traceId();       // 32 hex chars
String spanId = rootSpan.spanId();          // 16 hex chars
String header = rootSpan.traceparent();     // Full W3C header
```

### Sending with Trace Headers

```java
// Create root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Store in Vert.x Context for async propagation
Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);

// Create headers with traceparent
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
headers.put("correlationId", "order-12345");

// Send message
producer.send(payload, headers, correlationId).get();
```

### Parsing Incoming Trace Context

```java
// Parse traceparent from HTTP header or message
String traceparent = request.getHeader("traceparent");
TraceCtx trace = TraceCtx.parseOrCreate(traceparent);

// If traceparent is null/invalid, a new root trace is created
```

### Creating Child Spans

```java
// Parent span from incoming request
TraceCtx parentSpan = TraceCtx.parseOrCreate(traceparent);

// Create child span (same traceId, new spanId)
TraceCtx childSpan = parentSpan.childSpan("database-query");

// childSpan.traceId() == parentSpan.traceId() (same!)
// childSpan.spanId() != parentSpan.spanId() (different!)
// childSpan.parentSpanId() == parentSpan.spanId() (reference)
```

---

## Consuming Messages with Tracing

### Using AsyncTraceUtils (Recommended)

```java
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;

// Event Bus consumer with automatic trace extraction
AsyncTraceUtils.tracedConsumer(vertx, "orders.process", message -> {
    // MDC is automatically populated!
    logger.info("Processing order");
    // Output: [trace=abc123... span=def456...] Processing order
});

// Event Bus publish with trace propagation
AsyncTraceUtils.publishWithTrace(vertx, "orders.notify", orderData);

// Event Bus request-reply with trace propagation
AsyncTraceUtils.requestWithTrace(vertx, "orders.validate", orderData)
    .onSuccess(reply -> {
        logger.info("Validation complete");
    });
```

### Worker Thread Operations

```java
// Execute blocking code with trace propagation
AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, true, () -> {
    // Runs on worker thread with child span in MDC
    logger.info("Database operation");
    // Output: [trace=abc123... span=NEW_CHILD...] Database operation
    
    return performDatabaseOperation();
});
```

### Manual MDC Scope Management

```java
private void processMessage(OutboxMessage message) {
    TraceCtx trace = TraceContextUtil.parseOrCreate(traceparentHeader);
    
    // ✅ CORRECT: mdcScope() provides automatic cleanup
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        logger.info("Processing message {}", message.getId());
        
        // Your business logic here
        handler.accept(message).get();
        
    } catch (Exception e) {
        logger.error("Error processing message", e);
        throw e;
    }
    // MDC automatically cleared when scope exits
}
```

---

## Searching and Filtering Logs

### Find All Logs for a Request

```bash
# Linux/Mac
grep "trace=abc123" application.log

# Windows PowerShell
Select-String -Path application.log -Pattern "trace=abc123"
```

### Find Logs for a Specific Span

```bash
grep "span=def456" application.log
```

### Find Errors for a Trace

```bash
grep "trace=abc123" application.log | grep -i error
```

### Find Untraced Log Lines

```bash
grep "\[trace=-\]" application.log | grep -v "testcontainers\|Hikari\|startup"
```

### ELK Stack (Kibana) Queries

```
# Find all logs for a trace
traceId:"abc123"

# Find all errors for a trace
traceId:"abc123" AND level:"ERROR"

# Find all logs for a correlation ID
correlationId:"order-12345"
```

### Grafana Loki (LogQL)

```
{job="application"} |= "trace=abc123"
{job="application"} |= "trace=abc123" |= "ERROR"
```

---

## Understanding Log Output

### Sample Log Output

```
21:19:33.408 [eventloop-2] INFO  [trace=68b757f3... span=668899a8...] REST handler - Received POST
21:19:33.450 [worker-1]    INFO  [trace=68b757f3... span=aabbccdd...] Worker - Processing in background
21:19:33.500 [eventloop-2] INFO  [trace=68b757f3... span=668899a8...] REST handler - Response sent
```

**Key Observations**:
- **traceId remains constant** (`68b757f3...`) across all lines
- **spanId changes** when entering child spans (worker thread has different span)
- You can find ALL logs for this request with `grep "trace=68b757f3"`

### Span Hierarchy Visualization

```
[Root Span: HTTP Request]
  traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
  spanId:  1111111111111111
  │
  ├─[Child Span: Database Query]
  │   traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6  (same)
  │   spanId:  2222222222222222  (new)
  │
  └─[Child Span: Event Bus Publish]
      traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6  (same)
      spanId:  3333333333333333  (new)
```

### Understanding Blank Trace IDs (`-`)

Logs showing `[trace=- span=-]` are **normal** for:
- ✅ Application startup
- ✅ Shutdown operations
- ✅ Administrative operations (unsubscribe, close)
- ✅ Health checks (unless traced)
- ✅ Background tasks not part of a request

**Problem** (investigate):
- ❌ Message processing logs showing `-`
- ❌ Business logic showing `-`
- ❌ External service calls showing `-`

---

## Configuration Reference

### MDC Fields

| Field | Description | Set By |
|-------|-------------|--------|
| `traceId` | W3C trace ID (32 hex chars) | `TraceContextUtil.mdcScope()` |
| `spanId` | W3C span ID (16 hex chars) | `TraceContextUtil.mdcScope()` |
| `correlationId` | Application correlation ID | Application code |
| `messageId` | Queue message ID | `QueueHandler`, `OutboxConsumer` |
| `setupId` | Database setup identifier | REST handlers |
| `queueName` | Queue name being operated on | REST handlers |
| `topic` | Topic/queue name for consumers | `OutboxConsumer`, `PgNativeQueueConsumer` |

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PEEGEEQ_TRACE_ENABLED` | `true` | Enable/disable trace context propagation |
| `LOG_LEVEL` | `INFO` | Root log level |

### Logback Conversion Words

| Pattern | Converter Class | Description |
|---------|-----------------|-------------|
| `%vxTrace` | `VertxTraceIdConverter` | Trace ID from MDC or Vert.x Context |
| `%vxSpan` | `VertxSpanIdConverter` | Span ID from MDC or Vert.x Context |
| `%X{key:-}` | Standard MDC | Any MDC key with default `-` |

---

## FAQ & Troubleshooting

### Q: Do I need to configure anything special?

**A**: Just add the converter registrations to your `logback.xml` and use `%vxTrace`/`%vxSpan` patterns. Tracing is automatic!

### Q: What if I don't send a traceparent header?

**A**: PeeGeeQ creates a new root trace automatically. The message will still be traced.

### Q: Why do unsubscribe/shutdown logs show `[trace=-]`?

**A**: These are administrative operations, not part of a traced request. This is **expected behavior**.

### Q: Trace IDs are blank during message processing!

**Diagnosis**:
1. Check message was sent with headers:
   ```java
   // Wrong - no headers
   producer.send(payload, correlationId).get();
   
   // Correct
   headers.put("traceparent", rootSpan.traceparent());
   producer.send(payload, headers, correlationId).get();
   ```

2. Check traceparent format is valid W3C format

3. Check database:
   ```sql
   SELECT * FROM message_headers WHERE message_id = 1;
   ```

### Q: Different trace IDs in producer vs consumer?

**A**: This is **normal** for async message queues unless you explicitly propagate the same trace:

```java
// Propagate trace from HTTP request to message
String traceId = MDC.get("traceId");
String newSpanId = TraceContextUtil.generateSpanId();
TraceCtx childSpan = parentSpan.childSpan("send-message");
headers.put("traceparent", childSpan.traceparent());
```

### Q: Can I add custom MDC fields?

**A**: Yes! Add them in your handler and clean up after:

```java
try (var scope = TraceContextUtil.mdcScope(trace)) {
    MDC.put("userId", "user-123");
    MDC.put("orderId", order.getId());
    
    try {
        processOrder(order);
    } finally {
        MDC.remove("userId");
        MDC.remove("orderId");
    }
}
```

### Q: Does tracing add overhead?

**A**: Minimal - approximately:
- Parsing traceparent: ~1 microsecond
- Setting MDC: ~1 microsecond
- Total: < 0.1% for typical message processing

---

## Integration with External Tools

### OpenTelemetry

```java
// OpenTelemetry span with PeeGeeQ trace context
Span span = tracer.spanBuilder("process-order").startSpan();
String traceId = span.getSpanContext().getTraceId();
String spanId = span.getSpanContext().getSpanId();
String traceparent = String.format("00-%s-%s-01", traceId, spanId);

headers.put("traceparent", traceparent);
producer.send(order, headers, correlationId).get();
```

### Jaeger / Zipkin

PeeGeeQ's W3C Trace Context is fully compatible. Use the same traceparent format.

### AWS X-Ray

```java
AWSXRay.beginSegment("order-service");
Segment segment = AWSXRay.getCurrentSegment();
String traceparent = String.format("00-%s-%s-01", 
    segment.getTraceId().toString(), segment.getId());
headers.put("traceparent", traceparent);
```

### ELK Stack (Logstash)

```ruby
filter {
  grok {
    match => {
      "message" => ".*\[trace=%{DATA:traceId} span=%{DATA:spanId}\].*"
    }
  }
}
```

### Grafana Loki (Promtail)

```yaml
pipeline_stages:
  - regex:
      expression: '.*\[trace=(?P<traceId>[^\s]*) span=(?P<spanId>[^\s]*)\].*'
  - labels:
      traceId:
      spanId:
```

---

## Best Practices

### 1. Always Use mdcScope() for Automatic Cleanup

```java
// ✅ Correct
try (var scope = TraceContextUtil.mdcScope(trace)) {
    processMessage(message);
}  // MDC auto-cleared

// ❌ Wrong - manual management prone to leaks
MDC.put("traceId", trace.traceId());
processMessage(message);
MDC.remove("traceId");  // Easy to forget!
```

### 2. Always Send Trace Context in Headers

```java
// ✅ Correct
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
producer.send(payload, headers, correlationId).get();

// ❌ Wrong - no trace context
producer.send(payload, correlationId).get();
```

### 3. Create Child Spans for Each Service Call

```java
// ✅ Correct - new span for external call
TraceCtx child = parentSpan.childSpan("external-api");
httpClient.post("/api/orders")
    .putHeader("traceparent", child.traceparent())
    .send();

// ❌ Wrong - reusing parent span
httpClient.post("/api/orders")
    .putHeader("traceparent", parentSpan.traceparent())  // Don't reuse!
    .send();
```

### 4. Use Business-Meaningful Correlation IDs

```java
// ✅ Correct
String correlationId = "order-" + order.getId();
String correlationId = "customer-" + customer.getId();

// ❌ Wrong
String correlationId = UUID.randomUUID().toString();  // Not meaningful
```

### 5. Log at Key Points

```java
// ✅ Correct - log key milestones
logger.info("Processing order: {}", order.getId());
logger.info("Validating order");
validateOrder(order);
logger.info("Saving order");
saveOrder(order);
logger.info("Order complete");

// ❌ Wrong - no visibility
validateOrder(order);
saveOrder(order);
```

### 6. Include Trace Context in Error Logs

```java
try {
    processOrder(order);
} catch (Exception e) {
    // ✅ MDC still set - trace context in error
    logger.error("Error processing order: {}", order.getId(), e);
    throw e;
}
```

### 7. Use Consistent Log Format Across Services

```xml
<!-- All services should use the same format -->
<pattern>%d{...} [trace=%vxTrace span=%vxSpan] %logger - %msg%n</pattern>
```

---

## Examples

### Example 1: Simple Order Processing

```java
public void submitOrder(Order order) {
    TraceCtx rootSpan = TraceCtx.createNew();
    
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", rootSpan.traceparent());
    
    producer.send(order, headers, "order-" + order.getId()).get();
}

public void processOrders() {
    AsyncTraceUtils.tracedConsumer(vertx, "orders", message -> {
        logger.info("Processing order: {}", message.body().getId());
        processOrder(message.body());
        logger.info("Order complete");
    });
}
```

### Example 2: Multi-Service Flow

```java
// Service 1: API Gateway
@POST("/orders")
public Response createOrder(Order order, @HeaderParam("traceparent") String traceparent) {
    TraceCtx trace = TraceCtx.parseOrCreate(traceparent);
    TraceCtx childSpan = trace.childSpan("queue-message");
    
    headers.put("traceparent", childSpan.traceparent());
    orderProducer.send(order, headers, "order-" + order.getId()).get();
    
    return Response.accepted().build();
}

// Service 2: Order Processor
AsyncTraceUtils.tracedConsumer(vertx, "orders", message -> {
    TraceCtx current = TraceContextUtil.captureTraceContext();
    
    // Call external service with child span
    TraceCtx apiSpan = current.childSpan("validate-api");
    httpClient.post("/validate")
        .putHeader("traceparent", apiSpan.traceparent())
        .send();
    
    // Forward to fulfillment with child span
    TraceCtx fulfillSpan = current.childSpan("fulfillment");
    fulfillmentProducer.send(order, 
        Map.of("traceparent", fulfillSpan.traceparent()), 
        message.correlationId()).get();
});

// Service 3: Fulfillment
AsyncTraceUtils.tracedConsumer(vertx, "fulfillment", message -> {
    logger.info("Fulfilling order");
    // All three services share the same traceId!
});
```

**Log Search**: `grep "trace=abc123"` shows the **entire flow** across all 3 services!

---

## Testing Your Implementation

```bash
# Run the demonstration test
mvn test -Dtest=TraceIdSpanIdDemoTest -pl peegeeq-integration-tests

# Check logs - observe traceId constant, spanId changing
grep "trace=" logs/smoke-tests.log
```

**Expected Output**:
```
[trace=2f1b5099dabac0a4f947103d6449dff4 span=30df90ea7da79b22] ROOT SPAN
[trace=2f1b5099dabac0a4f947103d6449dff4 span=5df007e10e20ffbb] CHILD 1 (parent: root)
[trace=2f1b5099dabac0a4f947103d6449dff4 span=b2bb9612255e77cd] CHILD 2 (parent: child1)
```

---

## Conclusion

PeeGeeQ's tracing provides:

- **End-to-end visibility**: Track requests across all services
- **Fast debugging**: Find all logs with `grep "trace=xxx"`
- **W3C compliance**: Compatible with all major observability tools
- **Automatic MDC management**: Clean, safe trace context handling
- **Production-ready**: Tested and verified

**Get Started**: Add the logback configuration, send messages with traceparent headers, and search your logs!

---

*Document: PEEGEEQ_TRACING_USER_GUIDE.md*  
*Version: 2.0*  
*Last Updated: 2026-01-06*
