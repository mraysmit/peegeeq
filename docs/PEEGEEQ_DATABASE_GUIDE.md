# PeeGeeQ Database Guide
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 1.0

This guide explains how a PeeGeeQ schema comes into existence, what it contains, and how a setup is
provisioned, connected, detached and dropped. Property names are in
`PEEGEEQ_CONFIGURATION_GUIDE.md`. Endpoint detail is in
`PEEGEEQ_REST_API_GUIDE.md`. Test setup is in
`docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md`.

How the statements in this guide were checked:

- Every statement about what PeeGeeQ code, a template or a migration does when it runs, and every
  consequence drawn from that, was read in the code and not run.
- A statement that rests on Flyway or PostgreSQL behaviour says so.
- A statement marked "search of the main sources" comes from a text search of the `src/main` trees.
- Defects that explain a behaviour cite their register entry in
  `docs-design/tasks/tasks.md`.

## 1. Two ways to create a schema

PeeGeeQ has two independent provisioning paths. They create different sets of objects (register D42 (a),
D7).

| Path | Source | Used by | Creates the database |
|---|---|---|---|
| Flyway migrations | `peegeeq-migrations/src/main/resources/db/migration/` | A deployment that creates the schema before the application starts | No. The database must exist |
| Setup templates | `peegeeq-db/src/main/resources/db/templates/` | `DatabaseSetupService`: `PeeGeeQRuntime`, the REST server | Yes |

No runtime module runs Flyway. Outside the templates, the only DDL that runtime classes issue is
`CREATE DATABASE` and `DROP DATABASE ... WITH (FORCE)` in `DatabaseTemplateManager`, and
`DROP TABLE ... CASCADE` in `removeEventStore` (search of the main sources). No runtime class creates
tables, indexes, functions or triggers outside the templates. The setup service never runs the
migrations.

`PeeGeeQManager.start()` creates nothing. It fails unless `outbox`, `queue_messages`, `dead_letter_queue`
and `outbox_topic_subscriptions` exist in the configured schema. Both paths create these four tables, so a
manager can start on either schema.

### Objects only one path creates

| Created only by Flyway | Created only by the templates |
|---|---|
| `bitemporal_event_log`, its four views and `get_events_as_of_time` | `queue_template`, `event_store_template` |
| `outbox_partition_assignments`, `outbox_partition_offsets`, `outbox_topic_watermarks`, the `rebalance_generation` column and the index on `outbox (topic, message_group, id)` (`OFFSET_WATERMARK` consumption) | `peegeeq_object_registry`, `peegeeq_setup_metadata` |
| `message_processing`, `schema_version`, `partition_drop_audit` | One table, function and trigger per queue and per event store (section 6) |
| The triggers on `outbox`, `queue_messages`, `message_processing` and `bitemporal_event_log` | |
| The functions `notify_message_inserted`, `update_message_processing_updated_at`, `cleanup_completed_message_processing`, `register_consumer_group_for_existing_messages`, `create_consumer_group_entries_for_new_message`, `cleanup_completed_outbox_messages`, `notify_bitemporal_event`, `set_required_consumer_groups`, `mark_dead_consumer_groups` and `update_consumer_group_index` | |
| The V001 and V010 indexes on `outbox`, `queue_messages` and `dead_letter_queue` other than the idempotency indexes (topic, status, visibility, lock, correlation, priority, headers, fan-out), and the V001 indexes on `outbox_consumer_groups` | |

### Tables that both paths create with different definitions

- `outbox`, `queue_messages` and `dead_letter_queue`: the templates create only the idempotency indexes on
  `outbox` and `queue_messages`, and no index on `dead_letter_queue` (D42 (a)).
- `outbox_consumer_groups`: the template table has `claimed_at`, `completed_at`, `lock_id` and
  `lock_until`, and no foreign key from `message_id` to `outbox`. The Flyway table has `processed_at`,
  `processing_started_at` and `created_at`, and the foreign key. The template status check does not allow
  `DEAD_LETTER` (D34, D42 (a)).
