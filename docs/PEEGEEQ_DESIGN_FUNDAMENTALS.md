# PostgreSQL as a Transactional Message Queue: Architecture, Failure Modes, and Safe Implementation

Version: 1.1  
Date: May 11, 2025  
Author: Mark Andrew Ray-Smith Cityline Ltd


*With Java and Vert.x Implementation Guidelines*

---

PostgreSQL is often used as a job queue that is not automatically the wrong decision. If we already have it running and we know how to operate it then it might  If a job must be created atomically with business data, using the same database is cleaner than introducing a separate broker.

For a specific class of business workflow, PostgreSQL is not merely a convenient queue. It is the correct choice because it provides features such as guarantees that no broker-based alternative can match without significant added complexity.

---

## The Hard Problem: Atomic Business Transactions

Consider this business cycle, common in any event-driven backend:

```
BEGIN
  1.  Consume incoming work item   (dequeue / mark claimed)
  2.  Read and mutate domain tables (business logic)
  3.  Publish outgoing event        (enqueue result or notification)
COMMIT
```

All four operations — claim, domain read, domain write, result publication — must commit atomically. If anything fails, nothing should be visible. No partial state. No orphaned messages. No compensation logic.

This guarantee is impossible when an external broker is the queue. Kafka, RabbitMQ, and SQS do not participate in your PostgreSQL transaction. The moment you acknowledge a Kafka message and your database write subsequently fails, you have split-brain state.

The Transactional Outbox pattern is the standard answer for cross-service distribution, and it is the right answer in that context. But it introduces a CDC pipeline, a broker, and a consumer — all to solve a problem you do not have when producer and consumer share the same transactional boundary.

Saga patterns are the other common answer. They are the right answer when the workflow genuinely spans independent services with independent databases. But a saga is a distributed protocol for handling partial failure. Choosing it for a single-service workflow because the queue happened to be a broker is not architecture — it is unnecessary complexity.

For workflows where the business logic lives in one service with one database, staying inside PostgreSQL gives you stronger guarantees than any broker-based alternative, without sagas, compensating transactions, or eventual consistency.

The better conclusion is not:

> Do not use PostgreSQL as a queue.

The better conclusion is:

> Use PostgreSQL when the queue is part of your transactional data model. Use a broker when the queue is your runtime distribution fabric.

That is a much sharper distinction than small versus large.

---

## When Each Tool Is Right

| Scenario | Right tool |
| --- | --- |
| Event must cross a service boundary | Transactional Outbox → Kafka / broker |
| Same service, same transactional boundary | PostgreSQL queue — this document |
| Audit / history must survive independently | Append-only PostgreSQL event store |
| Fan-out to many independent consumers | Kafka / Pulsar |
| Replay is a requirement | Append-only event store or log |
| Bounded-context workflow with atomic guarantees | PostgreSQL queue |

---

## The Trap: Why Naive Queue Designs Fail

PostgreSQL is not weak. The problem is that a naïve queue design asks PostgreSQL to do a large amount of relational database work for what is logically "give the next worker a unit of work."

A queue table is a different workload from ordinary business data. It is hot, write-heavy, short-lived, and highly concurrent. Rows are inserted, claimed, updated, retried, completed, and often deleted — sometimes within seconds of creation. That access pattern pushes directly against PostgreSQL's MVCC, row locking, WAL, indexing, and vacuum machinery.

### The Standard Anti-Pattern

The typical table-backed queue starts with something like this:

```sql
CREATE TABLE job_queue (
    id          bigserial PRIMARY KEY,
    status      text NOT NULL DEFAULT 'pending',
    payload     jsonb NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    locked_by   text,
    locked_at   timestamptz,
    attempts    integer NOT NULL DEFAULT 0
);

CREATE INDEX idx_job_queue_pending
    ON job_queue (created_at)
    WHERE status = 'pending';
```

Workers claim jobs using `SELECT ... FOR UPDATE SKIP LOCKED`:

