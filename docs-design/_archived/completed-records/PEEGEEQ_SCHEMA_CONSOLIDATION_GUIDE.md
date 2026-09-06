# PeeGeeQ Schema Consolidation — Historical Decision Record

**Status:** SUPERSEDED — retained for architectural history; do not use as an implementation guide
**Original date:** 2025-12-25
**Reconciled:** 2026-09-06 against `7db748b8`

The 2025 refactoring recorded by the original version of this document removed a block of
schema DDL embedded in Java. Its intermediate `db/schema/minimal-core-schema.sql` design is no
longer present and is not the current schema authority. The former document also referenced
removed `docs-design/tmo` records and a removed standalone complete-schema script. Those paths
must not be used for maintenance or deployment.

Current work and verification status are controlled by the
[consolidated task register](../tasks/tasks.md). The current schema architecture is documented in
[PeeGeeQ Schema Configuration Design](PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md).

## 1. Current Sources of Truth

PeeGeeQ intentionally has two schema delivery paths with different responsibilities:

| Source | Responsibility | Location |
|---|---|---|
| Ordered runtime templates | Fresh setup provisioning and dynamic queue/event-store creation | `peegeeq-db/src/main/resources/db/templates/` |
| Flyway migrations | Versioned upgrades and test-schema initialization | `peegeeq-migrations/src/main/resources/db/migration/` |

There is no standalone “minimal core schema” file and no generated complete-schema file in the
current repository.

### Runtime template groups

- `templates/base/` creates the tenant schema, core tables, template tables, subscription state,
  indexes, and setup metadata.
- `templates/queue/` creates a named queue table, indexes, notification function, and trigger.
- `templates/eventstore/` creates a named event-store table, indexes, notification function, and
  trigger.
- `templates/eventstore-aggregate-summary/` adds the optional aggregate summary objects.
- `templates/registry/` creates the separate durable setup-binding registry used by the setup
  service.

Every directory has a hidden `.manifest` that defines execution order. Adding a SQL file without
adding it to the applicable manifest does not add it to provisioning.

## 2. Current Provisioning Flow

`PeeGeeQDatabaseSetupService` validates the requested schema before provisioning. It applies the
base template and requested queue/event-store templates through `SqlTemplateProcessor`, passing
validated identifiers as template parameters.

The base DDL, requested object DDL, setup metadata, and event-store registry rows are applied in
one database transaction. If the operation fails, the transaction is expected to roll back the
provisioning unit instead of leaving a partially registered setup.

The service verifies that the tenant-local `queue_template` and `event_store_template` tables
exist before creating requested objects. The runtime path does not load a separate core-schema
resource.

## 3. Tenant Isolation Rule

Each PeeGeeQ tenant or application setup owns one explicit PostgreSQL schema containing its:

- operational queue and outbox tables;
- queue and event-store templates;
- topic, subscription, consumer-group, ledger, and cursor tables;
- event stores, indexes, functions, and triggers;
- setup identity and object-registry metadata.

The literal `peegeeq` and `bitemporal` names are not mandatory shared infrastructure schemas.
Template SQL uses the configured schema parameter, and notification channel names include schema
context where required by the implementation.

## 4. Connection Semantics

`PgConnectionManager` configures the PostgreSQL connection startup `search_path` from the
required schema configuration. For transaction-pooling compatibility, `withTransaction` also
reapplies the registered schema as transaction-local state before invoking application work.

This second step matters when a proxy such as PgBouncer can assign a different backend session to
each transaction. Applications must not depend on custom session state surviving across
transactions.

## 5. Schema Change Procedure

For a schema change:

1. Add an ordered Flyway migration for existing databases.
2. Update every affected fresh-setup template.
3. Update the relevant hidden `.manifest` when adding or removing template files.
4. Update both base templates and dynamic object templates when the change affects both paths.
5. Add a real PostgreSQL contract that would fail if either the migration or fresh-template path
   were omitted.
6. Add or update a two-schema isolation contract when tenant-visible objects, queries, triggers,
   notifications, or cursors change.
7. Reconcile the consolidated task register with the exact verification evidence.

Do not add schema DDL to Java strings, create a third schema authority, or copy production DDL
into test helpers.

## 6. Test-Schema Provisioning

`PeeGeeQTestSchemaInitializer` requires callers to supply a schema explicitly. It delegates schema
creation and evolution to Flyway rather than maintaining a duplicate Java DDL model. Tests that
touch PostgreSQL use real PostgreSQL infrastructure and must clean up their tenant-local data and
resources deterministically.

The current test inventory and verified counts belong in the consolidated task register, not in
this historical record.

## 7. Historical Outcome

The durable outcome of the 2025 work remains valid: schema definition belongs in declarative SQL
resources, not Java string literals. The intermediate claim that one
`minimal-core-schema.sql` file was the definitive current schema is superseded by the ordered
template plus migration architecture described above.

## 8. Current References

- [Consolidated task register](../tasks/tasks.md)
- [Schema configuration design](PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md)
- [Coding principles](../dev/pgq-coding-principles.md)
- [Testing standards and antipatterns](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- `peegeeq-db/src/main/resources/db/templates/`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/SqlTemplateProcessor.java`
- `peegeeq-migrations/src/main/resources/db/migration/`
- `peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/schema/PeeGeeQTestSchemaInitializer.java`
