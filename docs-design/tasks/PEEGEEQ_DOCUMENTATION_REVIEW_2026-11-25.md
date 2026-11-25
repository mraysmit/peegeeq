# Review of docs/ — 2026-11-25

Scope: all 24 Markdown files in `docs/`. Each document was read in full and checked against the code,
the Flyway migrations, the templates, the poms, the router and the OpenAPI file. No file was edited.
No build or test was run. Line numbers are physical lines (blank lines counted).

Severity: HIGH = a reader acting on it gets a compile error, a failing call, wrong data, or a false
guarantee. MEDIUM = stale, misleading, contradictory or missing. LOW = style, duplication, minor.

Every HIGH item below was re-checked against the source by the coordinator after the reviewer
reported it, except where marked "(reviewer evidence)". Line references in the evidence column
point at the code.

---

## 0. Cross-cutting

| # | Sev | Finding | Evidence |
|---|---|---|---|
| X1 | HIGH | 10 broken file links (the other 12 link-checker hits are anchors, X2). `PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md` deleted in `97cfb21e`, still linked from README:91, COMPLETE_GUIDE:19,146, CONSUMER_GROUP:1489, EXAMPLES:17, MESSAGING_FEATURES:70, OPERATIONS:54. `DOCUMENTATION_CONSOLIDATION_LEDGER.md` moved to `docs-design/tasks/archive/`, still linked at old path from COMPLETE_GUIDE:148, GETTING_STARTED:118, TESTING_GUIDE:21. | link checker run this session |
| X2 | MEDIUM | Broken anchors: ARCH:40,41,42,1657,1746 (five anchors into COMPLETE_GUIDE that no longer exist); ORDERING:24-25 (`--` vs heading slug); SEQUENCE:49,53 (missing `1-`/`2-` prefix); OUTBOX:72-73 (emoji heading slug); REST:20 (`#per-setup-health-endpoints` has no heading). OUTBOX:6 `<img src="docs/PGQ-logo.png">` resolves to `docs/docs/`. | link checker; OUTBOX:6 |
| X3 | HIGH | Causation is described as a message attribute. MESSAGING_FEATURES:57, GETTING_STARTED:95, EVENT_STORE:18-20, TRACING_TECH:1042-1048. Queue messages carry `correlationId` only. | `causation_id` only on `bitemporal_event_log` (V001:475, V011:68,156); zero hits in peegeeq-native/outbox main; `MessageProducer.java:46-76` |
| X4 | HIGH | Exactly-once claims contradict the at-least-once contract. ARCH:1710, OUTBOX:475. | CONSUMER_GROUP:895, MESSAGING_FEATURES:19, ORDERING:69,155; `StuckMessageRecoveryManager` resets PROCESSING→PENDING |
| X5 | HIGH | NOTIFY coalescing reason is wrong. FUND:455, ARCH:1702, ORDERING:92 say every insert sends the same payload. The payload is the row id. ORDERING:100 names a `STANDARD` consumer mode that does not exist. | V001:334 `pg_notify('peegeeq_' \|\| NEW.topic, NEW.id::text)`; `PgNativeQueueProducer.java:199`; `ConsumerMode`: LISTEN_NOTIFY_ONLY, POLLING_ONLY, HYBRID |
| X6 | HIGH | Removed SSE route `GET /api/v1/queues/:setupId/:queueName/stream` documented as live: REST (12 places, e.g. 1628, 1699-1715, 4465-4496), ARCH:1644. | `PeeGeeQRestServer.java:417-419` "replaces the removed consuming /stream"; live route is `/messages/stream` |
| X7 | HIGH | Removed route `POST /api/v1/database-setup/:setupId/eventstores` documented: REST:410, ARCH:1566. | `PeeGeeQRestServer.java:388-389` commented out |
| X8 | HIGH | `new PeeGeeQConfiguration()` / profile-only constructor / `builder()` used: DB_SETUP:3233,3304,3387; OUTBOX:828; ARCH:714-744. | Only `(String, Properties)` at :74 and the 7-arg at :110 |
| X9 | HIGH | Example classes that do not exist: ARCH:310-329 lists 16; `peegeeq-examples/pom.xml` binds exec executions to 16 of 20 main classes that do not exist. | `find` returns 0 for PeeGeeQExampleRunner, PeeGeeQSelfContainedDemo, PeeGeeQExample, BiTemporalEventStoreExample, ConsumerGroupExample, RestApiExample, MultiConfigurationExample, TransactionalBiTemporalExample, ServiceDiscoveryExample, NativeVsOutboxComparisonExample, AdvancedConfigurationExample, MessagePriorityExample, EnhancedErrorHandlingExample, SecurityConfigurationExample, PerformanceTuningExample, IntegrationPatternsExample |
| X10 | HIGH | Documented test commands run zero tests because the tag's profile is missing: TRACING_USER:102,784 (`@Tag("smoke")`), CONSUMER_GROUP:76,1482 (`@Tag(INTEGRATION)`). | root `pom.xml:109-110` `test.groups=core`, `test.excludedGroups=integration,performance,slow` |
| X11 | HIGH | Stale schema. `queue_metrics`, `connection_pool_metrics` shown as current: ARCH:1078-1097, DB_SETUP:469,507,1719,1838-1840,3671-3674. `outbox_consumer_groups(outbox_message_id, consumer_group_name)`: ARCH:1063-1075, DB_SETUP:3297. | V019:14-16 drops both tables; V010:104,117-121 renames the columns to `message_id`, `group_name` |
| X12 | MEDIUM | Vert.x version stale: "Vert.x 5.0.4" OUTBOX:10,93,190,1322,1331,1416,1482,1667. Java 23 in DB_SETUP:1786. | `pom.xml:81` 5.1.10; `pom.xml:77` release 27 |
| X13 | MEDIUM | `docs/README.md:14` requires REST reference, OpenAPI and router to align. They do not: OpenAPI lists 17 operations that are not registered and omits about 58 registered routes. | `peegeeq-openapi/src/main/resources/peegeeq-api.yaml` vs `PeeGeeQRestServer.java:347-545` (reviewer evidence) |
| X14 | LOW | Prose style breaks the project rules in OUTBOX, REST, DB_SETUP, SERVICE_MANAGER, TRACING (emoji headings or status markers, marketing tone, "Happy Path", "PRODUCTION READY", "Final Truth", first-person). MESSAGING_FEATURES is clean; EVENT_STORE and the small entry docs have minor issues only (weak "should" for requirements, filler words). | per-doc sections |
| X15 | LOW | Header versions vary (0.1 BETA, 0.9, 1.0, 1.1, 1.1.0, 1.2, 2.0, 2.1). Copyright year is 2025 on all. | first three lines of each file |