```sql
UPDATE job_queue
   SET status    = 'processing',
       locked_by = $1,
       locked_at = now(),
       attempts  = attempts + 1
 WHERE id = (
     SELECT id FROM job_queue
      WHERE status = 'pending'
      ORDER BY created_at
      LIMIT 1
      FOR UPDATE SKIP LOCKED
 )
 RETURNING *;
```

The appeal is obvious. Multiple workers can compete for work without blocking each other. PostgreSQL explicitly supports `SKIP LOCKED`. But `SKIP LOCKED` avoids waiting — it does not make row locking, visibility checks, index access, WAL generation, or vacuum free.

### The Physical Cost of Mutable State

The classic mutable queue lifecycle is:

```
INSERT pending job
UPDATE row to processing
UPDATE row to completed
DELETE row
```

An `UPDATE` in PostgreSQL does not modify a tuple in place. PostgreSQL's MVCC model creates a new row version. Deletes leave dead tuples behind until vacuum cleans them. Indexes accumulate dead entries. A queue table magnifies this because rows are constantly changing state. The queue becomes a machine for producing:

```
dead tuples
index churn
WAL volume
vacuum pressure
checkpoint pressure
visibility-map churn
hot index-page contention at the leading edge of the pending partial index
```

The SQL stays simple. The physical workload is not.

### The Failure Mode Is Non-Linear

```
workers increase  →  throughput improves
                  →  then flattens
                  →  latency spikes
                  →  wait events rise
                  →  vacuum falls behind
                  →  throughput collapses
```

This is why "just add workers" can make a PostgreSQL queue significantly worse, not better.

### Workload Shape, Not Scale

A common but unhelpful framing is small scale versus large scale. A queue with 20 workers can be pathological. A queue with 500 workers can be stable. Worker count alone tells you almost nothing. The real question is what physical access pattern the queue imposes on PostgreSQL.

| Dimension | Why it matters | Warning signal |
| --- | --- | --- |
| **Claim rate** | High frequency means repeated index scans and lock checks | Hundreds of single-row claims per second |
| **Job duration** | Very short jobs increase claim churn | Sub-second jobs at high volume |
| **Mutations per job** | Each status change is a write plus a dead tuple | More than two status transitions |
| **Hot index concentration** | All workers compete at the same index leading edge | `ORDER BY created_at LIMIT 1` globally |
| **Retry behaviour** | Retries churn the same rows, amplifying bloat | Unbounded retries |
| **Completion model** | DELETE-on-complete is the most expensive pattern | Row-by-row delete at high rate |
| **Consumer topology** | Many competing workers versus fan-out are different architectures | Unlimited competing worker pool |
| **Data coupling** | If queue commits with business state, Postgres fits naturally | External broker when decoupled |
| **Blast radius** | A queue inside the primary OLTP database affects business transactions | Shared primary database |

---

## Safe Schema Design

The root cause of most PostgreSQL queue problems is a single mutable status table that tries to be queue, audit log, retry tracker, dead-letter store, and dashboard source at the same time. These are separate concerns with different physical access patterns.

### Separate Append-Only Tables from Operational Claim State

```sql
-- Incoming work: append only, never updated after insert
CREATE TABLE job_inbox (
    id          bigserial PRIMARY KEY,
    queue       text NOT NULL,
    payload     jsonb NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Operational claim state: small, hot, short-lived
-- Only in-flight rows exist here. Completed jobs have no row.
CREATE TABLE job_claim (
    job_id      bigint PRIMARY KEY REFERENCES job_inbox(id),
    worker_id   text NOT NULL,
    claimed_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    attempts    integer NOT NULL DEFAULT 1
);

-- Completion record: append only
CREATE TABLE job_result (
    id          bigserial PRIMARY KEY,
    job_id      bigint NOT NULL REFERENCES job_inbox(id),
    status      text NOT NULL,   -- 'completed' | 'failed' | 'dead'
    result      jsonb,
    worker_id   text NOT NULL,
    finished_at timestamptz NOT NULL DEFAULT now()
);

-- Prevents double-completion on retry
ALTER TABLE job_result
    ADD CONSTRAINT uq_job_result_job_id UNIQUE (job_id);

-- Terminal failures, for inspection and re-drive
CREATE TABLE job_dead_letter (
    id           bigserial PRIMARY KEY,
    job_id       bigint NOT NULL,
    queue        text NOT NULL,
    payload      jsonb NOT NULL,
    failure      text,
    attempts     integer NOT NULL,
    last_attempt timestamptz NOT NULL
);
```

