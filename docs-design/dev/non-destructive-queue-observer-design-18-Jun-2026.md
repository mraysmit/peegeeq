# Native Non-Destructive Queue Observer — Design (Phase 12.2)

**Status:** ✅ **IMPLEMENTED 2026-06-18** — built to this design for **native**
(`PgNativeQueueObserver`) and, via the poll variant (§13.4), for **outbox** (`OutboxQueueObserver`).
Delivered across commits `7a5b0a66` (SSE handler build-out), `1cf67c00` (outbox observer),
`1021d93e` (native + outbox SSE streaming). The as-built constructor (§6), constants (§7), and the
watermark-drain / FROM_NOW-seed / reconnect structure (§4–§7) match this design — verified against
`PgNativeQueueObserver.java`.
**Scope:** `peegeeq-native`. The component that backs `QueueBrowser.tail(...)` — a live,
**non-destructive** observer of new messages on a native queue topic.
**Delivery surface (as-built):** the REST SSE endpoint
`GET /api/v1/queues/:setupId/:queueName/messages/stream` → `ServerSentEventsHandler.handleQueueMessageStream`
→ `browser.tail(...)` streams observed messages to the management UI **without consuming them**. The
old *consuming* stream `GET …/{queueName}/stream` — which created a consumer and drained the queue (a
data-loss hazard) — and its `SSEConnection` wrapper were **removed** in the same work.
**Companion plan:** `peegeeq-management-ui/docs/tasks/MANAGEMENT_UI_ENHANCEMENTS-14-Jun-2026.md` §7.12.

---

## 1. Purpose

Push new messages on a topic to an observer **as they arrive**, without consuming them. This is
the live counterpart to `QueueBrowser.browse(...)`:

| | Read mechanism | Consumes? | Use |
|---|---|---|---|
| `browse(limit, offset)` | point-in-time `SELECT` | no | history / paging |
| `tail(onMessage)` | LISTEN/NOTIFY + `SELECT` | **no** | live admin view |
| `createConsumer().subscribe()` | `FOR UPDATE` + ack/delete | **yes** | real application consumer |

The observer must never `subscribe`, ack, `FOR UPDATE`, change `status`, or delete. A destructive
observe is "a `SELECT` that also deletes the rows it read" and is forbidden in the admin path.

## 2. Non-negotiable constraints

1. **Non-destructive.** Reads are plain `SELECT … WHERE topic = $1 AND id > $2 ORDER BY id ASC`.
   No `FOR UPDATE`, no `UPDATE status`, no `DELETE`.
2. **Follow the established pattern.** Mirror `PgNativeQueueConsumer` (same module) and
   `ReactiveNotificationHandler` (peegeeq-bitemporal): a dedicated LISTEN connection, the
   notification/close/exception handlers registered **inline on that connection's context**,
   `start()/stop()` lifecycle, `closeHandler`-driven reconnect with bounded backoff, WARN→ERROR
   handler-failure escalation. Do **not** invent a divergent LISTEN implementation, and do **not**
   bounce notifications through a second captured context.
3. **No banned patterns.** No `.recover`/`.otherwise`/`.await`/`CompletableFuture`/`.join`/`.get`/
   `Thread.sleep`/`Handler<AsyncResult>`. Every `Future` is observed (`.onFailure`/chained).
   `vertx.setTimer` for reconnect backoff is permitted (production timer, as in
   `ReactiveNotificationHandler`/`PgNativeQueueConsumer`) — the banned `setTimer` is the *test
   readiness guard*, which this is not.
4. **Separation of concerns.** `PgNativeQueueBrowser` stays a thin point-in-time reader. Connection
   lifecycle, reconnection, and the watermark live in a **separate observer class**; `tail()`
   delegates to it.

## 3. Where it lives (as-built)

