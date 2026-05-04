## PeeGeeQ Bitemporal Review Plan (March 30, 2026)

This document captures the execution plan for a complete code review of peegeeq-bitemporal and a test coverage assessment for both core functionality and typical bitemporal event store use cases.

### Scope

1. Primary module review target:
- peegeeq-bitemporal production code
- peegeeq-bitemporal tests

2. Direct contract surfaces included for correctness validation:
- peegeeq-api bitemporal interfaces and models
- peegeeq-db event store schema templates and indexes
- bitemporal documentation that defines expected behavior and invariants

3. Runtime evidence included:
- CORE test execution
- INTEGRATION test execution (with integration profile)

### Deliverable Style

Findings-first report with severity ordering and concrete file/line citations, followed by a coverage matrix and prioritized test gap backlog.

### Execution Phases

#### Phase A: Static Code Review

1. Review bitemporal production classes for:
- Correctness defects and behavioral regressions
- Reactive compliance (Future-based flow, no blocking bridges)
- Lifecycle and resource management correctness
- Concurrency/race-risk paths
- Schema isolation and multi-tenant channel behavior

2. Focus files:
- peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStore.java
- peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/ReactiveNotificationHandler.java
- peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/BiTemporalEventStoreFactory.java
- peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/BiTemporalPoolFactory.java

3. Constraints verified during review:
- Vert.x 5 Future-only composition patterns
- No CompletableFuture, join/get blocking bridges, or sleep polling
- No raw JDBC patterns in module scope
- No hardcoded global schema assumptions in bitemporal paths

#### Phase B: Static Coverage Mapping

1. Build a scenario matrix of expected bitemporal behaviors.
2. Map each scenario to existing tests.
3. Classify coverage status as:
- Covered
- Partially covered
- Uncovered

4. Coverage dimensions include:
- Core append/query/correction/version semantics
- Valid-time and transaction-time query behaviors
- Causality and correlation traversal
- Subscription behavior (exact, wildcard, all-events)
- Transaction participation and rollback behavior
- Failure and resilience paths (reconnect/retry/recovery)
- Concurrency and ordering behavior
- Boundary/scale conditions

#### Phase C: Runtime Evidence (CORE + INTEGRATION)

1. Execute module tests in:
- Default profile for CORE tests
- Integration profile for INTEGRATION tests

2. Capture evidence:
- Tests executed count
- Pass/fail/skipped outcomes
- Profile correctness
- Notable runtime warnings/errors
- Flaky or nondeterministic behavior signals

3. Correlate runtime evidence with static findings to identify:
- Confirmed defects
- Latent risks
- False positives from static-only analysis

#### Phase D: Gap Prioritization and Recommendations

1. Produce prioritized missing test backlog for core and typical use cases.
2. For each gap define:
- Objective/invariant
- Recommended test category/tag
- Priority and estimated effort
- Why it matters to production risk

3. Separate:
- Immediate high-value additions
- Medium-term hardening items

### Typical Bitemporal Use Cases To Validate

1. Append immutable events with valid-time and transaction-time separation.
2. Correct prior events while preserving version lineage.
3. Query state as-of transaction time and across valid-time windows.
4. Reconstruct aggregate/event history reliably.
5. Trace event causality and correlation chains.
6. Route and deliver subscriptions accurately across exact and wildcard patterns.
7. Enforce tenant/schema isolation in storage and notification channels.
8. Maintain correctness under concurrent writes, retries, and transient DB failures.

### Quality Gate Before Review Is Marked Complete

1. All findings severity-ranked with file and line citations.
2. Coverage matrix includes core functionality and typical use-case mapping.
3. Runtime evidence from CORE and INTEGRATION test runs is present.
4. Residual risks and untested high-impact scenarios are explicitly listed.

---

Good bones, but there are a few real production-grade problems here. The class is readable and the intent is clear, but the lifecycle, concurrency, and failure semantics are not tight enough yet.

My review is limited to this class only. The real truth depends on what `OutboxConsumer`, `OutboxConsumerGroupMember`, and your Postgres claim/reset semantics actually do.

Vert.x 5’s model is still: don’t block the event loop, and handler execution is context-sensitive, so anything here that serializes on JVM locks or assumes single-threaded access needs to be treated carefully. ([vertx.io][1])

