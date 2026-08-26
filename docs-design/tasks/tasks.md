# PeeGeeQ Task Index

**Last reconciled:** 2026-08-26
**Repository revision reviewed:** `09157c82`
**Release-gate evidence:** Jenkins build #36 at `e8d07e53`

This file is the entry point for task status under `docs-design`. It replaces the previous
pasted audit transcript, which was not a maintainable task register. Detailed design and
historical evidence remain in the linked documents.

## Verification Baseline

Jenkins build #36 ran the full pipeline from the beginning and finished successfully:

- Java: 4,022 tests, zero failures, zero errors, zero skipped
- Management UI unit: 95/95
- Management UI Playwright: 481 passed plus one flaky retry, 482 total
- Utilities unit: 836/836
- Utilities Playwright: 91/91

A green gate proves that implemented and selected tests passed. It does not prove that a
proposed feature exists, nor does it replace explicitly planned long-duration load or chaos
tests.

## Active Engineering Tasks

| Priority | Task | Current status | Remaining work |
|---|---|---|---|
| High | [Configuration Property Wiring Audit](pending/CONFIG-PROPERTY-WIRING-AUDIT.md) | Active | Resolve dead outbox concurrency setting, unwired pool minimum, stale timeout keys, system-property fallbacks, and add non-default behavior coverage |
| High | [Outbox Module Audit](OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md) | Partial | O1 dead `consumerThreads`; O2 options-start lifecycle; O4 duplicate-send metric |
| High | [Test Integrity Remediation](TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md) | Partial | D23 SSE flake investigation and Tier-9 ratchet debt: 98 calls across 31 files |
| Medium | [Outbox Schema Qualification](OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md) | Partial | Implement or map equivalent evidence for TC-S14 and TC-S15 |
| Medium | [Schema Processing Coverage](SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md) | Core complete | Multi-setup isolation P3 and distinct-Vert.x guard P4 |
| Medium | [PostgreSQL/HAProxy Gaps](pending/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_GAPS.md) | Open | Notification proxying, pool circuit breaker, replication failover, PgBouncer transaction mode |
| Medium | [Durable Subscriptions](../event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md) | Schema only | Runtime API/service, replay cursor, catch-up/live handoff, leases, end-to-end tests |
| Product decision | [Transactional REST API](../transactional-rest-api/PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md) | Proposed | Decide whether to implement domain-specific transactional endpoints; none currently exist |
| Release gate | [Partitioned Consumption Pre-GA Tests](../consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md#task-4-pre-ga-decision-checkpoints) | Open | Long-duration fanout, chaos, OLTP contention, isolation and partition-management validation |

## Unscheduled Product and Coverage Backlog

| Item | Verified state |
|---|---|
| [Schema Registry](../schema-registry/PEEGEEQ_SCHEMA_REGISTRY_DESIGN.md) | Proposed; UI is a “coming soon” placeholder and no registry backend exists |
| [Authentication and Authorization](../security-authentication-authorisation/PEEGEEQ_AUTHENTICATION_AUTHORIZATION_DESIGN.md) | Proposed; no auth module, JWT middleware or tenant-management implementation exists |
| [TypeScript REST Client Coverage](../peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md#phase-3-build-rest-client-into-peegeeq-management-ui---complete-2025-12-07) | Client is used by two UI pages; dedicated TypeScript unit and live-server integration tests remain open |

## Completed — Ready to Archive

| Task | Completion evidence |
|---|---|
| [Consumer Groups UI Redesign](CONSUMER-GROUPS-UI-REDESIGN-PLAN.md) | REST/UI lifecycle implemented; management UI suites ran in build #36 |
| [Management UI Tests Not Running](pending/management-ui-tests-not-running.md) | npm permissions and Playwright browser setup fixed; build #36 reached and passed UI suites |
| [Tier 4/5/7 Blocking Violations](TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md) | All checklist rows complete; guards and full Java gate pass |
| [Outbox DLQ / Filter Error Audit](OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md) | Steps 1–7 complete with regression coverage |
| [Bitemporal Examples Expansion](pending/bitemporal-examples-expansion-walkthrough.md) | Referenced examples exist and examples modules passed the full gate |
| [Monitoring Endpoints](../tracing-observability/MONITORING_ENDPOINTS_IMPLEMENTATION_PLAN.md) | WebSocket/SSE, lifecycle, CORS and metrics work complete |
| [Messaging Pattern Examples](../event-sourcing-messaging/PEEGEEQ_MESSAGING_PATTERNS_IMPLEMENTATION_PLAN.md) | Ten scenarios implemented; examples modules passed the full gate |

Completed files remain in their current locations until a dedicated archive move so that
existing inbound links are not broken.

## Historical Context

- [Session Handover — 2026-08-12](SESSION-HANDOVER-20260812.md) is superseded by the
  test-integrity P12 record and Jenkins build #36.
- Files under [`archive/`](archive/) and [`../_archived/`](../_archived/) are historical
  and are not active task sources.
- Design documents can contain proposals and historical implementation plans. Their
  explicit current-status block takes precedence over older narrative sections.

## Status Rules

Use one of these states at the top of every task document:

- **OPEN** — no implementation has started.
- **ACTIVE** — implementation or investigation is in progress.
- **PARTIAL** — some verified deliverables exist and the exact remainder is listed.
- **COMPLETE** — implementation and proportionate verification are recorded.
- **SUPERSEDED** — another linked document is now authoritative.
- **HISTORICAL** — retained for audit context and not an active task.

Every status update must state the reconciliation date and evidence source. Do not infer
runtime behavior solely from source inspection. Record whether evidence came from a focused
test, full Jenkins gate, repository scan, or design review.