- `dead_letter_queue.failed_at` is nullable in the template and `NOT NULL` after V011 (D42 (a)).
- `outbox_topics`: the template primary key is `id`, with a unique `topic`. The Flyway primary key is
  `topic`.

### Consequences on a template schema

- `PeeGeeQManager` starts `ConsumerGroupRetryService` when the consumer-group retry job is enabled. The
  service writes the status `DEAD_LETTER`, which the template status check refuses (D34).
- No code sets `outbox.required_consumer_groups` when a message is inserted. Java code only adds one
  during a backfill (`BackfillService`) and subtracts one when a dead group is cleaned up
  (`DeadConsumerGroupCleanup`). The only insert-time setter is the Flyway trigger
  `set_required_consumer_groups`, so a new `PUB_SUB` message on a template schema starts at the default of
  1 whatever the number of subscriptions (D42 (a); search of the main sources).
- `ConsumerGroupFetcher` writes `outbox_consumer_groups.processed_at`, which the template table does not
  have (D42 (a)).
- `OFFSET_WATERMARK` consumption needs the V017 tables and the V018 column.

### Choosing a path

- Use Flyway when the application uses the default event store table `bitemporal_event_log`,
  `OFFSET_WATERMARK` partitioned consumption, `PUB_SUB` fan-out completion, or consumer-group dead
  lettering.
- Use the setup service when PeeGeeQ creates the database, when queues and event stores are added at run
  time, or when the REST server manages the setup.

A setup service cannot connect to a schema that Flyway created, because the schema lacks the template and
registry tables and the `peegeeq_setup_metadata` row (D42 (a)). See section 5.

## 2. The configured schema

Every PeeGeeQ object of a setup lives in one schema. PeeGeeQ has no default schema.

- An application configuration sets `peegeeq.database.schema`. `PeeGeeQConfiguration` rejects an empty
  value. `PEEGEEQ_CONFIGURATION_GUIDE.md` owns the property.
- A setup request sets `DatabaseConfig.schema`. `createCompleteSetup` and `connectToExistingSetup`
  fail when it is null or blank. The REST parser returns 400 "schema is required".
- `RunMigrations` fails when `DB_SCHEMA` is unset.

There is no separate event-store schema. The names `peegeeq` and `bitemporal` have no meaning to the
code. Queue tables, event store tables, subscription tables and registry tables share the one schema.

`PgConnectionManager` sets the schema as the `search_path` of every connection in the pools it creates,
and sets it again as transaction-local state at the start of each of its `withConnection` and
`withTransaction` calls. Much of the SQL in PeeGeeQ uses unqualified table names and relies on this
`search_path`.

### Schema and object names

`PostgreSqlIdentifierValidator` accepts a name that matches `^[a-zA-Z_][a-zA-Z0-9_]*$`, is at most 63
characters long, does not start with `pg_` and is not `information_schema`. The setup service applies it
to the schema, the database name, the name given to `addQueue`, and the table name given to
`addEventStore`.

Use lowercase names that are not SQL reserved words. The validator accepts mixed case and reserved words,
but the templates insert the schema name unquoted. PostgreSQL then folds the name to lower case or rejects
it, and the setup's own existence checks, which compare the original text, fail (D42 (h)).

A database name built from `UUID.randomUUID()` contains hyphens and is rejected.

### Isolation between schemas

Notification channels are prefixed with the schema name: `<schema>_queue_<topic>` for native queues and
`<schema>_bitemporal_events_<table>` for event stores. Two setups in different schemas therefore do not
receive each other's notifications. One exception exists: the V001 trigger function
`notify_message_inserted` notifies on `peegeeq_<topic>`, which is not schema-qualified. No class in the main
sources listens on that channel (search of the main sources).

## 3. Flyway migrations

### What the migrations create

The scripts are V001 and V010 to V022. Each runs in the configured schema.