---

## 1. PEEGEEQ_DATABASE_SETUP_GUIDE.md (4065 lines)

The most defective guide after the REST reference. Most setup samples do not compile, and the schema
model it teaches (two default schemas, three DDL sources) is not the one the code implements.

HIGH
- H1 `DatabaseSetupRequest.Builder` (177, 1511, 2184, 2786, 3206, 3248, 3328, 3412) does not exist. Only constructors (`DatabaseSetupRequest.java:33,49,58`).
- H2 "Three sources of truth" / embedded JDBC DDL in `PeeGeeQTestSchemaInitializer` (38, 491-495, 584-619, 698-716, 1676-1685, 2284-2292, 3572-3575). The initializer runs Flyway (`PeeGeeQTestSchemaInitializer.java:35,112-114`). Component selection does not limit what is created.
- H3 `SchemaComponent.METRICS` (2303, 2357) does not exist. The enum is nested.
- H4 "By default, PeeGeeQ uses two schemas" `peegeeq` + `bitemporal` (60-71, 236, 976-979, 1879-1884, 2235, 3641-3667; `bitemporal.` qualifiers at 1399-1416, 2883, 3396, 3440). One configured schema, no default (`DatabaseConfig.java:34,58`).
- H5 V001 described as `CREATE SCHEMA peegeeq; CREATE TABLE peegeeq.queue_messages`, extensions, template tables (304-321, 839, 969-979). V001 has zero `CREATE SCHEMA`, `CREATE EXTENSION` or `peegeeq.` hits.
- H6 Migration runner flags and `FLYWAY_SCHEMAS` (99, 113-114, 1812-1815); CI and docker samples without `DB_SCHEMA`. `RunMigrations.java:80-83` reads env vars only; `DB_SCHEMA` is required.
- H7 "V001 through V018 (11 migrations)" (465). 14 scripts exist, V001 and V010-V022. V019 drops the metrics tables and `cleanup_old_metrics`.
- H8 "`createDatabaseFromTemplate()` drops and recreates the database" (2399-2404). It refuses (`DatabaseTemplateManager.java:77` "Create is NON-DESTRUCTIVE"). The whole workaround section rests on the false premise.
- H9 `destroySetup` drops the database (2129, 3958, 3769). It only closes resources; `dropSetupDatabase` drops (reviewer evidence, `PeeGeeQDatabaseSetupService.java:902-979`).
- H10 Database names `"test_db_" + UUID.randomUUID()` (171, 2160, 2369, 3880) fail `^[a-zA-Z_][a-zA-Z0-9_]*$` (`PostgreSqlIdentifierValidator.java:61`).
- H11 REST create sample has no `schema` (2416-2425); handler throws "schema is required" (`DatabaseSetupHandler.java:646`).
- H12 `subscriptionService.subscribe(topic, group, options)` (2464) does not exist; needs `SubscriptionStore`.
- H13 `new PeeGeeQRestServer(port, setupService)` (2414, 2506); only `(RestServerConfig, DatabaseSetupService)` at :114.
- H14 `new PeeGeeQConfiguration()` (3233, 3304, 3387). See X8.
- H15 `PeeGeeQEventStoreFactory` (2880, 3391) does not exist; `BiTemporalEventStoreFactory(vertx, manager)`.
- H16 `append("order-123", "OrderCreated", Instant.now(), payload)` (3400-3405); signature is `append(eventType, payload, validTime)`.
- H17 `QueueConfig.Builder.retryDelayMillis` (2638) does not exist.
- H18 `DatabaseSetupResult.getConnectionUrl()` (2804-2806) always returns null (`DatabaseSetupResult.java:25`).
- H19 `manager.getHealthService()` (4038) does not exist; `getHealthCheckManager()`.
- H20 Flyway Maven config (1981-1998: version 10.23.0, `<schemas>`, `classpath:`), `mvn flyway:clean` (2012, 3519, 3916, 4037), `application.properties` (80), `flyway.conf` (334), `application-dev.properties` (2068). Pom uses 11.7.2, `filesystem:`, `cleanDisabled=true`, url/user/password in profiles only; the files do not exist (reviewer evidence).
- H21 `docker-compose.dev.yml` and `docker build` command misdocumented (1919-1950, 3528) (reviewer evidence).
- H22 Test samples use raw JDBC and hand-written DDL (606-618, 759-765, 2805-2816, 3274-3279, 3350-3361, 3438-3450). The testing standard rates this HIGH (`PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md:841-846`).

