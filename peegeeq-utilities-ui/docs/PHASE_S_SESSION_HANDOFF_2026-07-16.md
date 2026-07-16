# Phase S — Session Handoff (2026-07-16)

**Start-here note for the next session.** Phase S is "both UIs (management-ui + utilities-ui) can
connect to an *existing* setup" — the blocker for the utilities-ui work. This session completed the
**backend foundation** (the self-describing registry + the `connectToExistingSetup` primitive). What
remains is the REST route and the UI.

Authoritative spec: [`PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md`](PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md) §4, §12, §13.
Phase tracking: [`PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md`](PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md) → "Phase S".

---

## 1. Done this session (green)

| Step | What | Where |
|---|---|---|
| S.2a | Registry writes on all object lifecycle events + delete-sync | `peegeeq-db` `PeeGeeQDatabaseSetupService` |
| S.1 | `connectToExistingSetup` interface method (non-breaking `default`) | `peegeeq-api` `DatabaseSetupService` |
| S.2 | `connectToExistingSetup` impl (separate non-destructive path) + reconstitution | `peegeeq-db` `PeeGeeQDatabaseSetupService` |

Registry data model (created in commit `fa209b61` via the base schema-template, `10a`/`10b`):
- `peegeeq_object_registry` — one row per queue/event-store: `object_name` (pk), `kind`
  (`native`|`outbox`|`bitemporal`), `config` (jsonb), `created_at`.
- `peegeeq_setup_metadata` — single self-identifying row: `setup_id` (pk), `schema_name`,
  `schema_version`, `created_at`.

**Key methods** (all in `peegeeq-db/.../db/setup/PeeGeeQDatabaseSetupService.java`):
- `insertSetupMetadata`, `insertObjectRegistry` (**upsert**, binds config as `JsonObject`),
  `deleteObjectRegistry`, `persistQueueRegistry` — the registry write/delete primitives.
- `connectToExistingSetup`, `reconstituteFromRegistry`, `buildReconstitutedContents`,
  `ReconstitutedContents` — the connect path.
- Bulk create writes live in `applySchemaTemplates` (metadata + event-store rows) and
  `createCompleteSetup` step 4 (queue rows via `persistQueueRegistry`).

**Tests** (real TestContainers, `PgConnectionManager` verification — the project standard):
`peegeeq-runtime` `RuntimeDatabaseSetupServiceIntegrationTest` `@Order(4..8)`:
create-registry, addQueue, addEventStore, removeEventStore, **connect-reconstitution**. Green
**runtime 8/8, db `PeeGeeQDatabaseSetupServiceEnhancedTest` 13/13**.

---

## 2. Critical context / gotchas (read before touching this code)

1. **`connectToExistingSetup` is a deliberately SEPARATE path** — do NOT fold it into
   `createCompleteSetup`. Decision this session: keep create untouched as a failsafe. Some small
   logic (manager start + register) is duplicated on purpose.
2. **JSONB must be bound as `io.vertx.core.json.JsonObject`, never a String.** Binding a `String`
   to `CAST($n AS JSONB)` makes the Vert.x pg client quote it → stored double-encoded as a JSON
   *string*, which fails to deserialize on reconnect. This was a latent bug fixed this session;
   don't reintroduce it. (When asserting config in tests, assert it round-trips, not just non-null.)
3. **Cross-module test runs need a fresh `peegeeq-db` in `.m2`.** `peegeeq-runtime` resolves
   `peegeeq-db` from the local repo, so after changing `peegeeq-db` main code/resources run
   `mvn install -pl :peegeeq-db -am -DskipTests` before the runtime test, or it uses a stale jar.
4. **`destroySetup` is non-destructive** — it closes the manager + clears in-memory maps but does
   NOT drop the database. Used as connect's failure-cleanup. (DB drop is not done anywhere in this
   service.)
5. **Queue delete-sync is moot today** — there is no `removeQueue` in the service/interface, and
   REST `deleteQueue` (`ManagementApiHandler`) is in-memory-only and never touches the DB registry.
   No queue-row drift is possible. Add a `deleteObjectRegistry` call *only if* a `removeQueue` is
   introduced.
6. **`validateDatabaseInfrastructure` derives required tables from the base manifest**
   (`resolveRequiredTables("base")`), which now includes `10a`/`10b`. A legacy setup provisioned
   before the registry tables existed will FAIL connect at validation — expected (spec §12.5).
