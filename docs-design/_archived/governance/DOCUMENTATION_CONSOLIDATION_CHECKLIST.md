# PeeGeeQ Documentation Consolidation Checklist

**Status:** ARCHIVED — STRUCTURAL CONSOLIDATION AND SOURCE-ARCHIVE PHASES COMPLETE

**Created:** 2026-09-06

**Scope:** Every Markdown document currently under `docs` and `docs-design`, including archived
records, completed investigations, proposals, test evidence, and environment-specific guides.

## Objective

Produce a clear core documentation set without losing any source detail. Current, validated
behavior belongs in `docs`. Design proposals, investigations, superseded guidance, and dated
evidence remain preserved and discoverable under `docs-design`.

## Non-loss requirements

- [x] Preserve the exact source document before moving, replacing, or removing it.
- [x] Record the source path, revision, status, and intended destination.
- [x] Map every source heading to a destination heading or an explicitly retained historical section.
- [x] Preserve rejected alternatives, design reasoning, dates, test counts, and repository baselines.
- [x] Annotate contradictions; never silently select one account and discard the other.
- [x] Separate static source observations from runtime evidence.
- [x] Keep unimplemented proposals out of current product contracts.
- [x] Update every inbound and outbound relative link after a move.
- [x] Run the banned-pattern check against every file changed during a phase.
- [x] Run Markdown link and formatting checks after every phase.
- [x] Do not delete or archive a source until its section map and coverage check are complete.
- [x] Keep Git provenance for any source already changed or removed in the working tree.

## Per-document migration record

Every source must receive a ledger entry with:

- source path and source revision;
- original document status;
- category and authoritative destination;
- heading-by-heading disposition;
- conflicts or stale claims discovered;
- runtime evidence supporting promoted claims;
- retained historical location;
- updated inbound links; and
- reviewer completion status.

## Phase 0 — Baseline and controls

- [x] Inventory the current `docs` directory.
- [x] Inventory every Markdown file under `docs-design`.
- [x] Define the documentation categories.
- [x] Establish the current-versus-historical authority boundary.
- [x] Create a categorized `docs/README.md`.
- [x] Capture source hashes for the 99-document pre-control consolidation baseline.
- [x] Create the [section-level migration ledger](../../tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md).
- [x] Verify the pre-existing deletion of
  `docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md`; preserve its exact
  original Git content under `docs-design/_archived` and map every heading in the ledger.


## Completion interpretation

A checked phase item means the subject has a maintained category destination, every detailed source
is retained and catalogued, and every source heading has a ledger disposition. It does not mean
historical prose was copied into a current contract or that a new runtime verification was inferred.

## Phase 1 — Getting Started and Examples

Destinations: focused getting-started guide, examples guide, and specialist example catalogues.

- [x] Publish a maintained Getting Started entry path while retaining the legacy Complete Guide and its anchors.
- [x] Remove no Complete Guide content until its separate main-document heading migration is complete.
- [x] Reconcile the messaging-pattern example implementation record with the Examples Guide.
- [x] Reconcile the bi-temporal examples walkthrough with the Examples Guide.
- [x] Preserve dated example coverage claims as historical evidence unless freshly verified.
- [x] Keep the Financial Services Event Catalogue clearly labelled as a specialist domain guide.

## Phase 2 — Architecture and Core Concepts

Destination: Architecture and API Guide, with focused component sections where necessary.

- [x] Route Design Fundamentals into the Architecture category while retaining its complete source.
- [x] Route implemented platform sequence flows into the Architecture category.
- [x] Keep planned sequence diagrams visibly separate from runtime documentation.
- [x] Route request and call-propagation architecture to the Architecture and API authority.
- [x] Route Management UI architecture to the Architecture and REST authorities.
- [x] Route lifecycle and shutdown guidance to Architecture and Operations.
- [x] Route PostgreSQL notification behavior to Architecture and Operations.
- [x] Route current fan-out architecture to Messaging while retaining its historical design record.

