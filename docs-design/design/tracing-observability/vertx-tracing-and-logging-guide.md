# PeeGeeQ Vert.x 5 Tracing & Logging Implementation Plan

Authored by Mark Andrew Ray-Smith Cityline Ltd
Date: 2026-01-06

**Goal:** Establish Vert.x Context as the single source of truth for tracing, using W3C standards and explicit MDC scoping.

## 1. Trace Foundation (`peegeeq-api`)
**Phase 1: Canonical Data Model**
*   **Create `TraceCtx` Record**:
    *   File: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceCtx.java`
    *   Structure: `record TraceCtx(traceId, spanId, parentSpanId, traceparent)`
    *   Logic:
        *   `createNew()`: Generates fresh random traceId/spanId.
        *   `childSpan(name)`: Preserves traceId, generates new spanId.
        *   `traceparent()`: Formats as `00-{traceId}-{spanId}-01`.

**Phase 2: Utilities & Scoping**
*   **Update `TraceContextUtil`**:
    *   File: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceContextUtil.java`
    *   `parseOrCreate(String traceparent)`: Returns `TraceCtx`.
    *   `mdcScope(TraceCtx ctx)`: Returns `AutoCloseable` that sets MDC (`traceId`, `spanId`) and clears on close.

## 2. Runtime Utilities (`peegeeq-api`)
**Phase 3: Async Safety Wrappers**
*   **Create `AsyncTraceUtils`**:
    *   File: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/AsyncTraceUtils.java`
    *   Dependencies: `io.vertx.core.Vertx`, `io.vertx.core.WorkerExecutor`.
    *   Function: `executeBlockingTraced(...)`
        *   **Step 1**: Capture `TraceCtx` from *current* Vert.x Context.
        *   **Step 2**: Create child span.
        *   **Step 3**: Call `worker.executeBlocking`.
        *   **Step 4**: Inside worker lambda, strictly wrap execution in `mdcScope(childSpan)`.

## 3. Component Instrumentation (`peegeeq-db`)
**Phase 4: Apply to Managers**
*   **Update `PeeGeeQManager`**:
    *   Locate usages of `SharedWorkerExecutor`.
    *   Replace raw `executeBlocking` with `AsyncTraceUtils.executeBlockingTraced`.
*   **Update Event Bus Publishers**:
    *   Inject `traceparent` header into `DeliveryOptions`.

## 4. Verification
**Phase 5: Prove It**
*   Rerun `OutboxQueueBrowserIntegrationTest`.
*   Verify logs show `traceId` persisting across thread boundaries (EventLoop -> Worker -> EventLoop).

---

# Vert.x 5.x Tracing & Distributed Logging Guide

**Custom W3C Trace Context + MDC (Logs-only, Tracing-ready)**

This document captures a **production-grade approach** to trace
propagation and log correlation in a Vert.x 5.x distributed system
using: - Custom W3C `traceparent` handling - Vert.x Event Bus (publish +
request) - `executeBlocking` and `SharedWorkerExecutor` - SLF4J +
Logback MDC - No tracing backend (yet)

The design is **OpenTelemetry-compatible by construction** and avoids
the common Vert.x pitfalls that cause silent trace corruption.

------------------------------------------------------------------------

## 1. Core Principle (Non‑Negotiable)

> **MDC is NOT the source of truth.**
>
> Vert.x Context is.

-   MDC is thread‑local
-   Vert.x hops threads aggressively
-   Worker threads are reused

**Rule** - Store trace context in the Vert.x `Context` - Re‑apply MDC
**at every async boundary** - Always clear MDC after use

If you violate this, logs will eventually lie.

------------------------------------------------------------------------

## 2. Canonical Trace Context Model

### W3C Trace Context

Use **only**: - `traceparent` - `tracestate` (optional)

Do not invent formats.

### Minimal Trace Context Object

``` java
record TraceCtx(
  String traceId,
  String spanId,
  String parentSpanId,
  String traceparent
) {}
```

Capabilities required: 1. Parse & validate `traceparent` 2. Create new
root context 3. Create child span (new spanId, same traceId) 4. Provide
MDC scope helper (set + clear)

------------------------------------------------------------------------

## 3. Vert.x Event Bus --- Fire‑and‑Forget (`publish`)

### Producer

-   Capture current trace from Vert.x Context
-   Create **child span**
-   Inject `traceparent` into `DeliveryOptions`

``` java
DeliveryOptions opts = new DeliveryOptions()
  .addHeader("traceparent", trace.childSpan("publish").traceparent());

