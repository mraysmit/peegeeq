# HAProxy PostgreSQL Routing Without Patroni

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Date**: May 10, 2026  
**Status**: REFERENCE  
**Module**: `peegeeq-service-manager`

---

## 1. The Core Problem

HAProxy is a battle-tested TCP/HTTP load balancer commonly placed in front of a PostgreSQL primary-replica pair to give the application a single, stable connection endpoint that survives a node failure.

`option pgsql-check` and plain TCP checks both verify that PostgreSQL is **alive**.
Both the primary and any replica pass these checks.
Neither tells HAProxy which node is the **write primary**.

Only something that queries `SELECT pg_is_in_recovery()` can distinguish them:

| Node role | `pg_is_in_recovery()` | HAProxy should… |
|-----------|----------------------|-----------------|
| Primary (read-write) | `f` | Route writes here |
| Standby / replica | `t` | Block writes / mark backup |

This is precisely what Patroni's `/primary` HTTP endpoint does internally — it calls
`pg_is_in_recovery()` and returns HTTP 200 for primary, HTTP 503 for replica.

---

## 2. What Patroni Does

Understanding what Patroni provides is necessary context for evaluating the alternatives.

Patroni is a Python daemon that runs on each PostgreSQL node and does three distinct things:

### 2.1 Leader Election via a Distributed Data Store (DCS)

Patroni stores the identity of the current primary in an external DCS — etcd, Consul, or
ZooKeeper.  Each Patroni instance holds a **session-based lock** (a TTL key in etcd, or a
Consul session + KV key).  The primary continuously renews that lock.  When the primary fails:

1. The lock TTL expires.
2. The standby with the most up-to-date WAL position races to acquire the lock.
3. The winner of the race is authorised to promote.

This is the property the alternatives in §3 either replicate (Consul-based monitor), partially
replicate (repmgr with witness), or omit entirely (custom HTTP sidecar).

### 2.2 Automatic Promotion

Once a Patroni standby wins the DCS lock it calls:

```
pg_promote()          ← SQL, PostgreSQL 12+
  or
pg_ctl promote        ← shell command, older PostgreSQL
```

It then updates the DCS entry to announce itself as the new primary.

### 2.3 HTTP Status Endpoint for HAProxy

Patroni runs a small REST API on each node (default port 8008).

| Endpoint | HTTP 200 | HTTP 503 |
|----------|----------|----------|
| `/primary` | This node is the write primary | This node is not the primary |
| `/replica` | This node is a healthy replica | This node is not a replica |
| `/health` | Node is running | Node is unhealthy |

HAProxy uses `option httpchk GET /primary` to query this endpoint.  Servers that return 503
are taken out of rotation.  This is the only part of Patroni that HAProxy interacts with.

### 2.4 Fencing the Old Primary

When the old primary comes back after a crash it must be prevented from accepting writes until
it re-syncs.  Patroni handles this by:

- Refusing to start PostgreSQL on the old primary until it has confirmed with the DCS that
  another node now holds the leader lock.
- Running `pg_rewind` automatically to align the old primary's WAL with the new primary's
  timeline before starting replication.

**This is the hardest part to replicate without Patroni.**  Without it, a recovered old
primary can accept writes concurrently with the new primary, causing data divergence.

### 2.5 Summary of What Patroni Provides

| Capability | Patroni provides | Notes |
|------------|-----------------|-------|
| HAProxy routing signal (`/primary` HTTP) | Yes | Trivially replicable with a sidecar |
| Automatic promotion | Yes | Requires DCS or equivalent |
| DCS-backed leader election (no split-brain) | Yes | Core value of Patroni |
| Automatic fencing / pg_rewind on re-join | Yes | Hardest to replicate |
| Monitoring REST API (`/patroni`, `/history`) | Yes | Nice-to-have, not critical for routing |

---

## 3. Options Without Patroni

### 3.1 Custom HTTP Sidecar (Patroni-Equivalent, Minimal)

Patroni's `/primary` endpoint is not complex.  The logic is:

```
GET /primary
  → SELECT pg_is_in_recovery()
  → false  → HTTP 200  (this node is the write primary)
  → true   → HTTP 503  (this node is a replica)
```

