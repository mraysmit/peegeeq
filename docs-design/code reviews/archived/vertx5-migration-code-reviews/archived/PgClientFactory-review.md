Nice—concise class, sensible responsibilities. But there are a few traps here that will hurt under load and during lifecycle changes. I’ll be blunt and give you concrete fixes + a cleaned-up version.

## What’s off (and why)

1. **Pool creation is done twice**

   * `createClient()` calls `getOrCreateReactivePool(...)` *before* the try/catch and then **again** inside it. That’s redundant and can hide failures. Kill the first call.

2. **No idempotent “get or create” at the factory level**

   * Calling `createClient("peegeeq-main", …)` twice will overwrite configs but you don’t check whether a pool already exists or if its config changed. That’s a foot-gun.

3. **No removal/close per client**

   * You can only close **all** pools via `connectionManager.close()`. In real systems you’ll want to unregister one tenant/client cleanly.

4. **Config mutation without visibility guarantees**

   * You store `PgConnectionConfig` and `PgPoolConfig` in concurrent maps, but if they’re mutable you can end up with “half-updated” configs visible across threads. Prefer immutable configs or defensive copies.

5. **Input validation is missing**

   * `clientId` null/blank? Configs null? You throw late, if at all. Fail fast with `IllegalArgumentException`.

6. **Leaky API surface**

   * Exposing the raw config maps through `getAvailableClients()` is fine, but without “read” access to the `Pool` or a way to fetch a `PgClient` you force callers to carry around references. Consider `getClient(clientId)` and `getPool(clientId)`.

7. **Close contract**

   * `close()` throws `Exception`. In Vert.x ecosystems you’ll usually prefer a non-blocking close that returns `Future<Void>` (and keep `AutoCloseable#close()` as a best-effort wrapper if you need it).

8. **Logging**

   * Avoid logging full configs (secrets). You’re not doing it now—good—but keep it that way. Also, log at `info` on first successful create, `debug` for subsequent get.

## Drop-in fixed version

Here’s a cleaned-up version that’s thread-safe, idempotent, validates inputs, supports per-client removal, and doesn’t create pools twice. I also added a non-blocking `closeAsync()`.

