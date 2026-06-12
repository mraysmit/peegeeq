# CRITICAL: Schema Processing Gaps — Findings and Remediation Tasks

## Status: SUPERSEDED IN PART — see "Architecture correction" below. The findings stand; the first remediation (S1–S3, system-property-driven) was architecturally WRONG and has been replaced by the explicit-schema remediation (12 Jun 2026, evening). Production fail-fast enforcement (D2) is the remaining open work.

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

**D2 — production fail-fast enforcement (OPEN, mandated last):** `PgConnectionConfig`
nullable schema + `PgConnectionManager` "using default schema" fallback;
`PeeGeeQConfiguration` null-tolerant schema resolution + 7-arg ctor null-schema tolerance;
`DatabaseSetupHandler` env lookup + `"peegeeq"` literal default; profile `.properties`
files (`schema=public` declarations; `${DB_SCHEMA:public}` env interpolation in
production.properties; parallel-test.properties missing the key); native module defaulting
nest (`PgNativeQueueBrowser` defaulting ctor + six `: "public"` fallbacks);
`OutboxQueueBrowser` 4-arg defaulting path; nine spring example apps' `schema = "public"`
field defaults; **migration/auto-migrate schema handling needs verification** (it recreated
tables in `public`, not the configured schema, when masking the spring free-rider race).

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
   - `DatabaseConfig.Builder` — **left as-is** (6 in `PeeGeeQDatabaseSetupServiceEnhancedTest`
     + 6 elsewhere): these are per-setup parameters for *new databases the setup service
     creates*, not the shared-container schema — proven green under tenant_ci unchanged.
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
