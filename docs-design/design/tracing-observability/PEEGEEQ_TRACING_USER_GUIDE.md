# PeeGeeQ Distributed Tracing User Guide

**Version**: 2.0  
**Last Updated**: 2026-01-08  
**Status**: ✅ Production-Ready

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Overview](#overview)
3. [W3C Trace Context](#w3c-trace-context)
4. [Logging Configuration](#logging-configuration)
5. [Producer Usage](#producer-usage)
6. [Consumer Usage](#consumer-usage)
7. [REST API Usage](#rest-api-usage)
8. [Searching Logs](#searching-logs)
9. [Integration with Observability Tools](#integration-with-observability-tools)
10. [Troubleshooting](#troubleshooting)

---

## Quick Start

### 1. Configure Logback

Add MDC placeholders to your `logback.xml`:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

### 2. Send Message with Trace Context

```java
// Trace context is automatically created and propagated
producer.send(payload, headers, correlationId).get();
```

Or with explicit trace headers:

```java
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
headers.put("correlationId", "order-12345");

producer.send(payload, headers, correlationId).get();
```

### 3. Consumer Automatically Gets Trace Context

```java
consumer.subscribe(message -> {
    // MDC is automatically populated!
    logger.info("Processing order");  
    // Output: [traceId=4bf92f... spanId=00f067... correlationId=order-12345] Processing order
    
    return CompletableFuture.completedFuture(null);
});
```

### 4. Search Logs

```bash
# Find all logs for a specific trace
grep "traceId=4bf92f3577b34da6a3ce929d0e0e4736" application.log

# Find all logs for a specific order
grep "correlationId=order-12345" application.log
```

---

## Overview

### What is Distributed Tracing?

PeeGeeQ provides **built-in distributed tracing** using the **W3C Trace Context** standard. This enables you to track requests across your entire system—from HTTP ingress through message queues to final processing.

Each log statement automatically includes:

| Field | Description | Example |
|-------|-------------|---------|
| `traceId` | Unique identifier for the entire request flow (32 hex chars) | `4bf92f3577b34da6a3ce929d0e0e4736` |
| `spanId` | Identifier for a specific operation within the trace (16 hex chars) | `00f067aa0ba902b7` |
| `correlationId` | Business-level identifier (e.g., order ID) | `order-12345` |
| `messageId` | Unique message ID | `msg-abc123` |
| `setupId` | PeeGeeQ setup identifier | `my-setup` |
| `queueName` | Queue/topic name | `orders` |

### Why Use Distributed Tracing?

**Without Tracing:**
```
09:00:00 INFO  QueueHandler - Sending message to queue orders
09:00:00 DEBUG Producer - Message sent to topic orders
09:00:01 INFO  Consumer - Processing message
```
*Problem: No way to correlate these logs for a specific request.*

**With Tracing:**
```
09:00:00 [traceId=4bf92f... spanId=a3ce92... correlationId=order-123] INFO  QueueHandler - Sending message
09:00:00 [traceId=4bf92f... spanId=d0e0e4... correlationId=order-123] DEBUG Producer - Message sent
09:00:01 [traceId=4bf92f... spanId=f06700... correlationId=order-123] INFO  Consumer - Processing message
```
*Benefit: Search for `traceId=4bf92f...` to see ALL related logs!*

---

## W3C Trace Context

### Header Format

PeeGeeQ uses the W3C Trace Context standard:

```
traceparent: 00-{trace-id}-{span-id}-{trace-flags}
```

| Component | Length | Description |
|-----------|--------|-------------|
| Version | 2 chars | Always `00` |
| Trace ID | 32 hex chars | Unique identifier for the trace |
| Span ID | 16 hex chars | Identifier for this operation |
| Trace Flags | 2 chars | `01` = recorded/sampled |

**Example:**
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

### Optional Headers

| Header | Purpose | Example |
|--------|---------|---------|
| `tracestate` | Vendor-specific trace data | `datadog=s:2,o:rum` |
| `baggage` | Application key-value pairs | `userId=alice,sessionId=xyz` |

---

## Logging Configuration

### Logback Pattern

Add these patterns to your `logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- JSON format for log aggregators -->
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyNames>traceId,spanId,correlationId,setupId,queueName,messageId</includeMdcKeyNames>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

### MDC Fields Available

| MDC Key | Description |
|---------|-------------|
| `traceId` | W3C trace ID |
| `spanId` | W3C span ID |
| `correlationId` | Business correlation ID |
| `messageId` | Message unique ID |
| `setupId` | PeeGeeQ setup ID |
| `queueName` | Queue/topic name |

---

## Producer Usage

### Basic Send (Auto-Generated Trace)

```java
// Trace context is automatically created
String messageId = producer.send(payload, "order-123").get();
```

### Send with Explicit Headers

```java
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
headers.put("tracestate", "vendor1=value1");
headers.put("baggage", "userId=alice");

String messageId = producer.send(payload, headers, "order-123").get();
```

### Send Continuing Existing Trace

```java
// If you're in a handler with existing trace context
TraceCtx currentTrace = vertx.getOrCreateContext().get(TraceContextUtil.CONTEXT_TRACE_KEY);
TraceCtx childSpan = currentTrace.childSpan("send-order");

Map<String, String> headers = new HashMap<>();
headers.put("traceparent", childSpan.traceparent());

producer.send(payload, headers, correlationId).get();
```

---

## Consumer Usage

### Basic Consumer (Auto MDC)

```java
consumer.subscribe(message -> {
    // MDC is automatically populated from message headers
    logger.info("Processing message: {}", message.getPayload());
    
    // Access trace context if needed
    Map<String, String> headers = message.getHeaders();
    String traceparent = headers.get("traceparent");
    
    return CompletableFuture.completedFuture(null);
});
```

### Propagate Trace to Downstream Services

```java
consumer.subscribe(message -> {
    Map<String, String> headers = message.getHeaders();
    
    // Forward trace context to downstream HTTP call
    httpClient.post("/downstream-service")
        .putHeader("traceparent", headers.get("traceparent"))
        .putHeader("tracestate", headers.get("tracestate"))
        .putHeader("baggage", headers.get("baggage"))
        .send();
    
    return CompletableFuture.completedFuture(null);
});
```

---

## REST API Usage

### Send Message with W3C Headers

```bash
curl -X POST http://localhost:8080/api/v1/queues/my-setup/orders/messages \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  -H "tracestate: datadog=s:2" \
  -H "baggage: userId=alice,sessionId=xyz123" \
  -d '{
    "payload": {"orderId": "12345", "amount": 99.99},
    "correlationId": "order-12345"
  }'
```

### Batch Messages

```bash
curl -X POST http://localhost:8080/api/v1/queues/my-setup/orders/messages/batch \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  -d '{
    "messages": [
      {"payload": {"orderId": "001"}, "correlationId": "order-001"},
      {"payload": {"orderId": "002"}, "correlationId": "order-002"}
    ]
  }'
```

### Verify Headers in Database

```sql
SELECT 
    correlation_id,
    headers->>'traceparent' as traceparent,
    headers->>'tracestate' as tracestate,
    headers->>'baggage' as baggage,
    created_at
FROM queue_messages
WHERE setup_id = 'my-setup' 
  AND queue_name = 'orders'
ORDER BY created_at DESC
LIMIT 10;
```

---

## Searching Logs

### By Trace ID

Find all logs for a complete request flow:

```bash
grep "traceId=4bf92f3577b34da6a3ce929d0e0e4736" *.log
```

### By Correlation ID

Find all logs for a business transaction:

```bash
grep "correlationId=order-12345" *.log
```

### By Span ID

Find logs for a specific operation:

```bash
grep "spanId=00f067aa0ba902b7" *.log
```

### Combined Search

```bash
grep -E "traceId=4bf92f.*correlationId=order-12345" *.log
```

---

## Integration with Observability Tools

### Jaeger

Configure trace export to Jaeger:

```java
// Add OpenTelemetry Jaeger exporter dependency
// <artifactId>opentelemetry-exporter-jaeger</artifactId>

SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(
        JaegerGrpcSpanExporter.builder()
            .setEndpoint("http://jaeger:14250")
            .build()
    ).build())
    .build();
```

### Datadog

```java
// Datadog APM auto-instruments when Java agent is attached
// Headers are automatically propagated via traceparent
```

### Grafana/Loki

Use JSON logging format and query by trace:

```logql
{app="peegeeq"} | json | traceId="4bf92f3577b34da6a3ce929d0e0e4736"
```

### Prometheus Metrics

PeeGeeQ exposes metrics at `/metrics`:

```
peegeeq_messages_sent_total{topic="orders",setup_id="my-setup"} 1234
peegeeq_messages_received_total{topic="orders",consumer_group="cg1"} 1230
peegeeq_message_processing_seconds{quantile="0.99"} 0.045
```

---

## Troubleshooting

### Blank Trace IDs in Logs

**Problem:** Logs show `[traceId= spanId=]`

**Causes:**
1. **Startup/initialization code** - Before any request context exists
2. **Background threads** - Workers not wrapped with trace utilities
3. **Missing MDC cleanup** - Previous trace leaked to new request

**Solutions:**
- For background work, use `AsyncTraceUtils.executeBlockingTraced()`
- For Event Bus consumers, use `AsyncTraceUtils.tracedConsumer()`
- Ensure MDC scope is always closed (use try-with-resources)

### Trace ID Changes Mid-Request

**Problem:** Different trace IDs appear for the same logical request

**Cause:** New trace created instead of continuing existing one

**Solution:** Always extract and propagate the incoming `traceparent`:

```java
// Extract from incoming request
String traceparent = ctx.request().getHeader("traceparent");
TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);

// Create child span for your operation
TraceCtx childSpan = trace.childSpan("my-operation");
```

### MDC Values Leak Between Requests

**Problem:** Wrong trace/correlation ID appears in logs

**Cause:** MDC not cleared after request completion

**Solution:** Always use scoped MDC:

```java
try (var scope = TraceContextUtil.mdcScope(trace)) {
    // Your code here
} // MDC automatically cleared
```

### Consumer Missing Trace Context

**Problem:** Consumer logs show empty trace IDs

**Solution:** Ensure headers are preserved through the message flow:

```java
// Check message headers
Map<String, String> headers = message.getHeaders();
logger.debug("Message headers: {}", headers);

// Verify traceparent exists
String traceparent = headers.get("traceparent");
if (traceparent == null) {
    logger.warn("No traceparent in message!");
}
```

---

## Best Practices

1. **Always use correlation IDs** - Set business-meaningful IDs (order ID, customer ID)

2. **Propagate trace headers** - When calling downstream services, forward `traceparent`, `tracestate`, and `baggage`

3. **Use scoped MDC** - Always use `try (var scope = TraceContextUtil.mdcScope(trace))` 

4. **Don't trust MDC directly** - In async code, always get trace from Vert.x Context first

5. **Create child spans** - For significant operations, create child spans to see timing breakdown

6. **Log at boundaries** - Log when entering/exiting services with trace context

7. **Include context in errors** - When logging errors, the trace ID enables finding related logs

```java
try {
    processOrder(order);
} catch (Exception e) {
    // Trace ID is automatically included via MDC
    logger.error("Failed to process order: {}", order.getId(), e);
    throw e;
}
```