eventBus.publish(address, payload, opts);
```

### Consumer

-   Extract `traceparent`
-   Store in Vert.x Context
-   Scope MDC for handler lifetime

``` java
eventBus.consumer(address, msg -> {
  TraceCtx trace = TraceContextUtil.parseOrCreate(msg.headers().get("traceparent"));
  vertx.getOrCreateContext().put("trace.ctx", trace);

  try (var scope = TraceContextUtil.mdcScope(trace)) {
    // handle message
  }
});
```

**Fire‑and‑forget consumers are new roots of work.** They must be
self‑sufficient.

------------------------------------------------------------------------

## 4. Event Bus --- Request / Response (RPC)

### Requester

-   Inject `traceparent`
-   Capture trace used for request
-   Re‑scope MDC inside reply handler

``` java
TraceCtx trace = currentTrace.childSpan("request");

eventBus.request(address, msg, withTrace(trace), ar -> {
  try (var scope = TraceContextUtil.mdcScope(trace)) {
    // handle reply
  }
});
```

### Responder

-   Extract incoming `traceparent`
-   Create child span for handling
-   Execute DB / blocking work under that span
-   Optionally propagate `traceparent` on reply

------------------------------------------------------------------------

## 5. `executeBlocking` & SharedWorkerExecutor (Critical Section)

### Why this breaks everything

-   Worker threads are reused
-   MDC is thread‑local
-   Vert.x Context does NOT magically flow

### Correct Pattern

``` java
public static <T> Future<T> executeBlockingTraced(
  Vertx vertx,
  WorkerExecutor worker,
  boolean ordered,
  Callable<T> blocking
) {
  Context ctx = vertx.getOrCreateContext();
  TraceCtx parent = ctx.get("trace.ctx");

  TraceCtx span = parent.childSpan("executeBlocking");

  return worker.executeBlocking(promise -> {
    try (var scope = TraceContextUtil.mdcScope(span)) {
      promise.complete(blocking.call());
    } catch (Throwable t) {
      promise.fail(t);
    }
  }, ordered);
}
```

### Mandatory Rules

-   ❌ No raw `executeBlocking`
-   ❌ No raw `SharedWorkerExecutor` usage
-   ✅ Always capture trace **before scheduling**
-   ✅ Always clear MDC in `finally` / scope

If you skip this, trace bleed is guaranteed.

------------------------------------------------------------------------

## 6. PeeGeeQManager (Publish Lifecycle Events)

**Centralize publishing.**

Do NOT allow:

``` java
eventBus.publish(...);
```

Instead:

``` java
publishLifecycleEvent(event) {
  TraceCtx trace = currentTrace.childSpan("lifecycle");
  eventBus.publish(addr, event, withTrace(trace));
}
```

Consumers must: - Extract headers - Set Vert.x Context - Scope MDC

------------------------------------------------------------------------

## 7. PgBiTemporalEventStore (RPC + DB)

### Client Side

-   Inject `traceparent`
-   Scope MDC inside reply handler

### Server Side

-   Extract `traceparent`
-   Child span for request handling
-   Use `executeBlockingTraced` for DB calls

### PostgreSQL

-   Log before/after queries
-   Optional: `SET LOCAL application_name = 'svc|traceId=...'`
-   Do NOT treat DB logging as tracing

------------------------------------------------------------------------

## 8. Wrapper APIs You Should Enforce

**If it's not wrapped, it's wrong.**

1.  `publishWithTrace(...)`
2.  `requestWithTrace(...)`
3.  `tracedConsumer(...)`
4.  `tracedHandler(...)`
5.  `executeBlockingTraced(...)`

Make raw APIs unavailable by convention or review.

------------------------------------------------------------------------

## 9. Testing (Prove It Works)

### Event Bus Propagation Test

-   Known traceId
-   Send → receive
-   Assert MDC set in handler
-   Assert MDC cleared after

### Concurrency Bleed Test

-   100 parallel traces
-   Each handler must see only its own traceId

If this fails, logs are untrustworthy.

------------------------------------------------------------------------

## 10. Forward Compatibility (OTel‑Ready)

You are already aligned if: - W3C propagation is canonical - TraceCtx is
immutable - Scope API mirrors OpenTelemetry `Scope`

Later: - Replace internals - Add exporter - Zero app‑level rewrite

------------------------------------------------------------------------

## 11. TraceId Visibility & Format Reference

This section details where and how trace identifiers appear throughout the system.

### 11.1 W3C Trace Context Format

The canonical format used throughout PeeGeeQ follows the W3C Trace Context specification:

**traceparent header format:**
```
traceparent: 00-{traceId}-{spanId}-{flags}
             │   │         │        │
             │   │         │        └─ Trace flags (01 = sampled/recorded)
             │   │         └────────── Span ID (16 hex characters)
             │   └──────────────────── Trace ID (32 hex characters)
             └──────────────────────── Version (always 00)
