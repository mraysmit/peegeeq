# Consumer Group Fan-Out Trace Propagation

**Status:** IMPLEMENTED — former proposal reconciled to current behavior
**Originally proposed:** 2026-04-05
**Implemented:** 2026-04-11
**Reconciled:** 2026-09-06 against `7db748b8`

The former version of this document described fan-out trace branching as future work. The
implementation is now part of the consumer-group runtime. This document records the resulting
contract; it is not a live implementation plan.

Current tasks and verification authority remain in the
[consolidated task register](../tasks/tasks.md).

## 1. Problem Addressed

A message published once can be processed by several consumer groups. Copying the same W3C
`traceparent` value into every group gives correlation, but it does not distinguish the parallel
processing branches.

PeeGeeQ therefore derives a child trace context for each consumer-group processing path. The
branches preserve the publication trace identifier while using distinct span identifiers and a
parent relationship to the persisted message context.

## 2. Implemented Trace Shape

```text
publish trace
└── persisted message context
    ├── consumer-group A processing child
    ├── consumer-group B processing child
    └── consumer-group C processing child
```

For a single published message:

- each group remains correlated through the same trace identifier;
- each group receives a distinct child span identifier;
- the stored publication span is the parent of each processing branch;
- downstream work inherits the group-specific child context;
- retries represent separate processing attempts rather than reusing one attempt span.

If a message has no valid persisted trace context, the normal trace parsing path creates a new
context rather than failing message delivery.

## 3. Implementation Boundaries

The implemented changes are distributed across the existing tracing and consumer-group layers:

- `TraceCtx` provides child-context derivation.
- `OutboxConsumer` creates a consumer-group processing child from the message headers.
- `PgNativeConsumerGroup` creates a distinct group processing context during distribution.
- `ConsumerGroupFetcher` accepts parent trace context for traced fetch operations.
- `CompletionTracker` accepts parent context for completion and failure tracking.
- MDC scope setup and cleanup keep the active group context bounded to the corresponding work.

Fetch operations that occur before a message is available may retain an infrastructure-level
trace. Per-message processing is the boundary at which the persisted publication parent can be
applied without an additional database lookup.

## 4. Required Invariants

1. One group must never reuse another group's child span identifier.
2. Creating a processing branch must not replace the persisted publication trace identifier.
3. Group name, topic, and message identity must remain available to the tracing/logging boundary.
4. A batch containing messages from different publications must create context per message, not
   one context for the entire batch.
5. Missing or malformed trace headers must not break delivery.
6. Trace state must be cleared when the processing scope settles so it cannot leak into unrelated
   event-loop work.

## 5. Verification Record

The implementation record in the partitioned-consumption design identifies coverage in
`ConsumerTracingTest`, `DistributedTracingTest`, and `TraceCtxTest`. Those recorded results are
historical evidence for their stated revision; this document does not claim a new execution at
`7db748b8`.

Future tracing changes require focused tests that prove parent linkage, unique group branches,
missing-header behavior, MDC cleanup, and failure-path propagation.

## 6. References

- [Consolidated task register](../tasks/tasks.md)
- [Partitioned consumption design](../consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md)
- [Consumer-group fan-out design](../consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md)
- [Tracing architecture guide](PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md)
- [Tracing technical reference](../../docs/PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