## Phase 3 — Messaging and Subscriptions

Destinations: Transactional Outbox, Consumer Groups, Ordering Patterns, and messaging-feature
sections.

- [x] Consolidate consumer-group fan-out behavior and lifecycle.
- [x] Consolidate backfill, zero-subscription protection, filtering, and cleanup behavior.
- [x] Consolidate crash recovery, dead-letter, retry, and shutdown contracts.
- [x] Complete the partitioned-consumption merge into the Ordering Patterns Guide.
- [x] Complete the outbox partitioned-ordering merge into the Ordering Patterns Guide.
- [x] Integrate server-side filtering guidance.
- [x] Integrate CloudEvents usage and compatibility guidance.
- [x] Integrate causation identifier semantics where they affect messaging.
- [x] Preserve historical API migrations and defect investigations outside the current contract.

## Phase 4 — Event Store and Data Management

Destinations: new Event Store Guide, Database Setup Guide, and Configuration Guide.

- [x] Create a focused Event Store Guide.
- [x] Merge bi-temporal causality guidance.
- [x] Merge non-durable subscription behavior.
- [x] Extract only implemented durable-subscription behavior from the mixed plan.
- [x] Retain unimplemented durable-subscription proposals in the design record.
- [x] Integrate keyset pagination into the Event Store Guide.
- [x] Merge current schema, tenancy, provisioning, and migration contracts.
- [x] Preserve the superseded schema-consolidation record.
- [x] Reconcile configuration audits with the authoritative property inventory.
- [x] Preserve historic configuration and schema defect records as evidence.

## Phase 5 — APIs and Integration

Destinations: REST API Reference, Service Manager Guide, and relevant architecture sections.

- [x] Declare OpenAPI and registered routes as the exact API authorities; retain the REST reference for a later semantic audit.
- [x] Do not promote unverified endpoint claims from design records into the REST reference.
- [x] Merge monitoring, consumer-group, causation, CloudEvents, and pagination endpoint details.
- [x] Merge the current Management UI to REST integration contract.
- [x] Keep historical gap analysis outside newly written normative sections.
- [x] Retain transactional REST, plugin, and messaging-facade proposals as unimplemented designs.

## Phase 6 — Operations and Observability

Destinations: Operations Guide and tracing documentation.

- [x] Create a consolidated Operations Guide.
- [x] Merge current PostgreSQL failover and HAProxy operating guidance.
- [x] Distinguish implemented failover behavior from open resilience gaps.
- [x] Merge crash recovery and orderly shutdown procedures.
- [x] Merge Grafana dashboard and hardware-profiling operation.
- [x] Merge supported performance-harness execution and tuning guidance.
- [x] Preserve dated performance results as immutable evidence.
- [x] Reconcile the two tracing user guides.
- [x] Reconcile tracing architecture material with the technical reference.
- [x] Merge consumer-group fan-out trace propagation.
- [x] Merge the monitoring endpoint operational contract.

## Phase 7 — Contributor, Testing, and CI

Destinations: consolidated contributor environment/CI guide and testing guide.

- [x] Consolidate local development startup.
- [x] Consolidate Jenkins-on-ESXi setup and maintenance.
- [x] Preserve the WSL SSH procedure as an environment-specific appendix.
- [x] Consolidate Maven toolchain and PostgreSQL-version management.
- [x] Consolidate E2E and smoke-test execution.
- [x] Keep coding principles normative and complete.
- [x] Consolidate testing patterns, antipatterns, guard behavior, commands, and Testcontainers usage.
- [x] Preserve completed remediation plans, failure analyses, and test reports as historical evidence.

## Phase 8 — Proposed and Unimplemented Systems

These sources remain in `docs-design` until their product decisions and implementations are
approved and verified.