```java
package dev.mars.peegeeq.db.client;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PgClientFactory implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(PgClientFactory.class);

  private final PgConnectionManager connectionManager;
  private final Map<String, PgConnectionConfig> connectionConfigs = new ConcurrentHashMap<>();
  private final Map<String, PgPoolConfig> poolConfigs = new ConcurrentHashMap<>();
  private final Map<String, PgClient> clients = new ConcurrentHashMap<>();

  public PgClientFactory(Vertx vertx) {
    this(new PgConnectionManager(Objects.requireNonNull(vertx, "vertx")));
  }

  public PgClientFactory(PgConnectionManager connectionManager) {
    this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
  }

  /** Idempotent: get existing client or create it (and its pool) atomically. */
  public PgClient createClient(String clientId, PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
    validate(clientId, connectionConfig, poolConfig);

    // Keep configs for observability/rehydration; treat them as immutable
    connectionConfigs.putIfAbsent(clientId, connectionConfig);
    poolConfigs.putIfAbsent(clientId, poolConfig);

    // Build or return cached client atomically
    return clients.computeIfAbsent(clientId, id -> {
      try {
        // Ensure the reactive pool exists
        Pool pool = connectionManager.getOrCreateReactivePool(id, connectionConfig, poolConfig);
        logger.info("Initialized reactive PG pool for client '{}'", id);
        return new PgClient(id, connectionManager);
      } catch (Exception e) {
        logger.warn("Failed to create reactive pool for client '{}': {}", id, e.getMessage());
        // Clean partial state
        connectionConfigs.remove(id);
        poolConfigs.remove(id);
        throw new RuntimeException("Failed to create reactive pool for client: " + id, e);
      }
    });
  }

  public PgClient createClient(String clientId, PgConnectionConfig connectionConfig) {
    return createClient(clientId, connectionConfig, new PgPoolConfig.Builder().build());
  }

  /** Return existing client if present. */
  public Optional<PgClient> getClient(String clientId) {
    return Optional.ofNullable(clients.get(clientId));
  }

  /** Handy for diagnostics or advanced usage. */
  public Optional<Pool> getPool(String clientId) {
    return Optional.ofNullable(connectionManager.getExistingPool(clientId));
  }

  public PgConnectionConfig getConnectionConfig(String clientId) {
    return connectionConfigs.get(clientId);
  }

  public PgPoolConfig getPoolConfig(String clientId) {
    return poolConfigs.get(clientId);
  }

  public Set<String> getAvailableClients() {
    return clients.keySet();
  }

  /** Remove one client: closes its pool and drops cached configs. */
  public Future<Void> removeClientAsync(String clientId) {
    PgClient removed = clients.remove(clientId);
    connectionConfigs.remove(clientId);
    poolConfigs.remove(clientId);
    return connectionManager.closePoolAsync(clientId)
        .onSuccess(v -> logger.info("Closed pool for client '{}'", clientId))
        .onFailure(err -> logger.warn("Error closing pool for client '{}': {}", clientId, err.getMessage()));
  }

  public Future<Void> closeAsync() {
    clients.clear();
    connectionConfigs.clear();
    poolConfigs.clear();
    return connectionManager.closeAsync()
        .onSuccess(v -> logger.info("PgClientFactory closed"))
        .onFailure(err -> logger.warn("PgClientFactory close encountered errors: {}", err.toString()));
  }

  @Override
  public void close() throws Exception {
    // Best-effort sync wrapper for AutoCloseable scenarios
    try {
      closeAsync().toCompletionStage().toCompletableFuture().get();
    } catch (Exception e) {
      throw e;
    }
  }

  private static void validate(String clientId, PgConnectionConfig conn, PgPoolConfig pool) {
    if (clientId == null || clientId.isBlank())
      throw new IllegalArgumentException("clientId must be non-blank");
    Objects.requireNonNull(conn, "connectionConfig");
    Objects.requireNonNull(pool, "poolConfig");
  }
}
```

### Notes on the changes

* **No double-creation**: only one `getOrCreateReactivePool` call.
* **Idempotency**: repeat `createClient` returns the same `PgClient` instance.
* **Per-client removal**: `removeClientAsync` shuts down a single pool cleanly.
* **Async lifecycle**: `closeAsync()` and `removeClientAsync()` return `Future<Void>`. Keep `close()` just for `AutoCloseable` interop.
* **Separate `getPool`**: lets you wire metrics/health directly to a specific pool where needed.
* **Validation**: fail fast on bad input.

## Follow-ups / cross-class contracts

* **`PgConnectionManager`** should expose:

  * `Pool getExistingPool(String clientId)` (no creation, just lookup).
  * `Future<Void> closePoolAsync(String clientId)` and `Future<Void> closeAsync()` for global close.
  * `Pool getOrCreateReactivePool(String clientId, PgConnectionConfig, PgPoolConfig)` should be idempotent and thread-safe (use `computeIfAbsent` under a concurrent map).
* Ensure **pool recreation** on config change is *explicit*, not implicit. If configs differ from existing, either:

  * reject with an error (“pool already exists with different config”), or
  * support a `recreateClient(...)` API that drains/awaits close and then builds a new pool.

## Quick wins elsewhere

* In `PeeGeeQManager`, stop calling `getOrCreateReactivePool` all over the place—fetch the single `Pool` via `getPool("peegeeq-main")` or, better, hold a `Pool` field when you create the client (as I suggested earlier).
* Make sure **`PgClient`** itself never stashes a `Pool` reference that can go stale if you ever rotate pools. Prefer resolving per operation via the manager, or document the contract that the pool is immutable for the client’s lifetime.

If you share `PgConnectionManager` next, I’ll check its concurrency and shutdown semantics (that’s where the hardest bugs usually hide).
