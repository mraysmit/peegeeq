# PeeGeeQ Tracing and Observability Analysis

**Date:** 2025-12-24  
**Purpose:** Analyze current state of distributed tracing and observability across PeeGeeQ layers

## Executive Summary

### Current State: ⚠️ **Partial Implementation**

**What We Have:**
- ✅ **Correlation ID propagation** - Full implementation across all layers
- ✅ **Metrics collection** - Micrometer-based metrics with Prometheus export
- ✅ **Structured logging** - SLF4J with correlation IDs in logs
- ✅ **Documentation** - OpenTelemetry examples in guides

**What We're Missing:**
- ❌ **W3C Trace Context propagation** - Not implemented in REST layer
- ❌ **Distributed tracing instrumentation** - No OpenTelemetry/Jaeger integration
- ❌ **Span creation** - No automatic span propagation through layers
- ❌ **MDC (Mapped Diagnostic Context)** - No automatic correlation ID injection into logs

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

**Status:** Implemented with SLF4J/Logback

**Configuration:**
- Module-specific log levels (DEBUG for dev, INFO/WARN for prod)
- Structured log patterns with timestamps
- Component-specific loggers for fine-grained control

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

### 1. W3C Trace Context Propagation ❌

**What's Missing:**
- No extraction of `traceparent` header from HTTP requests
- No injection of `traceparent` into message headers
- No propagation of `tracestate` or `baggage` headers

**Where It Should Be Implemented:**

#### REST Layer (peegeeq-rest)
```java
// QueueHandler.java - MISSING
public void sendMessage(RoutingContext ctx) {
    // TODO: Extract W3C trace context from HTTP headers
    String traceparent = ctx.request().getHeader("traceparent");
    String tracestate = ctx.request().getHeader("tracestate");
    
    // TODO: Inject into message headers
    if (traceparent != null) {
        headers.put("traceparent", traceparent);
    }
    if (tracestate != null) {
        headers.put("tracestate", tracestate);
    }
}
```

#### Native Producer (peegeeq-native)
```java
// PgNativeQueueProducer.java - MISSING
// TODO: Store traceparent in headers JSONB for downstream propagation
```

### 2. Automatic Span Creation ❌

**What's Missing:**
- No automatic span creation at layer boundaries
- No span context propagation through CompletableFuture chains
- No span attributes for message metadata

**Where It Should Be Implemented:**

#### REST Handler
```java
// QueueHandler.java - MISSING
Span span = tracer.spanBuilder("queue.send")
    .setAttribute("queue.name", queueName)
    .setAttribute("setup.id", setupId)
    .setAttribute("message.correlation_id", correlationId)
    .startSpan();

try (Scope scope = span.makeCurrent()) {
    // Existing send logic
} finally {
    span.end();
}
```

#### Producer Layer
```java
// PgNativeQueueProducer.java - MISSING
Span span = tracer.spanBuilder("producer.send")
    .setAttribute("topic", topic)
    .setAttribute("message.group", messageGroup)
    .startSpan();
```

#### Database Layer
```java
// Pool operations - MISSING
Span span = tracer.spanBuilder("db.insert")
    .setAttribute("db.system", "postgresql")
    .setAttribute("db.operation", "INSERT")
    .setAttribute("db.table", "queue_messages")
    .startSpan();
```

### 3. MDC (Mapped Diagnostic Context) ❌

**What's Missing:**
- No automatic injection of correlation ID into MDC
- No trace ID/span ID in log entries
- No automatic MDC cleanup after request completion

**Where It Should Be Implemented:**

#### REST Layer
```java
// QueueHandler.java - MISSING
MDC.put("correlationId", correlationId);
MDC.put("traceId", span.getSpanContext().getTraceId());
MDC.put("spanId", span.getSpanContext().getSpanId());

try {
    // Existing logic
} finally {
    MDC.clear();
}
```