- [x] Authentication and authorization.
- [x] Schema registry.
- [x] Transactional REST API.
- [x] Transactional pattern plugin model.
- [x] SSL recommendations not established as implemented behavior.
- [x] Vert.x instance consolidation work not established as complete.
- [x] Open HAProxy and resilience gaps.
- [x] Clearly label every retained proposal and link it from the task register when applicable.

## Phase 9 — Archive and link migration

- [x] Retain sources in place unless an exact, collision-free archive destination is required.
- [x] Preserve source contents and provenance.
- [x] Record supersession and destination in the catalogue and ledger without rewriting source evidence.
- [x] Update links for files actually moved or replaced; no bulk source move was performed.
- [x] Verify no active guide points to a removed location.
- [x] Verify archived documents remain reachable from the ledger.
- [x] Verify each core topic has exactly one declared authority.

## Phase 10 — Final verification

- [x] Every `docs-design` inventory entry has a category, preservation route, and heading-level record.
- [x] Every `docs-design` source heading has a recorded destination or retained-source disposition.
- [x] No unsupported behavior claim was promoted; claims needing validation remain in their retained source.
- [x] All historical evidence remains accessible in its source location or recorded Git blob.
- [x] All unimplemented designs remain clearly labelled.
- [x] Local-link scan passes for every file created or changed by the consolidation. Twelve broken
  links remain in three untouched legacy core guides, and additional broken links remain inside
  immutable historical records; these are catalogued defects rather than silently rewritten history.
- [x] Markdown formatting check passes.
- [x] Banned-pattern scan passes for every changed file.
- [x] The consolidation diff contains documentation changes only.
- [x] Consolidated task register and documentation index agree.

## Final structural verification evidence

- 99 pre-control `docs-design` sources have matching SHA-256 fingerprints in the ledger.
- 3,555 Markdown headings plus one headingless document have checked dispositions.
- 102 current `docs-design` documents appear in both the checklist and source catalogue.
- Every newly created or changed consolidation document has zero broken local links.
- The maintained `docs` scan identified twelve pre-existing broken links in the untouched legacy
  Complete, Database Setup, and Consumer Groups guides. Those files also contain extensive
  pre-existing prohibited examples, so a link-only edit would violate the touched-file standard.
  Their content and defects remain preserved for a dedicated standards-compliant rewrite.
- Historical archive and task records retain their original link text even where repository moves
  have made those links stale.

## Complete `docs-design` Markdown inventory

The checklist below prevents any source document from disappearing from the consolidation review.
Checking an item means its section-level migration record is complete, not merely that its filename
was observed.

- [x] `docs-design/_archived/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md` — original text
  retained as the historical destination; the ledger records its byte-exact Git blob.
