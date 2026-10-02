# Getting Started with PeeGeeQ

This guide is the maintained entry point for learning and evaluating PeeGeeQ. It deliberately
routes detailed subjects to focused guides instead of repeating their content.

## 1. What PeeGeeQ provides

PeeGeeQ uses PostgreSQL to provide:

- native queue-style message delivery;
- transactional outbox publishing;
- competing and fan-out consumer groups;
- opt-in per-key ordered consumption;
- bi-temporal event storage and subscriptions;
- REST, streaming, and management interfaces; and
- tracing, monitoring, and operational tooling.

The database remains part of the consistency boundary. Applications should choose a messaging
pattern based on transaction ownership, delivery semantics, and ordering requirements.

## 2. Choose the first path

| Requirement | Start here |
|---|---|
| Learn the smallest producer/consumer flow | [Examples Guide](PEEGEEQ_EXAMPLES_GUIDE.md) |
| Publish atomically with business data | [Transactional Outbox Patterns Guide](PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md) |
| Share work or fan events out to services | [Consumer Groups Guide](PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md) |
| Preserve order for each account or aggregate | [Ordering Patterns Guide](PEEGEEQ_ORDERING_PATTERNS_GUIDE.md) |
| Store and query temporal events | [Event Store Guide](PEEGEEQ_EVENT_STORE_GUIDE.md) |
| Call PeeGeeQ over HTTP | [REST API Reference](PEEGEEQ_REST_API_REFERENCE.md) |
| Understand the system before integrating | [Architecture and API Guide](PEEGEEQ_ARCHITECTURE_API_GUIDE.md) |

## 3. Prerequisites

The repository build expects:

- a supported JDK and Maven installation;
- PostgreSQL for manually operated environments;
- Docker-compatible container execution for Testcontainers-backed tests; and
- Node.js and browser-test dependencies when working on the Management UI.

Use the repository build configuration as the version authority. Contributor-machine setup and CI
instructions are maintained in the [Contributor Guide](PEEGEEQ_CONTRIBUTOR_GUIDE.md).

## 4. Start the local platform

For the Management UI development stack, start components in this order:

1. PostgreSQL;
2. the PeeGeeQ REST service; and
3. the Management UI.

Follow the existing [Development Environment Setup](PEEGEEQ_DEVELOPMENT_ENVIRONMENT_SETUP.md) for
the current repository commands and health checks. Database provisioning alternatives are covered
by the [Database Setup Guide](PEEGEEQ_DATABASE_SETUP_GUIDE.md).

## 5. Run a first example

The `peegeeq-examples` module contains the executable learning material. Begin with its basic
producer/consumer example, then follow the progression into outbox, consumer-group, event-store,
and integration scenarios.

The [Examples Guide](PEEGEEQ_EXAMPLES_GUIDE.md) owns example names, execution commands, and their
environment requirements. Treat historical example counts as dated evidence rather than a
permanent inventory.

## 6. Configure deliberately

Runtime properties must come from the supported inventory and precedence rules in the
[Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md). Do not infer that a property is supported
merely because it appears in an older example or design document.

Database schemas, migrations, fresh setup templates, and tenant isolation are covered separately
by the [Database Setup Guide](PEEGEEQ_DATABASE_SETUP_GUIDE.md).

## 7. Understand delivery semantics

All message handlers should be safe for at-least-once delivery. A version guard or duplicate fence
does not reconstruct messages that were processed out of order.

Use:

- a simple consumer when independent messages may complete concurrently;
- a consumer group when work distribution or service fan-out is required; and
- `OFFSET_WATERMARK` with an explicit `messageGroup` when order must be preserved within a business
  key.

The [Ordering Patterns Guide](PEEGEEQ_ORDERING_PATTERNS_GUIDE.md) defines the maintained ordering
contract and its operational boundaries.

## 8. Add observability

Carry correlation and causation identifiers through request, message, and event boundaries. Use
W3C trace context where distributed tracing is required.

- [Tracing and Logging User Guide](PEEGEEQ_TRACING_USER_GUIDE.md)
- [Tracing Technical Reference](PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md)
- [Operations Guide](PEEGEEQ_OPERATIONS_GUIDE.md)

## 9. Validate the integration

Choose the smallest relevant test scope while developing. Database behavior must be exercised
against PostgreSQL through the project's Testcontainers infrastructure. The full test profile is a
release gate rather than the normal edit loop.

Contributor testing rules, profiles, commands, and evidence requirements are consolidated in the
[Testing Guide](PEEGEEQ_TESTING_GUIDE.md).

## 10. Detailed legacy material

The [Complete Guide](PEEGEEQ_COMPLETE_GUIDE.md) remains available while its unique sections and
inbound anchors are migrated. It is not the authority for runtime property names, current task
status, or recently changed APIs. The focused guides linked above take precedence.

The lossless migration is tracked in the
[Documentation Consolidation Ledger](../docs-design/tasks/DOCUMENTATION_CONSOLIDATION_LEDGER.md).
