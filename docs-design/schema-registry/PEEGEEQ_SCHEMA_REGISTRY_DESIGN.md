# PeeGeeQ Schema Registry — Design

**Author**: Mark Andrew Ray-Smith Cityline Ltd
**Created**: 2026-07-18
**Status**: DRAFT — for review
**Version**: 0.1

---

## 1. Problem

PeeGeeQ has no mechanism for standardising message contracts between producers and consumers.
A producer can change its payload shape at any time. Consumers find out when they break.
There is no record of what shape a topic's messages have, no history of how that shape has
changed, and no check that a change is safe.

This design adds a schema registry: a per-setup store of versioned message contracts with
compatibility rules enforced at registration time.

## 2. Requirements

| # | Requirement |
|---|---|
| R1 | **Subjects.** One contract per topic. A subject is a queue name or an event-store event type within a setup. |
| R2 | **Versions.** Each subject has an append-only version history. Versions are never edited or deleted. |
| R3 | **Compatibility enforcement.** Each subject has a compatibility mode. A new version that violates the mode is rejected at registration with a clear error. No producer can ever use an incompatible schema, so evolution is safe by construction. |
| R4 | **Lookup.** Producers and consumers resolve subject → latest (or a pinned) version. Messages carry a schema reference so a consumer knows which writer schema produced them. |
| R5 | **Opt-in.** A topic with no registered subject behaves exactly as today. Nothing existing changes behaviour. |
| R6 | **Self-describing.** The registry lives in the setup's own PostgreSQL schema and is available after `connectToExistingSetup` with no extra infrastructure. |

## 3. Non-goals (v1)

- **Binary Avro storage or wire format.** Payloads remain JSON in JSONB. Binary encoding is a
  possible later optimisation and changes nothing in this design's data model.
- **Org-wide contract sharing.** Contracts are per-setup. Cross-setup distribution belongs to
  the Phase M management database and is out of scope here.
- **Mandatory publish-time payload validation.** v1 enforces compatibility at registration and
  provides lookup. Per-subject publish-time validation is a later, flagged addition (§10).
- **An external registry service.** No Confluent Schema Registry or other external dependency.
  PeeGeeQ setups remain self-contained.

## 4. Schema language: Avro schemas, JSON-encoded payloads

**Decision: contracts are Apache Avro schemas. Payloads stay JSON.**

Reasons:

1. Avro's schema resolution rules are formally specified. "Can a reader with schema A read
   data written with schema B" is a defined algorithm, not a convention. This is the property
   R3 needs.
2. The Avro Java library implements these rules (`SchemaValidatorBuilder`: canRead /
   canBeRead / mutualRead, validateLatest / validateAll). PeeGeeQ does not hand-roll
   compatibility checking.
3. The Avro specification defines a JSON encoding. Payloads therefore remain human-readable
   JSON in the existing JSONB columns. The message browser, `/stats`, bitemporal queries, and
   `pg_notify` are unaffected. There is no storage-layer change.

JSON Schema was considered and rejected as the contract language: its open/closed content
model makes compatibility checking ill-defined, and tooling disagrees on what "compatible"
means. Avro's rules are exact.

## 5. Data model

One new table in the setup's PostgreSQL schema, created by the base schema-template mechanism
(a new `10c-schema-registry.sql` appended to the base `.manifest`, so
`resolveRequiredTables` includes it in `validateDatabaseInfrastructure` — the same pattern as
`10a-setup-object-registry.sql` and `10b-setup-metadata.sql`).