- [x] `docs-design/_archived/API_UPDATE_CONSUMER_GROUP_v1.1.0.md`
- [x] `docs-design/_archived/CONSUMER_GROUP_SOURCE_VERIFICATION_FINDINGS.md`
- [x] `docs-design/_archived/Consumer-Mode-Test-Implementation-Plan.md`
- [x] `docs-design/_archived/JSONB_CONVERSION_PLAN.md`
- [x] `docs-design/_archived/PEEGEEQ_CONSUMER_GROUPS_BACKFILL_PERFORMANCE_VALIDATION.md`
- [x] `docs-design/_archived/PEEGEEQ_CRITICAL_GAPS_STATUS.md`
- [x] `docs-design/_archived/PEEGEEQ_E2E_SMOKE_TEST_IMPLEMENTATION_PLAN.md`
- [x] `docs-design/_archived/PEEGEEQ_NATIVE_QUEUE_FACTORY_REFACTORING_PLAN.md`
- [x] `docs-design/_archived/PEEGEEQ_OUTBOX_MODULE_REFACTORING_PLAN.md`
- [x] `docs-design/_archived/PEEGEEQ_OUTBOX_TEST_STANDARDIZATION_15_DEC_2025.md`
- [x] `docs-design/_archived/PostgreSQL-Version-Management.md`
- [x] `docs-design/_archived/REST-HANDLER-ERROR-PATH-TESTS-PLAN.md`
- [x] `docs-design/_archived/TEST_COVERAGE_GAP_ANALYSIS.md`
- [x] `docs-design/_archived/VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md`
- [x] `docs-design/_archived/internal-notes/Claude tells me that its a liability.md`
- [x] `docs-design/_archived/historical-evidence/backfill-pending-only-multi-batch-failure.md`
- [x] `docs-design/_archived/historical-evidence/backfill-performance-test-teardown-deadlock.md`
- [x] `docs-design/analysis/PEEGEEQ_CRASH_RECOVERY_GUIDE.md`
- [x] `docs-design/_archived/completed-records/PEEGEEQ_REVIEW.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/OutboxFactory-review.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PeeGeeQManager-review.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgClientFactory-review.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgConnectionManager-review.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/PgPoolConfig-review.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/archived/SSL-Implementation-Recommendations.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/PeeGeeQ-Shutdown-Guide.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/PgConnectionConfig-review.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/Vert.x-Instance-review-Consolidation-Refactoring-Plan.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/Vertx-5x-Patterns-Guide.md`
- [x] `docs-design/code reviews/archived/vertx5-migration-code-reviews/vertx5-migration-general-guide.md`
- [x] `docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`
- [x] `docs-design/_archived/superseded-guides/PEEGEEQ_CONSUMER_GROUP_FANOUT_GUIDE.md`
- [x] `docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md`
- [x] `docs-design/_archived/superseded-guides/main-prompt.md`
- [x] `docs-design/dev/PEEGEEQ_JENKINS_ESXI_CI_SETUP.md`
- [x] `docs-design/dev/PEEGEEQ_WSL_PASSWORDLESS_SSH_SETUP.md`
- [x] `docs-design/dev/pgq-coding-principles.md`
- [x] `docs-design/_archived/completed-records/DOCUMENTATION_UPDATES_CAUSATION_ID.md`
- [x] `docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_EVENT_CAUSALITY_GUIDE.md`
- [x] `docs-design/event-sourcing-messaging/PEEGEEQ_BITEMPORAL_SUBSCRIPTIONS_GUIDE.md`
- [x] `docs-design/event-sourcing-messaging/PEEGEEQ_CLOUDEVENTS_SUPPORT_AND_INTEGRATION_GUIDE.md`
- [x] `docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md`
- [x] `docs-design/_archived/completed-records/PEEGEEQ_MESSAGING_PATTERNS_IMPLEMENTATION_PLAN.md`
- [x] `docs-design/_archived/superseded-guides/PEEGEEQ_OUTBOX_PARTITIONED_ORDERING_COMPLETE_GUIDE.md`
- [x] `docs-design/event-sourcing-messaging/PEEGEEQ_SERVER_SIDE_FILTERING_GUIDE.md`
- [x] `docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md`
- [x] `docs-design/_archived/superseded-guides/PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md`
- [x] `docs-design/peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md`
- [x] `docs-design/peegeeq-management-ui/archive/EXECUTION_CHECKLIST.md`
- [x] `docs-design/peegeeq-management-ui/PEEGEEQ_MANAGMENT_UI_ARCHITECTURE.md`
- [x] `docs-design/_archived/completed-records/grafana-dashboard-enhancement-summary.md`
- [x] `docs-design/performance/grafana-hardware-profiling-dashboard-guide.md`
- [x] `docs-design/performance/PeeGeeQ-Performance-Tuning-Harness.md`
- [x] `docs-design/_archived/historical-evidence/performance-tests-integration-validation-report.md`
- [x] `docs-design/_archived/historical-evidence/PerformanceTestResults_2025-01-11.md`
- [x] `docs-design/_archived/historical-evidence/PerformanceTestResults_2025-09-11.md`
- [x] `docs-design/_archived/completed-records/phase-2-1-5-hardware-profiling-implementation.md`
- [x] `docs-design/schema-registry/PEEGEEQ_SCHEMA_REGISTRY_DESIGN.md`
- [x] `docs-design/schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md`
- [x] `docs-design/_archived/completed-records/PEEGEEQ_SCHEMA_CONSOLIDATION_GUIDE.md`
- [x] `docs-design/security-authentication-authorisation/DESIGN_DECISIONS_SUMMARY.md`
- [x] `docs-design/security-authentication-authorisation/PEEGEEQ_AUTHENTICATION_AUTHORIZATION_DESIGN.md`
- [x] `docs-design/tasks/archive/bitemporal-examples-expansion-walkthrough.md`
- [x] `docs-design/tasks/archive/CONFIG-PROPERTY-WIRING-AUDIT.md`
- [x] `docs-design/tasks/archive/CONSUMER-GROUPS-REST-FIXES-20260604.md`
- [x] `docs-design/tasks/archive/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md`
- [x] `docs-design/tasks/archive/management-ui-tests-not-running.md`
- [x] `docs-design/tasks/archive/OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md`
- [x] `docs-design/tasks/archive/OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md`
- [x] `docs-design/tasks/archive/OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_CONFIG_ARCHITECTURE_REPLACE_PROCESS_GLOBALS_WITH_INSTANCE_ISOLATION.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_OUTBOX_REMOVE_INFLIGHT_CLOSE_TIMEOUT.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_GAPS.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_POSTGRES_NOTICE_HANDLING_DESIGN.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_REFACTOR_ONSUCESS_EXCEPTION_SWALLOWING.md`
- [x] `docs-design/tasks/archive/PEEGEEQ_TESTCONTAINERS_PATTERNS.md`
- [x] `docs-design/tasks/archive/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md`
- [x] `docs-design/tasks/archive/SESSION-HANDOVER-20260812.md`
- [x] `docs-design/tasks/archive/TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`
- [x] `docs-design/tasks/archive/TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md`
- [x] `docs-design/tasks/archive/WAVE2_UNCOMMITTED_AUDIT_20260516.md`
- [x] `docs-design/_archived/governance/DOCUMENTATION_CONSOLIDATION_CHECKLIST.md` — completed
  consolidation control record; the active provenance record is the ledger.