MEDIUM (reviewer evidence, spot-checked)
- M1 Template counts wrong (37/38/32 files); `base/.manifest` lists 40. `registry/`, `eventstore-aggregate-summary/`, `04d`, `10a`, `10b` omitted.
- M2 Template SQL samples differ from files (unquoted names, `peegeeq.queue_template`, wrong index names).
- M3 Manifest example names files that do not exist (`02a-schema-peegeeq.sql`, `03a-table-queue-template.sql`, …).
- M4 "One statement per file" and "use IF NOT EXISTS" rules contradicted by shipped templates.
- M5 "Internal code always uses fully qualified names" (396) is false; code relies on `search_path`.
- M6 "Fallback to minimal schema without extensions" (3845, 3943) is unreachable code.
- M7 `addQueue` is not idempotent; no existence check, trigger template lacks IF NOT EXISTS.
- M8 Per-queue object counts ignore indexes copied by `LIKE … INCLUDING ALL`.
- M9 "Queue identity is in-memory only" (3119-3120) is false; `peegeeq_object_registry` persists it, and delete does not remove the row.
- M10 Contradictory statements on which path production uses.
- M11 `message_processing` and `bitemporal_event_log` attributed to the setup service; base template creates neither.
- M12 Flyway "rollback capability" claimed; no rollback command.
- M13 V001 "idempotent (safe to re-run)" is false.
- M14 References to files that do not exist (`SCHEMA_DRIFT_ANALYSIS.md`, `PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql`, …) and a stale "NO CRITICAL DRIFT" status.
- M15 `SchemaContractTest` sample does not match the test.
- M16 Create flow uses setupId as database name and `Map.of()`; code uses `getDatabaseName()` and `Map.of("schema", …)`.
- M17 Blocking Flyway/JDBC called inside Vert.x callbacks.
- M18 Hand-rolled `PostgreSQLContainer<>` instead of `PostgreSQLTestConstants.createStandardContainer()`.
- M19 Java 23 in CI sample; build requires 27.
- M20 Quoted log messages do not exist.

LOW: `.onFailure(Throwable::printStackTrace)` (6 places); assertion-free tests (2214-2221, 2341-2344); undefined names in snippets; stale line count "1148 lines" (file is 1977); template tree duplicated 3×; TOC out of order; unsourced throughput figures.

---

## 2. PEEGEEQ_REST_API_REFERENCE.md (5466 lines)

Route coverage: router 105 HTTP + 3 WebSocket; OpenAPI 65 operations; doc 103 + 3. Response
bodies for most resource groups do not match the handlers.

