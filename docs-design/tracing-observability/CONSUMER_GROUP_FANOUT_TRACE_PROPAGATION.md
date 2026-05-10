# Consumer Group Fan-Out Trace Propagation Design

**Status**: PROPOSAL  
**Author**: Mark Andrew Ray-Smith  
**Date**: 2026-04-05  
**Tracker Reference**: Task L6 in CONSUMER_GROUP_IMPLEMENTATION_TRACKER.md

---

## Problem Statement

When a message is published to a PUB_SUB topic with N subscriber consumer groups, the trace context must branch from a single publish operation into N parallel consumption paths. The current implementation has two behaviours:

1. **Publish side**: The original request's `traceparent` header is persisted in the message's `headers` JSONB column. All groups read the same header.

2. **Consumption side**: Each consumer group extracts the same `traceparent` from the message and re-applies it to MDC. Meanwhile, `ConsumerGroupFetcher.fetchMessages()` creates a **new root trace** via `TraceCtx.createNew()` disconnected from the publish trace.

This means:
- All N consumer groups log with the **same traceId** (from the published message), which is correct for correlation.
- The fetch-level operation creates a separate trace that is **not linked** to the publish trace.
- There is no structural parent-child relationship between the publish span and each group's processing span.
- Jaeger (or any OpenTelemetry-compatible tool) cannot reconstruct the fan-out tree.

---

## Current Trace Flow

```
HTTP Request (traceparent: 00-TRACE_A-SPAN_1-01)
    │
    ▼
QueueHandler.sendMessage()
    │  TraceCtx = parse(traceparent) → traceId=TRACE_A, spanId=SPAN_1
    │
    ▼
OutboxProducer.send()
    │  INSERT INTO outbox (headers: {"traceparent": "00-TRACE_A-SPAN_1-01"})
    │  required_consumer_groups = 3
    │
    ├───────────────────┬───────────────────┐
    ▼                   ▼                   ▼
Group A fetch       Group B fetch       Group C fetch
TraceCtx.createNew  TraceCtx.createNew  TraceCtx.createNew
traceId=NEW_A       traceId=NEW_B       traceId=NEW_C    ← disconnected
    │                   │                   │
    ▼                   ▼                   ▼
Extract traceparent from message headers
traceId=TRACE_A     traceId=TRACE_A     traceId=TRACE_A  ← re-applied via MDC
```

**Result**: The fetch span is disconnected. The processing span shares the same traceId but lacks parent-child linkage to the publish span.

---

## Proposed Design

### Option A: Child Spans per Consumer Group (Recommended)

Each consumer group's processing creates a **child span** of the original publish span. This produces a proper trace tree in Jaeger.

#### Trace Flow (Proposed)

```
HTTP Request (traceparent: 00-TRACE_A-SPAN_1-01)
    │
    ▼
QueueHandler.sendMessage()
    │  root span: traceId=TRACE_A, spanId=SPAN_1
    │
    ▼
OutboxProducer.send()
    │  child span: traceId=TRACE_A, spanId=SPAN_2, parent=SPAN_1
    │  INSERT INTO outbox (headers: {"traceparent": "00-TRACE_A-SPAN_2-01"})
    │
    ├───────────────────┬───────────────────┐
    ▼                   ▼                   ▼
Group A fetch       Group B fetch       Group C fetch
child span:         child span:         child span:
  traceId=TRACE_A     traceId=TRACE_A     traceId=TRACE_A
  spanId=SPAN_A1      spanId=SPAN_B1      spanId=SPAN_C1
  parent=SPAN_2       parent=SPAN_2       parent=SPAN_2
    │                   │                   │
    ▼                   ▼                   ▼
Group A process     Group B process     Group C process
child span:         child span:         child span:
  traceId=TRACE_A     traceId=TRACE_A     traceId=TRACE_A
  spanId=SPAN_A2      spanId=SPAN_B2      spanId=SPAN_C2
  parent=SPAN_A1      parent=SPAN_B1      parent=SPAN_C1
```

#### Jaeger Visualisation

```
TRACE_A ─┬─ QueueHandler.sendMessage [SPAN_1]
          │
          └─ OutboxProducer.send [SPAN_2]
              │
              ├─ consumer-group:A / fetch [SPAN_A1]
              │   └─ consumer-group:A / process [SPAN_A2]
              │
              ├─ consumer-group:B / fetch [SPAN_B1]
              │   └─ consumer-group:B / process [SPAN_B2]
              │
              └─ consumer-group:C / fetch [SPAN_C1]
                  └─ consumer-group:C / process [SPAN_C2]
```

#### Implementation Changes

