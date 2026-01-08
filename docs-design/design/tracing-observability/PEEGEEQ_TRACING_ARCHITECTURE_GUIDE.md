# PeeGeeQ Tracing Architecture Guide

**Version**: 2.0  
**Last Updated**: 2026-01-08  
**Status**: ✅ Production-Ready  
**Audience**: Contributors and maintainers

---

## Table of Contents

1. [Core Principle](#core-principle)
2. [Architecture Overview](#architecture-overview)
3. [Trace Context Model](#trace-context-model)
4. [Component Reference](#component-reference)
5. [Async Patterns](#async-patterns)
6. [Event Bus Integration](#event-bus-integration)
7. [Implementation Checklist](#implementation-checklist)
8. [Testing Strategy](#testing-strategy)

---

## Core Principle

> **MDC is NOT the source of truth. Vert.x Context is.**

This is the non-negotiable foundation of PeeGeeQ's tracing architecture.

### Why This Matters

- **MDC is thread-local** - It doesn't survive thread hops
- **Vert.x hops threads aggressively** - Event loop → Worker → Event loop
- **Worker threads are reused** - Previous MDC values can leak

### The Rule

1. Store trace context in **Vert.x Context** (single source of truth)
2. Re-apply MDC **at every async boundary**
3. **Always clear MDC** after use (via scoped cleanup)

**If you violate this, logs will eventually lie.**

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         HTTP Request                                    │
│  Headers: traceparent, tracestate, baggage                              │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    PeeGeeQRestServer (Router)                           │
│  1. Extract/create TraceCtx from traceparent header                     │
│  2. Store in Vert.x Context: ctx.put(CONTEXT_TRACE_KEY, trace)          │
│  3. Scope MDC for handler duration                                      │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         QueueHandler                                    │
│  1. Get TraceCtx from Vert.x Context                                    │
│  2. Inject traceparent into message headers                             │
│  3. Log with MDC (traceId, spanId, correlationId)                       │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    PgNativeQueueProducer                                │
│  Store headers (including traceparent) in queue_messages.headers JSONB  │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         PostgreSQL                                      │
│  queue_messages.headers = '{"traceparent": "00-...", ...}'              │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    PgNativeQueueConsumer                                │
│  1. Extract traceparent from message.headers                            │
│  2. Parse into TraceCtx                                                 │
│  3. Store in Vert.x Context                                             │
│  4. Scope MDC for handler duration                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Trace Context Model

### TraceCtx Record

Location: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceCtx.java`

```java
public record TraceCtx(
    String traceId,       // 32 hex characters
    String spanId,        // 16 hex characters  
    String parentSpanId,  // Parent span (null for root)
    String traceparent    // Full W3C header string
) {
    // Factory methods
    static TraceCtx createNew();
    static TraceCtx parseOrCreate(String traceparent);
    
    // Create child span (same traceId, new spanId)
    TraceCtx childSpan(String operationName);
}
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Immutable record** | Thread-safe, can be shared across contexts |
| **W3C format only** | Industry standard, compatible with all APM tools |
| **Child span preserves traceId** | Enables end-to-end trace correlation |
| **parentSpanId tracking** | Enables span hierarchy reconstruction |

---

## Component Reference

### TraceContextUtil

Location: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceContextUtil.java`

**Constants:**
```java
// MDC keys
String MDC_TRACE_ID = "traceId";
String MDC_SPAN_ID = "spanId";
String MDC_CORRELATION_ID = "correlationId";
String MDC_TOPIC = "topic";
String MDC_MESSAGE_ID = "messageId";
String MDC_SETUP_ID = "setupId";
String MDC_QUEUE_NAME = "queueName";

// Vert.x Context key
String CONTEXT_TRACE_KEY = "trace.ctx";
```

**Key Methods:**

| Method | Purpose |
|--------|---------|
| `parseOrCreate(String)` | Parse traceparent or create new root trace |
| `mdcScope(TraceCtx)` | Returns AutoCloseable that sets/clears MDC |
| `captureTraceContext()` | Capture current MDC state as TraceCtx |
| `setMDC(String key, String value)` | Set individual MDC value |

### AsyncTraceUtils

Location: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/AsyncTraceUtils.java`

**Wrapper APIs (use these, not raw Vert.x APIs):**

| Method | Replaces | Purpose |
|--------|----------|---------|
| `executeBlockingTraced()` | `worker.executeBlocking()` | Blocking code with trace propagation |
| `publishWithTrace()` | `eventBus.publish()` | Fire-and-forget with traceparent injection |
| `requestWithTrace()` | `eventBus.request()` | Request/reply with trace propagation |
| `tracedConsumer()` | `eventBus.consumer()` | Consumer with auto trace extraction |
| `traceAsyncAction()` | Raw Future chains | Wrap async operations |

---

## Async Patterns

### ❌ WRONG: Raw executeBlocking

```java
// DON'T DO THIS - MDC will be wrong/empty on worker thread
worker.executeBlocking(promise -> {
    logger.info("Processing..."); // traceId will be wrong!
    promise.complete(result);
});
```

### ✅ CORRECT: Traced executeBlocking

```java
AsyncTraceUtils.executeBlockingTraced(vertx, worker, false, () -> {
    logger.info("Processing..."); // traceId is correct
    return result;
});
```

### How It Works Internally

```java
public static <T> Future<T> executeBlockingTraced(
    Vertx vertx, WorkerExecutor worker, boolean ordered, Callable<T> blocking
) {
    // Step 1: Capture trace from Vert.x Context (on event loop thread)
    Context ctx = vertx.getOrCreateContext();
    TraceCtx parent = ctx.get(CONTEXT_TRACE_KEY);
    
    // Step 2: Create child span for this blocking operation
    TraceCtx span = parent.childSpan("executeBlocking");
    
    // Step 3: Execute on worker thread with MDC scoped
    return worker.executeBlocking(() -> {
        try (var scope = TraceContextUtil.mdcScope(span)) {
            return blocking.call();
        } // MDC cleared here, even if exception thrown
    }, ordered);
}
```

---

## Event Bus Integration

### Fire-and-Forget (publish)

**Producer side:**
```java
// Captures current trace, creates child span, injects traceparent
AsyncTraceUtils.publishWithTrace(vertx, "orders.created", orderEvent);
```

**Consumer side:**
```java
// Extracts traceparent, stores in context, scopes MDC
AsyncTraceUtils.tracedConsumer(vertx, "orders.created", msg -> {
    logger.info("Processing order"); // Trace context is correct
    // Handle message...
});
```

### Request/Response (RPC)

**Requester:**
```java
AsyncTraceUtils.requestWithTrace(vertx, "inventory.check", checkRequest)
    .onSuccess(reply -> {
        // MDC is re-scoped for reply handling
        logger.info("Inventory response: {}", reply.body());
    });
```

**Responder:**
```java
AsyncTraceUtils.tracedConsumer(vertx, "inventory.check", msg -> {
    // Trace context extracted from request headers
    InventoryResponse response = checkInventory(msg.body());
    msg.reply(response);
});
```

### Internal Implementation

```java
public static void publishWithTrace(Vertx vertx, String address, Object message) {
    // Get or create trace from Vert.x Context
    TraceCtx parent = getOrCreateTrace(vertx);
    
    // Create child span for this publish
    TraceCtx childSpan = parent.childSpan("publish:" + address);
    
    // Inject traceparent into delivery options
    DeliveryOptions opts = new DeliveryOptions()
        .addHeader("traceparent", childSpan.traceparent());
    
    vertx.eventBus().publish(address, message, opts);
}
```

---

## Implementation Checklist

When adding new components or modifying existing ones:

### HTTP Handlers

- [ ] Extract `traceparent` from request headers
- [ ] Parse into `TraceCtx` (use `parseOrCreate` for safety)
- [ ] Store in Vert.x Context: `ctx.put(CONTEXT_TRACE_KEY, trace)`
- [ ] Wrap handler body in `mdcScope(trace)`
- [ ] Inject `traceparent` into outgoing calls

### Message Producers

- [ ] Get `TraceCtx` from Vert.x Context
- [ ] Create child span if appropriate
- [ ] Include `traceparent` in message headers map

### Message Consumers

- [ ] Extract `traceparent` from message headers
- [ ] Parse into `TraceCtx`
- [ ] Store in Vert.x Context
- [ ] Wrap handler in `mdcScope(trace)`
- [ ] Propagate to downstream calls

### Blocking Operations

- [ ] Never use raw `executeBlocking`
- [ ] Use `AsyncTraceUtils.executeBlockingTraced()`
- [ ] Verify logs show correct traceId on worker threads

### Event Bus Usage

- [ ] Never use raw `eventBus.publish()` or `eventBus.request()`
- [ ] Use `AsyncTraceUtils.publishWithTrace()` / `requestWithTrace()`
- [ ] Use `AsyncTraceUtils.tracedConsumer()` for consumers

---

## Testing Strategy

### Unit Tests

Location: `peegeeq-api/src/test/java/dev/mars/peegeeq/api/tracing/`

```java
@Test
void testTraceCtxParsing() {
    String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    TraceCtx trace = TraceCtx.parseOrCreate(traceparent);
    
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.traceId());
    assertEquals("00f067aa0ba902b7", trace.spanId());
}

@Test
void testChildSpanPreservesTraceId() {
    TraceCtx parent = TraceCtx.createNew();
    TraceCtx child = parent.childSpan("operation");
    
    assertEquals(parent.traceId(), child.traceId());
    assertNotEquals(parent.spanId(), child.spanId());
    assertEquals(parent.spanId(), child.parentSpanId());
}

@Test
void testMdcScopeCleanup() {
    TraceCtx trace = TraceCtx.createNew();
    
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        assertEquals(trace.traceId(), MDC.get("traceId"));
    }
    
    assertNull(MDC.get("traceId")); // Must be cleared
}
```

### Integration Tests

Location: `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/`

```java
@Test
void testTracePropagationEndToEnd() {
    String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    
    // Send message via REST with traceparent header
    HttpResponse response = client.post("/api/v1/queues/test/orders/messages")
        .putHeader("traceparent", traceparent)
        .sendJson(message);
    
    assertEquals(200, response.statusCode());
    
    // Verify traceparent stored in database
    Row row = pgClient.query("SELECT headers->>'traceparent' FROM queue_messages WHERE ...").first();
    assertEquals(traceparent, row.getString(0));
    
    // Consume and verify trace propagated
    Message consumed = consumer.receive();
    assertEquals(traceparent, consumed.getHeaders().get("traceparent"));
}
```

### Concurrency Bleed Test

**Critical test** - Verifies traces don't leak between concurrent requests:

```java
@Test
void testNoTraceBleedUnderConcurrency() {
    int parallelRequests = 100;
    CountDownLatch latch = new CountDownLatch(parallelRequests);
    AtomicInteger failures = new AtomicInteger(0);
    
    for (int i = 0; i < parallelRequests; i++) {
        String expectedTraceId = "trace-" + i;
        
        executor.submit(() -> {
            try (var scope = TraceContextUtil.mdcScope(createTraceWithId(expectedTraceId))) {
                // Simulate work
                Thread.sleep(random.nextInt(10));
                
                // Verify our trace wasn't overwritten
                if (!expectedTraceId.equals(MDC.get("traceId"))) {
                    failures.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        });
    }
    
    latch.await();
    assertEquals(0, failures.get(), "Trace IDs leaked between concurrent requests!");
}
```

---

## Files Reference

| File | Purpose |
|------|---------|
| `peegeeq-api/.../tracing/TraceCtx.java` | Immutable W3C trace context record |
| `peegeeq-api/.../tracing/TraceContextUtil.java` | MDC scoping and parsing utilities |
| `peegeeq-api/.../tracing/AsyncTraceUtils.java` | Vert.x async operation wrappers |
| `peegeeq-api/.../tracing/TraceContext.java` | Legacy trace context (deprecated) |
| `peegeeq-rest/.../PeeGeeQRestServer.java` | HTTP trace extraction/injection |
| `peegeeq-rest/.../handlers/QueueHandler.java` | Message send with trace propagation |

---

## OpenTelemetry Compatibility

This implementation is **OpenTelemetry-ready by design**:

| Current | OTel Equivalent |
|---------|-----------------|
| `TraceCtx` | `SpanContext` |
| `mdcScope()` | `Scope` from `Span.makeCurrent()` |
| `childSpan()` | `Tracer.spanBuilder().setParent()` |
| `traceparent` | W3C propagator |

**Future migration path:**
1. Add OTel SDK dependency
2. Replace `TraceCtx.createNew()` with `tracer.spanBuilder().startSpan()`
3. Replace `mdcScope()` with `span.makeCurrent()`
4. Add exporter configuration
5. Zero application code changes required

---

## Summary

**The three rules of PeeGeeQ tracing:**

1. **Vert.x Context is truth** - Always store/retrieve trace from `ctx.get(CONTEXT_TRACE_KEY)`

2. **MDC is ephemeral** - Re-apply at every async boundary, always use scoped cleanup

3. **Use the wrappers** - Never use raw `executeBlocking`, `publish`, `request`, or `consumer`

Follow these rules and distributed tracing will work correctly across all thread boundaries.
