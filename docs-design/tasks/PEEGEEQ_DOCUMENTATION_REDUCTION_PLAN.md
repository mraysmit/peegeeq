# PeeGeeQ Documentation Reduction Plan

**Status:** PROPOSED 2026-11-21. Task 10 in [tasks.md](tasks.md).
**Reader:** the agent that carries out this plan in a later session. Read this file in full before starting a phase.
**Ordering constraint:** owner direction 2026-11-17 says Task 8 is completed before any other task starts. Do not
start phase 1 until the owner either releases Task 8 or says this task may run first.

## 1. Purpose and end state

`docs/` exists so that a developer can adopt PeeGeeQ correctly and an operator can run it. The current folder holds
24 Markdown files, about 780 KB. Much of it is out of date, contradicts other files, or contradicts the code.

End state:

- 11 files, about 185 KB (estimate).
- Each fact is stated in one document. Every other document links to it.
- Every claim in `docs/` has been checked against the code on the day it was written.
- Every code example follows `docs-design/dev/pgq-coding-principles.md`, or is a link to a compiled example.
- `docs/` holds no status, test counts, verification dates, or undated performance figures.

## 2. Scope

In scope: the 24 files under `docs/` (excluding `PGQ-logo.png`).

Out of scope: `docs-design/`. It was not read for this plan. It is reviewed in a separate task if the owner wants
one. Moving test-setup material from `docs/` into `docs-design/testing/` is in scope, because that is where the
material belongs.

The current `docs/README.md` has two rules this plan replaces, subject to decision OD-1:
"Historical documents remain discoverable; consolidation must not erase their detail" and "No source document may
be removed merely because a shorter core guide exists."

## 3. Data model: where each fact lives

The documents went wrong where they held hand copies of facts that another artifact owns. Each kind of fact has one
owner. A document links to the owner or summarizes it; it never copies a table, a schema, or an endpoint list.

| Fact | Source of truth | Treatment in `docs/` |
|---|---|---|
| REST endpoints, parameters, response shapes | Routes in `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`; described by `peegeeq-openapi/src/main/resources/peegeeq-api.yaml` | Link to the OpenAPI file. The guide explains concepts and lifecycle only. Depends on phase 2 confirming the YAML matches the routes |
| Runtime properties and precedence | `PeeGeeQConfiguration`; inventory enforced by `peegeeq-db/src/test/java/dev/mars/peegeeq/db/config/ShippedConfigurationPropertyContractTest.java` | `PEEGEEQ_CONFIGURATION_GUIDE.md` owns the table. No other document lists properties |
| Base schema | `peegeeq-migrations/src/main/resources/db/migration/` | Name the tables and their purpose. No DDL |
| Per-queue and per-event-store objects | `peegeeq-db/src/main/resources/db/templates/*/.manifest` and the files it lists | Describe what is created. No DDL, no file counts |
| Java API signatures | `peegeeq-api` interfaces | Short examples. Name the interface; do not reproduce it |
| Task status, test counts, release evidence | `docs-design/tasks/tasks.md` and saved logs | Link only |
| Coding and testing rules | `docs-design/dev/pgq-coding-principles.md`, `docs-design/testing/` | Link only |
| Runnable examples | `peegeeq-examples` test classes | Link to the class; paste a snippet only where no compiled example exists |
| Proposals and designs | `docs-design/` | Not in `docs/` until implemented |

## 4. Current files and their disposition

Sizes are bytes on disk on 2026-11-21.