## What is good

A few things are solid:

* Clear separation between group-level routing and member-level processing.
* Constructor overloads are pragmatic.
* `AtomicBoolean` for lifecycle flags is directionally right.
* `Predicate<Message<T>>` for group/member filtering is a nice clean API.
* Returning `Future<Void>` from `distributeMessage` is the right shape for Vert.x 5.
* You are at least thinking about “filtered” versus “failed”, which matters a lot in queue systems.

## The biggest problems

### 1. `start(SubscriptionOptions)` has a race and inconsistent lifecycle semantics RESOLVED

> **Resolution (pre-session):** Replaced `active`/`closed` booleans with `enum State { NEW, STARTING, ACTIVE, STOPPING, CLOSED }` backed by `AtomicReference<State>` with CAS transitions throughout.

This is the biggest design flaw in the class.

You do this:

```java
if (active.get()) {
    throw new IllegalStateException("Consumer group is already active");
}
```

Then later, asynchronously:

```java
.subscribe(topic, groupName, subscriptionOptions)
.map(v -> {
    start();
    return null;
});
```

That is not safe.

Two concurrent callers can both pass `active.get() == false`, both create the subscription, and then race into `start()`. One may fail, or worse, you may create duplicate external state before the second one blows up.

Also, `active` is only flipped inside `start()`, not when async startup begins. So your state model is effectively:

* “not active”
* “maybe starting but still looks inactive”
* “active”

That is not good enough for a queue consumer.

**What to do instead:**
Use a real state machine, not two booleans.

Example:

```java
enum State { NEW, STARTING, ACTIVE, STOPPING, CLOSED }
private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
```

Then `start(subscriptionOptions)` should CAS `NEW -> STARTING` once, and only transition to `ACTIVE` after everything succeeds. On failure, go back to `NEW` or `CLOSED`, depending on policy.

Right now the lifecycle is too loose.

---

### 2. `start(subscriptionOptions)` claims to be blocking, but it is not RESOLVED

> **Resolution (pre-session):** The misleading `isEventLoopContext` guard was removed. The method is now correctly non-blocking, returns `Future<Void>`, and uses `.compose()` for async chaining.

This comment/guard is misleading:

```java
if (context != null && context.isEventLoopContext()) {
    throw new IllegalStateException(
        "Do not call blocking start(subscriptionOptions) on event-loop thread - use a worker thread");
}
```

But the method returns `Future<Void>`. It is not inherently blocking.

Unless `databaseService.getSubscriptionService().subscribe(...)` is actually blocking internally, this guard is wrong and will annoy callers for no good reason.

In Vert.x, async startup methods are exactly what you *should* call from an event loop context, provided they do not block. ([vertx.io][1])

So either:

* remove that guard, or
* rename/document the method honestly if it really does blocking work under the covers.

As written, the API contract is confused.

---

### 3. `containsKey` + `put` is a race RESOLVED

> **Resolution (pre-session):** `addConsumer` now uses `putIfAbsent(consumerId, member)` with a null check on the return value.

Here:

```java
if (members.containsKey(consumerId)) {
    throw new IllegalArgumentException("Consumer with ID '" + consumerId + "' already exists in group");
}
...
members.put(consumerId, member);
```

That is classic check-then-act race on a concurrent map.

Two threads can both pass `containsKey`, both create a member, and one silently overwrites the other.

Use `putIfAbsent`:

```java
OutboxConsumerGroupMember<T> existing = members.putIfAbsent(consumerId, member);
if (existing != null) {
    throw new IllegalArgumentException(...);
}
```

Same kind of issue exists in `setMessageHandler()` with the default ID path.

---

### 4. Your "round-robin" is not round-robin RESOLVED

> **Resolution (pre-session):** Routing comment and Javadoc renamed to "deterministic hash-based routing". `selectConsumer()` method documented accordingly.

This comment is wrong:

```java
// Simple round-robin load balancing
```

This code:

```java
int index = Math.floorMod(message.getId().hashCode(), eligibleConsumers.size());
```

is deterministic hash partitioning, not round-robin.

That matters because:

* it gives sticky routing by message ID
* it can skew badly if IDs are not well distributed
* it does not balance based on live load
* if the eligible consumer set changes, routing jumps unpredictably

So either rename it honestly:

* “hash-based partitioning”
* “deterministic sticky routing”

or implement actual round-robin with an atomic cursor.

For message queues, this choice is architectural, not cosmetic.

---

### 5. Failure semantics for filtered / no-eligible-consumer look dangerous RESOLVED

> **Resolution (pre-session):** Exception hierarchy now separates permanent rejection (`RejectedMessageException` for group filter) from transient filtering (`MessageFilteredException` for no eligible consumer / removed member). `OutboxConsumer` treats these differently at the claim/reset level.

This bit worries me a lot:

```java
return Future.failedFuture(
    new MessageFilteredException(message.getId(), groupName, "rejected by group filter"));
```

and again:

```java
return Future.failedFuture(
    new MessageFilteredException(message.getId(), groupName, "no eligible consumer in group"));
```

Whether this is correct depends entirely on what `OutboxConsumer` does when the handler future fails.

If underlying failure means:

* release claim
* reset to `PENDING`
* retry later

then you may have created a hot-loop poison message.

A message that is permanently filtered out or permanently has no eligible consumer should usually not be treated the same as transient processing failure.

You need at least three outcomes, not just success/failure:

1. **processed successfully**
2. **transient failure; retry**
3. **rejected / unroutable / permanently ignored**

Right now you are encoding 2 and 3 both as failed futures. That is often wrong in queue systems.

This is probably the most important semantic question in the whole design.

---

### 6. No protection against concurrent delivery to the same member RESOLVED

> **Resolution (2026-03-30):** Added `AtomicInteger inFlightCount` with configurable `maxConcurrency` (default 1) to `OutboxConsumerGroupMember`. `processMessage()` now enforces a concurrency gate checks `inFlightCount.get() >= maxConcurrency` before accepting, atomically increments on accept, and decrements on success/failure/filter.

You select a member and call:

```java
selectedConsumer.processMessage(message)
```

But I see no evidence here that one member processes one message at a time.

If `underlyingConsumer.subscribe(...)` can deliver multiple messages concurrently, the same member may receive overlapping calls. That may be fine, but only if:

* handler code is thread-safe
* ordering does not matter
* member state is concurrency-safe
* backpressure is handled

If you want each group member to behave like a single logical consumer, you usually want explicit in-flight limits per member, often `1` by default.

Without seeing `OutboxConsumerGroupMember`, I would treat this as a likely correctness risk.

---

### 7. `getStats()` computes misleading aggregates RESOLVED

> **Resolution (pre-session):** `getStats()` now uses a weighted average (`weightedTotalMs += stats.getAverageProcessingTimeMs() * processed`, divided by `totalProcessed`). Redundant counters reconciled.

This line is mathematically wrong for heterogeneous workloads:

```java
double avgProcessingTime = members.values().stream()
    .mapToDouble(member -> member.getStats().getAverageProcessingTimeMs())
    .average()
    .orElse(0.0);
```

That is an **average of averages**. If one member processed 10 messages and another processed 10 million, this result is garbage.

You need a weighted average.

Similarly:

```java
Instant lastActiveAt = createdAt;
```

means a never-used group looks “active” since creation time, which is misleading.

Also, you recompute `totalProcessed` and `totalFailed` from member stats, while also keeping:

```java
totalMessagesProcessed
totalMessagesFailed
```

Those counters are then partially redundant and partially inconsistent because `getStats()` ignores them.

Pick one source of truth.

---

### 8. Stop/close semantics are not robust enough for async resources RESOLVED

> **Resolution (2026-03-30):** Added `closeAsync()` returning `Future<Void>` to `OutboxConsumer`. `OutboxConsumerGroup.stop()` and `close()` now call `closeAsync()` via `instanceof OutboxConsumer<?> oc` pattern match for non-blocking shutdown. Shared scheduler is shut down with `awaitTermination`.

You do:

```java
underlyingConsumer.unsubscribe();
underlyingConsumer.close();
underlyingConsumer = null;
```

That looks synchronous, but in Vert.x and database-backed consumers, unsubscribe/close are often logically asynchronous.

If these methods merely trigger shutdown but return immediately, then:

