# PeeGeeQ Tracing and Observability Analysis

**Date:** 2025-12-24  
**Last Updated:** 2026-01-06  
**Purpose:** Analyze current state of distributed tracing and observability across PeeGeeQ layers

## Executive Summary

### Current State: ✅ **Comprehensive Implementation**

**What We Have:**
- ✅ **Correlation ID propagation** - Full implementation across all layers
- ✅ **Metrics collection** - Micrometer-based metrics with Prometheus export
- ✅ **Structured logging** - SLF4J with trace/span IDs in logs
- ✅ **Documentation** - Comprehensive tracing guide with examples
- ✅ **W3C Trace Context propagation** - Full implementation via `TraceCtx` record
- ✅ **Span creation** - Child span propagation across async boundaries
- ✅ **MDC (Mapped Diagnostic Context)** - Automatic trace/span ID injection via custom Logback converters
- ✅ **Vert.x async trace propagation** - `AsyncTraceUtils` for worker threads and Event Bus

**What We're Missing (Optional Future Enhancements):**
- ⏳ **OpenTelemetry/Jaeger integration** - Optional external tracing export (not required for internal tracing)
- ⏳ **Automatic REST layer span extraction** - Could auto-extract `traceparent` from HTTP headers

## Current Implementation Analysis

### 1. Correlation ID Propagation ✅

**Status:** Fully implemented and working

**Flow:**
```
HTTP Request (correlationId in body)
  ↓
QueueHandler.sendMessage()
  ↓ Extract/generate correlationId
MessageRequest.correlationId
  ↓ Pass to producer
PgNativeQueueProducer.send(payload, headers, correlationId, messageGroup)
  ↓ Store in database
PostgreSQL: queue_messages.correlation_id (VARCHAR(255))
```

**Implementation:**
<augment_code_snippet path="peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java" mode="EXCERPT">
````java
// Use correlation ID from request field, then headers, then generate one
final String correlationId;
if (request.getCorrelationId() != null && !request.getCorrelationId().trim().isEmpty()) {
    correlationId = request.getCorrelationId();
} else if (headers.get("correlationId") != null) {
    correlationId = headers.get("correlationId");
} else {
    correlationId = java.util.UUID.randomUUID().toString();
}
````
</augment_code_snippet>

**Database Storage:**
- Stored in `queue_messages.correlation_id` column
- Indexed for fast lookups
- Propagated to outbox and bi-temporal event stores

### 2. Metrics Collection ✅

**Status:** Fully implemented with Micrometer

**Dependencies:**
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <version>1.12.0</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <version>1.12.0</version>
</dependency>
```

**Metrics Collected:**
- Message send/receive counts by topic
- Message processing duration
- Dead letter queue counts
- Connection pool metrics
- Consumer group metrics
- Notice handler metrics

**Implementation:**
<augment_code_snippet path="peegeeq-db/src/main/java/dev/mars/peegeeq/db/metrics/PeeGeeQMetrics.java" mode="EXCERPT">
````java
@Override
public void recordMessageDeadLettered(String topic, String reason) {
    if (messagesDeadLettered != null) {
        messagesDeadLettered.increment();
    }
    if (registry != null) {
        Counter.builder("peegeeq.messages.dead_lettered.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .tag("reason", reason)
            .register(registry)
            .increment();
    }
}
````
</augment_code_snippet>

### 3. Structured Logging ✅

**Status:** Fully implemented with SLF4J/Logback and custom converters

**Configuration:**
- Module-specific log levels (DEBUG for dev, INFO/WARN for prod)
- Structured log patterns with timestamps
- Component-specific loggers for fine-grained control
- **Custom Logback converters for Vert.x trace context:**

**Custom Converters (peegeeq-api):**

`VertxTraceIdConverter` - Retrieves traceId from MDC or Vert.x Context:
```java
// peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxTraceIdConverter.java
public class VertxTraceIdConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        // 1. Try MDC first (fastest)
        String mdcTraceId = event.getMDCPropertyMap().get("traceId");
        if (mdcTraceId != null && !mdcTraceId.isEmpty()) {
            return mdcTraceId;
        }
        // 2. Try Vert.x Context
        Context ctx = Vertx.currentContext();
        if (ctx != null) {
            Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
            if (traceObj instanceof TraceCtx) {
                 return ((TraceCtx) traceObj).traceId();
            }
        }
        return "-"; // No trace indicator
    }
}
```

`VertxSpanIdConverter` - Retrieves spanId similarly:
```java
// peegeeq-api/src/main/java/dev/mars/peegeeq/api/logging/VertxSpanIdConverter.java
public class VertxSpanIdConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        String mdcSpanId = event.getMDCPropertyMap().get("spanId");
        if (mdcSpanId != null && !mdcSpanId.isEmpty()) {
            return mdcSpanId;
        }
        Context ctx = Vertx.currentContext();
        if (ctx != null) {
            Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
            if (traceObj instanceof TraceCtx) {
                 return ((TraceCtx) traceObj).spanId();
            }
        }
        return "-"; // No span indicator
    }
}
```

**Logback Configuration (all 16 modules updated):**
```xml
<configuration>
    <!-- Register custom converters -->
    <conversionRule conversionWord="vxTrace" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxTraceIdConverter"/>
    <conversionRule conversionWord="vxSpan" 
                    converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