| Script | Purpose |
|---|---|
| V001 | Base tables, indexes, views, functions and triggers. Runs outside a transaction (`V001__Create_Base_Tables.sql.conf`) |
| V010 | Consumer-group fan-out: topic configuration, subscriptions, fan-out columns on `outbox`, ledger and index tables. Renames `outbox_consumer_groups` columns to `message_id` and `group_name` |
| V011 | Idempotency key columns and indexes, `causation_id` on `bitemporal_event_log`, schema-qualified event notification channels, `dead_letter_queue.failed_at` not null |
| V012 | `bitemporal_subscriptions` and the `durable_enabled` column on subscriptions |
| V013 | Unique index on `COALESCE(previous_version_id, event_id)` and `version` in `bitemporal_event_log` |
| V014 | Unique `event_id`, a foreign key from `previous_version_id`, and at most one successor per event |
| V015 | `consecutive_misses` and `dead_after_misses` on subscriptions |
| V016 | `DEAD_LETTER` status on `outbox_consumer_groups` |
| V017 | Offset and watermark tables for `OFFSET_WATERMARK`, and an index on `outbox (topic, message_group, id)` |
| V018 | `rebalance_generation` on subscriptions |
| V019 | Drops `queue_metrics`, `connection_pool_metrics` and `cleanup_old_metrics` |
| V020 | Delivery lease columns on `bitemporal_subscriptions` |
| V021 | `STOPPED` subscription status |
| V022 | `queue_message_group_exclusions` |

Tables in a fully migrated schema:

| Table | Purpose |
|---|---|
| `outbox` | Messages of outbox queues, keyed by topic |
| `queue_messages` | Messages of native queues, keyed by topic |
| `dead_letter_queue` | Messages that exhausted their retries, from either queue type |
| `outbox_consumer_groups` | Per-message, per-group delivery state for outbox fan-out |
| `queue_message_group_exclusions` | Native messages that a consumer-group filter refused |
| `outbox_topics` | Topic semantics (`QUEUE` or `PUB_SUB`), retention, completion tracking mode |
| `outbox_topic_subscriptions` | Consumer-group subscriptions, heartbeat and backfill state |
| `processed_ledger` | Processing audit rows per message and group |
| `outbox_partition_assignments`, `outbox_partition_offsets`, `outbox_topic_watermarks` | Partition ownership, committed offsets and cleanup boundary for `OFFSET_WATERMARK` |
| `bitemporal_event_log` | The default event store table |
| `bitemporal_subscriptions` | Durable event-store subscriptions and their cursors |
| `message_processing`, `schema_version`, `partition_drop_audit`, `consumer_group_index` | No runtime class references them (search of the main sources). `PeeGeeQTestSchemaInitializer.cleanupTestData` truncates `message_processing` and `consumer_group_index`. The base template also creates `consumer_group_index` |

Flyway also creates `flyway_schema_history` in the target schema (Flyway behaviour, covered by
`CustomSchemaIntegrationTest` in `peegeeq-migrations`). The
`PEEGEEQ_MESSAGING_GUIDE.md`, `PEEGEEQ_ORDERING_GUIDE.md` and
`PEEGEEQ_EVENT_STORE_GUIDE.md` explain how these tables are used.

### Running `RunMigrations`

`dev.mars.peegeeq.migrations.RunMigrations` is the main class of the shaded jar. Build it with the
documented rebuild command:

```powershell
mvn clean install "-DskipTests" -pl ":peegeeq-migrations" -am 2>&1 | Tee-Object -FilePath logs\peegeeq-migrations-build.log
```

The jar is `peegeeq-migrations\target\peegeeq-migrations.jar`.

`RunMigrations` reads environment variables only. It reads no command-line options other than the command
name and no system properties.

| Variable | Required | Meaning |
|---|---|---|
| `DB_JDBC_URL` | Yes | JDBC URL of the existing database |
| `DB_USER` | Yes | Database user |
| `DB_PASSWORD` | Yes | Password |
| `DB_SCHEMA` | Yes | Target schema. There is no default |
| `DB_CLEAN_ON_START` | No | `true` enables `clean`, and makes `migrate` clean the schema first. Default `false` |

```powershell
$env:DB_JDBC_URL = "jdbc:postgresql://localhost:5432/orders_db"
$env:DB_USER = "orders_owner"
$env:DB_PASSWORD = "<password>"
$env:DB_SCHEMA = "orders_app"
java -jar peegeeq-migrations\target\peegeeq-migrations.jar migrate
```