`job_claim` stays small at all times because only in-flight rows exist in it. Vacuum has almost nothing to do on the hottest table. The inbox and result tables are append-only, which is the cheapest workload for PostgreSQL's MVCC model.

### Indexes

```sql
-- Claim query: find unclaimed, unfinished jobs in arrival order
CREATE INDEX idx_inbox_queue_created
    ON job_inbox (queue, created_at);

-- Lease expiry sweep
CREATE INDEX idx_claim_expiry
    ON job_claim (expires_at);
```

---

## Claim Mechanics: Leases, Not Status Updates

The most important design decision is replacing mutable status transitions with expiry-based leases.

### Why Leases Eliminate the UPDATE Storm

Status-update queues require: `INSERT (pending) → UPDATE (processing) → UPDATE (completed) → DELETE`. Every row is touched multiple times. Each touch generates a dead tuple and WAL.

Lease-based claiming works differently:

```
Claim    = INSERT into job_claim        (one append)
Complete = DELETE from job_claim        (one delete)
         + INSERT into job_result       (one append)

The inbox row is NEVER modified.
The claim row is NEVER updated — it is either present or deleted.
```

Crash recovery is automatic. Expired claims are reclaimed by the next claim cycle. No explicit failed state to write.

### Batch Claim SQL

Single-row claiming is the worst possible unit of work. Always claim in batches.

```sql
WITH candidates AS (
    SELECT i.id
    FROM   job_inbox i
    WHERE  NOT EXISTS (
               SELECT 1 FROM job_claim c WHERE c.job_id = i.id
           )
    AND    NOT EXISTS (
               SELECT 1 FROM job_result r WHERE r.job_id = i.id
           )
    AND    i.queue = $4
    ORDER BY i.created_at
    LIMIT    $1                         -- batch size
    FOR UPDATE OF i SKIP LOCKED
)
INSERT INTO job_claim (job_id, worker_id, claimed_at, expires_at, attempts)
SELECT
    id,
    $2,                                 -- worker_id
    now(),
    now() + ($3 * INTERVAL '1 second'), -- lease duration in seconds
    1
FROM candidates
RETURNING job_id;
```

### Lease Expiry and Automatic Recovery

```sql
-- Return timed-out jobs to the available pool.
-- Run at the start of each claim cycle, or on a separate periodic task.
DELETE FROM job_claim
WHERE  expires_at < now()
RETURNING job_id;  -- log these
```

A crashed worker needs no intervention. The lease expires and the next claim cycle picks up the work.

---

## The Full Atomic Business Transaction

This is the architecture's central guarantee: claim, domain mutation, and result publication all commit in one transaction. Either everything succeeds or nothing is visible.

### The Transaction Pattern

```
BEGIN

  -- Step 1: Claim a batch
  INSERT INTO job_claim ... RETURNING job_id;

  -- Step 2: Fetch payloads
  SELECT * FROM job_inbox WHERE id = ANY($claimedIds);

  -- Step 3: Domain business logic
  --   reads from domain tables
  --   writes to domain tables
  --   all within this same transaction

  -- Step 4: Record completion
  INSERT INTO job_result (job_id, status, result, worker_id, finished_at)
  VALUES ($jobId, 'completed', $result, $workerId, now());

  -- Step 5: Release the claim
  DELETE FROM job_claim WHERE job_id = ANY($claimedIds);

COMMIT

-- If anything above fails, the transaction rolls back.
-- The claim expires naturally and the job re-enters the pool.
-- Domain state is untouched. No partial state is visible anywhere.
```

### Java / Vert.x Implementation