```
peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/
    PgNativeQueueObserver.java     ← the observer — package-private `final class` (this design)
    PgNativeMessages.java          ← shared static row→Message mapper (resolves §13.1)
    PgNativeQueueBrowser.java      ← tail() delegates to the observer; browse() unchanged (~148 lines lighter)
    PgNativeQueueFactory.java      ← passes Pool + VertxPoolAdapter into the browser
    NativeQueueChannels.java       ← channelFor(schema, topic) (reused)

peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/   (poll variant — §13.4)
    OutboxQueueObserver.java       ← browse-poll sibling: vertx.setPeriodic → drain (no LISTEN)
    OutboxMessages.java            ← shared static row→Message mapper
    OutboxQueueBrowser.java        ← tail() delegates to OutboxQueueObserver

peegeeq-rest/.../handlers/ServerSentEventsHandler.java   (REST delivery)
    handleQueueMessageStream       ← GET …/messages/stream → browser.tail() → SSE; consuming /stream + SSEConnection removed
```

`PgNativeQueueBrowser.tail()` creates and `start()`s a `PgNativeQueueObserver`, returns its
`Future<Void>`, and stops it in `browser.close()`. The hand-rolled LISTEN previously inlined in
`PgNativeQueueBrowser` was **removed** and replaced by this delegation.

## 4. Core design choice — watermark drain, not read-by-single-id

The NOTIFY payload carries the new message's DB id (`PgNativeQueueProducer` →
`pg_notify(channelFor(schema,topic), newId)`). A naive observer would `readById(payloadId)` and push
that one row. **That is wrong** for two reasons:

- **Out-of-order commits.** `id` is assigned at INSERT but NOTIFY is delivered at COMMIT. A
  transaction that obtained `id=4` can commit *after* one that obtained `id=5`, so the NOTIFY for
  `5` can arrive before `4`. Read-by-id + "skip if id ≤ lastSeen" would then silently drop `4`.
- **Reconnect gaps.** NOTIFYs delivered while the LISTEN connection is down are lost forever.

**Design:** treat the NOTIFY purely as a *wake signal*. Maintain a monotonic **watermark**
`highWaterId` = the highest id pushed so far. On any wake (NOTIFY or reconnect), **drain**:

```sql
SELECT id, payload, headers, created_at, status
FROM <schema>.queue_messages
WHERE topic = $1 AND id > $2          -- $2 = highWaterId
ORDER BY id ASC
LIMIT $3                              -- bounded batch
```

Push each row in ascending id order; advance `highWaterId` to the last id pushed. If the batch was
full (`rows == LIMIT`), immediately drain again (more may be waiting).

This single mechanism:
- never misses (a drain captures everything above the watermark, regardless of NOTIFY order),
- never duplicates (only `id > highWaterId` is read; the watermark advances monotonically),
- handles reconnect gaps identically to steady state (reconnect = one more wake → drain),
- mirrors `PgNativeQueueConsumer`'s "NOTIFY → drain available" shape (minus the consume).

This is the same watermark idea Phase 12.3's outbox poll fallback uses (`id > lastSeenId`), so
native and outbox share semantics.

## 5. Start position (watermark seeding)

`tail()` is **FROM_NOW**: it observes messages that arrive *after* subscription, not history
(history is `browse`). Seed the watermark to the current max id at start:

```sql
SELECT COALESCE(MAX(id), 0) AS hw FROM <schema>.queue_messages WHERE topic = $1
```

Ordering at start matters (avoid a race that misses a just-arrived message):

1. Open dedicated connection, register `notificationHandler` (it no-ops until `active`).
2. `LISTEN "<channel>"` — now every future commit will wake us.
3. Seed `highWaterId = MAX(id)` (snapshot).
4. Mark `active = true`; run one initial drain (covers anything committed between steps 2–3).

A message committed before the snapshot is treated as history (not pushed); one committed after is
drained. No post-subscription message is missed. (REST/UI can request a backlog separately via the
existing `browse(N)` — the observer itself stays FROM_NOW.)

## 6. Public surface

