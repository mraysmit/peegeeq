# peegeeq-utilities-ui — Telemetry Requirements

What instrumentation, metering, and telemetry PeeGeeQ must provide for the utilities-ui tools —
the Message Generator (§6.1, §7) and the Generation Tool Suite (§19) in
[PEEGEEQ_QUEUE_MESSAGE_GENERATOR_DESIGN.md](PEEGEEQ_QUEUE_MESSAGE_GENERATOR_DESIGN.md).

> **Verified vs. to-confirm.** The "available today" facts in §3 were probed against a running
> `peegeeq-rest` backend on 2026-07-05. Items marked *(to confirm)* are inferred from endpoint
> names or field labels and must be verified by running before being relied on — do not assert
> them. Nothing here is asserted from static reading alone.

---

## 1. The metering split (principle)

The generator is **client-driven**: the utilities-ui sets the rate and issues the publishes
(§7, "client drives rate in v1"). That fixes a clean division of responsibility:

- **The utilities-ui meters the *produce / accept* side itself** — everything it can observe from
  issuing a publish and timing the response. No PeeGeeQ change is needed for this.
- **PeeGeeQ must meter the *drain / deliver* side and the *why*** — queue depth, delivery latency,
  consumer drain, and resource saturation. The client is blind to everything past the HTTP publish
  response.

Every requirement below is classified as **[client]** (utilities-ui measures it), **[have]**
(PeeGeeQ exposes it today), or **[gap]** (PeeGeeQ must add it).

---

## 2. Client-side metering — no backend change [client]

The utilities-ui derives all of the following from issuing publishes and timing responses. These
power Zone E (§6.1) and the accept-side of every §19 tool:

| Metric | Derived from |
|---|---|
| Offered rate | The configured `RunConfig.rate` |
| Sent (requests issued) | Count of publish/batch calls made |
| Accepted | `messagesSent` (or `count`) in the batch response |
| Errors + HTTP status | Non-2xx responses and thrown errors, with status code |
| Error rate / consecutive errors | Derived from the above (drives auto-stop, §7.1) |
| Achieved rate | Accepted ÷ elapsed, rolling window |
| **Accept latency** p50/p95/p99 | Timing each publish call (request → response) client-side |

**Important limit:** accept latency is *enqueue* latency (how long the REST call took), **not**
end-to-end produce→consume latency. The client cannot see delivery latency — that requires backend
telemetry (see §4).

---

## 3. Backend telemetry available today [have] (verified 2026-07-05)

PeeGeeQ already exposes more than the coarse overview poll suggests.

### `GET /api/v1/queues/{setupId}/{queueName}/stats`
```json
{
  "queueName": "events", "setupId": "demo-setup", "implementationType": "outbox",
  "healthy": true,
  "totalMessages": 0, "pendingMessages": 0, "inFlightMessages": 0,
  "processedMessages": 0, "deadLetteredMessages": 0,
  "messagesPerSecond": 0.0, "avgProcessingTimeMs": 0.0,
  "successRatePercent": 100.0, "timestamp": 1783258014287
}
```
This gives **depth/backlog** (`pendingMessages`, `inFlightMessages`), **throughput**
(`messagesPerSecond`), **average latency** (`avgProcessingTimeMs`), **error rate**
(`successRatePercent`), and **DLQ count** (`deadLetteredMessages`) per queue.

### `GET /api/v1/queues/{setupId}/{queueName}`
Adds `consumerRate` (drain rate) alongside `messageRate` (produce rate), plus `config`
(`pollingIntervalSeconds`, `visibilityTimeoutSeconds`, …) and a `statistics` sub-object.

### `GET /api/v1/management/metrics`
```json
{ "threadsActive": 27, "cpuCores": 22, "memoryTotal": …, "memoryMax": …,
  "memoryUsed": …, "uptime": 5466436, "timestamp": … }
```
**JVM process metrics only** — no connection-pool, DB, or event-loop telemetry.

### `GET /api/v1/management/overview`
Per-setup/per-queue rollups plus `systemStats` including `activeConnections`.

### `GET /api/v1/sse/metrics` — streaming system telemetry (verified)
Standard SSE. A `connected` handshake, then `metrics` frames with an incrementing `id:` every
**~5 s**:
```
event: connected
data: {"connectionId":"monitoring-1","timestamp":…}

event: metrics
id: 1
data: {"type":"system_stats","timestamp":…,"uptime":"1h 39m 9s","messagesPerSecond":0.0,
       "memoryUsed":…,"cpuCores":22,"threadsActive":28,"monitoringSessions":1,
       "dbPool":{"active":1,"idle":5,"pending":0,"total":6,
                 "perSetup":[{"setupId":"demo-setup","active":1,"idle":5,"pending":0,"total":6}]},
       "totalMessages":0,"totalQueues":1,"totalSetups":1,
       "subscriptionHealth":{"active":0,"paused":0,"dead":0,"cancelled":0,"total":0},
       "activeBackfills":[]}
```
Notably this frame **already carries `dbPool` utilisation (active/idle/pending/total, per-setup)** —
i.e. connection-pool saturation is streamed, even though the REST `/management/metrics` omits it.