| Current file | Size | Goes to |
|---|---:|---|
| README.md | 5,160 | README.md (rewritten) |
| PEEGEEQ_COMPLETE_GUIDE.md | 5,894 | Async and transaction rules move to PEEGEEQ_GETTING_STARTED.md; file deleted |
| PEEGEEQ_GETTING_STARTED.md | 5,203 | PEEGEEQ_GETTING_STARTED.md |
| PEEGEEQ_EXAMPLES_GUIDE.md | 6,439 | PEEGEEQ_GETTING_STARTED.md; file deleted |
| PEEGEEQ_DEVELOPMENT_ENVIRONMENT_SETUP.md | 3,149 | PEEGEEQ_GETTING_STARTED.md; file deleted |
| PEEGEEQ_ARCHITECTURE_API_GUIDE.md | 64,289 | Modules and layers to PEEGEEQ_ARCHITECTURE.md; API dumps, DDL, performance figures dropped; file deleted |
| PEEGEEQ_DESIGN_FUNDAMENTALS.md | 30,471 | The "when PostgreSQL is the right queue" rationale (about 3 KB) to PEEGEEQ_ARCHITECTURE.md; the `job_inbox`/`job_claim` design is dropped (PeeGeeQ does not use it); file deleted |
| PEEGEEQ_MESSAGING_FEATURES_GUIDE.md | 3,532 | PEEGEEQ_ARCHITECTURE.md; file deleted |
| PEEGEEQ_SYSTEM_MODULES_SEQUENCE_DIAGRAMS.md | 13,988 | The two finished diagrams (backfill, fan-out completion) to PEEGEEQ_ARCHITECTURE.md; the "Planned" table and empty module sections dropped; file deleted |
| PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md | 60,341 | PEEGEEQ_MESSAGING_GUIDE.md, cut to the three send modes and transaction ownership; file deleted |
| PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md | 54,125 | PEEGEEQ_MESSAGING_GUIDE.md; file deleted |
| PEEGEEQ_ORDERING_PATTERNS_GUIDE.md | 25,074 | PEEGEEQ_ORDERING_GUIDE.md (light trim; move release evidence to a link) |
| PEEGEEQ_EVENT_STORE_GUIDE.md | 3,532 | PEEGEEQ_EVENT_STORE_GUIDE.md (rewritten) |
| PEEGEEQ_KEYSET_PAGINATION_GUIDE.md | 12,965 | PEEGEEQ_EVENT_STORE_GUIDE.md; file deleted |
| PEEGEEQ_CONFIGURATION_GUIDE.md | 18,878 | Kept; name unchanged |
| PEEGEEQ_DATABASE_SETUP_GUIDE.md | 153,739 | PEEGEEQ_DATABASE_GUIDE.md (about 20 KB); test-setup sections to `docs-design/testing/`; file deleted |
| PEEGEEQ_REST_API_REFERENCE.md | 169,593 | PEEGEEQ_REST_API_GUIDE.md (about 20 KB) plus the OpenAPI file; Gap Analysis dropped, open gaps to tasks.md; file deleted |
| PEEGEEQ_OPERATIONS_GUIDE.md | 3,001 | PEEGEEQ_OPERATIONS_GUIDE.md (rewritten) |
| PEEGEEQ_TRACING_USER_GUIDE.md | 23,893 | PEEGEEQ_OPERATIONS_GUIDE.md (tracing section); file deleted |
| PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md | 36,846 | Tracing internals to PEEGEEQ_OPERATIONS_GUIDE.md; causality to PEEGEEQ_EVENT_STORE_GUIDE.md; compatibility matrix, OTel claims and the `messages`/`message_headers` schema dropped; file deleted |
| PEEGEEQ_SERVICE_MANAGER_GUIDE.md | 39,677 | Decision OD-3 |
| PEEGEEQ_FINANCIAL_SERVICES_EVENT_CATALOGUE.md | 27,641 | Deleted, or moved to `docs-design/` as a proposal (decision OD-4). It uses APIs that do not exist |
| PEEGEEQ_CONTRIBUTOR_GUIDE.md | 1,878 | PEEGEEQ_CONTRIBUTOR_GUIDE.md |
| PEEGEEQ_TESTING_GUIDE.md | 3,057 | PEEGEEQ_CONTRIBUTOR_GUIDE.md; file deleted |

## 5. Target set