You can replicate this with a small process on each PostgreSQL node.  Since PeeGeeQ is a
Vert.x project, a Java implementation is the natural fit: no extra language runtime,
packaged as a standard fat-jar or native image, consistent with the rest of the codebase.

**Java / Vert.x implementation** (runs on each node, listens on port 8008):

```java
// PgPrimaryCheckVerticle.java — deploy as a standalone Vert.x verticle
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
                                .onSuccess(rows -> {
                                    boolean isReplica = rows.iterator().next().getBoolean(0);
                                    req.response().setStatusCode(isReplica ? 503 : 200).end();
                                })
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

**Main launcher** (for running as a standalone process):

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

**Run**:

```bash
java -Dpg.host=localhost -Dpg.port=5432 -Dpg.user=haproxy_check \
     -jar pg-primary-check.jar
```

**Why Java over Python here**:
- No additional language runtime on the node — the JVM is already required for PeeGeeQ.
- Uses `io.vertx.pgclient` (same driver as PeeGeeQ) — consistent behaviour, no new
  PostgreSQL dependency.
- **Built as a GraalVM native image** for production deployments: instant startup (~10 ms),
  minimal memory footprint (~15 MB RSS), no JVM on the node required.  The fat-jar form
  is retained for development and debugging.  See §7.3 for the native build command.
- The `haproxy_check` user needs no password and only the `SELECT pg_is_in_recovery()`
  privilege — same as the Python version.

**HAProxy config** (identical regardless of sidecar language):

```haproxy
backend pg_write
    option httpchk GET /primary
    server pg1 pg1:5432 check port 8008 inter 500ms fall 2 rise 1
    server pg2 pg2:5432 check port 8008 backup inter 500ms fall 2 rise 1
```

This is functionally identical to what Patroni exposes.  The HTTP check and HAProxy
configuration are the same; the only thing absent is the DCS-backed leader election that
Patroni adds on top.

**Deployment note**: this verticle must run as a sidecar on each PostgreSQL host (or
container).  In a container environment it can run in a sidecar container sharing the
network namespace with PostgreSQL, or as a second process in the same container managed by
a simple process supervisor (e.g. `s6`, `supervisord`).

---

### 3.2 HAProxy `agent-check` (TCP, No HTTP Needed)

HAProxy has a dedicated `agent-check` mechanism.  An agent process listens on a TCP port;
HAProxy connects, sends nothing, and reads a single text response:

| Agent response | HAProxy action |
|----------------|----------------|
| `up\n` | Mark server UP, route traffic |
| `down\n` | Mark server DOWN, stop routing |
| `drain\n` | Drain existing connections, stop new ones |

**Minimal shell agent** (served via `xinetd` or `socat` on port 9000 of each node):

```bash
#!/bin/bash
# pg_agent.sh
result=$(psql -U haproxy_check -Atc "SELECT pg_is_in_recovery()")
if [ "$result" = "f" ]; then
    echo "up"
else
    echo "down"
fi
```

**`socat` wrapper** to expose the script on a TCP port:

```bash
socat TCP-LISTEN:9000,fork,reuseaddr EXEC:/opt/pg_agent.sh
```

**HAProxy config**:

```haproxy
backend pg_write
    server pg1 pg1:5432 check agent-check agent-port 9000 agent-inter 500ms
    server pg2 pg2:5432 check agent-check agent-port 9000 agent-inter 500ms backup
