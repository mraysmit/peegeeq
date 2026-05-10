# peegeeq-pg-sidecar — Design, Build, and Operations Guide

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Date**: May 10, 2026  
**Status**: COMPLETE  
**Module**: `peegeeq-pg-sidecar`

---

## Overview

`peegeeq-pg-sidecar` is a lightweight Vert.x HTTP process that runs alongside each
PostgreSQL node.  It exposes a single endpoint:

```
GET /primary
  → HTTP 200  if this node is the write primary  (pg_is_in_recovery() = false)
  → HTTP 503  if this node is a replica or unreachable
```

HAProxy uses `option httpchk GET /primary` to query this endpoint on each backend node.
Only the true write primary returns 200; HAProxy routes all connections to that node.
This gives correct write routing without Patroni.

For the full context of why this is needed — TCP health checks, the HAProxy `pgsql-check`
limitation, and the complete comparison of resiliency options — see:

- [PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md](../../peegeeq-service-manager/docs/PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md)
- [PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md](../../docs-design/tasks/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md)

---

## Table of Contents

1. [Why a Sidecar?](#1-why-a-sidecar)
2. [Design](#2-design)
3. [HAProxy Configuration](#3-haproxy-configuration)
4. [Prerequisites](#4-prerequisites)
5. [Build the Fat-Jar (JVM)](#5-build-the-fat-jar-jvm)
6. [Build the Native Binary (GraalVM)](#6-build-the-native-binary-graalvm)
7. [Container Image (Native Binary, Distroless)](#7-container-image-native-binary-distroless)
8. [PostgreSQL User Setup](#8-postgresql-user-setup)
9. [Verify the Endpoint](#9-verify-the-endpoint)
10. [Deployment Notes](#10-deployment-notes)

---

## 1. Why a Sidecar?

HAProxy's built-in `option pgsql-check` and plain TCP checks verify that PostgreSQL is
**alive** — but both the primary and any replica pass these checks.  Neither tells HAProxy
which node is the **write primary**.

Only something that queries `SELECT pg_is_in_recovery()` can distinguish them:

| Node role | `pg_is_in_recovery()` | HAProxy should… |
|---|---|---|
| Primary (read-write) | `f` | Route writes here |
| Standby / replica | `t` | Block writes / mark `backup` |

This sidecar implements exactly that check as a minimal HTTP endpoint, giving HAProxy the
information it needs without requiring Patroni.

**Why Vert.x Java, not Python or shell**:

- No additional language runtime on the node.
- Uses `io.vertx.pgclient` — the same reactive driver PeeGeeQ already uses — so the
  behaviour is consistent and there are no new PostgreSQL dependencies.
- **Built as a GraalVM native image** for production deployments: instant startup (~10 ms),
  minimal memory footprint (~15 MB RSS), no JVM on the node required.  The fat-jar form
  is retained for development and debugging.
- The `haproxy_check` user needs no password and only the `SELECT pg_is_in_recovery()`
  privilege.

---

## 2. Design

### Verticle: `PgPrimaryCheckVerticle`

```java
// Exposes GET /primary → 200 if write primary, 503 if replica or unreachable
public class PgPrimaryCheckVerticle extends AbstractVerticle {

    private static final int HTTP_PORT = 8008;

    @Override
    public void start(Promise<Void> start) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(config().getString("pg.host", "localhost"))
                .setPort(config().getInteger("pg.port", 5432))
                .setDatabase(config().getString("pg.database", "postgres"))
                .setUser(config().getString("pg.user", "haproxy_check"))
                .setPassword(config().getString("pg.password", ""));

        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(2))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        vertx.createHttpServer()
                .requestHandler(req -> {
                    if ("/primary".equals(req.path())) {
                        pool.query("SELECT pg_is_in_recovery()")
                                .execute()
                                .compose(rows -> {
                                    boolean isReplica = rows.iterator().next().getBoolean(0);
                                    return Future.succeededFuture(isReplica ? 503 : 200);
                                })
                                .onSuccess(statusCode -> req.response().setStatusCode(statusCode).end())
                                .onFailure(err -> req.response().setStatusCode(503).end());
                    } else {
                        req.response().setStatusCode(404).end();
                    }
                })
                .listen(HTTP_PORT)
                .<Void>mapEmpty()
                .onComplete(start);
    }
}
```

### Launcher: `PgPrimaryCheckMain`

```java
public class PgPrimaryCheckMain {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        JsonObject config = new JsonObject()
                .put("pg.host",     System.getProperty("pg.host", "localhost"))
                .put("pg.port",     Integer.getInteger("pg.port", 5432))
                .put("pg.database", System.getProperty("pg.database", "postgres"))
                .put("pg.user",     System.getProperty("pg.user", "haproxy_check"))
                .put("pg.password", System.getProperty("pg.password", ""));

        vertx.deployVerticle(new PgPrimaryCheckVerticle(),
                new DeploymentOptions().setConfig(config));
    }
}
```

### Configuration properties

| System property | Default | Description |
|---|---|---|
| `pg.host` | `localhost` | PostgreSQL host |
| `pg.port` | `5432` | PostgreSQL port |
| `pg.database` | `postgres` | Database name |
| `pg.user` | `haproxy_check` | PostgreSQL user |
| `pg.password` | _(empty)_ | PostgreSQL password |
| `http.port` | `8008` | HTTP port the sidecar listens on |

---

## 3. HAProxy Configuration

```haproxy
backend pg_write
    option httpchk GET /primary
    server pg1 pg1:5432 check port 8008 inter 500ms fall 2 rise 1
    server pg2 pg2:5432 check port 8008 backup inter 500ms fall 2 rise 1
```

- `inter 500ms` — check every 500 ms.
- `fall 2` — mark a server down after 2 consecutive failures (~1 s detection time).
- `rise 1` — mark a server up after 1 successful check (fast recovery).
- `backup` on `pg2` — traffic goes to pg2 only when pg1 is marked down.

This configuration is functionally identical to what Patroni exposes from HAProxy's
perspective.  The only thing absent is the DCS-backed leader election that Patroni adds on
top.

---

## 4. Prerequisites

| Artefact | Requirement |
|---|---|
| Fat-jar | JDK 21+ (any distribution) |
| Native binary | GraalVM JDK 21+ with `native-image` installed |

Install GraalVM and `native-image` (one-time):

```powershell
# Option A — SDKMAN (Linux / macOS / WSL)
sdk install java 21.0.3-graalce
gu install native-image

# Option B — Winget (Windows, native GraalVM distribution)
winget install GraalVM.GraalVM.Community.21

# Option C — download directly from https://www.graalvm.org/downloads/
# Then add GRAALVM_HOME/bin to PATH and run:
gu install native-image
```

Verify:

```powershell
java -version              # should show GraalVM
native-image --version     # should print GraalVM native-image version
```

---

## 5. Build the Fat-Jar (JVM)

```powershell
cd <workspace-root>
mvn package -pl :peegeeq-pg-sidecar -DskipTests 2>&1 | Tee-Object -FilePath logs\pg-sidecar-build.txt
```

Output: `peegeeq-pg-sidecar/target/peegeeq-pg-sidecar-1.0-SNAPSHOT.jar`

Run:

```powershell
java `
  -Dpg.host=localhost `
  -Dpg.port=5432 `
  -Dpg.database=postgres `
  -Dpg.user=haproxy_check `
  -Dpg.password= `
  -Dhttp.port=8008 `
  -jar peegeeq-pg-sidecar/target/peegeeq-pg-sidecar-1.0-SNAPSHOT.jar
```

---

## 6. Build the Native Binary (GraalVM)

```powershell
cd <workspace-root>
mvn package -Pnative -pl :peegeeq-pg-sidecar -DskipTests 2>&1 | Tee-Object -FilePath logs\pg-sidecar-native.txt
```

The build takes 2–5 minutes.  Output:

- Windows: `peegeeq-pg-sidecar/target/peegeeq-pg-sidecar.exe`
- Linux/macOS: `peegeeq-pg-sidecar/target/peegeeq-pg-sidecar`

Run (Windows):

```powershell
.\peegeeq-pg-sidecar\target\peegeeq-pg-sidecar.exe `
  -Dpg.host=localhost `
  -Dpg.port=5432 `
  -Dpg.user=haproxy_check `
  -Dpg.password= `
  -Dhttp.port=8008
```

Run (Linux/macOS):

```bash
./peegeeq-pg-sidecar/target/peegeeq-pg-sidecar \
  -Dpg.host=localhost \
  -Dpg.port=5432 \
  -Dpg.user=haproxy_check \
  -Dpg.password= \
  -Dhttp.port=8008
```

---

## 7. Container Image (Native Binary, Distroless)

Build a minimal container image from the native binary using a two-stage Dockerfile.
The final image contains only the binary — no JVM, no shell, no package manager.

```dockerfile
# Stage 1: build the native binary
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /build
COPY . .
RUN mvn package -Pnative -pl :peegeeq-pg-sidecar -DskipTests

# Stage 2: distroless runtime — minimal attack surface, ~10 MB image
FROM gcr.io/distroless/base-debian12
COPY --from=builder /build/peegeeq-pg-sidecar/target/peegeeq-pg-sidecar /app/peegeeq-pg-sidecar
EXPOSE 8008
ENTRYPOINT ["/app/peegeeq-pg-sidecar"]
```

Build and run:

```bash
docker build -t peegeeq-pg-sidecar:latest .

docker run --rm \
  -e pg.host=db-node-1 \
  -e pg.port=5432 \
  -e pg.user=haproxy_check \
  -e pg.password= \
  -p 8008:8008 \
  peegeeq-pg-sidecar:latest
```

> **Note**: system properties (`-D`) do not pass through environment variables automatically
> in a native binary.  If running in a container, either set the properties in `ENTRYPOINT`
> or add a small config-from-env layer to `PgPrimaryCheckMain`.

---

## 8. PostgreSQL User Setup

The `haproxy_check` user requires no password and only the minimum privilege needed to call
`pg_is_in_recovery()`:

```sql
CREATE USER haproxy_check WITH PASSWORD '' CONNECTION LIMIT 3;
-- pg_is_in_recovery() is a built-in function; no GRANT is needed.
-- Optionally restrict to the postgres database only:
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
GRANT  CONNECT ON DATABASE postgres TO haproxy_check;
```

---

## 9. Verify the Endpoint

```powershell
# While the sidecar is running:
Invoke-WebRequest -Uri http://localhost:8008/primary -Method GET | Select-Object StatusCode
# Primary node  → StatusCode 200
# Replica node  → StatusCode 503
```

Or with `curl`:

```bash
curl -o /dev/null -s -w "%{http_code}\n" http://localhost:8008/primary
```

---

## 10. Deployment Notes

- The sidecar must run **on each PostgreSQL host** (or container).
- In a container environment it can run as a sidecar container sharing the network namespace
  with PostgreSQL, or as a second process in the same container managed by a process
  supervisor such as `s6` or `supervisord`.
- The native binary is the recommended production artefact.  It starts in ~10 ms and
  uses ~15 MB RSS — negligible overhead on a database host.
- The fat-jar is convenient for development: `mvn package -pl :peegeeq-pg-sidecar -DskipTests`
  and run directly with `java -jar`.

---

*End of document.*