| File | Contents | Est. size |
|---|---|---:|
| README.md | Index; the documentation rules of section 8 | 3 KB |
| PEEGEEQ_GETTING_STARTED.md | What PeeGeeQ provides; choosing a delivery model; local stack startup; the `Future` contract (return, compose, or observe both outcomes); caller-owned transactions; first producer and consumer, linked to `peegeeq-examples` | 12 KB |
| PEEGEEQ_ARCHITECTURE.md | Modules and layers; native, outbox, bitemporal; at-least-once; when PostgreSQL is the right queue; the two finished sequence diagrams | 20 KB |
| PEEGEEQ_MESSAGING_GUIDE.md | Producer send modes; transaction participation; consumer groups (QUEUE, PUB_SUB); subscriptions and start positions; client-side and server-side filtering; retry and dead letter; idempotent handlers | 25 KB |
| PEEGEEQ_ORDERING_GUIDE.md | Simple vs partitioned consumption; `OFFSET_WATERMARK`; partition keys; operational boundaries | 20 KB |
| PEEGEEQ_EVENT_STORE_GUIDE.md | Valid and transaction time; append, query, corrections; correlation and causation; subscriptions; keyset pagination | 15 KB |
| PEEGEEQ_CONFIGURATION_GUIDE.md | Unchanged except link fixes | 19 KB |
| PEEGEEQ_DATABASE_GUIDE.md | Provision vs connect, detach, guarded drop; migrations; templates and what they create; custom schemas; queue deletion behaviour | 20 KB |
| PEEGEEQ_REST_API_GUIDE.md | Setup lifecycle; consumption by SSE, WebSocket, webhook; error format; pagination; link to the OpenAPI file | 20 KB |
| PEEGEEQ_OPERATIONS_GUIDE.md | Lifecycle and shutdown; recovery; PostgreSQL connectivity and failover boundary; metrics; tracing and logging | 25 KB |
| PEEGEEQ_CONTRIBUTOR_GUIDE.md | Working sequence; links to coding principles, testing standards, test commands, Jenkins setup | 4 KB |

## 6. Findings to resolve

Found by reading all 24 files on 2026-11-21. The L items were checked on disk; the C items are contradictions
between documents and are certain from the text; nothing was checked against code. Each phase ticks off the items
it removes. A rewrite that drops the offending text resolves the item; record which.

### Broken links (checked on disk)

- L1 `PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md` does not exist; linked from README, Complete Guide, Examples,
  Messaging Features, Operations, Consumer Groups.
- L2 `docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md` moved to `docs-design/tasks/archive/`; linked from
  Complete Guide, Getting Started, Testing Guide.
- L3 `PEEGEEQ_BREAKING_CHANGES_CAUSATION_ID.md` does not exist; cited by the Tracing Technical Reference.
- L4 `docs/devtest/VERTX_MULTISTATEMENT_SQL_BUG_ANALYSIS.md` does not exist; cited by the Database Setup Guide. The
  real file is `docs-design/_archived/VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md`.
- L5 `peegeeq-migrations/docs/old/SCHEMA_DRIFT_ANALYSIS.md` does not exist; cited by the Database Setup Guide.
- L6 Architecture Guide links to Complete Guide anchors that no longer exist (`#your-first-message-hello-world`,
  `#configuration-management`, `#common-issues--solutions`, `#management-console`, `#integration-patterns`).
- L7 Outbox Guide image path `docs/PGQ-logo.png` resolves to `docs/docs/PGQ-logo.png`.

### Contradictions between documents

- C1 Configuration. The Configuration Guide forbids profile-only and no-argument constructors and the system-property
  and environment sweep. Contradicted by: Outbox Guide Spring setup (`System.setProperty`,
  `new PeeGeeQConfiguration(profile)`, `PeeGeeQManager.builder().withProfile`); Database Setup Guide
  (`new PeeGeeQConfiguration()` in "Production" examples); Architecture Guide config class (`builder`,
  `fromProperties`, `minPoolSize`, `deadLetterEnabled`, `jvmMetricsEnabled`); Service Manager Guide (`-D` and
  environment configuration); Tracing User Guide (`PEEGEEQ_TRACE_ENABLED`). Register defect D8 covers the code side of
  the service and sidecar entry points.
- C2 Database Setup Guide contradicts itself: Flyway does and does not create the template tables; `LIKE` is a
  one-time copy, yet existing queues "inherit automatically"; REST create "drops and recreates" the database (the REST
  reference says create refuses with 409 since 2026-07-17); manifest example and "32 SQL files" log lines do not match
  the 37-file base template.
- C3 Consumer Groups Guide lists offset/watermark mode and a DLQ as future work; the Ordering Guide records
  `OFFSET_WATERMARK` as implemented and release-gated.
- C4 REST reference: two "Delete Setup" sections with opposite meanings; missing "Per-Setup Health Endpoints"
  heading; stale Gap Analysis (recommends polling endpoints the document calls a design decision not to build; cites
  "lines 1230-1651"); event query response shows `validTo`, which the store refuses on input; main query table omits
  `aggregateId`, `sortOrder`, `afterTransactionTime`, `afterEventId`.