1. **ConsumerGroupFetcher.fetchMessages()** Replace `TraceCtx.createNew()` with child span derivation:

   ```java
   // Before:
   TraceCtx trace = TraceCtx.createNew();

   // After:
   // Extract parent trace from the first message's headers
   // Create child span preserving the traceId
   TraceCtx parentTrace = extractTraceFromMessage(message);
   TraceCtx fetchSpan = parentTrace.childSpan("consumer-group:" + groupName + "/fetch");
   ```

   **Challenge**: The fetch happens *before* messages are retrieved. The parent traceparent is inside the message. Two approaches:
   - **Pre-query**: Look up the traceparent from the first message before the main fetch query (extra DB round-trip).
   - **Post-query**: Create the child span after messages are returned, retroactively apply the trace to the fetch log context.
   - **Deferred**: Use a root span for fetch, then link it to the message trace during processing.

2. **OutboxConsumer.processRow()** Create child span instead of reusing parent span:

   ```java
   // Before:
   TraceCtx traceCtx = TraceContextUtil.parseOrCreate(traceparent);

   // After:
   TraceCtx publishTrace = TraceContextUtil.parseOrCreate(traceparent);
   TraceCtx processSpan = publishTrace.childSpan("consumer-group:" + groupName + "/process");
   ```

3. **Span naming convention**:
   - Fetch: `consumer-group:{groupName}/fetch`
   - Process: `consumer-group:{groupName}/process`
   - Mark complete: `consumer-group:{groupName}/complete`

### Option B: Linked Spans (Separate Traces with Links)

Each group creates an independent trace but **links** it to the publish trace via OpenTelemetry span links.

```java
// OpenTelemetry span link
SpanContext publishContext = SpanContext.createFromRemoteParent(
    traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
Span consumerSpan = tracer.spanBuilder("consumer-group:" + groupName)
    .addLink(publishContext)
    .startSpan();
```

**Pros**: Each consumer group has its own trace tree. Useful if consumer processing is very long-lived or involves its own sub-traces.
**Cons**: Requires OpenTelemetry SDK dependency. More complex to correlate in Jaeger (links are less visible than parent-child). Current PeeGeeQ tracing is custom W3C, not OTel SDK.

### Option C: Status Quo (Same traceId, No Structure)

Keep the current behaviour where all groups share the same traceId from the message. Correlation works via `grep traceId` but no structural tree.

**Pros**: Zero implementation cost.
**Cons**: Cannot tell which consumer group processed first, or trace the fan-out shape in Jaeger.

---

## Recommendation

**Option A (Child Spans)** with the **post-query** approach:

1. `ConsumerGroupFetcher.fetchMessages()` creates a root span for the fetch operation itself (as now).
2. When messages are returned, extract the traceparent from each message.
3. `OutboxConsumer.processRow()` creates a **child span** of the message's traceparent, with the group name in the span name.
4. The child span's spanId becomes the new traceparent for all downstream operations within that group's processing.

This gives:
- Correct tree structure in Jaeger for the fan-out
- No extra DB round-trips (child span created after message arrives)
- Each group's processing appears as a distinct branch under the publish span
- The fetch span remains independent (operational/infrastructure span) rather than forced into the message's trace tree

### Implementation Scope

| Change | File | Effort |
|--------|------|--------|
| Create child span in consumer processing | `OutboxConsumer.processRow()` | Small |
| Add group name to span name | `OutboxConsumer.processRow()` | Small |
| Update CompletionTracker with child span | `CompletionTracker.markCompleted()` | Small |
| Add span name support to `TraceCtx.childSpan()` | `TraceCtx.java` | Small may already exist |
| Update tests to verify parent-child linkage | `ConsumerTracingTest`, `DistributedTracingTest` | Medium |
| Document updated trace format in user guide | `PEEGEEQ_TRACING_USER_GUIDE.md` | Small |

### Open Considerations

1. **Batch fetch**: When `fetchMessages()` returns N messages, each message may have a different traceparent (from different publish requests). The child span must be per-message, not per-batch.

2. **Backfill**: Backfilled messages may have stale or missing traceparents. The `parseOrCreate` pattern handles this correctly (creates new trace if header is absent).

3. **Dead-letter / retry**: When a message is retried, should it create a new child span or reuse the previous one? A new child span is correct each attempt is a separate unit of work.

4. **Metrics**: The span name should include the group name so Prometheus/Jaeger can aggregate latency per consumer group: `consumer-group:payments-processor/process`.

5. **Configuration**: Consider a config flag to control child span creation for environments where trace volume is a concern. Default: enabled.

---

## References

- [PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md](../../docs/PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md) W3C trace format, core components
- [PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md](PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md) Vert.x Context source-of-truth principle
- [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md](../consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md) Reference counting, subscription lifecycle
- [W3C Trace Context Specification](https://www.w3.org/TR/trace-context/) traceparent format
