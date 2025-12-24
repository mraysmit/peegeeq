# Distributed Tracing Quick Reference

## 🚀 5-Minute Quick Start

### 1. Configure Logback

Add MDC placeholders to your `logback.xml`:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

### 2. Send Message with Trace Context

```java
// Generate trace IDs
String traceId = UUID.randomUUID().toString().replace("-", "") + 
                 UUID.randomUUID().toString().replace("-", "").substring(0, 32 - 32);
String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
String correlationId = "order-12345";

// Create traceparent header
String traceparent = String.format("00-%s-%s-01", traceId, spanId);

// Send message
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", traceparent);
headers.put("correlationId", correlationId);

producer.send(payload, headers, correlationId).get();
```

### 3. Consumer Automatically Gets Trace Context

```java
consumer.subscribe(message -> {
    // MDC is automatically populated!
    logger.info("Processing order");  
    // Output: [traceId=52a0d370... spanId=d6ae23cb... correlationId=order-12345] Processing order
    
    return CompletableFuture.completedFuture(null);
});
```

### 4. Search Logs

```bash
# Find all logs for a specific request
grep "traceId=52a0d3705aba4122aa266f1216f87e10" application.log

# Find all logs for a specific order
grep "correlationId=order-12345" application.log
```

## 📋 W3C Trace Context Format

```
traceparent: 00-{trace-id}-{parent-id}-{trace-flags}
             │  │          │           │
             │  │          │           └─ Trace flags (01 = sampled)
             │  │          └─ Span ID (16 hex chars)
             │  └─ Trace ID (32 hex chars)
             └─ Version (00)
```

Example:
```
traceparent: 00-52a0d3705aba4122aa266f1216f87e10-d6ae23cb58e1467d-01
```

## 🔑 MDC Keys

| Key | Description | Example |
|-----|-------------|---------|
| `traceId` | Unique ID for entire request flow | `52a0d3705aba4122aa266f1216f87e10` |
| `spanId` | Unique ID for specific operation | `d6ae23cb58e1467d` |
| `correlationId` | Business-level identifier | `order-12345` |
| `messageId` | Message ID (auto-set by PeeGeeQ) | `1` |
| `topic` | Topic name (auto-set by PeeGeeQ) | `orders` |

## 🎯 Common Patterns

### Pattern 1: HTTP → Queue → Consumer

```java
// 1. HTTP endpoint receives request with traceparent header
@POST
public Response createOrder(Order order, @HeaderParam("traceparent") String traceparent) {
    // Extract trace context
    String[] parts = traceparent.split("-");
    String traceId = parts[1];
    String spanId = parts[2];
    
    // Send to queue
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    headers.put("correlationId", "order-" + order.getId());
    
    producer.send(order, headers, "order-" + order.getId()).get();
    return Response.ok().build();
}

// 2. Consumer automatically gets trace context
consumer.subscribe(message -> {
    // All logs here will show the same traceId from the HTTP request!
    logger.info("Processing order");
    return CompletableFuture.completedFuture(null);
});
```

### Pattern 2: Propagate to Downstream Service

```java
consumer.subscribe(message -> {
    // Get current trace context
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");
    
    // Generate new span ID for downstream call
    String newSpanId = generateSpanId();
    String traceparent = String.format("00-%s-%s-01", traceId, newSpanId);
    
    // Call downstream service
    httpClient.post("/api/external")
        .putHeader("traceparent", traceparent)
        .send();
    
    return CompletableFuture.completedFuture(null);
});
```

### Pattern 3: OpenTelemetry Integration

```java
// OpenTelemetry automatically injects traceparent
Span span = tracer.spanBuilder("send-order").startSpan();
try (Scope scope = span.makeCurrent()) {
    // PeeGeeQ will pick up trace context from OpenTelemetry
    producer.send(order, headers, correlationId).get();
} finally {
    span.end();
}
```

## 🔍 Troubleshooting

### Problem: Blank trace IDs in logs

```
[traceId= spanId= correlationId=] Initializing PeeGeeQ Manager
```

**Solution**: This is **normal** for logs outside message processing (startup, shutdown, background tasks).

### Problem: Consumer logs have blank trace IDs

```
[traceId= spanId= correlationId=] Processing message
```

**Causes**:
1. Message sent without `traceparent` header
2. Logback pattern missing MDC placeholders
3. MDC cleared prematurely

**Solution**: See [DISTRIBUTED_TRACING_GUIDE.md](DISTRIBUTED_TRACING_GUIDE.md) troubleshooting section.

## 📊 Integration with Observability Tools

### OpenTelemetry

```java
// Works automatically - OpenTelemetry injects traceparent headers
```

### Datadog

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

## 🧪 Test It

```bash
mvn test -Dtest=DistributedTracingTest -Pintegration-tests -pl peegeeq-outbox
```

Look for logs with populated trace IDs:
```
[traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-12345]
```

## 📚 Full Documentation

- **[Distributed Tracing Guide](DISTRIBUTED_TRACING_GUIDE.md)** - Complete guide
- **[Understanding Blank Trace IDs](UNDERSTANDING_BLANK_TRACE_IDS.md)** - Troubleshooting
- **[MDC Distributed Tracing](MDC_DISTRIBUTED_TRACING.md)** - API reference

## 💡 Best Practices

1. ✅ Always include `traceparent` header when sending messages
2. ✅ Use correlation IDs for business-level tracking
3. ✅ Generate new span IDs when calling downstream services
4. ✅ Use structured logging (JSON) for better searchability
5. ❌ Don't modify MDC manually in message handlers
6. ❌ Don't expect trace IDs in initialization/shutdown logs

## 🎯 Key Takeaways

- **Blank trace IDs are normal** for logs outside message processing
- **Consumer logs should have populated trace IDs** when processing messages
- **MDC is automatic** - no code changes needed in message handlers
- **W3C Trace Context** is compatible with all major observability tools
- **Minimal overhead** - production-ready with < 1μs per operation