</configuration>
```

**Example Log Output:**
```
22:19:54.917 [vert.x-eventloop-thread-0] INFO  [trace=2f1b5099dabac0a4f947103d6449dff4 span=30df90ea7da79b22] d.m.p.i.t.TraceIdSpanIdDemoTest - ROOT SPAN
22:19:54.918 [vert.x-eventloop-thread-0] INFO  [trace=2f1b5099dabac0a4f947103d6449dff4 span=5df007e10e20ffbb] d.m.p.i.t.TraceIdSpanIdDemoTest - CHILD 1 (parent: root)
22:19:54.918 [vert.x-eventloop-thread-0] INFO  [trace=2f1b5099dabac0a4f947103d6449dff4 span=b2bb9612255e77cd] d.m.p.i.t.TraceIdSpanIdDemoTest - CHILD 2 (parent: child1)
```

**Example Configurations:**
```properties
# peegeeq-db/src/main/resources/logback.xml
logging.level.dev.mars.peegeeq.db=DEBUG
logging.level.dev.mars.peegeeq.db.PeeGeeQManager=DEBUG
logging.level.dev.mars.peegeeq.db.connection.PgConnectionManager=DEBUG

# peegeeq-bitemporal/src/main/resources/logback.xml
logging.level.dev.mars.peegeeq.bitemporal=DEBUG
logging.level.dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore=DEBUG
```

### 4. Documentation for OpenTelemetry ✅

**Status:** Comprehensive examples in documentation

**Location:** `docs-core/PEEGEEQ_COMPLETE_GUIDE.md`

**Example Code Provided:**
- OpenTelemetry SDK setup with Jaeger exporter
- W3C Trace Context injection/extraction
- Span creation for message processing
- Trace context propagation through message headers

<augment_code_snippet path="docs-core/PEEGEEQ_COMPLETE_GUIDE.md" mode="EXCERPT">
````java
private void injectTraceContext(Map<String, String> headers) {
    // Inject W3C trace context into headers
    TextMapSetter<Map<String, String>> setter = Map::put;
    
    GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
        .inject(Context.current(), headers, setter);
}
````
</augment_code_snippet>

## Missing Implementation: Distributed Tracing

### ~~1. W3C Trace Context Propagation~~ ✅ **IMPLEMENTED**

**Implementation Status:** Fully implemented via `TraceCtx` record and utilities.

**Core Implementation (`peegeeq-api`):**

#### TraceCtx Record - W3C Trace Context
```java
// peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceCtx.java
public record TraceCtx(String traceId, String spanId, String parentSpanId, String traceparent) {
    
    // Create new root trace
    public static TraceCtx createNew() {
        String traceId = generateHex(32);  // 32 hex chars
        String spanId = generateHex(16);   // 16 hex chars
        String traceparent = String.format("00-%s-%s-01", traceId, spanId);
        return new TraceCtx(traceId, spanId, null, traceparent);
    }
    
    // Parse incoming traceparent header
    public static TraceCtx parseOrCreate(String traceparent) { ... }
    