```java
final class PgNativeQueueObserver<T> {
    PgNativeQueueObserver(String topic, Class<T> payloadType, String schema,
                          Pool pool, VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                          MessageHandler<T> onMessage);

    Future<Void> start();   // resolves once LISTEN is established + watermark seeded
    Future<Void> stop();    // UNLISTEN (best-effort) + close dedicated connection; idempotent
    boolean isActive();
}
```

- `pool` — the shared pool, used for the **non-destructive drain `SELECT`s** and the seed query.
- `poolAdapter` — used only for `connectDedicated()` (the LISTEN connection) and `getVertx()`.
- `onMessage` — invoked per observed message; its `Future` provides backpressure (see §8).

### Browser integration

```java
// PgNativeQueueBrowser
@Override
public Future<Void> tail(MessageHandler<T> onMessage) {
    if (closed)        return Future.failedFuture(new IllegalStateException("Browser is closed"));
    if (onMessage == null) return Future.failedFuture(new IllegalArgumentException("onMessage handler is required"));
    if (observer != null)  return Future.failedFuture(new IllegalStateException("tail is already active on this browser"));
    this.observer = new PgNativeQueueObserver<>(topic, payloadType, schema, pool, poolAdapter, objectMapper, onMessage);
    return observer.start();
}

@Override
public void close() {
    closed = true;
    PgNativeQueueObserver<T> o = this.observer;
    this.observer = null;
    if (o != null) {
        o.stop().onFailure(err -> logger.warn("tail: observer stop failed for topic {}: {}", topic, err.getMessage()));
    }
}
```

Row→`Message` mapping is shared with `browse` (extract the existing `mapRow(Row)` into a shared
static helper, e.g. `PgNativeMessages.map(row, objectMapper, payloadType)`, so the two read paths
cannot drift).

## 7. Internal state & lifecycle

```java
private volatile PgConnection listenConn; // dedicated LISTEN connection
private volatile boolean active;          // true between successful start and stop
private volatile boolean shutdown;        // set by stop(); suppresses reconnect
private volatile long highWaterId;        // highest id pushed; volatile — also read across reconnect
private boolean draining;                 // single-flight drain guard (connection-context-confined)
private boolean rescanPending;            // a wake arrived during a drain (connection-context-confined)
private int reconnectAttempts;            // bounded backoff counter (connection-context-confined)
private final AtomicLong handlerFailures; // WARN→ERROR escalation
private static final int  MAX_RECONNECT_ATTEMPTS = 5;
private static final long BASE_RECONNECT_DELAY_MS = 1000;     // exp backoff, cap ~32s
private static final int  DRAIN_BATCH_LIMIT = 200;
private static final long HANDLER_FAILURE_ESCALATION_THRESHOLD = 3;
```

**Context &amp; serialization.** There is no thread model to reason about here — in Vert.x you reason
about the *context*. All observer state (the watermark and the `draining`/`rescanPending`/reconnect
flags) is touched only on the LISTEN connection's context, and a context runs its handlers one at a
time, in order — never two concurrently — so that state needs no locks or `synchronized`. The
notification, close and exception handlers are registered **inline on the connection's context**
(where the `LISTEN`/seed Futures complete), exactly as `PgNativeQueueConsumer` does; the drain's
pool-query callbacks complete back on that same context. There is no second context and no
`runOnContext` hop. `listenConn`, `active`, `shutdown` and `highWaterId` are `volatile` only because
they are also read across the reconnect boundary (a reconnect runs on a fresh connection) and from
the public `stop()`/`isActive()`; the plain flags are not, because they never leave the connection's
context.

### start()