#### Logback Configuration
```xml
<!-- logback.xml - MISSING -->
<pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] [trace=%X{traceId} span=%X{spanId} correlation=%X{correlationId}] %-5level %logger{36} - %msg%n</pattern>
```

## Comparison: Current vs. Ideal State

### Current State: Correlation ID Only

```
┌─────────────────────────────────────────────────────────────┐
│  HTTP Request                                               │
│  Body: { "correlationId": "abc-123" }                       │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  QueueHandler                                               │
│  correlationId = "abc-123"                                  │
│  Log: "Sending message to queue orders"                     │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  PgNativeQueueProducer                                      │
│  SQL: INSERT ... correlation_id = 'abc-123'                 │
│  Log: "Message sent to topic orders"                        │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  PostgreSQL                                                 │
│  queue_messages.correlation_id = 'abc-123'                  │
└─────────────────────────────────────────────────────────────┘

Logs:
2025-12-24 09:00:00 [vert.x-eventloop-thread-0] INFO  QueueHandler - Sending message to queue orders
2025-12-24 09:00:00 [vert.x-eventloop-thread-0] DEBUG PgNativeQueueProducer - Message sent to topic orders
```

### Ideal State: Full Distributed Tracing

```
┌─────────────────────────────────────────────────────────────┐
│  HTTP Request                                               │
│  Headers:                                                   │
│    traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-...     │
│    tracestate: vendor1=value1,vendor2=value2                │
│  Body: { "correlationId": "abc-123" }                       │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  QueueHandler                                               │
│  Span: "queue.send" (parent: HTTP span)                     │
│    - queue.name = "orders"                                  │
│    - correlation.id = "abc-123"                             │
│  MDC: {traceId=4bf92f..., spanId=a3ce929..., correlationId=abc-123} │
│  Log: [trace=4bf92f span=a3ce92 correlation=abc-123] Sending message │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  PgNativeQueueProducer                                      │
│  Span: "producer.send" (parent: queue.send)                 │
│    - topic = "orders"                                       │
│    - message.group = "customer-456"                         │
│  Headers JSONB: { "traceparent": "00-4bf92f...", ... }      │
│  Log: [trace=4bf92f span=d0e0e47 correlation=abc-123] Inserting message │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  PostgreSQL                                                 │
│  Span: "db.insert" (parent: producer.send)                  │
│    - db.system = "postgresql"                               │
│    - db.table = "queue_messages"                            │
│  queue_messages.correlation_id = 'abc-123'                  │
│  queue_messages.headers = '{"traceparent": "00-4bf92f..."}'  │
└─────────────────────────────────────────────────────────────┘

Logs (with MDC):
2025-12-24 09:00:00 [vert.x-eventloop-thread-0] [trace=4bf92f3577b34da6a3ce929d0e0e4736 span=a3ce929d0e0e4736 correlation=abc-123] INFO  QueueHandler - Sending message to queue orders
2025-12-24 09:00:00 [vert.x-eventloop-thread-0] [trace=4bf92f3577b34da6a3ce929d0e0e4736 span=d0e0e4736a3ce929 correlation=abc-123] DEBUG PgNativeQueueProducer - Message sent to topic orders

Jaeger UI:
┌─────────────────────────────────────────────────────────────┐
│  Trace: 4bf92f3577b34da6a3ce929d0e0e4736                     │
│  ├─ HTTP POST /api/v1/queues/setup1/orders/messages (50ms)  │
│  │  ├─ queue.send (45ms)                                    │
│  │  │  ├─ producer.send (40ms)                              │
│  │  │  │  └─ db.insert (35ms)                               │
└─────────────────────────────────────────────────────────────┘
```

## Recommendations

### Priority 1: Add W3C Trace Context Propagation (High Impact, Low Effort)

**Effort:** 2-3 days
**Impact:** Enables integration with existing tracing infrastructure