```sql
CREATE TABLE IF NOT EXISTS peegeeq_schema_registry (
    subject            VARCHAR(255)  NOT NULL,
    version            INTEGER       NOT NULL,
    fingerprint        VARCHAR(64)   NOT NULL,  -- SHA-256 hex of the Avro Parsing Canonical Form
    schema_text        TEXT          NOT NULL,  -- the Avro schema JSON, as registered
    compatibility_mode VARCHAR(32)   NOT NULL,  -- mode in force when this version was accepted
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (subject, version),
    CONSTRAINT chk_compat CHECK (compatibility_mode IN
        ('NONE','BACKWARD','FORWARD','FULL',
         'BACKWARD_TRANSITIVE','FORWARD_TRANSITIVE','FULL_TRANSITIVE'))
);

CREATE INDEX IF NOT EXISTS idx_schema_registry_fingerprint
    ON peegeeq_schema_registry (fingerprint);
```

Notes:

- The fingerprint is computed with Avro's `SchemaNormalization` over the Parsing Canonical
  Form, so formatting and property ordering do not produce distinct fingerprints.
- Registering a schema whose fingerprint equals the subject's latest version is idempotent:
  the existing version is returned, nothing is written.
- The subject's current compatibility mode is the `compatibility_mode` of its latest row.
  Changing the mode is done by registering the next version under the new mode. A dedicated
  per-subject config row is not needed in v1.
- Rows are immutable. There is no UPDATE or DELETE path in v1.

## 6. Compatibility modes and the registration algorithm

| Mode | Meaning | Checked against |
|---|---|---|
| NONE | No check. | — |
| BACKWARD | New readers can read data written with the previous schema. | latest version |
| FORWARD | Previous readers can read data written with the new schema. | latest version |
| FULL | Both directions. | latest version |
| BACKWARD_TRANSITIVE / FORWARD_TRANSITIVE / FULL_TRANSITIVE | Same, against every prior version. | all versions |

Registration algorithm (single transaction, `withTransaction`):

1. Parse the submitted schema with the Avro parser. Parse failure → rejected, 400.
2. Compute the fingerprint. If it equals the latest version's fingerprint → return the
   existing version (idempotent), 200.
3. Load the prior version(s) required by the subject's mode.
4. Run the Avro `SchemaValidator` for the mode. Violation → rejected, 409, with the
   validator's message (which names the offending field).
5. Insert `(subject, latest+1, fingerprint, schema_text, mode)`. Return the new version, 201.

Concurrent registration of the same subject is serialised by the primary-key insert: the
loser's insert fails, the service retries the algorithm once from step 2.

## 7. Message schema reference

Producers that use a registered subject attach two headers to each message:

```
x-schema-subject:     <subject>
x-schema-fingerprint: <sha-256 hex>
```

Headers travel in the existing `headers` JSONB column. Consumers resolve the fingerprint via
the registry (`GET …/schemas/ids/{fingerprint}`) to obtain the writer schema, then apply Avro
JSON decoding with their own reader schema. Messages without these headers are handled as
today (R5).

## 8. Java API

New interface in `peegeeq-api`, implemented in `peegeeq-db`, delegated by
`RuntimeDatabaseSetupService`, exposed by `peegeeq-rest`. All methods return Vert.x `Future`.
No banned patterns.

```java
public interface SchemaRegistryService {
    /** Registers a new version. Fails with SchemaCompatibilityException on violation. */
    Future<RegisteredSchema> register(String setupId, String subject,
                                      String avroSchemaJson, CompatibilityMode mode);

    Future<RegisteredSchema>       getLatest(String setupId, String subject);
    Future<RegisteredSchema>       getVersion(String setupId, String subject, int version);
    Future<RegisteredSchema>       getByFingerprint(String setupId, String fingerprint);
    Future<List<String>>           listSubjects(String setupId);
    Future<List<RegisteredSchema>> listVersions(String setupId, String subject);

    /** Dry-run of the compatibility check. Never writes. */
    Future<CompatibilityResult> checkCompatibility(String setupId, String subject,
                                                   String avroSchemaJson);
}
```

`RegisteredSchema` = subject, version, fingerprint, schemaText, compatibilityMode, createdAt.
The Avro library dependency (`org.apache.avro:avro`) is confined to `peegeeq-db`.

## 9. REST surface