The first argument is the command: `migrate` (the default), `info`, `validate`, `baseline`, `repair` or
`clean`. `clean` exits with an error unless `DB_CLEAN_ON_START` is `true`. The runner configures Flyway with
the classpath location `db/migration`, the schema from `DB_SCHEMA`, `baselineOnMigrate`,
`validateOnMigrate`, `outOfOrder` false and `mixed` true.

The Docker image runs the same jar. The Dockerfile copies `pom.xml` and the module directories from the
build context, so the context is the repository root:

```powershell
docker build -t peegeeq-migrations:latest -f peegeeq-migrations/Dockerfile .
docker run --rm -e DB_JDBC_URL=jdbc:postgresql://postgres:5432/orders_db -e DB_USER=orders_owner -e DB_PASSWORD=<password> -e DB_SCHEMA=orders_app peegeeq-migrations:latest migrate
```

`peegeeq-migrations/docker-compose.dev.yml` has two faults (D42 (l)):

- It does not set `DB_SCHEMA`, so its `migrations` service stops with the missing-variable error.
- The file sits in `peegeeq-migrations/` and declares `context: .` with
  `dockerfile: peegeeq-migrations/Dockerfile`. The Dockerfile needs the repository root as context, so
  this build path looks wrong (Compose path resolution, not run).

The scripts in `peegeeq-migrations/scripts/` handle the schema in two ways:

- `dev-migrate.bat`, `dev-migrate.sh`, `dev-reset-db.bat` and `dev-reset-db.sh` set `DB_SCHEMA` to
  `public` when it is unset.
- `test-migrations.ps1` and `test-migrations.sh` do not set it, so `RunMigrations` fails on the missing
  variable (D42 (l)).

Set `DB_SCHEMA` explicitly in every case.

### Flyway Maven plugin

The plugin in `peegeeq-migrations/pom.xml` uses the root `flyway.version`, the location
`filesystem:src/main/resources/db/migration`, `baselineOnMigrate`, `validateOnMigrate`, `outOfOrder`
false, `mixed` true and `cleanDisabled` true. Its URL, user and password come from the `local`, `test` or
`production` profile; `production` reads the environment variables `DB_URL`, `DB_USER` and `DB_PASSWORD`.
The plugin configuration names no schema. Use `RunMigrations` to migrate a named schema.

### Re-running and rollback

Flyway records each applied version in `flyway_schema_history` and does not apply it again (Flyway
behaviour). The scripts are not all safe to execute twice outside Flyway: V001 creates its indexes without
`IF NOT EXISTS`. `RunMigrations` has no undo command. V020, V021 and V022 state their rollback steps in a
header comment.

## 4. Provisioning a setup

A setup is one database, one schema, one `PeeGeeQManager`, and the queue factories and event stores
created for it. `PeeGeeQRuntime.createDatabaseSetupService()` returns a `DatabaseSetupService` with the
native and outbox queue factories and the bi-temporal event store factory registered. The REST server
obtains its setup service the same way.

```java
Future<DatabaseSetupResult> provisionOrders(DatabaseSetupService setupService, String password) {
    DatabaseConfig database = new DatabaseConfig.Builder()
            .host("localhost")
            .port(5432)
            .databaseName("orders_db")
            .username("peegeeq_admin")
            .password(password)
            .schema("orders_app")
            .build();
    QueueConfig orders = new QueueConfig.Builder().queueName("orders").build();
    EventStoreConfig audit = new EventStoreConfig.Builder()
            .eventStoreName("order_audit")
            .tableName("order_audit")
            .build();
    return setupService.createCompleteSetup(new DatabaseSetupRequest(
            "orders-setup", database, List.of(orders), List.of(audit), Map.of()));
}
```

The caller composes on the returned `Future` or observes both outcomes. Between them, the tests
`RuntimeDatabaseSetupServiceIntegrationTest`
and
`DatabaseSetupServiceIntegrationTest`
call `createCompleteSetup`, `addQueue`, `addEventStore`, `connectToExistingSetup`, `destroySetup` and
`dropSetupDatabase`.

`createCompleteSetup` runs these steps:

