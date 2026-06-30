# CRITICAL: Schema Processing Gaps — Findings and Remediation Tasks

## Status: REOPENED (2026-06-16) — moved back to active tasks.

The original "COMPLETE" was premature: it knowingly left ~60 test files (REST/setup-service per-setup
creation + frontend e2e) provisioning schema `public`, which the owner has ruled an error. The
**"no `public` in any test"** sweep is now an ACTIVE task — Phase A verified GREEN (the setup-service
create path works on a non-public schema); Phases B–E pending. See "Follow-up: no `public` in any
test (2026-06-16)" at the end.

**Prior remediation (still valid):** Test-layer remediation (S1–S5) COMPLETE; production fail-fast D2.1–D2.5 COMPLETE (13 Jun 2026). The first remediation (S1–S3, system-property-driven) was architecturally WRONG and has been replaced by the explicit-schema remediation; see "Architecture correction" below.

**Open production defect (not schema work):** the sweep surfaced **F1** — `PeeGeeQDatabaseSetupService` constructs `PeeGeeQManager` without its context Vert.x, so every `createCompleteSetup` spins up a new Vert.x + worker pool. Logged, unfixed, needs its own analysis + targeted run; detail in "Phase F — Testing discoveries: Vert.x lifecycle" below.

---

## Architecture correction (12 Jun 2026, evening) — the binding rules

The owner ruled, correcting the first remediation:

1. **PeeGeeQ has NO default schema.** Not `public`, not anything. The schema is mandatory,
   explicit configuration; a missing schema is an ERROR (fail fast), never a fallback.
2. **No configuration may come from JVM system properties or environment variables** — in
   production or tests. The Phase 11 config-architecture refactoring removed ambient
   configuration deliberately; the first S1–S3 remediation reintroduced it in error
   (`-Dpeegeeq.database.schema` reads) and has been fully reverted/replaced.

**As-built (replacing the S1–S3 as-built notes below, which are retained as history):**

- `PostgreSQLTestConstants.TEST_SCHEMA = "peegeeq_test"` — the single explicit schema
  constant for shared-container suites; schema-isolation tests use their own literals
  (`tenant_a`, `tenant_b`, …). Every suite now permanently exercises a NON-public schema.
- `PeeGeeQTestConfig.Builder`: `.schema(...)` is REQUIRED — `build()` throws
  `IllegalStateException` if never called; blank/invalid values are rejected. The contract
  test locks five behaviors, including the Phase-11-style inverted assertion that **setting
  the `peegeeq.database.schema` system property has no effect**.
- `PeeGeeQTestSchemaInitializer`: all schema-defaulting overloads of `initializeSchema` and
  `cleanupTestData` DELETED — every caller passes the schema explicitly (~190 call sites
  converted across 8 modules, in six distinct call shapes).
- `resolveSchema()` (the system-property reader) DELETED. Repo-wide grep gates: zero
  `System.getProperty("peegeeq.database.schema")` in any code; the single remaining
  ambient read is `DatabaseSetupHandler`'s `PEEGEEQ_DATABASE_SCHEMA` env lookup — a
  **production** defaulting path in the D2 inventory.
- The `custom-schema-tests` CI job (built on `-D` flags) was deleted; non-public operation
  is now proven by construction on every ordinary run.

**Verification matrix (all in explicit `peegeeq_test`):** test-support 47/47; peegeeq-db
integration 727/727; bitemporal CORE 120/120 + INTEGRATION 341/341; native CORE 148/148 +
INTEGRATION 185 run/0 fail (6 pre-existing skips); outbox CORE 80/80 + INTEGRATION 540 run
with the only 4 errors being `SystemPropertiesConfigurationExampleTest` (Phase-11 leftover,
owner decision pending: delete or invert); rest CORE 146/146 + INTEGRATION 331/331;
examples CORE 30/30 + INTEGRATION 143/143; examples-spring INTEGRATION 115/115.

**Genuine defects exposed by moving off `public`** (each fixed, see module reports):
funds-custody cleanup pool deleting from the wrong schema behind a silent first-run guard;
`NAVService.getNAVAsReported` `findFirst()` depending on unspecified result ordering
(F2 fallout); configuration-less queue factories putting LISTEN channels on a `public_`
prefix while triggers notify the configured schema (masked by polling fallback); native
LISTEN/NOTIFY channel names having NO 63-char truncation safety (bitemporal has it);
spring example shared-container DDL depending on class execution order and `auto-migrate`
silently recreating tables in `public`; three spring example apps unable to express a
schema at all.

---

## D2 — production fail-fast enforcement (phased, mandated last)

The owner mandated phased TDD execution, one phase verified at a time. Phase 0 (read-only
investigation) and phases D2.1–D2.2 are DONE; D2.3–D2.5 remain.

### D2.0 — investigation (DONE) — findings that reshaped the plan
- **The no-arg/1-arg `PeeGeeQConfiguration` constructors were already `@Deprecated(forRemoval)`** with zero production callers — deletion, not refactor.
- **Native defaulting nest has one choke point**: the config-less `PgNativeQueueBrowser` ctor is dead (zero callers); the consumer/producer `: "public"` fallbacks all originate from factories built without a `PeeGeeQConfiguration`. Requiring config at the factory makes every fallback dead.
- **`getEnvOrDefault` has INVERTED precedence**: 12 sites compute admin coords as env-var-first, the caller's explicit request as fallback — an env var silently overrides explicit config.
- **`peegeeq.migration.auto-migrate` is dead** — read by nothing; `PeeGeeQManager` has no migration path; `isAutoMigrationEnabled()` is a never-called API method. The earlier "auto-migrate masked the spring free-rider race" hypothesis was WRONG; class-execution-order luck alone did. The "verify migration schema handling" task dissolves.
- **Management UI has its own default**: `DatabaseSetups.tsx` sends `schema: values.schema || 'public'` at submit AND `initialValue="public"` on the form field; an e2e spec (`database-setup-form-defaults.spec.ts`) asserts the accidental default; the e2e harness passes `PEEGEEQ_DATABASE_SCHEMA=public`.

### D2.1 — core configuration fail-fast (DONE 13 Jun 2026)
- **C1**: `PeeGeeQConfiguration.validateDatabaseConfig` now rejects a missing/blank schema (`peegeeq.database.schema` required — no default). TDD RED→GREEN; contract test added.
- **C2**: the no-arg/1-arg `PeeGeeQConfiguration` constructors, `getActiveProfile()` (the `peegeeq.profile`/`PEEGEEQ_PROFILE` reader), and `PeeGeeQManager`'s no-arg constructor DELETED. The configuration guide test inverted to assert the ambient profile property has no effect.
- **C3**: `PgConnectionConfig` requires schema (`requireNonNull` + blank rejection); `PgConnectionManager`'s two "using default schema" fallback branches deleted — `search_path` is now unconditionally set. 49 test builder chains given explicit schema (42 db + 7 outbox surfaced by the repo-wide chain gate).
- **C4**: `peegeeq-default.properties` ships `schema=myschema` (deliberate non-functional placeholder, so an unconfigured instance fails loudly); 21 `${VAR:default}` interpolation fallbacks stripped from production/staging files (`${DB_SCHEMA}` now hard-required); `resolvePlaceholders` THROWS on an unresolvable `${VAR}` (was: pass the literal downstream); the hyphenated `test-schema` test-resource value fixed to `peegeeq_test`.
- Three more defaults-locking tests inverted (`testNoSchemaConfiguredUsesDefault`, the leave-literal placeholder test, outbox `tcS6_nullSchemaUsesUnqualifiedSql` → now verifies the full `myschema` fail-fast UX).
- **R5 delivered early**: `SystemPropertiesConfigurationExampleTest` DELETED (it configured PeeGeeQ entirely via `System.setProperty` — the abolished channel).
- **Gates**: config unit 28/28 · peegeeq-db integration **727/727** · test-support 47/47 · outbox integration **535/535**.