HIGH
- H1 Removed `/stream` SSE route documented in 12 places (X6). Live `/messages/stream` events: `connected, heartbeat, subscribed, message, error`; no `consumerGroup`/`autoAck`.
- H2 Removed `/database-setup/:setupId/eventstores` (410) documented; live `POST /setups/:setupId/queues` and `/eventstores` (:400, :402) not documented.
- H3 Create-setup body (64-101) omits required `databaseConfig` and `schema` (`DatabaseSetupHandler.java:615,646`). Doc 159 itself refers to `databaseConfig.password`.
- H4 Get Setup Details response wrong (573-605) (reviewer evidence).
- H5 `implementationType` values `peegeeq-native|peegeeq-outbox` (3 places); code returns `native`/`outbox` (`PgNativeQueueFactory.java:276`, `OutboxFactory.java:346`). OpenAPI repeats the wrong enum.
- H6 Queue consumers response fields wrong (803-828) (reviewer evidence).
- H7 Create Consumer Group: `maxMembers`, `loadBalancingStrategy`, `sessionTimeout` documented as request fields (1183-1200); handler only writes them to responses (`ConsumerGroupHandler.java:171-173`), reads `groupName`, `subscriptionOptions`, `groupFilter` (104-123). Doc 1262 says the group is created when subscription fails; the handler fails the request.
- H8 Member fields `memberId` etc. wrong; code uses `consumerId` (reviewer evidence). `ConsumerGroupHandlerTest.java:180-231` asserts JSON it builds itself and never calls the handler.
- H9 Subscription-option update with `FROM_NOW` on an existing subscription returns 409 (doc 1542-1548, 1783-1789 show it working). Delete cancels the subscription; doc says it reverts to defaults (reviewer evidence).
- H10 Webhook `retryPolicy`, filtering, reachability check, 422 documented (1866-1960); none implemented. Response fields wrong (reviewer evidence).
- H11 DLQ list returns a bare array with different fields; `sortBy`/`sortOrder` not read; `stackTrace`, `failureHistory`, `newMessageId` do not exist (reviewer evidence).
- H12 Subscription lifecycle responses wrong; states are ACTIVE, PAUSED, DEAD, CANCELLED, STOPPED, not `ACTIVE|PAUSED|STALE` (reviewer evidence).
- H13 Event store: correction example (3504) sends `validTo`, which doc 3120 says is refused with 400; `CorrectionRequest.java:28-34` has no `validTo`. Correction returns 201 with `originalEventId`, `correctionEventId`, `version`, `transactionTime`, `correctionReason` (`EventStoreHandler.java:1124-1136`); doc says 200 with `eventId`, `newVersion`, `timestamp`. Event objects use `eventId`, not `id`. Reconcile `mode` has no default.
- H14 Health responses wrong; states are HEALTHY, DEGRADED, UNHEALTHY (`ComponentHealthState.java:33-43`), not `UP|DOWN|DEGRADED`.
- H15 Management overview fields wrong; `messagesPerSecond` was removed 2026-08-09 (reviewer evidence).
- H16 Update Queue documented as 200; always returns 501 (`ManagementApiHandler.java:1111-1112`).
- H17 WebSocket protocol wrong: key is `type` (`ping, subscribe, unsubscribe, configure`), data frames are `"data"`, stream uses a browser tail, not `createConsumer()` (reviewer evidence).

MEDIUM
- M1 Undocumented routes: `/messages/stream`, `/stats/stream`, `/setups/:setupId/db-telemetry`, the two `/setups/...` create routes.
- M2 Undocumented bodies/events: `GET .../messages`, consumer-alert summary, `/sse/queues`, `/sse/metrics`, `/ws/monitoring`.
- M3-M5 Queue details/stats/purge/pause/resume/delete response fields missing or wrong.
- M6 `messageId` described as UUID; it is the correlation ID (doc 4835 says so). 413/256KB limit and `delaySeconds` 0-900 range not enforced.
- M7 Batch `failures[]` and 503 on `failOnError` undocumented.
- M8 Visibility timeout default 30 (7 places) and 300 (4730); code default is 5 minutes (`QueueConfig.java:46,73`).
- M9 Delete Setup documented twice with contradictory semantics.
- M10-M13 Conflict codes, event-store delete, query parameters and management list fields wrong or missing.
- M14 `traceparent`, `tracestate`, `baggage`, `Idempotency-Key` headers undocumented; CORS allows only `Content-Type`, `traceparent`, `Authorization`.
- M15 Error format omits `code`, `details`, `statusCode`; 503 missing from the status list.
- M16 `LEAST_LOADED` strategy (4697-4699, 5390) does not exist (ROUND_ROBIN, RANGE, STICKY, RANDOM). `MessageConsumer.start()/stop()` do not exist.
- M17 Gap Analysis section (5000-5440) is stale and contradicts the body in at least 12 places.

LOW: emoji headings at 5400-5425; truncated heading "Phase 1: es (Blockers)" at 5436; duplicated tables; message strings differ.

---

## 3. PEEGEEQ_ARCHITECTURE_API_GUIDE.md, PEEGEEQ_DESIGN_FUNDAMENTALS.md, PEEGEEQ_SYSTEM_MODULES_SEQUENCE_DIAGRAMS.md

HIGH
- H1 ARCH:763-1370 Filter Error Handling section documents an API that does not exist (`addTransientErrorPattern`, `AsyncFilterRetryManager`, `ErrorClassification`, `classifyError`, `DeadLetterQueueManager(config)`). Zero definitions in any `src/main`.
- H2 ARCH:464,474 `Message<T>.getPriority()`, `getCorrelationId()`. `Message.java` has four methods: `getId`, `getPayload`, `getCreatedAt`, `getHeaders`.
- H3 ARCH:517 `QueueFactory extends AutoCloseable`, `boolean isHealthy()`, `void close() throws Exception`; ARCH:486,1464 `QueueFactoryProvider.getInstance()`. Code: plain interface, `Future<Boolean> isHealthy()`, `Future<Void> close()`; no `getInstance`.
- H4 ARCH:609-636,1839-1842 `EventStore.appendEvent`, `queryByAggregateId`, `queryByTimeRange`, `correctEvent`. None exist on `EventStore`. `BiTemporalEventStore<T>` has no file.
- H5 ARCH:714-760 `PeeGeeQConfiguration.builder()`/`fromProperties` and `ConsumerConfig` fields invented.
- H6 ARCH:1378-1410 `StuckMessageRecoveryManager(DataSource, …)`, synchronous returns; code takes `Pool` and returns `Future`.
- H7 ARCH:1498 `connectionProvider.getPool()` does not exist.
- H8 ARCH:1566-1653 REST endpoints wrong or removed (X6, X7; `/messages/next`, `DELETE …/messages/{id}`, `/consumer-groups/{setupId}`).
- H9 ARCH:1710 exactly-once (X4).
- H10 FUND:453-455, ARCH:1702 NOTIFY coalescing (X5).
- H11 ARCH schema stale (X11).
- H12 ARCH:310-329 sixteen example classes that do not exist (X9). "Coverage: 95-98%" unsourced.
- H13 SEQ:17 marks group fetch and fan-out completion "Done". `CompletionTracker` is in `src/test` (SEQ:179 says `src/main`). `ConsumerGroupFetcher` has no production caller.
- H14 SEQ:109 "backfill is best-effort". `SubscriptionManager.java:222-236` `.onFailure` only logs, so a backfill failure fails `subscribe`.
- H15 FUND:174-216, 816-841 present `job_inbox`/`job_claim` as "the correct design" and PeeGeeQ's own status-update lifecycle as "a future incident". PeeGeeQ implements status tables; zero `job_inbox` hits.

