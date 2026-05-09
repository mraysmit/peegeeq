# PostgreSQL as a Job Queue: Useful Pattern, Dangerous Default

PostgreSQL is often used as a job queue. That is not automatically wrong. In many systems it is a pragmatic, robust choice: you already have Postgres, it is durable, transactional, observable, backed up, and operationally familiar. If a job must be created atomically with business data, using the same database can be cleaner than introducing a separate broker.

But there is a trap.

A database table that behaves like a queue is not the same workload as ordinary business data. A queue is usually hot, write-heavy, short-lived, and highly concurrent. Rows are inserted, claimed, updated, retried, completed, and often deleted. That access pattern pushes directly against PostgreSQL’s MVCC, row locking, WAL, indexing, and vacuum machinery.

The issue is not that PostgreSQL is weak. The issue is that a naïve queue design asks PostgreSQL to perform a large amount of relational database work for what is logically “give the next worker a unit of work.”

Richard Yen’s article makes this point through the common `SELECT ... FOR UPDATE SKIP LOCKED` job-queue pattern and warns about bloat, lock-management overhead, MultiXact SLRU contention, and operational collapse when this pattern is pushed too far. ([richyen.com][1])

The better conclusion is not:

> Do not use Postgres as a queue.

The better conclusion is:

> Do not use a hot mutable status table as a high-concurrency broker and then act surprised when PostgreSQL behaves like a relational database.

---

## The Standard Pattern

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

Workers then claim jobs using `SELECT ... FOR UPDATE SKIP LOCKED`, often wrapped inside an `UPDATE`:

```sql
UPDATE job_queue
   SET status = 'processing',
       locked_by = $1,
       locked_at = now(),
       attempts = attempts + 1
 WHERE id = (
     SELECT id
       FROM job_queue
      WHERE status = 'pending'
      ORDER BY created_at
      LIMIT 1
      FOR UPDATE SKIP LOCKED
 )
 RETURNING *;
```

The appeal is obvious. Multiple workers can compete for work without blocking on the same row. If one worker has already locked a row, another worker skips it and moves on.

PostgreSQL explicitly supports `SKIP LOCKED`. The official documentation says selected rows that cannot be immediately locked are skipped. It also warns that this provides an inconsistent view of the data, which is exactly why it is useful for queue-like access but unsuitable for normal relational reads that require a stable, ordered view. ([PostgreSQL][2])

That distinction matters.

`SKIP LOCKED` avoids waiting. It does not make row locking, visibility checks, index access, WAL generation, or vacuum free.

---

## Why the Pattern Is Attractive

This design has real advantages:

```text
one less infrastructure component
atomic commit with business data
simple operational model
easy inspection with SQL
simple retry and dead-letter modelling
transactional safety
familiar backup and recovery
```

For internal background jobs, transactional outbox processing, enrichment tasks, report generation, email dispatch, cleanup tasks, or low-contention workflows, this can be entirely reasonable.

The problem starts when the implementation turns into a hot mutable work-distribution fabric.

---

## “Small Scale vs Large Scale” Is the Wrong Framing

A lot of discussion around Postgres queues uses language like “small scale” and “large scale.” That is not useful enough.

A queue with 20 workers can be pathological. A queue with 500 workers can be boring. Worker count alone tells you very little. Table size alone tells you very little. Even jobs per second is not enough on its own.

The real issue is **workload shape**.

The question is not:

> Is this small or large?

The question is:

> What physical access pattern does this queue impose on PostgreSQL?

A PostgreSQL-backed queue should be assessed through these dimensions:

| Dimension                    | Why it matters                                                                                                                         |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **Claim rate**               | How often workers compete to acquire work. High claim frequency means repeated index scans, row-lock checks, and transactions.         |
| **Job duration**             | Very short jobs increase claim churn. Long jobs increase stale in-flight work and recovery complexity.                                 |
| **Mutations per job**        | `pending → processing → completed → deleted` is multiple writes for one logical job.                                                   |
| **Hot index concentration**  | `WHERE status = 'pending' ORDER BY created_at LIMIT 1` pushes all workers toward the same leading index range.                         |
| **Ordering requirements**    | Strict FIFO concentrates contention. Looser ordering allows batching, sharding, and partitioning.                                      |
| **Queue depth volatility**   | Spiky queues cause bursts of insert, claim, update, and cleanup activity. Empty queues can cause noisy polling.                        |
| **Retry behaviour**          | Retries can repeatedly churn the same rows and amplify bloat, WAL, and index updates.                                                  |
| **Completion model**         | Delete-on-complete, update-on-complete, append-only completion, and offset-based completion have very different physical costs.        |
| **Consumer topology**        | Competing workers, fan-out consumers, replay consumers, and independent subscribers are different architectures.                       |
| **Data coupling**            | If queue creation must commit with business state, Postgres is attractive. If not, a broker may be cleaner.                            |
| **Operational blast radius** | A queue inside the primary OLTP database can interfere with business transactions through WAL, bloat, lock waits, and vacuum pressure. |

So the better framing is:

> Does this workload behave like durable relational state, or like a high-churn broker workload?

That is the line that matters.

---

## Where the Standard Pattern Starts to Hurt

The classic mutable queue table usually follows this lifecycle:

```text
INSERT pending job
UPDATE row to processing
UPDATE row to completed
DELETE row
```

or:

```text
pending -> processing -> completed -> archived
```

Every step has a cost.

An `UPDATE` in PostgreSQL does not modify a tuple in place in the simplistic sense. PostgreSQL’s MVCC model creates new row versions. Deletes also leave dead tuples behind until vacuum can clean them. Indexes also accumulate dead entries. This is normal and correct database behaviour, but a queue table magnifies it because rows are constantly changing state.

The queue table becomes a machine for producing:

```text
dead tuples
index churn
WAL volume
vacuum pressure
checkpoint pressure
visibility-map churn
hot index-page contention
```

This is why queue tables often become much larger than their live data. You may have megabytes of active jobs and gigabytes of table and index footprint.

The misleading thing is that the SQL remains simple. The physical workload is not simple.

---

## The Locking and MultiXact Problem

`SKIP LOCKED` reduces blocking, but it does not eliminate lock machinery.

PostgreSQL has several row-level lock modes, including `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, and `FOR KEY SHARE`. Row-level locks are held until transaction end or savepoint rollback, and they interact with other writers and lockers of the same row. ([PostgreSQL][3])

Under concurrent queue consumption, workers repeatedly inspect candidate rows. Even when rows are skipped, PostgreSQL still has to check visibility and lock state. If many workers converge on the same hot region of the queue, the database can spend more time coordinating access than delivering useful work.

This is where MultiXact can become relevant. AWS describes MultiXact wait events as occurring when sessions retrieve the list of transactions that refer to a given row, with waits such as `LWLock:MultiXactMemberSLRU` and `LWLock:MultiXactOffsetSLRU` indicating pressure around MultiXact state. ([AWS Documentation][4])

The point is not that every Postgres queue will hit MultiXact waits. The point is that a hot competing-consumer design can push PostgreSQL into internal coordination paths that are invisible if you only look at application-level queue throughput.

The failure mode can be non-linear:

```text
workers increase
throughput improves
then flattens
then latency spikes
then wait events rise
then vacuum falls behind
then throughput collapses
```

This is why “just add workers” can make a Postgres queue worse.

---

## Bloat Is the Practical Warning Sign

Before you see exotic wait events, you will often see bloat.

Typical symptoms:

```text
queue table grows far beyond live row count
pending-job partial index grows unexpectedly
autovacuum runs frequently but cannot keep up
old jobs remain visible as dead tuples
claim queries become slower or noisier
WAL generation rises
replication lag increases
checkpoints become more expensive
```

A partial index such as this is useful:

```sql
CREATE INDEX idx_job_queue_pending
    ON job_queue (created_at)
    WHERE status = 'pending';