```

**Example:**
```
traceparent: 00-a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6-1234567890abcdef-01
```

| Component | Length | Format | Example |
|-----------|--------|--------|---------|
| Version | 2 chars | Always `00` | `00` |
| Trace ID | 32 chars | Lowercase hex | `a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6` |
| Span ID | 16 chars | Lowercase hex | `1234567890abcdef` |
| Flags | 2 chars | Hex bitfield | `01` (sampled) |

### 11.2 Log Output Format

Trace context appears in every log line via the Logback configuration.

**Logback pattern:**
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%vxTrace spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

**Sample log output:**
```
2026-01-06 14:30:45.100 [vert.x-eventloop-1] INFO  PeeGeeQRestServer - [traceId=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6 spanId=1111111111111111 correlationId=] Received POST /api/v1/queues/orders/messages
2026-01-06 14:30:45.150 [peegeeq-worker-1]  DEBUG QueueHandler       - [traceId=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6 spanId=2222222222222222 correlationId=] Processing message on worker thread
2026-01-06 14:30:45.200 [vert.x-eventloop-1] INFO  QueueHandler       - [traceId=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6 spanId=1111111111111111 correlationId=msg-456] Message sent successfully
```

**Key observations:**
- **traceId remains constant** across all log lines for the same distributed operation
- **spanId changes** when entering child spans (e.g., worker threads, Event Bus handlers)
- **correlationId** is application-defined and optional (typically the message ID)

**Grepping for a request:**
```bash
grep "traceId=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6" logs/*.log
```

### 11.3 Custom Logback Converter

The `%vxTrace` conversion word is implemented by `VertxTraceIdConverter`:

**File:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxTraceIdConverter.java`

**Resolution order:**
1. **MDC** (`traceId` key) - Checked first, fastest path
2. **Vert.x Context** (`TraceCtx` object) - Fallback for async contexts where MDC may not be set

This dual-lookup ensures trace IDs appear correctly even in edge cases where MDC wasn't explicitly set.

### 11.4 HTTP Headers (Inbound/Outbound)

**Inbound requests** should include the traceparent header:
```http
POST /api/v1/setups/my-setup/queues/orders/messages HTTP/1.1
Host: localhost:8080
Content-Type: application/json
traceparent: 00-a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6-1234567890abcdef-01

{"payload": {"orderId": "12345"}}
```

**If no traceparent is provided**, PeeGeeQ creates a new root trace automatically.

**Outbound responses** can optionally include the traceparent for debugging:
```http
HTTP/1.1 200 OK
Content-Type: application/json
traceparent: 00-a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6-aabbccdd11223344-01

{"messageId": "msg-456", "status": "sent"}
```

### 11.5 Event Bus Messages

When publishing or requesting via the Vert.x Event Bus, traceparent is injected into `DeliveryOptions`:

```java
DeliveryOptions opts = new DeliveryOptions()
    .addHeader("traceparent", "00-a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6-childspanid12345-01");

eventBus.publish("peegeeq.lifecycle", eventData, opts);
```

**Consumer extraction:**
```java
eventBus.consumer(address, msg -> {
    String traceparent = msg.headers().get("traceparent");
    TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
    // ...
});
```

### 11.6 Queue Message Headers

Messages stored in PeeGeeQ queues include trace context in their headers:

```json
{
  "id": "msg-456",
  "payload": {
    "orderId": "12345",
    "amount": 99.99
  },
  "headers": {
    "traceparent": "00-a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6-1234567890abcdef-01",
    "correlationId": "order-workflow-789"
  },
  "createdAt": "2026-01-06T14:30:45.123Z"
}
```

This enables:
- **End-to-end tracing** from producer to consumer
- **Dead letter queue debugging** - traces persist even for failed messages
- **Audit trails** - correlate business events with infrastructure logs

### 11.7 MDC Keys Reference

| MDC Key | Description | Set By |
|---------|-------------|--------|
| `traceId` | W3C trace ID (32 hex) | `TraceContextUtil.mdcScope()` |
| `spanId` | W3C span ID (16 hex) | `TraceContextUtil.mdcScope()` |
| `correlationId` | Application correlation ID | Application code |
| `messageId` | Queue message ID | `QueueHandler`, `OutboxConsumer` |
| `setupId` | Database setup identifier | REST handlers |
| `queueName` | Queue name being operated on | REST handlers |
| `topic` | Topic/queue name for consumers | `OutboxConsumer`, `PgNativeQueueConsumer` |

### 11.8 Span Hierarchy Example

A typical request flow creates this span hierarchy:

```
[Root Span: HTTP Request]
  traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
  spanId:  1111111111111111
  │
  ├─[Child Span: Database Query]
  │   traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6  (same)
  │   spanId:  2222222222222222  (new)
  │   parentSpanId: 1111111111111111
  │
  └─[Child Span: Event Bus Publish]
      traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6  (same)
      spanId:  3333333333333333  (new)
      parentSpanId: 1111111111111111
      │
      └─[Child Span: Consumer Handler]
          traceId: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6  (same)
          spanId:  4444444444444444  (new)
          parentSpanId: 3333333333333333
```

All spans share the same `traceId`, enabling correlation across the entire distributed operation.

------------------------------------------------------------------------

## 12. TraceId vs SpanId: Understanding the Difference

This section explains the fundamental difference between trace IDs and span IDs, and how to control their appearance in log output.

### 12.1 Conceptual Overview

| Property | TraceId | SpanId |
|----------|---------|--------|
| **Length** | 32 hex characters | 16 hex characters |
| **Scope** | Entire distributed operation | Single unit of work |
| **Lifetime** | Same across all services/threads | New for each span |
| **Purpose** | Correlate all logs for one request | Track parent-child relationships |

### 12.2 Visual Representation

```
User Request → API Server → Database → Message Queue → Consumer
     │              │            │           │            │
     └──────────────┴────────────┴───────────┴────────────┘
                    traceId: a1b2c3d4... (SAME everywhere)

     spanId: 1111...  spanId: 2222...  spanId: 3333...  spanId: 4444...
        │                 │                 │                 │
        └─ root span      └─ child          └─ child          └─ child
```

**Key insight:** The traceId acts as a "correlation ID" that ties together all work done for a single user request, even as it crosses service boundaries. The spanId identifies individual operations within that request.

### 12.3 When Each ID is Created

**TraceId** - Created once per incoming request, stays constant:
```java
// In PeeGeeQRestServer - creates root trace for each HTTP request
TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
// trace.traceId() = "68b757f33c38ac6b2df465122870ff07" (same for entire request)
```

**SpanId** - New for each "span" (unit of work):
```java
// Root span - created when request arrives
TraceCtx root = TraceCtx.createNew();  
// root.spanId() = "668899a857d8af19"

// Child span - created when delegating to worker thread
TraceCtx child = root.createChildSpan();
// child.traceId() = same as root (unchanged)
// child.spanId() = "aabbccdd11223344" (NEW - unique to this span)
// child.parentSpanId() = "668899a857d8af19" (points back to root)
```

### 12.4 When SpanId Changes

The spanId changes when entering a new unit of work:

| Scenario | SpanId Behavior |
|----------|-----------------|
| Worker thread execution (`executeBlockingTraced`) | New child span created |
| Event Bus message handler | New span from traceparent header |
| Explicit child span (`createChildSpan()`) | New span with parent reference |
| Same handler, same thread | SpanId stays the same |

**Example log output showing span changes:**
```
21:19:33.408 [eventloop-2] INFO  [trace=68b757f3... span=668899a8...] REST handler - Received POST
21:19:33.450 [worker-1]    INFO  [trace=68b757f3... span=aabbccdd...] Worker - Processing in background
21:19:33.500 [eventloop-2] INFO  [trace=68b757f3... span=668899a8...] REST handler - Response sent
```

Notice: Same traceId throughout, but different spanId on the worker thread.

### 12.5 Controlling Log Output

Both trace and span IDs are controlled via **logback.xml** patterns using custom converters.

**Converter Registration:**
```xml
<conversionRule conversionWord="vxTrace" 
                converterClass="dev.mars.peegeeq.api.logging.VertxTraceIdConverter" />
<conversionRule conversionWord="vxSpan" 
                converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter" />
```

**Pattern Options:**

| What you want | Pattern | Output Example |
|---------------|---------|----------------|
| Both trace and span | `[trace=%vxTrace span=%vxSpan]` | `[trace=68b757f3... span=668899a8...]` |
| Trace only (simpler) | `[trace=%vxTrace]` | `[trace=68b757f3...]` |
| With correlation ID | `[trace=%vxTrace span=%vxSpan corr=%X{correlationId:-}]` | `[trace=... span=... corr=order-123]` |
| No trace context | Remove from pattern | (no trace info in logs) |

**Recommended Production Pattern:**
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [trace=%vxTrace span=%vxSpan] %msg%n</pattern>
```

**Recommended Test Pattern (more compact):**
```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
```

### 12.6 Converter Behavior

Both `VertxTraceIdConverter` (`%vxTrace`) and `VertxSpanIdConverter` (`%vxSpan`) follow the same resolution order:

1. **MDC first** - Check SLF4J MDC for `traceId`/`spanId` key (fastest path)
2. **Vert.x Context fallback** - Check for `TraceCtx` object in Vert.x Context
3. **Return `-`** - If neither source has the value, return `-` (not empty string)

**Why `-` instead of empty?**
```
[trace=-] = No trace context exists (startup, background job) - EXPECTED
[trace=]  = Ambiguous - is it missing or a bug?
```

The `-` indicator makes it immediately clear when logs are from non-traced contexts (test framework, startup code, periodic background tasks) vs. when trace propagation may have failed.

### 12.7 Practical Debugging Examples

**Find all logs for a single request:**
```bash
grep "trace=68b757f33c38ac6b2df465122870ff07" logs/*.log
```

**Find logs for a specific span (one operation):**
```bash
grep "span=668899a857d8af19" logs/*.log
```

**Find all child operations of a parent span:**
```bash
# First, find the parent spanId, then search for it as parentSpanId in code
# or look for logs on different threads with the same traceId
grep "trace=68b757f3" logs/*.log | grep -v "span=668899a8"
```

**Identify untraced log lines (potential bugs):**
```bash
grep "\[trace=-\]" logs/*.log | grep -v "testcontainers\|Hikari\|startup"
```

------------------------------------------------------------------------

## Final Truth

> If you don't **standardize and enforce** these patterns, correlation
> will degrade silently over time.

This design is the minimum bar for correctness in a real Vert.x
distributed system.