| Method + path | Purpose | Statuses |
|---|---|---|
| `POST /api/v1/setups/{setupId}/schemas/{subject}/versions` | Register. Body: `{ schema, compatibilityMode }`. | 201 new / 200 idempotent / 400 parse / 409 incompatible / 404 setup |
| `GET /api/v1/setups/{setupId}/schemas/{subject}/versions` | List versions. | 200 / 404 |
| `GET /api/v1/setups/{setupId}/schemas/{subject}/versions/{version}` | One version (`latest` accepted). | 200 / 404 |
| `GET /api/v1/setups/{setupId}/schemas/{subject}/compatibility` | Dry-run check. Body: `{ schema }`. | 200 with `{ compatible, messages[] }` |
| `GET /api/v1/setups/{setupId}/schemas` | List subjects. | 200 |
| `GET /api/v1/setups/{setupId}/schemas/ids/{fingerprint}` | Resolve a message's schema reference. | 200 / 404 |

Error mapping follows the existing `DatabaseSetupHandler` pattern: cause-typed 4xx, 503
otherwise, no error swallowing.

## 10. Enforcement points

- **v1: registration-time only.** The registry rejects incompatible versions (§6). Producers
  and consumers use lookup (§7). Payloads are not validated per publish.
- **Later (flagged, per subject):** publish-time validation — the REST publish path validates
  the payload against the subject's latest schema and rejects non-conforming messages with
  400. Off by default. Requires a benchmark before adoption; not part of v1.

## 11. Adoption order

1. **Bitemporal event store first.** Event stores are long-lived records; schema evolution
   over time is where the absence of contracts hurts most. Subject = event type.
2. **Queues second.** Subject = queue name.

Both are opt-in per topic (R5).

## 12. UI

- **peegeeq-management-ui (later phase):** a schema browser — subjects per setup, version
  history, diff between versions, dry-run compatibility check form.
- **peegeeq-utilities-ui (much later):** schema-aware template generation in the message
  generator. Out of scope until the generator (Phase B–G) is complete.

## 13. Testing (TestContainers, no mocks)

Integration tests at the `peegeeq-db`/runtime layer:

1. Register v1 → 201; re-register identical → idempotent 200, still one row.
2. BACKWARD: add a field **with** a default → accepted. Remove a field a reader needs →
   rejected 409, row count unchanged.
3. FORWARD: add a field **without** a default → rejected. Delete an optional field → accepted.
4. FULL: only mutually-readable changes accepted; both violation directions rejected.
5. Transitive: change compatible with latest but incompatible with v1 under
   `*_TRANSITIVE` → rejected.
6. Fingerprint lookup returns the correct version; unknown fingerprint → 404.
7. `connectToExistingSetup` on a setup with registered schemas: subjects and versions are
   readable with no re-registration (R6).
8. Concurrent registration of the same subject: exactly one new version, the retry path
   returns it.

REST layer: status mapping per §9, exercised against a real server (the
`SetupManagementIntegrationTest` pattern).

## 14. Phasing

| Phase | Scope | Verification |
|---|---|---|
| SR.1 | This design, reviewed. | User review. |
| SR.2 | `10c-schema-registry.sql` + manifest; table present in create and validated on connect. | Runtime IT: table exists after create; `connectToExistingSetup` passes validation. |
| SR.3 | `SchemaRegistryService` in api/db/runtime + compatibility enforcement (Avro validator). | §13 tests 1–5, 7, 8. |
| SR.4 | REST surface + handler. | §13 REST tests, incl. 6. |
| SR.5 | Event-store adoption: schema-reference headers on publish, resolve on subscribe. | IT: produce/consume round-trip with schema headers; unregistered topic unchanged. |
| SR.6 | Queue adoption. | Same shape as SR.5. |
| SR.7 | management-ui schema browser. | Real-backend e2e. |

Each phase lands separately, one at a time, with the standard pre-work and banned-pattern
checks.
