# PeeGeeQ Documentation

This directory contains the current, user-facing and operator-facing documentation for PeeGeeQ.
Design proposals, implementation records, task tracking, and historical evidence remain under
[`docs-design`](../docs-design/).

## Documentation authority

- The files in this directory describe supported or currently implemented behavior.
- The [configuration guide](PEEGEEQ_CONFIGURATION_GUIDE.md) is authoritative for runtime property
  names and precedence.
- The [REST API reference](PEEGEEQ_REST_API_REFERENCE.md) must remain aligned with the OpenAPI
  definition and registered server routes.
- The [consolidated task register](../docs-design/tasks/tasks.md) is authoritative for incomplete
  work and accepted verification evidence.
- A design proposal is not a product contract until implementation and verification are complete.
- Historical documents remain discoverable; consolidation must not erase their detail.

## Getting Started and Examples

- [Getting Started](PEEGEEQ_GETTING_STARTED.md) — maintained entry path and guide selection.
- [Examples Guide](PEEGEEQ_EXAMPLES_GUIDE.md) — runnable examples and progressive learning path.
- [Development Environment Setup](PEEGEEQ_DEVELOPMENT_ENVIRONMENT_SETUP.md) — local database,
  backend, and Management UI startup.
- [Financial Services Event Catalogue](PEEGEEQ_FINANCIAL_SERVICES_EVENT_CATALOGUE.md) — specialist
  event-model examples for financial-services workloads.
- [Complete Guide](PEEGEEQ_COMPLETE_GUIDE.md) — preserved legacy detail while its remaining unique
  sections and inbound anchors are mapped to focused guides.

## Architecture and Core Concepts

- [Architecture and API Guide](PEEGEEQ_ARCHITECTURE_API_GUIDE.md) — system architecture, modules,
  core contracts, schema, and integration patterns.
- [Design Fundamentals](PEEGEEQ_DESIGN_FUNDAMENTALS.md) — PostgreSQL queue design rationale and
  failure modes.
- [Platform Sequence Flows](PEEGEEQ_SYSTEM_MODULES_SEQUENCE_DIAGRAMS.md) — implemented and planned
  cross-module sequence diagrams.

## Messaging and Subscriptions

- [Messaging Features Guide](PEEGEEQ_MESSAGING_FEATURES_GUIDE.md) — fan-out, filtering,
  CloudEvents, causation, and cross-cutting recovery concerns.
- [Transactional Outbox Patterns Guide](PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md) —
  transactional publishing and recovery patterns.
- [Consumer Groups Guide](PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md) — consumer-group delivery,
  lifecycle, filtering, and operational use.
- [Ordering Patterns Guide](PEEGEEQ_ORDERING_PATTERNS_GUIDE.md) — simple versus partitioned
  consumption and the `OFFSET_WATERMARK` ordering contract.

## Event Store and Data Management

- [Event Store Guide](PEEGEEQ_EVENT_STORE_GUIDE.md) — bi-temporal events, causality,
  subscriptions, durable replay, and pagination.
- [Database Setup Guide](PEEGEEQ_DATABASE_SETUP_GUIDE.md) — migrations, templates, schemas,
  tenancy, and database provisioning.
- [Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md) — supported properties, loading, and
  precedence.
- [Keyset Pagination Guide](PEEGEEQ_KEYSET_PAGINATION_GUIDE.md) — stable event-store pagination;
  scheduled for integration into a focused event-store guide.

## APIs and Integration

- [REST API Reference](PEEGEEQ_REST_API_REFERENCE.md) — HTTP, streaming, management, event-store,
  and monitoring endpoints.
- [Service Manager Guide](PEEGEEQ_SERVICE_MANAGER_GUIDE.md) — service discovery, federation, and
  instance management.

## Operations and Observability

- [Operations Guide](PEEGEEQ_OPERATIONS_GUIDE.md) — lifecycle, recovery, PostgreSQL failover,
  monitoring, and evidence-based capacity guidance.
- [Tracing and Logging User Guide](PEEGEEQ_TRACING_USER_GUIDE.md) — configuration, propagation,
  searching, and operational usage.
- [Tracing Technical Reference](PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md) — trace-context internals,
  asynchronous boundaries, and implementation contracts.

## Contributor and CI Documentation

- [Contributor Environment and CI Guide](PEEGEEQ_CONTRIBUTOR_GUIDE.md) — workstation, build,
  Jenkins, and environment-specific administration.
- [Testing Guide](PEEGEEQ_TESTING_GUIDE.md) — normative testing entry point, TDD loop, profiles,
  and evidence requirements.

Detailed normative sources remain under `docs-design/dev` and `docs-design/testing`; the category
guides do not weaken or replace those rules.

## Consolidation control

- [Documentation Source Catalogue](PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md) — every current
  `docs-design` document categorized with its status and consolidation route.
- [Documentation Archive](../docs-design/_archived/README.md) — superseded guides, completed
  records, dated evidence, and internal notes retained outside the current documentation set.

The completed lossless consolidation sequence and source inventory are preserved in the archived
[Documentation Consolidation Checklist](../docs-design/_archived/governance/DOCUMENTATION_CONSOLIDATION_CHECKLIST.md).
No source document may be removed merely because a shorter core guide exists.
