# peegeeq-pg-sidecar — Design, Build, and Operations Guide

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Design revision**: 2026-10-08, Patroni-style local supervision, coordinator port, optional watchdog
**Module**: `peegeeq-pg-sidecar`

## 1. Purpose and Scope

This document defines `peegeeq-pg-sidecar`, the service that reports whether a PostgreSQL node
is eligible to receive PeeGeeQ writer traffic. It is for developers implementing the service
and operators configuring its database access, HTTP endpoints, security, and packaging.
The guide describes the target contract for automatic profile A and manual profile B.
Build examples and verification requirements are not claims that the complete contract ships.

**Why the service exists.** The intended deployment has one sidecar beside each PostgreSQL
node. Applications use a stable SQL endpoint through HAProxy. HAProxy needs a per-node answer
that establishes more than a reachable database listener. A primary is a database in the
writable role. A standby follows the primary's replicated changes. A former primary can retain
its writable role while losing permission to serve applications. The sidecar must distinguish
that node from the currently authorised writer.

**What eligibility means.** The sidecar combines five kinds of evidence: the configured node
identity and live writable role; current profile authority naming that node; an open local
writer grant matching the authority; live coverage from the required synchronous standbys; and a fresh lease plus the watchdog state the selected mode requires.
The **grant** is local permission to admit application writes. **Fencing** excludes an obsolete
writer before ownership transfers. Each node's PeeGeeQ supervisor owns its lease and local
PostgreSQL lifecycle. Lease loss triggers local stop; an optional independent watchdog protects against
supervisor failure. The sidecar observes the lease through the coordinator port, and the grant and watchdog state, read-only.

In both profiles, the coordinator supplies node-supervisor ownership and serving intent. In B, an
operator requests takeover; autonomous promotion is disabled. Manual mode does
not mean that every writable database is eligible. Both modes require the matching open grant,
live role and identity checks, and the required durability coverage. Missing or uncertain
evidence makes the node ineligible. The sidecar never returns an earlier success as a fallback.
The sidecar reads authority through the coordinator port (system design §5.10) and has no
dependency on a coordinator product. Consul is the first adapter. Every adapter supplies an
authoritative read and a generation; a local leader flag or unconditional key lookup is not writer permission.

Synchronous coverage refers to the standbys required by the commit policy. They must
acknowledge flush of the write-ahead log (WAL), the database record of changes, before
required writes are confirmed. A writable node without that coverage cannot report eligible.

**How the endpoints are used.** The two endpoints serve different readers of the same check:

| Endpoint | Reader | Purpose |
|---|---|---|
| `GET /primary` | HAProxy and authorised operators | Return 200 only when this node can receive writer traffic; return 503 when eligibility cannot be established |
| `GET /writer` | Authenticated PeeGeeQ clients through a redundant HTTP endpoint | Apply the same live predicate and return the eligible node, cluster, generation, operation, and policy identity as JSON |

For a new SQL connection, HAProxy uses its scheduled `/primary` check results to select an
backend from its latest successful observation and configured health state. It does not issue
a fresh check for each SQL connection. The SQL connection terminates at PostgreSQL. SQL traffic
does not pass through the sidecar. A dedicated LISTEN client also obtains `/writer` status and compares it
with the identity and recovery mode observed on its actual database connection. A LISTEN
connection subscribes to notification channels and must remain attached to the eligible writer.
Clients receive observation access; they receive no coordinator or node-control mutation credentials.

During a transition from node 1 to node 2, node 1 must become ineligible when its authority or
grant is withdrawn. Node 2 remains ineligible while promotion, synchronous policy validation,
writer preparation, or activation is incomplete. It can return 200 only after all conditions
agree. When HAProxy marks a backend down, its configured session shutdown drives clients to
reconnect. Local self-demotion/watchdog enforcement excludes the old writer before lease handover despite
delayed proxy observations. Sidecar reporting cannot itself stop PostgreSQL. A failed host need
not acknowledge a remote stop in the qualified expiry path.

