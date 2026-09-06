# PeeGeeQ Event Store Guide

**Status:** CURRENT CATEGORY GUIDE — IMPLEMENTED AND PROPOSED MATERIAL SEPARATED

This is the maintained destination for bi-temporal event-store usage, causality, subscriptions,
durable replay, and stable pagination. Linked source guides retain their full detail and provenance.

## Event identity and time

A bi-temporal event records business-valid time separately from transaction time. Event identifiers
provide a stable cursor but sequence allocation alone must not be described as transaction commit
order. Query and replay contracts must state their ordering and boundary explicitly.

## Correlation and causation

Correlation groups work belonging to a wider flow. Causation points to the direct event or command
that caused another event. Preserve both across append, query, messaging, and REST boundaries when
the domain requires an auditable chain.

The complete API examples, REST usage, query patterns, and causation-chain guidance remain in the
[Event Causality Guide](../docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_EVENT_CAUSALITY_GUIDE.md).

## Subscription models

PeeGeeQ documents two different subscription lifecycles:

- non-durable notification subscriptions keep handlers in process and do not independently provide
  restart replay; and
- the implemented typed durable bi-temporal service persists definitions and cursors, then delivers
  when an application registers a compatible handler.

Durable delivery remains at least once. Successful handler completion permits cursor advancement;
handlers must tolerate replay. Loading a stored definition does not recreate application code or a
deserialization type that was not registered after restart.

The durable-subscription plan also contains broader outbox and operations proposals. Those sections
remain design material and are not promoted as implemented behavior.

## Catch-up and live delivery

The durable design records a finite committed replay boundary and a notification-assisted live
path. Notifications are hints to reconcile committed history rather than the durable data source.
Lease ownership and generation fencing protect cursor acknowledgement between competing service
instances. Exact cursor, lease, timeout, and lifecycle details remain in the retained durable record.

## Stable pagination

Keyset pagination anchors navigation on the last observed event instead of a shifting row offset.
It prevents page overlap or omission caused by concurrent appends, at the cost of sequential rather
than arbitrary page-number navigation.

The [Keyset Pagination Guide](PEEGEEQ_KEYSET_PAGINATION_GUIDE.md) remains the detailed API and UI
reference until its examples are fully integrated here.

## Current detailed sources

- [Event causality](../docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_EVENT_CAUSALITY_GUIDE.md)
- [Bi-temporal subscriptions](../docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_SUBSCRIPTIONS_GUIDE.md)
- [Durable subscription design and implementation record](../docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md)
- [Keyset pagination](PEEGEEQ_KEYSET_PAGINATION_GUIDE.md)
- [REST event-store endpoints](PEEGEEQ_REST_API_REFERENCE.md#event-store-endpoints)

## Verification and historical detail

Focused test names and counts in the durable design are evidence for their recorded revision only.
Current acceptance belongs in the consolidated task register. No source listed here is removed
merely because this destination exists.
