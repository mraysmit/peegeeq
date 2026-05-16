# PeeGeeQ Examples — Business Scenarios Overview

This document catalogues the example scenarios in `peegeeq-examples` from a **business perspective**. Each scenario describes the real-world problem it addresses, the PeeGeeQ features it exercises, and the primary classes/tests involved.

The examples are grouped into four broad categories:

1. **Financial Services (Domain-Specific)** — Funds, custody, investment banking, event-sourced banking.
2. **Core PeeGeeQ Messaging Patterns** — Outbox, consumer groups, throughput, native queue.
3. **Integration & Architecture Patterns** — Microservices, EIP, resilience, CloudEvents, SSE, tracing.
4. **Platform & Infrastructure** — Configuration, service discovery, REST API, database setup.

Each scenario ends with a **→ Code example** link to a short distilled snippet in [Appendix A](#appendix-a--code-snippets).

---

## 1. Financial Services (Domain-Specific)

### 1.1 Funds & Custody — Investment Fund Back-Office

**Business problem.** An investment fund administrator must record trades, calculate positions and NAV (Net Asset Value), handle trade cancellations and corrections, and produce regulatory reports (AIFMD, MiFID II). Auditors and regulators routinely ask "what did you know on date X, as of report date Y?" — requiring full bi-temporal reconstruction of historical state.

**Key capabilities demonstrated.**
- Recording trade executions and cancellations as immutable events.
- Computing net positions per security with BUY/SELL netting and weighted-average price.
- NAV calculation and **as-reported vs. corrected** reconstruction.
- Investor compensation calculation when a NAV correction is issued retrospectively.
- Audit trail and change detection between two transaction-time snapshots.
- AIFMD / MiFID II regulatory snapshots — state as it was reported on a specific filing date.

**PeeGeeQ features.** Bi-temporal event store (`BiTemporalEventStoreFactory`, `EventStore`) — valid time = trade/business date, transaction time = system recording time.

**Primary classes.**
- Main: `TradeService`, `PositionService`, `NAVService`, `TradeAuditService`, `RegulatoryReportingService` (under [fundscustody/](../src/main/java/dev/mars/peegeeq/examples/fundscustody/))
- Tests: [TradeServiceTest.java](../src/test/java/dev/mars/peegeeq/examples/fundscustody/TradeServiceTest.java), [PositionServiceTest.java](../src/test/java/dev/mars/peegeeq/examples/fundscustody/PositionServiceTest.java), [NAVServiceTest.java](../src/test/java/dev/mars/peegeeq/examples/fundscustody/NAVServiceTest.java), [TradeAuditServiceTest.java](../src/test/java/dev/mars/peegeeq/examples/fundscustody/TradeAuditServiceTest.java), [RegulatoryReportingServiceTest.java](../src/test/java/dev/mars/peegeeq/examples/fundscustody/RegulatoryReportingServiceTest.java)

→ **Code example:** [§A.1](#a1-funds--custody)

---

### 1.2 Investment Banking Trade Processing (Advanced Bi-Temporal)

**Business problem.** A full sell-side trade pipeline — capture → enrichment → validation → settlement — that must comply with MiFID II T+1 correction rules, Dodd-Frank record-keeping, and Basel III operational risk requirements. Settlement failures, retries, and real-time position/risk monitoring must all reconcile to a single audit-grade history.

**Key capabilities demonstrated.**
- Full trade lifecycle modelled as bi-temporal events.
- MiFID II T+1 correction with both the original record and the correction preserved.
- Dodd-Frank record-keeping queries.
- Position reconciliation across the trade lifecycle.
- Real-time risk monitoring derived from the event stream.
- Advanced JSONB queries on CloudEvent metadata (correlation, causation) combined with bi-temporal dimensions.

**PeeGeeQ features.** Bi-temporal event store, CloudEvents integration, PostgreSQL JSONB query patterns.

**Primary tests.**
- [BiTemporalEventStoreExampleTest.java](../src/test/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalEventStoreExampleTest.java)
- [CloudEventsJsonbQueryTest.java](../src/test/java/dev/mars/peegeeq/examples/bitemporal/CloudEventsJsonbQueryTest.java)

> **See also:** For a multi-domain Spring-Boot financial event fabric with five separate event stores (trading / settlement / cash / position / regulatory) and `{entity}.{action}.{state}` event naming, see [FINANCIAL_FABRIC_GUIDE.md](./FINANCIAL_FABRIC_GUIDE.md) (lives in the sibling `peegeeq-examples-spring` module).

→ **Code example:** [§A.2](#a2-advanced-bi-temporal-trade-pipeline)

---

### 1.3 Bank Account Event Sourcing / CQRS

**Business problem.** A retail bank account where the balance is not stored directly but **derived** by replaying deposit and withdrawal events. The read side must serve balance queries quickly without re-reading the entire history every time.

**Key capabilities demonstrated.**
- The outbox table used as an immutable event store.
- CQRS read-model projection (balance computed from events).
- Snapshot optimisation to avoid full replay on every read.

**PeeGeeQ features.** Outbox factory as event store, Vert.x async test patterns (`VertxTestContext`, `Checkpoint`).

**Primary test.** [EventSourcingCQRSDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/EventSourcingCQRSDemoTest.java)

→ **Code example:** [§A.3](#a3-event-sourcing--cqrs)

---

## 2. Core PeeGeeQ Messaging Patterns

### 2.1 Transactional Outbox Pattern

**Business problem.** When a service writes to its database AND publishes a message, the two operations must be **atomic**. Failure modes such as "DB committed but broker didn't receive the message" or vice versa must be impossible.

**Key capabilities demonstrated.**
- Messages only become visible to consumers after the producing transaction commits.
- Rollback isolation — messages from a rolled-back transaction are never delivered.
- Concurrent transaction isolation guarantees.
- QUEUE vs. PUB-SUB semantics, with configurable behaviour when there are zero subscribers (block writes, retention).
- Server-side filtering on JSONB headers at the database level (no client-side filtering overhead).

**Primary tests.**
- [TransactionalOutboxAnalysisTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/TransactionalOutboxAnalysisTest.java)
- [ZeroSubscriptionProtectionDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/ZeroSubscriptionProtectionDemoTest.java)
- [OutboxServerSideFilteringTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/OutboxServerSideFilteringTest.java)

→ **Code example:** [§A.4](#a4-transactional-outbox) and [§A.5](#a5-outbox-server-side-filtering)

---

### 2.2 Consumer Groups — Load Balancing, Resilience & Late Joiners

**Business problem.** A high-volume queue (order events, payment events, notifications) must be processed by multiple competing consumers for throughput, must survive consumer crashes without losing messages, and must allow new consumers to join later — sometimes needing historical backfill.

**Key capabilities demonstrated.**
- Competing-consumer load balancing: round-robin, weighted, sticky-session, dynamic, failover.
- Header-based routing to specialised consumers.
- Dead consumer detection via heartbeat, with automatic reactivation on recovery.
- Late-joining consumers with `StartPosition.FROM_NOW`, `FROM_BEGINNING`, or `FROM_TIMESTAMP`.
- Partitioned ordering — in-order delivery within a partition while parallelising across partitions.
- Failure and recovery scenarios with message redelivery.

**Primary tests.**
- [AdvancedProducerConsumerGroupTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java)
- [ConsumerGroupResilienceTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/ConsumerGroupResilienceTest.java)
- [DeadConsumerDetectionDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/DeadConsumerDetectionDemoTest.java)
- [LateJoiningConsumerDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/LateJoiningConsumerDemoTest.java)
- [PartitionedOrderingDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/PartitionedOrderingDemoTest.java)
- [ConsumerGroupLoadBalancingDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/ConsumerGroupLoadBalancingDemoTest.java)

→ **Code example:** [§A.6](#a6-consumer-groups), [§A.7](#a7-late-joining-consumers), [§A.8](#a8-dead-consumer-detection)

---

### 2.3 High-Frequency / Performance

**Business problem.** Pipelines such as payments processing or market-data distribution require measured guarantees on throughput and latency.

**Key capabilities demonstrated.**
- Comparative measurement of native vs. outbox factories under load.
- Sustained throughput and end-to-end latency observation.

**Primary test.** [HighFrequencyProducerConsumerTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/HighFrequencyProducerConsumerTest.java)

→ **Code example:** [§A.9](#a9-native-queue) (same producer/consumer API; this scenario differs in volume and measurement, not in API shape).

---

### 2.4 Native PostgreSQL Queue (LISTEN/NOTIFY)

**Business problem.** Some scenarios require the lowest possible latency from producer to consumer and don't need the strong transactional guarantees of the outbox. The native queue uses PostgreSQL `LISTEN/NOTIFY` and advisory locks to deliver this without a separate broker.

**Key capabilities demonstrated.**
- LISTEN/NOTIFY-based real-time push delivery.
- Advisory locks for competing-consumer semantics.
- Native consumer groups and resource management.
- Server-side JSONB header filtering.

**Primary tests.**
- [SimpleNativeQueueTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/SimpleNativeQueueTest.java)
- [NativeQueueFeatureTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/NativeQueueFeatureTest.java)
- [ServerSideFilteringTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/ServerSideFilteringTest.java)

→ **Code example:** [§A.9](#a9-native-queue) and [§A.10](#a10-native-server-side-filtering)

---

## 3. Integration & Architecture Patterns

### 3.1 Microservices Communication

**Business problem.** In a microservices mesh, services must communicate reliably — request-response for synchronous workflows, pub-sub for fan-out, and orchestrated or choreographed sagas for multi-step business processes.

**Key capabilities demonstrated.**
- Request-response over async messaging.
- Pub-sub fan-out.
- Service orchestration vs. choreography.

**Primary test.** [MicroservicesCommunicationDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/MicroservicesCommunicationDemoTest.java)

→ **Code example:** [§A.11](#a11-microservices-requestresponse)

---

### 3.2 Enterprise Integration Patterns

**Business problem.** Cross-system integration where external messages (e.g. FIX trade confirmations) must be transformed to internal formats, routed by content, aggregated across responses, or coordinated as a distributed saga across multiple back-end services.

**Key capabilities demonstrated.**
- Message transformation (e.g. FIX → internal).
- Content-based routing.
- Aggregation and scatter-gather.
- Distributed saga pattern.

**Primary test.** [EnterpriseIntegrationDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/EnterpriseIntegrationDemoTest.java)

→ **Code example:** [§A.12](#a12-eip--transform--route)

---

### 3.3 Resilience & Error Handling

**Business problem.** Critical pipelines (payments, risk calculations) must handle transient failures, bad data, and downstream outages gracefully — without losing messages, without crashing, and without overwhelming a degraded downstream system.

**Key capabilities demonstrated.**
- Retry with exponential backoff.
- Dead-letter queue (DLQ) for poison messages.
- Circuit breaker, bulkhead, timeout, fallback / graceful degradation.
- Error classification (transient vs. invalid data) and poison-message detection.

**Primary tests.**
- [EnhancedErrorHandlingDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/EnhancedErrorHandlingDemoTest.java)
- [DistributedSystemResilienceDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/DistributedSystemResilienceDemoTest.java)

→ **Code example:** [§A.13](#a13-resilience--dlq--retry)

---

### 3.4 CloudEvents Integration

**Business problem.** Event-driven integration across heterogeneous systems benefits from a standard envelope. The CNCF CloudEvents specification provides one — including correlation and causation tracking for end-to-end traceability.

**Key capabilities demonstrated.**
- CloudEvent producer/consumer using PeeGeeQ as the transport.
- Correlation and causation extension attributes preserved through the pipeline.
- Jackson serialisation with combined JSR310 + CloudEvents modules.

**Primary classes.**
- Main: [CloudEventsExample.java](../src/main/java/dev/mars/peegeeq/examples/CloudEventsExample.java)
- Test: [CloudEventsObjectMapperTest.java](../src/test/java/dev/mars/peegeeq/examples/CloudEventsObjectMapperTest.java)

→ **Code example:** [§A.14](#a14-cloudevents)

---

### 3.5 Server-Sent Events (SSE) Streaming

**Business problem.** Browser dashboards, live trade blotters, and alert feeds need real-time push from the server over plain HTTP — without WebSockets — and must recover gracefully from disconnections.

**Key capabilities demonstrated.**
- SSE endpoint over the REST API, parsing the `event:` / `data:` wire format.
- Message-type and header filtering, batch size and max-wait tuning.
- Consumer groups for load-balanced SSE delivery.
- Reconnection with `Last-Event-ID`, exponential backoff with jitter, transient vs. permanent error classification.
- Connection lifecycle state machine (CONNECTING → ACTIVE → IDLE → CLOSED) with metrics (messages/s, latency, throughput).

**Primary classes (main).**
- [ServerSentEventsConsumerExample.java](../src/main/java/dev/mars/peegeeq/examples/ServerSentEventsConsumerExample.java)
- [SSEFilteringExample.java](../src/main/java/dev/mars/peegeeq/examples/SSEFilteringExample.java)
- [SSEErrorHandlingExample.java](../src/main/java/dev/mars/peegeeq/examples/SSEErrorHandlingExample.java)
- [SSEConnectionManagementExample.java](../src/main/java/dev/mars/peegeeq/examples/SSEConnectionManagementExample.java)

→ **Code example:** [§A.15](#a15-sse-streaming)

---

### 3.6 Distributed Tracing

**Business problem.** When an order flows from an HTTP API, through a queue, to a consumer, and on to a downstream HTTP service, operators must be able to reconstruct the full end-to-end trace for debugging and SLA analysis.

**Key capabilities demonstrated.**
- W3C `traceparent` propagation through HTTP → queue → consumer → downstream HTTP.
- MDC correlation IDs in logs.
- Trace context preservation across async boundaries.

**Primary class (main).** [FullDistributedTracingExample.java](../src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java)

→ **Code example:** [§A.16](#a16-distributed-tracing)

---

## 4. Platform & Infrastructure

### 4.1 Configuration & Multi-Tenant Isolation

**Business problem.** A platform deployed across many environments (dev/test/prod) and many tenants needs a predictable configuration model: per-tenant overrides, environment-variable injection (Kubernetes-style), and strict isolation so one tenant's settings never leak into another.

**Key capabilities demonstrated.**
- 5-source merge priority chain and placeholder resolution.
- Kubernetes-style environment property injection.
- Multi-tenant named profiles with isolation guarantees.
- Regression guard: JVM system properties must not bleed into tenant-scoped configurations.

**Primary tests.**
- [ConfigurationValidationTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/ConfigurationValidationTest.java)
- [ConfigurationGuideExamplesTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/ConfigurationGuideExamplesTest.java)
- [SystemPropertiesConfigurationDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/SystemPropertiesConfigurationDemoTest.java)

→ **Code example:** [§A.17](#a17-configuration)

---

### 4.2 Native vs. Outbox — Choosing the Right Factory

**Business problem.** Architects need empirical guidance on when to use the native queue (low latency, fire-and-forget) versus the outbox (transactional guarantees, durability).

**Key capabilities demonstrated.**
- Side-by-side comparison of performance, reliability, and feature availability.
- Use-case suitability matrix.
- Running multiple factory configurations simultaneously in the same JVM.

**Primary tests.**
- [NativeVsOutboxComparisonTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/NativeVsOutboxComparisonTest.java)
- [MultiConfigurationIntegrationTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/MultiConfigurationIntegrationTest.java)

→ **Code example:** combine [§A.4](#a4-transactional-outbox) and [§A.9](#a9-native-queue) — both use the same `MessageProducer`/`MessageConsumer` API behind a different factory `provider.createFactory("outbox"|"native", databaseService)`.

---

### 4.3 REST API & Service Discovery

**Business problem.** External clients and operators need an HTTP control plane to manage queues, event stores, and consumer groups; in a clustered deployment, instances must register with a service registry for discovery and health monitoring.

**Key capabilities demonstrated.**
- REST lifecycle, health checks, queue management, event-store and consumer-group management over HTTP.
- Consul integration for service registration and health monitoring.

**Primary tests.**
- [RestApiExampleTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/RestApiExampleTest.java)
- [ServiceDiscoveryExampleTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/ServiceDiscoveryExampleTest.java)

→ **Code example:** [§A.18](#a18-rest-api)

---

### 4.4 Database Setup & Runtime Lifecycle

**Business problem.** Provisioning a new tenant or environment must create the database, apply schema migrations, set up queue and event-store tables, and tear everything down cleanly afterwards.

**Key capabilities demonstrated.**
- Template DB creation and schema migration.
- Queue and event-store table provisioning.
- Cleanup and graceful shutdown.
- `PeeGeeQManager` lifecycle, backpressure management, metrics.

**Primary tests.**
- [DatabaseSetupServiceIntegrationTest.java](../src/test/java/dev/mars/peegeeq/examples/integration/DatabaseSetupServiceIntegrationTest.java)
- [PeeGeeQExampleTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/PeeGeeQExampleTest.java)
- [PeeGeeQSelfContainedDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/PeeGeeQSelfContainedDemoTest.java)

→ **Code example:** see the bootstrap pattern at the top of [Appendix A](#appendix-a--code-snippets) (the `PeeGeeQManager.start()` + `PgDatabaseService` initialisation is identical across scenarios).

---

## Cross-Reference: Business Goal → Scenario

| If you want to learn how to… | Read scenario |
|---|---|
| Record trades with full audit-grade history | 1.1 Funds & Custody |
| Reconstruct what was reported on a past date | 1.1, 1.2 |
| Build an event-sourced aggregate (account, etc.) | 1.3 Bank Account ES/CQRS |
| Atomically write to DB and publish a message | 2.1 Transactional Outbox |
| Scale consumers horizontally | 2.2 Consumer Groups |
| Backfill a new consumer from history | 2.2 Consumer Groups (late joiners) |
| Keep ordered processing per key | 2.2 Consumer Groups (partitioned) |
| Achieve lowest end-to-end latency | 2.4 Native Queue |
| Measure throughput / latency under load | 2.3 High-Frequency |
| Wire microservices with async messaging | 3.1 Microservices Communication |
| Implement transform / route / aggregate / saga | 3.2 Enterprise Integration |
| Survive transient failures gracefully | 3.3 Resilience & Error Handling |
| Standardise event envelopes across systems | 3.4 CloudEvents |
| Push real-time events to a browser | 3.5 SSE Streaming |
| Trace a request end-to-end | 3.6 Distributed Tracing |
| Configure per-tenant isolated settings | 4.1 Configuration & Multi-Tenant |
| Decide between native and outbox | 4.2 Native vs. Outbox |
| Operate PeeGeeQ over REST and Consul | 4.3 REST API & Service Discovery |
| Provision a new tenant DB | 4.4 Database Setup |

---

# Appendix A — Code Snippets

Distilled idiomatic snippets for each scenario, based on the actual example tests under `peegeeq-examples`. All snippets follow the project's mandatory Vert.x reactive style: `io.vertx.core.Future<T>` with `.compose()`, `.onSuccess()`, `.onFailure()`. Boilerplate (imports, TestContainers setup, assertions) is omitted.

**Common bootstrap** (used by every scenario unless noted):

```java
// Initialise schema, start PeeGeeQ manager (Vert.x-managed)
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
manager.start().await();

DatabaseService databaseService = new PgDatabaseService(manager);
QueueFactoryProvider provider   = new PgQueueFactoryProvider();
// Register the factory implementations you intend to use:
OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);   // for outbox examples
PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider); // for native examples
```

---

## A.1 Funds & Custody

Source: [TradeService.java](../src/main/java/dev/mars/peegeeq/examples/fundscustody/service/TradeService.java)

```java
// Create per-domain bi-temporal event stores
BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager.getVertx(), manager);
EventStore<TradeEvent>          trades        = factory.createEventStore(TradeEvent.class, "trade_events");
EventStore<TradeCancelledEvent> cancellations = factory.createEventStore(TradeCancelledEvent.class, "cancellation_events");

// Record a trade: valid time = trade date, transaction time set by store
Future<BiTemporalEvent<TradeEvent>> recorded = trades.append(
    "TradeExecuted",
    TradeEvent.from(trade),
    trade.tradeDate().atStartOfDay().toInstant(ZoneOffset.UTC),   // validTime
    Map.of("tradeId", trade.tradeId(), "counterparty", trade.counterparty()),
    null, null,                                                    // correlationId, causationId
    "TRADE:" + trade.fundId());                                    // aggregateId

// Query the full history for a fund — point-in-time NAV / position reconstruction
Future<List<BiTemporalEvent<TradeEvent>>> history =
    trades.query(EventQuery.forAggregate("TRADE:" + fundId));

// Cancellation preserves audit trail in a separate stream
trades.query(EventQuery.forAggregate("CANCELLATION:" + fundId))
    .onSuccess(list -> log.info("{} cancellations for fund", list.size()));
```

---

## A.2 Advanced Bi-Temporal Trade Pipeline

Source: [BiTemporalEventStoreExampleTest.java](../src/test/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalEventStoreExampleTest.java)

```java
BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager.getVertx(), manager);
EventStore<TradeEvent> store = factory.createEventStore(TradeEvent.class, "bitemporal_event_log");

// Original capture
BiTemporalEvent<TradeEvent> original = store.append(
    "TradeCaptured", trade, baseTime,
    Map.of("session", "morning"),
    "corr-1", null,
    tradeId).await();

// MiFID II T+1 correction — original NOT mutated, correction event is appended
store.appendCorrection(
    original.getEventId(), "TradeCorrected", correctedTrade,
    baseTime,                              // same business time
    Map.of("reason", "price-adjusted"),
    "corr-1", null,
    tradeId).await();

// Bi-temporal queries
store.query(EventQuery.forAggregate(tradeId)).await();         // full history
store.query(EventQuery.forEventType("TradeCaptured")).await(); // all captures
EventStore.EventStoreStats stats = store.getStats().await();   // counts, latencies
```

---

## A.3 Event Sourcing + CQRS

Source: [EventSourcingCQRSDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/EventSourcingCQRSDemoTest.java)

```java
QueueFactory queueFactory = provider.createFactory("outbox", databaseService);

// Command side
MessageProducer<Command>     commandProducer = queueFactory.createProducer("commands", Command.class);
MessageConsumer<Command>     commandConsumer = queueFactory.createConsumer("commands", Command.class);
// Event side
MessageProducer<DomainEvent> eventProducer   = queueFactory.createProducer("events",   DomainEvent.class);
MessageConsumer<DomainEvent> eventConsumer   = queueFactory.createConsumer("events",   DomainEvent.class);

// Command handler: load aggregate, apply command -> emit event
commandConsumer.subscribe(message -> {
    Command cmd = message.getPayload();
    DomainEvent event = aggregate.handle(cmd);     // returns AccountCredited / AccountDebited / ...
    return eventProducer.send(event, null, null, event.getAggregateId()).mapEmpty();
});

// Read-model projector: apply event to the materialised view
eventConsumer.subscribe(message -> {
    readModel.apply(message.getPayload());          // updates balance, etc.
    return Future.succeededFuture();
});

commandProducer.send(new OpenAccount("acc-1", new BigDecimal("100.00")));
```

---

## A.4 Transactional Outbox

Source: [TransactionalOutboxAnalysisTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/TransactionalOutboxAnalysisTest.java)

```java
OutboxFactory outboxFactory = new OutboxFactory(databaseService, config);
Pool pool = databaseService.getPool();

MessageProducer<String> producer = outboxFactory.createProducer("orders", String.class);
MessageConsumer<String> consumer = outboxFactory.createConsumer("orders", String.class);

// Atomic business write + outbox send in ONE reactive transaction
pool.withTransaction(conn ->
    conn.preparedQuery(
        "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1,$2,$3,$4)")
        .execute(Tuple.of(orderId, "customer-123", new BigDecimal("99.99"), "CREATED"))
    .compose(v -> producer.sendInTransaction("OrderCreated:" + orderId, conn))
).onSuccess(v -> log.info("Both row and message committed atomically"));

consumer.subscribe(msg -> { handleOrder(msg.getPayload()); return Future.succeededFuture(); });

// Rollback isolation — if the transaction fails, the message is NEVER delivered
pool.withTransaction(conn ->
    conn.preparedQuery("INSERT INTO test_orders ...").execute(Tuple.of(/* ... */))
        .compose(v -> producer.sendInTransaction("ShouldNotBeSeen", conn))
        .compose(v -> Future.failedFuture(new RuntimeException("rule violation")))
).onFailure(err -> log.info("Both row and message rolled back"));
```

---

## A.5 Outbox Server-Side Filtering

Source: [OutboxServerSideFilteringTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/OutboxServerSideFilteringTest.java)

```java
OutboxFactory outboxFactory = (OutboxFactory) provider.createFactory("outbox", databaseService);
MessageProducer<String> producer = outboxFactory.createProducer("orders", String.class);

// Composable JSONB predicates evaluated at the database, not the client
ServerSideFilter filter = ServerSideFilter.and(
    ServerSideFilter.headerEquals("type", "ORDER"),
    ServerSideFilter.headerIn("region", Set.of("US", "EU"))
);
OutboxConsumerConfig cfg = OutboxConsumerConfig.builder().serverSideFilter(filter).build();
MessageConsumer<String> consumer = outboxFactory.createConsumer("orders", String.class, cfg);

consumer.subscribe(msg -> { handle(msg.getPayload()); return Future.succeededFuture(); });

producer.send("Order 1",   Map.of("type", "ORDER", "region", "US"));    // delivered
producer.send("Order 2",   Map.of("type", "ORDER", "region", "APAC"));  // skipped at DB
producer.send("Refund 1",  Map.of("type", "REFUND", "region", "US"));   // skipped at DB
```

---

## A.6 Consumer Groups

Source: [AdvancedProducerConsumerGroupTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java)

```java
QueueFactory factory = provider.createFactory("outbox", databaseService);
MessageProducer<OrderEvent> producer = factory.createProducer("orders", OrderEvent.class);

// Producer: headers for routing + correlationId + messageGroup for partitioned ordering
producer.send(event,
        Map.of("region", "US", "priority", "HIGH"),
        "correlation-" + i,
        "order-" + event.customerId())                          // messageGroup
    .onSuccess(v -> sendCheckpoint.flag())
    .onFailure(testContext::failNow);

// Consumer group with per-consumer filters
ConsumerGroup<OrderEvent> group =
    factory.createConsumerGroup("OrderProcessors", "orders", OrderEvent.class);
group.addConsumer("US-Consumer",     handler, MessageFilter.byRegion(Set.of("US")));
group.addConsumer("EU-Consumer",     handler, MessageFilter.byRegion(Set.of("EU")));
group.addConsumer("HighPriority",    handler, MessageFilter.byHeader("priority", "HIGH"));
group.start();
```

---

## A.7 Late-Joining Consumers

Source: [LateJoiningConsumerDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/LateJoiningConsumerDemoTest.java)

```java
TopicConfigService   topicConfigService  = new TopicConfigService(connectionManager, "peegeeq-main");
SubscriptionManager  subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
ConsumerGroupFetcher fetcher             = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");

topicConfigService.createTopic(TopicConfig.builder()
    .topic("user-events")
    .semantics(TopicSemantics.PUB_SUB)
    .messageRetentionHours(24)
    .build()).await();

// "email-service" starts now — only sees future messages
subscriptionManager.subscribe("user-events", "email-service",
    SubscriptionOptions.defaults()).await();                    // StartPosition.FROM_NOW

// "analytics-service" joins later but needs full history
subscriptionManager.subscribe("user-events", "analytics-service",
    SubscriptionOptions.builder()
        .startPosition(StartPosition.FROM_BEGINNING)
        .build()).await();

// Pull a batch
List<Message> batch = fetcher.fetchMessages("user-events", "analytics-service", 100).await();
```

---

## A.8 Dead Consumer Detection

Source: [DeadConsumerDetectionDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/outbox/DeadConsumerDetectionDemoTest.java)

```java
DeadConsumerDetector detector = new DeadConsumerDetector(connectionManager, "peegeeq-main");

SubscriptionOptions opts = SubscriptionOptions.builder()
    .heartbeatIntervalSeconds(1)
    .heartbeatTimeoutSeconds(10)
    .deadAfterMisses(1)
    .build();

subscriptionManager.subscribe("orders", "healthy-consumer", opts).await();
subscriptionManager.subscribe("orders", "crashed-consumer", opts).await();

// Live consumer emits heartbeats; crashed one stops
subscriptionManager.updateHeartbeat("orders", "healthy-consumer").await();

// Periodic sweep marks subscriptions DEAD when they miss too many heartbeats
List<Subscription> dead = detector.detectDeadConsumers().await();
Subscription s = subscriptionManager.getSubscriptionInternal("orders", "crashed-consumer").await();
assert s.getStatus() == SubscriptionStatus.DEAD;
```

---

## A.9 Native Queue

Source: [SimpleNativeQueueTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/SimpleNativeQueueTest.java), [NativeQueueFeatureTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/NativeQueueFeatureTest.java)

```java
// Use the "native" factory key — same API as outbox, different backend (LISTEN/NOTIFY + advisory locks)
QueueFactory nativeFactory = provider.createFactory("native", databaseService);

MessageProducer<String> producer = nativeFactory.createProducer("low-latency", String.class);
MessageConsumer<String> consumer = nativeFactory.createConsumer("low-latency", String.class);

consumer.subscribe(message -> {
    processed.incrementAndGet();
    return Future.succeededFuture();
});

// Concurrent sends — aggregate via Future.all
List<Future<Void>> futures = new ArrayList<>();
for (int i = 0; i < 1000; i++) futures.add(producer.send("msg-" + i));
Future.all(futures)
    .onSuccess(v -> log.info("All 1000 messages enqueued"))
    .onFailure(err -> log.error("Send failed", err));
```

The same `MessageProducer`/`MessageConsumer` API works for both factories — choose by performance vs. transactional needs (see scenario 4.2).

---

## A.10 Native Server-Side Filtering

Source: [ServerSideFilteringTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/ServerSideFilteringTest.java)

```java
PgNativeQueueFactory nativeFactory =
    (PgNativeQueueFactory) provider.createFactory("native", databaseService);

ServerSideFilter filter = ServerSideFilter.headerIn("type", Set.of("ORDER", "REFUND"));
ConsumerConfig cfg = ConsumerConfig.builder().serverSideFilter(filter).build();
MessageConsumer<String> consumer = nativeFactory.createConsumer("events", String.class, cfg);

consumer.subscribe(msg -> { received.add(msg.getPayload()); return Future.succeededFuture(); });

producer.send("Order 1",   Map.of("type", "ORDER"));      // delivered
producer.send("Refund 1",  Map.of("type", "REFUND"));     // delivered
producer.send("Payment 1", Map.of("type", "PAYMENT"));    // filtered out at DB
```

(Note: native uses `ConsumerConfig`, outbox uses `OutboxConsumerConfig`. The `ServerSideFilter` factory methods are shared: `headerEquals`, `headerIn`, `and`, `or`, `not`.)

---

## A.11 Microservices Request/Response

Source: [MicroservicesCommunicationDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/MicroservicesCommunicationDemoTest.java)

```java
MessageProducer<ServiceMessage> requestProducer  = factory.createProducer("requests",  ServiceMessage.class);
MessageConsumer<ServiceMessage> requestConsumer  = factory.createConsumer("requests",  ServiceMessage.class);
MessageProducer<ServiceMessage> responseProducer = factory.createProducer("responses", ServiceMessage.class);
MessageConsumer<ServiceMessage> responseConsumer = factory.createConsumer("responses", ServiceMessage.class);

// Service: receive request, send response with the same correlationId
requestConsumer.subscribe(message -> {
    ServiceMessage req = message.getPayload();
    ServiceMessage resp = new ServiceMessage(req.correlationId(), handle(req));
    return responseProducer.send(resp).mapEmpty();
});

// Caller: maintains a map of correlationId -> Promise
Map<String, Promise<ServiceMessage>> pending = new ConcurrentHashMap<>();

responseConsumer.subscribe(message -> {
    Promise<ServiceMessage> p = pending.remove(message.getPayload().correlationId());
    if (p != null) p.complete(message.getPayload());
    return Future.succeededFuture();
});

Promise<ServiceMessage> reply = Promise.promise();
pending.put(corrId, reply);
requestProducer.send(new ServiceMessage(corrId, "check-stock", "AAPL"));
ServiceMessage answer = reply.future().await();
```

---

## A.12 EIP — Transform & Route

Source: [EnterpriseIntegrationDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/EnterpriseIntegrationDemoTest.java)

```java
MessageConsumer<OrderMessage>       input          = factory.createConsumer("input",         OrderMessage.class);
MessageProducer<TransformedMessage> transformedOut = factory.createProducer("transformed",   TransformedMessage.class);
MessageProducer<OrderMessage>       highPrio       = factory.createProducer("orders.high",   OrderMessage.class);
MessageProducer<OrderMessage>       normalPrio     = factory.createProducer("orders.normal", OrderMessage.class);
MessageProducer<OrderMessage>       lowPrio        = factory.createProducer("orders.low",    OrderMessage.class);

// Message Translator: external format -> internal format
input.subscribe(message -> {
    TransformedMessage out = transform(message.getPayload());       // e.g. FIX -> internal
    return transformedOut.send(out).mapEmpty();
});

// Content-Based Router: dispatch by priority
input.subscribe(message -> {
    OrderMessage order = message.getPayload();
    MessageProducer<OrderMessage> target = switch (order.getPriority()) {
        case "HIGH"   -> highPrio;
        case "NORMAL" -> normalPrio;
        default       -> lowPrio;
    };
    return target.send(order).mapEmpty();
});
```

---

## A.13 Resilience — DLQ & Retry

Source: [EnhancedErrorHandlingDemoTest.java](../src/test/java/dev/mars/peegeeq/examples/nativequeue/EnhancedErrorHandlingDemoTest.java)

```java
// Configure retry, DLQ, and circuit breaker via properties
Properties props = PeeGeeQTestConfig.builder().from(postgres)
    .property("peegeeq.retry.enabled",                 "true")
    .property("peegeeq.retry.max.attempts",            "3")
    .property("peegeeq.retry.backoff.initial",         "100")
    .property("peegeeq.retry.backoff.multiplier",      "2.0")
    .property("peegeeq.dlq.enabled",                   "true")
    .property("peegeeq.circuit.breaker.enabled",       "true")
    .property("peegeeq.circuit.breaker.failure.threshold", "5")
    .build();

MessageProducer<OrderEvent> producer    = factory.createProducer("orders",     OrderEvent.class);
MessageConsumer<OrderEvent> consumer    = factory.createConsumer("orders",     OrderEvent.class);
MessageConsumer<OrderEvent> dlqConsumer = factory.createConsumer("orders-dlq", OrderEvent.class);

consumer.subscribe(message -> {
    return tryProcess(message.getPayload())
        .recover(err -> isTransient(err)
            ? Future.failedFuture(err)              // returning failed future triggers retry
            : Future.failedFuture(new PoisonMessage(err)));  // -> DLQ after max attempts
});

dlqConsumer.subscribe(msg -> { alertOps(msg); return Future.succeededFuture(); });

producer.send(order, Map.of("error_type", ErrorType.TRANSIENT_NETWORK.name()));
```

---

## A.14 CloudEvents

Source: [CloudEventsExample.java](../src/main/java/dev/mars/peegeeq/examples/CloudEventsExample.java)

```java
QueueFactory factory = provider.createFactory("outbox", databaseService);
MessageProducer<CloudEvent> producer = factory.createProducer("cloudevents", CloudEvent.class);
MessageConsumer<CloudEvent> consumer = factory.createConsumer("cloudevents", CloudEvent.class);

CloudEvent event = CloudEventBuilder.v1()
    .withId("evt-" + UUID.randomUUID())
    .withType("com.example.trade.executed.v1")
    .withSource(URI.create("https://example.com/trading"))
    .withTime(OffsetDateTime.now())
    .withDataContentType("application/json")
    .withData(objectMapper.writeValueAsBytes(tradePayload))
    .withExtension("correlationid", "trade-workflow-1")
    .withExtension("causationid",   parentEventId)
    .withExtension("validtime",     OffsetDateTime.now().toString())
    .build();

consumer.subscribe(msg -> {
    CloudEvent ce = msg.getPayload();
    log.info("Received {} (corr={})", ce.getType(),
        ce.getExtension("correlationid"));
    return Future.succeededFuture();
});

producer.send(event, Map.of("priority", "HIGH"));
```

---

## A.15 SSE Streaming

Source: [SSEFilteringExample.java](../src/main/java/dev/mars/peegeeq/examples/SSEFilteringExample.java)

```java
Vertx vertx = Vertx.vertx();
HttpClient httpClient = vertx.createHttpClient();

// SSE filter parameters: messageType, batchSize, maxWaitMs, consumerGroup
String url = "/api/v1/queues/" + setupId + "/orders/stream"
           + "?messageType=OrderCreated&batchSize=10&maxWaitMs=500&consumerGroup=dashboard";

httpClient.request(HttpMethod.GET, restPort, "localhost", url)
    .compose(req -> req.putHeader("Accept", "text/event-stream").send())
    .onSuccess(resp ->
        resp.handler(buf -> parseSseFrame(buf, eventCount, latch)));
```

The SSE wire format is `event: <type>\ndata: <json>\n\n`; clients reconnect by sending `Last-Event-ID` to resume from their last position. See [SSEErrorHandlingExample.java](../src/main/java/dev/mars/peegeeq/examples/SSEErrorHandlingExample.java) for backoff + reconnect logic.

---

## A.16 Distributed Tracing

Source: [FullDistributedTracingExample.java](../src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java)

```java
QueueFactory factory = provider.createFactory("outbox", databaseService);
MessageProducer<JsonObject> producer = factory.createProducer("orders", JsonObject.class);
MessageConsumer<JsonObject> consumer = factory.createConsumer("orders", JsonObject.class);

// Inbound HTTP: extract W3C traceparent, hydrate MDC, propagate through queue
router.post("/orders").handler(ctx -> {
    String traceparent   = ctx.request().getHeader("traceparent");
    String correlationId = ctx.request().getHeader("X-Correlation-Id");
    TraceContextUtil.setMDCFromTraceparent(traceparent);
    String traceId = MDC.get("traceId");

    Map<String, String> headers = Map.of(
        "traceparent",   String.format("00-%s-%s-01", traceId, generateSpanId()),
        "correlationId", correlationId);

    producer.send(ctx.body().asJsonObject(), headers, correlationId)
        .onSuccess(v -> ctx.response().setStatusCode(202).end())
        .onFailure(err -> ctx.response().setStatusCode(500).end());
});

// Consumer: MDC is auto-populated from message headers; propagate to downstream HTTP
consumer.subscribe(message -> {
    String traceId = MDC.get("traceId");
    return webClient.post(9090, "localhost", "/external/process")
        .putHeader("traceparent", String.format("00-%s-%s-01", traceId, generateSpanId()))
        .sendJsonObject(message.getPayload())
        .mapEmpty();
});
```

---

## A.17 Configuration

Source: [ConfigurationValidationTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/ConfigurationValidationTest.java)

```java
// Properties (e.g. from Kubernetes ConfigMap, env vars, or Spring config) override defaults
Properties overrides = new Properties();
overrides.setProperty("peegeeq.queue.max-retries",      "10");
overrides.setProperty("peegeeq.queue.batch-size",       "100");
overrides.setProperty("peegeeq.queue.polling-interval", "PT2S");
overrides.setProperty("peegeeq.database.host",          "prod-postgres.company.com");
overrides.setProperty("peegeeq.database.port",          "5432");
overrides.setProperty("peegeeq.database.name",          "peegeeq_production");

// Named profile + overrides — each tenant gets its own isolated instance
PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("tenant-acme", overrides);
assert cfg.getQueueConfig().getMaxRetries()     == 10;
assert cfg.getQueueConfig().getBatchSize()      == 100;
assert cfg.getQueueConfig().getPollingInterval().equals(Duration.ofSeconds(2));
```

---

## A.18 REST API

Source: [RestApiExampleTest.java](../src/test/java/dev/mars/peegeeq/examples/patterns/RestApiExampleTest.java)

```java
// Start the REST server as a Vert.x verticle
RestServerConfig restConfig = new RestServerConfig(
    8080, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));
DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
PeeGeeQRestServer server = new PeeGeeQRestServer(restConfig, setupService);
String deploymentId = vertx.deployVerticle(server).await();

WebClient client = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(5000));

// Health check
JsonObject health = client.get(8080, "localhost", "/health").send()
    .map(HttpResponse::bodyAsJsonObject).await();

// Create a new tenant setup (database + schema + queues + event stores)
JsonObject setupRequest = new JsonObject()
    .put("setupId", "tenant-acme-" + System.currentTimeMillis())
    .put("databaseConfig", new JsonObject()
        .put("host", postgres.getHost())
        .put("port", postgres.getFirstMappedPort())
        .put("databaseName", postgres.getDatabaseName()));

client.post(8080, "localhost", "/api/v1/database-setup/create")
    .sendJsonObject(setupRequest)
    .onSuccess(resp -> log.info("Setup created: {}", resp.bodyAsJsonObject()));
```

---

***Note on `.await()`*** *— in test snippets, PeeGeeQ uses Vert.x 5's `Future.await()` (a virtual-thread suspension point) to block sequentially. In production code, prefer composing with `.compose()` / `.onSuccess()` / `.onFailure()` rather than `.await()`.*