```

But it also means every job that enters or leaves `pending` affects that index. If jobs rapidly transition out of `pending`, the partial index itself becomes a churn point.

This is the core problem with the mutable status-table model: every logical state transition becomes physical database churn.

---

## The Real Decision Model

Do not use this decision model:

```text
small queue -> Postgres
large queue -> Kafka or Redis
```

Use this instead:

| Workload shape                                                 |         PostgreSQL fit | Better design direction                                     |
| -------------------------------------------------------------- | ---------------------: | ----------------------------------------------------------- |
| Job creation must commit atomically with business data         |                 Strong | Transactional outbox or append-only job/event table         |
| Jobs are moderate duration and claim frequency is controlled   |                   Good | `SKIP LOCKED` can be acceptable                             |
| Jobs are extremely short and workers constantly claim new work |                   Weak | Batch claiming, sharding, or external broker                |
| Completion requires repeated status updates and deletes        |                   Weak | Append-only completion, partition rotation, or offset model |
| Strict FIFO across all workers is required                     |                   Weak | Reconsider requirement or use a broker/log                  |
| Loose ordering is acceptable                                   |                 Better | Partition by queue, shard, tenant, or aggregate             |
| Multiple independent consumers need the same events            | Poor for mutable queue | Event log, Kafka/Pulsar, or append-only event store         |
| Replay is a first-class requirement                            | Poor for mutable queue | Append-only event store or broker with retention            |
| Queue load can spike unpredictably                             |                  Risky | Backpressure, admission control, broker, or isolation       |
| Queue shares the primary OLTP database                         |                  Risky | Separate database/schema, resource isolation, or broker     |
| Duplicate processing is tolerable                              |                 Easier | Idempotent workers and simpler claim semantics              |
| Duplicate processing is unacceptable                           |                 Harder | Idempotency keys, dedupe table, transactional completion    |

The key distinction is this:

> PostgreSQL is a strong choice when the queue is part of the transactional data model. It is a weaker choice when the queue is the runtime distribution fabric.

---

## Better Postgres-Based Designs

If you want to stay inside PostgreSQL, there are better and worse ways to do it.

### 1. Prefer batching over single-row claiming

Single-row claiming is often the worst possible unit of work.

Bad shape:

```text
claim 1 job
commit
process 1 job
update/delete 1 job
repeat
```

Better shape:

```text
claim a batch
commit
process the batch
record completion in bulk
```

Batching reduces transaction count, index probes, round trips, WAL overhead, and repeated lock acquisition.

A simple batch-claim pattern may look like this:

```sql
WITH claimed AS (
    SELECT id
      FROM job_queue
     WHERE status = 'pending'
     ORDER BY created_at
     LIMIT 100
     FOR UPDATE SKIP LOCKED
)
UPDATE job_queue q
   SET status = 'processing',
       locked_by = $1,
       locked_at = now(),
       attempts = attempts + 1
  FROM claimed
 WHERE q.id = claimed.id
 RETURNING q.*;
