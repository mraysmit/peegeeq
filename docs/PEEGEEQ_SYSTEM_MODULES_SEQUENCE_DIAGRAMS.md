# PeeGeeQ Platform Sequence Flows

This document maps concrete runtime flows across the PeeGeeQ platform, starting with implemented `peegeeq-db` fanout/backfill and producer/consumer lifecycle sequences.

## Document Goal

This is the living sequence-flow reference for the full PeeGeeQ platform.
It will grow to include key runtime interactions across all modules and services, with one or more Mermaid sequence diagrams per critical flow.

## Platform Flow Coverage

| Module / Service | Key flow(s) | Status |
|---|---|---|
| `peegeeq-db` | Done: topic subscription with historical message backfill; concurrent backfill coordination and row-level locking.<br>In progress: connection/pool lifecycle, setup/migrations path, subscription/backfill/fanout expansion. | Mixed (Done + In progress) |
| `peegeeq-db` + `peegeeq-outbox` | Message production, group fetch, and fanout completion tracking | Done |
| `peegeeq-api` | API contract flows for producer, consumer, subscription lifecycle | Planned |
| `peegeeq-outbox` | Outbox produce, polling consume, retry/dead-letter routing | Planned |
| `peegeeq-native` | Native queue publish/consume and notification path | Planned |
| `peegeeq-bitemporal` | Event append/query and temporal consistency flow | Planned |
| `peegeeq-runtime` | Runtime bootstrap, factory registration, lifecycle start/stop | Planned |
| `peegeeq-rest` | REST request to service to DB sequence per major endpoint group | Planned |
| `peegeeq-rest-client` | Client request/retry/SSE stream handling sequence | Planned |
| `peegeeq-service-manager` | Service orchestration and health/lifecycle coordination | Planned |
| `peegeeq-migrations` | Migration startup, ordering, schema validation | Planned |
| `peegeeq-management-ui` | UI action to API to backend to DB round trips | Planned |
| `peegeeq-openapi` | OpenAPI generation/publish flow | Planned |
| `peegeeq-integration-tests` | End-to-end test harness orchestration flow | Planned |
| `peegeeq-performance-test-harness` | Performance run setup, workload, metrics capture | Planned |
| `peegeeq-test-support` | Shared test infrastructure lifecycle (containers, schema) | Planned |
| `peegeeq-examples` | Canonical usage flows by pattern | Planned |
| `peegeeq-examples-spring` | Spring wiring and auto-start integration flows | Planned |

---

## Module: `peegeeq-api`

- Purpose: Core contracts for messaging, subscriptions, eventing, and service abstractions.
- Key sequences to capture: API lifecycle calls, producer/consumer contract usage, subscription option semantics.

## Module: `peegeeq-db`

- Purpose: PostgreSQL-backed infrastructure, connection management, subscriptions, backfill, fanout tracking.
- Key sequences to capture: setup/start, subscription/backfill, fetch/complete, cleanup/recovery.

**Flows in this section:**

