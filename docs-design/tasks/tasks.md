# PeeGeeQ Task Index

Branch: `feature/offset-watermark-phase1`  
Last updated: 2026-05-09

---

## Active Tasks

### [CONFIG ARCHITECTURE — Replace Process-Wide Globals with Per-Instance Isolation](PEEGEEQ_CONFIG_ARCHITECTURE_REPLACE_PROCESS_GLOBALS_WITH_INSTANCE_ISOLATION.md)

**Status:** IN PROGRESS — Phases 0a–4 complete; **Phase 5 next**  
**Created:** 2026-05-07

Root defect: `PeeGeeQConfiguration` sources values from JVM System properties and OS env vars — process-wide globals that cannot express per-instance configuration in a multi-tenant JVM. Four distinct problems, one root cause.

| Problem | Description | Scope | Status |
|---|---|---|---|
| A | Production code reads directly from System, bypassing `PeeGeeQConfiguration` | 3 classes | Phases 0a–0c complete |
| B | Test code writes to System to pass config in | ~188 files, 8 modules | Phases 1–4 complete; Phase 5 next |
| C | `loadProperties()` sweeps process-wide globals — root architectural defect | `PeeGeeQConfiguration` | Phase 11 |
| D | Spring bridge writes `peegeeq.*` to System at runtime | `BiTemporalTxConfig` | Phase 10 |

**Phase summary:**

| Phase | Description | Status |
|---|---|---|
| 0a | Fix `PgBiTemporalEventStore` System.getProperty bypass | Complete |
| 0b | Fix `VertxPerformanceOptimizer` System.getProperty bypass | Complete |
| 0c | Fix `SystemInfoCollector` System.getProperty bypass | Complete |
| 1 | `peegeeq-db` BaseIntegrationTest | Complete |
| 2 | `peegeeq-db` remaining integration tests | Complete |
| 3 | `peegeeq-db` core (non-container) tests | Complete |
| 4 | `peegeeq-db` System-property tests: add @ResourceLock | Complete |
| 5 | `peegeeq-bitemporal` tests (~30 files) | **NEXT** |
| 6 | `peegeeq-outbox` tests (~60 files) | Not started |
| 7 | `peegeeq-native` tests (~39 files) | Not started |
| 8 | `peegeeq-examples` tests (~29 files) | Not started |
| 9 | `peegeeq-rest` tests (~3 files) | Not started |
| 10 | `peegeeq-examples-spring` BiTemporalTxConfig (Problem D) | Not started |
| 11 | Remove System sweep from `loadProperties()` (Problem C root fix) | Not started |
| 12 | Deprecate zero-arg and single-arg constructors | Not started |

**Standard fix pattern:**
```java
Properties testProps = PeeGeeQTestConfig.builder()
    .from(postgres)
    .schema("public")
    .property("peegeeq.database.pool.min-size", "1")
    .property("peegeeq.database.pool.max-size", "3")
    .build();
PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
```

**Permitted System.setProperty callers (do not migrate):**
- `PgPoolConfigPropertyBindingTest.java`
- `PeeGeeQConfigurationTest.java`
- `SystemPropertiesConfigurationDemoTest.java`
- `SystemPropertiesConfigurationExampleTest.java`

---

## Completed Tasks

### [onSuccess Exception Swallowing — Refactor](PEEGEEQ_REFACTOR_ONSUCESS_EXCEPTION_SWALLOWING.md)

**Status:** ALL PHASES COMPLETE (2026-05-06)  
**Created:** 2026-05-05

Vert.x silently swallows exceptions thrown synchronously inside `.onSuccess()`, routing them to `vertx.exceptionHandler` rather than failing the test. Fixed confirmed Tier 3 failures (Phase 1), added `.onFailure(testContext::failNow)` hardening (Phase 2), and migrated Tier 1 to canonical `.onComplete(testContext.succeeding(...))` pattern (Phase 3).

**Canonical pattern:**
```java
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    assertEquals(expected, actual);
    testContext.completeNow();
})))
```

---

### [PostgreSQL Connection Management and HAProxy Failover](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md)

**Status:** COMPLETE  
**Created:** 2025-08-26 (updated 2026-05-06)

Documents the full Vert.x reactive pool connection management architecture and the HAProxy failover integration test design. Key design principle: PeeGeeQ delegates database-tier failover to infrastructure (HAProxy + PgBouncer); application-tier failover (ConnectionRouter + Consul) is a separate plane.
