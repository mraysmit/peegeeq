# Distributed Tracing FAQ

## Common Questions

### Q: Why don't producer logs show trace IDs after sending a message?

**A**: Message queues are **asynchronous** (fire-and-forget). The producer sends the message and moves on. The trace context is stored in the **message headers**, not in the producer's thread.

```java
// Producer
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", "00-abc123-span1-01");
producer.send(message, headers, correlationId).get();

logger.info("Message sent");  // [traceId= ] ← This is NORMAL!
```

The trace context will be available when the **consumer** processes the message.

---

### Q: Why don't unsubscribe/close logs show trace IDs?

**A**: Unsubscribe and close are **administrative operations** that happen during cleanup, not part of the traced request flow.

```
Timeline:
T=0: Request arrives [traceId=abc123]
T=1: Message sent [traceId=abc123 in headers]
T=2: Request complete [traceId=abc123]
     ↓ Trace context cleared
     
T=5: Consumer processes [traceId=abc123 from headers]
T=6: Processing complete [traceId=abc123]
     ↓ Trace context cleared

T=100: Application shutdown
       └─ Unsubscribe [traceId= ] ← No trace context (not part of any request)
```

This is **normal and expected**.

---

### Q: How do I see the full request flow across multiple services?

**A**: Search logs by `traceId`. All services that process the same request will have the **same traceId**.

```bash
# Find all logs for a specific request
grep "traceId=abc123" *.log
```

Example output:
```
# Service 1: REST API
12:34:56.789 [http-1] INFO  OrderAPI - [traceId=abc123 spanId=span1] Received order

# Service 2: Consumer (different process)
12:34:57.123 [consumer-1] INFO  OrderConsumer - [traceId=abc123 spanId=span2] Processing order

# Service 3: External service (different process)
12:34:57.456 [http-2] INFO  ExternalService - [traceId=abc123 spanId=span3] Processing request
```

See [FullDistributedTracingExample.java](../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java) for a working example.

---

### Q: Should trace context flow back from consumer to producer?

**A**: **No**. Message queues are asynchronous. There's no synchronous "response" that flows back to the producer.

This is different from HTTP where trace context flows both ways:

```
HTTP (Synchronous):
Client → Server → Client  ✅ Trace flows both ways

Message Queue (Asynchronous):
Producer → Queue → Consumer  ✅ Trace flows forward only
```

See [DISTRIBUTED_TRACING_ARCHITECTURE.md](DISTRIBUTED_TRACING_ARCHITECTURE.md) for detailed explanation.

---

### Q: How do I propagate trace context to downstream services?

**A**: Extract the trace ID from MDC and create a new span ID:

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

---

### Q: Do I need to manually set MDC in my message handler?

**A**: **No**. PeeGeeQ automatically extracts trace context from message headers and sets MDC. All logs within your message handler will automatically include trace IDs.

```java
consumer.subscribe(message -> {
    // MDC is already set by PeeGeeQ!
    logger.info("Processing order");  
    // Output: [traceId=abc123 spanId=span1 correlationId=order-123] Processing order
    
    processOrder(message.getPayload());
    
    logger.info("Order complete");
    // Output: [traceId=abc123 spanId=span1 correlationId=order-123] Order complete
    
    return CompletableFuture.completedFuture(null);
});
```

---

### Q: What if I create my own threads in the message handler?

**A**: MDC is thread-local, so you need to manually propagate it to new threads:

```java
consumer.subscribe(message -> {
    // Capture MDC before async operation
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    CompletableFuture.runAsync(() -> {
        // Restore MDC in new thread
        if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
        }
        
        logger.info("Processing in async thread");
        // Output: [traceId=abc123 ...] Processing in async thread
        
        // Clear MDC when done
        MDC.clear();
    });
    
    return CompletableFuture.completedFuture(null);
});
```

---

### Q: How do I integrate with OpenTelemetry/Datadog/Elastic APM?

**A**: PeeGeeQ uses W3C Trace Context standard, which is compatible with all major observability tools.

**OpenTelemetry**:
```java
// OpenTelemetry automatically injects traceparent headers
Span span = tracer.spanBuilder("send-order").startSpan();
try (Scope scope = span.makeCurrent()) {
    producer.send(order, headers, correlationId).get();
} finally {
    span.end();
}
```

**Datadog**:
```yaml
# datadog.yaml
apm_config:
  propagation_style_extract:
    - tracecontext  # W3C Trace Context
```

**Elastic APM**:
```properties
# elasticapm.properties
trace_context_propagation_style=tracecontext
```

---

### Q: What's the performance impact?

**A**: Minimal. MDC operations are very fast (< 1μs per operation). There are no network calls - trace context is propagated via message headers only.

---

### Q: Can I use correlation IDs for business tracking?

**A**: **Yes!** Use business identifiers as correlation IDs:

```java
String correlationId = "order-" + orderId;

Map<String, String> headers = new HashMap<>();
headers.put("traceparent", traceparent);
headers.put("correlationId", correlationId);

producer.send(order, headers, correlationId).get();
```

Then search logs by business ID:
```bash
grep "correlationId=order-12345" *.log
```

---

### Q: How do I verify distributed tracing is working?

**A**: Run the test and look for populated trace IDs in consumer logs:

```bash
mvn test -Dtest=DistributedTracingTest -Pintegration-tests -pl peegeeq-outbox
```

Look for logs like:
```
[traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-123] Processing message
```

If you see populated trace IDs, it's working! ✅

---

### Q: Where can I find working examples?

**A**: 

1. **Basic test**: `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/DistributedTracingTest.java`
2. **Full flow**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java`

---

## Quick Reference

| Scenario | Trace IDs | Expected? |
|----------|-----------|-----------|
| Application startup | Blank | ✅ Yes |
| Producer sending | Blank | ✅ Yes |
| Unsubscribe/close | Blank | ✅ Yes |
| **Consumer processing** | **Populated** | **✅ Yes** |
| Business logic in handler | Populated | ✅ Yes |

## See Also

- [Quick Reference](DISTRIBUTED_TRACING_QUICK_REFERENCE.md) - 5-minute quick start
- [Complete Guide](DISTRIBUTED_TRACING_GUIDE.md) - Full documentation
- [Architecture](DISTRIBUTED_TRACING_ARCHITECTURE.md) - How it works
- [Understanding Blank Trace IDs](UNDERSTANDING_BLANK_TRACE_IDS.md) - Troubleshooting