**Tasks:**
1. Extract `traceparent`, `tracestate`, `baggage` from HTTP headers in `QueueHandler`
2. Inject trace headers into message headers map
3. Store trace headers in `queue_messages.headers` JSONB
4. Propagate trace headers to consumers
5. Add integration tests for trace context propagation

**Files to Modify:**
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueProducer.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`

### Priority 2: Add MDC Support (High Impact, Low Effort)

**Effort:** 1-2 days
**Impact:** Dramatically improves log correlation and debugging

**Tasks:**
1. Add MDC.put() calls in `QueueHandler` for correlationId
2. Add MDC.put() calls for traceId/spanId (if available from traceparent)
3. Update logback.xml patterns to include MDC fields
4. Ensure MDC cleanup in finally blocks
5. Add Vert.x context propagation for async operations

**Files to Modify:**
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java`
- `peegeeq-api/src/main/resources/logback.xml`
- `peegeeq-db/src/main/resources/logback.xml`
- `peegeeq-native/src/main/resources/logback.xml`
- `peegeeq-outbox/src/main/resources/logback.xml`
- `peegeeq-bitemporal/src/main/resources/logback.xml`

### Priority 3: Add OpenTelemetry Instrumentation (Medium Impact, High Effort)

**Effort:** 1-2 weeks
**Impact:** Full distributed tracing with Jaeger/Zipkin integration

**Tasks:**
1. Add OpenTelemetry dependencies (optional, not required by default)
2. Create `TracingProvider` interface in `peegeeq-api`
3. Implement `OpenTelemetryTracingProvider` in new `peegeeq-tracing` module
4. Add span creation at layer boundaries
5. Add automatic instrumentation for Vert.x HTTP and SQL operations
6. Add configuration for Jaeger/Zipkin exporters
7. Add comprehensive tracing examples

**New Module:**
- `peegeeq-tracing` (optional module for OpenTelemetry integration)

**Dependencies to Add:**
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

## Implementation Roadmap

### Phase 1: Foundation (Week 1)
- ✅ Correlation ID propagation (DONE)
- ✅ Metrics collection (DONE)
- ✅ Structured logging (DONE)
- ⏳ **W3C Trace Context propagation** (TODO)
- ⏳ **MDC support** (TODO)

### Phase 2: Enhanced Observability (Week 2-3)
- ⏳ OpenTelemetry API integration
- ⏳ Automatic span creation
- ⏳ Jaeger/Zipkin exporter configuration
- ⏳ Tracing examples and documentation

### Phase 3: Production Readiness (Week 4)
- ⏳ Performance testing with tracing enabled
- ⏳ Sampling configuration
- ⏳ Production deployment guide
- ⏳ Grafana dashboard templates

## Testing Strategy

### Unit Tests
- Test trace context extraction from HTTP headers
- Test trace context injection into message headers
- Test MDC cleanup in error scenarios
- Test span creation and attribute setting

### Integration Tests
- Test end-to-end trace propagation from REST to database
- Test trace context propagation through consumer processing
- Test correlation ID consistency across all layers
- Test MDC values in log output

### Performance Tests
- Measure overhead of tracing instrumentation
- Test with different sampling rates (1%, 10%, 100%)
- Verify no performance degradation with tracing disabled

## Conclusion

**Current State:**
- ✅ Strong foundation with correlation IDs and metrics
- ✅ Production-ready logging infrastructure
- ✅ Excellent documentation for OpenTelemetry integration

**Gaps:**
- ❌ No W3C Trace Context propagation
- ❌ No automatic distributed tracing
- ❌ No MDC support for enhanced log correlation

**Recommendation:**
Implement **Priority 1** (W3C Trace Context) and **Priority 2** (MDC) immediately for maximum impact with minimal effort. These changes will provide 80% of the observability benefits with only 20% of the effort required for full OpenTelemetry instrumentation.

**Priority 3** (OpenTelemetry) can be implemented later as an optional module for organizations that require full distributed tracing capabilities.