### Other SSE endpoints *(to confirm)*
`sse/queues`, `sse/queues/{setup}/{queue}`, and `queues/{setup}/{queue}/stream` exist (per
management-ui's `endpoints.ts`). `sse/queues` emitted **no frames** in a 6 s probe window — likely
change-driven rather than periodic; its trigger, cadence, and payload must be verified before use.
A WebSocket transport also exists (`/ws`, proxied by the dev server); its telemetry payload is
unverified here.

---

## 4. Telemetry gaps PeeGeeQ must close [gap]

| # | Gap | Why it's needed | Suggested shape |
|---|---|---|---|
| G1 | **Latency percentiles (p50/p95/p99)** | Only `avgProcessingTimeMs` exists; an average hides the tail that breaking-point and comparison testing are all about | A histogram per queue; expose percentiles on `/stats` |
| G2 | **End-to-end delivery latency (enqueue → available/delivered), tagged by implementation type** | This *is* the native-vs-outbox difference (LISTEN/NOTIFY vs polling delay). `avgProcessingTimeMs` appears to be consumer *handler* time, not delivery latency *(to confirm)* | A distinct `deliveryLatencyMs` (p50/p95/p99) measured enqueue→first-available |
| G3 | **Resource-saturation metrics** — *partly closed*: `dbPool` (active/idle/pending/total, per-setup) is already in the `/sse/metrics` frame; the REST `/management/metrics` is JVM-only | Breaking-point testing needs to attribute *why* it saturated | Still missing: DB write latency, event-loop lag, NOTIFY backlog, pool acquire-wait time |
| G4 | **Higher-frequency streaming** — the SSE transport exists but emits at **~5 s**; too coarse for a 5 s spike or 10 s ramp step | Ramp (§19.1) and profile (§19.3) need sub-second resolution | Raise `/sse/metrics` cadence (or add a fast per-run/per-queue stream) to ≥ 1 Hz |
| G5 | **Per-run / correlation scoping** | Per-queue aggregates can't separate two concurrent runs on one queue | Run-tagged counters, or mandate a dedicated queue per run (tool-side workaround) |
| G6 | **Per-message enqueue timestamp + echoed client send-time header** | Needed to auto-verify delay honoured and FIFO ordering, and to compute true latency by joining on `correlationId` | Return enqueue timestamp; echo a client `x-send-ts` header on consume |
| G7 | **Database-level queue-table metrics** (Postgres `pg_stat_*`) — none exposed today | For a Postgres-backed queue the real bottleneck is INSERT/DELETE churn → dead-tuple bloat → autovacuum lag → scan degradation, all invisible to app-level counters | A backend telemetry endpoint/stream running `pg_stat_user_tables` etc. against the setup DB — see §4A |

---

## 4A. Database-level telemetry (PostgreSQL — the queue's real bottleneck)

PeeGeeQ is **entirely Postgres-backed**, so for a high-throughput queue the limiting factor is
usually not application CPU but the database: enqueue is an `INSERT`, dequeue/ack is a `DELETE` (or
an `UPDATE` to a processed state), and that churn produces **dead tuples → table/index bloat →
autovacuum load → scan degradation**. A long-running transaction that holds back the `xmin` horizon
stops autovacuum from reclaiming those dead tuples at all. None of this is visible in the app-level
counters of §2–§3 — it must be **probed from the database's own statistics views**.

**Who probes it.** The browser cannot reach Postgres. PeeGeeQ (backend) must expose these metrics
via a telemetry endpoint/stream that runs the `pg_stat_*` queries against the *setup's* database
using its existing pool (gap **G7**). The utilities-ui only consumes them.

### Verified schema (probed 2026-07-05, setup DB `demo_setup_db`)
The churn-bearing tables to watch, per setup:
`queue_messages` (native), `outbox` (outbox), `dead_letter_queue`, the per-queue tables (named after
the queue, e.g. `orders`, `events`), and `processed_ledger`. `pg_stat_user_tables` exposes the full
churn/vacuum column set (`n_dead_tup`, `n_live_tup`, `n_tup_ins/del/upd/hot_upd`, `seq_scan`,
`idx_scan`, `vacuum_count`, `autovacuum_count`, `last_autovacuum`, …). Even **idle**, `outbox`,
`queue_messages`, and `dead_letter_queue` showed **1,400–1,500 `seq_scan`s** from the pollers — so
scan rate is itself a signal. `pgstattuple` is **not installed** by default.

### Per-queue-table metrics (from `pg_stat_user_tables` / `pg_statio_user_tables` / `pg_class`)

| Metric | Source | Tells you |
|---|---|---|
| `n_tup_ins` vs `n_tup_del` (+ `n_tup_upd`, `n_tup_hot_upd`) | `pg_stat_user_tables` | Enqueue-vs-dequeue balance; HOT-update ratio (the insert-vs-delete churn) |
| `n_dead_tup`, `n_live_tup`, dead/live ratio | same | Bloat proxy — dead tuples piling up faster than vacuum clears them |
| `last_autovacuum`, `autovacuum_count`, `last_autoanalyze` | same | Whether autovacuum is keeping up with the churn |
| `seq_scan` vs `idx_scan` (rates) | same | Poller scan load; plan degradation as the table bloats |
| heap / total / index size (growth) | `pg_relation_size`, `pg_total_relation_size` | Bloat = size grows while `n_live_tup` stays flat; index bloat |
| `heap_blks_hit` / `heap_blks_read` | `pg_statio_user_tables` | Queue table falling out of `shared_buffers` |
| exact dead-tuple % / free % | `pgstattuple` *(extension not installed — enable, or use the `n_dead_tup` estimate)* | Precise bloat, on demand (expensive — snapshot only) |

### Cluster / database-wide metrics

| Metric | Source | Tells you |
|---|---|---|
| Oldest / longest-running transaction, `backend_xmin` horizon | `pg_stat_activity` | The classic vacuum blocker — an old snapshot prevents dead-tuple cleanup |
| Locks / blocked backends | `pg_locks` | `SELECT … FOR UPDATE SKIP LOCKED` contention under high concurrency |
| WAL generation rate | `pg_stat_wal` / `pg_current_wal_lsn` delta | Churn → WAL volume → checkpoint pressure |
| Checkpoints (timed vs requested, buffers) | `pg_stat_bgwriter` | Checkpoint storms under write load |
| Xid age (`age(datfrozenxid)`, `age(relfrozenxid)`) | `pg_database` / `pg_class` | Transaction-ID wraparound / anti-wraparound autovacuum risk at very high throughput |
| `xact_commit`/`xact_rollback`, `deadlocks`, `tup_returned`/`fetched` | `pg_stat_database` | Rollback and deadlock rates; read amplification |
| `numbackends` | `pg_stat_database` | DB-side backend count (complements the app-side `dbPool` from `/sse/metrics`) |

### Ties to the tools
- **Breaking-point (§19.1):** turns "it saturated at N msg/s" into *why* — e.g. "dead tuples on
  `queue_messages` outran autovacuum," "`xmin` pinned by a long transaction," or "SKIP LOCKED lock
  waits climbed." For a PG queue this is the primary saturation signal.
- **Native-vs-Outbox (§19.2):** compares the two implementations' **DB churn profiles**, not just
  latency — native (`queue_messages`: INSERT + DELETE + LISTEN/NOTIFY) vs outbox (`outbox`: INSERT +
  UPDATE/DELETE + heavy polling `seq_scan`s). Dead-tuple accumulation, autovacuum load, WAL, and
  seq-scan rate side by side is a comparison only the DB layer can give.
- **Profile (§19.3):** watch bloat/dead-tuples accumulate across phases and whether autovacuum
  recovers during the idle phase.

### Implementation notes
- **Cost/cadence:** these queries are heavier than a counter read. Sample at the `/sse/metrics`
  cadence (~5 s), **not** 1 Hz. `pgstattuple` full scans are expensive — on-demand snapshot only,
  never in the periodic stream.
- **Deltas:** `pg_stat_*` counters are cumulative since last reset. The tool baselines at run start
  (or `pg_stat_reset`) and reports deltas over the run window.
- **Permissions:** the setup's DB role must be able to read the stats views; cross-backend fields in
  `pg_stat_activity` need sufficient privilege. Note this as a backend concern.
- **Scoping:** query the setup's own database; the queue table names are known (per §"Verified
  schema").

---

## 5. Streaming telemetry

The live tools cannot poll. Zone E refreshes every ~500 ms; ramp (§19.1) steps every ~10 s;
profile (§19.3) has phases as short as 5 s. A pull model — worst of all the 30 s overview poll —
either misses short phases entirely or hammers the backend. Telemetry must be **pushed**.

### 5.1 What exists (verified)
`/sse/metrics` is a working SSE stream (§3): `connected` handshake, `metrics` frames with an
incrementing `id:`, cadence ~5 s, carrying system stats **including `dbPool` utilisation**. This is
the right transport and format — it is the cadence and scoping that fall short for the fast tools.

### 5.2 Streaming requirements for the tools

- **Cadence ≥ 1 Hz** for ramp and profile. The existing ~5 s `/sse/metrics` interval is too coarse
  (gap G4). Either raise its cadence or add a dedicated fast stream for an active run.
- **Per-queue / per-run scoping.** `/sse/metrics` is system-wide. A run needs a stream filtered to
  *its* queue (and ideally its run, gap G5): a subscribe like `/sse/queues/{setup}/{queue}` (exists;
  behaviour to confirm) emitting depth, produce/consume rate, delivery-latency percentiles, and
  error/DLQ deltas per tick.
- **Frame shape.** Each frame is a timestamped metric snapshot (or delta) so the client can plot a
  timeline directly — the `metrics` frame shape is already the right idea; it needs the
  drain/latency fields (G1, G2) added and per-queue scoping.
- **Reconnect / resume.** SSE already sends `id:` on each frame, so the client should reconnect with
  `Last-Event-ID` and the server should resume from it (no gap in the timeline across a blip).
  Confirm the server honours `Last-Event-ID`.
- **Stream backpressure.** A high-rate run must not drown the browser tab. The client throttles
  render to ~2–4 Hz (coalescing frames); the stream itself should stay bounded (fixed cadence, not
  per-message) — `/sse/metrics`'s fixed interval is correct; a per-*message* stream
  (`queues/{setup}/{queue}/stream`) is **not** appropriate for load telemetry.
- **Two transports.** SSE (`/sse/*`) is the primary, and the natural fit (one-way, auto-reconnect).
  A WebSocket (`/ws`) also exists; SSE is preferred unless bidirectional control is needed.

### 5.3 Client-side streaming (already covered)
The utilities-ui streams its *own* accept-side metrics into Zone E at ~500 ms with **no backend
involvement** — it is timing its own publishes (§2). Backend streaming (§5.1) is additive: it
supplies the drain-side (depth, consume rate, delivery latency, pool) that the client cannot see.

## 5A. Other transport & data requirements

- **Scoping:** metrics addressable per queue (present) and ideally per run (gap G5).
- **Clock/correlation:** to compute end-to-end latency, client send-time and server/consume-time
  must be joinable — via an echoed timestamp header or a `correlationId` join (gap G6).
- **Percentiles over averages:** histograms, not means (gap G1).

---

## 6. Per-tool telemetry matrix

| Tool | Needs from telemetry | Buildable now? |
|---|---|---|
| Message Generator — Zone E (§6.1) | [client] accept counts/rate/errors/latency | ✅ no backend change |
| Ramp / breaking-point (§19.1) | [client] achieved rate + accept latency to find the knee; [have] `pendingMessages` to see backlog; [gap] G3, G4 for *why* + resolution | ⚠️ basic now; rich needs G3/G4 |
| Native-vs-Outbox compare (§19.2) | [gap] G2 (delivery latency per type), G1 (percentiles), G6 (join) | ❌ needs backend — the one tool that genuinely can't be built well client-side |
| Traffic profile (§19.3) | [client] achieved-rate timeline; [gap] G4 for fine resolution | ✅ chart now; finer with G4 |
| Saved scenarios (§19.4) | none (config persistence only) | ✅ no telemetry |
| Delay/Priority/FIFO (§19.5) | sending is [client]; auto-verify needs [gap] G6 (else defer to management-ui browser) | ✅ send now; auto-verify needs G6 |
| Trace seed (§19.6) | none (emits ids; verify in management-ui) | ✅ no telemetry |

---

## 7. Prioritisation

- **No backend prerequisite:** Zone E, Ramp (basic knee), Profile, Saved scenarios, Delay/Prio/FIFO
  (send), Trace seed. These lean on client-side metering + the existing `/stats` depth fields.
- **Backend prerequisites (new work in `peegeeq-db` / `peegeeq-rest`, following the reactive /
  no-banned-patterns rules, like the Phase 1B backend gap):**
  - **G2 + G1 + G6 + G7** — required for Native-vs-Outbox comparison (§19.2): delivery latency,
    percentiles, correlation join, **and the per-implementation DB churn profile (§4A)**.
  - **G3 + G4 + G7** — required for rich breaking-point attribution (§19.1): resource saturation,
    ≥1 Hz streaming, **and the database-level bottleneck signals (dead tuples / autovacuum / locks /
    `xmin`, §4A)** that are usually the real limit for a Postgres-backed queue.

These map onto IMPLEMENTATION_PLAN Phase G: G.2 (compare) and the "rich" half of G.1 (ramp) carry a
backend dependency; the rest do not.

---

## 8. Boundary note

This telemetry is for **driving and measuring generation runs** — it does not turn utilities-ui into
a monitoring console. Ongoing observation, dashboards, DLQ operations, and event/trace exploration
remain `peegeeq-management-ui`'s job (utilities-ui *writes load in*; management-ui *reads and
operates* what is there). Where a run's downstream effect must be inspected, that inspection happens
in management-ui.