    // Create child span (same traceId, new spanId)
    public TraceCtx childSpan(String operationName) {
        String newSpanId = generateHex(16);
        String newTraceparent = String.format("00-%s-%s-01", traceId, newSpanId);
        return new TraceCtx(traceId, newSpanId, this.spanId, newTraceparent);
    }
}
```

#### TraceContextUtil - MDC Management
```java
// peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/TraceContextUtil.java
public class TraceContextUtil {
    public static final String CONTEXT_TRACE_KEY = "trace.ctx";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    
    // Apply trace to MDC with auto-cleanup
    public static MDCScope mdcScope(TraceCtx trace) {
        MDC.put(MDC_TRACE_ID, trace.traceId());
        MDC.put(MDC_SPAN_ID, trace.spanId());
        return new MDCScope(true); // Auto-cleans on close
    }
}
```

**Usage Pattern:**
```java
// Create root trace
TraceCtx rootSpan = TraceCtx.createNew();

// Store in Vert.x Context (source of truth)
Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);

// Apply to MDC for logging
try (var scope = TraceContextUtil.mdcScope(rootSpan)) {
    log.info("Processing request"); // Logs include [trace=xxx span=yyy]
}
```

### ~~2. Automatic Span Creation~~ ✅ **IMPLEMENTED**

**Implementation Status:** Fully implemented via `AsyncTraceUtils` class.

#### AsyncTraceUtils - Async Trace Propagation
```java
// peegeeq-api/src/main/java/dev/mars/peegeeq/api/tracing/AsyncTraceUtils.java

// Worker thread execution with child span
public static <T> Future<T> executeBlockingTraced(
        Vertx vertx, WorkerExecutor worker, boolean ordered, Callable<T> blocking) {
    TraceCtx parent = getOrCreateTrace(vertx);
    TraceCtx span = parent.childSpan("executeBlocking");
    
    return worker.executeBlocking(() -> {
        try (var scope = TraceContextUtil.mdcScope(span)) {
            return blocking.call();
        }
    }, ordered);
}

// Event Bus publish with trace propagation
public static void publishWithTrace(Vertx vertx, String address, Object message) {
    TraceCtx parent = getOrCreateTrace(vertx);
    TraceCtx childSpan = parent.childSpan("publish:" + address);
    
    DeliveryOptions opts = new DeliveryOptions();
    opts.addHeader("traceparent", childSpan.traceparent());
    vertx.eventBus().publish(address, message, opts);
}

// Event Bus consumer with auto trace extraction
public static <T> MessageConsumer<T> tracedConsumer(
        Vertx vertx, String address, Handler<Message<T>> handler) {
    return vertx.eventBus().<T>consumer(address, msg -> {
        String traceparent = msg.headers().get("traceparent");
        TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
        TraceCtx span = trace.childSpan("consumer:" + address);
        
        try (var scope = TraceContextUtil.mdcScope(span)) {
            handler.handle(msg);
        }
    });
}
```

**Modules Using AsyncTraceUtils:**
- `peegeeq-service-manager` - PeeGeeQServiceManager, InstanceRegistrationHandler, FederatedManagementHandler
- `peegeeq-native` - PgNativeQueueConsumer (tracedConsumer pattern)
- `peegeeq-bitemporal` - Event-driven lifecycle tests

### ~~3. MDC (Mapped Diagnostic Context)~~ ✅ **IMPLEMENTED**

**Implementation Status:** Fully implemented with custom Logback converters.

**Key Components:**
1. `VertxTraceIdConverter` - `%vxTrace` pattern for traceId
2. `VertxSpanIdConverter` - `%vxSpan` pattern for spanId
3. `TraceContextUtil.mdcScope()` - Auto-cleanup MDC scope

**Resolution Priority:**
1. MDC (fastest, works for blocking code)
2. Vert.x Context (works for event loop code without MDC)
3. Returns "-" if no trace exists

**All 16 Logback Configs Updated:**
- peegeeq-api: logback.xml, logback-test.xml
- peegeeq-db: logback.xml, logback-test.xml  
- peegeeq-native: logback.xml, logback-test.xml
- peegeeq-bitemporal: logback.xml, logback-test.xml
- peegeeq-outbox: logback.xml, logback-test.xml
- peegeeq-rest: logback.xml, logback-test.xml
- peegeeq-service-manager: logback.xml, logback-test.xml
- peegeeq-integration-tests: logback-test.xml
- peegeeq-examples: logback.xml

## Current Implementation: Full Distributed Tracing

### Actual State: W3C Trace Context with Span Propagation

```
┌─────────────────────────────────────────────────────────────┐
│  HTTP Request                                               │
│  Headers: traceparent: 00-4bf92f...736-00f067...-01         │
│  Body: { "correlationId": "abc-123" }                       │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Handler (Event Loop)                                       │
│  TraceCtx rootSpan = TraceCtx.parseOrCreate(traceparent);   │
│  ctx.put(CONTEXT_TRACE_KEY, rootSpan);                      │
│  try (var scope = mdcScope(rootSpan)) {                     │
│      log.info("Processing request");                        │
│  }                                                          │
│  Log: [trace=4bf92f...736 span=00f067...] Processing request│
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Worker Thread (via executeBlockingTraced)                  │
│  TraceCtx childSpan = parent.childSpan("db-operation");     │
│  try (var scope = mdcScope(childSpan)) {                    │
│      // Blocking DB call                                    │
│      log.info("Inserting message");                         │
│  }                                                          │
│  Log: [trace=4bf92f...736 span=a3ce92...] Inserting message │
│       ↑ Same traceId        ↑ Different spanId              │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Event Bus Consumer (via tracedConsumer)                    │
│  String traceparent = msg.headers().get("traceparent");     │
│  TraceCtx trace = parseOrCreate(traceparent);               │
│  TraceCtx span = trace.childSpan("consumer:address");       │
│  try (var scope = mdcScope(span)) {                         │
│      log.info("Consuming message");                         │
│  }                                                          │
│  Log: [trace=4bf92f...736 span=d0e0e47...] Consuming message│
│       ↑ Same traceId        ↑ Different spanId              │
└─────────────────────────────────────────────────────────────┘