MEDIUM
- M1 ARCH:135 "19 modules"; root pom lists 20. `peegeeq-pg-failover`, `peegeeq-utilities-ui` undocumented.
- M2 ARCH:108-116 dependency arrows reversed (api depends on nothing; rest depends on runtime).
- M3 ARCH:1696 "advisory locks" in native queue; code uses `FOR UPDATE SKIP LOCKED`.
- M4 Missing columns (`idempotency_key`, `required_consumer_groups`, `completed_groups_bitmap`) and 9 missing tables.
- M5 WebSocket and SSE event types wrong.
- M6 Spring Boot auto-configuration and Actuator claimed; none exist.
- M7 `@CircuitBreaker` annotation; API is programmatic.
- M8 REST client interface and class roles swapped.
- M9 Service-manager strategy list incomplete.
- M10 SEQ marks benchmarking "Implemented" with no diagram.
- M11 SEQ:295 test path wrong (file is in peegeeq-benchmarking).
- M12 FUND says fan-out and replay are poor fits; PeeGeeQ ships both. FUND:35 says the outbox needs CDC and a broker.
- M13 `StuckMessageRecoveryManager` and `PgConnectionProvider` placed in wrong modules.

LOW: unobserved Futures and dropped errors in FUND samples; SLF4J `{:.2f}` syntax; broken sentence FUND:13; "All four operations" lists three (FUND:31); first-person rhetoric FUND:788-859.

---

## 4. PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md, PEEGEEQ_ORDERING_PATTERNS_GUIDE.md, PEEGEEQ_MESSAGING_FEATURES_GUIDE.md

HIGH
- H1 OUTBOX:488-498 (and 292-294, 459-463, 597-670, 957-977, 1135-1153) says chained `sendInOwnTransaction(..., CONTEXT)` calls share one transaction with business writes. `OutboxProducer.java:224-229`: "does NOT participate in any caller-initiated transaction … use sendInExistingTransaction". This is the guide's central claim.
- H2 OUTBOX:115,370,1342 "graceful fallback" off a Vert.x context. `OutboxProducer.java:310-314` fails with `IllegalStateException`. Spring samples call it from Spring threads.
- H3 OUTBOX:762-768 `PeeGeeQManager.builder()`, `manager.getQueueFactory(OutboxQueueFactory.class)`; neither exists. OUTBOX:517,692 `future.get(5, TimeUnit.SECONDS)`; not on Vert.x 5 `Future`, and a banned blocking bridge.
- H4 OUTBOX:826,854-863 `System.setProperty("peegeeq.database.host", …)`. The loader does not read system properties (CONFIGURATION_GUIDE:48).
- H5 OUTBOX:676-696 Vert.x insert "joins" a JDBC transaction. No JDBC bridge exists.
- H6 OUTBOX:712-739 performance table attributed to `ReactiveOutboxProducerTest`, which has no timing code. Figures repeat at 9 places and contradict each other.
- H7 ORDERING:92,100 — see X5.

MEDIUM
- M1 ORDERING does not say OFFSET_WATERMARK reads only the `outbox` table (`PartitionedFetcher.java:115-141`).
- M2 ORDERING:256,401 `message.getMessageGroup()` is not on `Message<T>`; it is on `SimpleMessage`.
- M3 OUTBOX:469,475 ordering and exactly-once guarantees (X4).
- M4 OUTBOX:399-443 recovery describes a broker, `SENT`/`PROCESSED` statuses, PENDING-based stuck detection and exponential backoff. None match the code.
- M5 OUTBOX:1207-1226 consumer side called "Future Enhancement" with a `ReactiveOutboxConsumer` that does not exist.
- M6 OUTBOX JDBC backward-compatibility claims; JDBC paths were removed.
- M7 OUTBOX:1366-1410,1503-1554 internals (`getOrCreateSharedVertx`, `PgBuilder.pool()`, `executeOnVertxContext`) do not exist; INSERT omits `idempotency_key`.
- M8 Producer idempotency key (`idempotencyKey` header, `ON CONFLICT DO NOTHING`) undocumented.
- M9-M10 Spring controllers return `io.vertx.core.Future`; "Zero Vert.x exposure" contradicts OUTBOX:776.
- M11 MESSAGING_FEATURES:57 causation (X3).
- M12 Vert.x 5.0.4 (X12).
- M13 Hand-rolled retry with undefined `sendWithRetry`; Resilience4j `executeSupplier` misuse.
- M14 Blocking `orderRepository.save` on the event loop.

