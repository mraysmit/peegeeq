# Distributed Tracing Architecture

## Understanding Trace Context Flow

### ❌ Common Misconception: "Trace should flow back to producer"

Many developers expect trace context to flow **bidirectionally** like HTTP:

```
Producer                           Consumer
────────                           ────────
[traceId=abc...]                   
  │
  ├─ Send message ──────────────►  [traceId=abc...]
  │                                  │
  │ ◄──────────── Response ─────────┤  ❌ WRONG for message queues!
  │                                  
[traceId=abc...] ← Expected but doesn't happen
```

**Why this is wrong**: Message queues are **asynchronous** and **fire-and-forget**. There's no synchronous response that flows back to the producer.

### ✅ Correct: Asynchronous Message Queue Pattern

```
Producer Thread                    Consumer Thread
─────────────────                  ───────────────
[traceId=abc...]                   
  │
  ├─ Send message ──────────────►  [traceId=abc...] ← Trace from message headers
  │                                  │
  │                                  ├─ Process message
  │                                  │
  └─ send() returns                  └─ Complete processing
     (producer done)                    (consumer done)
     
Later (cleanup):
[traceId= ] ← No trace context     [traceId= ] ← No trace context
  │                                  │
  └─ Unsubscribe                     └─ Close consumer
     (administrative, not traced)       (administrative, not traced)
```

**Key Points**:
1. Producer sends message with trace context in **headers**
2. Producer's `send()` returns immediately (async)
3. Consumer extracts trace context from message headers
4. Consumer processes message with trace context in MDC
5. Cleanup operations (unsubscribe, close) are **not part of the traced request**

## Full Distributed Tracing Flow

The **real power** is tracing across **multiple services**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         traceId=abc123 (same throughout)                    │
└─────────────────────────────────────────────────────────────────────────────┘

HTTP Client          REST API           Queue            Consumer         External Service
───────────          ────────           ─────            ────────         ────────────────
[traceId=abc123]
     │
     ├─ POST /orders ──►[traceId=abc123]
     │                   │
     │                   ├─ Log: "Received order"
     │                   │
     │                   ├─ send(msg) ──►[Store msg]
     │                   │                   │
     │ ◄─ 202 Accepted ──┤                   │
     │                                       │
[traceId=abc123]                             ├─ Poll ──►[traceId=abc123]
     │                                                   │
     │                                                   ├─ Log: "Processing order"
     │                                                   │
     │                                                   ├─ POST /external ──►[traceId=abc123]
     │                                                   │                     │
     │                                                   │                     ├─ Log: "Processing"
     │                                                   │                     │
     │                                                   │ ◄─ 200 OK ──────────┤
     │                                                   │
     │                                                   └─ Log: "Complete"
     │
     └─ Search logs: grep "traceId=abc123" → See ALL logs across ALL services!
```

### Log Output Example

All services show the **same traceId**:

```
# REST API logs
12:34:56.789 [http-thread-1] INFO  OrderAPI - [traceId=abc123 spanId=span1 correlationId=order-12345] Received order
12:34:56.790 [http-thread-1] INFO  OrderAPI - [traceId=abc123 spanId=span1 correlationId=order-12345] Sending to queue

# Consumer logs (different process/server)
12:34:57.123 [consumer-thread-1] INFO  OrderConsumer - [traceId=abc123 spanId=span2 correlationId=order-12345] Processing order
12:34:57.124 [consumer-thread-1] INFO  OrderConsumer - [traceId=abc123 spanId=span2 correlationId=order-12345] Calling external service

# External service logs (different process/server)
12:34:57.456 [http-thread-2] INFO  ExternalService - [traceId=abc123 spanId=span3 correlationId=order-12345] Processing request
12:34:57.457 [http-thread-2] INFO  ExternalService - [traceId=abc123 spanId=span3 correlationId=order-12345] Request complete
```

**Search for `traceId=abc123`** and you see the **entire flow** across all services!

## Trace Context Propagation Mechanisms

### 1. HTTP Headers (Synchronous)

```java
// Client sends request
httpClient.post("/api/orders")
    .putHeader("traceparent", "00-abc123-span1-01")
    .send();