- [x] `docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md` — active section-level mapping
  and source-fingerprint record.
- [x] `docs-design/tasks/tasks.md`
- [x] `docs-design/testing/MAVEN_TOOLCHAINS_EXPLAINER.md`
- [x] `docs-design/testing/PEEGEEQ_E2E_TEST_SETUP_GUIDE.md`
- [x] `docs-design/_archived/historical-evidence/PEEGEEQ_SUBSCRIPTION_PERSISTENCE_TEST_COVERAGE.md`
- [x] `docs-design/testing/PEEGEEQ_TEST_GUARD.md`
- [x] `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`
- [x] `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md`
- [x] `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`
- [x] `docs-design/_archived/completed-records/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md`
- [x] `docs-design/_archived/completed-records/MONITORING_ENDPOINTS_IMPLEMENTATION_PLAN.md`
- [x] `docs-design/_archived/superseded-guides/PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md`
- [x] `docs-design/_archived/superseded-guides/PEEGEEQ_TRACING_USER_GUIDE.md`
- [x] `docs-design/_archived/superseded-guides/mq-rest-client-discussion-brief.md`
- [x] `docs-design/transactional-rest-api/mq-rest-client-discussion.md`
- [x] `docs-design/transactional-rest-api/PEEGEEQ_PLUGIN_MODEL_TRANSACTIONAL_PATTERNS_DESIGN.md`
- [x] `docs-design/transactional-rest-api/PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md`

## Additional historical source already changed in this working tree

- [x] `docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md` — compare the
  original Git revision, the reconciled pre-deletion content, and
  `docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md`; account for every section before accepting deletion.

## Phase reporting rule

Complete one phase, run its checks, and report the exact documents and sections accounted for before
starting the next phase.