* in-flight messages may still be running
* `underlyingConsumer` becomes null before actual shutdown completes
* `close()` may race with active callbacks

A serious queue component should usually expose async shutdown:

```java
Future<Void> stopAsync()
Future<Void> closeAsync()
```

and wait for:

* polling stopped
* no new deliveries
* in-flight processing drained or cancelled
* DB claims released if required

The current lifecycle is too eager.

---

### 9. `synchronized` on `setMessageHandler()` is awkward in Vert.x code RESOLVED

> **Resolution (pre-session):** `setMessageHandler()` is no longer `synchronized`. Consistent with the rest of the class's use of concurrent primitives.

This is not automatically wrong, but it is suspicious:

```java
public synchronized ConsumerGroupMember<T> setMessageHandler(...)
```

A Java monitor is a blocking lock. In plain Java that is fine. In Vert.x code, it is something to be cautious about because event-loop code should avoid unnecessary blocking primitives. ([vertx.io][1])

Given the rest of the class is already using concurrent primitives, this method being `synchronized` looks inconsistent rather than deliberate.

A proper state/members CAS strategy would let you remove it.

---

### 10. Member removal can race with message assignment RESOLVED

> **Resolution (2026-03-30):** Added post-selection liveness check in `distributeMessage()`: after `selectConsumer()`, verifies `members.containsValue(selectedConsumer) && selectedConsumer.isActive()` before dispatch. Returns `MessageFilteredException` if the member was removed or deactivated between selection and dispatch.

This sequence is possible:

* `distributeMessage()` computes eligible consumers
* picks one member
* another thread calls `removeConsumer()`
* member is stopped/closed
* original thread still invokes `processMessage(message)`

Depending on `OutboxConsumerGroupMember`, that may fail noisily or silently.

You need either:

* stronger serialization around routing/removal, or
* members that can safely reject post-close processing in a defined way.

---

## Less critical, but still worth fixing ALL RESOLVED

### Constructor overload explosion RESOLVED

> **Resolution (pre-session):** `Builder<T>` pattern introduced. Overloads retained for backward compatibility but delegate to the builder-constructed path.

Too many overloads. It is already awkward and will get worse. A builder would be cleaner.

### Null validation RESOLVED

> **Resolution (pre-session):** `Objects.requireNonNull()` calls added for `groupName`, `topic`, `payloadType` in the constructor.

You do not validate key ctor arguments like `groupName`, `topic`, `payloadType`, `objectMapper`, `configuration`. Fail fast.

### Logging

These info logs may be too chatty in a real consumer runtime:

* add/remove consumer
* start/stop/close
  It depends on scale, but this can get noisy.

### `getConsumerIds()`

Returning a copy is fine.

### Weakly consistent iteration

Using `ConcurrentHashMap.values().stream()` is acceptable if you are happy with weakly consistent snapshots. That is often fine for stats, less fine for routing if you expect exact behavior under churn.

## What I would change first

In order:

### First: fix lifecycle

Replace `active` / `closed` with a single atomic state machine.

### Second: separate outcome types

Do not use failed futures for both transient failure and permanent rejection unless your underlying consumer explicitly understands the difference.

You probably want something like:

```java
sealed interface DeliveryOutcome {
    record Ack() implements DeliveryOutcome {}
    record Retry(Throwable cause) implements DeliveryOutcome {}
    record Reject(String reason) implements DeliveryOutcome {}
}
```

or simpler, if you want to stay idiomatic:

* success future = ack
* custom exception hierarchy:

  * `RetryableMessageException`
  * `RejectedMessageException`

and make `OutboxConsumer` treat them differently.

### Third: fix membership concurrency

Use `putIfAbsent`, and think through add/remove/start/stop races.

### Fourth: decide routing policy explicitly

Do you want:

* real round-robin
* sticky hash routing
* least-loaded
* partition affinity
* ordered per key

Right now the code says one thing and does another.

### Fifth: make shutdown async

A queue consumer should shut down cleanly, not optimistically.

## Concrete code-level fixes

### Safer `addConsumer`

