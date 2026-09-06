# Performance-Test Integration Validation Record

**Status:** HISTORICAL SNAPSHOT — NOT AN ACTIVE DELIVERY PLAN

**Original validation date:** 2025-09-19

**Last reconciled:** 2026-09-06

## Purpose

This document preserves the useful outcome of a 2025 validation of PeeGeeQ test-support
infrastructure. Its old phase schedule, recommendations, and unchecked boxes are superseded by the
[consolidated task register](../tasks/tasks.md). They must not be treated as current assignments.

The original source plan named `docs/performance/performance-tests-integration.md`; that file is not
present in the reviewed repository. This record therefore no longer claims to validate a live plan.

## What the historical review established

At the time of the review, the first infrastructure slice existed:

- `PeeGeeQTestContainerFactory` provided named PostgreSQL test profiles;
- `PeeGeeQTestBase` standardized container and metrics setup;
- `ParameterizedPerformanceTestBase` provided a reusable base for parameterized measurements; and
- `PerformanceMetricsCollector` captured and compared performance snapshots.

Those source files are still present at repository baseline
`7db748b8e77f3aba850be7b73547d192dac5b83f`. The historical report recorded focused test counts for
that earlier baseline, but those counts are not evidence for the current checkout.

## Reconciliation of the old “pending phases”

The old report described Phases 2–4 as pending and attached a three-week implementation schedule.
That framing is no longer authoritative:

| Former item | Current treatment |
|---|---|
| Consumer-mode performance base | A `ConsumerModePerformanceTestBase` and focused test now exist; the old “missing” claim is obsolete |
| Parameterized suite/result abstractions | No approved task requires these exact abstractions |
| Generic performance matrix generator | No approved task requires this exact class or package |
| Wholesale migration of module tests | Not an approved objective; migrations must be justified by behaviour or maintenance value |
| Generic CI/CD performance integration | Superseded by explicit, bounded release gates in the consolidated register |

The names and shapes proposed in the old report are not architectural requirements. New test
abstractions should be added only when a concrete test demonstrates repeated value.

## Current approved performance work

The only active performance-related work is the bounded evidence requested by the consolidated
register, principally the partitioned-consumption pre-GA gates. Those gates cover long-duration
stability, failure and rebalance chaos, database contention and pool pressure, tenant isolation,
and recovery behaviour.

Results must record:

- repository commit and configuration;
- host, VM, CPU, memory, storage, PostgreSQL, and container limits;
- workload shape, duration, concurrency, partition count, and payload size;
- throughput and latency distribution, not averages alone;
- resource saturation, pool pressure, errors, retries, and recovery time; and
- the exact command, profile, test scope, and per-class test totals.

Without that context, a throughput or latency number is not a release claim.

## Guidance for future test-support changes

Any new performance-test infrastructure must follow a bounded TDD cycle:

1. Start with a real performance question and an executable failing test or missing assertion.
2. Reuse the smallest existing fixture that can express the workload.
3. Add an abstraction only after the repetition is demonstrated in maintained tests.
4. Use real PostgreSQL containers and actual serialization and transport boundaries.
5. Keep correctness assertions alongside measurements so a fast incorrect result cannot pass.
6. Separate deterministic regression thresholds from exploratory benchmark output.
7. Promote work to the consolidated register before assigning schedules or release status.

Mocking frameworks and mocked database or repository layers are not acceptable performance or
compatibility evidence.

## References

- [Consolidated task register](../tasks/tasks.md)
- [Testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- [Coding principles](../dev/pgq-coding-principles.md)