Actual Test Output:
========== TRACE ID vs SPAN ID DEMONSTRATION ==========
Expected TraceId: eac7ee5b5b770ae4863566755bddb5de
Root SpanId: 1b121c0d70b3cf56

[trace=eac7ee5b5b770ae4863566755bddb5de span=1b121c0d70b3cf56] STEP 1: Root span on event loop
[trace=eac7ee5b5b770ae4863566755bddb5de span=ae878be183a2127f] STEP 2: Child span on worker thread
[trace=eac7ee5b5b770ae4863566755bddb5de span=1b121c0d70b3cf56] STEP 3: Back to root span on event loop

Analysis:
  - STEP 1 (event loop): spanId = 1b121c0d70b3cf56
  - STEP 2 (worker):     spanId = ae878be183a2127f <-- DIFFERENT (child span)
  - STEP 3 (event loop): spanId = 1b121c0d70b3cf56 <-- SAME as STEP 1 (root span)
  - TraceId: SAME across all steps ✓
=======================================================
```

### Key Concepts Demonstrated

| Concept | Behavior | Purpose |
|---------|----------|---------|
| **traceId** | CONSTANT across all operations | Correlates entire request flow |
| **spanId** | CHANGES per unit of work | Identifies specific operations |
| **parentSpanId** | Links child to parent | Builds span hierarchy |
| **traceparent** | W3C header format | Interoperability |

## Recommendations

### ~~Priority 1: Add W3C Trace Context Propagation~~ ✅ **COMPLETED**

**Status:** Implemented in `peegeeq-api` module

**Completed Tasks:**
- ✅ `TraceCtx` record for W3C traceparent parsing and generation
- ✅ `TraceContextUtil` for MDC management and scope handling
- ✅ `AsyncTraceUtils` for worker thread and Event Bus propagation
- ✅ Integration tests (`TraceIdSpanIdDemoTest`) verifying behavior

### ~~Priority 2: Add MDC Support~~ ✅ **COMPLETED**

**Status:** Implemented across all 16 modules

**Completed Tasks:**
- ✅ `VertxTraceIdConverter` custom Logback converter (%vxTrace)
- ✅ `VertxSpanIdConverter` custom Logback converter (%vxSpan)
- ✅ All logback.xml and logback-test.xml files updated
- ✅ Returns "-" indicator when no trace context exists
- ✅ MDC auto-cleanup via `try (var scope = mdcScope(trace))` pattern

### Priority 3: Add OpenTelemetry Export (Optional Enhancement)

**Status:** Not implemented - Optional for external tracing integration

**Effort:** 1-2 weeks
**Impact:** Export traces to Jaeger/Zipkin for visualization

**Potential Tasks:**
1. Add OpenTelemetry dependencies (optional module)
2. Create `OpenTelemetryExporter` that wraps `TraceCtx`
3. Add Jaeger/Zipkin exporter configuration
4. Add Grafana dashboard templates

**Dependencies to Add (if needed):**
```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.32.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>1.32.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-jaeger</artifactId>
    <version>1.32.0</version>
