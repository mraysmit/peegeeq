# PeeGeeQ Tracing Technical Reference

**Version**: 2.0  
**Last Updated**: 2026-01-06  
**Status**: ✅ Production-Ready  
**Authored by**: Mark Andrew Ray-Smith, Cityline Ltd

---

## Table of Contents

1. [Design Principles](#design-principles)
2. [Architecture Overview](#architecture-overview)
3. [Core Components](#core-components)
4. [W3C Trace Context Specification](#w3c-trace-context-specification)
5. [MDC Scope Management](#mdc-scope-management)
6. [Async Boundary Handling](#async-boundary-handling)
7. [Custom Logback Converters](#custom-logback-converters)
8. [Implementation Patterns](#implementation-patterns)
9. [Thread Safety](#thread-safety)
10. [Database Schema](#database-schema)
11. [Performance Characteristics](#performance-characteristics)
12. [API Reference](#api-reference)
13. [Compatibility Matrix](#compatibility-matrix)
14. [Implementation Checklist](#implementation-checklist)
15. [Event Causality and Identifier Taxonomy](#event-causality-and-identifier-taxonomy)

---

## Design Principles

### Core Principle (Non-Negotiable)

> **MDC is NOT the source of truth. Vert.x Context is.**

- MDC is thread-local
- Vert.x hops threads aggressively
- Worker threads are reused

**Rule**: Store trace context in the Vert.x `Context`. Re-apply MDC **at every async boundary**. Always clear MDC after use.

If you violate this, logs will eventually lie.

### Design Goals

1. **W3C Trace Context compliance** - Standard `traceparent` format
2. **OpenTelemetry-compatible by construction** - Zero rewrite for future OTel adoption
3. **Vert.x-native** - Context stored in Vert.x Context, not just MDC
4. **Automatic cleanup** - `mdcScope()` returns `AutoCloseable`
5. **No trace bleed** - Worker threads never leak trace context

### Why Custom Converters?

Standard MDC converters (`%X{traceId}`) fail in Vert.x because:
1. Event loop code may not have MDC set
2. Vert.x Context contains trace info, but MDC doesn't
3. Need dual-lookup: MDC first, Vert.x Context fallback

Solution: `VertxTraceIdConverter` and `VertxSpanIdConverter` provide:
- `%vxTrace` - Trace ID from MDC or Vert.x Context
- `%vxSpan` - Span ID from MDC or Vert.x Context
- Returns `-` when no trace exists (clear indicator)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PeeGeeQ Tracing Architecture                      │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────┐     ┌─────────────────────────┐
│    TraceCtx Record      │     │   TraceContextUtil      │
│  (Immutable W3C Data)   │     │   (MDC Scope Mgmt)      │
│                         │     │                         │
│  - traceId (32 hex)     │     │  - mdcScope(TraceCtx)   │
│  - spanId (16 hex)      │     │  - captureTraceContext()│
│  - parentSpanId         │     │  - CONTEXT_TRACE_KEY    │
│  - traceparent()        │     │  - parseOrCreate()      │
│  - childSpan(name)      │     │                         │
└───────────┬─────────────┘     └───────────┬─────────────┘
            │                               │
            └───────────────┬───────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AsyncTraceUtils                                   │
│                   (Async Boundary Wrappers)                                 │
│                                                                             │
│  - executeBlockingTraced(vertx, worker, ordered, callable)                  │
│  - publishWithTrace(vertx, address, message)                                │
│  - requestWithTrace(vertx, address, message)                                │
│  - tracedConsumer(vertx, address, handler)                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Custom Logback Converters                              │
│                                                                             │
│  VertxTraceIdConverter (%vxTrace)    VertxSpanIdConverter (%vxSpan)        │
│  ┌─────────────────────────────┐     ┌─────────────────────────────┐       │
│  │ 1. Check MDC("traceId")     │     │ 1. Check MDC("spanId")      │       │
│  │ 2. Check Vert.x Context     │     │ 2. Check Vert.x Context     │       │
│  │ 3. Return "-" if missing    │     │ 3. Return "-" if missing    │       │
│  └─────────────────────────────┘     └─────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Flow

```
Producer Side                              Consumer Side
─────────────                              ─────────────

1. Application Code                        4. Event Bus Consumer
   │                                          │
   ├─ TraceCtx.createNew()                   ├─ AsyncTraceUtils.tracedConsumer()
   ├─ Store in Vert.x Context                │
   └─ Send message with traceparent          5. Trace Extraction
                                                │
2. AsyncTraceUtils.publishWithTrace()          ├─ Parse traceparent from headers
   │                                           ├─ Create child span
   ├─ Get parent trace from Context            └─ Set MDC via mdcScope()
   ├─ Create child span                           │
   └─ Inject traceparent header                   6. Application Code
                                                     │
3. Event Bus / Database                             ├─ MDC automatically in logs
   │                                                └─ Process message
   ├─ Message delivered with headers
   └─ traceparent preserved                      7. Scope Exit
                                                     │
                                                     └─ MDC auto-cleared
```

---

## Core Components

### TraceCtx Record

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceCtx.java`

**Purpose**: Immutable W3C Trace Context container

```java
record TraceCtx(
    String traceId,       // 32 hex characters
    String spanId,        // 16 hex characters  
    String parentSpanId,  // null for root spans
    String traceparent    // Full W3C header
) {
    // Factory methods
    static TraceCtx createNew();
    static TraceCtx parseOrCreate(String traceparent);
    
    // Child span creation
    TraceCtx childSpan(String operationName);
}
```

**Key Behaviors**:
- `createNew()` generates random traceId and spanId
- `parseOrCreate()` parses header or creates new if invalid
- `childSpan()` preserves traceId, generates new spanId, sets parentSpanId
- `traceparent()` formats as `00-{traceId}-{spanId}-01`

### TraceContextUtil

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceContextUtil.java`

**Purpose**: MDC scope management and Vert.x Context key constants

```java
public class TraceContextUtil {
    // Vert.x Context key for storing TraceCtx
    public static final String CONTEXT_TRACE_KEY = "trace.ctx";
    
    // Returns AutoCloseable that sets MDC and clears on close
    public static AutoCloseable mdcScope(TraceCtx trace);
    
    // Capture current trace from MDC (for forwarding)
    public static TraceCtx captureTraceContext();
    
    // Delegates to TraceCtx.parseOrCreate
    public static TraceCtx parseOrCreate(String traceparent);
}
```

**mdcScope() Implementation**:
```java
public static AutoCloseable mdcScope(TraceCtx trace) {
    MDC.put("traceId", trace.traceId());
    MDC.put("spanId", trace.spanId());
    return () -> {
        MDC.remove("traceId");
        MDC.remove("spanId");
    };
}
```

### AsyncTraceUtils

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/AsyncTraceUtils.java`

**Purpose**: Async boundary handling for Vert.x

**Key Methods**:

```java
// Execute blocking with trace propagation
public static <T> Future<T> executeBlockingTraced(
    Vertx vertx,
    WorkerExecutor worker,
    boolean ordered,
    Callable<T> blocking
) {
    Context ctx = vertx.getOrCreateContext();
    TraceCtx parent = ctx.get(CONTEXT_TRACE_KEY);
    TraceCtx span = parent.childSpan("executeBlocking");
    
    return worker.executeBlocking(promise -> {
        try (var scope = TraceContextUtil.mdcScope(span)) {
            promise.complete(blocking.call());
        } catch (Throwable t) {
            promise.fail(t);
        }
    }, ordered);
}

// Event Bus publish with trace injection
public static void publishWithTrace(Vertx vertx, String address, Object message);

// Event Bus request with trace injection
public static Future<Message<Object>> requestWithTrace(Vertx vertx, String address, Object message);

// Consumer wrapper with automatic trace extraction
public static void tracedConsumer(Vertx vertx, String address, Handler<Message<Object>> handler);
```

---

## W3C Trace Context Specification

### Traceparent Header Format

```
traceparent: version-trace-id-parent-id-trace-flags
```

| Field | Length | Format | Example | Description |
|-------|--------|--------|---------|-------------|
| version | 2 chars | Hex | `00` | Version (always `00`) |
| trace-id | 32 chars | Hex | `4bf92f3577b34da6a3ce929d0e0e4736` | Unique trace identifier |
| parent-id | 16 chars | Hex | `00f067aa0ba902b7` | Current span identifier |
| trace-flags | 2 chars | Hex | `01` | Trace flags (01 = sampled) |

**Full Example**:
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

### Trace Flags

| Value | Meaning |
|-------|---------|
| `00` | Not sampled (may not be recorded) |
| `01` | Sampled (should be recorded) |

PeeGeeQ always uses `01` (sampled) by default.

### Parsing Rules

1. Split by `-` into 4 parts
2. Validate lengths: version(2), traceId(32), spanId(16), flags(2)
3. Validate hex characters
4. If invalid, create new root trace

### Generation Rules

1. **traceId**: 32 lowercase hex chars from cryptographically secure random
2. **spanId**: 16 lowercase hex chars from cryptographically secure random
3. **version**: Always `00`
4. **flags**: Always `01` unless explicitly configured

---

## MDC Scope Management

### The Problem

Without automatic cleanup, MDC leaks trace context:

```java
// ❌ WRONG: MDC not cleared after processing
private void processMessage(Message message) {
    MDC.put("traceId", trace.traceId());
    MDC.put("spanId", trace.spanId());
    handler.accept(message).get();
    // MDC never cleared - leaks to next operation!
}
```

**Result**: Subsequent unrelated logs show wrong trace context.

### The Solution: mdcScope()

```java
// ✅ CORRECT: Automatic cleanup via try-with-resources
private void processMessage(Message message) {
    TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
    
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        logger.info("Processing...");  // Has trace context
        handler.accept(message).get();
    }  // MDC automatically cleared here
    
    logger.info("Cleanup...");  // No trace context (correct!)
}
```

### Implementation

```java
public class TraceContextUtil {
    public static AutoCloseable mdcScope(TraceCtx trace) {
        // Set MDC keys
        MDC.put("traceId", trace.traceId());
        MDC.put("spanId", trace.spanId());
        
        // Return cleanup function
        return () -> {
            MDC.remove("traceId");
            MDC.remove("spanId");
        };
    }
}
```

### Verification Test

```java
@Test
void testMDCScopeAutoCleanup() {
    TraceCtx trace = TraceCtx.createNew();
    
    // Before scope - no MDC
    assertNull(MDC.get("traceId"));
    
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        // Inside scope - MDC set
        assertEquals(trace.traceId(), MDC.get("traceId"));
    }
    
    // After scope - MDC cleared
    assertNull(MDC.get("traceId"));
}

@Test
void testMDCScopeClearsOnException() {
    TraceCtx trace = TraceCtx.createNew();
    
    try {
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            throw new RuntimeException("Test");
        }
    } catch (RuntimeException e) { /* expected */ }
    
    // MDC still cleared even after exception
    assertNull(MDC.get("traceId"));
}
```

---

## Async Boundary Handling

### Why Async Boundaries Matter

In Vert.x:
- Event loop threads handle I/O
- Worker threads handle blocking code
- Threads are reused across requests
- MDC is thread-local

**Without proper handling**: Trace context is lost or bleeds between requests.

### executeBlocking Pattern

```java
// ❌ WRONG: Raw executeBlocking loses trace context
vertx.executeBlocking(promise -> {
    logger.info("Worker log");  // MDC empty!
    promise.complete(result);
});

// ✅ CORRECT: Wrapped with trace propagation
AsyncTraceUtils.executeBlockingTraced(vertx, worker, true, () -> {
    logger.info("Worker log");  // MDC populated!
    return result;
});
```

**Implementation**:
```java
public static <T> Future<T> executeBlockingTraced(
    Vertx vertx,
    WorkerExecutor worker,
    boolean ordered,
    Callable<T> blocking
) {
    // Capture from event loop thread
    Context ctx = vertx.getOrCreateContext();
    TraceCtx parent = ctx.get(CONTEXT_TRACE_KEY);
    
    // Create child span for worker
    TraceCtx span = parent.childSpan("executeBlocking");
    
    return worker.executeBlocking(promise -> {
        // Set MDC on worker thread
        try (var scope = TraceContextUtil.mdcScope(span)) {
            promise.complete(blocking.call());
        } catch (Throwable t) {
            promise.fail(t);
        }
        // MDC cleared when scope exits
    }, ordered);
}
```

### Event Bus Publish Pattern

```java
// ❌ WRONG: Raw publish loses trace context
eventBus.publish(address, payload);

// ✅ CORRECT: Wrapped with trace injection
AsyncTraceUtils.publishWithTrace(vertx, address, payload);
```

**Implementation**:
```java
public static void publishWithTrace(Vertx vertx, String address, Object message) {
    TraceCtx parent = getCurrentTrace(vertx);
    TraceCtx span = parent.childSpan("publish:" + address);
    
    DeliveryOptions opts = new DeliveryOptions()
        .addHeader("traceparent", span.traceparent());
    
    vertx.eventBus().publish(address, message, opts);
}
```

### Event Bus Consumer Pattern

```java
// ❌ WRONG: Raw consumer doesn't extract trace
eventBus.consumer(address, msg -> {
    logger.info("Handling");  // No trace context!
});

// ✅ CORRECT: Wrapped consumer extracts and scopes trace
AsyncTraceUtils.tracedConsumer(vertx, address, msg -> {
    logger.info("Handling");  // Trace context present!
});
```

**Implementation**:
```java
public static void tracedConsumer(Vertx vertx, String address, Handler<Message<Object>> handler) {
    vertx.eventBus().consumer(address, msg -> {
        String traceparent = msg.headers().get("traceparent");
        TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
        
        // Store in Vert.x Context
        vertx.getOrCreateContext().put(CONTEXT_TRACE_KEY, trace);
        
        // Scope MDC for handler
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            handler.handle(msg);
        }
    });
}
```

---

## Custom Logback Converters

### VertxTraceIdConverter

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxTraceIdConverter.java`

**Pattern**: `%vxTrace`

```java
public class VertxTraceIdConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        // 1. Check MDC first (fastest path)
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isEmpty()) {
            return traceId;
        }
        
        // 2. Check Vert.x Context (for event loop code)
        Context ctx = Vertx.currentContext();
        if (ctx != null) {
            TraceCtx trace = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
            if (trace != null) {
                return trace.traceId();
            }
        }
        
        // 3. No trace context - return dash indicator
        return "-";
    }
}
```

### VertxSpanIdConverter

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxSpanIdConverter.java`

**Pattern**: `%vxSpan`

Same logic as VertxTraceIdConverter but returns `spanId`.

### Registration in logback.xml

```xml
<configuration>
    <conversionRule conversionWord="vxTrace" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxTraceIdConverter"/>
    <conversionRule conversionWord="vxSpan" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter"/>
    
    <pattern>... [trace=%vxTrace span=%vxSpan] ...</pattern>
</configuration>
```

### Why "-" Instead of Empty String?

```
[trace=-] = No trace context (startup, admin ops) - EXPECTED, clear indicator
[trace=]  = Ambiguous - is it missing or a bug?
```

---

## Implementation Patterns

### Wrapper APIs (Enforce These)

**If it's not wrapped, it's wrong.**

| Raw API | Wrapped API | Usage |
|---------|-------------|-------|
| `eventBus.publish()` | `AsyncTraceUtils.publishWithTrace()` | Fire-and-forget |
| `eventBus.request()` | `AsyncTraceUtils.requestWithTrace()` | Request-reply |
| `eventBus.consumer()` | `AsyncTraceUtils.tracedConsumer()` | Message handlers |
| `executeBlocking()` | `AsyncTraceUtils.executeBlockingTraced()` | Worker threads |
| `MDC.put()` / `MDC.remove()` | `TraceContextUtil.mdcScope()` | Manual MDC |

### Correct Pattern: Fire-and-Forget (publish)

**Producer**:
```java
TraceCtx trace = currentTrace.childSpan("publish");
DeliveryOptions opts = new DeliveryOptions()
    .addHeader("traceparent", trace.traceparent());
eventBus.publish(address, payload, opts);
```

**Consumer**:
```java
eventBus.consumer(address, msg -> {
    TraceCtx trace = TraceContextUtil.parseOrCreate(msg.headers().get("traceparent"));
    vertx.getOrCreateContext().put(CONTEXT_TRACE_KEY, trace);
    
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        // handle message
    }
});
```

### Correct Pattern: Request-Reply (RPC)

**Requester**:
```java
TraceCtx trace = currentTrace.childSpan("request");
DeliveryOptions opts = new DeliveryOptions()
    .addHeader("traceparent", trace.traceparent());

eventBus.request(address, msg, opts, ar -> {
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        // handle reply
    }
});
```

**Responder**:
```java
eventBus.consumer(address, msg -> {
    TraceCtx trace = TraceContextUtil.parseOrCreate(msg.headers().get("traceparent"));
    TraceCtx childSpan = trace.childSpan("handle");
    
    try (var scope = TraceContextUtil.mdcScope(childSpan)) {
        // process and reply
        msg.reply(response);
    }
});
```

### Correct Pattern: PeeGeeQManager Lifecycle Events

**Centralize publishing**:
```java
publishLifecycleEvent(event) {
    TraceCtx trace = currentTrace.childSpan("lifecycle");
    eventBus.publish(addr, event, withTrace(trace));
}
```

Do NOT allow scattered `eventBus.publish(...)` calls.

---

## Thread Safety

### MDC Thread Isolation

SLF4J MDC is **thread-local** by design:

```java
// Thread 1: MDC = {traceId: "abc123"}
// Thread 2: MDC = {traceId: "xyz789"}
// No interference - each thread has its own map
```

### Vert.x Context Propagation

Vert.x Context is **tied to the event loop thread** that created it:

```java
// Event loop thread 1
Context ctx1 = vertx.getOrCreateContext();
ctx1.put(CONTEXT_TRACE_KEY, trace1);

// Event loop thread 2 (different context)
Context ctx2 = vertx.getOrCreateContext();
ctx2.put(CONTEXT_TRACE_KEY, trace2);
```

### Worker Thread Safety

Worker threads **require explicit trace propagation**:

```java
// Event loop: Capture before scheduling
TraceCtx parent = ctx.get(CONTEXT_TRACE_KEY);
TraceCtx span = parent.childSpan("worker");

// Worker: Set MDC explicitly
worker.executeBlocking(promise -> {
    try (var scope = mdcScope(span)) {
        // Safe - MDC set for this execution only
    }
});
```

### Concurrency Test (Prove No Bleed)

```java
@Test
void testNoCrossThreadBleed() throws Exception {
    int parallelCount = 100;
    CountDownLatch latch = new CountDownLatch(parallelCount);
    AtomicInteger failures = new AtomicInteger(0);
    
    for (int i = 0; i < parallelCount; i++) {
        String expectedTraceId = "trace-" + i;
        executor.submit(() -> {
            try (var scope = mdcScope(new TraceCtx(expectedTraceId, "span", null, null))) {
                Thread.sleep(10);  // Allow interleaving
                if (!expectedTraceId.equals(MDC.get("traceId"))) {
                    failures.incrementAndGet();
                }
            }
            latch.countDown();
        });
    }
    
    latch.await();
    assertEquals(0, failures.get(), "Trace context should not bleed between threads");
}
```

---

## Database Schema

### event_store_template table (v1.1.0+)

All tenant event store tables include `causation_id` (added in v1.1.0):

```sql
ALTER TABLE event_store_template
ADD COLUMN causation_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_event_store_causation_id
ON event_store_template(causation_id);
```

This enables querying full causality chains:

```sql
-- Find all events caused by a specific parent event
SELECT * FROM {schema}.event_store
WHERE causation_id = 'parent-event-id-here';

-- Trace a complete event causality chain
WITH RECURSIVE causality_chain AS (
    SELECT event_id, event_type, causation_id, 0 AS depth
    FROM {schema}.event_store
    WHERE event_id = 'root-event-id'
    UNION ALL
    SELECT e.event_id, e.event_type, e.causation_id, cc.depth + 1
    FROM {schema}.event_store e
    JOIN causality_chain cc ON e.causation_id = cc.event_id
)
SELECT * FROM causality_chain ORDER BY depth;
```

### messages table

```sql
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    correlation_id VARCHAR(255),
    payload JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    next_retry_at TIMESTAMP,
    error_message TEXT
);

CREATE INDEX idx_messages_processed ON messages(processed);
CREATE INDEX idx_messages_correlation_id ON messages(correlation_id);
```

### message_headers table

```sql
CREATE TABLE message_headers (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    header_key VARCHAR(255) NOT NULL,
    header_value TEXT,
    PRIMARY KEY (message_id, header_key)
);

CREATE INDEX idx_message_headers_message_id ON message_headers(message_id);
```

**Traceparent Storage**:
```sql
INSERT INTO message_headers (message_id, header_key, header_value)
VALUES (1, 'traceparent', '00-abc123...-def456...-01');
```

---

## Performance Characteristics

| Operation | Time | Notes |
|-----------|------|-------|
| `TraceCtx.createNew()` | ~1 μs | Uses `SecureRandom` |
| `TraceCtx.parseOrCreate()` | ~1 μs | String split + validation |
| `TraceCtx.childSpan()` | ~1 μs | New spanId generation |
| `mdcScope()` open | ~1 μs | 2 MDC puts |
| `mdcScope()` close | ~1 μs | 2 MDC removes |
| `VertxTraceIdConverter.convert()` | ~1 μs | MDC lookup + optional Context lookup |
| Database header insert | ~1 ms | Network + IO |
| Database header select | ~1 ms | Network + IO |

**Total per-message overhead**: ~2ms (< 0.1% for typical processing)

---

## API Reference

### TraceCtx

```java
// Create new root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Parse incoming header (creates new if invalid)
TraceCtx trace = TraceCtx.parseOrCreate("00-abc...-def...-01");

// Create child span
TraceCtx childSpan = parentSpan.childSpan("operation-name");

// Access fields
String traceId = trace.traceId();       // 32 hex
String spanId = trace.spanId();          // 16 hex
String parent = trace.parentSpanId();    // null for root
String header = trace.traceparent();     // Full W3C header
```

### TraceContextUtil

```java
// MDC scope with auto-cleanup
try (var scope = TraceContextUtil.mdcScope(trace)) {
    // MDC contains traceId and spanId
}

// Vert.x Context key
Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, trace);
TraceCtx trace = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);

// Capture current trace from MDC
TraceCtx current = TraceContextUtil.captureTraceContext();

// Parse or create
TraceCtx trace = TraceContextUtil.parseOrCreate(header);
```

### AsyncTraceUtils

```java
// Worker thread execution
Future<T> result = AsyncTraceUtils.executeBlockingTraced(
    vertx, worker, ordered, () -> { return blockingCall(); }
);

// Event Bus publish
AsyncTraceUtils.publishWithTrace(vertx, "address", message);

// Event Bus request
AsyncTraceUtils.requestWithTrace(vertx, "address", message)
    .onSuccess(reply -> { ... });

// Consumer with auto trace extraction
AsyncTraceUtils.tracedConsumer(vertx, "address", msg -> {
    // MDC already populated
});
```

---

## Compatibility Matrix

| Tool/Framework | Compatible | Notes |
|----------------|------------|-------|
| OpenTelemetry | ✅ Yes | Full W3C Trace Context support |
| Jaeger | ✅ Yes | Supports W3C Trace Context |
| Zipkin | ✅ Yes | Supports W3C Trace Context |
| AWS X-Ray | ✅ Yes | Can convert to X-Ray format |
| Google Cloud Trace | ✅ Yes | Supports W3C Trace Context |
| Azure Monitor | ✅ Yes | Supports W3C Trace Context |
| Datadog | ✅ Yes | Supports W3C Trace Context |
| New Relic | ✅ Yes | Supports W3C Trace Context |
| Elastic APM | ✅ Yes | Supports W3C Trace Context |
| Grafana Tempo | ✅ Yes | Supports W3C Trace Context |
| ELK Stack | ✅ Yes | Parse from log pattern |
| Grafana Loki | ✅ Yes | Parse from log pattern |

---

## Implementation Checklist

### Module: peegeeq-api (Foundation)

- [x] `TraceCtx` record with `createNew()`, `parseOrCreate()`, `childSpan()`
- [x] `TraceContextUtil` with `mdcScope()`, `CONTEXT_TRACE_KEY`
- [x] `AsyncTraceUtils` with all wrapper methods
- [x] `VertxTraceIdConverter` for `%vxTrace`
- [x] `VertxSpanIdConverter` for `%vxSpan`
- [x] Unit tests for MDC cleanup behavior

### Module: peegeeq-db

- [x] `PeeGeeQManager` uses `AsyncTraceUtils.executeBlockingTraced()`
- [x] Event Bus publishers inject traceparent header
- [x] logback.xml registers custom converters

### Module: peegeeq-bitemporal

- [x] `PgBiTemporalEventStore` extracts trace from requests
- [x] Child spans for database operations
- [x] logback.xml registers custom converters

### Module: peegeeq-native

- [x] Native queue consumers use `tracedConsumer()`
- [x] logback.xml registers custom converters

### Module: peegeeq-outbox

- [x] `OutboxConsumer` uses `mdcScope()` for message processing
- [x] logback.xml registers custom converters

### Module: peegeeq-runtime

- [x] REST handlers inject traceparent to messages
- [x] logback.xml registers custom converters

### Module: peegeeq-service-manager

- [x] `PeeGeeQServiceManager` instruments tracing
- [x] `InstanceRegistrationHandler` traces registration operations
- [x] `FederatedManagementHandler` traces federated operations
- [x] logback.xml registers custom converters

### All Modules

- [x] 16 logback configs updated with `%vxTrace` and `%vxSpan`
- [x] Converters return `-` when no trace exists
- [x] `TraceIdSpanIdDemoTest` passes demonstrating behavior

---

## Limitations

1. **No automatic span creation for observability tools**: PeeGeeQ propagates trace context via MDC. Integration with OpenTelemetry exporters requires additional setup.

2. **No automatic trace sampling**: PeeGeeQ always uses flags `01` (sampled). Custom sampling must be implemented if needed.

3. **No parent-child visualization**: Span hierarchy is captured (`parentSpanId`) but visualization requires external tools.

4. **No distributed context (baggage)**: Custom context beyond traceparent must be manually propagated in headers.

---

## Event Causality and Identifier Taxonomy

PeeGeeQ uses four distinct identifiers for correlation and causality. Understanding their differences prevents misuse.

### Identifier Reference Table

| Identifier | Scope | Layer | Set by | Purpose |
|------------|-------|-------|--------|---------|
| `traceId` | Distributed request | W3C / Tracing | PeeGeeQ tracing infra | Correlate logs across service hops for a single request |
| `spanId` | Single operation | W3C / Tracing | PeeGeeQ tracing infra | Identify a specific operation within a trace |
| `correlationId` | Workflow / session | Event Sourcing | Application code | Group all events belonging to the same workflow or user session |
| `causationId` | Event parent-child | Event Sourcing | Application code | Record which specific event caused this event |

### How They Differ

```
traceId/spanId  → "Which HTTP request / Vert.x context produced this log line?"
correlationId   → "Which business workflow do these events belong to?"
causationId     → "Which specific event directly triggered this event?"
```

`traceId` and `spanId` live in the W3C `traceparent` header and are **ephemeral** — they exist for the lifetime of a request and are stored in MDC and Vert.x Context. They are not stored in the event store as first-class fields.

`correlationId` and `causationId` are **persistent** — they are stored in every event record and survive beyond the originating request.

### EventStore.append() Signature (v1.1.0+)

```java
Future<BiTemporalEvent<T>> append(
    String eventType,      // 1. Event type name
    T payload,             // 2. Event payload
    Instant validTime,     // 3. Valid-time timestamp
    Map<String, String> headers, // 4. Additional headers
    String correlationId,  // 5. Groups related events in a workflow
    String causationId,    // 6. NEW (v1.1.0) — ID of the event that caused this one
    String aggregateId     // 7. Entity/aggregate ID
);
```

Passing `null` for `causationId` is valid for root events (commands from external actors).

### Event Causality Chain Pattern

```java
// Root event — caused by external user action, so causationId = null
BiTemporalEvent<Order> orderEvent = eventStore.append(
    "OrderCreated", order, now, headers,
    "corr-123",  // correlationId — groups the whole workflow
    null,        // causationId — root event, no parent
    "order-456"  // aggregateId
).toCompletionStage().toCompletableFuture().get();

// Child event — caused by OrderCreated
BiTemporalEvent<Inventory> inventoryEvent = eventStore.append(
    "InventoryReserved", inventory, now, headers,
    "corr-123",                  // same correlationId (same workflow)
    orderEvent.getEventId(),     // causationId = parent event ID
    "inventory-789"
).toCompletionStage().toCompletableFuture().get();

// Grandchild event — caused by InventoryReserved
eventStore.append(
    "PaymentProcessed", payment, now, headers,
    "corr-123",
    inventoryEvent.getEventId(), // causationId = immediate parent
    "order-456"
);
```

This produces the chain:

```
UserAction
  → OrderCreated        (causationId: null,                    correlationId: corr-123)
      → InventoryReserved (causationId: OrderCreated.eventId,    correlationId: corr-123)
          → PaymentProcessed (causationId: InventoryReserved.eventId, correlationId: corr-123)
```

### Combining Tracing and Causality

The W3C `traceparent` and `causationId` serve complementary roles and should both be present:

```
Incoming HTTP POST /orders
  traceparent: 00-abc123...-def456...-01   ← W3C trace context for log correlation
  body: { correlationId: "corr-123", causationId: null, ... }

  │
  ├─ Log: [trace=abc123 span=def456] Handling OrderCreated
  ├─ eventStore.append(... causationId=null ...)   ← stored persistently in event
  └─ eventStore.append(... causationId=orderEventId ...)  ← child event, same HTTP request trace
```

If the child event is triggered asynchronously (e.g. via outbox), it starts a **new** W3C trace but preserves the same `correlationId` and carries the `causationId` from the parent event:

```
Outbox consumer picks up InventoryReserved event
  traceparent: 00-zzz999...-yyy888...-01   ← NEW trace (different request)
  causationId: <OrderCreated event ID>      ← SAME causation chain (persistent)
  correlationId: corr-123                   ← SAME workflow (persistent)
```

### BiTemporalEvent Interface (v1.1.0+)

```java
public interface BiTemporalEvent<T> {
    String getEventId();
    String getEventType();
    T getPayload();
    Instant getValidTime();
    Instant getTransactionTime();
    long getVersion();
    String getPreviousVersionId();
    Map<String, String> getHeaders();
    String getCorrelationId();
    String getCausationId();   // ← NEW in v1.1.0
    String getAggregateId();
    boolean isCorrection();
    String getCorrectionReason();
}
```

### Migration from v1.0.x

See `PEEGEEQ_BREAKING_CHANGES_CAUSATION_ID.md` for the full migration guide. Summary:

| Change | Action |
|--------|--------|
| `EventStore.append()` now takes 7 parameters | Add `null` as the 6th argument |
| `BiTemporalEvent.getCausationId()` added | Implement if you have custom `BiTemporalEvent` impls |
| `SimpleBiTemporalEvent` constructor updated | Add `null` as the 10th argument |
| Event store tables get `causation_id` column | Run the migration SQL (see Database Schema section) |

---

## Forward Compatibility (OpenTelemetry-Ready)

This design is **OTel-compatible by construction**:

| PeeGeeQ Pattern | OpenTelemetry Equivalent |
|-----------------|--------------------------|
| `TraceCtx` record | `SpanContext` |
| `mdcScope()` returning `AutoCloseable` | `Scope` |
| `childSpan()` | `Span.spanBuilder().setParent()` |
| W3C traceparent | W3C Trace Context Propagator |
| `causationId` on events | OTel `Link` (causal relationship between spans) |

**Future migration path**:
1. Replace `TraceCtx` internals with OTel SDK
2. Add exporter (Jaeger, Zipkin, OTLP)
3. Zero application-level rewrite

---

## Final Truth

> If you don't **standardize and enforce** these patterns, correlation will degrade silently over time.

This design is the **minimum bar for correctness** in a real Vert.x distributed system.

---

*Document: PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md*  
*Version: 2.1*  
*Last Updated: 2026-05-10*