Using the Vert.x reactive PostgreSQL client (`io.vertx:vertx-pg-client`):

```java
pgPool.withTransaction(conn -> {

    // Step 1: Claim a batch
    return conn.preparedQuery(CLAIM_SQL)
        .execute(Tuple.of(batchSize, workerId, leaseSeconds, queueName))
        .flatMap(claimedRows -> {

            if (claimedRows.size() == 0) {
                return Future.succeededFuture();  // nothing to do
            }

            List<Long> jobIds = StreamSupport
                .stream(claimedRows.spliterator(), false)
                .map(r -> r.getLong("job_id"))
                .toList();

            // Step 2: Fetch payloads
            return conn.preparedQuery(FETCH_SQL)
                .execute(Tuple.of(jobIds.toArray()))
                .flatMap(payloadRows -> {

                    // Step 3: Domain logic — all inside this transaction
                    return processDomainLogic(conn, payloadRows)
                        .flatMap(results -> {

                            // Step 4: Write completion
                            return writeResults(conn, results)
                                .flatMap(v ->

                                    // Step 5: Release claims
                                    conn.preparedQuery(RELEASE_SQL)
                                        .execute(Tuple.of(jobIds.toArray()))
                                );
                        });
                });
        });

    // Transaction rolls back on any failure.
    // Lease expires and job re-enters the pool.
});
```

### Idempotent Completion with Deduplication

Even with atomic transactions, retries happen — network timeouts, process restarts, lease expiry on a slow job. Domain logic must be idempotent. The unique constraint on `job_result` is the deduplication mechanism:

```java
private Future<Void> writeResults(SqlConnection conn, List<JobResult> results) {
    return conn.preparedQuery(INSERT_RESULT_SQL)
        .execute(buildTuple(results))
        .mapEmpty()
        .recover(err -> {
            if (isUniqueViolation(err)) {
                // Already completed in a previous attempt. Safe to ignore.
                return Future.succeededFuture();
            }
            return Future.failedFuture(err);
        });
}

private boolean isUniqueViolation(Throwable err) {
    // PostgreSQL SQLSTATE 23505 = unique_violation
    return err instanceof PgException pge
        && "23505".equals(pge.getSqlState());
}
```

---

## Vert.x Concurrency: The Most Critical Rule

Vert.x makes it easy to accidentally create unbounded concurrent database operations. Each verticle instance independently polling and claiming is the exact anti-pattern that produces the non-linear failure mode described above.

### Single Claim Coordinator Per Node

Use one periodic claim loop per node, not one per verticle instance:

```java
public class QueueWorkerVerticle extends AbstractVerticle {

    private final AtomicBoolean claimInProgress = new AtomicBoolean(false);
    private long timerId;

    @Override
    public void start() {
        timerId = vertx.setPeriodic(pollIntervalMs, id -> {
            if (claimInProgress.compareAndSet(false, true)) {
                claimAndProcess()
                    .onComplete(v -> claimInProgress.set(false));
            }
            // If a cycle is already running, skip this tick.
        });
    }

    @Override
    public void stop() {
        vertx.cancelTimer(timerId);
    }
}
```

### LISTEN/NOTIFY for Wake-Up

`LISTEN/NOTIFY` removes polling overhead. The durable state stays in the table; `NOTIFY` is purely a wake-up signal. It must never be treated as the queue itself — a lost notification means a delayed job, not a lost job.

#### PostgreSQL NOTIFY Coalescing Behaviour

PostgreSQL deduplicates pending `NOTIFY` notifications. If the same channel+payload pair is sent multiple times before the client reads its notification queue, **only one delivery occurs**. This is a server-side property that cannot be configured away.

In PeeGeeQ's native queue, every insert to a topic sends the same channel name as the payload. Rapid burst inserts therefore coalesce: 12 inserts may produce only 11 (or fewer) `NOTIFY` deliveries.

The correct mitigation is a **drain-on-completion** pattern: after each message is processed, immediately call `processAvailableMessages()` to fetch any remaining messages regardless of how many NOTIFYs were delivered. This turns `NOTIFY` into "there is at least one message" rather than "there is exactly one message". The periodic fallback sweep (see below) provides a secondary safety net.

