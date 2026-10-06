# PeeGeeQ Testing Guide

**Status:** CURRENT CATEGORY GUIDE — NORMATIVE SOURCES CONDENSED 2026-10-06

This guide is the maintained navigation point for test classification, TDD, PostgreSQL integration
testing, asynchronous verification, build profiles, guard tests, and CI evidence.

## Normative sources during consolidation

- [Testing standards and antipatterns](../docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- [Testing patterns](../docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md)
- [Test commands](../docs-design/testing/PEEGEEQ-TEST-COMMANDS.md)
- [Test guard](../docs-design/testing/PEEGEEQ_TEST_GUARD.md)
- JDK toolchain and management UI E2E procedure: sections in [Test commands](../docs-design/testing/PEEGEEQ-TEST-COMMANDS.md). The earlier standalone explainers are archived under `docs-design/_archived/superseded-guides/`.

Commit `9e14170e` (2026-10-06) rewrote and condensed the antipatterns, test commands, and test
guard documents. The text removed from them is in git history at `f1c5d25d`. The removed
material was not mapped rule by rule to a recorded destination; the
[consolidation ledger](../docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md) records which
entries describe the earlier revisions. Historical test reports remain evidence rather than
permanent claims.

## Core rules

- Mockito and substitute mocking frameworks are prohibited.
- Database behavior is tested against real PostgreSQL with Testcontainers.
- Pure logic may use the core test category only when it performs no database operation.
- Asynchronous work must remain in composable Vert.x futures and every returned future must be
  chained or explicitly observed.
- Test completion must surface assertion, callback, setup, and cleanup failures.
- Teardown must deterministically release pools, clients, servers, timers, and containers.

## TDD loop

1. Establish the smallest observable behavior and write a failing test.
2. Make the smallest implementation change.
3. Rebuild the affected Maven reactor slice while compiling tests.
4. Run the narrowest tagged test scope.
5. Inspect per-class test counts and failures in the retained output.
6. Refactor only while the focused test remains green.

## Database and external boundaries

Do not replace PostgreSQL serialization, locking, transactions, notifications, schema behavior, or
failure semantics with in-memory substitutes. External adapters should exercise real serialization,
transport, parsing, error behavior, and cleanup where compatibility cannot be proven locally.

## Release testing

The approximately 90-minute all-tests profile is a release, nightly, or explicitly requested gate.
It is not the normal iteration loop. Failures should be reproduced and corrected with the smallest
relevant scope before the release gate is restarted.

## Historical evidence

Coverage summaries, failure analyses, and remediation plans retain the evidence and reasoning for
their revisions. They do not override the current standards or prove that a later revision passed.
