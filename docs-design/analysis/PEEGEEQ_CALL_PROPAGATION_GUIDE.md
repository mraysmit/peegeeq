# PeeGeeQ Call Propagation Guide

**Status:** CURRENT ARCHITECTURE GUIDE

**Last reconciled:** 2026-09-06

**Repository baseline:** `7db748b8e77f3aba850be7b73547d192dac5b83f`

## Purpose

This guide explains how a request moves from an HTTP client through PeeGeeQ's contracts,
composition layer, adapters, and PostgreSQL storage. It intentionally avoids hard-coded source line
numbers and endpoint totals, which become stale as routes evolve.

Use these sources as the authorities for exact details:

- [OpenAPI definition](../../peegeeq-openapi/src/main/resources/peegeeq-api.yaml) for the supported
  public HTTP contract;
- [REST server routing](../../peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java)
  for routes actually registered by the server;
- [consolidated task register](../tasks/tasks.md) for implementation and verification status; and
- [schema configuration design](../schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md)
  for tenant schema creation and migration rules.

Static source inspection identifies code paths; runtime claims still require the relevant
integration test.

## Layered architecture

```text
HTTP client or management UI
        |
        v
peegeeq-rest: routing, validation, protocol mapping, streams
        |
        v
peegeeq-api: contracts and value types
        ^
        |
peegeeq-runtime: composition and service facade
        |
        +----------------+----------------+
        v                v                v
peegeeq-native      peegeeq-outbox   peegeeq-bitemporal
        \                |                /
         +---------------+---------------+
                         v
                    peegeeq-db
                         |
                         v
                     PostgreSQL
```

### Module responsibilities

| Module | Responsibility |
|---|---|
| `peegeeq-api` | Stable interfaces, request/result types, configuration values, and errors |
| `peegeeq-db` | Connection management, schema setup, subscriptions, health, metrics, dead letters, offsets, and shared PostgreSQL services |
| `peegeeq-native` | Native queue producer, consumer, browser, and consumer-group adapter |
| `peegeeq-outbox` | Transactional outbox producer, consumer, browser, and consumer-group adapter |
| `peegeeq-bitemporal` | Bi-temporal event-store adapter |
| `peegeeq-runtime` | Composition root that registers adapters and exposes the setup-service facade |
| `peegeeq-rest` | HTTP, event-stream, WebSocket, and Prometheus adapters |
| `peegeeq-rest-client` | Java HTTP client for the public REST contract |
| `peegeeq-management-ui` | Browser client using the REST and streaming protocols |

Contracts do not construct infrastructure. The runtime layer is the only layer that wires all
backends together. REST handlers depend on the service contracts and runtime facade rather than
constructing database adapters directly.

## Common request pattern

Most setup-scoped requests follow this sequence:

1. `PeeGeeQRestServer` matches the route and invokes the responsible handler.
2. The handler parses and validates path, query, and body values.
3. The handler resolves the setup through `DatabaseSetupService`.
4. The setup result exposes the required factory or service contract.
5. A native, outbox, event-store, subscription, health, or dead-letter implementation performs the
   operation.
6. The database layer obtains a tenant-qualified connection and executes parameterized SQL.
7. Success is serialized to the public response; failure reaches the centralized HTTP failure
   mapping.

Every asynchronous operation on which the response depends must remain in the returned chain.
Errors are logged where useful and remain failed until the protocol boundary maps them to an HTTP
response.

## Queue message send: end-to-end path

The canonical send route is:

```text
POST /api/v1/queues/{setupId}/{queueName}/messages
  -> PeeGeeQRestServer
  -> QueueHandler.sendMessage
  -> DatabaseSetupService.getSetupResult
  -> QueueFactory.createProducer
  -> MessageProducer.send
  -> native or outbox producer
  -> tenant-qualified PostgreSQL transaction
  -> HTTP response
```

`QueueHandler` maps the request fields into producer arguments:

| HTTP field | Producer meaning | Storage meaning |
|---|---|---|
| `payload` | Typed message body | JSON payload |
| `headers` | Message metadata | JSON headers |
| `correlationId` | End-to-end correlation | Correlation column and response metadata |
| `messageGroup` | Ordering or affinity key | Group/partition column |
| `priority` | Delivery preference | Priority column |
| `delaySeconds` | Relative visibility delay | Absolute visibility timestamp |
| `idempotencyKey` when supported | Duplicate-send fence | Unique topic/key constraint |

The native producer inserts into `queue_messages` and issues a parameterized PostgreSQL
notification after the insert. The outbox producer writes to the outbox storage contract. Both are
resolved behind `QueueFactory`; callers should not infer one implementation from the route alone.

## Queue consumption path

```text
Subscription request or in-process consumer
  -> QueueFactory.createConsumer or createConsumerGroup
  -> native or outbox consumer adapter
  -> subscription and topic configuration
  -> notification and/or polling trigger
  -> PostgreSQL fetch and lock
  -> payload deserialization
  -> application handler
  -> completion, retry, dead-letter, or offset update
```

Regular consumers optimize for concurrent delivery and do not guarantee processing order.
`OFFSET_WATERMARK` consumer groups use assignment generations and per-partition cursors to provide
sequential handling inside one explicit `messageGroup`. See the
[partitioned consumption design](../consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md).

## Event-store append and query paths