- C5 Delivery semantics: Architecture Guide claims "exactly-once delivery" for the outbox; the Outbox Guide claims
  consumers receive events "exactly once"; all current guides say at-least-once.
- C6 Names and values: three different load-balancing strategy lists (Architecture, REST, Service Manager);
  subscription states `ACTIVE/PAUSED/STALE` vs `ACTIVE/PAUSED/DEAD/CANCELLED`; `outbox_consumer_groups` columns
  `outbox_message_id`/`consumer_group_name` vs `message_id`/`group_name`; message priority 0-9 vs schema 1-10;
  `MessageConsumer` methods `subscribe/unsubscribe` vs `start/stop`.
- C7 Event Store API: `appendEvent`/`queryByAggregateId` (Architecture) vs 7-argument `append` (Examples, Tracing
  reference) vs builder and annotation APIs (Financial catalogue).
- C8 Versions: Outbox Guide states Vert.x 5.0.4; the root `pom.xml` sets `vertx.version` to 5.1.10 (checked
  2026-11-21; re-check before writing any version). Tracing Technical Reference header says 2.0/2026-01-06, footer says 2.1/2026-05-10.

### Examples that break the coding rules

- E1 Blocking bridges: `future.get(5, TimeUnit.SECONDS)` and JDBC `.get(...)` (Outbox Guide).
- E2 Callback style and Vert.x 4 APIs: `eventBus.request(..., ar -> ...)` and `executeBlocking(promise -> ...)`
  (Tracing Technical Reference).
- E3 Unobserved futures: `claimAndProcess().onComplete(...)`, `subscriber.connect().onSuccess(...)` (Design
  Fundamentals); `subscriptionManager.subscribe(...)` not composed with `start()` (Consumer Groups); discarded
  `eventStore.append(event)` (Financial catalogue).
- E4 Tests that pass without proof: the Examples Guide consumer test completes on send, not on receipt; Database
  Setup `testQueueOperations` completes with no assertion.
- E5 Swallowed failures: `.onFailure(Throwable::printStackTrace)` throughout the Database Setup Guide; Consumer Groups
  "permanent error, send to DLQ" handler returns success, which acknowledges the message.

## 7. Phases

One phase at a time. After each phase: run the checks in 7.8, report, and stop for the owner. Docs-only phases need
no Maven build. If a phase finds a code defect, add it to the defect table in tasks.md; do not fix code inside this
task.

### 7.1 Phase 0: decisions

Get rulings on OD-1 to OD-4 (section 9). Record them in this file. No file changes in `docs/`.

### 7.2 Phase 1: removals and link fixes

1. Delete or move the Financial catalogue (per OD-4).
2. Delete the REST Gap Analysis section. Before deleting, list each gap it calls open, check it against the code,
   and add the ones that are still true to tasks.md.
3. Delete the "Planned" table and empty module sections of the sequence-diagram file.
4. Fix L1 to L7 in the files that survive this phase.
5. Remove "PRODUCTION READY" banners and ✅ status checklists.

### 7.3 Phase 2: REST

1. Read `PeeGeeQRestServer.createRouter()` and every handler it registers. Build the route list from the code.
2. Compare it with `peegeeq-api.yaml`. Record every route missing from the YAML and every YAML path with no route.
   Known gap: the YAML does not describe the `Idempotency-Key` header (register D13).
3. If the YAML is not complete, stop and report: OD-2 depends on it.
4. Write `PEEGEEQ_REST_API_GUIDE.md` from the code. Delete `PEEGEEQ_REST_API_REFERENCE.md`.

### 7.4 Phase 3: database

1. Read the migrations, the three `.manifest` files and their SQL, `PeeGeeQDatabaseSetupService`,
   `SqlTemplateProcessor`, and the setup handler paths for create, connect, detach, and drop.
2. Write `PEEGEEQ_DATABASE_GUIDE.md`. Keep the "Queue Deletion (current behavior)" section after re-checking it.
3. Move test-setup material (`PeeGeeQTestSchemaInitializer`, integration-test patterns) to
   `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md`, re-checked against the code and fixed for E4 and E5.
4. Delete `PEEGEEQ_DATABASE_SETUP_GUIDE.md`.

### 7.5 Phase 4: messaging and architecture

1. Read the `peegeeq-api` messaging interfaces, `OutboxProducer`, the consumer-group classes, `SubscriptionManager`,
   `ServerSideFilter`, and the module poms (for the module list).