```
start():
  if active        -> Future.succeededFuture()
  if vertx == null -> Future.failedFuture(IllegalStateException)   // vertx = poolAdapter.getVertx()
  shutdown = false
  return establish(seedWatermark = true)

establish(seedWatermark):
  poolAdapter.connectDedicated().compose(conn ->
      conn.query("LISTEN \"" + channel + "\"").execute()            // quoted identifier
        .compose(rs -> seedWatermark ? readMaxId()                  // SELECT MAX(id) — FROM_NOW
                                     : succeededFuture(highWaterId)) // reconnect keeps the watermark
        .map(hw -> {                                                 // runs on the connection's context
            listenConn = conn; highWaterId = hw; reconnectAttempts = 0; active = true
            conn.notificationHandler(n -> { if (channel.equals(n.getChannel())) drain() })
            conn.exceptionHandler(e -> logger.error("tail: LISTEN conn error on {}: {}", channel, e.getMessage()))
            conn.closeHandler(v -> onConnectionClosed())
            drain()                                                 // initial (start) / catch-up (reconnect)
            return null
        })
        .onFailure(err -> conn.close()                              // LISTEN/seed failed -> close, no leak;
            .onFailure(ce -> logger.warn("tail: close after start failure: {}", ce.getMessage()))))
                                                                    // the failed Future still propagates
```

The returned `Future<Void>` resolves once LISTEN + watermark are in place. A failure to connect /
LISTEN / seed propagates to the caller (e.g. `tail()` → SSE handler), so it surfaces — never a
silent hang.

### The NOTIFY callback

The `notificationHandler` (registered inline above) calls `drain()` directly on the connection's
context — no `runOnContext` hop, mirroring `PgNativeQueueConsumer`. The NOTIFY payload id is
intentionally **not** trusted as the thing to read (see §4); it only means "something changed, go
drain".

### drain() — single-flight watermark drain

```
drain():                                  // runs on the connection's context
  if !active || shutdown return
  if draining { rescanPending = true; return }    // coalesce concurrent wakes
  draining = true; from = highWaterId
  pool.preparedQuery(DRAIN_SQL).execute(Tuple.of(topic, from, DRAIN_BATCH_LIMIT))
    .compose(rows -> pushAll(rows))               // sequential; advances highWaterId; returns count pushed
    .onSuccess(pushed -> finishDrain(pushed == DRAIN_BATCH_LIMIT))   // full batch -> more waiting
    .onFailure(err -> { logger.error("tail: drain failed for {} (retry on next wake): {}", topic, err); finishDrain(false) })

finishDrain(more):                        // clear the single-flight guard on either outcome
  draining = false
  if (more || rescanPending) { rescanPending = false; drain() }
```

`pushAll` folds the rows into a sequential `Future` chain so a slow `onMessage` (e.g. a
back-pressured SSE write) is awaited before the next push, advancing `highWaterId` as it goes
(including past unparseable rows, so the drain never re-reads them):

```
pushAll(rows):
  Future<Void> chain = succeededFuture; count = 0
  for row in rows (ascending id):
      id = row.getLong("id")
      Message<T> m = PgNativeMessages.map(row, objectMapper, payloadType)
      if (m == null) { advanceWatermark(id); continue }    // skip but make progress past it
      count++
      chain = chain.compose(v -> onMessage.handle(m).transform(ar -> {
                 advanceWatermark(id)                        // advance even on handler failure
                 if (ar.failed()) logHandlerFailure(id, ar.cause()) else handlerFailures.set(0)
                 return succeededFuture() }))                // a handler failure must not abort the drain
  return chain.map(count)
```

> **Watermark-on-failure note.** We advance `highWaterId` even when the handler fails, so one
> bad/slow consumer cannot wedge the drain on the same row forever (the observer is a best-effort
> *viewer*, not a delivery guarantee — that is what real consumers are for). The failure is logged
> and escalated; it is never swallowed.

### onConnectionClosed() — reconnect