// Server receives and responds
@POST
public Response createOrder(@HeaderParam("traceparent") String traceparent) {
    // Set MDC from traceparent
    TraceContextUtil.setMDCFromTraceparent(traceparent);
    
    // Process...
    
    // Return response with same traceparent
    return Response.ok()
        .header("traceparent", traceparent)
        .build();
}
```

### 2. Message Headers (Asynchronous)

```java
// Producer sends message
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", "00-abc123-span2-01");
producer.send(payload, headers, correlationId).get();

// Consumer receives message
consumer.subscribe(message -> {
    // PeeGeeQ automatically extracts traceparent and sets MDC!
    // All logs here will show traceId=abc123
    logger.info("Processing message");
    return CompletableFuture.completedFuture(null);
});
```

## Span Hierarchy

Each service creates a **new span** while keeping the **same trace ID**:

```
traceId=abc123
│
├─ span1 (HTTP Client → REST API)
│
├─ span2 (REST API → Queue → Consumer)
│
└─ span3 (Consumer → External Service)
```

This creates a **trace tree** that observability tools can visualize:

```
abc123 (trace)
  ├─ span1: POST /orders (200ms)
  │   └─ span2: process-order (5000ms)
  │       └─ span3: POST /external (100ms)
```

## Why Unsubscribe Has Blank Trace IDs

```
Timeline:

T=0: HTTP request arrives [traceId=abc123]
T=1: Message sent to queue [traceId=abc123 in headers]
T=2: HTTP response returned [traceId=abc123]
     ↓
     Producer's work is DONE. Trace context cleared.
     
T=5: Consumer polls message [traceId=abc123 from headers]
T=6: Consumer processes message [traceId=abc123 in MDC]
T=7: Consumer completes [traceId=abc123]
     ↓
     Consumer's work is DONE. Trace context cleared.

T=100: Application shutdown
       ├─ Unsubscribe [traceId= ] ← No trace context (not part of any request)
       └─ Close [traceId= ] ← No trace context (administrative operation)
```

**Unsubscribe is not part of the traced request flow**. It's an administrative operation that happens during cleanup, long after the request has been processed.

## Best Practices

### ✅ DO: Propagate trace context forward

```java
// Consumer calls downstream service
String traceId = MDC.get("traceId");
String newSpanId = generateSpanId();
String traceparent = String.format("00-%s-%s-01", traceId, newSpanId);

httpClient.post("/downstream")
    .putHeader("traceparent", traceparent)
    .send();
```

### ✅ DO: Search logs by trace ID

```bash
# Find all logs for a specific request
grep "traceId=abc123" *.log

# Or in log aggregation tools
traceId:"abc123"
```

### ❌ DON'T: Expect trace context in producer after send()

```java
// Producer
producer.send(message, headers, correlationId).get();
// Trace context is in the message, not in this thread anymore
logger.info("Message sent"); // [traceId= ] ← This is normal!
```

### ❌ DON'T: Expect trace context in cleanup operations

```java
// Cleanup
consumer.unsubscribe();  // [traceId= ] ← This is normal!
consumer.close();        // [traceId= ] ← This is normal!
```

## Summary

| Pattern | Trace Flow | Example |
|---------|------------|---------|
| **HTTP (Sync)** | Bidirectional | Client → Server → Client |
| **Message Queue (Async)** | Forward only | Producer → Queue → Consumer |
| **Full Distributed** | Multi-hop forward | Client → API → Queue → Consumer → External |

**Key Insight**: Message queues are **asynchronous**. Trace context flows **forward** through messages, not **backward** to producers. For full distributed tracing, trace context propagates through **multiple services** in a **forward chain**.

## See Also

- [Distributed Tracing Guide](DISTRIBUTED_TRACING_GUIDE.md) - Complete guide
- [Full Distributed Tracing Example](../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java) - Working code
- [Understanding Blank Trace IDs](UNDERSTANDING_BLANK_TRACE_IDS.md) - Troubleshooting

