# PeeGeeQ Messaging Features Guide

**Status:** CURRENT CATEGORY GUIDE

This guide connects PeeGeeQ's cross-cutting messaging features without duplicating the detailed
outbox, consumer-group, ordering, and event-store guides.

## Choose the delivery model first

| Need | Maintained guide |
|---|---|
| Atomic publication with a business transaction | [Transactional Outbox Patterns](PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md) |
| Competing consumers or service fan-out | [Consumer Groups](PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md) |
| Ordered processing within a business key | [Ordering Patterns](PEEGEEQ_ORDERING_PATTERNS_GUIDE.md) |
| Temporal event history and subscriptions | [Event Store](PEEGEEQ_EVENT_STORE_GUIDE.md) |

At-least-once delivery applies across the messaging boundaries. Ordering, duplicate resistance,
and exactly-once application effects are separate concerns.

## Consumer-group fan-out

Consumer groups provide queue-style work sharing and independent group delivery. Completion
tracking, subscription lifecycle, backfill, retry, dead-letter behavior, cleanup, and zero-group
protection are parts of the wider contract.

Detailed sources retained during consolidation:

- [Consumer Groups Guide](PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md)
- [Fan-out design and implementation record](../docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md)
- [Partitioned-consumption record](../docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md)

## Server-side filtering

Filtering changes which messages a subscription is eligible to process. Filter syntax,
serialization, database evaluation, indexes, error behavior, retry interaction, and tenant
qualification must be treated as one contract.

The complete retained source is the
[Server-Side Filtering Guide](../docs-design/event-sourcing-messaging/PEEGEEQ_SERVER_SIDE_FILTERING_GUIDE.md).
Its implementation and performance claims must be validated before being repeated as current
release evidence.

## CloudEvents

CloudEvents integration standardizes event metadata and interoperability. Applications still need
to choose the PeeGeeQ storage and delivery model independently of the envelope format.

The complete usage, Spring integration, testing, troubleshooting, and compatibility discussion is
retained in the
[CloudEvents Support and Integration Guide](../docs-design/event-sourcing-messaging/PEEGEEQ_CLOUDEVENTS_SUPPORT_AND_INTEGRATION_GUIDE.md).

## Correlation and causation

- Correlation identifies a wider business or request flow.
- Causation identifies the direct predecessor that caused a message or event.
- Trace context describes distributed execution and is not a substitute for domain causality.

See the [Event Store Guide](PEEGEEQ_EVENT_STORE_GUIDE.md) for event causality and the
[Tracing Technical Reference](PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md) for execution tracing.

## Recovery and failure handling

Recovery guidance must distinguish message visibility, processing leases, retry state, dead-letter
state, committed partition cursors, and application side effects. A recovered queue record does not
prove that an external side effect was rolled back.

Detailed recovery analysis remains available through the
[Documentation Source Catalogue](PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md).

## Documentation boundary

Completed implementation plans and defect investigations remain historical evidence. They do not
become new product requirements merely because they are linked from this guide.