```

This still has MVCC cost, but it pays the cost in larger units.

---

### 2. Avoid unnecessary status transitions

This is a common anti-pattern:

```text
pending -> picked -> processing -> retrying -> completed -> archived
```

Every transition is an update. If those states are mainly for observability or audit, an append-only event model may be better:

```text
job.created
job.claimed
job.started
job.completed
job.failed
job.retried
```

That turns mutation into append. PostgreSQL is generally happier with append-heavy workloads than with hot-row mutation and deletion.

---

### 3. Separate durable history from operational work state

Do not force one table to be:

```text
queue
audit log
retry state
dead-letter store
dashboard source
historical archive
```

Those are different concerns.

A cleaner design is often:

```text
job_event          append-only history
job_work_item      current operational claim state
job_dead_letter    failed terminal work
consumer_offset    progress per consumer/group
```

This allows the hot operational table to stay small while preserving history elsewhere.

---

### 4. Partition aggressively

A queue table should not become immortal.

Partitioning gives you a way to drop, detach, archive, or truncate old data instead of relying entirely on row-by-row vacuum cleanup.

Partitioning options include:

```text
by time window
by queue name
by tenant
by shard key
by aggregate hash
by lifecycle state
```

For example:

```text
job_queue_2026_05_09_10
job_queue_2026_05_09_11
job_queue_2026_05_09_12
```

or:

```text
job_queue_shard_00
job_queue_shard_01
job_queue_shard_02
...
```

The aim is to avoid one global hot table and one global hot pending index.

---

### 5. Use `LISTEN/NOTIFY` as wake-up, not durability

`LISTEN/NOTIFY` can reduce polling. That is useful.

But it should not be treated as the durable queue. The durable truth still needs to be in a table.

Good shape:

```text
insert durable job row
commit transaction
notify workers
workers read durable state
```

Bad shape:

```text
notify is the queue
worker assumes notification is durable
lost notification means lost job
```

PostgreSQL also has wait events related to NOTIFY internals, including `NotifyQueue`, `NotifySLRU`, and related SLRU/cache waits, so it should still be treated as a coordination mechanism, not a high-throughput broker replacement. ([AWS Documentation][5])

---

### 6. Consider advisory locks carefully

Advisory locks can avoid some row-level lock and tuple-update patterns because the lock is application-defined rather than directly tied to updating the row.

That can help, but it shifts responsibility into the application.

You must be clear about:

```text
connection ownership
session lifecycle
transaction scope
crash recovery
timeout handling
lock release
idempotency
duplicate execution
visibility of in-flight work
```

Advisory locks are not simpler. They are a different trade-off.

---

## When to Use a Broker or Log

Use a broker or log when the queue is no longer a database-adjacent implementation detail.

PostgreSQL is usually not the best final destination for:

```text
high-frequency dispatch
large fan-out
many independent consumer groups
strict replay semantics
long retention with reprocessing
very low-latency work distribution
massive burst absorption
cross-service event distribution
```

A rough model:

| Requirement                                 | Better fit                         |
| ------------------------------------------- | ---------------------------------- |
| Transactional handoff from relational state | PostgreSQL outbox                  |
| Internal jobs with controlled claim rate    | PostgreSQL queue                   |
| Durable audit/history                       | Append-only PostgreSQL event store |
| Fast competing-consumer dispatch            | Redis Streams, RabbitMQ, SQS, etc. |
| Replayable distributed event stream         | Kafka, Pulsar, Redpanda, etc.      |
| Multiple independent subscribers            | Kafka/Pulsar-style log             |
| Simple local background task                | PostgreSQL table may be enough     |

The choice should follow semantics, not fashion.

Kafka is not automatically better. Redis is not automatically better. PostgreSQL is not automatically good enough. The workload decides.

---

## Production Checklist for PostgreSQL Queues

If you run a PostgreSQL-backed queue, monitor it like a serious database workload, not like a convenient implementation detail.

Track table health:

```sql
SELECT relname,
       n_live_tup,
       n_dead_tup,
       vacuum_count,
       autovacuum_count
FROM pg_stat_user_tables
WHERE relname LIKE '%queue%';
```

Track wait events:

```sql
SELECT wait_event_type,
       wait_event,
       count(*)
FROM pg_stat_activity
WHERE wait_event IS NOT NULL
GROUP BY wait_event_type, wait_event
ORDER BY count(*) DESC;
```

Watch for:

```text
LWLock:MultiXactMemberSLRU
LWLock:MultiXactOffsetSLRU
LWLock:ProcArray
LWLock:WALWrite
Lock:tuple
Lock:transactionid
IO:SLRURead
```

PostgreSQL’s own monitoring documentation includes wait events for MultiXact SLRU access, including `MultiXactMemberSLRU` and `MultiXactOffsetSLRU`. ([PostgreSQL][6])

Also monitor application-level queue health:

```text
oldest pending job age
claim latency
completion latency
queue depth
retry count
dead-letter count
jobs claimed per second
jobs completed per second
jobs failed per second
time in processing state
worker idle time
duplicate execution count
```

And database-level cost:

```text
WAL generation rate
replication lag
autovacuum duration
index size
table size
dead tuple ratio
checkpoint frequency
buffer cache hit ratio
CPU consumed by queue queries
```

The biggest mistake is only monitoring job throughput. By the time throughput drops, the database may already be suffering from bloat, WAL pressure, lock waits, or vacuum lag.

---

## My View

My view is blunt: **`SKIP LOCKED` is a useful coordination primitive, not a queue architecture.**

It solves one narrow problem:

> Do not block this worker if another worker already locked the row.

It does not solve:

```text
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
operational blast radius
```

That does not make `SKIP LOCKED` bad. It means it should be used with discipline.

The worst design is the one that looks deceptively simple:

```text
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