### D2.2 — factory fail-fast + native channel safety (DONE 13 Jun 2026)
- `VertxPoolAdapter` constructor `requireNonNull`s vertx/pool/connectOptionsProvider — the all-null adapter is rejected at the boundary (the `No ConnectOptionsProvider available` deep failure is now unreachable). `VertxPoolAdapterFailFastTest` rewritten to assert the real (constructor-level) fail-fast.
- `PgNativeQueueFactory` and `OutboxFactory` require a resolvable `PeeGeeQConfiguration` (passed explicitly, or resolved from the `DatabaseService` — never ambient); their `: "public"` schema fallbacks collapsed; the dead config-less `PgNativeQueueBrowser` ctor and the config-less consumer/producer ctors removed.
- **`NativeQueueChannels`** (new): single source for `{schema}_queue_{topic}` channel names with 63-byte-safe truncation + MD5 suffix (the native analogue of bitemporal's `createSafeChannelName`); producer and consumer both derive channels from it.
- **`PgNativeConsumerGroup` telescoping constructors collapsed** to the single canonical 10-arg form (production only ever used that one); the 6/7/8-arg overloads and their two constructor-existence tests deleted.
- The 57 `PgNativeConsumerGroupLifecycleTest` tests, formerly built on the now-rejected all-null adapter, given a **valid** non-null adapter (real Vertx + real Pool + provider lambda; no live DB needed — they exercise the in-memory state machine and `distributeMessage` routing). **Lesson recorded:** the fix was a valid adapter all along; the detour into delete/seam/extract/integration proposals was over-engineering a one-helper change.
- **Gates**: native CORE **153/153** · native + outbox factory contract tests green.

### D2.3 — REST and setup service (DONE 13 Jun 2026)
- `DatabaseSetupHandler.parseDatabaseConfig()`: removed the entire env-reading block (`System.getenv` for host/port/username/password/schema); schema is now explicitly required — `IllegalArgumentException("schema is required")` thrown if absent/blank → caught → 400. Host/port/username/password revert to hardcoded defaults (localhost/5432/postgres/postgres) matching the form pre-fills. Contract test A10 (`createSetup_missingSchema_returns400`) added to `DatabaseSetupHandlerErrorTest`.
- `PeeGeeQDatabaseSetupService.getEnvOrDefault` deleted (both overloads); 12 call sites in `createDatabaseFromTemplate`, `applySchemaTemplates`, and `validateDatabaseInfrastructure` replaced with direct `dbConfig.getX()` / `request.getDatabaseConfig().getX()` reads.
- `SystemInfoCollector.collectPeeGeeQConfiguration`: removed the `else` branch that swept `System.getProperties()` for `peegeeq.*` keys; only reads from the injected config instance now.
- Management UI: `DatabaseSetups.tsx` — removed `initialValue="public"` and `|| 'public'` submit fallback; Schema Form.Item marked required. `database-setup-form-defaults.spec.ts` — "Schema field defaults to 'public'" test inverted to "Schema field has no default" (asserts empty value). `global-setup-testcontainers.ts` — `-DPEEGEEQ_DATABASE_SCHEMA=public` and `PEEGEEQ_DATABASE_SCHEMA: 'public'` removed from backend spawn.
- **Gates**: REST CORE **147/147** · peegeeq-db CORE **335/335**.

### D2.4 — migrations CLI + standalone tools (DONE 13 Jun 2026)
- `RunMigrations`: `DB_SCHEMA` now uses `getRequiredEnv` — no `"public"` fallback; fails at startup with a clear message if absent. Javadoc updated.
- `isAutoMigrationEnabled()` deleted from `QueueConfiguration` interface, `PgQueueConfiguration` implementation, and `PgQueueConfigurationCoreTest` (the one test that called it). The 40+ test files still setting `peegeeq.migration.enabled`/`peegeeq.migration.auto-migrate` are left untouched — those properties are inert once the only reader is gone, and sweeping them is out of scope.
- `peegeeq-pg-sidecar` / `peegeeq-service-manager` / `peegeeq-performance-test-harness`: documented exception — entry-point CLIs configured by system properties is their interface.
- **Gates**: peegeeq-db CORE **334/334** (335 before — deleted test) · peegeeq-api + peegeeq-migrations compile clean.

### D2.5 — examples cleanup (DONE 13 Jun 2026)
- All 11 Spring example config classes (`PeeGeeQProperties` ×2, `PeeGeeQDlqProperties`, `PeeGeeQRetryProperties`, `IntegratedProperties`, `BitemporalProperties`, `ReactiveBiTemporalProperties`, `PeeGeeQPriorityProperties`, `BiTemporalTxProperties`, `FinancialFabricProperties`, `PeeGeeQConsumerProperties`): `private String schema = "public"` → `private String schema;`; `getSchema()` now throws `IllegalStateException("<prefix>.database.schema is required")` if null/blank.
- `application-springboot-bitemporal-tx.yml`: `schema: ${DB_SCHEMA:public}` → `schema: ${DB_SCHEMA}` (fallback removed).
- `application-springboot-dlq.yml`, `application-springboot-retry.yml`, `application-springboot-financial-fabric.yml`: `schema: ${PEEGEEQ_DB_SCHEMA}` added under `database:` block (was absent — no schema property at all).
- `application-springboot2-bitemporal.yml`: no database section; tests provide schema via `@DynamicPropertySource` in `SharedTestContainers` — no yml change needed.
- Plain examples `.properties`: explicit values (`schema=public`) kept — these are intentional example configs, not ambient defaults.
- **Gates**: `SharedTestContainers.configureSharedProperties()` provides all Spring example schemas via `@DynamicPropertySource`; removing Java defaults does not affect the 115 integration tests.

### Post-completion scan findings (DONE 13 Jun 2026)

A full codebase rescan after D2.5 found two remaining production gaps:

- **G1 — `04-search-path.sql`** (`peegeeq-db/src/main/resources/db/templates/base/04-search-path.sql`): `SET search_path TO {schema}, public;` → `SET search_path TO {schema};`. The `, public` fallback allowed PostgreSQL to silently resolve unqualified table names in `public` during schema setup, violating the no-ambient-schema rule.
- **G2 — `PeeGeeQConfiguration` 7-arg constructor** (`PeeGeeQConfiguration.java` lines 90–109): the `if (dbSchema != null && !dbSchema.isEmpty())` guard silently skipped setting `peegeeq.database.schema` when `dbSchema` was null, allowing the `myschema` placeholder from `peegeeq-default.properties` to pass `validateConfiguration()` silently. Now throws `IllegalArgumentException("dbSchema is required — PeeGeeQ has no default schema")` at the top of the constructor before any property loading. The stale Javadoc `(uses configured/default PostgreSQL search_path if null)` updated to `(required — must be non-null and non-blank)`.

All other scan findings were legitimate: profile `.properties` files and example YMLs with explicit `schema=public` values (named configuration, not defaults); dead defensive null check in `PgConnectionManager.normalizeSearchPath` (unreachable; PgConnectionConfig guarantees non-null schema before this point). **CORRECTION (2026-06-16): the originally-"legitimate" `.schema("public")` in setup-service integration tests was an error.** No test in any module may use `public`; these are being converted to an explicit non-public schema (see "Follow-up: no `public` in any test" below).

### Related findings (DONE 13 Jun 2026)
- **LISTEN-failure log severity** (DONE): `PgNativeQueueConsumer` — three sites that logged at ERROR when HYBRID-mode polling covers the failure now log at WARN with `(polling active as fallback)` in the message; `LISTEN_NOTIFY_ONLY` mode retains ERROR. Helper `isListenOnlyMode()` added. Sites: `subscribe()` onFailure · `exceptionHandler` on LISTEN connection · `startListening()` onFailure (reconnect path).
- **R1/R3 rulings (owner)**: env vars remain the single sanctioned production channel but with NO defaults (missing env = startup error); `public` stays a legitimate *explicit* value — only the *accidental* default dies.

The PostgreSQL schema is the anchor of every PeeGeeQ instance: schema selection is
connection-level (`search_path` set by `PgConnectionManager`), runtime SQL is unqualified, and
DDL is `{schema}`-parameterized in templates. A systematic review (two module audits plus
manual verification of every load-bearing claim) found that **the production core honors this
design, but the test infrastructure that claims to support custom-schema runs actually pins
~100+ test classes to `public`** — and CI never runs a non-`public` profile. The design's
custom-schema soundness is therefore largely unverified. Two production-side traps (dead
schema-unsafe code) and one hygiene item complete the findings.

**Empirical proof of the root defect** (12 Jun 2026): `PgBiTemporalEventStoreComplexTest`
fails under `-Dpeegeeq.database.schema=tenant_summary_test` with
`required tables missing in schema 'public'` — its DDL lands in the custom schema while its
manager runs against `public`. `BiTemporalAggregateSummaryIntegrationTest` (fixed the same
day to thread the schema fully) passes 7/7 under both schemas and is currently the only
bitemporal test that genuinely supports a custom schema.

---

## Root cause

`PeeGeeQTestConfig.Builder` (peegeeq-test-support, lines ~66/89/124): the builder defaults
`schema = "public"` and **unconditionally** writes it into the manager properties. `.schema()`
is optional, so a test that omits it does not inherit the `-Dpeegeeq.database.schema` system
property — it **clobbers** it. Meanwhile the tests' `resolveSchema()` pattern reads that same
property for DDL placement, so DDL and manager configuration silently diverge.

This is an API that accepts misconfiguration: an optional setter with a silent default — the
configuration-shaped form of the ignored-parameter defect class documented in the other
audit task files.

## Census (audited 12 Jun 2026)

- 378 test classes in scope; 135 use `PeeGeeQTestConfig.builder()`.
- **74 of 135 never call `.schema()`** → silently pinned to `public`, system property clobbered.
- 54 call `.schema("public")` explicitly; only 7 pass a dynamic schema.
- **9 bitemporal classes half-thread the schema** (DDL in schema X, manager on `public`):
  `PgBiTemporalEventStoreComplexTest` (proven failing), `EventBusDistributionEquivalenceTest`,
  `EventBusDistributionSemanticGapsTest`, `PgBiTemporalEventStoreStatsTest`,
  `TraceContextPropagationTest`, `TransactionPropagationHonestyTest`,
  `VersionFamilyDefensiveTest`, `VersionFamilyTopologyTest`, `VersionLineageBugSurfacingTest`.
- **`BaseIntegrationTest` (peegeeq-db, line ~89) hardcodes `.schema("public")`** — every
  subclass (~25–40 classes) has no custom-schema path at all.
- **No CI profile or pom sets `peegeeq.database.schema`** — the entire defect class is latent.
- Estimated blast radius under a custom schema: ~100–115 failing test classes, plus false
  passes from configuration-only tests.

## Verified clean (the production foundation holds)

- `PgConnectionManager.createReactivePool()` sets `search_path` for all managed pools.
- `PgBiTemporalEventStore`'s lazy pool sets `search_path` explicitly
  (`createConnectOptionsFromPeeGeeQManager`, ~lines 1848–1857).
- NOTIFY channel names are schema-prefixed (native and bitemporal).
- All DDL lives in `{schema}`-parameterized, validated templates; none is built in Java.
- `PostgreSqlIdentifierValidator` (`^[a-zA-Z_][a-zA-Z0-9_]*$` whitelist) is applied to all
  user-provided schema/table/queue names.
- Schema configuration has a single source (`PeeGeeQConfiguration`/`DatabaseConfig`) — no
  split-brain.
- `PeeGeeQTestSchemaInitializer` takes the schema as a parameter and drives Flyway's
  `defaultSchema` with it.
- Four dedicated isolation tests thread schemas correctly: `OutboxSchemaIsolationCoverageTest`,
  `DlqMultiTenantSchemaIsolationTest`, outbox `MultiTenantSchemaIsolationTest`,
  `BiTemporalAggregateSummaryIntegrationTest`.

## Audit-claim corrections (recorded so they are not re-reported)

The initial production audit labelled three findings CRITICAL; manual verification corrected:

1. **`BiTemporalPoolFactory` missing `search_path`** — real, but the class has **zero
   production callers** (dead code); the store's actual pool path is schema-correct. The
   audit's "any unqualified SQL fails" scenario is false today. Reclassified MEDIUM (trap).
2. **`String.format` schema interpolation** — not injectable (validator whitelist applied
   first); reclassified LOW hygiene.
3. **`ReactiveNotificationHandler` defaulting constructor** — production passes the schema
   explicitly; only tests use the defaulting form. Reclassified MEDIUM (trap).

---

## Remediation tasks (in priority order)

### S1 — CRITICAL: `PeeGeeQTestConfig` must inherit the schema system property

- **Change**: the builder's schema default becomes
  `System.getProperty("peegeeq.database.schema", "public")` (validated with the same
  whitelist the suite's `resolveSchema()` uses); `.schema(...)` remains an explicit override.
- **Effect**: the 74 silent-default classes and the 9 half-threaded classes re-converge
  (their `resolveSchema()` reads the same property) without touching any of them.
- **TDD**: RED exists today — `PgBiTemporalEventStoreComplexTest` under
  `-Dpeegeeq.database.schema=tenant_summary_test` fails with "required tables missing in
  schema 'public'". Add a `@Tag(CORE)` contract test on `PeeGeeQTestConfig` (no database)
  asserting the property flows: property set → builder default follows it; `.schema()`
  overrides it; neither set → `public`. (Contract tests for test infrastructure are the
  regression lock prescribed by `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`.)
- **GREEN gate**: the bitemporal suite passes under both default and
  `-Dpeegeeq.database.schema=tenant_summary_test`.

**DONE (12 Jun 2026).** As-built:
- Contract test: `PeeGeeQTestConfigSchemaContractTest` (test-support, `@Tag(CORE)`, no
  database — a stub container supplies coordinates). 5 tests: absent property → `public`;
  property inherited; `.schema()` overrides; blank → `public`; invalid → rejected naming
  the offender. RED confirmed 2 failures against the clobbering builder, GREEN 5/5 after.
- The resolution is exposed as **`PeeGeeQTestConfig.resolveSchema()`** (public static) —
  the single definition of the suite-wide convention; the builder default and all test
  call sites converted in S2 use it.
- Gate evidence: `PgBiTemporalEventStoreComplexTest` 84/84 under
  `-Dpeegeeq.database.schema=tenant_summary_test` (was: wholesale failure). Five of its
  tests needed their own raw pools fixed (search_path via `PgConnectOptions.setProperties`,
  mirroring `PgConnectionManager` — the appendInTransaction connections run the store's
  unqualified SQL, so qualifying SQL was not an option there). Bitemporal CORE 129/129
  under default schema after the change; test-support 46/46.

### S2 — HIGH: `BaseIntegrationTest` custom-schema support

- Replace the hardcoded `.schema("public")` (line ~89) with the same property-driven
  resolution. After S1, simply removing the explicit `.schema("public")` call may suffice —
  decide during implementation; either way the subclasses must pass under a custom schema.
- **Gate**: a representative set of `BaseIntegrationTest` subclasses passes under
  `-Dpeegeeq.database.schema=tenant_ci`.

**DONE (12 Jun 2026) — and substantially larger than scoped.** Removing the
`BaseIntegrationTest` hardcode exposed three further layers, fixed the same day:

1. **`SharedPostgresTestExtension`** hand-builds the module's DDL on a raw JDBC connection —
   it was pinned to `public` regardless of the property. Now resolves the schema via
   `PeeGeeQTestConfig.resolveSchema()`, creates it if needed, and `SET search_path` before
   the DDL. (RED: `PeeGeeQMetricsCoreTest` 23/23 errors "required tables missing in schema
   'tenant_ci'"; GREEN after.)
2. **Explicit `.schema("public")` hardcodes** across the repo's tests, classified by builder
   before converting (a blind replace would have been wrong):
   - `PgConnectionConfig.Builder` — 42 peegeeq-db files + 7 in peegeeq-native/peegeeq-examples
     → `.schema(PeeGeeQTestConfig.resolveSchema())`.
   - `PeeGeeQTestConfig.builder()` — 7 occurrences (CloseLogLevel ×4, CloseReactiveError ×2,
     MultiConfigurationExample, BiTemporalEventStoreExample) → call removed; the S1 default
     applies. The two tests with their own containers + private `initializeSchemaFor` DDL
     helpers (`PeeGeeQManagerCloseLogLevelTest`, `PeeGeeQManagerTimerGuardTest`) had the
     helpers threaded with the same CREATE SCHEMA + SET search_path pattern.
   - `DatabaseConfig.Builder` — **CORRECTION (2026-06-16): the original "left as-is" decision
     here was an error and has been reverted.** The rationale ("per-setup parameters for new
     databases the setup service creates, not the shared-container schema") explains only why
     these were outside the shared-container *mechanism* — not why they may stay on `public`.
     The per-setup creation path (CREATE SCHEMA → `search_path` → schema-parameterized DDL →
     schema-prefixed NOTIFY) is the real production flow and the most important to verify on a
     non-public schema; `public` is specifically the schema that *masks* `search_path` defects
     (see G1). "Proven green under tenant_ci unchanged" only confirmed these tests are
     schema-insensitive (always `public`) — the gap, not a clearance. These are being converted
     to an explicit non-public schema. See "Follow-up: no `public` in any test" below.
3. **Schema-less `PgConnectionConfig` chains and raw `Properties` hardcodes** — two more
   forms of the same defect surfaced by the first full custom-schema run (108 failures
   across ~19 classes): 9 shared-container classes with no `.schema()` call at all (pool
   silently defaulted to `public`), and 12 `setProperty("peegeeq.database.schema", "public")`
   occurrences in 11 raw-Properties classes. All converted to `resolveSchema()`. 15 further
   schema-less files were verified schema-independent (own containers or no table access)
   and deliberately left untouched.

**Verification**: full peegeeq-db integration suite **727/727 under default schema** and
**727/727 under `-Dpeegeeq.database.schema=tenant_ci`**. One non-reproducible failure was
observed in an intermediate ad-hoc 6-class parallel run (`dead_letter_queue` reported
missing once in `testAggregateSummaryTableCreatedAndMaintained` setup); it did not recur in
any subsequent run including the full suite — recorded here so the S3 CI job's history is
the arbiter if it ever resurfaces.

### S3 — HIGH: CI regression lock

- Add a CI job/profile running at least the bitemporal and peegeeq-db integration suites with
  `-Dpeegeeq.database.schema=tenant_ci`. Without this, the defect class returns silently —
  it was only ever discoverable by a manual run.

**DONE (12 Jun 2026).** New `custom-schema-tests` job in `.github/workflows/build.yml`
(parallel to the main build job, same setup steps): peegeeq-db integration, bitemporal core,
and bitemporal integration, each with `-Dpeegeeq.database.schema=tenant_ci`, plus its own
test-results publication. Local proof per leg under `tenant_ci`: peegeeq-db integration
**727/727**, bitemporal core **120/120**, bitemporal integration **341/341**. (Bitemporal
counts are 9 lower than before S4 because the deleted placeholder/dead-code tests are gone.)

**Bitemporal hardening required for the CI legs (12 Jun 2026).** Running the bitemporal
suites under `tenant_ci` surfaced five further hardcode forms beyond the S2 census, all
fixed to the same `PeeGeeQTestConfig.resolveSchema()` convention:

1. **`PeeGeeQTestSchemaInitializer` defaulting overloads** — the 2-arg/4-arg forms pinned
   `"public"`; they now default to the resolved property (~30 callers across
   bitemporal/native/rest/examples-spring converge without edits).
2. **Builder-extras hardcode** — `.property("peegeeq.database.schema", "public")` overrides
   the S1 default because extras apply last; 3 occurrences removed
   (PgBiTemporalEventStoreIntegrationTest, TransactionalBiTemporalExampleTest,
   WildcardPatternComprehensiveTest).
3. **Stubbed `resolveSchema()` returning `"public"`** with a stale "the property can no
   longer inject a value" comment (EventBusDistributionEquivalenceTest,
   TraceContextPropagationTest) — now delegates to the central helper.
4. **Coordinate-args `PeeGeeQConfiguration(..., "public")` constructor** — 4 sites in
   VersionLineageIntegrationTest.
5. **Raw verification pools / JDBC DDL helpers without `search_path`** —
   TransactionParticipationIntegrationTest (10 pools), TransactionPropagationHonestyTest,
   VersionLineageBugSurfacingTest, ReactiveNotificationHandlerIntegrationTest,
   PgBiTemporalEventStoreTest, DatabaseWorkerVerticleTest (its `LIKE bitemporal_event_log`
   secondary-table helper), and the private-table DDL helpers of
   PgBiTemporalEventStorePerformanceTest / PgBiTemporalEventStoreIntegrationTest /
   WildcardPatternComprehensiveTest.

One genuine product-edge finding: NOTIFY channel names are `{schema}_bitemporal_events_...`,
so a longer schema name pushes hand-built channel names past PostgreSQL's 63-char limit
("channel name too long"). The handler already truncates via `createSafeChannelName`; that
method is now package-private static so tests publishing manual NOTIFYs build the exact
(truncated) name the handler LISTENs on, instead of duplicating the format by hand.

### S4 — MEDIUM: remove the production traps

- **`BiTemporalPoolFactory`**: dead code that creates pools without `search_path`, guarded by
  placeholder tests (`BiTemporalFactoryTest` asserts the class exists and its package name —
  the CRITICAL placeholder-test antipattern). Delete the factory and its placeholder tests,
  or fix it (set `search_path` like `createConnectOptionsFromPeeGeeQManager`) and give it a
  real caller. Recommendation: delete.
- **`ReactiveNotificationHandler` defaulting constructor** (hardcodes
  `"public"`/`"bitemporal_event_log"`): remove it; update
  `ReactiveNotificationHandlerLifecycleTest` to pass the schema explicitly.

**DONE (12 Jun 2026).** As-built:
- `BiTemporalPoolFactory.java` and `BiTemporalPoolFactoryTest.java` deleted.
  `BiTemporalFactoryTest` kept, with its six placeholder tests (class-exists /
  name-equals / package-equals) removed — its two real validation tests
  (qualified-table-name and invalid-identifier rejection) remain.
- The 5-arg defaulting constructor removed; schema and tableName are now required and
  `Objects.requireNonNull`-checked (the old `null → "public"` fallback was the same
  silent-default trap in another form). Production was already passing them explicitly
  (`PgBiTemporalEventStore`). 29 test call sites across the three handler test classes
  (incl. one `super(...)` in `ReactiveNotificationHandlerFailurePathTest`) now pass
  `PeeGeeQTestConfig.resolveSchema()` + `"bitemporal_event_log"`.
- Verified: 8 CORE + 26 INTEGRATION tests across the four affected classes, all green.

### S5 — LOW: hygiene

- Parameterize the two `information_schema` queries in `PeeGeeQDatabaseSetupService`
  (~lines 459, 1022) — identifiers are pre-validated so this is defense-in-depth, and unlike
  DDL these queries can use `$n` bindings.

**DONE (12 Jun 2026).** Both converted from `String.format('%s')` to
`preparedQuery` + `Tuple.of(schema)` with `$1` bindings. Verified:
`PeeGeeQDatabaseSetupServiceEnhancedTest` 12/12 (both paths exercise the queries).

## Summary

| # | Task | Severity | Layer | Status |
|---|------|----------|-------|--------|
| S1 | `PeeGeeQTestConfig` inherits `peegeeq.database.schema` (with contract test) | CRITICAL | peegeeq-test-support | **Done — 12 Jun 2026** |
| S2 | `BaseIntegrationTest` custom-schema support (grew into the full hardcode sweep: extension DDL threading + ~70 call sites across 4 defect forms) | HIGH | peegeeq-db/native/examples tests | **Done — 12 Jun 2026** (727/727 both schemas) |
| S3 | CI custom-schema job (`custom-schema-tests` in build.yml) | HIGH | CI | **Done — 12 Jun 2026** |
| S4 | Delete `BiTemporalPoolFactory` (+ placeholder tests) and the `ReactiveNotificationHandler` defaulting constructor | MEDIUM | peegeeq-bitemporal | **Done — 12 Jun 2026** |
| S5 | Parameterize `information_schema` queries | LOW | peegeeq-db | **Done — 12 Jun 2026** |
| D2.1 | Core configuration fail-fast (`PeeGeeQConfiguration`, constructors deleted, `PgConnectionConfig` requires schema) | HIGH | peegeeq-db/outbox/test-support | **Done — 13 Jun 2026** |
| D2.2 | Factory fail-fast + native channel safety (`NativeQueueChannels`, dead ctors removed) | HIGH | peegeeq-native | **Done — 13 Jun 2026** |
| D2.3 | REST/setup service: `DatabaseSetupHandler` schema required (A10 test), `getEnvOrDefault` 12 sites deleted, `SystemInfoCollector` ambient reads removed, Management UI schema required + e2e updated | MEDIUM | peegeeq-rest/peegeeq-db/peegeeq-management-ui | **Done — 13 Jun 2026** |
| D2.4 | `RunMigrations` `DB_SCHEMA` required + `isAutoMigrationEnabled` deleted from API/impl/test | LOW | peegeeq-migrations/peegeeq-api/peegeeq-db | **Done — 13 Jun 2026** |
| D2.5 | Eleven Spring example config classes `private String schema = "public"` defaults removed; getSchema() validates; 4 yml files updated (fallback stripped or schema added) | LOW | peegeeq-examples-spring | **Done — 13 Jun 2026** |
| G1 | `04-search-path.sql`: removed `, public` fallback from `SET search_path TO {schema}, public` | HIGH | peegeeq-db (DDL template) | **Done — 13 Jun 2026** |
| G2 | `PeeGeeQConfiguration` 7-arg constructor: null/blank schema now throws at construction (was: silently inherited `myschema` placeholder) | HIGH | peegeeq-db | **Done — 13 Jun 2026** |
| L1 | `PgNativeQueueConsumer` LISTEN-failure severity: HYBRID mode ERROR→WARN (polling covers it); `LISTEN_NOTIFY_ONLY` retains ERROR | LOW | peegeeq-native | **Done — 13 Jun 2026** |

---

## Follow-up: no `public` in any test (2026-06-16)

**Directive (owner, 2026-06-16):** the per-setup-test exception recorded above was an error and is
removed (see the CORRECTIONs in S2 and the post-completion scan). **No test in any module of the
PeeGeeQ system may use the database schema `public`** — not the shared-container suites (already on
`peegeeq_test`) and not the per-setup databases that REST/setup-service/e2e tests create.

**Why:** the per-setup creation path (CREATE SCHEMA → `search_path` → schema-parameterized DDL →
schema-prefixed NOTIFY) is the real production flow and the most important to verify on a non-public
schema. `public` is precisely the schema that *masks* `search_path` defects (see G1), so a test that
provisions `public` never exercises — and can hide bugs in — the non-public path.

**Blast radius (inventory 2026-06-16):** ~66 occurrences across 49 Java test files (heaviest in
`peegeeq-rest`; also `peegeeq-db`, `peegeeq-integration-tests`, `peegeeq-examples`, `peegeeq-native`,
`peegeeq-outbox`, `peegeeq-runtime`, `peegeeq-rest-client`) and ~13 across 11 frontend e2e/TS files.

**Convention:** shared-container suites → `PostgreSQLTestConstants.TEST_SCHEMA` ("peegeeq_test") or
`PeeGeeQTestConfig.resolveSchema()`; per-setup databases the setup service creates → pass an explicit
non-public schema (`peegeeq_test`) in the create request.

**Execution:** phased, one verifiable unit at a time (each may surface a defect `public` was masking).
Phase A validates the riskiest assumption — that the setup-service create path works on a non-public
schema — on `ManagementApiIntegrationTest` before the mechanical sweep of the remaining ~59 files.

| Phase | Scope | Status |
|---|---|---|
| A | `ManagementApiIntegrationTest` create-setup → `peegeeq_test` (proves the non-public create path) | ✅ **Done 2026-06-16 — GREEN.** The full class passed against `peegeeq_test`, confirming the setup-service create path (CREATE SCHEMA → `search_path` → DDL → NOTIFY) works on a non-public schema. B–E are therefore a mechanical `"public"` → non-public swap (still run per module). |
| B | Remaining `peegeeq-rest` test setup-creation + `.schema("public")` | ✅ **Done 2026-06-20 — GREEN.** 31 files moved off `public` to `PostgreSQLTestConstants.TEST_SCHEMA` (29 uniform `.put("schema", …)` via regex; `BasicUnitTest`/`DatabaseSetupHandlerErrorTest` builder/`JsonObject.of` forms + import added; plus the stale `SetupManagementIntegrationTest:195` response assertion the swap exposed). Grep-clean: zero `public` schema literals in `peegeeq-rest/src/test`. Validated via `mvn test -Pintegration-tests -pl :peegeeq-rest` (log `logs/peegeeq-rest-integration-20260619.txt`): **321 tests, 0 failures**, every schema-converted file passing. The one error — `SseMessageStreamDemoIntegrationTest.tenMessages_streamOverSse_outboxQueue` `HttpClosedException` — is unrelated to the schema swap (functional path completed: all 10 received + non-destructive verified before the exception) and **passes clean in isolation** (`logs/sse-msgstream-rerun-20260619.txt`, 2/2 green); logged as a separate parallel-teardown flake in Open Items below. |
| C | `peegeeq-db` / `peegeeq-integration-tests` / `peegeeq-runtime` / `peegeeq-rest-client` | ✅ **DONE — all GREEN.** ✅ `peegeeq-runtime` (`RuntimeDatabaseSetupServiceIntegrationTest:103` `.schema(...)`) + `peegeeq-rest-client` (`RestClientIntegrationTest:147` `.put("schema",…)`) converted to `PostgreSQLTestConstants.TEST_SCHEMA` and GREEN 2026-06-20 (`logs/schema-phaseC-runtime-restclient-20260620.txt`: runtime 38/0, rest-client 15/0). ✅ `peegeeq-db` **GREEN 2026-06-20** — 8 genuine schema-config literals across 2 files (`PeeGeeQDatabaseSetupServiceEnhancedTest` ×6 create-path, `EventDrivenLifecycleTest` ×2 positional) → `TEST_SCHEMA`; 3 occurrences deliberately LEFT (`PeeGeeQManagerTimerGuardTest:519` + `PeeGeeQManagerCloseLogLevelTest:287` `if (!"public".equals(schema))` sentinels where schema already = `TEST_SCHEMA`; `PostgreSqlIdentifierValidatorTest:238` validator fixture). Validated via `mvn test -Pintegration-tests -pl :peegeeq-db` (`logs/schema-phaseC-db-20260620.txt`): **727 tests, 0 failures**, both converted classes pass. Confirmed `EventDrivenLifecycleTest` (construction-only, schema not pre-provisioned) tolerates `TEST_SCHEMA`. ✅ `peegeeq-integration-tests` **GREEN 2026-06-21** — 10 create-path schema literals across 7 files (`SmokeTestBase` shared helper, `PeeGeeQCriticalPathSmokeTest`, `NativeConcurrencySmokeTest`, `BiTemporalEventStoreSmokeTest`, `EventVisualizationApiTest`, `EventStoreAdvancedAttributesSmokeTest`, `EventStoreManagementSmokeTest` ×4) → `TEST_SCHEMA` (+import added to the 5 that lacked it); grep-clean. **Mixed tags** (5 `@Tag(SMOKE)` + 2 `@Tag(INTEGRATION)`) verified via module-scoped all-tags `mvn test -pl :peegeeq-integration-tests -Pall-tests` (`logs/schema-phaseC-integtests-20260620.txt`): **97 tests, 0 failures**, all 7 converted classes pass. Flagged (not fixed): `PeeGeeQCriticalPathSmokeTest` hand-rolls its container (antipattern §2); `NativeConcurrencySmokeTest` uses `CountDownLatch.await`/`setTimer` readiness guards/reflection field access (pre-existing, unrelated to schema swap). **All Phase C conversions complete and GREEN.** Flagged (not fixed here): `RestClientIntegrationTest` hand-rolls its container instead of `PostgreSQLTestConstants.createStandardContainer()` (antipattern §2). |
| D | `peegeeq-examples` / `peegeeq-native` / `peegeeq-outbox` test usages | ✅ **DONE — all GREEN.** **Owner ruling 2026-06-21:** cosmetic/non-config `"public"` defaults are CONVERTED too (not left); only documentation prose referencing `"public"` to explain the no-public rule stays (converting it would make the doc factually wrong). ✅ `peegeeq-native` **GREEN 2026-06-21** — `PeeGeeQExampleTest:258` dead `getString(key,"public")` log fallback → `TEST_SCHEMA`; 2 doc references left (`PgNativeQueueFactoryIntegrationTest:91` comment, `PgNativeQueueFactoryContractTest:38` Javadoc). `mvn test -Pintegration-tests -pl :peegeeq-native` (`logs/schema-phaseD-native-20260621.txt`): **190 tests, 0 failures** (6 pre-existing skips). ✅ `peegeeq-outbox` **GREEN 2026-06-21** — 2 genuine config literals → `TEST_SCHEMA` (`OutboxBlockingSafetyTest:38`, `OutboxFactoryCloseHookTest:181`; both `@Tag(CORE)`, no-DB construction-only configs) +imports; 1 Javadoc left (`OutboxFactoryContractTest:38`). `mvn test -pl :peegeeq-outbox` (`logs/schema-phaseD-outbox-20260621.txt`): **81 tests, 0 failures**. Flagged (not fixed): `OutboxFactoryCloseHookTest` uses banned `Future.await()` throughout (pre-existing). ✅ `peegeeq-examples` **GREEN 2026-06-21** — 3 genuine create-path literals → `TEST_SCHEMA` (`DatabaseSetupServiceIntegrationTest:116` & `:339`, `RestApiExampleTest:196`; both files already imported the constant); 1 comment left (`ConsumerGroupLoadBalancingDemoTest:170`, documents the "no public fallback" rule). `mvn test -Pintegration-tests -pl :peegeeq-examples -Dtest=DatabaseSetupServiceIntegrationTest,RestApiExampleTest` (`logs/schema-phaseD-examples-20260621.txt`): **13 tests, 0 failures** (DatabaseSetupServiceIntegrationTest 8/0, RestApiExampleTest 5/0). Flagged (not fixed): `DatabaseSetupServiceIntegrationTest` hand-rolls its container (§2); `RestApiExampleTest:122` post-`deployVerticle` `vertx.timer(500)` readiness delay (setTimer-guard antipattern). **Note (pre-existing, unrelated):** the run logged 7× `"already on a Vert.x context… create a new Vertx instance?"` warnings — root cause is `PeeGeeQDatabaseSetupService:178` constructing `new PeeGeeQManager(config)` without passing its context Vert.x, so `PeeGeeQManager:187` creates a fresh Vert.x on the event loop. Production-code lifecycle issue, not a schema-sweep concern; tracked separately below. |
| E | Frontend e2e/TS (`peegeeq-management-ui`) create flows + fixtures | 🔄 **Converted 2026-06-21, pending e2e run.** Added `TEST_SCHEMA = 'peegeeq_test'` to `src/tests/e2e/test-constants.ts` (mirrors Java `PostgreSQLTestConstants.TEST_SCHEMA`). Converted **12 genuine schema literals across 10 spec files**: 4 `schema: 'public'` object-literals (`setup-prerequisite`, `database-setup` ×2, `event-stores-scope-filter`) + 8 `getByLabel(/schema/i).fill('public')` form-fills (`api-error-paths`, `event-store-management`, `event-store-workflow`, `event-visualization`, `queue-messaging-workflow`, `queue-management`, `take-screenshots` ×2; `take-screenshots` uses a local `TEST_SCHEMA` const to mirror its local-`SETUP_ID` idiom). Left intentionally: `database-cleanup.ts:61` system-schema *preserve* sentinel (`NOT IN (…, 'public')`), and the `test-constants.ts` doc comment. `npm run type-check` passes. **Partially verified GREEN 2026-06-22:** `event-stores-scope-filter` (one of the converted `schema:` create-path specs) passed via `npx playwright test --project=12b-event-stores-scope-filter` (the spec + its setup-prerequisite dependency chain — not the full suite), confirming the `TEST_SCHEMA` create-path + scope filtering work against the real backend. That run also required fixing the spec's own `showAllRows` pagination helper — a **pre-existing full-suite fragility, not a schema issue**: it ran before the async rows loaded, and a switch to `selectAntOption` regressed it because `filter({hasNot})` can't exclude a self-hidden leftover dropdown; restored the CSS `:not(.ant-select-dropdown-hidden)` selector + wait-for-rows + a selection-item verify. The other 9 converted specs are type-checked but not yet individually run. **Scope note:** my first grep caught only `schema: 'public'` object-literals and missed the `.fill('public')` form-fills — corrected (E is 10 files, not the 3 first reported). PS helper scripts + docs `public` references: **done** — see the "remove ALL public references" follow-up section below. |
| F | **Testing discovery (not a schema conversion)** — Vert.x lifecycle issues surfaced during sweep verification runs | 🔎 **Logged 2026-06-21, not fixed.** Production-code (and related test-side) Vert.x lifecycle defects found while running the schema-sweep verification suites. Out of scope for the sweep; each needs its own analysis + targeted run before any change. Detail in "Phase F — Testing discoveries: Vert.x lifecycle" below. |

### Open items surfaced during Phase B validation (not schema-related)

- **SSE message-stream parallel-teardown flake.** `SseMessageStreamDemoIntegrationTest.tenMessages_streamOverSse_outboxQueue`
  threw `io.vertx.core.http.HttpClosedException: Connection was closed` once during the full `peegeeq-rest`
  integration run (`logs/peegeeq-rest-integration-20260619.txt:10060`), but **only after** the functional path
  completed (all 10 messages received over SSE + non-destructive verified, lines 9776–9779). It **passes clean in
  isolation** (`logs/sse-msgstream-rerun-20260619.txt`, 2/2 green), and the native variant passed in both runs. This
  points at a teardown / SSE-connection-close race under parallel execution (`test.parallel=methods`, 4 threads), not
  a defect in the streaming feature or the schema swap. Needs its own investigation — the test should not surface a
  post-assertion connection close as an error. **Do not** fold this into the schema sweep. No code changed for it here.

## Phase F — Testing discoveries: Vert.x lifecycle (not schema-related)

Production-code (and related test-side) Vert.x lifecycle issues surfaced by the schema-sweep
**verification runs**. These are **not** schema conversions and were **not** fixed as part of the sweep —
each needs its own analysis and targeted test run. Logged here so they are not lost. **Status: F1 still
logged / not-fixed; all three F2 files converted 2026-06-27 (owner-directed), pending owner verification —
see F2 below.**

### F1 — `PeeGeeQManager` creates a new Vert.x instance on the event loop, once per setup (production)

**Symptom.** The Phase D `peegeeq-examples` run logged the following **7×**, one per `createCompleteSetup`,
all on `vert.x-eventloop-thread-0` during `DatabaseSetupServiceIntegrationTest`
(`logs/schema-phaseD-examples-20260621.txt` lines 48–77):

> `WARN io.vertx.core.impl.VertxImpl - You're already on a Vert.x context, are you sure you want to create a new Vertx instance?`

**Root cause (confirmed by reading the code, not inferred).**
- `PeeGeeQDatabaseSetupService:105-107` correctly captures the context's Vert.x —
  `var ctx = Vertx.currentContext(); this.vertx = (ctx != null) ? ctx.owner() : Vertx.vertx();` — does **not** warn.
- But `PeeGeeQDatabaseSetupService:178` constructs `new PeeGeeQManager(config)` **without** passing that Vert.x.
- So `PeeGeeQManager:182-189` takes the `vertx == null` branch and calls `Vertx.vertx()` at line 187, executed on
  the event-loop thread `createCompleteSetup` runs on → the warning, once per setup.

**Impact.** Non-fatal (the run is green). But every setup spins up its **own** Vert.x — a fresh event loop plus a
20-thread `peegeeq-worker-pool` worker executor (`PeeGeeQManager:194`) — instead of reusing the context's. Resource
waste, and the testing-standards "manual `Vertx.vertx()`" antipattern in production code.

**Fix sketch + why it is its own task (not a drive-by).** Pass the captured `this.vertx` into the per-setup manager
(`PeeGeeQManager` has a Vert.x-accepting constructor — the `if (vertx != null)` branch). That flips ownership: the
manager would set `vertxOwnedByManager = false` and stop closing the Vert.x on `destroySetup`. That changes
teardown/lifecycle and isolation semantics (all setups in a context sharing one Vert.x), so it must be verified —
confirm `destroySetup` still releases per-setup resources and does not leak or prematurely close a shared Vert.x.
Requires a targeted run of `DatabaseSetupServiceIntegrationTest` plus the setup-lifecycle suites after the change.

### F2 — Related Vert.x test-side antipatterns flagged during the sweep (pre-existing)

Surfaced while reading files for the conversions; all pre-existing. Owner directed these be fixed one
file at a time (2026-06-27):

- **`OutboxFactoryCloseHookTest`** (`peegeeq-outbox`, `@Tag(CORE)`) — **converted 2026-06-27, pending
  owner verification.** Removed all seven banned `Future.await()` calls: the test now uses
  `@ExtendWith(VertxExtension.class)` + `VertxTestContext` and drives async close-hook completion via
  `.onComplete(testContext.succeeding(v -> testContext.verify(...)))` (mirrors the sibling CORE test
  `OutboxBlockingSafetyTest`). The `HookCapturingDatabaseService` stub no longer creates its own
  `Vertx.vertx()` — it takes the extension-managed instance via constructor (leak gone). The two tests
  that mix the async hook with the blocking, thread-affinity-guarded `QueueFactory.close()` keep
  `close()` on the JUnit worker thread (7b closes first; 7a drives the hook via the allowed
  `awaitCompletion(...)`, then closes). Banned-pattern grep clean; does not trip
  `OnSuccessExceptionSwallowingGuardTest`. Verify: `mvn test -pl :peegeeq-outbox -Dtest=OutboxFactoryCloseHookTest`.

- **`NativeConcurrencySmokeTest`** (`peegeeq-integration-tests`) — **converted 2026-06-27, pending owner
  verification.** Owner chose the observability-API route over a behavioral-only rewrite. Three coupled changes:
  - **Production** (`PgNativeQueueConsumer`): added four read-only accessors — `hasActiveListenConnection()`,
    `hasPendingListenReconnect()`, `isClosed()`, `isSubscribed()` — over the existing private LISTEN/close
    state. Replaces the test's reflection (`getDeclaredField`/`setAccessible`), which was banned and
    otherwise unavoidable: the consumer exposed no public API and the test is in a different module/package
    (package-private would not reach it).
  - **Build** (`peegeeq-integration-tests/pom.xml`): added an explicit `peegeeq-native` test dependency (the
    module previously reached native only transitively — the reason reflection was originally used).
  - **Test**: rewrote `testDestroySetupStopsNativeListenersCleanly` reactively — removed all six
    `CountDownLatch.await`, both `setPeriodic` readiness polls, the four reflection helpers, and the
    fire-and-forget `subscribe()`. LISTEN readiness now chains off `subscribe()`'s Future; the
    post-`destroySetup` wait uses one recursive `vertx.timer` poll (`pollUntil`); the four state assertions
    call the new accessors; appender-detach + best-effort setup cleanup run via `.eventually(...)`. Assertion
    logic and order preserved.
  - Verify (native changed → install first): `mvn -pl :peegeeq-native install -DskipTests`, then
    `mvn test -pl :peegeeq-integration-tests -Pintegration-tests -Dtest=NativeConcurrencySmokeTest` (and
    optionally `mvn test -pl :peegeeq-native` to confirm the accessor addition didn't disturb the module).

- **`RestApiExampleTest`** (`peegeeq-examples`) — **converted 2026-06-27, pending owner verification.** Removed
  the `vertx.timer(500).mapEmpty()` post-`deployVerticle` readiness delay; `setUp` now chains directly off
  `deployVerticle(...).onSuccess(...)`. Confirmed by reading `PeeGeeQRestServer.start()` that `startPromise`
  completes only after `HttpServer.listen()` succeeds — so `deployVerticle` success already implies the server
  is listening and the timer was pure waste (no production change needed). (Pre-existing unused
  `io.vertx.core.Future` import left in place — unrelated to the antipattern.) Verify:
  `mvn test -pl :peegeeq-examples -Pintegration-tests -Dtest=RestApiExampleTest`.

(Container hand-rolling — antipattern §2 — was also flagged in several files, but that is not a Vert.x issue and is
noted inline in the phase rows above, not here.)

## Follow-up sweep: remove ALL `"public"` references incl. comments (2026-06-21)

Owner directive after Phase E: remove every `"public"` schema reference from test code, comments included.
Re-scan (repo-wide `"public"` over `**/src/test/**/*.java` + the frontend) caught references the per-phase
config-literal greps had missed, including modules never in the original sweep (`peegeeq-bitemporal`,
`peegeeq-migrations`, `peegeeq-test-support`). Owner ruling: **"comments + setup guards"** (keep tests that
verify/protect public-schema behavior).

**Done:**
- Frontend: 3 PowerShell helper scripts (`scripts/create-test-setup.ps1`, `test-create-setup.ps1`,
  `setup-test-data.ps1`) `schema = "public"` → `"peegeeq_test"`; `test-constants.ts` doc comment reworded.
- Reworded 5 no-public-rule doc comments/Javadoc: `bitemporal/BiTemporalAggregateSummaryIntegrationTest:127`,
  `native/PgNativeQueueFactoryIntegrationTest:91`, `native/PgNativeQueueFactoryContractTest:38`,
  `examples/ConsumerGroupLoadBalancingDemoTest:170`, `outbox/OutboxFactoryContractTest:38`.
- Removed 3 `if (!"public".equals(schema))` setup guards (→ unconditional idempotent `CREATE SCHEMA IF NOT
  EXISTS`, behavior-identical): `bitemporal/PgBiTemporalEventStorePerformanceTest:123`,
  `db/PeeGeeQManagerTimerGuardTest:519`, `db/PeeGeeQManagerCloseLogLevelTest:287`. The two db classes verified
  GREEN 2026-06-21 (`logs/public-sweep-db-20260621.txt`: 9 tests, 0 failures); `bitemporal/PgBiTemporalEventStorePerformanceTest` guard removal still to be run under its perf profile.

**Kept by ruling (verify/protect public handling — NOT "tests using public"):**
`migrations/CustomSchemaIntegrationTest:164` (asserts flyway history NOT in public),
`db/PostgreSqlIdentifierValidatorTest:238` (validator accepts `"public"`),
`test-support/PeeGeeQTestSchemaInitializerSchemaParameterTest:92-93` (`testInitializeSchemaWithPublicSchema`),
`management-ui/src/tests/fixtures/database-cleanup.ts:61` (protective `NOT IN (…,'public')`).

**Docs swept 2026-06-21:** all `public` schema **example snippets** in `peegeeq-management-ui/docs` converted to
`peegeeq_test` — 10 markdown files (active testing guide + the enhancements doc's example test code + 8 `archive/`
docs: `schema: 'public'`, `PEEGEEQ_DATABASE_SCHEMA="public"`, form `default: "public"`, `.fill('public')`,
`toHaveValue('public')`) plus the postman `Event_Store_Management_API` create-setup body (`\"schema\": \"public\"`).
**Kept** (documentation *of* the rule, not usage — same basis as the ③ tests): prose that describes the no-public
principle / this sweep / a known bug / form defaults, where `public` is the necessary subject term
(e.g. PHASE-A "search_path has no `, public` fallback" / "never `public`"; MANAGEMENT_UI_ENHANCEMENTS "the
no-`public`-in-tests sweep"; AGGREGATE-STREAM "manager pinned to `public`"; COVERAGE_GAPS form-default description).
The pervasive Java `public` keyword in code snippets is not a schema reference and is untouched.

## Follow-up: test-resource `.properties` files (2026-06-26)

A re-scan that **included resource files** — every prior sweep grep was scoped to
`**/src/test/**/*.java` + frontend + docs `.md`, so `.properties`/`.yml` resources were never
checked — found two test-resource files still carrying `peegeeq.database.schema=public`. Both
converted to `peegeeq_test`:

- `peegeeq-outbox/src/test/resources/peegeeq-test.properties:7`. Loaded only by
  `OutboxConsumerIntegrationTest.testConstructorWithClientFactory_WithConfiguration` and three
  `OutboxFactoryRegistrarTest` methods, all via `new PeeGeeQConfiguration("test", new Properties())` —
  construction-only (`assertNotNull`/`assertInstanceOf`); none reads the schema, connects, or
  provisions (the file's `host=test-host` is non-resolvable). Behavior-safe.
- `peegeeq-bitemporal/src/test/resources/peegeeq-development.properties:11`, plus its stale header
  comment `(overridden by system properties in tests)` → `(overridden by explicit PeeGeeQConfiguration
  constructor args in tests)` (D2 abolished the system-property channel). Loaded only by
  `VersionLineageIntegrationTest`, whose 7-arg constructor passes `PostgreSQLTestConstants.TEST_SCHEMA`
  explicitly (verified at the line-125 site; S3 records all four sites converted), so the file value
  was already overridden/dead. Behavior-safe.

**Verified GREEN 2026-06-26** (owner-run): `peegeeq-outbox` integration + bitemporal
`VersionLineageIntegrationTest`.

**Methodology note:** completeness greps for the no-`public` rule must include `.properties`/`.yml`
resources, not just `.java`/frontend/`.md`. This is the third scope-gap of the same kind (Phase E
missed `.fill('public')`; the 2026-06-21 follow-up found modules never in the original sweep; this
found resource files). Repo is now grep-clean for schema-`public` literals across `.java`, frontend,
docs, **and** test resources.