> **Design rule**: A `NOTIFY` handler must fetch messages in a loop until the queue is empty, not assume exactly one message per notification.

On the producer side, after inserting into `job_inbox`:

```sql
NOTIFY job_available;
```

On the consumer side, using the Vert.x `PgSubscriber`:

```java
PgSubscriber subscriber = PgSubscriber.subscriber(vertx, connectOptions);

subscriber.connect()
    .onSuccess(v -> {
        subscriber.channel("job_available")
            .handler(notification -> {
                if (claimInProgress.compareAndSet(false, true)) {
                    claimAndProcess()
                        .onComplete(done -> claimInProgress.set(false));
                }
            });
    });

// Fallback sweep — notifications can be lost during connection drops.
vertx.setPeriodic(30_000, id -> {
    if (claimInProgress.compareAndSet(false, true)) {
        claimAndProcess()
            .onComplete(done -> claimInProgress.set(false));
    }
});
```

The periodic fallback is not optional. Notifications can be lost during connection drops, and the sweep guarantees jobs are eventually picked up regardless.

### Bounded Concurrency

Bound how many jobs are in-flight at once. The ceiling is enforced at claim time by the batch size:

```java
private static final int MAX_CONCURRENT_JOBS = 10;  // tune per workload

private Future<Void> claimAndProcess() {
    return pgPool.withTransaction(conn ->
        conn.preparedQuery(BATCH_CLAIM_SQL)
            .execute(Tuple.of(MAX_CONCURRENT_JOBS, workerId, leaseSeconds, queueName))
            .flatMap(rows -> {
                List<Long> ids = toList(rows);
                if (ids.isEmpty()) return Future.succeededFuture();
                return processBatch(conn, ids);
            })
    );
}
```

---

## Dead-Letter Handling

Jobs that fail repeatedly must move to a dead-letter table, not retry forever. Unbounded retries amplify bloat and can mask bugs indefinitely.

```java
private Future<Void> handleJobFailure(
        SqlConnection conn, long jobId, String payload,
        String queue, Throwable error, int attempts) {

    if (attempts >= maxAttempts) {
        // Terminal failure: write to dead letter and release the claim.
        return conn.preparedQuery(INSERT_DLQ_SQL)
            .execute(Tuple.of(
                jobId, queue, payload,
                error.getMessage(), attempts, OffsetDateTime.now()
            ))
            .flatMap(v ->
                conn.preparedQuery("DELETE FROM job_claim WHERE job_id = $1")
                    .execute(Tuple.of(jobId))
            )
            .mapEmpty();
    }

    // Transient failure: let the lease expire for automatic retry.
    // Do not update the claim row — just let the transaction roll back.
    return Future.failedFuture(error);
}
```

Dead-letter rows must be queryable with plain SQL, re-driveable manually by reinserting into `job_inbox`, and alerted on. They are not failures to hide.

---

## Partitioning for Long-Lived Queues

A queue table must not grow without bound. As `job_inbox` grows, claim queries slow down even with good indexes. Partitioning lets you archive or drop old data without row-by-row vacuum.

### Time-Window Partitioning

```sql
CREATE TABLE job_inbox (
    id         bigserial,
    queue      text NOT NULL,
    payload    jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

CREATE TABLE job_inbox_2026_05
    PARTITION OF job_inbox
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE job_inbox_2026_06
    PARTITION OF job_inbox
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

-- Archiving is a DDL operation, not a vacuum operation.
ALTER TABLE job_inbox DETACH PARTITION job_inbox_2026_04;
-- Then DROP or move to cold storage.
```

### Queue-Name Partitioning

If multiple queues share one database, partition by queue name so one busy queue cannot slow down another:

```sql
CREATE TABLE job_inbox (
    id         bigserial,
    queue      text NOT NULL,
    payload    jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
) PARTITION BY LIST (queue);

CREATE TABLE job_inbox_payments
    PARTITION OF job_inbox FOR VALUES IN ('payments');

CREATE TABLE job_inbox_notifications
    PARTITION OF job_inbox FOR VALUES IN ('notifications');
```