1. [Topic Subscription with Historical Message Backfill](#topic-subscription-with-historical-message-backfill)

   End-to-end subscribe call with `FROM_BEGINNING` start position, persisting the subscription row via `SubscriptionManager`, then conditionally triggering `BackfillService`. Backfill acquires a row lock on the subscription, processes pre-existing outbox messages in batches with checkpoint updates per transaction, and marks backfill complete on finish. Covers the three backfill entry outcomes: lock acquired (run), already in progress (skip), and already completed (no-op).

2. [Concurrent Backfill Coordination and Row-Level Locking](#concurrent-backfill-coordination-and-row-level-locking)

   How multiple workers contend for backfill ownership using database row locks, with skip and idempotency outcomes.

### 1. Topic Subscription with Historical Message Backfill

Key classes:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`
- `peegeeq-db/src/main/resources/db/templates/base/08b-consumer-table-subscriptions.sql`

```mermaid
sequenceDiagram
    autonumber
    participant App as ClientApp
    participant Sub as SubscriptionManager
    participant Cx as PgConnectionManager
    participant DB as PostgreSQL
    participant Bf as BackfillService

    App->>Sub: subscribe(topic, group, FROM_BEGINNING)
    Sub->>Cx: withConnection(serviceId)
    Cx->>DB: INSERT/UPSERT outbox_topic_subscriptions\nstatus=ACTIVE start_from_message_id=1
    DB-->>Sub: Subscription row persisted

    alt BackfillService configured
        Sub->>Bf: startBackfill(topic, group, scope)
        Bf->>Cx: withTransaction(serviceId)
        Cx->>DB: SELECT subscription FOR UPDATE
        DB-->>Bf: status/checkpoint/start_from_message_id

        alt backfill status COMPLETED
            Bf-->>Sub: ALREADY_COMPLETED
        else backfill status IN_PROGRESS
            Bf-->>Sub: SKIPPED (other worker owns progress)
        else lock acquired
            Bf->>DB: UPDATE backfill_status=IN_PROGRESS
            Bf->>Cx: withConnection(serviceId)
            Cx->>DB: COUNT eligible outbox rows
            DB-->>Bf: total to process

            loop Batch loop (tail-recursive Future compose)
                Bf->>Cx: withTransaction(serviceId)
                Cx->>DB: SELECT subscription FOR UPDATE\n(check cancel/progress)
                Cx->>DB: SELECT outbox IDs from checkpoint\nLIMIT batch
                Cx->>DB: UPDATE outbox increment required_consumer_groups
                Cx->>DB: INSERT outbox_consumer_groups\nON CONFLICT DO NOTHING
                Cx->>DB: UPDATE checkpoint + processed_messages
                DB-->>Bf: batch committed
            end

            Bf->>DB: UPDATE backfill_status=COMPLETED\nset completed_at
            Bf-->>Sub: COMPLETED(processedCount)
        end

        Sub-->>App: subscribe succeeds\n(backfill is best-effort)
    else no BackfillService configured
        Sub-->>App: subscribe succeeds only
    end
```

### 2. Concurrent Backfill Coordination and Row-Level Locking

- Backfill uses short, explicit `withConnection` and `withTransaction` steps rather than a long-lived connection lifecycle.
- Concurrency control remains in database row locks (`SELECT ... FOR UPDATE` on subscription row).
- If another worker already owns in-progress state, backfill returns `SKIPPED` instead of racing.
- If backfill already completed, it returns `ALREADY_COMPLETED` and preserves prior processed count.
- Batch work and checkpoint updates are committed transactionally per batch, improving resumability after failure/cancel.

```mermaid
sequenceDiagram
    autonumber
    participant W1 as Worker 1
    participant W2 as Worker 2
    participant Bf as BackfillService
    participant Cx as PgConnectionManager
    participant DB as PostgreSQL

    par concurrent startBackfill(topic,group)
        W1->>Bf: startBackfill
    and
        W2->>Bf: startBackfill
    end

    Bf->>Cx: withTransaction
    Cx->>DB: SELECT subscription FOR UPDATE

    alt W1 acquires row lock first
        DB-->>W1: lock + state
        W1->>DB: set IN_PROGRESS
        DB-->>W2: waits, then sees IN_PROGRESS
        W2-->>W2: return SKIPPED

        loop each batch
            W1->>Cx: withTransaction
            W1->>DB: lock subscription row, fetch IDs, update outbox + tracking, checkpoint
        end

        W1->>DB: mark COMPLETED
        W1-->>W1: COMPLETED
    else already COMPLETED before either starts
        DB-->>W1: COMPLETED
        DB-->>W2: COMPLETED
        W1-->>W1: ALREADY_COMPLETED
        W2-->>W2: ALREADY_COMPLETED
    end
```

## Module: `peegeeq-outbox`

- Purpose: Outbox producer/consumer implementation including retries and dead-letter behavior.
- Key sequences to capture: produce, poll/claim/process, retry/dead-letter routing.

**Flows in this section:**

1. [Message Production, Group Fetch, and Fanout Completion Tracking](#message-production-group-fetch-and-fanout-completion-tracking)

   Producer inserts with idempotency, consumer group fetch with skip-locked claiming, and atomic per-group completion tracking.

### Message Production, Group Fetch, and Fanout Completion Tracking

Key classes:

- Producer: `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxProducer.java`
- Group fetch: `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/ConsumerGroupFetcher.java`
- Completion: `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/CompletionTracker.java`
- Schema: `peegeeq-db/src/main/resources/db/templates/base/04a-core-table-outbox.sql`, `peegeeq-db/src/main/resources/db/templates/base/08c-consumer-table-groups.sql`

```mermaid
sequenceDiagram
    autonumber
    participant Prod as OutboxProducer
    participant DB as PostgreSQL
    participant Fetch as ConsumerGroupFetcher
    participant Handler as Group Handler
    participant Track as CompletionTracker

    Prod->>DB: INSERT INTO outbox(topic,payload,headers,...)\nstatus='PENDING'
    alt duplicate idempotency_key
        DB-->>Prod: unique constraint hit
        Prod-->>Prod: treat as successful no-op
    else inserted
        DB-->>Prod: row created
    end

    Fetch->>DB: SELECT outbox rows\nJOIN subscription + LEFT JOIN outbox_consumer_groups\nFOR UPDATE OF outbox SKIP LOCKED
    DB-->>Fetch: batch of group-visible messages
    Fetch-->>Handler: OutboxMessage list

    loop For each processed message
        Handler->>Track: markCompleted(messageId, groupName, topic)
        Track->>DB: INSERT/UPSERT outbox_consumer_groups status=COMPLETED
        Track->>DB: UPDATE outbox increment completed_consumer_groups
        Track->>DB: if all groups completed then status=COMPLETED
        DB-->>Track: atomic completion result
    end
```

Notes:

- Fetch side uses `FOR UPDATE ... SKIP LOCKED` to avoid duplicate in-flight claims by concurrent workers.
- Completion side is idempotent by design (`ON CONFLICT` + guarded counter update).

## Module: `peegeeq-native`

- Purpose: Native queue implementation and runtime message operations.
- Key sequences to capture: native publish/consume and acknowledgment behavior.

## Module: `peegeeq-bitemporal`

- Purpose: Bitemporal/event-store processing and temporal query behavior.
- Key sequences to capture: append/query/correction and temporal consistency checks.

## Module: `peegeeq-runtime`

- Purpose: Runtime bootstrapping and composition of providers/factories/services.
- Key sequences to capture: startup wiring, provider resolution, lifecycle transitions.

## Module: `peegeeq-rest`

- Purpose: REST handlers exposing queue/subscription/management operations.
- Key sequences to capture: request -> handler -> service -> DB and response mapping.

## Module: `peegeeq-rest-client`

- Purpose: Client SDK flows including REST requests and SSE streaming.
- Key sequences to capture: client call, retry, stream open/read/reconnect handling.

## Module: `peegeeq-service-manager`

- Purpose: Service lifecycle and orchestration.
- Key sequences to capture: service registration, start/stop, health and dependency coordination.

## Module: `peegeeq-test-support`

- Purpose: Shared test infrastructure and helpers.
- Key sequences to capture: shared container lifecycle and test setup orchestration.

## Module: `peegeeq-performance-test-harness`

- Purpose: Performance scenario execution and metric collection.
- Key sequences to capture: run initialization, workload drive, result collection.

## Module: `peegeeq-examples`

- Purpose: Reference usage patterns for core PeeGeeQ features.
- Key sequences to capture: canonical end-to-end producer/consumer/subscription examples.

## Module: `peegeeq-examples-spring`

- Purpose: Spring-oriented integration examples.
- Key sequences to capture: Spring bootstrapping, bean wiring, runtime interactions.

## Module: `peegeeq-migrations`

- Purpose: Schema migration lifecycle and validation.
- Key sequences to capture: migration ordering, execution, verification.

## Module: `peegeeq-management-ui`

- Purpose: Web UI for operational visibility and control.
- Key sequences to capture: UI action -> REST -> service -> DB -> UI refresh.

## Module: `peegeeq-openapi`

- Purpose: OpenAPI generation/publication and contract artifacts.
- Key sequences to capture: spec generation and downstream consumption flow.

## Module: `peegeeq-integration-tests`

- Purpose: Cross-module end-to-end verification.
- Key sequences to capture: test harness orchestration and integrated runtime validation.

## Module: `peegeeq-coverage-report`

- Purpose: Coverage aggregation/report generation.
- Key sequences to capture: test result aggregation and report publication.

## Validation References

- Concurrency and race safety tests:
  - `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillServiceConcurrencyTest.java`
- Backfill integration tests:
  - `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillServiceIntegrationTest.java`
- Backfill scope/perf tests:
  - `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillScopePerformanceTest.java`
- OLTP interaction tests:
  - `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/P4_BackfillVsOLTPTest.java`

## Next Diagrams To Add

- Runtime bootstrap path: `peegeeq-runtime` -> provider registration -> queue factory resolution -> manager start
- REST subscription path: `peegeeq-rest` endpoint -> `SubscriptionService` -> `SubscriptionManager` -> `BackfillService`
- Outbox failure path: handler error -> retry counter update -> dead letter routing
- Native queue fast path: producer publish -> notify/listen -> consumer acknowledge
- Migrations startup path: launcher -> ordered migration execution -> schema contract checks
- Management UI operational path: UI action -> REST handler -> DB/service response -> UI refresh
