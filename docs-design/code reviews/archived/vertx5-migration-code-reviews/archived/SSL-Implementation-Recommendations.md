# PeeGeeQ SSL/TLS Implementation Recommendations

**Date:** 2026-04-04
**Scope:** All modules that create `PgConnectOptions` — `peegeeq-db`, `peegeeq-bitemporal`
**Vert.x version:** 5.0.10
**Status:** Proposed

---

## Current State

PeeGeeQ has **minimal SSL support**: a single `boolean sslEnabled` field in `PgConnectionConfig`, mapped to a hardcoded binary choice.

### What exists today

| Component | SSL logic |
|-----------|-----------|
| `PgConnectionConfig` | `sslEnabled` boolean (default `false`) |
| `PgConnectionManager.createReactivePool()` | `sslEnabled ? SslMode.REQUIRE : SslMode.DISABLE` |
| `PgDatabaseService` | Same binary pattern |
| `BiTemporalPoolFactory` | Same binary pattern |
| `PgBiTemporalEventStore` | Same binary pattern |
| `VertxPerformanceOptimizer` | Same binary pattern |
| Properties (`peegeeq-production.properties`) | `peegeeq.database.ssl.enabled=true` |
| Docs (`PEEGEEQ_COMPLETE_GUIDE.md`) | References `ssl.mode`, `ssl.cert`, `ssl.key`, `ssl.rootcert` — **none implemented** |

### What's missing

1. **No `SslMode` selection** — users cannot choose `PREFER`, `ALLOW`, `VERIFY_CA`, or `VERIFY_FULL`.
2. **No CA certificate configuration** — `VERIFY_CA` and `VERIFY_FULL` are unusable without trust material.
3. **No client certificate support** — mutual TLS (mTLS) is not possible.
4. **No `trustAll` escape hatch** — development with self-signed certs requires workarounds.
5. **Five places duplicate the same `if (sslEnabled)` → `REQUIRE` logic** — no single point of truth.

---

## Vert.x 5.x SSL API Surface

Vert.x provides two complementary APIs:

### `PgConnectOptions` (inherited from `SqlConnectOptions` → `NetClientOptions`)

```java
connectOptions.setSslMode(SslMode.VERIFY_CA);
```

Controls the PostgreSQL-level negotiation mode. Six values:
- `DISABLE` — no SSL
- `ALLOW` — try plain first, fall back to SSL
- `PREFER` — try SSL first, fall back to plain
- `REQUIRE` — require SSL, do not verify certificate
- `VERIFY_CA` — require SSL, verify server cert against trusted CA
- `VERIFY_FULL` — require SSL, verify CA + hostname match

### `ClientSSLOptions` (set via `connectOptions.setSslOptions(...)`)

```java
connectOptions.setSslOptions(
    new ClientSSLOptions()
        .setTrustAll(false)
        .setTrustOptions(new PemTrustOptions().addCertPath("/path/to/ca.pem"))
        .setKeyCertOptions(new PemKeyCertOptions()
            .setCertPath("/path/to/client-cert.pem")
            .setKeyPath("/path/to/client-key.pem"))
);
```

Controls the TLS handshake details:
- `setTrustAll(boolean)` — bypass certificate verification (dev only)
- `setTrustOptions(TrustOptions)` — CA cert(s) for server verification (`PemTrustOptions`, `JksOptions`, `PfxOptions`)
- `setKeyCertOptions(KeyCertOptions)` — client cert + key for mTLS (`PemKeyCertOptions`, `JksOptions`, `PfxOptions`)
- `setHostnameVerificationAlgorithm(String)` — `"HTTPS"` for hostname checking
- `setSslHandshakeTimeout(long)` / `setSslHandshakeTimeoutUnit(TimeUnit)`

---

## Recommended Changes

### Phase 1: Configuration Model

Extend `PgConnectionConfig` with SSL fields.

```java
public class PgConnectionConfig {
    // Existing
    private final boolean sslEnabled;

    // New
    private final String sslMode;        // "disable","allow","prefer","require","verify-ca","verify-full"
    private final boolean trustAll;       // dev/test only — bypass cert verification
    private final String caCertPath;      // PEM file path for server CA certificate
    private final String clientCertPath;  // PEM file path for client certificate (mTLS)
    private final String clientKeyPath;   // PEM file path for client private key (mTLS)
    ...
}
```

**Property mapping:**

