# PeeGeeQ Vert.x Async Test Safety Patterns

> **Purpose.** Document the canonical safe forms for Vert.x + JUnit5 async tests
> in PeeGeeQ, and catalogue the anti-patterns that have repeatedly produced
> silent test failures — green builds covering broken code.
>
> **Companion fixture.**
> [peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java)
> contains executable counter-examples for every anti-pattern listed here.

---

## 1. Why this document exists

PeeGeeQ's test suite is large, fully reactive, and runs against real PostgreSQL
via Testcontainers. The combination of Vert.x 5 reactive futures, JUnit5's
`VertxExtension`, and `VertxTestContext` is powerful but unforgiving: the
framework's default behaviour on a mis-wired test is **silence**, not failure.

During the Tier 1 async-test sweep across `peegeeq-db`, `peegeeq-outbox`,
`peegeeq-native`, `peegeeq-examples`, and `peegeeq-bitemporal`, we repeatedly
found tests where:

- Assertions threw `AssertionError`, but Vert.x caught and logged the error
  at WARN — the test reported a generic timeout with no diagnostic.
- Futures failed, but no `.onFailure` was wired — the failure vanished.
- `awaitCompletion(...)` returned `false` on timeout, but the return value was
  discarded and a trivial post-await assertion let the test pass green.
- `ctx.completeNow()` was called too early in a `.compose` chain — later
  assertions were delivered to a closed context and silently dropped.
- `.toCompletionStage().get()` wrapped `AssertionError` in `ExecutionException`,
  which a broad `catch (Exception)` silently discarded.

In every case, the build was green. **That is the failure mode this document
exists to eliminate.**

---

## 2. The canonical safe forms

Every async PeeGeeQ test must use one of the two forms below as the terminal
node of every chain that the test depends on.

### 2.1 Success expected

```java
@Test
void someOperationSucceeds(VertxTestContext ctx) {
    service.doSomething()
        .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertEquals(expected, result);
            ctx.completeNow();
        })));
}
```

### 2.2 Failure expected

```java
@Test
void someOperationFailsWithIllegalArgument(VertxTestContext ctx) {
    service.doSomething(-1)
        .onComplete(ctx.failing(e -> ctx.verify(() -> {
            assertTrue(e instanceof IllegalArgumentException);
            ctx.completeNow();
        })));
}
```

### 2.3 Why exactly this form

There are **three obligations** on every async test branch. The canonical form
satisfies all three by construction; alternative forms satisfy them only if
hand-written correctly every time.

| Obligation | How the canonical form satisfies it |
|---|---|
| Route both success and failure to the test context | `ctx.succeeding` / `ctx.failing` route the *unexpected* branch to `failNow` automatically. |
| Catch `AssertionError` thrown in the lambda and report it to the test context | `ctx.verify(() -> ...)` wraps the lambda and reroutes any `AssertionError` to `ctx.failNow`. |
| Reach `ctx.completeNow()` exactly once on the intended terminal branch | The verify body is the only branch — `completeNow()` is its last statement. |

Drop **any one** of those three pieces and the test will lie to you on at least
one failure path.

---

## 3. Anti-patterns (all demonstrated in `VertxAsyncTestPitfallsDemo`)

### Category A: failures masked as timeouts

These are bad UX (the real diagnostic is hidden), but at least the test
*does* fail.

#### A1. Assertion in raw `.onSuccess` with no `ctx.verify`

```java
// WRONG
future.onSuccess(v -> {
    assertEquals(expected, v);   // AssertionError swallowed by Vert.x
    ctx.completeNow();           // never reached
}).onFailure(ctx::failNow);
```

`AssertionError` propagates out of the lambda, Vert.x logs it at WARN, and
`completeNow()` is never reached. The test hangs to `awaitCompletion` timeout
with no mention of the failed assertion.

**Fix:** wrap the assertion in `ctx.verify(...)`.

#### A2. `.onSuccess` with no `.onFailure`

```java
// WRONG
future.onSuccess(v -> ctx.verify(() -> { ...; ctx.completeNow(); }));
// no .onFailure — future failures go to Vert.x's unhandled-exception logger
```

If the future fails, nothing reaches `ctx`. Test times out with no cause.

**Fix:** use `.onComplete(ctx.succeeding(...))` so the failure path is routed
automatically.

### Category B: failures that produce GREEN BUILDS (catastrophic)

These are the patterns that have produced real outages of trust in the test
suite.

#### B1. Ignored `awaitCompletion` return value

```java
// WRONG
VertxTestContext ctx = new VertxTestContext();
service.doWork(ctx);
ctx.awaitCompletion(100, TimeUnit.MILLISECONDS);  // return value discarded
assertTrue(counter.get() > 0);                    // runs against partial state
```

`awaitCompletion` returns `false` on timeout. If the caller doesn't check the
return value, the test continues into post-await assertions that have nothing
to do with the actual work, and passes green.