```

The `agent-check` and TCP health check run independently.  Both must pass for HAProxy to
consider a server available.

---

### 3.3 `repmgr` + `repmgrd` (Automated Promotion, No DCS)

`repmgr` is the traditional PostgreSQL replication manager.  The `repmgrd` daemon handles
automatic failover without requiring an external distributed data store (etcd, Consul,
ZooKeeper).

Key characteristics:
- `repmgrd` runs on each PostgreSQL node.
- It monitors the primary and triggers promotion scripts when the primary is unresponsive.
- A **witness server** (a lightweight third node, not a full PostgreSQL replica) is
  recommended to provide quorum and prevent split-brain; without it a two-node cluster
  has the same split-brain exposure as any other two-node setup.
- `repmgr` does not expose an HTTP endpoint compatible with `option httpchk`; HAProxy
  integration still requires one of the sidecar approaches above.
- Automatic failover is optional — `repmgr` can be used for monitoring and manual failover
  only.

---

## 4. Comparison

| Approach | Complexity | Extra process required | Automatic promotion | Split-brain safe |
|----------|------------|------------------------|--------------------|--------------------|
| Custom HTTP sidecar (§3.1) | Very low | Yes — `peegeeq-pg-sidecar` (Vert.x Java fat-jar) | No | No |
| HAProxy `agent-check` (§3.2) | Low | Yes — shell + socat/xinetd | No | No |
| repmgr + repmgrd (§3.3) | Medium | Yes — repmgrd daemon | Yes (with witness) | Partial |
| Consul-based monitor (see `PG_FAILOVER_CONSUL_DESIGN.md`) | Medium | No (uses existing Consul) | Yes | Yes (with fencing) |
| Patroni | High | Yes — Patroni + DCS | Yes | Yes |

---

## 5. What Each Option Provides and Lacks

### Custom HTTP sidecar / `agent-check`

**Provides**:
- HAProxy routes writes only to the current primary — correctly.
- Identical behaviour to Patroni from HAProxy's perspective.
- No new runtime dependencies.

**Does not provide**:
- Automatic promotion of the standby when the primary fails.
- Split-brain prevention — if you promote manually and the old primary recovers, both
  nodes could accept writes until the operator intervenes.

**Appropriate when**:
- Promotion is manual (operator decides when to promote).
- Downtime during primary failure is acceptable until operator action.
- You want the simplest possible routing-correctness fix.

### `repmgr` + `repmgrd`

**Provides**:
- Automatic promotion without Patroni.
- History of switchovers/failovers stored in `repmgr` schema.

**Does not provide**:
- Consul/etcd-backed quorum (uses its own TCP-based primary check).
- Guaranteed split-brain safety without a witness node.

**Appropriate when**:
- You want automatic promotion without adding Consul or etcd.
- You can provision a third node (witness) for quorum.

### Consul-based monitor (PeeGeeQ-native)

See `PG_FAILOVER_CONSUL_DESIGN.md` for the full design.  This is the preferred path for
PeeGeeQ because Consul is already present in `peegeeq-service-manager` and the integration
is fully reactive (Vert.x `io.vertx.ext.consul.ConsulClient`).

---

## 6. Recommendation for PeeGeeQ

| Scenario | Recommended approach |
|----------|----------------------|
| Development / single-node | `option pgsql-check` already in HAProxy test config — sufficient |
| Production, manual failover acceptable | Custom HTTP sidecar (§2.1) + HAProxy `httpchk` |
| Production, automatic failover required | Consul-based monitor (`PG_FAILOVER_CONSUL_DESIGN.md`) |
| Production, no Consul, 3 nodes available | `repmgr` + witness node |

---

## 7. Building the Java Sidecar

The sidecar is implemented in `peegeeq-pg-sidecar`. Two deployment artefacts are supported:
a **fat-jar** (JVM) and a **native binary** (GraalVM). Both expose the same `/primary`
endpoint and accept the same system property configuration.

### 7.1 Prerequisites

| Artefact | Requirement |
|----------|-------------|
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

### 7.2 Build the Fat-Jar (JVM)

```powershell
cd c:\Users\mraysmit\dev\idea-projects\peegeeq
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

### 7.3 Build the Native Binary (GraalVM)

```powershell
cd c:\Users\mraysmit\dev\idea-projects\peegeeq
mvn package -Pnative -pl :peegeeq-pg-sidecar -DskipTests 2>&1 | Tee-Object -FilePath logs\pg-sidecar-native.txt
```

The build takes 2–5 minutes. Output:

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

### 7.4 Container Image (Native Binary, Distroless)

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

Note: system properties (`-D`) do not pass through environment variables automatically in a
native binary. If running in a container, you can either set the properties in `ENTRYPOINT`
or add a small config-from-env layer to `PgPrimaryCheckMain`.

### 7.5 PostgreSQL User Setup

The `haproxy_check` user requires no password and only the minimum privilege needed to call
`pg_is_in_recovery()`:

```sql
CREATE USER haproxy_check WITH PASSWORD '' CONNECTION LIMIT 3;
-- pg_is_in_recovery() is a built-in function; no GRANT is needed.
-- Optionally restrict to the postgres database only:
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
GRANT  CONNECT ON DATABASE postgres TO haproxy_check;
```

### 7.6 Verify the Endpoint

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

*End of document.*
