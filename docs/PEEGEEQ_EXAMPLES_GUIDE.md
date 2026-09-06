# PeeGeeQ Examples Guide

**Status:** CURRENT

**Updated:** 2026-09-06

## Purpose

This guide provides small, composable examples for the current PeeGeeQ asynchronous API. Each
example either returns its `Future`, composes it into a larger operation, or observes both terminal
outcomes. The examples intentionally omit application-specific bootstrap code.

For complete contracts and operational constraints, use the focused guides linked from the
[documentation index](README.md). Historical example material remains available through Git history
and the [documentation source catalogue](PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md).

## Send a Message

```java
Future<Void> sendOrder(MessageProducer<Order> producer, Order order) {
    Map<String, String> headers = Map.of(
        "traceparent", traceContext.traceparent(),
        "content-type", "application/json");

    return producer.send(order, headers, order.correlationId())
        .onFailure(error -> logger.error("Order send failed: {}", order.id(), error));
}
```

The caller decides whether to compose another operation or install terminal callbacks:

```java
sendOrder(producer, order)
    .onSuccess(ignored -> logger.info("Order accepted: {}", order.id()))
    .onFailure(error -> requestContext.fail(500, error));
```

## Compose Business Persistence and Outbox Publication

Use one caller-owned transaction when the domain row and outgoing message must commit together:

```java
Future<String> createOrder(
        ConnectionProvider connections,
        OutboxProducer<OrderCreated> producer,
        Order order) {

    return connections.withTransaction("orders", connection ->
        insertOrder(connection, order)
            .compose(orderId -> producer.sendInExistingTransaction(
                new OrderCreated(orderId),
                Map.of("event-type", "OrderCreated"),
                order.correlationId(),
                orderId,
                connection)
                .map(ignored -> orderId))
    );
}
```

A failure from either operation fails the returned chain and causes the transaction owner to roll
back.

## Append a Causal Event Chain

```java
Future<BiTemporalEvent<PaymentProcessed>> appendOrderWorkflow(
        EventStore<Object> eventStore,
        OrderCreated order,
        PaymentProcessed payment,
        Instant validTime) {

    String correlationId = order.correlationId();

    return eventStore.append(
            "OrderCreated",
            order,
            validTime,
            Map.of("source", "orders-api"),
            correlationId,
            null,
            order.orderId())
        .compose(orderEvent -> eventStore.append(
            "PaymentProcessed",
            payment,
            validTime,
            Map.of("source", "payments"),
            correlationId,
            orderEvent.getEventId(),
            order.orderId()));
}
```

The parent event identifier is only available after the first append completes, so causality is
expressed naturally through `.compose(...)`.

## Run Independent Operations Concurrently

```java
List<Future<Void>> sends = orders.stream()
    .map(order -> producer.send(order, Map.of(), order.correlationId()))
    .toList();

Future<Void> batch = Future.all(sends).mapEmpty();

batch
    .onSuccess(ignored -> logger.info("Sent {} orders", sends.size()))
    .onFailure(error -> logger.error("Order batch failed", error));
```

`Future.all(...)` fails when any member fails. Use a different aggregation policy only when partial
success is an explicit business requirement.

## Consumer Handler

```java
Future<Void> handleOrder(Message<Order> message) {
    Order order = message.getPayload();

    return validate(order)
        .compose(ignored -> persistProjection(order))
        .compose(ignored -> publishAuditEvent(order))
        .onFailure(error -> logger.error(
            "Order processing failed: {}", order.id(), error));
}
```

Return the complete chain to the consumer. Acknowledgement must occur only after the returned value
completes successfully.

## Non-Blocking Retry Delay

```java
Future<Void> delay(Vertx vertx, long delayMillis) {
    Promise<Void> promise = Promise.promise();
    vertx.setTimer(delayMillis, ignored -> promise.complete());
    return promise.future();
}

Future<Void> sendWithRetry(Order order, int attempt) {
    return producer.send(order)
        .compose(
            ignored -> Future.succeededFuture(),
            error -> {
                if (!isTransient(error) || attempt >= maxAttempts) {
                    return Future.failedFuture(error);
                }
                long delayMillis = retryDelayMillis(attempt);
                return delay(vertx, delayMillis)
                    .compose(ignored -> sendWithRetry(order, attempt + 1));
            });
}
```

The delay uses a Vert.x timer and keeps the event-loop thread available.

## Asynchronous Test

```java
@Test
void sendsAndConsumesOrder(VertxTestContext testContext) {
    Order order = new Order("order-123", "corr-123");

    consumer.subscribe(message -> {
        testContext.verify(() -> assertEquals(order.id(), message.getPayload().id()));
        return Future.succeededFuture();
    })
        .compose(ignored -> producer.send(order))
        .onComplete(testContext.succeedingThenComplete());
}
```

For multi-step integration tests, return one composed chain to the final
`succeedingThenComplete()` callback. The detailed rules are in the
[testing guide](PEEGEEQ_TESTING_GUIDE.md).

## HTTP Handler

```java
void createOrder(RoutingContext context) {
    Order order = context.body().asPojo(Order.class);

    orderService.createOrder(order)
        .onSuccess(orderId -> context.response()
            .setStatusCode(202)
            .end(new JsonObject().put("orderId", orderId).encode()))
        .onFailure(error -> {
            logger.error("Create-order request failed", error);
            context.fail(500, error);
        });
}
```

The HTTP response is written from the asynchronous outcome; the event-loop thread is never held
waiting for the service.

## Related Guides

- [Getting Started](PEEGEEQ_GETTING_STARTED.md)
- [Messaging Features](PEEGEEQ_MESSAGING_FEATURES_GUIDE.md)
- [Event Store](PEEGEEQ_EVENT_STORE_GUIDE.md)
- [Ordering Patterns](PEEGEEQ_ORDERING_PATTERNS_GUIDE.md)
- [Transactional Outbox Patterns](PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md)
- [Testing](PEEGEEQ_TESTING_GUIDE.md)