First-start bootstrap keeps sidecars ineligible through initial intent, fences, guarded starts,
and first policy confirmation. A missing confirmed policy returns 503. Only matching serving
intent and an open grant with live coverage can permit 200 after bootstrap. The sidecar never
creates initial intent. See
[the bootstrap protocol](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#511-first-start-bootstrap).

**Scope and operational requirements.** The guide covers endpoint responses, HAProxy
integration, explicit mode and identity configuration, least-privilege observation credentials,
authentication, request deadlines, startup, shutdown, JVM packaging, native packaging
qualification, and real-component verification. Each request has a deadline covering all
eligibility dependencies. Failed dependencies deny eligibility and remain visible. Production
uses redundant SQL and status endpoints. Observe resource-close results during shutdown.

Promotion, fencing, rewind, grant mutation, and standby admission belong to the node-local supervisor. Their protocols are in [the coordinator and supervision design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md).
Application pools, transaction outcomes, LISTEN reconnection, and durable catch-up belong to
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md). A successful sidecar response
does not by itself establish that an application subscription has finished recovery.

**Reading guide.** Section 1.1 defines the data and endpoint contract. Sections 2 and 3 define
responsibilities and routing integration. Read §4 before configuring a deployment and §6
before provisioning credentials. Sections 5 and 7 cover packaging, lifecycle, and verification.
Use [the implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md)
for local-supervisor implementation and watchdog/admission qualification gates.

### 1.1 Data Model and Endpoint Contract