---

## Broader Design Patterns

### Prefer Append-Only State Over Status Machines

A common anti-pattern is modelling every lifecycle stage as a status update:

```
pending -> picked -> processing -> retrying -> completed -> archived
```

Every transition is an update. If those states exist mainly for observability or audit, an append-only event log is better:

```
job.created
job.claimed
job.completed
job.failed
job.retried
```

Appends are cheap. Hot-row mutation and deletion are not.

### Separate Durable History from Operational Work State

One table should not serve as queue, audit log, retry tracker, dead-letter store, and reporting source at the same time. A cleaner split:

```
job_inbox          append-only, authoritative record of what arrived
job_claim          current lease state only — stays small
job_result         append-only, authoritative record of what completed
job_dead_letter    terminal failures
```

### Use LISTEN/NOTIFY as Wake-Up Only

`LISTEN/NOTIFY` reduces polling. But it is a coordination hint, not a delivery guarantee. The durable truth must stay in a table.

```
insert durable job row
commit transaction
notify workers
workers read durable state
```

A notification can be lost. The job cannot be.

---

## Monitoring

Monitor the database, not just job throughput. By the time throughput drops, the database may already be suffering from bloat, WAL pressure, or vacuum lag.

### Application-Level Metrics

| Metric | What it tells you |
| --- | --- |
| `oldest_pending_age_ms` | Is the queue draining? A rising value means workers are falling behind. |
| `claim_latency_p99` | How long does the claim transaction take? Rising p99 signals DB pressure. |
| `processing_latency_p99` | How long does domain logic take? Use this to set lease durations. |
| `lease_expiry_rate` | Are jobs timing out before completion? The most important early warning. Non-zero and growing is a problem. |
| `dead_letter_rate` | Are jobs failing terminally? Should be zero in a healthy system. |
| `retry_rate` | Are failures transient or systematic? |
| `transaction_rollback_rate` | Is domain logic failing atomically? Rollbacks are expected; spikes are not. |
| `duplicate_completion_rate` | How often is idempotency deduplication firing? Should be low. |

### PostgreSQL Table Health

```sql
SELECT
    relname,
    n_live_tup                                          AS live_rows,
    n_dead_tup                                          AS dead_rows,
    round(
        n_dead_tup::numeric /
        NULLIF(n_live_tup + n_dead_tup, 0) * 100, 1
    )                                                   AS dead_pct,
    vacuum_count,
    autovacuum_count,
    last_autovacuum
FROM pg_stat_user_tables
WHERE relname LIKE '%job%'
ORDER BY n_dead_tup DESC;
```

### Wait Events

```sql
SELECT
    wait_event_type,
    wait_event,
    count(*) AS sessions
FROM pg_stat_activity
WHERE wait_event IS NOT NULL
GROUP BY wait_event_type, wait_event
ORDER BY sessions DESC;
```

| Wait event | Likely cause |
| --- | --- |
| `LWLock:MultiXactMemberSLRU` | Too many competing workers on hot rows. Reduce worker count or claim rate. |
| `LWLock:MultiXactOffsetSLRU` | Same root cause as above. |
| `LWLock:WALWrite` | WAL volume too high. Reduce mutation rate; increase `checkpoint_completion_target`. |
| `Lock:tuple` | Row-level lock contention. Workers converging on the same rows. |
| `Lock:transactionid` | High concurrent transaction count or transaction ID pressure. |
| `IO:SLRURead` | SLRU cache misses. Usually accompanies MultiXact pressure. |

---

## The Real Decision Model

Do not use this model:

```
small queue -> Postgres
large queue -> Kafka or Redis
```

Use workload shape instead:

| Workload characteristic | PostgreSQL fit | Design direction |
| --- | --- | --- |
| Job must commit atomically with domain state | Strong | This architecture — lease-based queue |
| Controlled claim rate, moderate job duration | Good | `SKIP LOCKED` with batch claiming |
| Very high frequency, sub-second jobs | Weak | External broker or Redis Streams |
| Many independent consumers / fan-out | Poor | Kafka / Pulsar |
| Replay is a requirement | Poor | Append-only event store or log |
| Strict global FIFO required | Weak | Reconsider the requirement or use a broker |
| Multiple status transitions per job | Weak | Append-only events instead of status mutations |
| Queue shares the primary OLTP database | Risky | Separate schema, dedicated connection pool, resource isolation |
| Burst / spike handling | Risky | Backpressure, admission control, or broker |
| Duplicate processing is tolerable | Easier | Idempotent workers and simpler claim semantics |
| Duplicate processing is unacceptable | Harder | Idempotency keys and deduplication table |

---

## Production Checklist

**Schema**

```
append-only job_inbox — never updated after insert
lease-based job_claim — insert on claim, delete on complete, never update
append-only job_result — unique constraint on job_id for idempotency
dedicated job_dead_letter table
partitioned job_inbox by time window or queue name
index on (queue, created_at) for claim queries
index on (expires_at) for lease expiry sweep
```

**Claim mechanics**

```
batch claiming — never single-row
expiry-based leases — no status update, no UPDATE storm
automatic lease recovery via periodic expiry DELETE
attempt counter increment on re-claim
dead-letter after maxAttempts — never unbounded retry
```

**Vert.x concurrency**

```
single claim coordinator per node (AtomicBoolean guard)
LISTEN/NOTIFY for primary wake-up
periodic fallback sweep for missed notifications
MAX_CONCURRENT_JOBS ceiling enforced at claim time
no unbounded verticle-per-job patterns
```

**Transactional integrity**

```
entire business cycle in one pgPool.withTransaction()
idempotent domain logic throughout
unique constraint deduplication on job_result
PgException SQLSTATE 23505 handled as idempotent success
```

**Monitoring**

```
lease_expiry_rate alerted — leading indicator of problems
dead_letter_rate alerted — should be zero in steady state
n_dead_tup / n_live_tup ratio on queue tables watched
MultiXactMemberSLRU and WALWrite wait events watched
replication lag tracked if read replicas are present
checkpoint frequency and duration tracked
```

---

## My View

My view is blunt: **`SKIP LOCKED` is a coordination primitive, not a queue architecture.**

It solves one problem:

> Do not block this worker if another worker already locked the row.

It does not solve:

```
MVCC churn
dead tuple cleanup
index bloat
WAL volume
hot index contention
retry storms
visibility checks
MultiXact pressure
vacuum lag
replication lag
blast radius on the primary database
```

That does not make `SKIP LOCKED` bad. It means it should be used with discipline.

The worst design is the one that looks simple:

```
one global queue table
one pending partial index
many workers
ORDER BY created_at LIMIT 1
FOR UPDATE SKIP LOCKED
update status
delete on completion
repeat forever
```

That is not a durable architecture. That is a future incident.

The correct design for a transactional business workflow is:

```
append-only job_inbox
lease-based job_claim (insert on claim, delete on complete)
append-only job_result with unique deduplication constraint
single claim coordinator per node
LISTEN/NOTIFY wake-up backed by durable table state
batch claiming with bounded concurrency
idempotent domain logic
dead-letter handling from day one
partitioned tables — no immortal queue
```

The strongest rule is:

> Use PostgreSQL when the queue is part of your transactional data model. Use a broker when the queue is your runtime distribution fabric.

For workflows where incoming event, domain mutation, and outgoing completion must commit atomically, PostgreSQL is not a compromise. It is the only option that actually provides the guarantee. Every broker-based alternative requires a saga, an outbox, or a consistency gap at the point where the message system meets the database.

The real question is not:

> Can PostgreSQL be used as a queue?

Of course it can.

The real question is:

> Does this queue's workload shape match PostgreSQL's strengths — durable, transactional, append-friendly — or are we using a relational database as a high-churn broker because it was convenient?

That is the engineering decision.