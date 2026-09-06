# PeeGeeQ Complete Guide

**Status:** CURRENT DOCUMENTATION PORTAL

**Updated:** 2026-09-06

## Purpose

PeeGeeQ documentation is maintained as focused guides rather than one duplicated monolith. This
page is the stable compatibility entry point for readers who previously used the complete guide.
It identifies the authoritative document for each subject and the order in which a new reader
should approach the system.

The former monolithic version accumulated duplicated APIs and obsolete blocking examples. Its
historical text remains permanently available in Git history. Design investigations, completed
work, proposals, and historical evidence remain discoverable through the
[documentation source catalogue](PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md); no design source was
discarded during consolidation.

## Recommended Reading Path

1. [Getting Started](PEEGEEQ_GETTING_STARTED.md) — choose modules and establish a working setup.
2. [Design Fundamentals](PEEGEEQ_DESIGN_FUNDAMENTALS.md) — understand PostgreSQL queue mechanics,
   transactions, leasing, recovery, and workload boundaries.
3. [Messaging Features](PEEGEEQ_MESSAGING_FEATURES_GUIDE.md) — producers, consumers, fan-out,
   filtering, CloudEvents, and recovery behavior.
4. [Ordering Patterns](PEEGEEQ_ORDERING_PATTERNS_GUIDE.md) — ordering keys, partitions, consumer
   concurrency, and watermark behavior.
5. [Event Store](PEEGEEQ_EVENT_STORE_GUIDE.md) — bi-temporal events, identity, causality,
   subscriptions, catch-up, and pagination.
6. [Operations](PEEGEEQ_OPERATIONS_GUIDE.md) — lifecycle, shutdown, failover, monitoring, and
   performance evidence.
7. [Testing](PEEGEEQ_TESTING_GUIDE.md) — test profiles, real PostgreSQL boundaries, asynchronous
   completion, and release gates.
8. [Contributor Guide](PEEGEEQ_CONTRIBUTOR_GUIDE.md) — development environment and CI workflow.

## API and Integration References

- [REST API Reference](PEEGEEQ_REST_API_REFERENCE.md)
- [Database Setup Guide](PEEGEEQ_DATABASE_SETUP_GUIDE.md)
- [Consumer Group Getting Started](PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md)
- [Transactional Outbox Patterns](PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md)
- [Tracing User Guide](PEEGEEQ_TRACING_USER_GUIDE.md)
- [Tracing Technical Reference](PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md)
- [Service Manager Guide](PEEGEEQ_SERVICE_MANAGER_GUIDE.md)
- [Examples Guide](PEEGEEQ_EXAMPLES_GUIDE.md)

## Asynchronous Programming Contract

PeeGeeQ exposes `io.vertx.core.Future` for asynchronous work. A caller must do one of three things
with every returned value:

- return it to its caller;
- compose it into a larger operation; or
- install terminal success and failure handling.

### Return the operation

```java
Future<Void> publishOrder(MessageProducer<Order> producer, Order order) {
    return producer.send(order, Map.of(), order.correlationId());
}
```

### Compose dependent operations

```java
Future<String> createAndPublish(OrderRepository repository,
                                MessageProducer<OrderCreated> producer,
                                Order order) {
    return repository.insert(order)
        .compose(orderId -> producer.send(
            new OrderCreated(orderId),
            Map.of("event-type", "OrderCreated"),
            order.correlationId())
            .map(ignored -> orderId));
}
```

### Observe a terminal operation

```java
createAndPublish(repository, producer, order)
    .onSuccess(orderId -> logger.info("Created order {}", orderId))
    .onFailure(error -> logger.error("Order creation failed", error));
```

Failures remain failures unless the business contract explicitly defines a successful alternative.
Cleanup and observability callbacks must not conceal the original failure.

## Transaction Boundaries

When domain data and an outgoing event must commit atomically, the caller owns the transaction and
passes the same `SqlConnection` to each participating operation:

```java
Future<String> transactOrder(ConnectionProvider connections,
                             OrderRepository repository,
                             OutboxProducer<OrderCreated> producer,
                             Order order) {
    return connections.withTransaction("orders", connection ->
        repository.insert(connection, order)
            .compose(orderId -> producer.sendInExistingTransaction(
                new OrderCreated(orderId), connection)
                .map(ignored -> orderId))
    );
}
```

See the transactional-outbox and event-store guides before combining additional operations in the
same transaction.

## Testing Contract

Tests use real implementations at infrastructure boundaries and complete through observable
asynchronous outcomes:

```java
@Test
void publishesOrder(VertxTestContext testContext) {
    producer.send(order)
        .onComplete(testContext.succeedingThenComplete());
}
```

Integration tests use the repository’s PostgreSQL test infrastructure and the Maven profile that
owns the test tag. The full release suite is a deliberate release gate, not the normal edit-test
loop.

## Documentation Authority

Use the following precedence when documents disagree:

1. Current public guides under `docs/`.
2. Current architecture and testing standards under `docs-design/`.
3. Explicitly proposed designs.
4. Archived investigations and completion reports.

An archived or proposed document is evidence and context, not a current runtime contract. Runtime
claims still require runtime verification.

## Complete Source Inventory

The [documentation source catalogue](PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md) lists every current
Markdown source under `docs-design`, including proposals and historical records. The
[consolidation ledger](../docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md) records
heading-level disposition and source fingerprints for the consolidation baseline.