The canonical contracts are in
[the system design §1.1](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#11-data-model-and-safety-contracts).

| Information | Source of truth or derivable | Contract |
|---|---|---|
| Mode, cluster ID, incarnation, node ID, database address, and secret references | Authoritative deployment configuration | Explicit `automatic` or `manual` mode. Missing identity or mode rejects startup. |
| Current writer and generation in both modes | Authoritative control record and coordinator metadata | Authoritative read through the port. Lease holder present, phase `SERVING`, writer ID equal to this node. |
| Local writer grant and quarantine | Authoritative node-local supervisor permission | Require matching open grant. Loaded grants start closed after supervisor restart. |
| Confirmed and pending durability policy | Authoritative control-record transition intent in both modes | Pending membership never establishes target coverage. |
| Node-local identity, recovery mode, read-only session setting, and synchronous peer state | Derived live database observations | Match `peegeeq.node_id` to configured node identity. Do not substitute a cached primary or ready flag. |
| Lease freshness, watchdog health, routing eligibility, and response | Derived live observations | Require current safe ownership and a watchdog state that satisfies the selected mode. No persisted deadline, watchdog-ready flag, or independent writer authority. |

```text
GET /primary
  200: writable node, OPEN matching local grant, current lease, watchdog state under the selected mode, synchronous coverage
  503: standby, quarantine, missing/mismatched permission or coverage, dependency failure/deadline
GET /writer
  Same checks. Authenticated 200 returns the derived writer-status JSON described below.
  Unauthenticated or unauthorised callers receive 401 or 403.
Other paths: 404
```

Both modes require an authoritative coordinator read naming this node under
a live lease in phase `SERVING`. A retained value on an unowned control record is not authority.
No cached 200 is served when the authority read fails.

`GET /writer` returns `profile`, `clusterId`, `incarnation`, `nodeId`, `generation`,
`operationId`, and `policyRevision`. The generation contains the control record name, the coordinator generation, and the lease
holder. Compute the response
from current authority, matching open local supervisor grant, SQL identity/role, and confirmed policy
coverage. Do not store another authority record. Both endpoints call the same eligibility
check. Responses use `Cache-Control: no-store`. HTTP proxies must not cache successful responses.

The application reads its actual connection's `peegeeq.node_id` and recovery mode, then
compares them with this endpoint. It needs no PostgreSQL host list, coordinator token, or provider
mutation credential. A prepared grant, published serving intent without activation, mismatched
policy revision, pending policy alone, or unavailable required peer returns 503.

Manual mode requires an authenticated operator request. The requested node still acquires the
writer lease and applies its watchdog mode. Qualified expiry permits takeover without a former-host
reply. Planned release requires confirmed local stop. Returning old primaries stay quarantined
through rewind/rebuild. Disabling autonomous promotion does not remove ownership enforcement.

Both modes use the same node-local supervisor and lease generation. Ownership-sensitive effects
are serialised locally. The winning node persists withdrawn intent, applies its watchdog mode, reconciles
local effects, promotes or bootstraps, confirms policy, prepares, publishes, and activates.
There is no cluster-wide generation-installation barrier or separate provider-owned manual
authority. See [the local supervision contract](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md#4-node-control-provider-contract).

The complete check has one end-to-end deadline. It covers pool acquisition, SQL, the coordinator read, node
admission observation, and HTTP completion. An unavailable dependency returns 503 within that
deadline. Observe response and cleanup Futures. Log dependency failures with their causes.
No exception is converted into an eligible result.

## 2. Responsibilities

The sidecar reports writer eligibility. It never promotes, fences, rewinds, or releases
quarantine. The node-local supervisor owns those actions. An active independent watchdog adds protection for a supervisor that cannot act.

`pg_is_in_recovery() = false` establishes local role. It does not establish authority or prove
that another primary is absent. TCP and HAProxy `pgsql-check` do not establish writer role.

The sidecar uses Vert.x and the reactive PostgreSQL client. The JVM jar is the development
artifact. A GraalVM native artifact is a production packaging option after build, startup,
configuration, and failure behaviour are verified. Startup time, memory, and image-size
claims require measurements on the deployed artifact.

The node-local supervisor is in the `peegeeq-pg-failover` module. It has no dependency on PeeGeeQ
federation in `peegeeq-service-manager`.

## 3. HAProxy

The system design §5.3 owns the complete configuration. The essential backend contract is:

```haproxy
backend pg_primary
    option httpchk GET /primary
    http-check expect status 200
    default-server check port 8008 inter 500ms fall 2 rise 1 on-marked-down shutdown-sessions
    server pg-node-1 pg-node-1:5432
    server pg-node-2 pg-node-2:5432
    server pg-node-3 pg-node-3:5432
```

The full production topology has one primary and two synchronous standbys. A two-node pair
has no committed-write recovery target until a synchronous peer is restored.

No fixed backup preference selects the old primary after recovery. Lease-timed local self-demotion, with watchdog protection where active, excludes obsolete writers despite
inconsistent proxy observations. Sidecar health checks alone do not provide
fencing. Measure request duration and scheduling before claiming a detection deadline.
Session shutdown occurs when failed checks mark the backend down, not at the instant authority
or role changes. Test stale successful observations on both proxies while grants are revoked.
Every allowed write route must still respect local self-demotion/watchdog enforcement during that delay.

Production uses redundant proxies behind a tested stable endpoint. Sidecar check addresses
refer to the corresponding database node. Test containers obtain addresses and mapped ports
from the live containers. They do not use fixed test HTTP ports.

Provide a separate redundant HTTP frontend for the client's `/writer` endpoint. Its backends
are sidecar HTTP ports, not PostgreSQL ports. Route using the same `/primary` checks and
perform the full live check again for `/writer`. A stale route must not bypass authority.
Use authenticated encrypted access for application status requests. Keep health-check access
private to authorised proxies. When PgBouncer is enabled, its layer and client endpoint are
also redundant. The system design §8 owns that topology.

## 4. Configuration

These are target contracts. New properties require loader, default, contract-test, and
configuration-guide changes before they are described as shipped.

| Property | Default | Contract |
|---|---|---|
| `pg.host`, `pg.port` | `localhost`, `5432` | Local database endpoint |
| `pg.database`, `pg.user` | `postgres`, `haproxy_check` | Least-privilege observation |
| `pg.password` | deployment secret | Authentication appropriate to the deployment |
| `pg.query-timeout-ms` | `1000` | Bounds the complete eligibility request, not just SQL execution |
| `http.port` | `8008` | Production check port; tests request an available port |
| `pg.sidecar.mode` | required | `automatic` or `manual`; no implicit fallback |
| `peegeeq.pg.cluster-id`, `peegeeq.pg.cluster-incarnation` | required | Same deployment namespace as the controller |
| `peegeeq.pg.node-id` | required | Stable node-local identity, not PostgreSQL system identifier |
| `peegeeq.pg.coordinator.type` | required | Same coordinator adapter as the supervisor. The sidecar uses the port's read only |
| `peegeeq.pg.node-control.provider` | `local-supervisor` | Local grant, quarantine, safe lease, and watchdog observation integration |

Both modes require authenticated coordinator configuration, read-only local supervisor observation,
and configured endpoint/TLS bindings. G-1 qualifies the local lifecycle and, where claimed, the independent watchdog.
G-2 qualifies admission and observations. The same protocol is implemented for Linux hosts/VMs,
Docker hosts, and Kubernetes; no single production platform must be chosen first. Missing dependencies reject startup.
The controller and sidecar use the same mode, namespace, and node-supervisor lease generation contract.
G-7 qualifies the selected adapter's authority read and lease binding. The sidecar and the
local supervisor use the same adapter. The sidecar cannot select a second authority or fall back between them.
The client configuration keys and separate LISTEN deadlines are in the system design §6.2.
The node-local database identity setting is a PostgreSQL deployment setting, not a copied
application-table value. Verify it after rewind or rebuild.

The runtime consumes explicit JVM-style `-D` properties unless a tested environment mapping
is added. Docker `-e` does not automatically populate Java system properties or a native
binary's configuration.

## 5. Build and Run

Use the module's verified Maven configuration and JDK. After a Java or Maven change, rebuild
the reactor slice before testing:

```powershell
mvn clean install -DskipTests -pl :peegeeq-pg-sidecar -am 2>&1 | Tee-Object -FilePath logs/pg-sidecar-rebuild.log
```

Run the smallest relevant test class under the profile matching its tag. Read the saved log
and every per-class count. A successful build is not endpoint verification.

Native packaging requires the module's native profile and compatible GraalVM. Packaging,
artifact names, native property parsing, and container base compatibility must be verified
before publication. A native packaging command does not use test skipping as a verification
substitute.

Pass container settings as arguments to the verified entry point. Do not publish a Dockerfile
that assumes Maven exists in a native-image base or that a native executable uses the same
argument parser as the JVM without testing it.

The previous Java sketches were removed. They discarded deployment and HTTP response Futures
and omitted authority, deadlines, and resource shutdown. The contract above replaces those
sketches; production code must follow the project's established asynchronous patterns.

## 6. Database Role and Security

Use a dedicated login role without superuser, replication, promotion, or database-control
privileges. `pg_is_in_recovery()` is an observation function. The role does not need permission
to alter application tables.

Provision a non-empty secret or an explicitly secured local authentication mechanism.
Restrict the role's origin and databases in `pg_hba.conf`. Do not revoke `CONNECT` from
`PUBLIC` as a sidecar-specific restriction; that changes access for other roles.

Restrict `/primary` health access to proxies and operators. Use encrypted authenticated coordinator access
with read-only authority permissions. Sidecar credentials cannot mutate the control record
or release a fence. Never log passwords or tokens.

Permit authenticated applications read-only `/writer` access through the stable HTTP endpoint.
Do not grant them node-control or coordinator mutation access. Grant only the PostgreSQL metadata
observation needed for identity and synchronous coverage. Test those queries using the actual
least-privilege role; a superuser result does not qualify the sidecar role.

## 7. Lifecycle and Verification

Startup completes only after required configuration and HTTP binding succeed. Shutdown
observes in-flight work and closes the HTTP server, pool, and owned clients in dependency
order. Failed close remains failure. The sidecar never closes a shared injected Vert.x.

Verify the following against real components:

| Case | Required response |
|---|---|
| Eligible primary in the configured mode | 200 |
| Physical standby | 503 |
| Writable old primary without current authority | 503 |
| Quarantined or restarted former writer | 503 |
| `PREPARED` grant or `SERVING` before activation | 503 |
| Grant with wrong node, generation, operation, or policy revision | 503 |
| Required synchronous peer unavailable | 503 |
| Unowned control record retaining an old serving value | 503 |
| Coordinator unavailable, stale observation, or malformed record | 503 |
| Frozen database, acquisition failure, or lost response | 503 within the request deadline |
| Other path | 404 |
| Missing mode or identity | Startup failure |
| Authenticated `/writer` request | Same eligibility as `/primary`; matching derived JSON; no caching |
| Unauthorised `/writer` request | 401 or 403; no eligible response |
| Database identity copied from another node | Startup or eligibility failure |
| Bootstrap before first policy confirmation or writer activation | 503; missing history never becomes default-primary permission |
| Proxy retains an earlier successful check after lease loss | Sidecar returns 503; local stop/watchdog excludes the former writer before lease handover despite routing delay |
| Watchdog missing, unsafe, unhealthy, or not armed, in `required` mode | 503; supervisor must refuse writer admission |
| Watchdog absent in `automatic` or `off` mode | Not a reason for 503; the absence is reported |
| Manual mode without current writer lease | 503; operator request alone is insufficient |
| Supervisor restarts with persisted open grant | 503 until fresh ownership, the selected watchdog mode, role, coverage, and admission are established |

Assert node-local identity, role, authority, and elapsed deadline. Check primary and standby
through HAProxy. Inject each dependency failure before implementing its handling. Teardown
must fail on close failure.
Sidecar 200 is only one input to client recovery. Native readiness additionally requires the
executed finite claim/acknowledgement pass in system design §6.6 and S52. A skipped consumer
operation cannot be counted as catch-up solely because the sidecar is eligible.

See [the detection options](PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md),
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md), and
[the coordinator and supervision design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md).