1. Validates the schema name.
2. Connects to the `postgres` database with the request's credentials. If the target database exists,
   it fails with `DatabaseCreationConflictException` and changes nothing. The REST server answers 409.
   Otherwise it runs `CREATE DATABASE` with the template database (default `template0`) and encoding
   (default `UTF8`).
3. Applies, in one transaction on one connection:
   - the base template;
   - the queue template for each queue;
   - the event store template for each event store, and the aggregate summary template for each event
     store with `aggregateSummaryEnabled`;
   - the `peegeeq_setup_metadata` row and one `peegeeq_object_registry` row per event store.
4. Starts a `PeeGeeQManager` for the setup. The setup id is the configuration profile name.
5. Checks that every table the base template creates exists in the schema.
6. Creates one queue factory per queue. A queue uses its `implementationType` (`native` or `outbox`) or,
   when none is given, the best available type. An unregistered requested type fails the request. A
   queue with no `implementationType` is skipped, with an INFO log, when no implementation is registered.
7. Creates one `EventStore` per event store through the service's event store factory, when it has one.
8. Writes one `peegeeq_object_registry` row per queue with the resolved type, in a second transaction.
9. Writes the persisted binding when the request sets `persistBinding` (section 5).

The database user must be able to connect to `postgres`, create databases, and create the `uuid-ossp`
and `pg_stat_statements` extensions, which the base template creates.

A failure after step 2 releases the in-memory state and drops nothing. The database created in step 2
remains. A retry with the same `databaseName` is then refused as an existing database, and
`dropSetupDatabase` cannot remove it because the setup is not active (D42 (b)). Retry with a new database
name, or drop the database outside PeeGeeQ.

The release does not happen for every late failure. The service classifies a failure as a creation
conflict when its cause chain contains `DatabaseCreationConflictException`, or a message containing
"duplicate key value violates unique constraint", or a message containing both "database" and "already
exists". It then returns the conflict without releasing the in-memory state, so a manager started in
step 4 stays registered.

`createCompleteSetup` validates the schema name, the database name and the encoding. An invalid database
name or encoding is thrown as `IllegalArgumentException` from the call itself, not returned as a failed
`Future` (D42 (c)). It does not validate queue or event store names (D42 (d)). Give every queue and event
store in the request a name that matches the identifier rule in section 2, because `addQueue` and
`addEventStore` enforce it.

### Adding queues and event stores

`addQueue(setupId, queueConfig)`:

- validates the queue name;
- refuses, with `QueueImplementationChangeException`, a change of implementation type for a queue that is
  registered with another type and has subscriptions (REST 409);
- applies the queue template on one connection without a transaction;
- creates the queue factory and then writes the registry row.

`addQueue` is not idempotent. The queue template creates its trigger without `IF NOT EXISTS`, so a second
`addQueue` with the same name fails (D42 (e)).

`addEventStore(setupId, eventStoreConfig)` validates the table name, then applies the event store template,
the aggregate summary template when `aggregateSummaryEnabled` is set, and the registry row in one
transaction. It creates the `EventStore` object only when the service has an event store factory, and
reports success without one (D42 (j)). `PeeGeeQRuntime` registers one by default.

`removeEventStore(setupId, storeName)` closes the store, then drops the aggregate summary table and the
event store table with `CASCADE` and deletes the registry row in one transaction. The functions
`notify_<table>_events()` and `maintain_<table>_aggregate_summary()` are not dropped.

## 5. Connecting, detaching and dropping

### Connect

`connectToExistingSetup(request)` attaches to a schema that `createCompleteSetup` created earlier. It
applies no DDL:

1. Validates the schema name.
2. Checks that every table the base template creates exists. A missing table fails the connect. A
   schema provisioned before a table was added to the base template is refused, not upgraded. PeeGeeQ
   has no command that adds the missing template tables to an existing schema. The refusal message
   suggests the Flyway migrations, which do not create these tables (D42 (a)).
3. Reads the setup id from `peegeeq_setup_metadata`. If the request's `setupId` is not blank and differs,
   the connect fails.
