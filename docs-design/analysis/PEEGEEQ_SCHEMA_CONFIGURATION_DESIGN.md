# PeeGeeQ Schema Configuration Design

**Status:** IMPLEMENTED BASELINE — historical plans and contradictory checklists removed
**Corrected:** 2026-11-21 against `0ffaafb6` (the earlier hash `7db748b8` no longer resolves):
corrected the provisioning sequence and its two transactions, recorded the missing Flyway
migration for the setup registry tables as defect D7, recorded the two different schema-name rules.
**Task authority:** [PeeGeeQ Consolidated Task Register](../tasks/tasks.md)

This document describes the current schema architecture. It is not a second task list and does
not claim a new test run. Exact completion evidence and remaining release work are recorded only
in the consolidated task register.

## 1. Architectural Invariant

PeeGeeQ uses an explicit, isolated PostgreSQL schema for each tenant or application setup.
Templates, operational tables, subscription state, event stores, functions, triggers, and setup
metadata for that setup live in the configured schema.

The implementation does not require globally shared `peegeeq` or `bitemporal` schemas. Those
names may be chosen by an operator as ordinary schema names, but they have no special runtime
meaning.

The invariant is:

```text
database
├── tenant_a
│   ├── queue and outbox tables
│   ├── queue and event-store templates
│   ├── subscriptions, groups, ledgers, and cursors
│   ├── named queues and event stores
│   └── tenant-local functions, triggers, and setup metadata
└── tenant_b
    └── an independent copy of the same object families
```

Dropping, backing up, granting access to, or migrating one tenant schema can therefore be
reasoned about independently. Database permissions must still enforce the intended boundary;
schema naming alone is not an authorization system.

## 2. Configuration Contract

The canonical configuration property is:

```properties
peegeeq.database.schema=tenant_a
```

The schema is required by the current connection configuration. PeeGeeQ has no implicit runtime
schema fallback. Callers must provide the intended schema explicitly.

Schema identifiers are validated before setup work begins. The supported identifier form is a
PostgreSQL-safe name composed of letters, digits, and underscores, beginning with a letter or
underscore. PostgreSQL system schemas are not valid tenant schemas.

The connection manager also normalizes a configured search-path value. The supported tenant
model remains one isolated schema per setup; a broader search path should be used only when an
operator deliberately accepts the resulting cross-schema visibility.

The two checks apply different rules. Setup creation (`createCompleteSetup`) validates the schema
with `PostgreSqlIdentifierValidator`: the name must start with a letter or underscore, must not
exceed 63 characters, and must not start with `pg_` or equal `information_schema`. The
connection-level normalizer (`PgConnectionManager.normalizeSearchPath`) accepts any
comma-separated list of letters, digits, and underscores. It accepts a name that starts with a
digit, and it does not apply the length or system-schema checks. A schema name that the
connection manager accepts can therefore be rejected by setup creation.

## 3. Connection and Transaction Behavior

`PgConnectionManager` applies the normalized schema configuration to PostgreSQL connection
startup properties. Unqualified operational SQL therefore resolves against the registered
service schema on a direct or session-pooled connection.

For transaction pooling, `withTransaction` additionally sets the registered schema as
transaction-local state before application work executes. This is required because a proxy can
route consecutive transactions from one logical client to different PostgreSQL backend
sessions.

The rules are:

- use the connection manager registered for the tenant;
- execute writes through the transactional API;
- do not rely on arbitrary session variables persisting between transactions;
- use validated, qualified identifiers where SQL must address a named dynamic object directly;
- do not concatenate unvalidated user input into identifiers or notification channels.

The PgBouncer transaction-pooling verification recorded in the task register covers alternating
tenant transactions and backend-session reuse. That evidence is historical to its recorded
revision; this document does not upgrade it to a new run at the current revision.

## 4. Fresh Setup Architecture

Fresh setups are built from ordered resources under
`peegeeq-db/src/main/resources/db/templates/`:

| Template group | Purpose |
|---|---|
| `base` | Schema, core tables, templates, indexes, subscription state, and setup metadata |
| `queue` | A named queue table, indexes, notification function, and trigger |
| `eventstore` | A named event-store table, indexes, notification function, and trigger |
| `eventstore-aggregate-summary` | Optional aggregate-summary table, function, and trigger |
| `registry` | Durable setup-to-database binding registry outside an individual setup schema |

Each group has a hidden `.manifest`. `SqlTemplateProcessor` reads that manifest and executes its
SQL resources sequentially after substituting the supplied template parameters.

`PeeGeeQDatabaseSetupService.createCompleteSetup`:

1. validates the schema name with `PostgreSqlIdentifierValidator`;
2. creates the database from a template database (`template0` unless the configuration names
   another);
3. opens a temporary reactive pool and, in **transaction 1**, applies the base template, verifies
   the tenant-local queue and event-store template tables, records the setup identity in
   `peegeeq_setup_metadata`, creates the requested queue tables, creates the requested event-store
   tables, and writes one `peegeeq_object_registry` row per event store; the temporary pool is
   closed after the transaction settles;
4. creates and starts a `PeeGeeQManager` for the setup, with its own Vert.x instance;
5. validates that the required tables exist;
6. registers the queue and event-store factories and creates the requested queue factories and
   event stores;
7. in **transaction 2**, on a second temporary pool, writes one `peegeeq_object_registry` row per
   queue (`persistQueueRegistry`); and
8. writes the durable binding row when the caller requested it.

Provisioning uses two database transactions, not one. Queue registry rows are written after the
manager starts because a queue's native or outbox kind is known only after its factory is built.
The header comment of `10a-setup-object-registry.sql` records the same split.