| Property | Config field | Default | Notes |
|----------|-------------|---------|-------|
| `peegeeq.database.ssl.enabled` | `sslEnabled` | `false` | Master switch — already exists |
| `peegeeq.database.ssl.mode` | `sslMode` | `"require"` | Applied only when `sslEnabled=true` |
| `peegeeq.database.ssl.trust-all` | `trustAll` | `false` | Ignored when `sslEnabled=false` |
| `peegeeq.database.ssl.rootcert` | `caCertPath` | `null` | Required for `verify-ca` / `verify-full` |
| `peegeeq.database.ssl.cert` | `clientCertPath` | `null` | Optional — enables mTLS |
| `peegeeq.database.ssl.key` | `clientKeyPath` | `null` | Required when `clientCertPath` is set |

These property names are already documented in `PEEGEEQ_COMPLETE_GUIDE.md` but not yet parsed.

**Builder additions:**

```java
public static class Builder {
    private String sslMode = "require";
    private boolean trustAll = false;
    private String caCertPath;
    private String clientCertPath;
    private String clientKeyPath;

    public Builder sslMode(String sslMode) { this.sslMode = sslMode; return this; }
    public Builder trustAll(boolean trustAll) { this.trustAll = trustAll; return this; }
    public Builder caCertPath(String caCertPath) { this.caCertPath = caCertPath; return this; }
    public Builder clientCertPath(String clientCertPath) { this.clientCertPath = clientCertPath; return this; }
    public Builder clientKeyPath(String clientKeyPath) { this.clientKeyPath = clientKeyPath; return this; }
    ...
}
```

### Phase 2: Centralised SSL Application

Create a single static method that all pool-creation sites call:

```java
/**
 * Applies SSL configuration to PgConnectOptions based on PgConnectionConfig.
 * This is the single point of truth for SSL setup across all modules.
 */
public static void applySslConfig(PgConnectOptions connectOptions, PgConnectionConfig config) {
    if (!config.isSslEnabled()) {
        connectOptions.setSslMode(SslMode.DISABLE);
        return;
    }

    // Map string mode to enum
    SslMode mode = SslMode.of(config.getSslMode());  // "verify-ca" → VERIFY_CA etc.
    connectOptions.setSslMode(mode);

    ClientSSLOptions sslOptions = new ClientSSLOptions();

    // Trust configuration
    if (config.isTrustAll()) {
        sslOptions.setTrustAll(true);
    } else if (config.getCaCertPath() != null) {
        sslOptions.setTrustOptions(
            new PemTrustOptions().addCertPath(config.getCaCertPath())
        );
    }

    // Client certificate (mTLS)
    if (config.getClientCertPath() != null && config.getClientKeyPath() != null) {
        sslOptions.setKeyCertOptions(
            new PemKeyCertOptions()
                .setCertPath(config.getClientCertPath())
                .setKeyPath(config.getClientKeyPath())
        );
    }

    connectOptions.setSslOptions(sslOptions);
}
```

**Location:** `PgConnectionManager` or a new `PgSslConfigurer` utility class in `peegeeq-db`.

### Phase 3: Eliminate Duplication

Replace the five independent `if (sslEnabled) setSslMode(REQUIRE)` blocks with a single call to `applySslConfig()`:

| File | Current code | Replacement |
|------|-------------|-------------|
| `PgConnectionManager.createReactivePool()` | 4-line if/else | `applySslConfig(connectOptions, connectionConfig)` |
| `PgDatabaseService` | 4-line if/else | `applySslConfig(connectOptions, config)` |
| `BiTemporalPoolFactory` | 3-line if block | `applySslConfig(connectOptions, connectionConfig)` |
| `PgBiTemporalEventStore` | 3-line if block | `applySslConfig(connectOptions, connectionConfig)` |
| `VertxPerformanceOptimizer` | 3-line if block | `applySslConfig(connectOptions, connectionConfig)` |

---

## Validation Rules

Enforce at `PgConnectionConfig.build()` time:

1. `sslMode` must be one of: `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full` (case-insensitive).
2. If `sslMode` is `verify-ca` or `verify-full` and `trustAll` is `false`, then `caCertPath` must be non-null and point to an existing file.
3. If `clientCertPath` is set, `clientKeyPath` must also be set (and vice versa).
4. If `trustAll` is `true`, log a warning at pool creation time: *"trustAll=true bypasses certificate verification — not suitable for production."*
5. If `sslEnabled` is `false` but `sslMode` is explicitly set to something other than `disable`, log a warning about the conflict.

---

## Security Considerations

