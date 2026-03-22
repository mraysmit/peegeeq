1. The biggest domain bug: version lineage is muddled

Your correction model is not internally consistent.

What the code currently does

In appendCorrectionWithTransaction(...) you insert:

event_id = new UUID
previous_version_id = originalEventId
version = MAX(version) + 1 from rows where event_id = $1 OR previous_version_id = $1

That means one of two things is intended:

Model A: chain model

Each correction points to the immediately previous version.

If that is the intended model, your code is wrong, because:

getAllVersions(...) only fetches one level:
WHERE event_id = $1 OR previous_version_id = $1
version calculation is only safe if callers always pass the root or always pass the right prior node
getById(...) and getAsOfTransactionTime(...) do not line up cleanly with this interpretation
Model B: star model

Every correction points back to the original root event.

If that is the intended model, then:

previous_version_id is a misleading field name
getAllVersions(...) is only correct if all corrections always point to the same root
the API name originalEventId is doing a lot of semantic work and callers can easily break the model by passing a non-root version

Right now it looks like a hybrid. That is dangerous.

Worse: concurrent correction versioning is unsafe

This is the real production bug.

You do:

SELECT COALESCE(MAX(version), 0)
compute nextVersion
insert row

Two concurrent corrections can compute the same nextVersion.

That is a classic race.

You need one of:

a unique constraint on the version family plus retry
SELECT ... FOR UPDATE on a root/family row
a dedicated version-allocation table
or redesign so version is DB-assigned atomically

For an event store, this is not optional.

2. TransactionPropagation.CONTEXT is presented as stronger than it really is

You clearly care about transaction participation, which is good. But the implementation over-promises.

The problem

appendWithTransactionInternal(...) calls:

executeOnVertxContext(sharedVertx, () -> pool.withTransaction(...))

and your comment says this ensures proper TransactionPropagation support.

That is only partly true.

TransactionPropagation.CONTEXT depends on the relevant Vert.x context already carrying the transaction state. But you are hopping to a context created by your own internal static sharedVertx.

That means:

if the caller started work on some other Vert.x instance/context, you may not be participating in that context
if the caller expects ambient transaction sharing from outside this internal Vert.x world, they may not get it
your static internal Vert.x becomes the hidden center of transaction semantics

So the code is not exactly lying, but it is definitely more fragile than the comments suggest.

What I would do

For infrastructure like this, pick one of these and be explicit:

Option 1: explicit transaction participation only

Make appendInTransaction(..., SqlConnection connection) the real transactional API and treat withTransaction(...) as “own transaction”.

That is the cleanest.

Option 2: inject Vertx and require same Vertx universe

Do not create your own static hidden Vertx. Require the application to supply one.

That removes a whole class of context ambiguity.

Right now you are mixing both worlds.

3. Hidden static Vertx in a library/infrastructure component is a smell

static volatile Vertx sharedVertx;

I do not like this in a reusable library.

Why:

hidden resource ownership
hidden thread pools
hidden native transport settings
hard-to-reason-about shutdown
cross-test contamination
surprising interaction with host app metrics/tracing/config

This class is not just using Vert.x. It is creating its own runtime.

That is too much responsibility for an event store implementation.

Better design

Inject:

Vertx
maybe Pool or a pool factory
maybe notification handler factory

Then this class becomes a proper component rather than a mini platform.

4. Your event-bus distribution path is not functionally equivalent to the normal path

This matters a lot.

Direct append path

appendWithTransactionInternal(...):

validates
serializes with ObjectMapper
may use transaction wrapper
returns DB transaction_time
uses correlation fallback
can use TransactionPropagation
Event-bus distribution path

appendWithEventBusDistribution(...):

sends JSON body over event bus
worker uses pool.preparedQuery(...) directly, no transaction wrapper
worker reconstructs insert independently
tracing is supposed to happen but does not fully propagate
it is not actually the same semantics

That means enabling:

peegeeq.database.use.event.bus.distribution=true

changes more than performance. It changes behavior.

For infrastructure code, that is a red flag.

5. Tracing propagation is incomplete / partly broken

You clearly tried to do this properly. But it is not wired through cleanly.

Bug 1: trace header is never actually sent

In DatabaseWorkerVerticle.start() you do:

String traceparent = message.headers().get("traceparent");
TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);

But in sendDatabaseOperation(...) you call:

vertx.eventBus().<JsonObject>request(operationAddress, operation)

No delivery options. No header set.

So the consumer is looking for a traceparent that the producer never sends.

That means the worker normally creates a new trace instead of continuing the existing one.

Bug 2: wrong context source in the worker

Inside the consumer:

Context ctx = vertx.getOrCreateContext();
ctx.put(...)

Inside a verticle consumer, you almost certainly want the current context, not an arbitrary created/retrieved one.

Use Vertx.currentContext() or otherwise operate on the actual handling context.

As written, you can end up storing trace state on the wrong context.

Bug 3: MDC scope is only around parts of the flow

You wrap the outer handler and reply callbacks, which is decent, but async operations inside processDatabaseOperation(...) rely on context propagation being right. Given the previous issue, that is shaky.

6. close() is not really a close

Your public close() does this:

closeFuture().onFailure(...)

and returns immediately.

That means:

caller thinks store is closed
resources may still be closing
errors are only logged
there is no completion signal for synchronous callers

For infrastructure, this is weak.

Since the interface probably forces void close(), fine, but then:

document clearly that it is fire-and-forget
or block only at application boundary
or make closeFuture() the main lifecycle API and push callers there
Also: async closes ignored elsewhere

clearInstancePools() does:

reactivePool.close();
pipelinedClient.close();

and throws the futures away.

Same lifecycle sloppiness.

7. getStatsReactive() likely has a timestamp mapping bug

You store and read most timestamps using OffsetDateTime.

But in getStatsReactive() you parse:

basicRow.getLocalDateTime("oldest_event_time")
basicRow.getLocalDateTime("newest_event_time")

If valid_time is timestamptz or equivalent offset-aware column, this is the wrong getter and loses timezone semantics.

Given the rest of the class, this looks inconsistent at best and wrong at worst.

Use getOffsetDateTime(...) consistently.

8. getAllVersions(...) and family traversal are not robust

getAllVersions(...):

WHERE event_id = $1 OR previous_version_id = $1
ORDER BY version ASC

That only gives:

the row itself
direct children

It does not traverse a correction family recursively unless your model is strict “all corrections point to root”.

If that is the model, encode it clearly and rename accordingly.

If not, this method is incomplete.

By contrast, getAsOfTransactionTime(...) goes to the effort of recursive family resolution. So the class itself is internally inconsistent.

9. Mapping errors are swallowed in query paths

In both:

queryReactive(...)
getAllVersionsReactive(...)

you do row mapping in a loop and on failure you log and continue.

That is bad for an event store.

Returning partial results after silently dropping malformed rows is usually worse than failing fast.

This code makes corruption/deserialization bugs look like “fewer events”.

I would fail the whole future unless you have a very explicit degraded-read mode.

10. mapRowToEvent(...) has brittle JSON handling

This part worries me:

if (payloadJson.startsWith("\"") && payloadJson.endsWith("\"")) {
    payloadJson = payloadJson.substring(1, payloadJson.length() - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
}

That is manual JSON surgery.

Usually when code starts stripping quotes and unescaping slashes by hand, it means the real serialization boundary is wrong elsewhere.

This is fragile because:

it can mis-handle legitimate JSON string payloads
it bakes in assumptions about how the driver rendered JSONB
it is hard to reason about for edge cases

You should fix the storage/driver mapping path, not keep layering heuristics on top.

Also:

Map<String, String> headers = objectMapper.readValue(headersJson, Map.class);

That is raw-type deserialization. You do not actually guarantee Map<String,String> there.

Use a TypeReference<Map<String,String>>.

11. appendInTransactionReactive(...) ignores returned DB transaction time

In appendWithTransactionInternal(...) you correctly use RETURNING transaction_time and map that actual value.

But in appendInTransactionReactive(...) you insert with RETURNING event_id, transaction_time and then return:

transactionTime.toInstant()

rather than the returned row’s transaction_time.

That is inconsistent and avoidable.

If the DB is the source of truth, use the DB value in all paths.

12. performanceMonitor lifecycle looks leaky

In getOrCreateReactivePool() you call:

performanceMonitor.startPeriodicLogging(getOrCreateSharedVertx(), 10000);

Potential problems:

repeated starts if multiple stores create pools
unclear cancellation on close
tied to shared Vertx lifecycle
more background activity hidden inside pool creation

Maybe your monitor class handles this, maybe not. But from this class, it looks like potential timer leakage.

13. Some “performance” comments are stronger than the code justifies

Examples:

“CRITICAL PERFORMANCE FIX”
“4x performance improvement”
“maximum throughput”
“true parallel processing”
“event loop threads for maximum performance”

Some of those may be true in a benchmark, but in infrastructure code comments they read like overclaiming.

A few specific points:

Event bus distribution

Using event-loop verticles plus event-bus request/reply is not automatically faster. It adds:

extra serialization
extra dispatch hop
more moving parts
different failure behavior

It may improve throughput under some contention patterns, but the comments treat it as universally superior. That is too strong.

Pipelined client

The idea is sensible, but “4x performance improvement” should not be encoded as fact in code comments unless you have very specific benchmark conditions.

Infrastructure comments should be precise, not sales copy.

14. There is too much responsibility in one class

This class currently owns:

append/query semantics
transaction orchestration
correction/version logic
pool creation
pipelined client creation
Vertx creation
event-bus routing
worker verticle deployment
tracing propagation
performance monitoring
notification handler lifecycle
test-reset hooks

That is too much.

It makes correctness harder because concerns bleed into each other.

Better split

I would break it into at least:

BiTemporalEventStoreRepository or SQL adapter
BiTemporalVersioningService
BiTemporalNotificationService
BiTemporalEventBusDispatcher
BiTemporalResourceManager or externally supplied Vertx/Pool

Then the store class becomes orchestration, not a god-object.

15. Reactive/Vert.x-specific review

On the Vert.x side specifically:

Good
no blocking calls in the hot paths shown
uses Future composition properly in many places
avoids callback nesting
uses withTransaction(...) in several places instead of hand-rolled begin/commit
Weak points
too much manual context fiddling
internal hidden Vertx
fire-and-forget lifecycle behavior
partial-result swallowing
semantic differences between execution paths
tracing/context propagation not actually airtight

This is the kind of code that can look “reactive” while still being operationally fragile.

Highest-priority fixes

If I were reviewing this in a real repo, these are the changes I would push first.

Must fix now
Fix correction/version concurrency
Add DB-level uniqueness and atomic version allocation strategy.
Define the version-family model clearly
Decide root-star vs chain, then make schema/API/query names match that model.
Fix trace propagation over the event bus
Actually send traceparent headers and use the current Vert.x context in the consumer.
Stop swallowing row-mapping failures
Fail the query future instead of returning partial data.
Remove or reduce hidden static Vertx ownership
Inject Vertx if at all possible.
Fix timestamp getter inconsistency in stats
Use offset-aware mapping.
Next tier
Make the event-bus path semantically equivalent to the direct path, or explicitly mark it as a different execution mode with different guarantees.
Clean up lifecycle:
asynchronous close semantics
clearInstancePools()
performance monitor shutdown
Replace brittle manual JSON quote/unescape handling with a single consistent serialization contract.
Split the class up.
A few concrete examples of code I would challenge in review
This is unsafe under concurrency
SELECT COALESCE(MAX(version), 0) as max_version
...
nextVersion = maxVersion + 1
This probably breaks distributed tracing continuity
vertx.eventBus().<JsonObject>request(operationAddress, operation)

while the consumer expects:

message.headers().get("traceparent")
This is likely the wrong context
Context ctx = vertx.getOrCreateContext();
ctx.put(...)
This is dangerous partial-failure behavior
catch (Exception e) {
    logger.error("Failed to map row to event: {}", e.getMessage(), e);
}
This is brittle JSON repair logic
.replace("\\\"", "\"")
.replace("\\\\", "\\");
This is lifecycle hand-waving
public void close() {
    closeFuture().onFailure(...)
}
Bottom line

This code shows real thought and real engineering effort. It is not amateur work.

But it is also trying to be:

repository
runtime
dispatcher
tracer bridge
pool manager
benchmark harness
lifecycle manager

That is where it starts to crack.

The biggest problem is not syntax or style. It is semantic reliability:

version lineage
concurrent correction safety
transaction/context truthfulness
behavior changing across code paths

That is exactly the stuff that bites reactive infrastructure in production.

Paste the corresponding table schema and SimpleBiTemporalEvent contract next, and I’ll give you the next-level review: schema correctness, index strategy, and whether the SQL actually matches the object model.