</dependency>
```

**Note:** Current implementation provides full internal tracing without external dependencies. OpenTelemetry export is only needed if you want to visualize traces in Jaeger/Zipkin.

## Implementation Roadmap

### Phase 1: Foundation ✅ **COMPLETED**
- ✅ Correlation ID propagation
- ✅ Metrics collection (Micrometer)
- ✅ Structured logging (SLF4J/Logback)
- ✅ **W3C Trace Context propagation** (`TraceCtx`, `TraceContextUtil`)
- ✅ **MDC support** (`VertxTraceIdConverter`, `VertxSpanIdConverter`)
- ✅ **Async trace propagation** (`AsyncTraceUtils`)

### Phase 2: Enhanced Observability ✅ **COMPLETED**
- ✅ Custom Logback converters for Vert.x context
- ✅ Auto-cleanup MDC scopes
- ✅ Worker thread trace propagation
- ✅ Event Bus trace propagation
- ✅ Integration tests demonstrating behavior

### Phase 3: Production Readiness (Optional Future)
- ⏳ OpenTelemetry export to Jaeger/Zipkin
- ⏳ Grafana dashboard templates
- ⏳ Performance testing with tracing enabled
- ⏳ Sampling configuration

## Testing Strategy

### Unit Tests ✅
- ✅ `TraceContextUtilTest` - MDC scope cleanup verification
- ✅ `TraceCtxTest` - W3C traceparent parsing, child span creation

### Integration Tests ✅
- ✅ `TraceIdSpanIdDemoTest` - Demonstrates traceId/spanId behavior
  - Test 1: Nested span hierarchy (4 levels, same traceId, different spanIds)
  - Test 2: Worker thread propagation (child span on worker, root span preserved)
  - Test 3: Event Bus propagation (traceparent header injection/extraction)

### Run the Demo Test:
```bash
cd peegeeq-integration-tests
mvn test -Dtest=TraceIdSpanIdDemoTest
```

### Expected Output:
```
========== NESTED SPAN HIERARCHY ==========
TraceId (same for all): 2f1b5099dabac0a4f947103d6449dff4

Span Hierarchy:
  ROOT:    spanId=30df90ea7da79b22 parentSpanId=null
  CHILD1:  spanId=5df007e10e20ffbb parentSpanId=30df90ea7da79b22
  CHILD2:  spanId=b2bb9612255e77cd parentSpanId=5df007e10e20ffbb
  CHILD3:  spanId=78bc2cfcf76e8e87 parentSpanId=b2bb9612255e77cd
============================================
```

## Conclusion

**Current State:** ✅ **Comprehensive distributed tracing implemented**

**What's Implemented:**
- ✅ W3C Trace Context via `TraceCtx` record
- ✅ MDC integration via custom Logback converters (`%vxTrace`, `%vxSpan`)
- ✅ Async trace propagation via `AsyncTraceUtils`
- ✅ All 16 logback configs updated with trace/span patterns
- ✅ Integration tests demonstrating traceId/spanId behavior

**Key Files:**
| File | Purpose |
|------|---------|
| `peegeeq-api/.../tracing/TraceCtx.java` | W3C Trace Context record |
| `peegeeq-api/.../tracing/TraceContextUtil.java` | MDC scope management |
| `peegeeq-api/.../tracing/AsyncTraceUtils.java` | Worker/EventBus propagation |
| `peegeeq-api/.../logging/VertxTraceIdConverter.java` | Logback %vxTrace |
| `peegeeq-api/.../logging/VertxSpanIdConverter.java` | Logback %vxSpan |

**Optional Future Enhancement:**
- ⏳ OpenTelemetry export for Jaeger/Zipkin visualization (only needed if external tracing is required)

**Summary:**
The tracing implementation provides full observability without external dependencies:
- **TraceId** correlates all operations within a distributed request
- **SpanId** identifies specific units of work within that trace
- **Logs** automatically include `[trace=xxx span=yyy]` via custom converters
- **Async boundaries** (worker threads, Event Bus) properly propagate context