LOW: ORDERING test descriptions differ from `PartitionedOrderingDemoTest`; unobserved `subscribe`/`start` Futures; `.transform(result -> result.succeeded() …)` turns failures into success; emoji and marketing tone in OUTBOX ("APPROVED FOR PRODUCTION USE", "Happy Path"). MESSAGING_FEATURES style is clean.

---

## 5. PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md, PEEGEEQ_KEYSET_PAGINATION_GUIDE.md, PEEGEEQ_EVENT_STORE_GUIDE.md, PEEGEEQ_FINANCIAL_SERVICES_EVENT_CATALOGUE.md

HIGH
- H1 CG:146,614,867,1012,1020,1025 `subscriptionManager.subscribe(topic, group, options)` does not compile; needs `SubscriptionStore` (`SubscriptionManager.java:162,189`).
- H2 CG:1003 `updateTopic("orders.events", TopicConfig…)`; signature is `updateTopic(TopicConfig)`.
- H3 CG:557,1195,1275 "Heartbeat is sent automatically". The only `updateHeartbeat` caller in `src/main` is the REST handler (`SubscriptionHandler.java:335`).
- H4 CG:852-853,884,887,1397 a heartbeat resurrects DEAD to ACTIVE. `SubscriptionManager.java:894-898`: "A heartbeat does not make a DEAD subscription ACTIVE"; SQL filters `IN ('ACTIVE','PAUSED')` (:933).
- H5 CG:1437 a CANCELLED subscription can rejoin. `SubscriptionManager.java:426` "CANCELLED is terminal".
- H6 CG:1205-1209,1291-1295 recovery by calling `start(options)` again does nothing (native) or fails (outbox).
- H7 CG:577-662 `start()` without options is said to apply FROM_NOW and to be equivalent to subscribe-then-start. Only `start(SubscriptionOptions)` applies a start position (reviewer evidence).
- H8 CG:143,600,658,1094,1108 FROM_BEGINNING "processes all historical messages". Default `backfillScope = PENDING_ONLY` (`SubscriptionOptions.java:201`).
- H9 CG:562,758,1202 second constructor argument is a pool service ID, not a component name.
- H10 CG:76,1482 test commands run zero tests (X10).
- H11 CG:1151-1160, 449-470 samples do not compile (out-of-scope variable, six redeclarations).
- H12 KP:31-32,123-124,201-204 "pages never overlap and never skip, regardless of concurrent writes". `transaction_time` is `OffsetDateTime.now()` from the JVM before commit (`PgBiTemporalEventStore.java:355,515`), and page 1 orders by `transaction_time, valid_time` (:986) while cursor pages order by `transaction_time, event_id` (:980). The guarantee does not hold as stated.
- H13 FS:453-690 code samples use 14 types and methods that do not exist and are not labelled as illustrative.
- H14 FS:90-92,189,247,536,563 naming rules (kebab-case, 72-character types, trailing-dot patterns) are rejected by bi-temporal subscriptions (`ReactiveNotificationHandler.java:70-71`: max 50 characters, alphanumeric/underscore segments).

MEDIUM
- M1 CG:1461-1472 "Limitations & Future Work" lists DLQ, OFFSET_WATERMARK and metrics as missing; all exist (V016, V017, `PeeGeeQMetrics`).
- M2 State model omits STOPPED (V021).
- M3 DEAD timing: needs timeout plus 3 consecutive misses (V015), not "after 5 minutes".
- M4 Zero-subscription write blocking is not enforced on the producer path.
- M5 Schema excerpts stale (V010, V015, V016, V021 columns).
- M6 "≤16 consumer groups per topic" has no basis in code.
- M7 Example citations name test methods that do not exist.
- M8 "Custom predicate" sample registers a competing consumer, not a filter.
- M9 "Send to DLQ" sample returns `succeededFuture()`, which acknowledges the message.
- M10 Opening example contradicts the FAQ on QUEUE vs PUB_SUB.
- M11 Unobserved and unordered Futures in samples.
- M12 `TradeEvent` shape matches neither repository class.
- M13 EVENT_STORE:18-20 causation across messaging (X3).
- M14 FS contradicts the financial-fabric example (event type names, Avro vs JSON, headers).
- M15 KP:250-251 test counts presented as current status without a date.

LOW: "candidate for integration" status text; first-person "I recommend" (FS:96); planning estimates in a user doc (FS:696-718); emoji in prose and log strings.

---

## 6. PEEGEEQ_SERVICE_MANAGER_GUIDE.md, PEEGEEQ_CONFIGURATION_GUIDE.md, PEEGEEQ_OPERATIONS_GUIDE.md