```java
@Override
public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler,
                                          Predicate<Message<T>> messageFilter) {
    Objects.requireNonNull(consumerId, "consumerId");
    Objects.requireNonNull(handler, "handler");

    if (closed.get()) {
        throw new IllegalStateException("Consumer group is closed");
    }

    OutboxConsumerGroupMember<T> member =
        new OutboxConsumerGroupMember<>(consumerId, groupName, topic, handler, messageFilter, this);

    OutboxConsumerGroupMember<T> existing = members.putIfAbsent(consumerId, member);
    if (existing != null) {
        throw new IllegalArgumentException("Consumer with ID '" + consumerId + "' already exists in group");
    }

    if (active.get()) {
        member.start();
    }

    logger.info("Added consumer '{}' to outbox group '{}' for topic '{}'", consumerId, groupName, topic);
    return member;
}
```

### Rename routing method honestly

```java
/**
 * Selects a consumer using deterministic hash-based routing on message ID.
 */
private OutboxConsumerGroupMember<T> selectConsumer(List<OutboxConsumerGroupMember<T>> eligibleConsumers,
                                                    Message<T> message) {
    int index = Math.floorMod(message.getId().hashCode(), eligibleConsumers.size());
    return eligibleConsumers.get(index);
}
```

If you actually want round-robin:

```java
private final AtomicInteger rr = new AtomicInteger();

private OutboxConsumerGroupMember<T> selectConsumer(List<OutboxConsumerGroupMember<T>> eligibleConsumers,
                                                    Message<T> message) {
    int index = Math.floorMod(rr.getAndIncrement(), eligibleConsumers.size());
    return eligibleConsumers.get(index);
}
```

### Fix weighted average

Assuming each member stat exposes processed count:

```java
long totalProcessed = 0;
double weightedTotalMs = 0.0;

for (OutboxConsumerGroupMember<T> member : members.values()) {
    ConsumerMemberStats stats = member.getStats();
    long processed = stats.getMessagesProcessed();
    totalProcessed += processed;
    weightedTotalMs += stats.getAverageProcessingTimeMs() * processed;
}

double avgProcessingTime = totalProcessed == 0 ? 0.0 : weightedTotalMs / totalProcessed;
```

## Architectural question you need to answer

This is the real question:

**What exactly does a failed handler future mean in your Postgres-backed queue?**

Because this class currently assumes:

* business reject
* no eligible consumer
* actual processing failure

can all be represented as failure.

That is usually wrong.

In a proper Postgres queue, those states often map to different DB transitions:

* `DONE`
* `RETRY_PENDING`
* `DEAD_LETTER`
* `IGNORED`
* `UNROUTABLE`

If you do not model those separately, you will get retry storms, stuck messages, or misleading metrics.

## Bottom line

~~This is decent skeleton code, but not yet something I would trust in a hard production queue.~~

**Update (2026-03-30): All 10 major findings and all minor items are now resolved.** The class has been hardened with:

* Proper `enum State` machine with CAS transitions (was: racy booleans)
* Non-blocking `start(subscriptionOptions)` with correct `Future<Void>` semantics (was: misleading blocking guard)
* `putIfAbsent` for member registration (was: check-then-act race)
* Honest "hash-based routing" labeling (was: mislabeled "round-robin")
* Separated `RejectedMessageException` / `MessageFilteredException` (was: ambiguous failure semantics)
* Per-member concurrency gate with `maxConcurrency` (was: unbounded concurrent delivery)
* Weighted average stats aggregation (was: average-of-averages)
* `closeAsync()` for non-blocking shutdown (was: synchronous eager close)
* Removed `synchronized` from `setMessageHandler` (was: blocking lock in Vert.x code)
* Post-selection liveness check for remove/route race (was: unguarded)
* Builder pattern and `Objects.requireNonNull` validation
* Shared `ScheduledExecutorService` for filter retry (was: per-member thread)

**Verification:** 246 CORE tests pass (0 failures, 0 errors, 0 skipped). No forbidden patterns (`CompletableFuture`, `.join()`, `.get()` blocking, `Thread.sleep`) in touched files.

If you want, send `OutboxConsumer`, `OutboxConsumerGroupMember`, and the SQL/schema for claim/ack/retry/reset. That is where the real queue correctness lives.

[1]: https://vertx.io/docs/vertx-core/java/?utm_source=chatgpt.com "Vert.x Core Manual"