- **`trustAll` must never be used in production.** Consider rejecting it when a `production` profile property is detected, or at minimum emitting a WARN-level log on every pool creation.
- **Private key files** (`clientKeyPath`) should have restrictive file permissions. PeeGeeQ cannot enforce OS-level permissions, but should validate `Files.isReadable()` at config load time and document the requirement.
- **Certificate rotation:** Vert.x does not automatically reload certificates. For zero-downtime rotation, the pool must be recreated. Document this limitation and consider a future `reloadSsl()` method that drains and replaces the pool.
- **`SslMode.REQUIRE` does NOT verify the server certificate** — it only encrypts the connection. Production deployments handling sensitive data should use `VERIFY_CA` or `VERIFY_FULL`.

---

## Configuration Examples

### Development (self-signed certs)

```properties
peegeeq.database.ssl.enabled=true
peegeeq.database.ssl.mode=require
peegeeq.database.ssl.trust-all=true
```

### Staging (CA-signed, no hostname check)

```properties
peegeeq.database.ssl.enabled=true
peegeeq.database.ssl.mode=verify-ca
peegeeq.database.ssl.rootcert=/etc/peegeeq/certs/ca.pem
```

### Production (full verification + mTLS)

```properties
peegeeq.database.ssl.enabled=true
peegeeq.database.ssl.mode=verify-full
peegeeq.database.ssl.rootcert=/etc/peegeeq/certs/ca.pem
peegeeq.database.ssl.cert=/etc/peegeeq/certs/client-cert.pem
peegeeq.database.ssl.key=/etc/peegeeq/certs/client-key.pem
```

### Testcontainers (tests)

```properties
peegeeq.database.ssl.enabled=false
```

No SSL configuration needed — Testcontainers PostgreSQL does not enable SSL by default.

---

## Backward Compatibility

The existing `sslEnabled` boolean continues to work:
- `sslEnabled=true` without `sslMode` defaults to `SslMode.REQUIRE` (same as current behaviour).
- `sslEnabled=false` sets `SslMode.DISABLE` (same as current behaviour).
- All new fields are optional with safe defaults.
- No existing properties files or test configurations break.

---

## Test Plan

| Test | What it verifies |
|------|-----------------|
| Unit: `PgConnectionConfig` builder validation | Rejects invalid `sslMode`, missing `caCertPath` for `verify-ca`, mismatched cert/key |
| Unit: `applySslConfig()` | Correct `SslMode` / `ClientSSLOptions` for each mode, `trustAll` flag, cert paths |
| Unit: backward compat | `sslEnabled=true` with no other fields → `REQUIRE`, `sslEnabled=false` → `DISABLE` |
| Integration: SSL with Testcontainers | PostgreSQL container with SSL enabled, verify connection with `VERIFY_CA` |

The integration test for SSL with Testcontainers is optional and depends on container SSL setup complexity. Unit tests for the config model and `applySslConfig()` logic are sufficient for the initial implementation.

---

## Affected Files (Estimated)

| File | Change |
|------|--------|
| `PgConnectionConfig.java` | Add 5 fields, getters, builder methods, validation |
| `PgConnectionManager.java` | Replace if/else with `applySslConfig()` call |
| `PgDatabaseService.java` | Replace if/else with `applySslConfig()` call |
| `BiTemporalPoolFactory.java` | Replace if/else with `applySslConfig()` call |
| `PgBiTemporalEventStore.java` | Replace if/else with `applySslConfig()` call |
| `VertxPerformanceOptimizer.java` | Replace if/else with `applySslConfig()` call |
| `PeeGeeQConfiguration.java` | Parse new `peegeeq.database.ssl.*` properties |
| `PgConnectionConfigTest.java` | New validation tests |
| `PgConnectionManagerCoreTest.java` | Test `applySslConfig()` logic |

---

## References

- [Vert.x PG Client — Using SSL/TLS](https://vertx.io/docs/vertx-pg-client/java/#_using_ssl_tls)
- [Vert.x `SslMode` enum](https://vertx.io/docs/apidocs/io/vertx/pgclient/SslMode.html)
- [Vert.x `ClientSSLOptions`](https://vertx.io/docs/apidocs/io/vertx/core/net/ClientSSLOptions.html)
- [PostgreSQL libpq SSL modes](https://www.postgresql.org/docs/current/libpq-ssl.html#LIBPQ-SSL-PROTECTION)
- Existing property names in `PEEGEEQ_COMPLETE_GUIDE.md` lines 4638–4642
