# PeeGeeQ Distributed Tracing Handbook

**Version**: 2.0  
**Last Updated**: 2026-01-06  
**Status**: ✅ Complete and Production-Ready

---

## Table of Contents

1. [Quick Start (5 Minutes)](#quick-start-5-minutes)
2. [Overview](#overview)
3. [Understanding Trace Context Flow](#understanding-trace-context-flow)
4. [How It Works](#how-it-works)
5. [⚠️ Critical: MDC Cleanup](#critical-mdc-cleanup)
6. [Implementation Guide](#implementation-guide)
7. [Understanding Blank Trace IDs](#understanding-blank-trace-ids)
8. [FAQ & Troubleshooting](#faq--troubleshooting)
9. [Integration with Observability Tools](#integration-with-observability-tools)
10. [Best Practices](#best-practices)
11. [Examples](#examples)
12. [Technical Reference](#technical-reference)
13. [Implementation Summary](#implementation-summary)

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

> **Note**: The `%vxTrace` and `%vxSpan` patterns automatically resolve trace context from MDC or Vert.x Context, returning `-` when no trace exists.

### 2. Send Message with Trace Context

```java
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

// Create root trace using TraceCtx record
TraceCtx rootSpan = TraceCtx.createNew();
String correlationId = "order-12345";

// Store in Vert.x Context (source of truth for async operations)
Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);

// Send message with traceparent header
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
headers.put("correlationId", correlationId);

producer.send(payload, headers, correlationId).get();
```

### 3. Consumer with Automatic Trace Context (Using AsyncTraceUtils)

```java
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;

// Use tracedConsumer for automatic trace extraction and MDC management
AsyncTraceUtils.tracedConsumer(vertx, "orders.process", message -> {
    // MDC is automatically populated with trace context from message headers!
    logger.info("Processing order");  
    // Output: [trace=52a0d370... span=d6ae23cb...] Processing order
    
    // Create child span for sub-operations
    TraceCtx currentTrace = TraceContextUtil.captureTraceContext();
    TraceCtx childSpan = currentTrace.childSpan("validate-order");
    
    try (var scope = TraceContextUtil.mdcScope(childSpan)) {
        logger.info("Validating order");
        // Output: [trace=52a0d370... span=NEW_SPAN...] Validating order
    }
});
```

### 4. Search Logs

```bash
# Find all logs for a specific request
grep "traceId=52a0d3705aba4122aa266f1216f87e10" application.log

# Find all logs for a specific order
grep "correlationId=order-12345" application.log
```

### 5. Test It

```bash
# Run the demonstration test
mvn test -Dtest=TraceIdSpanIdDemoTest -pl peegeeq-integration-tests
```

Look for logs with populated trace IDs:
```
22:19:54.917 [vert.x-eventloop-thread-0] INFO  [trace=2f1b5099dabac0a4f947103d6449dff4 span=30df90ea7da79b22] TraceIdSpanIdDemoTest - ROOT SPAN
22:19:54.918 [vert.x-eventloop-thread-0] INFO  [trace=2f1b5099dabac0a4f947103d6449dff4 span=5df007e10e20ffbb] TraceIdSpanIdDemoTest - CHILD 1 (parent: root)
22:19:54.918 [vert.x-eventloop-thread-0] INFO  [trace=2f1b5099dabac0a4f947103d6449dff4 span=b2bb9612255e77cd] TraceIdSpanIdDemoTest - CHILD 2 (parent: child1)
```

**Key observation**: TraceId stays **constant** (`2f1b5099...`) while SpanId **changes** for each unit of work.

---

## Overview

### What is Distributed Tracing?

PeeGeeQ provides **built-in distributed tracing** support using **W3C Trace Context** standard and **SLF4J MDC (Mapped Diagnostic Context)**. This enables you to track requests across your entire system - from HTTP ingress through message queues to final processing.

Distributed tracing allows you to follow a single request as it flows through multiple services and components. Each log statement automatically includes:

- **Trace ID**: A unique identifier for the entire request flow (32 hex characters)
- **Span ID**: A unique identifier for a specific operation within the trace (16 hex characters)
- **Correlation ID**: A business-level identifier (e.g., order ID, customer ID)

### Why Use Distributed Tracing?

#### Without Distributed Tracing
```
21:32:34.059 [vert.x-eventloop-thread-0] INFO  [trace=- span=-] PeeGeeQManager - Validating database connectivity...
21:32:34.061 [vert.x-eventloop-thread-0] INFO  [trace=- span=-] PeeGeeQManager - Starting all PeeGeeQ components...
```

**Note**: The `-` indicator shows no trace context exists (startup operations aren't traced).

#### With Distributed Tracing
```
23:28:17.561 [outbox-processor-1] INFO  [trace=52a0d3705aba4122aa266f1216f87e10 span=d6ae23cb58e1467d] OutboxConsumer - MDC set for message 1
23:28:17.562 [outbox-processor-1] INFO  [trace=52a0d3705aba4122aa266f1216f87e10 span=d6ae23cb58e1467d] OutboxConsumer - Processing message 1
23:28:17.562 [outbox-processor-1] INFO  [trace=52a0d3705aba4122aa266f1216f87e10 span=d6ae23cb58e1467d] OrderService - Processing order
```

**Benefit**: You can search for `trace=52a0d3705aba4122aa266f1216f87e10` and see ALL logs related to this request across all services!

### W3C Trace Context Format

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

### MDC Fields

PeeGeeQ automatically populates the following MDC fields via `TraceContextUtil.mdcScope()`:

| Field | Description | Example |
|-------|-------------|---------|
| `traceId` | W3C trace ID (32 hex chars) | `4bf92f3577b34da6a3ce929d0e0e4736` |
| `spanId` | W3C span/parent ID (16 hex chars) | `00f067aa0ba902b7` |
| `correlationId` | Message correlation ID | `order-12345` |
| `messageId` | Unique message ID | `msg-12345` |
| `topic` | Queue/topic name | `orders` |
| `setupId` | Database setup ID | `prod-db` |
| `queueName` | Queue name | `orders` |

### Core Tracing Classes

| Class | Location | Purpose |
|-------|----------|---------|
| `TraceCtx` | `peegeeq-api/.../tracing/` | W3C Trace Context record (immutable) |
| `TraceContextUtil` | `peegeeq-api/.../tracing/` | MDC scope management |
| `AsyncTraceUtils` | `peegeeq-api/.../tracing/` | Worker thread & Event Bus propagation |
| `VertxTraceIdConverter` | `peegeeq-api/.../logging/` | Logback `%vxTrace` converter |
| `VertxSpanIdConverter` | `peegeeq-api/.../logging/` | Logback `%vxSpan` converter |

---

## Understanding Trace Context Flow

### Common Misconception: "Trace should flow back to producer"

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

### Message Queues vs HTTP

| Pattern | Trace Flow | Example |
|---------|------------|---------|
| **HTTP (Sync)** | Bidirectional | Client → Server → Client |
| **Message Queue (Async)** | Forward only | Producer → Queue → Consumer |
| **Full Distributed** | Multi-hop forward | Client → API → Queue → Consumer → External |

### Full Distributed Tracing Flow

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

### Trace Context Propagation Mechanisms

#### 1. HTTP Headers (Synchronous)

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

#### 2. Message Headers (Asynchronous)

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

### Span Hierarchy

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

### Why Unsubscribe Has Blank Trace IDs

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

---

## How It Works

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PeeGeeQ Tracing (v2.0)                            │
└─────────────────────────────────────────────────────────────────────────────┘

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

### Component Responsibilities

#### 1. TraceCtx Record

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceCtx.java`

**Responsibilities**:
- Immutable W3C Trace Context container
- Parse traceparent headers
- Generate trace/span IDs
- Create child spans

**Key Methods**:

```java
// Create new root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Parse incoming traceparent
TraceCtx trace = TraceCtx.parseOrCreate(traceparentHeader);

// Create child span (same traceId, new spanId)
TraceCtx childSpan = parentSpan.childSpan("operation-name");

// Access fields
String traceId = trace.traceId();       // 32 hex chars
String spanId = trace.spanId();          // 16 hex chars
String parent = trace.parentSpanId();    // null for root
String header = trace.traceparent();     // Full W3C header
```

#### 2. TraceContextUtil

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceContextUtil.java`

**Responsibilities**:
- MDC scope management with auto-cleanup
- Vert.x Context key constants
- Trace context capture from MDC

**Key Methods**:

```java
// Set MDC with auto-cleanup scope
try (var scope = TraceContextUtil.mdcScope(trace)) {
    // MDC contains traceId and spanId
    logger.info("Processing...");
} // MDC automatically cleared

// Capture current trace from MDC
TraceCtx current = TraceContextUtil.captureTraceContext();

// Parse or create (delegates to TraceCtx)
TraceCtx trace = TraceContextUtil.parseOrCreate(header);
```

#### 3. AsyncTraceUtils

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/AsyncTraceUtils.java`

**Responsibilities**:
- Worker thread trace propagation
- Event Bus trace injection/extraction
- Child span creation for async boundaries

**Key Methods**:

```java
// Execute blocking with trace propagation
AsyncTraceUtils.executeBlockingTraced(vertx, worker, ordered, () -> {
    // Runs on worker with child span in MDC
    return result;
});

// Event Bus publish with trace
AsyncTraceUtils.publishWithTrace(vertx, address, message);

// Event Bus request/reply with trace
AsyncTraceUtils.requestWithTrace(vertx, address, message)
    .onSuccess(reply -> { /* MDC restored */ });

// Consumer with auto trace extraction
AsyncTraceUtils.tracedConsumer(vertx, address, msg -> {
    // MDC set from message headers
});
```

#### 4. Custom Logback Converters

**Location**: `peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/`

**Files**:
- `VertxTraceIdConverter.java` - `%vxTrace` pattern
- `VertxSpanIdConverter.java` - `%vxSpan` pattern

**Resolution Logic**:
1. Check MDC first (fastest for blocking code)
2. Check Vert.x Context (for event loop code)
3. Return `-` if no trace exists

### Message Lifecycle with Tracing

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Message Lifecycle                                   │
└─────────────────────────────────────────────────────────────────────────────┘

Phase 1: Message Creation
─────────────────────────
Application:
  ├─ Generate traceId = "abc123..."
  ├─ Generate spanId = "def456..."
  ├─ Create traceparent = "00-abc123...-def456...-01"
  └─ Create headers = {"traceparent": "00-abc123...-def456...-01"}

Phase 2: Message Sending
────────────────────────
PeeGeeQProducer:
  ├─ Insert into messages table
  │   └─ id=1, correlation_id="order-12345", payload={...}
  │
  └─ Insert into message_headers table
      └─ message_id=1, header_key="traceparent", header_value="00-abc123...-def456...-01"

Phase 3: Message Storage
────────────────────────
Database:
  ├─ messages: [id=1, correlation_id="order-12345", ...]
  └─ message_headers: [message_id=1, traceparent="00-abc123...-def456...-01"]

Phase 4: Message Polling
────────────────────────
PeeGeeQConsumer:
  ├─ SELECT * FROM messages WHERE processed = false
  └─ SELECT * FROM message_headers WHERE message_id = 1

Phase 5: MDC Setup
──────────────────
TraceContextUtil:
  ├─ Parse traceparent = "00-abc123...-def456...-01"
  ├─ Extract traceId = "abc123..."
  ├─ Extract spanId = "def456..."
  └─ MDC.put("traceId", "abc123...")
      MDC.put("spanId", "def456...")
      MDC.put("correlationId", "order-12345")

Phase 6: Message Processing
───────────────────────────
Application Handler:
  ├─ logger.info("Processing order")
  │   └─ Output: [traceId=abc123... spanId=def456... correlationId=order-12345] Processing order
  │
  └─ Process message...

Phase 7: MDC Cleanup
────────────────────
TraceContextUtil:
  └─ MDC.clear()
```

### Thread Safety

PeeGeeQ's tracing is **thread-safe** because:

1. **SLF4J MDC is thread-local**: Each thread has its own MDC context
2. **Consumer processes one message at a time per thread**: No concurrent access
3. **MDC is set/cleared per message**: No leakage between messages

```java
// Thread 1
consumer1.subscribe(message -> {
    // MDC for thread 1: traceId=abc123
    logger.info("Processing message 1");
    return CompletableFuture.completedFuture(null);
});

// Thread 2
consumer2.subscribe(message -> {
    // MDC for thread 2: traceId=xyz789
    logger.info("Processing message 2");
    return CompletableFuture.completedFuture(null);
});
```

Each thread maintains its own trace context without interference.

### Error Handling

PeeGeeQ maintains trace context even during errors:

```java
consumer.subscribe(message -> {
    try {
        // MDC is set: traceId=abc123
        logger.info("Processing message");

        // Error occurs
        throw new RuntimeException("Processing failed");

    } catch (Exception e) {
        // MDC still set: traceId=abc123
        logger.error("Error processing message", e);
        // Output: [traceId=abc123 ...] Error processing message

        throw e;
    } finally {
        // MDC is cleared in PeeGeeQConsumer
    }
});
```

This ensures all error logs are correlated with the original request.

---

## ⚠️ Critical: MDC Cleanup

### The Problem

**Current State**: PeeGeeQ consumers automatically set MDC (Mapped Diagnostic Context) with trace context when processing messages, but this trace context **MUST be cleared** after message processing completes to prevent trace context leakage.

**Solution**: PeeGeeQ provides `TraceContextUtil.mdcScope()` which returns an `AutoCloseable` that automatically cleans up MDC when the scope exits.

### Evidence from Codebase

#### Current Implementation Pattern (Recommended)

Using `try-with-resources` for automatic cleanup:

```java
private void processMessage(OutboxMessage message) {
    TraceCtx trace = TraceContextUtil.parseOrCreate(traceparentHeader);
    
    // ✅ CORRECT: mdcScope() provides automatic cleanup
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        logger.info("MDC set for message {}", message.getId());
        
        // Invoke user handler
        handler.accept(message).get();
        
        // Mark as processed
        markAsProcessed(message.getId());
        
    } catch (Exception e) {
        logger.error("Error processing message {}", message.getId(), e);
        handleError(message, e);
    }
    // MDC automatically cleared when scope exits (even on exception)
}
```

**Key Features of `mdcScope()`:**
- Sets `traceId` and `spanId` in MDC
- Returns `AutoCloseable` for use with try-with-resources
- Automatically removes MDC keys when scope closes
- Works correctly even when exceptions occur

#### What Happens Without Cleanup

If you manually set MDC without using `mdcScope()`:

```java
public void unsubscribe() {
    logger.info("Unsubscribing from queue: {}", queueName);
    // ❌ This log will show trace context from the last processed message!
    // Output: [trace=abc123 span=def456] Unsubscribing from queue: orders

    running = false;
    // ... cleanup code ...
}
```

**Expected**: `[trace=- span=-] Unsubscribing from queue: orders`
**Actual (without cleanup)**: `[trace=abc123 span=def456] Unsubscribing from queue: orders`

### The Solution: Always Use mdcScope()

**Always use `try-with-resources` with `mdcScope()`**:

```java
private void processMessage(OutboxMessage message) {
    TraceCtx trace = TraceContextUtil.parseOrCreate(traceparentHeader);
    
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        logger.info("MDC set for message {}", message.getId());

        // Invoke user handler
        handler.accept(message).get();

        // Mark as processed
        markAsProcessed(message.getId());

    } catch (Exception e) {
        logger.error("Error processing message {}", message.getId(), e);
        handleError(message, e);
    }
    // ✅ MDC automatically cleared when scope exits
}
```

### Why This Matters

#### Scenario 1: Unsubscribe After Processing

```
Timeline:

T=0: Process message 1
     [traceId=abc123 spanId=def456 correlationId=order-12345] Processing message 1

T=1: Message 1 complete
     ❌ MDC NOT cleared

T=2: Unsubscribe
     [traceId=abc123 spanId=def456 correlationId=order-12345] Unsubscribing from queue
     ^^^ WRONG! This is not part of order-12345 processing!
```

#### Scenario 2: Processing Multiple Messages

```
Timeline:

T=0: Process message 1
     [traceId=abc123 ...] Processing message 1

T=1: Message 1 complete
     ❌ MDC NOT cleared

T=2: Process message 2
     [traceId=abc123 ...] Processing message 2  ← WRONG! Should be xyz789!
     ^^^ Message 2 shows trace context from message 1!
```

### Implementation Checklist

When implementing PeeGeeQ consumers, ensure:

- [x] ✅ Use `TraceContextUtil.mdcScope()` for automatic MDC cleanup
- [x] ✅ Use `AsyncTraceUtils.tracedConsumer()` for Event Bus consumers
- [x] ✅ Use `AsyncTraceUtils.executeBlockingTraced()` for worker threads
- [x] ✅ All 16 logback configs use `%vxTrace` and `%vxSpan` converters
- [x] ✅ Tests verify MDC is cleared after processing
- [x] ✅ Tests verify MDC is cleared even on error

### Testing MDC Cleanup

**Unit Test**: Verify MDC is properly scoped:

```java
@Test
void testMDCScopeAutoCleanup() {
    TraceCtx trace = TraceCtx.createNew();
    
    // Before scope - no MDC
    assertNull(MDC.get("traceId"));
    
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        // Inside scope - MDC set
        assertEquals(trace.traceId(), MDC.get("traceId"));
        assertEquals(trace.spanId(), MDC.get("spanId"));
    }
    
    // After scope - MDC cleared
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));
}

@Test
void testMDCScopeClearsOnException() {
    TraceCtx trace = TraceCtx.createNew();
    
    try {
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            assertEquals(trace.traceId(), MDC.get("traceId"));
            throw new RuntimeException("Test exception");
        }
    } catch (RuntimeException e) {
        // Expected
    }
    
    // MDC should still be cleared even after exception
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));
}
```

### Current Status

✅ **IMPLEMENTED**: PeeGeeQ provides `TraceContextUtil.mdcScope()` for automatic MDC cleanup.

**Verification**: Run integration tests to confirm:

```bash
mvn test -Dtest=TraceIdSpanIdDemoTest -pl peegeeq-integration-tests
```

Look for logs showing:
- Trace context **present** during message processing
- Trace context shows **`-`** after processing (unsubscribe, shutdown)

### Related Files

- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceCtx.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceContextUtil.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/AsyncTraceUtils.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxTraceIdConverter.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxSpanIdConverter.java`
- `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/TraceIdSpanIdDemoTest.java`

---

## Implementation Guide

### Step 1: Configure Logback

Add the custom Vert.x-aware converters to your `logback.xml` or `logback-test.xml`:

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

    <root level="info">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

**Key Points**:
- `%vxTrace` - Custom converter that checks MDC first, then Vert.x Context, returns `-` if not set
- `%vxSpan` - Custom converter with same resolution logic for span ID
- These converters handle Vert.x's async nature where MDC may not be propagated

### Step 2: Generate Trace IDs

#### Option A: Use TraceCtx Record (Recommended)

```java
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

// Create new root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Or parse from incoming traceparent header
TraceCtx incomingTrace = TraceCtx.parseOrCreate(traceparentHeader);

// Create child span (same traceId, new spanId)
TraceCtx childSpan = rootSpan.childSpan("operation-name");

// Get the W3C traceparent header value
String traceparent = rootSpan.traceparent();
// Output: "00-{32-char-traceId}-{16-char-spanId}-01"
```

#### Option B: Manual Generation (Legacy)

```java
// Generate 32-character hex trace ID
String traceId = UUID.randomUUID().toString().replace("-", "") +
                 UUID.randomUUID().toString().replace("-", "").substring(0, 32 - 32);

// Generate 16-character hex span ID
String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

// Create traceparent header
String traceparent = String.format("00-%s-%s-01", traceId, spanId);
```

### Step 3: Send Message with Trace Context

```java
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

// Create root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Store in Vert.x Context for async propagation
Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);

// Create headers with traceparent
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
headers.put("correlationId", correlationId);

// Optional: Add custom headers
headers.put("userId", "user-123");
headers.put("requestId", "req-456");

// Send message
producer.send(payload, headers, correlationId).get();
```

### Step 4: Consumer with AsyncTraceUtils (Recommended)

```java
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

// Use tracedConsumer for automatic trace extraction
AsyncTraceUtils.tracedConsumer(vertx, "orders.process", message -> {
    // MDC is automatically populated from message headers!
    // All logs here will include trace context

    logger.info("Processing message");
    // Output: [trace=abc123... span=def456...] Processing message

    // Your business logic here
    processOrder(message.body());
});
```

### Step 5: Worker Thread Operations

```java
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;

// Execute blocking code with trace propagation
AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, true, () -> {
    // This runs on a worker thread with proper MDC context
    logger.info("Performing database operation");
    // Output: [trace=abc123... span=NEW_CHILD_SPAN...] Performing database operation
    
    return performDatabaseOperation();
});
```

### Step 6: Event Bus Communication

```java
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;

// Publish with trace propagation (fire-and-forget)
AsyncTraceUtils.publishWithTrace(vertx, "orders.notify", orderData);

// Request-reply with trace propagation
AsyncTraceUtils.requestWithTrace(vertx, "orders.validate", orderData)
    .onSuccess(reply -> {
        logger.info("Validation response received");
        // MDC restored with parent span context
    });
```

### Step 7: Test Your Implementation

```java
@Test
@DisplayName("Verify trace context propagation")
void testDistributedTracing(Vertx vertx, VertxTestContext testContext) {
    // Create root trace
    TraceCtx rootSpan = TraceCtx.createNew();
    String expectedTraceId = rootSpan.traceId();
    
    // Store in Vert.x Context
    vertx.runOnContext(v -> {
        Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
        
        // Execute on worker thread
        AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, true, () -> {
            // Verify trace ID is preserved
            String workerTraceId = MDC.get("traceId");
            assertEquals(expectedTraceId, workerTraceId, 
                "TraceId should remain constant on worker thread");
            
            // Verify span ID is different (child span)
            String workerSpanId = MDC.get("spanId");
            assertNotEquals(rootSpan.spanId(), workerSpanId, 
                "SpanId should be different (child span)");
            
            return "done";
        }).onComplete(ar -> {
            testContext.completeNow();
        });
    });
}
```

### Step 8: Search Logs

#### Find all logs for a specific trace

```bash
# Linux/Mac
grep "trace=abc123" application.log

# Windows PowerShell
Select-String -Path application.log -Pattern "trace=abc123"
```

#### Find all logs for a specific span

```bash
# Linux/Mac
grep "span=def456" application.log

# Windows PowerShell
Select-String -Path application.log -Pattern "span=def456"
```

### Complete Example

```java
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    private final PeeGeeQProducer<Order> producer;
    private final PeeGeeQConsumer<Order> consumer;

    public void submitOrder(Order order) {
        // Generate trace context
        String traceId = TraceContextUtil.generateTraceId();
        String spanId = TraceContextUtil.generateSpanId();
        String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

        // Create headers
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", traceparent);

        // Send message
        String correlationId = "order-" + order.getId();
        producer.send(order, headers, correlationId).get();

        logger.info("Order submitted: {}", order.getId());
        // Output: [traceId= spanId= correlationId=] Order submitted: 12345
        // Note: No trace context here because we're not in a consumer
    }

    public void startProcessing() {
        consumer.subscribe(message -> {
            // MDC is automatically set here!
            logger.info("Processing order: {}", message.getPayload().getId());
            // Output: [traceId=abc123... spanId=def456... correlationId=order-12345] Processing order: 12345

            Order order = message.getPayload();

            // Validate order
            logger.info("Validating order");
            // Output: [traceId=abc123...] Validating order
            validateOrder(order);

            // Save to database
            logger.info("Saving order");
            // Output: [traceId=abc123...] Saving order
            saveOrder(order);

            // Call external service
            logger.info("Notifying external service");
            // Output: [traceId=abc123...] Notifying external service
            notifyExternalService(order);

            logger.info("Order processing complete");
            // Output: [traceId=abc123...] Order processing complete

            return CompletableFuture.completedFuture(null);
        });
    }

    private void notifyExternalService(Order order) {
        // Get current trace context
        String traceId = MDC.get("traceId");
        String newSpanId = TraceContextUtil.generateSpanId();
        String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

        // Make HTTP call with trace context
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.example.com/orders"))
            .header("traceparent", traceparent)
            .POST(HttpRequest.BodyPublishers.ofString(toJson(order)))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("External service responded: {}", response.statusCode());
            // Output: [traceId=abc123...] External service responded: 200
        } catch (Exception e) {
            logger.error("External service call failed", e);
            // Output: [traceId=abc123...] External service call failed
        }
    }
}
```

---

## Understanding Blank Trace IDs

### Why Do Some Logs Show Blank Trace IDs?

You may see logs like this:

```
[traceId= spanId= correlationId=] Unsubscribing from queue
[traceId= spanId= correlationId=] Closing consumer
```

**This is normal and expected!** Here's why:

### Trace Context Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Trace Context Lifecycle                             │
└─────────────────────────────────────────────────────────────────────────────┘

Phase 1: Request Arrives
────────────────────────
[traceId=abc123 spanId=def456 correlationId=order-12345]
  │
  ├─ HTTP request received
  ├─ Message sent to queue
  └─ HTTP response returned
     │
     └─ Trace context CLEARED (request complete)

Phase 2: Message Processing
───────────────────────────
[traceId=abc123 spanId=def456 correlationId=order-12345]
  │
  ├─ Consumer polls message
  ├─ MDC set from message headers
  ├─ Message processed
  └─ MDC cleared (message complete)

Phase 3: Application Shutdown
─────────────────────────────
[traceId= spanId= correlationId=]  ← No trace context!
  │
  ├─ Unsubscribe from queue
  ├─ Close consumer
  └─ Shutdown complete
```

### What Operations Have Trace Context?

| Operation | Has Trace Context? | Why? |
|-----------|-------------------|------|
| Processing message | ✅ Yes | Part of traced request |
| Logging during message processing | ✅ Yes | Part of traced request |
| Calling external services | ✅ Yes | Part of traced request |
| Database operations | ✅ Yes | Part of traced request |
| Error handling | ✅ Yes | Part of traced request |
| **Unsubscribe** | ❌ No | Administrative operation |
| **Close consumer** | ❌ No | Administrative operation |
| **Shutdown** | ❌ No | Administrative operation |
| **Startup** | ❌ No | Administrative operation |

### Example: Full Lifecycle

```java
public class OrderService {
    public void start() {
        // No trace context - startup
        logger.info("Starting order service");
        // Output: [traceId= spanId= correlationId=] Starting order service

        consumer.subscribe(message -> {
            // Trace context set by PeeGeeQConsumer
            logger.info("Processing order");
            // Output: [traceId=abc123 spanId=def456 correlationId=order-12345] Processing order

            processOrder(message.getPayload());

            logger.info("Order complete");
            // Output: [traceId=abc123 spanId=def456 correlationId=order-12345] Order complete

            return CompletableFuture.completedFuture(null);
            // MDC cleared by PeeGeeQConsumer after this returns
        });

        logger.info("Order service started");
        // Output: [traceId= spanId= correlationId=] Order service started
    }

    public void stop() {
        // No trace context - shutdown
        logger.info("Stopping order service");
        // Output: [traceId= spanId= correlationId=] Stopping order service

        consumer.unsubscribe();
        // Output: [traceId= spanId= correlationId=] Unsubscribing from queue

        logger.info("Order service stopped");
        // Output: [traceId= spanId= correlationId=] Order service stopped
    }
}
```

### Why This Design?

1. **Trace context is request-scoped**: It only exists during request processing
2. **Administrative operations are not requests**: Startup, shutdown, unsubscribe are not part of any user request
3. **Prevents confusion**: Blank trace IDs clearly indicate "not part of a traced request"
4. **Follows standards**: W3C Trace Context is designed for request tracing, not administrative operations

### When to Worry About Blank Trace IDs

✅ **Normal (don't worry)**:
- Startup logs
- Shutdown logs
- Unsubscribe logs
- Configuration logs
- Health check logs (unless you want to trace them)

❌ **Problem (investigate)**:
- Message processing logs showing blank trace IDs
- Business logic logs showing blank trace IDs
- External service calls showing blank trace IDs

### Debugging Blank Trace IDs in Message Processing

If you see blank trace IDs during message processing:

```
[traceId= spanId= correlationId=] Processing order  ← PROBLEM!
```

**Possible causes**:

1. **Message sent without headers**:
   ```java
   // Wrong - no headers
   producer.send(payload, correlationId).get();

   // Correct - with headers
   Map<String, String> headers = new HashMap<>();
   headers.put("traceparent", traceparent);
   producer.send(payload, headers, correlationId).get();
   ```

2. **Invalid traceparent format**:
   ```java
   // Wrong - invalid format
   headers.put("traceparent", "invalid");

   // Correct - valid W3C format
   headers.put("traceparent", "00-abc123...-def456...-01");
   ```

3. **MDC cleared prematurely**:
   ```java
   // Wrong - clearing MDC manually
   consumer.subscribe(message -> {
       TraceContextUtil.clearMDC();  // Don't do this!
       logger.info("Processing");  // No trace context
       return CompletableFuture.completedFuture(null);
   });

   // Correct - let PeeGeeQConsumer manage MDC
   consumer.subscribe(message -> {
       logger.info("Processing");  // Trace context present
       return CompletableFuture.completedFuture(null);
   });
   ```

### Testing Trace Context

```java
@Test
public void testTraceContextPresent() throws Exception {
    // Generate trace context
    String traceId = TraceContextUtil.generateTraceId();
    String spanId = TraceContextUtil.generateSpanId();
    String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

    // Send message with trace context
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(payload, headers, "test-id").get();

    // Verify trace context in consumer
    CountDownLatch latch = new CountDownLatch(1);
    consumer.subscribe(message -> {
        // Verify MDC is set
        String actualTraceId = MDC.get("traceId");
        String actualSpanId = MDC.get("spanId");

        assertNotNull(actualTraceId, "traceId should not be null");
        assertNotNull(actualSpanId, "spanId should not be null");
        assertEquals(traceId, actualTraceId);
        assertEquals(spanId, actualSpanId);

        latch.countDown();
        return CompletableFuture.completedFuture(null);
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
}
```

---

## FAQ & Troubleshooting

### General Questions

#### Q: Do I need to configure anything to use distributed tracing?

**A**: Just add MDC placeholders to your `logback.xml`:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

Everything else is automatic!

#### Q: What if I don't send a traceparent header?

**A**: The message will be processed normally, but logs will show blank trace IDs:

```
[traceId= spanId= correlationId=order-12345] Processing order
```

You'll still have the correlation ID for basic tracking.

#### Q: Can I use my own trace ID format?

**A**: No, PeeGeeQ uses the W3C Trace Context standard. This ensures compatibility with:
- OpenTelemetry
- Jaeger
- Zipkin
- AWS X-Ray
- Google Cloud Trace
- Azure Monitor

#### Q: Does tracing add overhead?

**A**: Minimal overhead:
- Parsing traceparent: ~1 microsecond
- Setting MDC: ~1 microsecond
- Database storage: 2 extra rows per message (headers table)

Total overhead: < 0.1% for typical message processing.

#### Q: Can I add custom MDC fields?

**A**: Yes! Add them in your consumer:

```java
consumer.subscribe(message -> {
    // PeeGeeQ sets: traceId, spanId, correlationId

    // Add custom fields
    MDC.put("userId", message.getPayload().getUserId());
    MDC.put("orderId", message.getPayload().getOrderId());

    try {
        logger.info("Processing order");
        // Output: [traceId=abc123 spanId=def456 correlationId=order-12345 userId=user-123 orderId=order-456] Processing order

        processOrder(message.getPayload());

        return CompletableFuture.completedFuture(null);
    } finally {
        // Clean up custom fields
        MDC.remove("userId");
        MDC.remove("orderId");
        // PeeGeeQ clears traceId, spanId, correlationId automatically
    }
});
```

### Troubleshooting

#### Problem: Blank trace IDs in message processing logs

**Symptoms**:
```
[traceId= spanId= correlationId=order-12345] Processing order
```

**Diagnosis**:
1. Check if message was sent with headers:
   ```java
   // Wrong
   producer.send(payload, correlationId).get();

   // Correct
   Map<String, String> headers = new HashMap<>();
   headers.put("traceparent", traceparent);
   producer.send(payload, headers, correlationId).get();
   ```

2. Check traceparent format:
   ```java
   // Wrong
   headers.put("traceparent", "invalid");

   // Correct
   headers.put("traceparent", "00-abc123...-def456...-01");
   ```

3. Check database:
   ```sql
   SELECT * FROM message_headers WHERE message_id = 1;
   ```

   Should show:
   ```
   message_id | header_key  | header_value
   -----------+-------------+----------------------------------
   1          | traceparent | 00-abc123...-def456...-01
   ```

#### Problem: Different trace IDs in producer and consumer

**Symptoms**:
```
Producer: [traceId=abc123 ...]
Consumer: [traceId=xyz789 ...]
```

**Diagnosis**: This is **normal** for asynchronous message queues!

- Producer's trace context is from the HTTP request
- Consumer's trace context is from the message headers
- They should be **different** unless you explicitly propagate the same trace ID

**Solution**: If you want the same trace ID:
```java
// In HTTP handler
String traceId = MDC.get("traceId");  // Get from HTTP request
String spanId = TraceContextUtil.generateSpanId();
String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

Map<String, String> headers = new HashMap<>();
headers.put("traceparent", traceparent);
producer.send(payload, headers, correlationId).get();
```

#### Problem: Trace context not propagating to external services

**Symptoms**:
```
Consumer: [traceId=abc123 ...]
External service: [traceId= ...]
```

**Diagnosis**: You need to manually propagate trace context to external services.

**Solution**:
```java
consumer.subscribe(message -> {
    // Get current trace context
    String traceId = MDC.get("traceId");
    String newSpanId = TraceContextUtil.generateSpanId();
    String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

    // Add to HTTP request
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/orders"))
        .header("traceparent", traceparent)  // ← Add this!
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    client.send(request, HttpResponse.BodyHandlers.ofString());

    return CompletableFuture.completedFuture(null);
});
```

#### Problem: MDC not cleared between messages

**Symptoms**:
```
[traceId=abc123 ...] Processing message 1
[traceId=abc123 ...] Processing message 2  ← Should be different!
```

**Diagnosis**: You're manually managing MDC incorrectly.

**Solution**: Let PeeGeeQConsumer manage MDC automatically:
```java
// Wrong - don't do this
consumer.subscribe(message -> {
    TraceContextUtil.setMDCFromHeaders(message.getHeaders(), message.getCorrelationId());
    processMessage(message);
    TraceContextUtil.clearMDC();
    return CompletableFuture.completedFuture(null);
});

// Correct - PeeGeeQConsumer handles MDC automatically
consumer.subscribe(message -> {
    processMessage(message);
    return CompletableFuture.completedFuture(null);
});
```

#### Problem: Trace IDs in logs but can't search them

**Symptoms**: Logs show trace IDs but `grep` doesn't find them.

**Diagnosis**: Log format issue.

**Solution**: Ensure consistent format in `logback.xml`:
```xml
<!-- Use this exact format -->
<pattern>... [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] ...</pattern>
```

Then search:
```bash
grep "traceId=abc123" application.log
```

#### Problem: Performance degradation with tracing

**Symptoms**: Slow message processing after enabling tracing.

**Diagnosis**: Check database performance.

**Solution**:
1. Add index on message_headers:
   ```sql
   CREATE INDEX idx_message_headers_message_id ON message_headers(message_id);
   ```

2. Check query performance:
   ```sql
   EXPLAIN ANALYZE
   SELECT * FROM message_headers WHERE message_id = 1;
   ```

3. Monitor database connections:
   ```sql
   SELECT count(*) FROM pg_stat_activity;
   ```

### Common Patterns

#### Pattern 1: HTTP → Queue → Consumer

```java
// HTTP Handler
@POST
@Path("/orders")
public Response createOrder(Order order, @HeaderParam("traceparent") String traceparent) {
    // Extract trace context from HTTP request
    TraceContextUtil.setMDCFromTraceparent(traceparent);

    logger.info("Received order: {}", order.getId());
    // Output: [traceId=abc123 ...] Received order: 12345

    // Generate new span for queue message
    String traceId = MDC.get("traceId");
    String newSpanId = TraceContextUtil.generateSpanId();
    String newTraceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

    // Send to queue with trace context
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", newTraceparent);
    producer.send(order, headers, "order-" + order.getId()).get();

    logger.info("Order queued: {}", order.getId());
    // Output: [traceId=abc123 ...] Order queued: 12345

    return Response.accepted().build();
}

// Consumer
consumer.subscribe(message -> {
    // MDC automatically set from message headers
    logger.info("Processing order: {}", message.getPayload().getId());
    // Output: [traceId=abc123 spanId=def456 ...] Processing order: 12345

    processOrder(message.getPayload());

    return CompletableFuture.completedFuture(null);
});
```

#### Pattern 2: Consumer → External Service → Consumer

```java
// Consumer 1: Receives order, calls external service
consumer1.subscribe(message -> {
    logger.info("Validating order");
    // Output: [traceId=abc123 spanId=span1 ...] Validating order

    // Get current trace context
    String traceId = MDC.get("traceId");
    String newSpanId = TraceContextUtil.generateSpanId();
    String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

    // Call external service
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/validate"))
        .header("traceparent", traceparent)
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    logger.info("Validation complete");
    // Output: [traceId=abc123 spanId=span1 ...] Validation complete

    // Send to next queue
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer2.send(message.getPayload(), headers, message.getCorrelationId()).get();

    return CompletableFuture.completedFuture(null);
});

// Consumer 2: Processes validated order
consumer2.subscribe(message -> {
    logger.info("Processing validated order");
    // Output: [traceId=abc123 spanId=span2 ...] Processing validated order

    processOrder(message.getPayload());

    return CompletableFuture.completedFuture(null);
});
```

All logs will have the **same traceId** (abc123) but **different spanIds** (span1, span2), allowing you to trace the entire flow!

---

## Integration with Observability Tools

### OpenTelemetry

PeeGeeQ's W3C Trace Context is fully compatible with OpenTelemetry:

```java
// OpenTelemetry setup
OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    .build();

Tracer tracer = openTelemetry.getTracer("order-service");

// Create span
Span span = tracer.spanBuilder("process-order").startSpan();
try (Scope scope = span.makeCurrent()) {
    // Get trace context
    String traceId = span.getSpanContext().getTraceId();
    String spanId = span.getSpanContext().getSpanId();
    String traceparent = String.format("00-%s-%s-01", traceId, spanId);

    // Send to PeeGeeQ
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(order, headers, correlationId).get();

} finally {
    span.end();
}

// Consumer
consumer.subscribe(message -> {
    // Extract trace context from MDC
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");

    // Create OpenTelemetry span from PeeGeeQ trace context
    SpanContext spanContext = SpanContext.createFromRemoteParent(
        traceId,
        spanId,
        TraceFlags.getSampled(),
        TraceState.getDefault()
    );

    Span span = tracer.spanBuilder("process-message")
        .setParent(Context.current().with(Span.wrap(spanContext)))
        .startSpan();

    try (Scope scope = span.makeCurrent()) {
        processOrder(message.getPayload());
    } finally {
        span.end();
    }

    return CompletableFuture.completedFuture(null);
});
```

### Jaeger

```java
// Jaeger setup
Configuration config = new Configuration("order-service")
    .withSampler(new Configuration.SamplerConfiguration().withType("const").withParam(1))
    .withReporter(new Configuration.ReporterConfiguration().withLogSpans(true));

Tracer tracer = config.getTracer();

// Create span
Span span = tracer.buildSpan("process-order").start();
try {
    // Get trace context
    String traceId = span.context().toTraceId();
    String spanId = span.context().toSpanId();
    String traceparent = String.format("00-%s-%s-01", traceId, spanId);

    // Send to PeeGeeQ
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(order, headers, correlationId).get();

} finally {
    span.finish();
}
```

### Zipkin

```java
// Zipkin setup
Tracing tracing = Tracing.newBuilder()
    .localServiceName("order-service")
    .spanReporter(AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v2/spans")))
    .build();

Tracer tracer = tracing.tracer();

// Create span
Span span = tracer.nextSpan().name("process-order").start();
try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
    // Get trace context
    String traceId = span.context().traceIdString();
    String spanId = span.context().spanIdString();
    String traceparent = String.format("00-%s-%s-01", traceId, spanId);

    // Send to PeeGeeQ
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(order, headers, correlationId).get();

} finally {
    span.finish();
}
```

### ELK Stack (Elasticsearch, Logstash, Kibana)

#### Logstash Configuration

```ruby
input {
  file {
    path => "/var/log/application.log"
    start_position => "beginning"
  }
}

filter {
  grok {
    match => {
      "message" => "%{TIMESTAMP_ISO8601:timestamp} \[%{DATA:thread}\] %{LOGLEVEL:level} %{DATA:logger} - \[traceId=%{DATA:traceId} spanId=%{DATA:spanId} correlationId=%{DATA:correlationId}\] %{GREEDYDATA:message}"
    }
  }

  date {
    match => ["timestamp", "ISO8601"]
  }
}

output {
  elasticsearch {
    hosts => ["localhost:9200"]
    index => "application-logs-%{+YYYY.MM.dd}"
  }
}
```

#### Kibana Queries

```
# Find all logs for a trace
traceId:"abc123"

# Find all logs for a correlation ID
correlationId:"order-12345"

# Find all errors for a trace
traceId:"abc123" AND level:"ERROR"

# Find all logs for a specific service
logger:"OrderService" AND traceId:"abc123"
```

### Grafana Loki

#### Promtail Configuration

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://localhost:3100/loki/api/v1/push

scrape_configs:
  - job_name: application
    static_configs:
      - targets:
          - localhost
        labels:
          job: application
          __path__: /var/log/application.log
    pipeline_stages:
      - regex:
          expression: '.*\[traceId=(?P<traceId>[^\s]*) spanId=(?P<spanId>[^\s]*) correlationId=(?P<correlationId>[^\]]*)\].*'
      - labels:
          traceId:
          spanId:
          correlationId:
```

#### LogQL Queries

```
# Find all logs for a trace
{job="application"} |= "traceId=abc123"

# Find all logs for a correlation ID
{job="application"} |= "correlationId=order-12345"

# Find all errors for a trace
{job="application"} |= "traceId=abc123" |= "ERROR"
```

### AWS X-Ray

```java
// X-Ray setup
AWSXRay.beginSegment("order-service");
try {
    Segment segment = AWSXRay.getCurrentSegment();

    // Get trace context
    String traceId = segment.getTraceId().toString();
    String spanId = segment.getId();
    String traceparent = String.format("00-%s-%s-01", traceId, spanId);

    // Send to PeeGeeQ
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(order, headers, correlationId).get();

} finally {
    AWSXRay.endSegment();
}

// Consumer
consumer.subscribe(message -> {
    // Extract trace context from MDC
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");

    // Create X-Ray subsegment
    Subsegment subsegment = AWSXRay.beginSubsegment("process-message");
    try {
        subsegment.putMetadata("traceId", traceId);
        subsegment.putMetadata("spanId", spanId);
        subsegment.putMetadata("correlationId", MDC.get("correlationId"));

        processOrder(message.getPayload());

    } finally {
        AWSXRay.endSubsegment();
    }

    return CompletableFuture.completedFuture(null);
});
```

### Google Cloud Trace

```java
// Cloud Trace setup
TraceConfig traceConfig = TraceConfig.getDefault();
Tracing tracing = Tracing.newBuilder()
    .setProjectId("my-project")
    .build();

Tracer tracer = tracing.getTracer();

// Create span
Span span = tracer.spanBuilder("process-order").startSpan();
try (Scope scope = tracer.withSpan(span)) {
    // Get trace context
    String traceId = span.getContext().getTraceId().toLowerBase16();
    String spanId = span.getContext().getSpanId().toLowerBase16();
    String traceparent = String.format("00-%s-%s-01", traceId, spanId);

    // Send to PeeGeeQ
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(order, headers, correlationId).get();

} finally {
    span.end();
}
```

### Datadog

```java
// Datadog setup
GlobalTracer.registerIfAbsent(DDTracer.builder().build());
Tracer tracer = GlobalTracer.get();

// Create span
Span span = tracer.buildSpan("process-order").start();
try (Scope scope = tracer.activateSpan(span)) {
    // Get trace context
    String traceId = String.format("%016x", span.context().toTraceId());
    String spanId = String.format("%016x", span.context().toSpanId());
    String traceparent = String.format("00-%s-%s-01", traceId, spanId);

    // Send to PeeGeeQ
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(order, headers, correlationId).get();

} finally {
    span.finish();
}
```

---

## Best Practices

### 0. ⚠️ CRITICAL: Always Clear MDC After Processing

**This is the most important best practice!** See the [Critical: MDC Cleanup](#critical-mdc-cleanup) section for full details.

**Do**:
```java
private void processMessage(Message message) {
    try {
        // Set MDC
        TraceContextUtil.setMDCFromHeaders(message.getHeaders(), message.getCorrelationId());

        // Process message
        handler.accept(message).get();

    } catch (Exception e) {
        logger.error("Error processing message", e);
        throw e;
    } finally {
        // ✅ CRITICAL: Always clear MDC
        TraceContextUtil.clearMDC();
    }
}
```

**Don't**:
```java
private void processMessage(Message message) {
    try {
        // Set MDC
        TraceContextUtil.setMDCFromHeaders(message.getHeaders(), message.getCorrelationId());

        // Process message
        handler.accept(message).get();

    } catch (Exception e) {
        logger.error("Error processing message", e);
        throw e;
    }
    // ❌ WRONG: MDC not cleared - will leak to subsequent operations!
}
```

**Why**: Without MDC cleanup, trace context leaks into unrelated operations (like unsubscribe, shutdown), causing incorrect log correlation and confusion.

### 1. Always Send Trace Context

**Do**:
```java
// Generate trace context
String traceId = TraceContextUtil.generateTraceId();
String spanId = TraceContextUtil.generateSpanId();
String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

// Send with headers
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", traceparent);
producer.send(payload, headers, correlationId).get();
```

**Don't**:
```java
// Missing trace context
producer.send(payload, correlationId).get();
```

### 2. Use Correlation IDs for Business Context

**Do**:
```java
// Use business-meaningful correlation IDs
String correlationId = "order-" + order.getId();
String correlationId = "customer-" + customer.getId();
String correlationId = "payment-" + payment.getId();
```

**Don't**:
```java
// Random UUIDs are not helpful
String correlationId = UUID.randomUUID().toString();
```

### 3. Generate New Spans for Each Service

**Do**:
```java
consumer.subscribe(message -> {
    // Get trace ID from MDC
    String traceId = MDC.get("traceId");

    // Generate NEW span for external call
    String newSpanId = TraceContextUtil.generateSpanId();
    String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

    // Call external service
    httpClient.post("/api/orders")
        .putHeader("traceparent", traceparent)
        .send();

    return CompletableFuture.completedFuture(null);
});
```

**Don't**:
```java
consumer.subscribe(message -> {
    // Reusing same span ID
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");  // ← Don't reuse!
    String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

    httpClient.post("/api/orders")
        .putHeader("traceparent", traceparent)
        .send();

    return CompletableFuture.completedFuture(null);
});
```

### 4. Don't Manually Manage MDC in Consumers

**Do**:
```java
consumer.subscribe(message -> {
    // PeeGeeQConsumer sets MDC automatically
    logger.info("Processing message");
    processMessage(message);
    return CompletableFuture.completedFuture(null);
    // PeeGeeQConsumer clears MDC automatically
});
```

**Don't**:
```java
consumer.subscribe(message -> {
    // Don't do this!
    TraceContextUtil.setMDCFromHeaders(message.getHeaders(), message.getCorrelationId());
    logger.info("Processing message");
    processMessage(message);
    TraceContextUtil.clearMDC();
    return CompletableFuture.completedFuture(null);
});
```

### 5. Log at Key Points

**Do**:
```java
consumer.subscribe(message -> {
    logger.info("Processing order: {}", message.getPayload().getId());

    logger.info("Validating order");
    validateOrder(message.getPayload());

    logger.info("Saving order");
    saveOrder(message.getPayload());

    logger.info("Calling external service");
    callExternalService(message.getPayload());

    logger.info("Order processing complete");

    return CompletableFuture.completedFuture(null);
});
```

**Don't**:
```java
consumer.subscribe(message -> {
    // No logging - can't trace execution
    validateOrder(message.getPayload());
    saveOrder(message.getPayload());
    callExternalService(message.getPayload());
    return CompletableFuture.completedFuture(null);
});
```

### 6. Include Trace Context in Error Logs

**Do**:
```java
consumer.subscribe(message -> {
    try {
        processOrder(message.getPayload());
    } catch (Exception e) {
        // MDC is still set - trace context in error log
        logger.error("Error processing order: {}", message.getPayload().getId(), e);
        // Output: [traceId=abc123 ...] Error processing order: 12345
        throw e;
    }
    return CompletableFuture.completedFuture(null);
});
```

**Don't**:
```java
consumer.subscribe(message -> {
    try {
        processOrder(message.getPayload());
    } catch (Exception e) {
        // Clearing MDC before logging error
        TraceContextUtil.clearMDC();
        logger.error("Error processing order: {}", message.getPayload().getId(), e);
        // Output: [traceId= ...] Error processing order: 12345
        throw e;
    }
    return CompletableFuture.completedFuture(null);
});
```

### 7. Use Consistent Log Format

**Do**:
```xml
<!-- All services use same format -->
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId:-} spanId=%X{spanId:-} correlationId=%X{correlationId:-}] %msg%n</pattern>
```

**Don't**:
```xml
<!-- Service 1 -->
<pattern>... [trace=%X{traceId}] ...</pattern>

<!-- Service 2 -->
<pattern>... [traceId=%X{traceId}] ...</pattern>

<!-- Service 3 -->
<pattern>... [tid=%X{traceId}] ...</pattern>
```

### 8. Test Trace Context Propagation

**Do**:
```java
@Test
public void testTraceContextPropagation() throws Exception {
    // Generate trace context
    String traceId = TraceContextUtil.generateTraceId();
    String spanId = TraceContextUtil.generateSpanId();
    String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

    // Send message
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceparent);
    producer.send(payload, headers, "test-id").get();

    // Verify in consumer
    CountDownLatch latch = new CountDownLatch(1);
    consumer.subscribe(message -> {
        assertEquals(traceId, MDC.get("traceId"));
        assertEquals(spanId, MDC.get("spanId"));
        latch.countDown();
        return CompletableFuture.completedFuture(null);
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
}
```

**Don't**:
```java
@Test
public void testMessageProcessing() throws Exception {
    // No trace context testing
    producer.send(payload, "test-id").get();

    CountDownLatch latch = new CountDownLatch(1);
    consumer.subscribe(message -> {
        processMessage(message);
        latch.countDown();
        return CompletableFuture.completedFuture(null);
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
}
```

### 9. Document Trace Context Requirements

**Do**:
```java
/**
 * Processes orders from the queue.
 *
 * <p>Expects messages to include the following headers:
 * <ul>
 *   <li>traceparent: W3C Trace Context header (required for distributed tracing)</li>
 *   <li>correlationId: Business correlation ID (e.g., order-12345)</li>
 * </ul>
 *
 * <p>All logs will include trace context for correlation across services.
 */
public void processOrders() {
    consumer.subscribe(message -> {
        logger.info("Processing order: {}", message.getPayload().getId());
        processOrder(message.getPayload());
        return CompletableFuture.completedFuture(null);
    });
}
```

### 10. Monitor Trace Context Coverage

**Do**:
```java
// Add metrics for trace context coverage
consumer.subscribe(message -> {
    String traceId = MDC.get("traceId");

    if (traceId == null || traceId.isEmpty()) {
        metrics.increment("messages.without.trace.context");
        logger.warn("Message received without trace context");
    } else {
        metrics.increment("messages.with.trace.context");
    }

    processMessage(message);
    return CompletableFuture.completedFuture(null);
});
```

### 11. Use Trace Context in Alerts

**Do**:
```java
consumer.subscribe(message -> {
    try {
        processOrder(message.getPayload());
    } catch (Exception e) {
        String traceId = MDC.get("traceId");
        String correlationId = MDC.get("correlationId");

        // Include trace context in alert
        alertService.sendAlert(
            "Order processing failed",
            String.format("traceId=%s, correlationId=%s, error=%s",
                traceId, correlationId, e.getMessage())
        );

        throw e;
    }
    return CompletableFuture.completedFuture(null);
});
```

### 12. Archive Logs with Trace Context

**Do**:
```bash
# Archive logs with trace context preserved
tar -czf logs-2024-12-24.tar.gz application.log

# Later, search archived logs
tar -xzOf logs-2024-12-24.tar.gz | grep "traceId=abc123"
```

---

## Examples

### Example 1: Simple Order Processing

```java
public class SimpleOrderExample {
    private static final Logger logger = LoggerFactory.getLogger(SimpleOrderExample.class);

    private final PeeGeeQProducer<Order> producer;
    private final PeeGeeQConsumer<Order> consumer;

    public void submitOrder(Order order) throws Exception {
        // Generate trace context
        String traceId = TraceContextUtil.generateTraceId();
        String spanId = TraceContextUtil.generateSpanId();
        String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

        // Create headers
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", traceparent);

        // Send message
        String correlationId = "order-" + order.getId();
        producer.send(order, headers, correlationId).get();

        logger.info("Order submitted: {}", order.getId());
    }

    public void startProcessing() {
        consumer.subscribe(message -> {
            // MDC automatically set: traceId, spanId, correlationId
            logger.info("Processing order: {}", message.getPayload().getId());

            Order order = message.getPayload();
            processOrder(order);

            logger.info("Order complete: {}", order.getId());

            return CompletableFuture.completedFuture(null);
        });
    }

    private void processOrder(Order order) {
        logger.info("Validating order");
        // Validation logic...

        logger.info("Saving order");
        // Save logic...
    }
}
```

**Log Output**:
```
12:34:56.789 [main] INFO  SimpleOrderExample - [traceId= spanId= correlationId=] Order submitted: 12345
12:34:57.123 [consumer-1] INFO  SimpleOrderExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Processing order: 12345
12:34:57.124 [consumer-1] INFO  SimpleOrderExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Validating order
12:34:57.125 [consumer-1] INFO  SimpleOrderExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Saving order
12:34:57.126 [consumer-1] INFO  SimpleOrderExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Order complete: 12345
```

### Example 2: Multi-Service Flow

```java
public class MultiServiceExample {
    private static final Logger logger = LoggerFactory.getLogger(MultiServiceExample.class);

    // Service 1: Order API
    @POST
    @Path("/orders")
    public Response createOrder(Order order, @HeaderParam("traceparent") String traceparent) {
        // Set MDC from HTTP request
        TraceContextUtil.setMDCFromTraceparent(traceparent);

        logger.info("Received order: {}", order.getId());

        try {
            // Generate new span for queue message
            String traceId = MDC.get("traceId");
            String newSpanId = TraceContextUtil.generateSpanId();
            String newTraceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

            // Send to queue
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", newTraceparent);
            producer.send(order, headers, "order-" + order.getId()).get();

            logger.info("Order queued: {}", order.getId());

            return Response.accepted().build();
        } finally {
            TraceContextUtil.clearMDC();
        }
    }

    // Service 2: Order Processor
    public void startOrderProcessor() {
        orderConsumer.subscribe(message -> {
            logger.info("Processing order: {}", message.getPayload().getId());

            Order order = message.getPayload();

            // Validate with external service
            logger.info("Validating with external service");
            validateWithExternalService(order);

            // Send to fulfillment queue
            logger.info("Sending to fulfillment");
            sendToFulfillment(order);

            logger.info("Order processing complete");

            return CompletableFuture.completedFuture(null);
        });
    }

    private void validateWithExternalService(Order order) {
        // Get current trace context
        String traceId = MDC.get("traceId");
        String newSpanId = TraceContextUtil.generateSpanId();
        String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

        // Call external service
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.example.com/validate"))
            .header("traceparent", traceparent)
            .POST(HttpRequest.BodyPublishers.ofString(toJson(order)))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("Validation response: {}", response.statusCode());
        } catch (Exception e) {
            logger.error("Validation failed", e);
            throw new RuntimeException("Validation failed", e);
        }
    }

    private void sendToFulfillment(Order order) throws Exception {
        // Get current trace context
        String traceId = MDC.get("traceId");
        String newSpanId = TraceContextUtil.generateSpanId();
        String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

        // Send to fulfillment queue
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", traceparent);
        fulfillmentProducer.send(order, headers, "order-" + order.getId()).get();
    }

    // Service 3: Fulfillment Processor
    public void startFulfillmentProcessor() {
        fulfillmentConsumer.subscribe(message -> {
            logger.info("Fulfilling order: {}", message.getPayload().getId());

            Order order = message.getPayload();
            fulfillOrder(order);

            logger.info("Order fulfilled: {}", order.getId());

            return CompletableFuture.completedFuture(null);
        });
    }

    private void fulfillOrder(Order order) {
        logger.info("Allocating inventory");
        // Allocation logic...

        logger.info("Creating shipment");
        // Shipment logic...

        logger.info("Sending notification");
        // Notification logic...
    }
}
```

**Log Output** (all with same traceId):
```
# Service 1: Order API
12:34:56.789 [http-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span1... correlationId=] Received order: 12345
12:34:56.790 [http-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span1... correlationId=] Order queued: 12345

# Service 2: Order Processor
12:34:57.123 [consumer-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span2... correlationId=order-12345] Processing order: 12345
12:34:57.124 [consumer-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span2... correlationId=order-12345] Validating with external service
12:34:57.456 [consumer-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span2... correlationId=order-12345] Validation response: 200
12:34:57.457 [consumer-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span2... correlationId=order-12345] Sending to fulfillment
12:34:57.458 [consumer-1] INFO  MultiServiceExample - [traceId=abc123... spanId=span2... correlationId=order-12345] Order processing complete

# Service 3: Fulfillment Processor
12:34:58.789 [consumer-2] INFO  MultiServiceExample - [traceId=abc123... spanId=span3... correlationId=order-12345] Fulfilling order: 12345
12:34:58.790 [consumer-2] INFO  MultiServiceExample - [traceId=abc123... spanId=span3... correlationId=order-12345] Allocating inventory
12:34:58.791 [consumer-2] INFO  MultiServiceExample - [traceId=abc123... spanId=span3... correlationId=order-12345] Creating shipment
12:34:58.792 [consumer-2] INFO  MultiServiceExample - [traceId=abc123... spanId=span3... correlationId=order-12345] Sending notification
12:34:58.793 [consumer-2] INFO  MultiServiceExample - [traceId=abc123... spanId=span3... correlationId=order-12345] Order fulfilled: 12345
```

**Search**: `grep "traceId=abc123" *.log` shows the **entire flow** across all 3 services!

### Example 3: Error Handling with Trace Context

```java
public class ErrorHandlingExample {
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingExample.class);

    public void startProcessing() {
        consumer.subscribe(message -> {
            try {
                logger.info("Processing order: {}", message.getPayload().getId());

                Order order = message.getPayload();

                // Step 1: Validate
                logger.info("Validating order");
                validateOrder(order);

                // Step 2: Process payment
                logger.info("Processing payment");
                processPayment(order);

                // Step 3: Create shipment
                logger.info("Creating shipment");
                createShipment(order);

                logger.info("Order complete: {}", order.getId());

                return CompletableFuture.completedFuture(null);

            } catch (ValidationException e) {
                logger.error("Validation failed for order: {}", message.getPayload().getId(), e);
                // Send to DLQ with trace context
                sendToDLQ(message, "Validation failed: " + e.getMessage());
                return CompletableFuture.completedFuture(null);

            } catch (PaymentException e) {
                logger.error("Payment failed for order: {}", message.getPayload().getId(), e);
                // Retry with trace context
                return retryPayment(message);

            } catch (Exception e) {
                logger.error("Unexpected error processing order: {}", message.getPayload().getId(), e);
                throw e;
            }
        });
    }

    private void sendToDLQ(Message<Order> message, String reason) {
        try {
            // Get current trace context
            String traceId = MDC.get("traceId");
            String newSpanId = TraceContextUtil.generateSpanId();
            String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

            // Create DLQ message with trace context
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", traceparent);
            headers.put("error", reason);
            headers.put("originalCorrelationId", message.getCorrelationId());

            dlqProducer.send(message.getPayload(), headers, "dlq-" + message.getCorrelationId()).get();

            logger.info("Message sent to DLQ");

        } catch (Exception e) {
            logger.error("Failed to send message to DLQ", e);
        }
    }

    private CompletableFuture<Void> retryPayment(Message<Order> message) {
        logger.info("Retrying payment");

        try {
            // Get current trace context
            String traceId = MDC.get("traceId");
            String newSpanId = TraceContextUtil.generateSpanId();
            String traceparent = TraceContextUtil.createTraceparent(traceId, newSpanId, "01");

            // Send to retry queue with trace context
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", traceparent);
            headers.put("retryCount", "1");

            retryProducer.send(message.getPayload(), headers, message.getCorrelationId()).get();

            logger.info("Message sent to retry queue");

        } catch (Exception e) {
            logger.error("Failed to send message to retry queue", e);
        }

        return CompletableFuture.completedFuture(null);
    }
}
```

**Log Output** (error case):
```
12:34:57.123 [consumer-1] INFO  ErrorHandlingExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Processing order: 12345
12:34:57.124 [consumer-1] INFO  ErrorHandlingExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Validating order
12:34:57.125 [consumer-1] INFO  ErrorHandlingExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Processing payment
12:34:57.126 [consumer-1] ERROR ErrorHandlingExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Payment failed for order: 12345
12:34:57.127 [consumer-1] INFO  ErrorHandlingExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Retrying payment
12:34:57.128 [consumer-1] INFO  ErrorHandlingExample - [traceId=abc123... spanId=def456... correlationId=order-12345] Message sent to retry queue
```

**Search**: `grep "traceId=abc123" *.log` shows the **entire flow including errors and retries**!

---

## Technical Reference

### W3C Trace Context Specification

PeeGeeQ implements the [W3C Trace Context](https://www.w3.org/TR/trace-context/) specification.

#### Traceparent Header Format

```
traceparent: version-trace-id-parent-id-trace-flags
```

**Components**:

| Field | Length | Format | Example | Description |
|-------|--------|--------|---------|-------------|
| version | 2 chars | Hex | `00` | Version (currently always `00`) |
| trace-id | 32 chars | Hex | `4bf92f3577b34da6a3ce929d0e0e4736` | Unique trace identifier |
| parent-id | 16 chars | Hex | `00f067aa0ba902b7` | Parent span identifier |
| trace-flags | 2 chars | Hex | `01` | Trace flags (01 = sampled) |

**Example**:
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

#### Trace Flags

| Value | Meaning |
|-------|---------|
| `00` | Not sampled |
| `01` | Sampled |

PeeGeeQ always uses `01` (sampled) by default.

### SLF4J MDC Fields

| Field | Type | Example | Set By | Description |
|-------|------|---------|--------|-------------|
| `traceId` | String (32 hex) | `4bf92f3577b34da6a3ce929d0e0e4736` | PeeGeeQConsumer | W3C trace ID |
| `spanId` | String (16 hex) | `00f067aa0ba902b7` | PeeGeeQConsumer | W3C span/parent ID |
| `correlationId` | String | `order-12345` | PeeGeeQConsumer | Message correlation ID |
| `messageId` | String | `msg-12345` | PeeGeeQConsumer | Unique message ID |
| `topic` | String | `orders` | PeeGeeQConsumer | Queue/topic name |
| `setupId` | String | `prod-db` | PeeGeeQConsumer | Database setup ID |
| `queueName` | String | `orders` | PeeGeeQConsumer | Queue name |

### Database Schema

#### messages table

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
CREATE INDEX idx_messages_next_retry_at ON messages(next_retry_at) WHERE processed = FALSE;
```

#### message_headers table

```sql
CREATE TABLE message_headers (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    header_key VARCHAR(255) NOT NULL,
    header_value TEXT,
    PRIMARY KEY (message_id, header_key)
);

CREATE INDEX idx_message_headers_message_id ON message_headers(message_id);
CREATE INDEX idx_message_headers_key ON message_headers(header_key);
```

### TraceContextUtil API

#### Methods

```java
/**
 * Set MDC from W3C traceparent header.
 *
 * @param traceparent W3C traceparent header (e.g., "00-{trace-id}-{span-id}-01")
 */
public static void setMDCFromTraceparent(String traceparent)

/**
 * Set MDC from message headers.
 *
 * @param headers Message headers (must contain "traceparent" key)
 * @param correlationId Message correlation ID
 */
public static void setMDCFromHeaders(Map<String, String> headers, String correlationId)

/**
 * Clear all MDC fields set by PeeGeeQ.
 */
public static void clearMDC()

/**
 * Generate a new W3C trace ID (32 hex characters).
 *
 * @return Trace ID
 */
public static String generateTraceId()

/**
 * Generate a new W3C span ID (16 hex characters).
 *
 * @return Span ID
 */
public static String generateSpanId()

/**
 * Create a W3C traceparent header.
 *
 * @param traceId Trace ID (32 hex characters)
 * @param spanId Span ID (16 hex characters)
 * @param flags Trace flags (e.g., "01" for sampled)
 * @return Traceparent header (e.g., "00-{trace-id}-{span-id}-01")
 */
public static String createTraceparent(String traceId, String spanId, String flags)

/**
 * Parse a W3C traceparent header.
 *
 * @param traceparent Traceparent header
 * @return Map with keys: "version", "traceId", "spanId", "flags"
 */
public static Map<String, String> parseTraceparent(String traceparent)

/**
 * Validate a W3C traceparent header.
 *
 * @param traceparent Traceparent header
 * @return true if valid, false otherwise
 */
public static boolean isValidTraceparent(String traceparent)
```

#### Usage Examples

```java
// Generate trace context
String traceId = TraceContextUtil.generateTraceId();
String spanId = TraceContextUtil.generateSpanId();
String traceparent = TraceContextUtil.createTraceparent(traceId, spanId, "01");

// Parse trace context
Map<String, String> parsed = TraceContextUtil.parseTraceparent(traceparent);
String extractedTraceId = parsed.get("traceId");
String extractedSpanId = parsed.get("spanId");

// Validate trace context
boolean valid = TraceContextUtil.isValidTraceparent(traceparent);

// Set MDC
TraceContextUtil.setMDCFromTraceparent(traceparent);

// Get from MDC
String currentTraceId = MDC.get("traceId");
String currentSpanId = MDC.get("spanId");

// Clear MDC
TraceContextUtil.clearMDC();
```

### Performance Characteristics

| Operation | Time | Notes |
|-----------|------|-------|
| Generate trace ID | ~1 μs | Uses UUID.randomUUID() |
| Generate span ID | ~1 μs | Uses UUID.randomUUID() |
| Parse traceparent | ~1 μs | Simple string split |
| Set MDC | ~1 μs | Thread-local map put |
| Clear MDC | ~1 μs | Thread-local map clear |
| Store headers in DB | ~1 ms | 2 INSERT statements |
| Load headers from DB | ~1 ms | 1 SELECT statement |

**Total overhead per message**: ~2 ms (< 0.1% for typical message processing)

### Thread Safety

- **SLF4J MDC**: Thread-local, inherently thread-safe
- **TraceContextUtil**: Stateless, thread-safe
- **PeeGeeQConsumer**: Processes one message at a time per thread, thread-safe
- **PeeGeeQProducer**: Thread-safe (uses connection pooling)

### Compatibility

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

### Limitations

1. **No automatic span creation**: PeeGeeQ only propagates trace context via MDC. You need to manually create spans for observability tools.

2. **No automatic trace sampling**: PeeGeeQ always uses trace flags `01` (sampled). Implement custom sampling logic if needed.

3. **No automatic trace export**: PeeGeeQ only logs trace context. Use observability tools to collect and visualize traces.

4. **No parent-child span relationships**: PeeGeeQ doesn't maintain span hierarchy. Use observability tools for span relationships.

5. **No distributed context propagation beyond headers**: Custom context (baggage) must be manually propagated via message headers.

### Security Considerations

1. **Trace IDs are not secrets**: Trace IDs are logged and should not contain sensitive information.

2. **Correlation IDs may contain PII**: Be careful with correlation IDs that contain customer IDs or other PII.

3. **Headers are stored in database**: Message headers (including traceparent) are stored in plaintext in the database.

4. **Log sanitization**: Ensure logs don't contain sensitive information when using trace context.

---

## Implementation Summary

### What PeeGeeQ Provides Out-of-the-Box

✅ **Automatic MDC Population and Cleanup**
- PeeGeeQConsumer automatically extracts trace context from message headers
- Sets SLF4J MDC with traceId, spanId, correlationId
- **Automatically clears MDC after message processing** (in finally block)
- Prevents trace context leakage to unrelated operations

✅ **W3C Trace Context Support**
- Full W3C Trace Context specification compliance
- Compatible with all major observability tools
- Standard traceparent header format

✅ **TraceCtx Record**
- Immutable W3C Trace Context container
- Create root traces and child spans
- Parse and generate traceparent headers

✅ **TraceContextUtil Helper**
- MDC scope with auto-cleanup (`mdcScope()`)
- Trace context capture from MDC
- Vert.x Context key constants

✅ **AsyncTraceUtils**
- Worker thread trace propagation (`executeBlockingTraced()`)
- Event Bus trace injection (`publishWithTrace()`, `requestWithTrace()`)
- Auto-tracing consumers (`tracedConsumer()`)

✅ **Custom Logback Converters**
- `VertxTraceIdConverter` - `%vxTrace` pattern
- `VertxSpanIdConverter` - `%vxSpan` pattern
- MDC-first, Vert.x Context fallback resolution

✅ **Thread Safety**
- Thread-local MDC (no cross-thread contamination)
- Safe for concurrent message processing
- Automatic cleanup via try-with-resources

### What You Need to Implement

✅ **Generate Trace Context in Producers** (Use TraceCtx)
```java
TraceCtx rootSpan = TraceCtx.createNew();

Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
producer.send(payload, headers, correlationId).get();
```

✅ **Configure Logback** (Use custom converters)
```xml
<conversionRule conversionWord="vxTrace" 
                converterClass="dev.mars.peegeeq.api.logging.VertxTraceIdConverter"/>
<conversionRule conversionWord="vxSpan" 
                converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter"/>
<pattern>... [trace=%vxTrace span=%vxSpan] ...</pattern>
```

✅ **Propagate to External Services** (Use child spans)
```java
TraceCtx current = TraceContextUtil.captureTraceContext();
TraceCtx childSpan = current.childSpan("external-call");

httpClient.post("/api/orders")
    .putHeader("traceparent", childSpan.traceparent())
    .send();
```

⏳ **Integrate with Observability Tools** (Optional Enhancement)
- OpenTelemetry, Jaeger, Zipkin export
- Not required for internal tracing

### Quick Implementation Checklist

- [x] Register `%vxTrace` and `%vxSpan` converters in `logback.xml`
- [x] Use `TraceCtx.createNew()` for root traces
- [x] Use `childSpan()` for creating child spans
- [x] Use `mdcScope()` for auto-cleanup MDC management
- [x] Use `AsyncTraceUtils` for async boundaries
- [ ] (Optional) Integrate with OpenTelemetry export
- [ ] (Optional) Add custom MDC fields
- [ ] (Optional) Implement trace sampling

### Common Pitfalls to Avoid

❌ **CRITICAL: Not using mdcScope()** (See [Critical: MDC Cleanup](#critical-mdc-cleanup))
```java
// Wrong - manual MDC management, easy to forget cleanup
private void processMessage(Message message) {
    MDC.put("traceId", trace.traceId());
    MDC.put("spanId", trace.spanId());
    try {
        handler.accept(message).get();
    } finally {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}

// Correct - automatic cleanup via mdcScope()
private void processMessage(Message message) {
    TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
    try (var scope = TraceContextUtil.mdcScope(trace)) {
        handler.accept(message).get();
    }  // ✅ MDC auto-cleared
}
```

❌ **Forgetting to send headers**
```java
// Wrong
producer.send(payload, correlationId).get();

// Correct
Map<String, String> headers = new HashMap<>();
headers.put("traceparent", rootSpan.traceparent());
producer.send(payload, headers, correlationId).get();
```

❌ **Reusing span IDs instead of creating child spans**
```java
// Wrong - reusing parent span
String parentTraceparent = msg.headers().get("traceparent");
// Just forwarding the same traceparent...

// Correct - create child span
TraceCtx parent = TraceCtx.parseOrCreate(parentTraceparent);
TraceCtx child = parent.childSpan("my-operation");
// Use child.traceparent() for downstream
```

❌ **Expecting trace context in administrative operations**
```java
// Normal - administrative ops show "-" for trace/span
// [trace=- span=-] Starting application...
// [trace=- span=-] Unsubscribing from queue...

// Expected - message processing has trace context
// [trace=abc123 span=def456] Processing order...
```

### Testing Your Implementation

```java
@Test
@DisplayName("Verify distributed tracing with child spans")
void testDistributedTracing(Vertx vertx, VertxTestContext testContext) {
    // 1. Create root trace
    TraceCtx rootSpan = TraceCtx.createNew();
    String expectedTraceId = rootSpan.traceId();
    
    vertx.runOnContext(v -> {
        // 2. Store in Vert.x Context
        Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
        
        // 3. Execute on worker with trace propagation
        AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, true, () -> {
            // 4. Verify traceId is constant
            String workerTraceId = MDC.get("traceId");
            assertEquals(expectedTraceId, workerTraceId);
            
            // 5. Verify spanId is different (child span)
            String workerSpanId = MDC.get("spanId");
            assertNotEquals(rootSpan.spanId(), workerSpanId);
            
            return "done";
        }).onComplete(ar -> testContext.completeNow());
    });
}
```

### Next Steps

1. **Start Simple**: Register converters in logback.xml and create a root trace
2. **Verify**: Check logs for `[trace=xxx span=yyy]` output
3. **Search**: Use `grep "trace=abc123"` to find all logs for a trace
4. **Expand**: Use `AsyncTraceUtils` for all async boundaries
5. **Propagate**: Use `childSpan()` for external service calls
6. **Monitor**: Run `TraceIdSpanIdDemoTest` to verify behavior

### Resources

- [W3C Trace Context Specification](https://www.w3.org/TR/trace-context/)
- [SLF4J MDC Documentation](http://www.slf4j.org/manual.html#mdc)
- [OpenTelemetry Java](https://opentelemetry.io/docs/instrumentation/java/)
- [Logback Configuration](http://logback.qos.ch/manual/configuration.html)

### Support

For questions or issues:
1. Check the [FAQ](#faq--troubleshooting) section
2. Review the [Examples](#examples) section
3. Run the demonstration test: `mvn test -Dtest=TraceIdSpanIdDemoTest -pl peegeeq-integration-tests`
4. Check the logs for trace context

---

## Conclusion

PeeGeeQ's distributed tracing support provides **automatic trace context propagation** through message queues using the **W3C Trace Context standard** and **SLF4J MDC**.

### Key Takeaways

1. **TraceCtx Record**: Immutable W3C Trace Context with `childSpan()` for hierarchy
2. **mdcScope()**: Auto-cleanup MDC management via try-with-resources
3. **AsyncTraceUtils**: Complete async boundary handling for Vert.x
4. **Custom Converters**: `%vxTrace` and `%vxSpan` resolve from MDC or Vert.x Context
5. **Thread-Safe**: MDC is thread-local, safe for concurrent processing
6. **"-" Indicator**: Clear signal when no trace context exists

### Benefits

- **End-to-end visibility**: Track requests across all services
- **Faster debugging**: Find all logs for a specific request with `grep "trace=xxx"`
- **Better monitoring**: Correlate logs, metrics, and traces
- **Standard compliance**: Compatible with W3C Trace Context tools
- **Production-ready**: Tested via `TraceIdSpanIdDemoTest`

### Get Started Now

```bash
# 1. Run the demonstration test
mvn test -Dtest=TraceIdSpanIdDemoTest -pl peegeeq-integration-tests

# 2. Check the logs - observe traceId constant, spanId changing
grep "trace=" logs/smoke-tests.log

# 3. See trace context in action!
# [trace=2f1b5099dabac0a4f947103d6449dff4 span=30df90ea7da79b22] ROOT SPAN
# [trace=2f1b5099dabac0a4f947103d6449dff4 span=5df007e10e20ffbb] CHILD 1 (parent: root)
```

**Happy tracing! 🎉**

---

*Last updated: 2025-12-24*
*Version: 1.0*
*Status: ✅ Production-Ready*

