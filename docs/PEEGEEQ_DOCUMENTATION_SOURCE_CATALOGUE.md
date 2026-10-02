# PeeGeeQ Documentation Source Catalogue

**Status:** CURRENT CONSOLIDATION INDEX

**Updated:** 2026-09-14

## Purpose

This catalogue makes every Markdown document under `docs-design` discoverable from the maintained
documentation set. It does not change a document's implementation status. Exact content and
heading-level disposition are protected by the
[consolidation ledger](../docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md).

A catalogue entry is not an endorsement of its claims. Proposed, superseded, historical, and dated
documents remain explicitly separated from current product contracts.

Documents whose source path is under `docs-design/_archived/` are preserved evidence, even when
they remain listed under their original subject category for discoverability. The recorded status
describes the source at its reviewed revision; the archive path is its current authority status.

## Summary

| Category | Documents |
|---|---:|
| Architecture and APIs | 2 |
| Messaging and Subscriptions | 8 |
| Event Store and Data Management | 4 |
| Operations and Observability | 14 |
| Contributor, Testing, and CI | 11 |
| Proposed and Unimplemented Systems | 9 |
| Historical Evidence and Completed Work | 49 |
| Project Governance and Internal Notes | 5 |
| **Total** | **102** |

## Architecture and APIs

### [PeeGeeQ Call Propagation Guide](<../docs-design/peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md>)

- Recorded status: **Status:** CURRENT ARCHITECTURE GUIDE
- Route: Consolidate current architecture into Architecture or REST documentation
- Source: `docs-design/peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md`

### [PeeGeeQ Management UI - Architecture and Design](<../docs-design/peegeeq-management-ui/PEEGEEQ_MANAGMENT_UI_ARCHITECTURE.md>)

- Recorded status: Implementation Status:
- Route: Consolidate current architecture into Architecture or REST documentation
- Source: `docs-design/peegeeq-management-ui/PEEGEEQ_MANAGMENT_UI_ARCHITECTURE.md`

## Messaging and Subscriptions

### [Consumer Group Fan-Out Design: Hybrid Queue/Pub-Sub](<../docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md>)

- Recorded status: **Status**: Design Specification (with Implementation Status)
- Route: Consolidate current behavior into Consumer Groups or Ordering
- Source: `docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`

### [Consumer Group Fan-Out Guide](<../docs-design/_archived/superseded-guides/PEEGEEQ_CONSUMER_GROUP_FANOUT_GUIDE.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate current behavior into Consumer Groups or Ordering
- Source: `docs-design/_archived/superseded-guides/PEEGEEQ_CONSUMER_GROUP_FANOUT_GUIDE.md`

### [Partitioned Consumption Design and Implementation Record](<../docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md>)

- Recorded status: **Status:** IMPLEMENTED BASELINE — PRE-GA VALIDATION OPEN
- Route: Consolidate current behavior into Consumer Groups or Ordering
- Source: `docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md`

### [Documentation Updates for CausationId Implementation](<../docs-design/_archived/completed-records/DOCUMENTATION_UPDATES_CAUSATION_ID.md>)

- Recorded status: **Status:** ✅ Complete
- Route: Consolidate current behavior into messaging guides
- Source: `docs-design/_archived/completed-records/DOCUMENTATION_UPDATES_CAUSATION_ID.md`

### [CloudEvents Support and Integration in PeeGeeQ](<../docs-design/event-sourcing-messaging/PEEGEEQ_CLOUDEVENTS_SUPPORT_AND_INTEGRATION_GUIDE.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate current behavior into messaging guides
- Source: `docs-design/event-sourcing-messaging/PEEGEEQ_CLOUDEVENTS_SUPPORT_AND_INTEGRATION_GUIDE.md`

### [Advanced Messaging Patterns Demo Tests Implementation Plan](<../docs-design/_archived/completed-records/PEEGEEQ_MESSAGING_PATTERNS_IMPLEMENTATION_PLAN.md>)