4. Reads `peegeeq_object_registry` and rebuilds each queue with its recorded type and each event store
   with its recorded configuration. The `queues` and `eventStores` of the request are ignored.
5. Refuses a setup id that is already active in the same service instance.
6. Starts the manager and registers the rebuilt factories.

### Detach, destroy and drop

| Operation | Java | REST | Effect |
|---|---|---|---|
| Destroy | `destroySetup(setupId)` | `DELETE /api/v1/setups/{setupId}` | Closes the setup's queue factories, event stores and manager, and forgets the setup in memory. Drops nothing. Leaves a persisted binding in place |
| Detach | `detachSetup(setupId)` | `POST /api/v1/setups/{setupId}/detach` | `destroySetup`, then deletes the persisted binding when a binding registry is configured |
| Drop | `dropSetupDatabase(setupId, confirmDatabaseName)` | `POST /api/v1/setups/{setupId}/database/drop` | The only operation that issues `DROP DATABASE` |
| Close service | `close()` | none | Closes every active manager. Does not close queue factories or event stores. Drops nothing |

`dropSetupDatabase` works only on a setup that is active in the same service instance. `confirmDatabaseName`
must equal the setup's database name; otherwise it fails with `IllegalArgumentException` and drops nothing
(REST 400). On a match it terminates the database's other sessions and counts them in
`pg_stat_activity`, repeating every 100 ms for at most 21 attempts until none remain. It then runs
`DROP DATABASE IF EXISTS ... WITH (FORCE)`, which requires PostgreSQL 13 or later (PostgreSQL behaviour).
After the drop it starts `detachSetup`; the returned `Future` does not wait for the detach. The REST
endpoints are listed in `PEEGEEQ_REST_API_GUIDE.md`.

### Persisted setup bindings

The three-argument constructor
`PeeGeeQDatabaseSetupService(eventStoreFactoryProvider, registryDatabase, credentialProvider)` configures
a binding registry. The registry template creates `peegeeq_setup_bindings` in the schema of
`registryDatabase`. The design intent is a schema that belongs to no setup; `SetupBindingRegistry` checks
only that the schema name is a valid identifier, not that it differs from a setup's schema. A binding
stores the setup id, connection coordinates and an optional credential reference. It never stores a
password.

- A request with `persistBinding` set writes a binding after create or connect. Without a configured
  registry, the request fails and the setup is released.
- `reloadPersistedSetups()` calls `connectToExistingSetup` for each binding, resolves passwords through the
  `CredentialProvider`, and reports skipped bindings without failing.
- `PeeGeeQRuntime` and the REST server configure no registry, and the REST request parser does not read
  `persistBinding` or `credentialRef`. No class calls `reloadPersistedSetups` (D42 (m)).

`SetupBindingPersistenceIntegrationTest`
exercises these paths.

## 6. What the templates create

Each template directory has a `.manifest` that lists its SQL files in execution order. `SqlTemplateProcessor`
replaces the placeholders `{schema}`, `{queueName}` and `{tableName}` and sends each file as one
simple-protocol query. PostgreSQL runs every statement in such a query. A review probe on PostgreSQL 15.13
ran all three statements of a file shaped like `04a-core-table-outbox.sql` this way, so the idempotency
unique indexes are created on template schemas. The processor's Javadoc says that only the first statement
runs; that comment is wrong (D42 (k)).

The processor rejects a placeholder value that contains `'`, `;`, `--` or `/*`. Files in a directory that
the manifest does not list are not run (D42 (g)).

### Base template (once per provisioned schema)

- The `uuid-ossp` and `pg_stat_statements` extensions, and the schema if it is absent.
- `SET search_path TO <schema>` for the provisioning session (`04-search-path.sql`).
- The message tables `outbox`, `queue_messages`, `queue_message_group_exclusions` and `dead_letter_queue`.
- The template tables `queue_template` and `event_store_template`, dropped and recreated each time the base
  template runs, with their indexes.
- The consumer-group tables `outbox_topics`, `outbox_topic_subscriptions`, `outbox_consumer_groups`,
  `processed_ledger` and `consumer_group_index`, and `bitemporal_subscriptions`.