The service manager guide describes configuration, deployment, metrics and logging that do not
exist or are not wired. The configuration guide matches the loader: zero undocumented keys, zero
documented-but-unread keys, zero wrong defaults, correct precedence; ten rows are misclassified or
incomplete.

HIGH (service manager)
- H1 System properties, env vars, `config.json` (630-681, 1284-1353). `main` reads positional args only (`PeeGeeQServiceManager.java:263-265`); zero `System.getProperty`/`getenv` in the module.
- H2 `java -jar … -Dconsul.host=…` and Dockerfile (753-776). No shade/assembly plugin; args after `-jar` go to `parseInt(args[0])`; no Dockerfile; `-XX:+UseCGroupMemoryLimitForHeap` removed in JDK 11.
- H3 Load balancing, retry with backoff, health monitor (85-95, 800-892). `HealthMonitor` and `ConnectionRouter` are never instantiated; router wires only registration and federation handlers.
- H4 Route list (192-217, 538-591, 891, 920-929, 1172) includes `GET /instances/{id}`, `/metrics`, `/health/check`, `/ws/updates`; none registered. 404 and 503 never sent.
- H5 Almost every response body wrong (123-535) (reviewer evidence).
- H6 "Instances register themselves on startup", "deregistered after 30 seconds" (79-81). No self-registration outside the module; zero `DeregisterAfter` hits.
- H7 "Current Status: PRODUCTION READY … All tests passing (100%)" (1468-1481) against header "Version 0.1 (BETA)". License says MIT (1485); root `LICENSE` is Apache 2.0.
- H8 Java client classes and Micrometer metrics do not exist (931-949, 1254-1274).

HIGH (configuration)
- H9 `pipelining.enabled`, `worker.pool.size`, `verticle.instances` listed EXTERNAL (123, 126, 128). Owner `VertxPerformanceOptimizer` has no production caller.

MEDIUM
- M1 `peegeeq.metrics.reporting-interval` listed UNSUPPORTED but still validated (< 1 s fails construction).
- M2 `pipelining.limit` clamped 8..256 by one owner and unclamped (default 1024) by another; `event.loop.size` clamped 2..32; clamps undocumented.
- M3 Pool timeouts and `shared` are hard-coded in the bitemporal pool.
- M4 Profiles ship `logging.level.*` keys outside the inventory; the contract test skips them.
- M5 `peegeeq-examples` ships same-named profile files with keys the loader does not read; classpath shadowing risk.
- M6 Validation section: empty password only warns; type errors throw `IllegalArgumentException` immediately; value formats undocumented.
- M7 OPERATIONS:33-35 names Patroni; omits `peegeeq-pg-sidecar` and the not-built status of `peegeeq-pg-failover`.
- M8 OPERATIONS:59-60 cites `docs-design/tracing-observability`, which does not exist.
- M9 Grafana dashboard metric names do not match the meters; `peegeeq-performance-test-harness` holds only logs.
- M10-M13 Service manager: CORS and TLS "configurable" (hard-coded/absent); `/ui/*` serves a missing `webroot`; `status` filter only matches healthy; project tree and test names do not exist; cross-doc conflicts with DB_SETUP and OUTBOX (X8, OUTBOX H4).

LOW: service manager duplicated blocks and marketing tone; JSON fenced as `java`; configuration guide "Reconciled: August 29, 2026" predates changes it describes.

---

## 7. PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md, PEEGEEQ_TRACING_USER_GUIDE.md

HIGH
- H1 TR:744-781, UG:520-523 `messages` and `message_headers` tables. They do not exist; trace context is a key in the `headers` JSONB column.
- H2 TR:223,397,424,670 `executeBlocking(promise -> …)`, TR:600 `eventBus.request(…, ar -> …)`. Vert.x 4 callback forms; do not compile on 5.1.10.
- H3 TR "Implementation" blocks for `mdcScope`, `executeBlockingTraced`, `publishWithTrace`, signatures and the converter differ from the code (reviewer evidence).
- H4 UG:532 `TraceContextUtil.generateSpanId()` does not exist.
- H5 UG:509,645 `producer.send(payload, correlationId)` does not exist; overloads are `send(T)`, `(T, Map)`, `(T, Map, String)`, `(T, Map, String, String)`.
- H6 UG:76-86,726-773 consumes PeeGeeQ queue messages through `AsyncTraceUtils.tracedConsumer`, which is a Vert.x event-bus consumer (`AsyncTraceUtils.java:263-264`). JAX-RS annotations in a Vert.x Web project.
- H7 UG:102,784 test command runs zero tests (X10).
- H8 UG:96,386,399 log searches cannot match the configured pattern (`correlationId` not in the pattern; `[trace=-]` never written).
- H9 UG:476-477 `PEEGEEQ_TRACE_ENABLED`, `LOG_LEVEL`; zero readers in code.