The better design is usually one of these:

```text
transactional outbox + external broker
append-only event table + consumer offsets
partitioned work table + batch claiming
LISTEN/NOTIFY wake-up + durable table
sharded queues + bounded workers
```

The strongest rule is:

> Use PostgreSQL when the queue is part of your transactional data model. Use a broker or log when the queue is your runtime distribution fabric.

That is a much sharper distinction than small versus large.

---

## Specific View for PeeGeeQ-Style Systems

For a PostgreSQL-backed queue/event-store design, the lesson is not “avoid Postgres.” The lesson is to avoid making the core design depend on a hot mutable status table.

A PeeGeeQ-style system should lean into PostgreSQL’s strengths:

```text
durable append
transactional consistency
queryability
auditability
bitemporal history
operational simplicity
```

The strongest direction is:

```text
append-only event store
transactional outbox
consumer offsets
batch reads
partitioned event/work tables
bounded concurrency
backpressure
dead-letter handling
LISTEN/NOTIFY as wake-up only
```

The risky direction is:

```text
high-frequency polling
single-row claim transactions
mutable status-machine rows
large competing-worker pools
DELETE-heavy completion
unbounded retries
one global hot pending index
```

For PeeGeeQ specifically, I would keep the distinction very explicit:

| Concern                           | Preferred model                 |
| --------------------------------- | ------------------------------- |
| Durable event history             | Append-only event store         |
| Transactional message publication | Outbox                          |
| Work dispatch                     | Batch claim or broker bridge    |
| Real-time wake-up                 | LISTEN/NOTIFY                   |
| Replay                            | Event store or external log     |
| Dead letters                      | Dedicated DLQ table             |
| Consumer progress                 | Explicit offsets/checkpoints    |
| High-throughput fan-out           | External broker/log if required |

PostgreSQL should provide the durable transactional core. It should not be forced to impersonate Kafka, RabbitMQ, or Redis Streams unless the workload shape genuinely fits.

---

## Final Position

PostgreSQL as a queue should not be judged by vague labels like “small scale” or “large scale.” That framing is too blunt to be useful.

A PostgreSQL-backed queue is a good fit when:

```text
jobs are tied to relational state
claim frequency is controlled
workers can claim in batches
ordering can be relaxed or partitioned
completion does not require excessive mutation
history is append-only or partitioned
operational impact is isolated
```

It becomes a poor fit when:

```text
many workers repeatedly scan the same pending index
jobs are claimed one at a time at high frequency
the same rows are updated through several lifecycle states
completed work is deleted row by row
retry storms churn the same table
the queue shares the primary OLTP database
multiple consumers need replay or fan-out
```

`SELECT ... FOR UPDATE SKIP LOCKED` is useful, but it is not magic. It avoids waiting on locked rows, but PostgreSQL still has to manage row visibility, row locks, transaction state, WAL, indexes, dead tuples, and vacuum.

The real question is not:

> Can PostgreSQL be used as a queue?

Of course it can.

The real question is:

> Does this queue’s workload shape match PostgreSQL’s strengths, or are we using a relational database as a high-churn broker because it was convenient?

That is the engineering decision.

[1]: https://richyen.com/postgres/2026/05/04/postgres_job_queue.html?utm_source=chatgpt.com "Potential Consequences of Using Postgres as a Job Queue"
[2]: https://www.postgresql.org/docs/current/sql-select.html?utm_source=chatgpt.com "PostgreSQL: Documentation: 18: SELECT"
[3]: https://www.postgresql.org/docs/current/explicit-locking.html?utm_source=chatgpt.com "Documentation: 18: 13.3. Explicit Locking"
[4]: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/apg-waits.lwlockmultixact.html?utm_source=chatgpt.com "LWLock:MultiXact - Amazon Aurora"
[5]: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Reference.Waitevents.html?utm_source=chatgpt.com "Amazon Aurora PostgreSQL wait events"
[6]: https://www.postgresql.org/docs/current/monitoring-stats.html?utm_source=chatgpt.com "Documentation: 18: 27.2. The Cumulative Statistics System"
