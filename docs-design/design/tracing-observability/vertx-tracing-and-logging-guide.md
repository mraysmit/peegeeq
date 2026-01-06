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

## Final Truth

> If you don't **standardize and enforce** these patterns, correlation
> will degrade silently over time.

This design is the minimum bar for correctness in a real Vert.x
distributed system.
