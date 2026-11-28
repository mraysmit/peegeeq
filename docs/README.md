# PeeGeeQ Documentation
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 2.0

This directory holds the documentation for developers who adopt PeeGeeQ and operators who run it.
Design proposals, implementation records and task tracking are under `docs-design/`.

## Guides

| Guide | Contents |
|---|---|
| `PEEGEEQ_GETTING_STARTED.md` | What PeeGeeQ provides, choosing a delivery model, local stack startup, the `Future` contract, caller-owned transactions, first producer and consumer |
| `PEEGEEQ_ARCHITECTURE.md` | Modules and layers; native queue, outbox and bi-temporal event store; delivery semantics; when PostgreSQL is the right queue; subscription and backfill sequence diagrams |
| `PEEGEEQ_MESSAGING_GUIDE.md` | Producer send modes and transaction ownership, consumers, consumer groups, subscriptions and start positions, filtering, retry and dead letter, payload serialisation and CloudEvents |
| `PEEGEEQ_ORDERING_GUIDE.md` | Simple versus partitioned consumption, `OFFSET_WATERMARK`, partition keys |
| `PEEGEEQ_EVENT_STORE_GUIDE.md` | Valid and transaction time, append, query, corrections, causation, subscriptions, keyset pagination |
| `PEEGEEQ_CONFIGURATION_GUIDE.md` | Every runtime property, its default, validation and precedence |
| `PEEGEEQ_DATABASE_GUIDE.md` | Migrations, setup templates, schemas, provision, connect, detach and drop |
| `PEEGEEQ_REST_API_GUIDE.md` | Every HTTP route, SSE stream and WebSocket path of the REST server |
| `PEEGEEQ_OPERATIONS_GUIDE.md` | Lifecycle and shutdown, background recovery, health, PostgreSQL connectivity and failover, metrics, tracing and logging |
| `PEEGEEQ_CONTRIBUTOR_GUIDE.md` | Working sequence, toolchain, test profiles, Jenkins, and the names of the coding and testing standards |

The service manager module is documented in
`peegeeq-service-manager/README.md`.

## Documentation rules

1. One document owns each fact. Other documents refer to it by name. Runtime properties are owned by the
   Configuration Guide, REST endpoints by the REST API Guide, and task status by the
   task register (`docs-design/tasks/tasks.md`).
2. No status, test counts, verification dates or "production ready" claims. Status lives in the task
   register.
3. Performance figures appear only as dated release evidence. A guide names that evidence and does not copy
   the figures.
4. Proposals live in `docs-design/` until they are implemented.
5. Examples follow the coding principles (`docs-design/dev/pgq-coding-principles.md`). Naming a
   compiled example is preferred to a pasted snippet.
6. Every claim is checked against the code when it is written. A document is updated to match the
   code; it is never quoted as evidence of what the code does. A statement about runtime behaviour
   that was read in the code and not observed is marked "(read in code, not run)".
7. Plain declarative sentences; no slang; purposes and reasons stated in plain words.
8. Text removed from this directory is kept in git history, not copied elsewhere.
9. A code defect found while writing a document goes into the defect table of the task register. The
   document states what the code does and names the defect; it does not describe the defect as intended
   behaviour.
10. No Markdown links. Links break when files move. A document refers to another document by its file
    name in backticks, with the repository path when the file is outside `docs/`, and to a section by its
    number or heading. Code is referred to by class name or path.

The coding and testing standards under `docs-design/dev` and `docs-design/testing` are normative.
The guides in this directory do not weaken or replace them.