An append request resolves the named event store from the setup, validates event metadata, and
calls the event-store contract. The bitemporal adapter writes the event, valid-time range,
transaction-time range, correlation and causation identifiers, aggregate identifier, and metadata
inside the selected transaction boundary.

Queries map public filters into `EventQuery`, execute tenant-qualified reads, and serialize event
versions without flattening the two time dimensions. Corrections append a new version; they do not
overwrite history.

Streaming requests keep an event-stream connection open, subscribe through the event-store
contract, write protocol events and heartbeats, and observe connection closure so the subscription
is released.

## Setup lifecycle path

Setup operations flow through `DatabaseSetupHandler` and `DatabaseSetupService`:

- create provisions an explicitly named database/schema boundary and registered objects;
- connect attaches to an existing setup and reconstructs registered queue/event-store objects;
- detach releases the in-process binding without dropping the database;
- delete follows the current non-destructive setup lifecycle contract; and
- database drop is a separate explicit operation with confirmation requirements.

The runtime facade delegates provisioning to the database setup service, which applies the ordered
SQL template manifests and records setup metadata. It then invokes registered native, outbox, and
event-store factories.

## Current schema authorities

The old `db/schema/minimal-core-schema.sql` file is not a current schema authority and does not
exist. PeeGeeQ uses two deliberate paths:

| Use case | Authority |
|---|---|
| New dynamic setup | Ordered manifests under `peegeeq-db/src/main/resources/db/templates/` |
| Upgrade existing installation | Versioned Flyway migrations under `peegeeq-migrations/src/main/resources/db/migration/` |

Both paths must remain semantically aligned. SQL identifiers are schema-qualified or use the
validated tenant search path established by the connection manager. Under transaction-pooled
PgBouncer, the tenant search path is reapplied inside every transaction.

See the [schema consolidation record](../_archived/completed-records/PEEGEEQ_SCHEMA_CONSOLIDATION_GUIDE.md)
for the history and the schema configuration design for the current procedure.

## REST surface map

The server currently groups routes into these families:

| Family | Handler or owner |
|---|---|
| Health, monitoring, metrics | Server, `HealthHandler`, `SystemMonitoringHandler` |
| Database setup and setup objects | `DatabaseSetupHandler` |
| Queue send, batch, and statistics | `QueueHandler` |
| Queue and event-store streams | SSE/WebSocket handlers and `EventStoreHandler` |
| Consumer groups and options | `ConsumerGroupHandler` |
| Management views and actions | `ManagementApiHandler` |
| Event append, correction, query, versions, aggregate summary | `EventStoreHandler` |
| Dead-letter operations | `DeadLetterHandler` |
| Subscription lifecycle, backfill, and partition offsets | `SubscriptionHandler` |
| Database telemetry and consumer alerts | Dedicated telemetry and alert handlers |
| Webhook delivery | `WebhookSubscriptionHandler` |

Do not copy an endpoint list from this guide into a client. Generate or validate clients against
the OpenAPI definition, then confirm the route is registered in `PeeGeeQRestServer`.

## Failure propagation

- Validation failures become explicit client errors.
- Missing setups, queues, stores, subscriptions, and messages retain distinguishable error types.
- Database or downstream failures remain failures; handlers must not replace them with empty arrays,
  zero counts, or synthetic success.
- HTTP handlers pass asynchronous failures to the shared failure mapper.
- Streaming handlers surface an error event when the protocol permits it and always release owned
  resources.
- Cleanup failures are observed, logged with context, and propagated according to the lifecycle
  contract.

## Verification map

Use maintained tests instead of historical counts:

| Boundary | Representative verification |
|---|---|
| REST request to PostgreSQL message row | `CallPropagationIntegrationTest` |
| Cross-handler runtime wiring | `CrossLayerPropagationIntegrationTest` |
| Java REST client against live server | `RestClientIntegrationTest` |
| Event append, query, correction, and versions | REST and bitemporal integration suites |
| Queue/event streaming | SSE and WebSocket integration suites |
| Consumer-group lifecycle | Native, outbox, and REST consumer-group suites |
| Partition ordering and cursors | Partition assignment, fetcher, safety, and ordering suites |
| Setup create/connect/detach/drop | Setup lifecycle integration suites |
| Tenant isolation and PgBouncer | Dedicated schema and transaction-pooling integration suites |

The exact profile, scope, saved log, and per-class totals must be reported for each run. Historical
counts in an earlier guide revision are not proof for the current checkout.

## Debugging a propagation failure

Work from the outside inward:

1. Confirm the route and method in the OpenAPI file and server router.
2. Confirm request validation and error mapping in the handler.
3. Confirm the setup resolves the expected factory or service kind.
4. Confirm the runtime registered the correct adapter.
5. Follow the returned asynchronous chain into the adapter and database service.
6. Inspect the tenant-qualified SQL and bound values.
7. Query the relevant table using the same schema and correlation identifier.
8. Check notifications, subscriptions, offsets, and completion rows as appropriate.
9. Reproduce with the smallest real-boundary integration test.

## References

- [OpenAPI definition](../../peegeeq-openapi/src/main/resources/peegeeq-api.yaml)
- [REST server routing](../../peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java)
- [Consolidated task register](../tasks/tasks.md)
- [Schema configuration design](../schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md)
- [Partitioned consumption design](../consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md)
- [Testing patterns](../testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md)