7. Test verification connections use **`PgConnectionManager`** (not a hand-rolled `PgBuilder.pool()`).
   Production operational pools do use `PgBuilder.pool()` (mirrors `validateDatabaseInfrastructure`).

---

## 3. Pick up here — Phase S remaining (recommended order)

### S.3 — REST route (do this next; unblocks the UI)
- `peegeeq-rest` `DatabaseSetupHandler`: add `POST /api/v1/database-setup/connect` →
  `connectToExistingSetup`. Mirror the existing create route.
- `RestDatabaseSetupService` (`peegeeq-rest`): add a `connectToExistingSetup` delegation
  (`RuntimeDatabaseSetupService` already delegates; `RestDatabaseSetupService` does not yet).
- Spec: §4 table row 3, §12.2 "the connect delta". The request DTO is the same as create; on connect
  the `queues`/`eventStores` body is **ignored** (reconstituted), and `setupId` is validated against
  the recovered value.
- TDD: `peegeeq-rest` integration test — POST connect against a pre-provisioned DB, assert 200 +
  reconstituted contents; assert 400/clear error when the schema is absent.

### S.0 — non-destructive create guard (backend hardening, independent)
- `peegeeq-api`/`db`/`rest`: add an `overwrite` flag (default `false`) to the create path; refuse
  with **409 before any drop** when the DB already exists; force-drop only under `overwrite` (log at
  WARN, not INFO). Spec §13. This is the "create must not silently wipe an existing setup" guard.

### S.4 / S.5 — UI (needs S.3)
- S.4 (reference): `peegeeq-management-ui` — "Connect to Existing" button + modal, POST to
  `database-setup/connect`. Spec §12.1/§12.2.
- S.5 (port): `peegeeq-utilities-ui` — `setupService.connectExisting` + "Connect to existing setup"
  form, **replacing** the Create Setup page. Spec §12.4, §12.5 (post-connect repopulation).
- Reminder: Admin UI + APIs stay non-destructive by default (browse, never consume).

### S.6 — remove utilities-ui provisioning UI (after S.5)
- Remove `CreateSetupPage`, `CreateQueuePage`, their routes, and `setupService.createSetup` /
  `queueService.createQueue`; repoint create CTAs at "Connect to existing setup" + a pointer to the
  admin tool. Spec design §6.4/§6.5.

### Not blocking Phase S, but noted
- `connectToExistingSetup` diff (+294 in the service) has **not been code-reviewed** yet.
- Java-25 baseline: Netty logs `Unsafe`/native-access DEBUG noise; optional to silence with
  `--enable-native-access=ALL-UNNAMED` in surefire argLine (separate chore).
- `peegeeq-examples-spring` Spring Boot 4.0.7 runtime verification (`mvn verify`) still deferred.

---

## 4. Uncommitted files (this session — commit these together)

```
peegeeq-api  src/main/java/.../api/setup/DatabaseSetupService.java          (S.1)
peegeeq-db   src/main/java/.../db/setup/PeeGeeQDatabaseSetupService.java     (S.2a writes + S.2 connect + JSONB fix)
peegeeq-runtime src/main/java/.../runtime/RuntimeDatabaseSetupService.java   (delegation)
peegeeq-runtime src/test/java/.../RuntimeDatabaseSetupServiceIntegrationTest.java  (5 new tests)
peegeeq-runtime src/test/resources/logback-test.xml                         (new — module logging config)
peegeeq-utilities-ui/docs/PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md    (status)
peegeeq-utilities-ui/docs/PHASE_S_SESSION_HANDOFF_2026-07-16.md              (this doc)
```

The registry DDL (`10a`/`10b`), the base-template `.manifest` entries, and the bulk-create writes are
already committed in `fa209b61`.

---

## 5. Test commands (narrow scope — never `-Pall-tests`, it's 60+ min)

```powershell
# Refresh peegeeq-db in .m2 after changing its main code (required for cross-module runtime tests)
mvn install "-pl" ":peegeeq-db" "-am" "-DskipTests"

# The registry + connect integration tests (runtime module, real factories)
mvn test "-Pintegration-tests" "-pl" ":peegeeq-runtime" "-Dtest=RuntimeDatabaseSetupServiceIntegrationTest" 2>&1 | Tee-Object -FilePath logs\runtime-registry-<date>.txt

# The bulk-create registry test (db module)
mvn test "-Pintegration-tests" "-pl" ":peegeeq-db" "-Dtest=PeeGeeQDatabaseSetupServiceEnhancedTest" 2>&1 | Tee-Object -FilePath logs\db-enhanced-<date>.txt
```
