Mark — this adapter is doing the exact things that make Vert.x apps hard to operate at scale: it creates its **own Vert.x**, builds **a second pool** beside your factory’s pool, blocks on closes, and even ignores the `clientId` you pass in. Let’s fix it.

## The problems (blunt + specific)

1. **Duplicate pools**
   You already have `PgClientFactory` → `PgConnectionManager` building pools. This adapter builds **another** pool with `PgBuilder.pool()`. Now you’ve got two pools to the same DB, two sets of connections, two lifecycles. That’s wasteful and a nightmare to reason about.

2. **Ignores `clientId`**
   In `createPool(PgClientFactory, clientId)` you fetch:

   ```java
   factory.getConnectionConfig("peegeeq-main");
   factory.getPoolConfig("peegeeq-main");
   ```

   …instead of the given `clientId`. That’s just a bug.

3. **Owns a Vert.x instance behind the caller’s back**
   Several ctors call `Vertx.vertx()`. Now this adapter becomes an owner of event loops you didn’t intend. Your other classes also create their own Vert.x. That’s how you get “event executor terminated” on shutdown and thread leaks.
   **Rule:** only the top-level app owns Vert.x; everything else gets it injected.

4. **Close semantics are wrong**
   `pool.close()` and `vertx.close()` are **async** in Vert.x 5 but you call them as if they’re sync. You also close `vertx` even if the caller injected it (ownership confusion).

5. **Options mapping is incomplete**
   `PoolOptions` only sets `maxSize`. You ignore `maxWaitQueueSize`, `idleTimeout`, `connectionTimeout`, `shared`. `PgConnectOptions` misses SSL mode/CA, timeouts, reconnects. You *do* want those in a prod queue.

6. **`connectDedicated()` requires `createPool()` first**
   You force callers to create a **pooled** connection to set `connectOptions` before they can create a **dedicated** connection for LISTEN. That’s backwards. You should be able to build `PgConnectOptions` straight from the factory’s config without creating a pool.

7. **Fallback defaults**
   When you don’t find config, you silently build a pool to `localhost/peegeeq`. That’s dangerous — services “work” but talk to the wrong DB. **Fail fast** instead.

8. **Thread-safety / idempotence**
   `pool` is a plain field; concurrent calls could race to create two pools. Guard creation.

9. **Naming**
   “Adapter” suggests it adapts your factory’s pool; it actually re-creates one. Either make it a true adapter (return the **existing** pool) or call it `StandalonePoolBuilder`.

---

## What to do instead

### A) Make this a thin adapter over **your factory’s pool**

* Don’t build a new pool; **reuse** the one the factory already made for that `clientId`.
* Only build **dedicated** connections (for LISTEN) when needed, from the **same config**.

### B) Don’t own Vert.x here

* Require a `Vertx` in the ctor (or pull it from the factory/manager if you expose it).
* Never call `Vertx.vertx()` inside.

### C) Close asynchronously and don’t close injected Vert.x

* Provide `Future<Void> closeAsync()`.
* Only close resources you created. If Vert.x is injected, **don’t close it**.

### D) Fix config + options mapping

* Respect `clientId`.
* Map `PoolOptions`: `maxSize`, `maxWaitQueueSize`, `idleTimeout`, `connectionTimeout`, `shared`.
* Map `PgConnectOptions`: host, port, db, user, password, **sslMode**, trust/key certs, connectTimeout, reconnectAttempts/Interval.

---

## Drop-in rewrite (lean, correct)

```java
package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin adapter that exposes the existing Pool from PgClientFactory
 * and can create a dedicated non-pooled PgConnection (e.g., for LISTEN/UNLISTEN).
 * Does NOT own Vert.x and does NOT create duplicate pools.
 */
public final class VertxPoolAdapter {
  private static final Logger log = LoggerFactory.getLogger(VertxPoolAdapter.class);

  private final Vertx vertx;
  private final PgClientFactory clientFactory;
  private final String clientId;

  // Keep connect options for dedicated connections; build lazily.
  private final AtomicReference<PgConnectOptions> connectOptionsRef = new AtomicReference<>();

  public VertxPoolAdapter(Vertx vertx, PgClientFactory clientFactory, String clientId) {
    this.vertx = Objects.requireNonNull(vertx, "vertx");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
  }

  /** Return the existing shared pool created by PgClientFactory for this clientId. */
  public Pool getPoolOrThrow() {
    return clientFactory.getPool(clientId)
        .orElseThrow(() -> new IllegalStateException("No pool for clientId=" + clientId +
            ". Ensure PgClientFactory.createClient(...) was called."));
  }

  /** Build PgConnectOptions from the factory configs (no pool creation required). */
  public PgConnectOptions getOrBuildConnectOptions() {
    PgConnectOptions existing = connectOptionsRef.get();
    if (existing != null) return existing;

    PgConnectionConfig c = clientFactory.getConnectionConfig(clientId);
    PgPoolConfig p = clientFactory.getPoolConfig(clientId);
    if (c == null || p == null) {
      throw new IllegalStateException("Missing config for clientId=" + clientId);
    }

    PgConnectOptions opts = new PgConnectOptions()
        .setHost(c.getHost())
        .setPort(c.getPort())
        .setDatabase(c.getDatabase())
        .setUser(c.getUsername())
        .setPassword(c.getPassword())
        // Map SSL properly if you support it in PgConnectionConfig:
        .setSslMode(c.isSslEnabled() ? SslMode.REQUIRE : SslMode.DISABLE)
        // Timeouts / reconnects (if you added them to PgConnectionConfig):
        // .setConnectTimeout((int) c.getConnectTimeout().toMillis())
        // .setReconnectAttempts(c.getReconnectAttempts())
        // .setReconnectInterval((int) c.getReconnectInterval().toMillis())
        ;

    connectOptionsRef.compareAndSet(null, opts);
    return connectOptionsRef.get();
  }

  /** Create a dedicated, non-pooled connection (useful for LISTEN/UNLISTEN). */
  public Future<PgConnection> connectDedicated() {
    try {
      PgConnectOptions options = getOrBuildConnectOptions();
      return PgConnection.connect(vertx, options);
    } catch (Throwable t) {
      return Future.failedFuture(t);
    }
  }

  /** No-op: we don't own the Pool or Vertx here. */
  public Future<Void> closeAsync() {
    return Future.succeededFuture();
  }

  public Vertx getVertx() { return vertx; }
}
```

### Why this is better

* **No duplicate pools** — you reuse the one your factory already manages.
* **No hidden Vert.x** — lifecycle stays with the app.
* **Dedicated LISTEN conn** without forcing a pool to be created first.
* **Respects `clientId`**.

---

## If you truly need a “build a pool” utility

If your use case requires building a pool (e.g., standalone test utility), isolate it in a separate class like `StandalonePoolBuilder` and make the ownership explicit:

* Require a `Vertx`.
* Return `Future<Pool>` and a separate `closeAsync()`.
* No defaults; **fail** if config isn’t provided.

---

## Final TL;DR

* This adapter should **not** create Vert.x or its own pool. Use the pool from `PgClientFactory`.
* Fix the `clientId` bug.
* Remove default “localhost” fallbacks; fail fast.
* Build **dedicated** `PgConnection` from config, not from a side-effect of pool creation.
* Async close semantics. Don’t close Vert.x you didn’t create.

Do this and your queue consumer can cleanly reuse the factory pool, create a robust dedicated LISTEN connection, and stop fighting lifecycle/thread issues.