**Fix:** always inject `VertxTestContext` as a method parameter (let the
`VertxExtension` manage the await), or — if a manual context is required —
`assertTrue(ctx.awaitCompletion(...))` as the first post-await line.

#### B2. `completeNow()` on the wrong `.compose` branch

```java
// WRONG
future
    .compose(a -> {
        ctx.completeNow();              // PREMATURE — test is done as far as JUnit knows
        return doStepTwo();
    })
    .compose(b -> {
        assertEquals(expected, b);      // failure has nowhere to go
        return Future.succeededFuture();
    })
    .onFailure(ctx::failNow);           // failNow on completed context is ignored
```

Once `ctx.completeNow()` has been called, the context is closed and subsequent
`failNow` / `verify` calls are no-ops. Any downstream assertion failure is
silently dropped.

**Fix:** call `ctx.completeNow()` exactly once, in the *last* terminal handler.

#### B3. Sync bridge + broad `catch`

```java
// WRONG
try {
    future.toCompletionStage().toCompletableFuture().get();
} catch (Exception e) {
    // anti-pattern: catch Exception silently
}
```

`.get()` wraps any failure (including `AssertionError`) in `ExecutionException`.
A broad `catch (Exception)` swallows it. The PeeGeeQ coding rules already ban
sync bridges (`.toCompletionStage()`, `.toCompletableFuture()`, `.get()`,
`.join()`); this is *why*.

**Fix:** remove the bridge entirely. Use composable futures only.

#### B4. `ctx.verify(...)` after `ctx.completeNow()`

```java
// WRONG
future.onSuccess(v -> {
    ctx.completeNow();
    ctx.verify(() -> assertEquals(expected, v));   // ctx is already closed
});
```

`VertxTestContext` ignores failures delivered after completion. The verify body
runs, the `AssertionError` is caught by `verify`, but the call to `failNow`
inside `verify` is a no-op because the context is already complete.

**Fix:** put `ctx.completeNow()` at the *end* of the `verify` body, never before
or alongside it.

---

## 4. Hard rules for new tests

These are mandatory and enforceable by reviewer judgment until automated rules
are added.

1. **Every terminal future handler in a test must be `.onComplete(ctx.succeeding(...))` or `.onComplete(ctx.failing(...))`.** Bare `.onSuccess(...).onFailure(...)` pairs are banned in test code.
2. **Every assertion inside an async callback must be wrapped in `ctx.verify(...)`.** No exceptions.
3. **`ctx.completeNow()` must be the last statement of the `verify` body on the intended terminal branch.** Never call it earlier; never call it from multiple branches.
4. **Never construct a manual `VertxTestContext` unless you need an unusual lifecycle.** Inject it as a method parameter and let `VertxExtension` handle the await. If you must construct one, assert the boolean return of `awaitCompletion`.
5. **Never bridge a `Future` to a `CompletableFuture` in tests.** Already banned by `pgq-coding-principles.md`; restated here because this is *the* most common source of swallowed failures.
6. **Post-`awaitCompletion` assertions are forbidden.** All verification happens inside the future chain. The only thing allowed after `awaitCompletion` is the JUnit-managed test exit.

---

## 5. Proposed future hardening

Not yet implemented; tracked here as the natural next step once the Tier 1
sweep finishes across all modules.

- **ArchUnit rule**: forbid `.onSuccess(...)` / `.onFailure(...)` calls in any
  `src/test` class file under any PeeGeeQ module. Force `.onComplete(...)` as
  the only legal terminal handler in tests.
- **ArchUnit rule**: forbid `.toCompletionStage()`, `.toCompletableFuture()`,
  `.get()`, `.join()`, `Thread.sleep` in `src/test`.
- **Checkstyle rule** (if a parser can handle it): require any `assertEquals` /
  `assertTrue` / `assertNotNull` call lexically inside a `.onComplete` lambda
  to be inside a `ctx.verify(...)` call.
- **CI-side default timeout**: shorten the default `awaitCompletion` timeout
  to 10s in CI so accidental pass-by-timeout failures surface fast instead of
  silently consuming the full default.
- **Helper consolidation**: a tiny `TestAssertions.succeeding(ctx, body)` and
  `TestAssertions.failing(ctx, type, body)` that bundle verify + completeNow,
  so the surface area where a test author can get the plumbing wrong shrinks
  to zero.

---

## 6. References

- [docs-design/dev/pgq-coding-principles.md](../dev/pgq-coding-principles.md)
- [docs-design/testing/PEEGEEQ_TESTING_STANDARDS.md](PEEGEEQ_TESTING_STANDARDS.md)
- [docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md](PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- [docs-design/testing/PEEGEEQ-TEST-COMMANDS.md](PEEGEEQ-TEST-COMMANDS.md)
- [peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java)
- [Vert.x 5 JUnit5 integration](https://vertx.io/docs/vertx-junit5/java/)