- `peegeeq_object_registry`: one row per queue or event store, with its kind (`native`, `outbox` or
  `bitemporal`) and its configuration as JSON. A queue's kind cannot be read from its tables, because
  native and outbox queues use identical DDL.
- `peegeeq_setup_metadata`: one row naming the setup id that owns the schema.

### Per queue

- A table named after the queue, created with `LIKE queue_template INCLUDING ALL`. PostgreSQL copies the
  template's columns, defaults, CHECK constraints and indexes once; later changes to `queue_template` do not
  reach existing queue tables (PostgreSQL behaviour).
- Indexes named `idx_<queueName>_...` on topic and visibility, lock, status, correlation id, priority,
  headers and idempotency key. They exist in addition to the indexes copied from `queue_template`.
- A function `notify_<queueName>_changes()` and a trigger `trigger_<queueName>_notify` that fires after
  insert, update and delete.

Native and outbox messages are stored in the shared `queue_messages` and `outbox` tables, keyed by
topic. No class in the main sources of `peegeeq-native`, `peegeeq-outbox`, `peegeeq-db` or `peegeeq-rest`
writes to the per-queue table (search of the main sources).

### Per event store

- A table named by `tableName`, created with `LIKE event_store_template INCLUDING ALL`. This table holds the
  store's events. PostgreSQL copies the template's CHECK constraints and its unique index
  `idx_event_store_version_family_unique` into it (PostgreSQL behaviour).
- Indexes on valid time, transaction time, event id, event type, aggregate id, correlation id, version,
  corrections, payload and headers. The file `02f1-index-causation.sql` is not in the manifest, so no
  causation index is created (D42 (g)).
- A function `notify_<tableName>_events()` and an after-insert trigger. It notifies on
  `<schema>_bitemporal_events_<table>` and on a channel per event type. `notificationPrefix` is not used.
  `PEEGEEQ_EVENT_STORE_GUIDE.md` section 7 gives the channel names.
- With `aggregateSummaryEnabled`: a table `<tableName>_aggregate_summary`, keyed by aggregate id and event
  type, maintained by a trigger on every insert.

### Registry template

The `registry` template creates the registry schema if absent and the `peegeeq_setup_bindings` table. Only
`SetupBindingRegistry.ensureSchema()` applies it.

## 7. Queue deletion

`DatabaseSetupService` has no `removeQueue`. Queues are deleted only through two REST handlers in
`ManagementApiHandler`:

| Endpoint | Handler | Messages | Factory | Schema objects | Registry row |
|---|---|---|---|---|---|
| `DELETE /api/v1/management/queues/{setupId}/{queueName}` | `deleteQueue` | Kept | Closed; the response waits for the close. A failed close answers 503 and the queue stays listed | Kept | Kept |
| `DELETE /api/v1/queues/{setupId}/{queueName}` | `deleteQueueByName` | Deleted from `queue_messages` or `outbox` where the topic is the queue name | Closed, but the close result is not observed (D38 (a)) | Kept | Kept |

Both handlers remove the queue from the active setup's in-memory factory map. The queue then disappears
from `GET /api/v1/setups/{setupId}/queues`, which reads that map. Neither handler touches the queue's
subscriptions.

Consequences (D42 (f)):

- The per-queue table, its indexes, its function and its trigger remain in the schema.
- The `peegeeq_object_registry` row remains. A later `connectToExistingSetup` of the same schema rebuilds
  the queue's factory, so the queue appears again.
- A later `addQueue` with the same name fails, because the trigger already exists.
- `deleteQueue` leaves the queue's messages in the shared message table.

PeeGeeQ has no operation that removes a queue completely. To do it by hand, delete the queue's messages,
drop its table, drop the function `notify_<queueName>_changes()`, and delete its row from
`peegeeq_object_registry`. `DROP TABLE ... CASCADE` also drops the table's indexes and trigger
(PostgreSQL behaviour).

Event stores are removed through `removeEventStore` (section 4). The REST endpoints
`DELETE /api/v1/eventstores/{setupId}/{eventStoreName}` and
`DELETE /api/v1/management/event-stores/{storeId}` call it.