On unexpected close (the `closeHandler` fires on the connection's context), schedule a reconnect
with exponential backoff, capped at `MAX_RECONNECT_ATTEMPTS`. Reconnect calls `establish(false)` —
re-LISTEN on a fresh connection — and **retains** the existing `highWaterId`, so the post-reconnect
drain catches up the gap automatically.

```
onConnectionClosed():
  listenConn = null; active = false
  scheduleReconnect()

scheduleReconnect():
  if shutdown || active return                     // expected during stop(), or already back up
  if reconnectAttempts >= MAX_RECONNECT_ATTEMPTS {
     logger.error("tail: exhausted {} reconnects for {}; live tail stopped", MAX_RECONNECT_ATTEMPTS, channel); return }
  delay = BASE_RECONNECT_DELAY_MS << min(reconnectAttempts, 5); attempt = ++reconnectAttempts
  vertx.setTimer(delay, id -> { if (!shutdown && !active)
     establish(false)                              // re-LISTEN; KEEP highWaterId; drain catches the gap
       .onSuccess(v -> logger.info("tail: reconnected on {} (attempt {})", channel, attempt))
       .onFailure(e -> { logger.error("tail: reconnect {} failed for {}: {}", attempt, channel, e.getMessage())
                         scheduleReconnect() }) })  // retry the next attempt; establish() resets the counter on success
```

`establish(false)` is `establish(true)` without re-seeding the watermark — it keeps the high-water
mark from before the drop. Unlike the initial start, the first drain after reconnect intentionally
finds the gap messages and pushes them. A successful `establish` sets `reconnectAttempts = 0`.

### stop()

```
stop():                                  // idempotent
  shutdown = true; active = false
  conn = listenConn; listenConn = null
  if conn == null return succeededFuture
  conn.query("UNLISTEN \"" + channel + "\"").execute()      // best-effort
    .eventually(() -> conn.close())                         // close regardless of UNLISTEN outcome
    .onFailure(e -> logger.warn("observer: stop cleanup for {}: {}", topic, e.getMessage()))
    .mapEmpty()
```

Closing the connection drops the LISTEN with it; UNLISTEN is a courtesy. `.eventually(...)` (not
`.recover`) is used so close always runs without erasing the UNLISTEN outcome.

## 8. Error handling & escalation

- **Connection/LISTEN/seed failure at start:** propagates out of `start()` → caller surfaces it
  (SSE 5xx / failed `tail()` Future). Not swallowed.
- **Drain failure:** logged at ERROR; the watermark is unchanged for the failed batch, so the next
  wake retries it. Not fatal to the observer.
- **Handler failure:** WARN, escalating to ERROR after `HANDLER_FAILURE_ESCALATION_THRESHOLD`
  consecutive failures (same shape as `ReactiveNotificationHandler.logHandlerFailure`). Watermark
  still advances (§7 note).
- **Unexpected connection close:** bounded-backoff reconnect; on exhaustion, ERROR + observer goes
  inactive (the SSE layer can then surface "stream ended" to the client).

## 9. Channel naming

LISTEN on `NativeQueueChannels.channelFor(schema, topic)` — the **same** function the producer uses
for `pg_notify`, so producer and observer always agree (including the 63-byte MD5 truncation).
Quote the identifier in `LISTEN`/`UNLISTEN` (`"<channel>"`) exactly as `PgNativeQueueConsumer` does.

## 10. Edge cases

| Case | Handling |
|---|---|
| Out-of-order NOTIFY (id 5 before 4) | Drain reads all `id > highWater` ascending → 4 then 5. No miss. |
| NOTIFY lost during disconnect | Reconnect drain catches `id > highWater`. No miss. |
| Burst of inserts | One drain per wake, coalesced via `draining`/`rescanPending`; `LIMIT` batches + auto-rescan. |
| Slow SSE client | Sequential `pushSequentially` awaits each `onMessage` Future before the next. |
| Empty topic at start | `MAX(id)` → 0; first real insert has id ≥ 1 > 0 → pushed. |
| `tail()` twice on one browser | Second call fails fast (`observer != null`). |
| `tail()` on closed browser / null handler | Fails fast with `IllegalStateException` / `IllegalArgumentException`. |
| `stop()` during in-flight drain | `active=false` short-circuits the next drain; in-flight drain completes against a closing pool → its failure is logged, not fatal. |

## 11. Tests (Phase 12.2) — JUnit `@Tag(INTEGRATION)`, TestContainers, **no mocking**

1. **Observe-not-consume (primary).** `tail` → publish → assert pushed; then `browse()` asserts the
   message is **still present**, *and* a real `createConsumer().subscribe()` still receives it.
   Proves the observe did not consume.
2. **No-miss / out-of-order & catch-up.** Seed watermark; publish several; assert all observed in
   ascending id order with none missed. (Reconnect variant below also exercises catch-up.)
3. **Reconnect.** Terminate the observer's LISTEN backend (`pg_terminate_backend(pid)` via the pool),
   publish during the gap, assert the gap message is observed after reconnect (watermark catch-up).
4. **Fail-fast guards.** closed browser / null handler / double-tail each return a failed `Future`
   (no silent 30 s `VertxTestContext` timeout) — surfacing the deviation class from §7.12.
5. **Backpressure (optional).** A deliberately slow handler does not drop or reorder messages.

Each test mirrors the setup of `PgNativeQueueFactoryIntegrationTest`
(`PeeGeeQTestSchemaInitializer` + `PeeGeeQTestConfig` + real `PgNativeQueueFactory`) and drives
everything through the injected `VertxTestContext` (`onSuccess`/`onFailure`, `ctx.verify`), per the
testing-standards doc.

**As-built (2026-06-18) — `PgNativeQueueBrowserTailIntegrationTest`:** tests 1 (observe-not-consume),
3 (reconnect/catch-up via `pg_terminate_backend`), and the 3 fail-fast guards (4) are implemented and
green. ✅ **Gap closed (2026-06-18):** test 1 now adds the `createConsumer().subscribe()` leg — after
the tail observes and `browse()` still sees the message, a real consumer still receives it (the decisive
observe-≠-consume proof), green for native and outbox. The standalone no-miss/out-of-order case (2) is
not a separate test (catch-up is covered by the reconnect test); backpressure (5, optional) is not
implemented.

## 12. Banned-pattern compliance checklist — ✅ verified as-built (2026-06-18)

- [x] No `.recover(` / `.otherwise(` — `stop()` cleanup uses `.transform(...)` then `conn.close()`.
- [x] No `.await(` / `CompletableFuture` / `.join()` / `.get()` — pure `Future` composition.
- [x] No `Thread.sleep` / `LockSupport.parkNanos` — backoff via `vertx.setTimer` (production timer).
- [x] No `Handler<AsyncResult>` / `.onComplete(ar -> …succeeded)` for branching — the drain clears
      its single-flight guard via `onSuccess`/`onFailure` → `finishDrain(...)`, not `onComplete`.
- [x] Every `Future` observed (`.onFailure(...)` or chained); no fire-and-forget.
- [x] All reads non-destructive: `SELECT … WHERE id > $2` only; no `FOR UPDATE`/status/delete.

Verified by a full read of `PgNativeQueueObserver.java`; `OutboxQueueObserver.java` is clean too (a
grep for every banned token returns nothing).

## 13. Open decisions — all resolved (as-built 2026-06-18)

1. **Row-mapper sharing.** ✅ Resolved — extracted to a shared static `PgNativeMessages.map(...)`
   (and `OutboxMessages.map(...)` for outbox), so `browse` and the observer read paths cannot drift.
2. **`DRAIN_BATCH_LIMIT` value.** ✅ Shipped at **200** (both native and outbox observers); revisit
   only if SSE write throughput warrants tuning.
3. **Backlog policy.** ✅ Resolved — the observer stays strictly FROM_NOW; REST/UI fetch initial
   history via `browse(N)`. No combined "history + live" call at the API layer.
4. **Reuse for outbox (12.3).** ✅ Resolved 2026-06-18: the outbox emits **no** insert NOTIFY (it
   is a poll-based queue — `OutboxConsumer` uses `vertx.setPeriodic`, not LISTEN), so it uses the
   watermark **poll** variant: a sibling `OutboxQueueObserver` that shares this drain/watermark
   design but is driven by a periodic timer instead of a NOTIFY. Not generalised into one class —
   the LISTEN-vs-poll wake mechanism differs enough that two focused observers are clearer.
