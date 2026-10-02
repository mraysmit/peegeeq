# PeeGeeQ Transactional Pattern Plugin Model

**Status:** REJECTED — OUT OF PEEGEEQ PRODUCT SCOPE

**Last reconciled:** 2026-09-14

**Repository baseline:** `7db748b8e77f3aba850be7b73547d192dac5b83f`

## Purpose

This document records a rejected candidate plugin model for transactional REST coordination. It
is retained as historical design analysis, not as an implementation description, delivery plan,
or product roadmap.

The repository currently has no `peegeeq-transactional-*` modules, transactional executor
registry, saga or reservation runtime, or `/api/v1/transactional/*` endpoints. Examples in the
superseded draft were illustrative and must not be interpreted as available APIs.

The product decision is recorded in the
[Transactional REST API design](PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md#final-product-decision-not-part-of-peegeeq).
The former task-register proposal was deleted because the capability is outside PeeGeeQ's generic
transactional-outbox scope. No implementation work is scheduled.

## Problem under consideration

PeeGeeQ already exposes outbox and event-store capabilities. A higher-level REST operation that
coordinates a domain change with those capabilities would have to define who owns the database
transaction and what can honestly be guaranteed when the domain service, PeeGeeQ, or the network
fails.

Four candidate patterns were explored:

| Candidate | Transaction owner | Intended topology | Principal trade-off |
|---|---|---|---|
| Inversion | Domain application | Shared database | Strong local atomicity, but couples the caller to PeeGeeQ transaction participation |
| Reservation | PeeGeeQ command log | Same or separate databases | Short transactions and durable work, but only eventual completion |
| Saga | Orchestrator | Separate databases/services | Explicit compensation, with substantial state-machine and operational complexity |
| Callback | PeeGeeQ while calling a service | Shared database | Simple surface, but holds a database transaction open across a network call and is not recommended |

These are alternatives for evaluation, not four components that should automatically be built.

## Candidate plugin boundary

If the product decision is approved, a minimal extension boundary could separate:

- an immutable request and result contract;
- a strategy capability and compatibility description;
- a registry that accepts only explicitly enabled strategies;
- a selection policy whose choice is visible to callers and operators; and
- strategy implementations packaged independently from the core REST service.

Automatic selection must never silently strengthen or weaken consistency. A caller must be able
to tell which strategy ran and which guarantees apply.

## Required product decisions

Before an implementation plan is added, the owner must decide:

1. Whether transactional coordination belongs in PeeGeeQ at all.
2. Which single initial use case and topology justify the feature.
3. Whether the domain application or PeeGeeQ owns the transaction.
4. The exact atomicity, delivery, ordering, retry, and compensation guarantees.
5. The idempotency key contract and duplicate-result behaviour.
6. Authentication, authorization, tenant isolation, and audit requirements.
7. Timeout and cancellation semantics, including indeterminate outcomes.
8. Persistence, recovery, observability, and operator controls.
9. Compatibility and migration policy for any public REST contract.

The callback candidate should be rejected unless a measured, bounded use case demonstrates that
holding a database transaction across an HTTP call is acceptable.

## Entry criteria for implementation

Implementation may start only after all of the following are recorded in the consolidated task
register:

- an approved product boundary and one bounded first strategy;
- public request, response, and error contracts;
- a failure-mode table covering crashes and network uncertainty at every boundary;
- an authorization and tenant-isolation model;
- measurable acceptance and performance criteria;
- a rollout, compatibility, and removal strategy; and
- an ordered test-first task breakdown.

## Testing requirements if approved

Development must follow the repository testing standards and TDD:

- write contract and failure tests before implementation;
- use real PostgreSQL containers for transaction and recovery behaviour;
- exercise actual HTTP serialization, transport, timeouts, and response parsing;
- verify idempotency by replaying real requests;
- verify tenant isolation and authorization at the protocol boundary;
- prove recovery by stopping and restarting real components; and
- benchmark transaction duration, pool pressure, recovery lag, and duplicate handling.

Mocking frameworks and mocked database or repository layers are not acceptable evidence for this
feature.

## Relationship to other documents

- [Transactional REST API design](PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md) contains the broader
  exploratory design.
- [Consolidated task register](../tasks/tasks.md) is the only authority for approved work and
  execution order.
- [Coding principles](../dev/pgq-coding-principles.md) and
  [testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md) govern any future
  implementation.

## Historical reconciliation

The previous version showed sample modules, endpoints, configuration, implementation classes,
completed checkmarks, and a seven-week schedule. None of those artifacts existed at the reviewed
repository baseline. They have therefore been reclassified as an unapproved candidate design,
and the false completion signals have been removed.