- Recorded status: **Status:** COMPLETE — historical implementation record; reconciled 2026-09-05. The ten example scenarios exist (two class
- Route: Consolidate current behavior into messaging guides
- Source: `docs-design/_archived/completed-records/PEEGEEQ_MESSAGING_PATTERNS_IMPLEMENTATION_PLAN.md`

### [PeeGeeQ Outbox Partitioned Ordering Guide](<../docs-design/_archived/superseded-guides/PEEGEEQ_OUTBOX_PARTITIONED_ORDERING_COMPLETE_GUIDE.md>)

- Recorded status: **Status:** IMPLEMENTED BASELINE — obsolete pre-implementation guidance removed
- Route: Consolidate current behavior into messaging guides
- Source: `docs-design/_archived/superseded-guides/PEEGEEQ_OUTBOX_PARTITIONED_ORDERING_COMPLETE_GUIDE.md`

### [Server-Side Filtering: Technical Design & Implementation Guide](<../docs-design/event-sourcing-messaging/PEEGEEQ_SERVER_SIDE_FILTERING_GUIDE.md>)

- Recorded status: **Status:** Production Ready
- Route: Consolidate current behavior into messaging guides
- Source: `docs-design/event-sourcing-messaging/PEEGEEQ_SERVER_SIDE_FILTERING_GUIDE.md`

## Event Store and Data Management

### [PeeGeeQ Event Causality Guide](<../docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_EVENT_CAUSALITY_GUIDE.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate implemented behavior into the Event Store Guide
- Source: `docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_EVENT_CAUSALITY_GUIDE.md`

### [PeeGeeQ Bi-Temporal Event Subscriptions](<../docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_SUBSCRIPTIONS_GUIDE.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate implemented behavior into the Event Store Guide
- Source: `docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_SUBSCRIPTIONS_GUIDE.md`

### [PeeGeeQ Schema Configuration Design](<../docs-design/schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md>)

- Recorded status: **Status:** IMPLEMENTED BASELINE — historical plans and contradictory checklists removed
- Route: Consolidate current contracts into Database Setup and Configuration
- Source: `docs-design/schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md`

### [PeeGeeQ Schema Consolidation — Historical Decision Record](<../docs-design/_archived/completed-records/PEEGEEQ_SCHEMA_CONSOLIDATION_GUIDE.md>)

- Recorded status: **Status:** SUPERSEDED — retained for architectural history; do not use as an implementation guide
- Route: Consolidate current contracts into Database Setup and Configuration
- Source: `docs-design/_archived/completed-records/PEEGEEQ_SCHEMA_CONSOLIDATION_GUIDE.md`

## Operations and Observability

### [PeeGeeQ Outbox Consumer Crash Recovery: Complete Guide](<../docs-design/analysis/PEEGEEQ_CRASH_RECOVERY_GUIDE.md>)

- Recorded status: **Status:** ✅ **CONFIRMED AND ADDRESSED** - The system has a comprehensive recovery mechanism in place.
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/analysis/PEEGEEQ_CRASH_RECOVERY_GUIDE.md`

### [PeeGeeq Connection Management and HAProxy Failover](<../docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md>)

- Recorded status: **Status**: REFERENCE
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md`

### [HAProxy PostgreSQL Routing Without Patroni](<../docs-design/_archived/superseded-guides/PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md>)

- Recorded status: **Status**: REFERENCE
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/superseded-guides/PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md`

### [PeeGeeQ Grafana Dashboard Enhancement - Implementation Summary](<../docs-design/_archived/completed-records/grafana-dashboard-enhancement-summary.md>)

- Recorded status: **Status**: ✅ **COMPLETE AND TESTED**
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/completed-records/grafana-dashboard-enhancement-summary.md`

### [PeeGeeQ Hardware-Aware Performance Dashboard Guide](<../docs-design/performance/grafana-hardware-profiling-dashboard-guide.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/performance/grafana-hardware-profiling-dashboard-guide.md`

### [PeeGeeQ Performance Tuning Harness](<../docs-design/performance/PeeGeeQ-Performance-Tuning-Harness.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/performance/PeeGeeQ-Performance-Tuning-Harness.md`

### [Performance-Test Integration Validation Record](<../docs-design/_archived/historical-evidence/performance-tests-integration-validation-report.md>)

- Recorded status: **Status:** HISTORICAL SNAPSHOT — NOT AN ACTIVE DELIVERY PLAN
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/historical-evidence/performance-tests-integration-validation-report.md`

### [PeeGeeQ Performance Test Results](<../docs-design/_archived/historical-evidence/PerformanceTestResults_2025-01-11.md>)

- Recorded status: **Status:** Ready for Live Testing
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/historical-evidence/PerformanceTestResults_2025-01-11.md`

### [PeeGeeQ Performance Test Results](<../docs-design/_archived/historical-evidence/PerformanceTestResults_2025-09-11.md>)

- Recorded status: **Status:** ✅ **ALL TESTS PASSED**
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/historical-evidence/PerformanceTestResults_2025-09-11.md`

### [Phase 2.1.5: Hardware Profiling Infrastructure Implementation](<../docs-design/_archived/completed-records/phase-2-1-5-hardware-profiling-implementation.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/completed-records/phase-2-1-5-hardware-profiling-implementation.md`

### [Consumer Group Fan-Out Trace Propagation](<../docs-design/_archived/completed-records/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md>)

- Recorded status: **Status:** IMPLEMENTED — former proposal reconciled to current behavior
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/completed-records/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md`

### [Real-Time Monitoring Endpoints Implementation Record](<../docs-design/_archived/completed-records/MONITORING_ENDPOINTS_IMPLEMENTATION_PLAN.md>)

- Recorded status: **Status:** IMPLEMENTED — HISTORICAL PLAN CLOSED
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/completed-records/MONITORING_ENDPOINTS_IMPLEMENTATION_PLAN.md`

### [PeeGeeQ Tracing Architecture Guide](<../docs-design/_archived/superseded-guides/PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md>)

- Recorded status: **Status**: ✅ Production-Ready
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/superseded-guides/PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md`

### [PeeGeeQ Distributed Tracing User Guide](<../docs-design/_archived/superseded-guides/PEEGEEQ_TRACING_USER_GUIDE.md>)

- Recorded status: **Status**: ✅ Production-Ready
- Route: Consolidate supported operations; retain dated evidence and open gaps
- Source: `docs-design/_archived/superseded-guides/PEEGEEQ_TRACING_USER_GUIDE.md`

## Contributor, Testing, and CI

### [main-prompt](<../docs-design/_archived/superseded-guides/main-prompt.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/_archived/superseded-guides/main-prompt.md`

### [PeeGeeQ Jenkins CI on VMware ESXi](<../docs-design/dev/PEEGEEQ_JENKINS_ESXI_CI_SETUP.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/dev/PEEGEEQ_JENKINS_ESXI_CI_SETUP.md`

### [Password-Free SSH from WSL to the PeeGeeQ Linux VM](<../docs-design/dev/PEEGEEQ_WSL_PASSWORDLESS_SSH_SETUP.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/dev/PEEGEEQ_WSL_PASSWORDLESS_SSH_SETUP.md`

### [PeeGeeQ Coding Principles & Standards](<../docs-design/dev/pgq-coding-principles.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/dev/pgq-coding-principles.md`

### [Maven Toolchains - How It Works](<../docs-design/testing/MAVEN_TOOLCHAINS_EXPLAINER.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/testing/MAVEN_TOOLCHAINS_EXPLAINER.md`

### [E2E Test Execution Guide (Multi-Terminal Setup)](<../docs-design/testing/PEEGEEQ_E2E_TEST_SETUP_GUIDE.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/testing/PEEGEEQ_E2E_TEST_SETUP_GUIDE.md`

### [Subscription Persistence - Test Coverage](<../docs-design/_archived/historical-evidence/PEEGEEQ_SUBSCRIPTION_PERSISTENCE_TEST_COVERAGE.md>)

- Recorded status: **Status**: ✅ Production Ready
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/_archived/historical-evidence/PEEGEEQ_SUBSCRIPTION_PERSISTENCE_TEST_COVERAGE.md`

### [PeeGeeQ Async Test Guard](<../docs-design/testing/PEEGEEQ_TEST_GUARD.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/testing/PEEGEEQ_TEST_GUARD.md`

### [PeeGeeQ Error Handling Antipatterns](<../docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`

### [PeeGeeQ Testing Patterns](<../docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md>)

- Recorded status: **Status:** CURRENT COMPANION GUIDE
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md`

### [PeeGeeQ Test Commands Quick Reference](<../docs-design/testing/PEEGEEQ-TEST-COMMANDS.md>)

- Recorded status: Not explicitly stated
- Route: Consolidate stable instructions into contributor/testing guides; retain normative sources
- Source: `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`

## Proposed and Unimplemented Systems

### [PeeGeeQ SSL/TLS Implementation Recommendations](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/SSL-Implementation-Recommendations.md>)

- Recorded status: **Status:** Proposed
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/SSL-Implementation-Recommendations.md`

### [PeeGeeQ Durable Subscriptions Option Plan (Outbox + Bi-Temporal)](<../docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md>)

- Recorded status: **Status:** Task 4 bitemporal runtime implemented, committed, and focused-verification complete; broader outbox/operations proposals are not implemented
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md`

### [PeeGeeQ Schema Registry — Design](<../docs-design/schema-registry/PEEGEEQ_SCHEMA_REGISTRY_DESIGN.md>)

- Recorded status: **Status**: PROPOSED — NOT IMPLEMENTED; reconciled 2026-08-26
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/schema-registry/PEEGEEQ_SCHEMA_REGISTRY_DESIGN.md`

### [PeeGeeQ Authentication and Authorization Decision Summary](<../docs-design/security-authentication-authorisation/DESIGN_DECISIONS_SUMMARY.md>)

- Recorded status: **Status:** DESIGN DIRECTION RECORDED — NOT APPROVED OR IMPLEMENTED
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/security-authentication-authorisation/DESIGN_DECISIONS_SUMMARY.md`

### [PeeGeeQ Authentication & Authorization Design](<../docs-design/security-authentication-authorisation/PEEGEEQ_AUTHENTICATION_AUTHORIZATION_DESIGN.md>)

- Recorded status: **Status:** PROPOSED — NOT IMPLEMENTED; reconciled 2026-08-26
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/security-authentication-authorisation/PEEGEEQ_AUTHENTICATION_AUTHORIZATION_DESIGN.md`

### [**Management Summary REST Interfaces over Message Queues**](<../docs-design/_archived/superseded-guides/mq-rest-client-discussion-brief.md>)

- Recorded status: Not explicitly stated
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/_archived/superseded-guides/mq-rest-client-discussion-brief.md`

### [REST Interfaces over Message Queues: Architecture, Trade-offs, and Mitigation](<../docs-design/transactional-rest-api/mq-rest-client-discussion.md>)

- Recorded status: Not explicitly stated
- Route: Retain as design material; do not present as a current contract
- Source: `docs-design/transactional-rest-api/mq-rest-client-discussion.md`

### [PeeGeeQ Transactional Pattern Plugin Model](<../docs-design/transactional-rest-api/PEEGEEQ_PLUGIN_MODEL_TRANSACTIONAL_PATTERNS_DESIGN.md>)

- Recorded status: **Status:** REJECTED — OUT OF PEEGEEQ PRODUCT SCOPE
- Route: Retain as historical design analysis; do not present as a current contract or roadmap
- Source: `docs-design/transactional-rest-api/PEEGEEQ_PLUGIN_MODEL_TRANSACTIONAL_PATTERNS_DESIGN.md`

### [Transactional REST API Design](<../docs-design/transactional-rest-api/PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md>)

- Recorded status: **Status:** REJECTED — OUT OF PEEGEEQ PRODUCT SCOPE
- Route: Retain as historical design analysis; the final product-decision note is authoritative
- Source: `docs-design/transactional-rest-api/PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md`

## Historical Evidence and Completed Work

### [Consumer Group API Updates - Version 1.1.0](<../docs-design/_archived/API_UPDATE_CONSUMER_GROUP_v1.1.0.md>)

- Recorded status: **Status:** ✅ Production Ready
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/API_UPDATE_CONSUMER_GROUP_v1.1.0.md`

### [Consumer Group Source Verification Findings](<../docs-design/_archived/CONSUMER_GROUP_SOURCE_VERIFICATION_FINDINGS.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/CONSUMER_GROUP_SOURCE_VERIFICATION_FINDINGS.md`

### [Consumer Mode Test Implementation Plan](<../docs-design/_archived/Consumer-Mode-Test-Implementation-Plan.md>)

- Recorded status: **Status**: Missing
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/Consumer-Mode-Test-Implementation-Plan.md`

### [Guaranteed Ordering for Concurrent Consumers - Architecture Design](<../docs-design/_archived/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md>)

- Recorded status: **Status**: COMPLETE v1.5 (Phases 1–5 shipped; Decision 2 deferred to a future phase)
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md`

### [JSONB Conversion Plan: From JSON Strings to JSONB Objects](<../docs-design/_archived/JSONB_CONVERSION_PLAN.md>)

- Recorded status: **Status**: Design Specification
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/JSONB_CONVERSION_PLAN.md`

### [BackfillService Performance & Concurrency Validation Guide](<../docs-design/_archived/PEEGEEQ_CONSUMER_GROUPS_BACKFILL_PERFORMANCE_VALIDATION.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PEEGEEQ_CONSUMER_GROUPS_BACKFILL_PERFORMANCE_VALIDATION.md`

### [PeeGeeQ Critical Gaps - Status & Resolution](<../docs-design/_archived/PEEGEEQ_CRITICAL_GAPS_STATUS.md>)

- Recorded status: **Status**: ✅ **ALL CRITICAL GAPS RESOLVED**
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PEEGEEQ_CRITICAL_GAPS_STATUS.md`

### [PeeGeeQ End-to-End Integration Smoke Test Implementation Plan](<../docs-design/_archived/PEEGEEQ_E2E_SMOKE_TEST_IMPLEMENTATION_PLAN.md>)

- Recorded status: **Status: COMPLETE**
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PEEGEEQ_E2E_SMOKE_TEST_IMPLEMENTATION_PLAN.md`

### [PgNativeQueueFactory Refactoring Plan](<../docs-design/_archived/PEEGEEQ_NATIVE_QUEUE_FACTORY_REFACTORING_PLAN.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PEEGEEQ_NATIVE_QUEUE_FACTORY_REFACTORING_PLAN.md`

### [Outbox Module Refactoring Plan](<../docs-design/_archived/PEEGEEQ_OUTBOX_MODULE_REFACTORING_PLAN.md>)

- Recorded status: **Status:** COMPLETE
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PEEGEEQ_OUTBOX_MODULE_REFACTORING_PLAN.md`

### [Test Standardization Report](<../docs-design/_archived/PEEGEEQ_OUTBOX_TEST_STANDARDIZATION_15_DEC_2025.md>)

- Recorded status: **Status:** Complete
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PEEGEEQ_OUTBOX_TEST_STANDARDIZATION_15_DEC_2025.md`

### [PostgreSQL Version Management for PeeGeeQ](<../docs-design/_archived/PostgreSQL-Version-Management.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/PostgreSQL-Version-Management.md`

### [REST Handler Error-Path Tests — TDD Implementation Plan](<../docs-design/_archived/REST-HANDLER-ERROR-PATH-TESTS-PLAN.md>)

- Recorded status: *Status: **COMPLETE** — All cycles (A1–A9, B1–B3, C1–C8, D1–D4, E1–E3) implemented and GREEN. 458 tests, 0 failures.*
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/REST-HANDLER-ERROR-PATH-TESTS-PLAN.md`

### [🔴 CRITICAL TEST COVERAGE GAP - Root Cause Analysis](<../docs-design/_archived/TEST_COVERAGE_GAP_ANALYSIS.md>)

- Recorded status: **Status:** Tests correctly identified the runtime error!
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/TEST_COVERAGE_GAP_ANALYSIS.md`

### [Vert.x Multi-Statement SQL Bug - Critical Analysis & Remediation](<../docs-design/_archived/VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md>)

- Recorded status: **Status:** FIXED
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/_archived/VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md`

### [BackfillScopePerformanceTest PENDING_ONLY Multi-Batch Failure Analysis](<../docs-design/_archived/historical-evidence/backfill-pending-only-multi-batch-failure.md>)

- Recorded status: Not explicitly stated
- Route: Retain investigation; promote only verified durable findings
- Source: `docs-design/_archived/historical-evidence/backfill-pending-only-multi-batch-failure.md`

### [Deadlock in BackfillScopePerformanceTest — teardown scope too broad](<../docs-design/_archived/historical-evidence/backfill-performance-test-teardown-deadlock.md>)

- Recorded status: Not explicitly stated
- Route: Retain investigation; promote only verified durable findings
- Source: `docs-design/_archived/historical-evidence/backfill-performance-test-teardown-deadlock.md`

### [PEEGEEQ_REVIEW](<../docs-design/_archived/completed-records/PEEGEEQ_REVIEW.md>)

- Recorded status: Not explicitly stated
- Route: Retain investigation; promote only verified durable findings
- Source: `docs-design/_archived/completed-records/PEEGEEQ_REVIEW.md`

### [What needs fixing (by priority)](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/OutboxFactory-review.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/OutboxFactory-review.md`

### [Top priority (fix these first)](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PeeGeeQManager-review.md>)

- Recorded status: **Status**: All top priority fixes and most medium priority improvements have been successfully implemented and tested.
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PeeGeeQManager-review.md`

### [PgClientFactory-review](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgClientFactory-review.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgClientFactory-review.md`

### [PgConnectionManager-review](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgConnectionManager-review.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgConnectionManager-review.md`

### [PgPoolConfig-review](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgPoolConfig-review.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgPoolConfig-review.md`

### [PeeGeeQ Application Shutdown Guide](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/PeeGeeQ-Shutdown-Guide.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/PeeGeeQ-Shutdown-Guide.md`

### [PgConnectionConfig-review](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/PgConnectionConfig-review.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/PgConnectionConfig-review.md`

### [Vert.x Instance Consolidation Refactoring Plan](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/Vert.x-Instance-review-Consolidation-Refactoring-Plan.md>)

- Recorded status: **Status**: Technical Debt - Planned Implementation
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/Vert.x-Instance-review-Consolidation-Refactoring-Plan.md`

### [Vert.x 5.x Patterns Guide](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/Vertx-5x-Patterns-Guide.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/Vertx-5x-Patterns-Guide.md`

### [Vert.x 5 Migration Guide](<../docs-design/code reviews/archived/vertx5-migration-code-reviews/vertx5-migration-general-guide.md>)

- Recorded status: **Status**: Production Ready
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/code reviews/archived/vertx5-migration-code-reviews/vertx5-migration-general-guide.md`

### [PeeGeeQ Management UI - Execution Checklist](<../docs-design/peegeeq-management-ui/archive/EXECUTION_CHECKLIST.md>)

- Recorded status: **Status:** COMPLETE
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/peegeeq-management-ui/archive/EXECUTION_CHECKLIST.md`

### [Walkthrough: PeeGeeQ Examples Expansion](<../docs-design/tasks/archive/bitemporal-examples-expansion-walkthrough.md>)

- Recorded status: **Status:** COMPLETE — verified against repository commit `09157c82` on 2026-08-26.
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/bitemporal-examples-expansion-walkthrough.md`

### [Configuration Property Wiring Audit](<../docs-design/tasks/archive/CONFIG-PROPERTY-WIRING-AUDIT.md>)

- Recorded status: **Status:** ACTIVE — tables reconciled against revision `32ab0371` and the current worktree on 2026-08-29
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/CONFIG-PROPERTY-WIRING-AUDIT.md`

### [Consumer Groups REST API Fixes](<../docs-design/tasks/archive/CONSUMER-GROUPS-REST-FIXES-20260604.md>)

- Recorded status: **Status:** All phases complete
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/CONSUMER-GROUPS-REST-FIXES-20260604.md`

### [Consumer Groups UI Redesign — Change Plan](<../docs-design/tasks/archive/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md`

### [Task: Fix peegeeq-management-ui Tests Not Running](<../docs-design/tasks/archive/management-ui-tests-not-running.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/management-ui-tests-not-running.md`

### [peegeeq-outbox — Module Audit Findings](<../docs-design/tasks/archive/OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md>)

- Recorded status: Current status: O1 through O4 are fixed. The detailed findings below retain their original
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md`

### [Outbox DLQ / Filter-Error Dead Code Audit](<../docs-design/tasks/archive/OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md>)

- Recorded status: **Status:** Steps 1–7 complete. All tests passing.
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md`

### [Outbox Schema Qualification Regression](<../docs-design/tasks/archive/OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md>)

- Recorded status: **Status:** COMPLETE — reconciled 2026-08-29 against commit `32ab0371`
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md`

### [Configuration Architecture Remediation: Replace Process-Wide Globals with Per-Instance Isolation](<../docs-design/tasks/archive/PEEGEEQ_CONFIG_ARCHITECTURE_REPLACE_PROCESS_GLOBALS_WITH_INSTANCE_ISOLATION.md>)

- Recorded status: Status: **COMPLETE** — Phases 0a–12 ✅ all done
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_CONFIG_ARCHITECTURE_REPLACE_PROCESS_GLOBALS_WITH_INSTANCE_ISOLATION.md`

### [PeeGeeQ `.onSuccess` Exception-Swallowing — Definitive Audit (2026-05-14)](<../docs-design/tasks/archive/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md`

### [Task: Remove `inflightProcessing` / `inflightFutures` Timeout Machinery and Audit `*Async` Naming](<../docs-design/tasks/archive/PEEGEEQ_OUTBOX_REMOVE_INFLIGHT_CLOSE_TIMEOUT.md>)

- Recorded status: Status: **COMPLETE** — implemented in commits `29a6379a` and `84ac1201`. Verified 2026-05-15: 40/40 outbox tests pass (`OutboxConsumerEdgeCasesCoverageTest`, `OutboxConsumerGroupFaultToleranceTest`, `OutboxConsumerGroupReviewFixesTest`); F3 `HungHandlerBlocking` now completes in ~10.8s (previously a 30-second hang). Absence-check passes: no `inflightProcessing` / `closeInflightTimeoutMs` / `inflightFutures` / `stopAsync` remain in `peegeeq-outbox/src/main`; only `OutboxConsumerGroup.closeAsync()` survives by design.
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_OUTBOX_REMOVE_INFLIGHT_CLOSE_TIMEOUT.md`

### [PostgreSQL Connection Management and HAProxy Failover — Gaps and Implementation Plan](<../docs-design/tasks/archive/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_GAPS.md>)

- Recorded status: **Status**: OPEN — plan verified against current source 2026-08-26
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_GAPS.md`

### [PostgreSQL Notice Handling Guide](<../docs-design/tasks/archive/PEEGEEQ_POSTGRES_NOTICE_HANDLING_DESIGN.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_POSTGRES_NOTICE_HANDLING_DESIGN.md`

### [Refactoring Plan: Fix `onSuccess` Exception Swallowing in Tests](<../docs-design/tasks/archive/PEEGEEQ_REFACTOR_ONSUCESS_EXCEPTION_SWALLOWING.md>)

- Recorded status: Status: **PHASE 1 COMPLETE — PHASE 2 COMPLETE — PHASE 3 COMPLETE (2026-05-15)**
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_REFACTOR_ONSUCESS_EXCEPTION_SWALLOWING.md`

### [Testcontainers Usage Patterns in PeeGeeQ](<../docs-design/tasks/archive/PEEGEEQ_TESTCONTAINERS_PATTERNS.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/PEEGEEQ_TESTCONTAINERS_PATTERNS.md`

### [CRITICAL: Schema Processing Gaps — Findings and Remediation Tasks](<../docs-design/tasks/archive/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md`

### [Session Handover — 2026-08-12 (reconciled 2026-08-29)](<../docs-design/tasks/archive/SESSION-HANDOVER-20260812.md>)

- Recorded status: **Status:** SUPERSEDED by
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/SESSION-HANDOVER-20260812.md`

### [Test Integrity Defect Remediation Plan](<../docs-design/tasks/archive/TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md>)

- Recorded status: Not explicitly stated
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`

### [Guard Tiers 4, 5, 7 — Audit and Remediation Plan](<../docs-design/tasks/archive/TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md>)

- Recorded status: **Status:** ACTIVE — reopened because the current no-exceptions rule invalidates Tier-5 exemptions
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md`

### [Wave 2 Uncommitted Changes — Testing-Standards Audit](<../docs-design/tasks/archive/WAVE2_UNCOMMITTED_AUDIT_20260516.md>)

- Recorded status: **Status**: CLEAN. Documentation file; content consistent with other standards docs. No changes required.
- Route: Retain intact; extract only verified facts still useful to a maintained guide
- Source: `docs-design/tasks/archive/WAVE2_UNCOMMITTED_AUDIT_20260516.md`

## Project Governance and Internal Notes

### [Claude tells me that its a liability](<../docs-design/_archived/internal-notes/Claude tells me that its a liability.md>)

- Recorded status: Not explicitly stated
- Route: Retain outside the product documentation
- Source: `docs-design/_archived/internal-notes/Claude tells me that its a liability.md`

### [PeeGeeQ Documentation Consolidation Checklist](<../docs-design/_archived/governance/DOCUMENTATION_CONSOLIDATION_CHECKLIST.md>)

- Recorded status: **Status:** ARCHIVED — STRUCTURAL CONSOLIDATION AND SOURCE-ARCHIVE PHASES COMPLETE
- Route: Retain as task, audit, handover, or consolidation evidence
- Source: `docs-design/_archived/governance/DOCUMENTATION_CONSOLIDATION_CHECKLIST.md`

### [PeeGeeQ Documentation Archive](<../docs-design/_archived/README.md>)

- Recorded status: **Status:** HISTORICAL AND SUPERSEDED MATERIAL — NOT A CURRENT PRODUCT CONTRACT
- Route: Retain as the archive entry point
- Source: `docs-design/_archived/README.md`

### [PeeGeeQ Documentation Consolidation Ledger](<../docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md>)

- Recorded status: **Status:** SECTION ROUTING AND ARCHIVE MIGRATION COMPLETE — SOURCE DETAIL RETAINED
- Route: Retain as task, audit, handover, or consolidation evidence
- Source: `docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md`

### [PeeGeeQ Consolidated Task Register](<../docs-design/tasks/tasks.md>)

- Recorded status: **Status:** ACTIVE
- Route: Retain as task, audit, handover, or consolidation evidence
- Source: `docs-design/tasks/tasks.md`

## Lossless completion rule

A document leaves active design space only after its complete heading map has an exact maintained
destination or an explicit retained historical location. Source files remain available throughout
that process. Conflicting and obsolete claims are annotated rather than silently discarded.