A failure after transaction 1 commits leaves the schema, its tables, the metadata row, and the
event-store registry rows in the database. The failure path calls `destroySetup`, which releases
the in-process resources and does not drop the database. This is static reading; no test was run
for this document. A failed create must not be documented as a successful or partially usable
setup.

## 5. Migration Architecture

Flyway migrations under `peegeeq-migrations/src/main/resources/db/migration/` are the versioned
upgrade path for existing schemas. Runtime templates represent the current fresh-setup shape.

Both paths must be maintained:

| Change | Migration | Base template | Dynamic template |
|---|---:|---:|---:|
| Existing shared/core table changes | Required | Required | When applicable |
| New queue-table column or index | Required | Required for base queue tables | Required |
| New event-store column or index | Required | Required for base event-store tables | Required |
| Subscription or cursor state | Required | Required | Usually not applicable |
| Trigger or notification behavior | Required for existing objects | Required | Required |

There is no current `minimal-core-schema.sql` authority and no standalone
`PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql` authority.

**Open gap (defect D7 in the [task register](../tasks/tasks.md)):** the two paths are not aligned
for the setup registry tables. `peegeeq_setup_metadata` (`10b-setup-metadata.sql`) and
`peegeeq_object_registry` (`10a-setup-object-registry.sql`) exist only in the base templates. No
Flyway migration under `peegeeq-migrations/src/main/resources/db/migration/` creates them. A schema
built or upgraded by Flyway alone therefore lacks both tables.

## 6. Runtime Object Resolution

Most operational queries use unqualified table names and resolve them through the tenant-bound
connection. Setup and lifecycle operations that address a dynamic table by name qualify the
validated schema and object name explicitly.

Queue and event-store notification functions are created in the same schema as their tables.
Notification channel construction must include the schema context defined by the corresponding
producer/consumer implementation so tenants using the same logical topic or table name do not
share a channel accidentally.

## 7. Test Architecture

Database behavior is tested against real PostgreSQL. `PeeGeeQTestSchemaInitializer` requires an
explicit schema and delegates initialization to Flyway, avoiding a second DDL implementation in
Java.

A schema-affecting change requires the smallest relevant set of contracts covering:

- migration of an existing schema;
- fresh provisioning from the matching templates;
- positive behavior in a non-default schema;
- two-schema data isolation;
- notification isolation when triggers or listeners change;
- subscription, cursor, or consumer-group isolation when those tables change;
- cleanup and resource ownership; and
- failure propagation for invalid or inaccessible schemas.

The repository contains schema-isolation contracts across the database, native, outbox,
bitemporal, REST, migration, and integration-test modules. Exact names and passing counts must be
taken from current test reports or the consolidated task register rather than copied into this
design as permanently current totals.

## 8. Operational Patterns

### Schema per tenant

Use one schema per tenant when tenants share a database but need separate object namespaces and
schema-scoped permissions. Budget connection pools and PostgreSQL connections across all active
tenants.

### Database per tenant

Use one database per tenant when stronger operational isolation, independent database tuning, or
independent recovery is required. Each database still uses an explicit PeeGeeQ schema.

### PgBouncer transaction pooling

Transaction pooling is supported only when the proxy configuration accepts the required startup
parameter behavior and resets backend session state between clients. PeeGeeQ reapplies tenant
schema state transaction-locally, but operators must still validate their exact PgBouncer and
PostgreSQL versions and configuration.

### Permissions

The application role needs only the database and schema privileges required for the selected
setup and runtime operations. Production roles should not receive blanket access to neighboring
tenant schemas. Extension creation may require a separately privileged provisioning role.

## 9. Troubleshooting

### A relation cannot be found

Confirm the setup exists in the configured schema, the service was registered with that same
schema, and the active transaction resolves that schema. Do not add unrelated schemas to the
search path merely to hide a provisioning or configuration mismatch.

### A template cannot be found

Confirm the SQL resource is under the expected template directory and listed in that directory's
hidden `.manifest`. Rebuild the affected module so classpath resources are refreshed.

### Tenants appear to share data

Check the registered pool identity, schema configuration, role grants, qualified dynamic-object
SQL, and notification channel names. Reproduce the issue with two schemas in one PostgreSQL
instance and assert both positive delivery and absence of cross-tenant delivery.

### Behavior differs through PgBouncer

Check whether transaction pooling is enabled, whether the required startup parameter is allowed,
and whether the proxy resets backend state. Verify schema selection inside each transaction;
session-level observations from a previous transaction are not sufficient evidence.

## 10. Historical Reconciliation

The previous 3,000-line version mixed several generations of analysis:

- an initial report of shared-schema defects;
- a later discovery that many templates were already parameterized;
- completed remediation claims;
- obsolete multi-schema workarounds;
- an unchecked six-week implementation plan; and
- test examples using patterns prohibited by the current engineering standards.

Those sections were removed because they contradicted one another and could not serve as an
implementation guide. They are recoverable from Git history if forensic detail is required.

The current conclusion is narrower and evidence-based: explicit tenant schemas, ordered
templates, Flyway migrations, tenant-bound connections, and transaction-local schema selection
form the implemented baseline. Remaining release validation is listed only in the consolidated
task register.

## 11. References

- [Consolidated task register](../tasks/tasks.md)
- [Historical schema consolidation record](../_archived/completed-records/PEEGEEQ_SCHEMA_CONSOLIDATION_GUIDE.md)
- [Coding principles](../dev/pgq-coding-principles.md)
- [Testing standards and antipatterns](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/SqlTemplateProcessor.java`
- `peegeeq-db/src/main/resources/db/templates/`
- `peegeeq-migrations/src/main/resources/db/migration/`
- `peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/schema/PeeGeeQTestSchemaInitializer.java`