MEDIUM
- M1 TR:886-933 checklist marks unimplemented items done. No production class outside the tracing package calls `executeBlockingTraced`, `publishWithTrace`, `requestWithTrace` or `tracedConsumer`. "logback.xml registers converters" per module: the only `src/main` logback file is in `peegeeq-pg-sidecar`.
- M2 "If it's not wrapped, it's wrong" contradicted by 6 production call sites.
- M3 "SecureRandom" (TR:282-283,789); code uses `ThreadLocalRandom` (`TraceCtx.java:93`). Parsing validation overstated.
- M4 Consumers do not create a child span except with a consumer group.
- M5 "No trace bleed" not supported; context is set and never cleared (static reading).
- M6 TR:1042-1048 causation on outbox messages (X3); "starts a NEW trace" contradicts UG:772.
- M7 Event-store schema section stale (`event_store` table name, index names, "v1.1.0").
- M8 `tracestate` and `baggage` propagation undocumented.
- M9 MDC field table incomplete (`consumerGroup` missing).
- M10 Performance table, OTel compatibility matrix and X-Ray sample have no basis in code.

LOW: unobserved Futures; OTel API name wrong; logback XML duplicated 4×; marketing tone ("Final Truth", "Tracing is automatic!").

---

## 8. Entry-point and contributor docs

README, GETTING_STARTED, COMPLETE_GUIDE, EXAMPLES_GUIDE, DEVELOPMENT_ENVIRONMENT_SETUP, CONTRIBUTOR_GUIDE, TESTING_GUIDE.

HIGH
- H1 DEV_ENV:50,57,62,66,109,115-119 port 8080. Server listens on 8088 from `conf/rest-server.json:2`; vite proxies to 8088; `-Dexec.args` is ignored (`StartRestServer.java:80`). `StartRestServer.java:127-128` prints a hard-coded 8080 (code defect).
- H2 DEV_ENV:101 "E2E tests require the Backend (Step 2) to be running". `PEEGEEQ-TEST-COMMANDS.md:303` "No manual backend start is needed"; global setup may kill a mismatched backend.
- H3 EXAMPLES_GUIDE names no example class, module or command, while GETTING_STARTED:61-66 and README:24 say it owns them.
- H4 EXAMPLES_GUIDE:162-172 canonical test completes on `send` success; it passes when no message is received. Violates `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md:136-137,445-446`.
- H5 EXAMPLES_GUIDE:69-70 return type `Future<BiTemporalEvent<PaymentProcessed>>` from an `EventStore<Object>`; does not compile.

MEDIUM
- M1 Retry sample contradicts `pgq-coding-principles.md:368` (use `CircuitBreakerManager`).
- M2 README and GETTING_STARTED call COMPLETE_GUIDE "preserved legacy detail"; it is a portal with no unique content and its inbound anchors are dead.
- M3 COMPLETE_GUIDE:136-137 precedence (docs/ above docs-design/) contradicts README:86-87 and TESTING_GUIDE.
- M4 Causation on messages (X3).
- M5 DEV_ENV omits the rebuild step, root-level start command, and that the database is reached only through a setup.
- M6 README index entries overstate content (Operations "capacity guidance", Event Store "durable replay and pagination", Testing "profiles", Contributor "build, Jenkins").
- M7 JDK 27 + toolchains, Node 22.12.0, Jenkins as the only active CI not stated.
- M8 TESTING_GUIDE omits `pgq-coding-principles.md` as a normative source; status date 2026-10-06 is stale.

LOW: outbox sample passes `orderId` as `messageGroup` unnamed; README "scheduled for integration" for a PROPOSED task; DEV_ENV bash/`docker-compose` for a PowerShell-only command set.

---

## 9. Code defects found during the review (outside docs/)

- `peegeeq-examples/pom.xml:254-481` binds exec executions to 16 main classes that do not exist.
- `StartRestServer.java:127-128` prints `http://localhost:8080/health` while the server binds 8088.
- `ConsumerGroupHandlerTest.java:180-231` asserts JSON built in the test; it never calls the handler (reviewer evidence).
- `peegeeq-openapi/.../peegeeq-api.yaml` is not aligned with the router (X13).
- `db/templates/base/08c-consumer-table-groups.sql:6` CHECK omits `DEAD_LETTER`, which V016 adds (reviewer evidence).
- `BackfillService.java:114` javadoc says IN_PROGRESS resumes; lines 437-444 return SKIPPED (reviewer evidence).
- REST CORS allow-list blocks `Idempotency-Key`, `tracestate`, `baggage` from browser clients (static reading).

---

## 10. Not checked

- No build, test, server, Flyway, Docker or Consul run. All runtime statements are static readings: H7 and H12 in section 5, M5 in section 7, M7-M10 in section 1, and the CORS item in section 9.
- External library semantics taken from knowledge, not fetched: PostgreSQL NOTIFY folding, logback `%X{key:-}` default, Resilience4j `executeSupplier`, Spring MVC return-value handling, OTel API names, X-Ray ID format, JDK flag removal.
- OpenAPI component schemas compared only for `DatabaseSetupRequest`; not field by field.
- Mermaid rendering of the sequence diagrams.
- Docker image tags against a registry.
- MEDIUM and LOW items marked "reviewer evidence" were spot-checked, not each re-read by the coordinator.