2. Write `PEEGEEQ_MESSAGING_GUIDE.md` and `PEEGEEQ_ARCHITECTURE.md`. Resolve C3, C5, C6, C7.
3. Trim `PEEGEEQ_ORDERING_PATTERNS_GUIDE.md` into `PEEGEEQ_ORDERING_GUIDE.md`.
4. Delete the source files listed in section 4.

### 7.6 Phase 5: event store and operations

1. Read `peegeeq-bitemporal` public API, `EventQuery`, `EventStoreHandler` cursor parsing, and the tracing classes in
   `peegeeq-api` (`TraceCtx`, `TraceContextUtil`, `AsyncTraceUtils`, the two converters).
2. Write `PEEGEEQ_EVENT_STORE_GUIDE.md` and `PEEGEEQ_OPERATIONS_GUIDE.md`. Resolve E2. Handle the Service Manager
   material per OD-3.
3. Delete the source files listed in section 4.

### 7.7 Phase 6: entry points

1. Write `PEEGEEQ_GETTING_STARTED.md` and `PEEGEEQ_CONTRIBUTOR_GUIDE.md`. Every example links to a real
   `peegeeq-examples` class or passes the banned-pattern grep.
2. Rewrite `README.md` with the rules of section 8.
3. Delete the remaining source files listed in section 4.

### 7.8 Checks after every phase

1. Every relative link in `docs/` resolves: list the links and test each path, file by file. Do not sample.
2. Grep the code blocks in `docs/` for the banned patterns in `.claude/CLAUDE.md` step 5. Zero results.
3. Grep `docs/` for every key listed as UNSUPPORTED in the Configuration Guide. Zero results outside that guide.
4. Grep for links into `docs/` from the rest of the repository (Java comments, `docs-design`, module READMEs, UI
   source) and fix each link that points at a deleted file.
5. Report: files changed by name (`git status` of the whole tree), finding IDs resolved, and what was not checked.

## 8. Rules for documents in `docs/`

These go into the new README in phase 6 and apply from phase 1 on.

1. One owner per fact (section 3). Other documents link.
2. No status, test counts, verification dates, or "production ready" claims. Status lives in tasks.md.
3. Performance figures appear only as dated release evidence, by link.
4. Proposals live in `docs-design/` until they are implemented.
5. Examples follow the coding principles. Prefer a link to a compiled example.
6. Every claim is checked against the code when written. A document is updated to match the code, never quoted as
   evidence of what the code does.
7. Plain declarative sentences; no slang; purposes and reasons in plain words.

## 9. Open decisions

| ID | Decision | Recommendation | Ruling |
|---|---|---|---|
| OD-1 | Removed text: delete and rely on git history, or move to `docs-design/_archived` | Delete; git history keeps it. Moving keeps the volume | Pending |
| OD-2 | REST endpoint detail: OpenAPI as the single source | Yes, once phase 2 shows the YAML matches the routes | Pending |
| OD-3 | Service Manager guide: fold into the Operations Guide, or keep as `peegeeq-service-manager/README.md` | Module README; it documents one optional module | Pending |
| OD-4 | Financial Services Event Catalogue: delete, or move to `docs-design/` as a proposal | Delete; its APIs do not exist | Pending |
| OD-5 | Add a link check to the build | Owner's call; it adds a tool | Pending |

## 10. Working constraints for the agent

- Read every file a rewrite rests on in full before writing. An excerpt is not a read.
- State nothing about runtime behaviour from static reading as fact. Label it "read in code, not run".
- Do not commit. The owner commits. Name the changed files at the end of each phase.
- `docs/README.md` names `PEEGEEQ_CONFIGURATION_GUIDE.md` and the REST reference as authorities; keep links to the
  new files working in the same phase that renames them.
- The archived register `docs-design/tasks/archive/TASKS_REGISTER_TO_2026-11-21.md` is linked from the Ordering
  Guide; keep that link.

## 11. Not covered by this plan

- No claim in `docs/` has yet been checked against the code. That checking is the main cost of phases 2 to 5.
- The target sizes are estimates.
- `docs-design/` was not read.

## 12. Progress log

| Date | Phase | Result |
|---|---|---|
| 2026-11-21 | Plan written | No `docs/` file changed |
