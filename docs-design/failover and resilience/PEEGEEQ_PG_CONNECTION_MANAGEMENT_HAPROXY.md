# PeeGeeQ Connection Management and HAProxy Failover — Design

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Document type**: Design. This document describes the target system.  
**Design revision**: 2026-10-07, coordination, bootstrap, and recovery prerequisites revision

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
   - [1.1 Data Model and Safety Contracts](#11-data-model-and-safety-contracts)
2. [Design Principles](#2-design-principles)
3. [Requirements](#3-requirements)
4. [Deployment Profiles](#4-deployment-profiles)
5. [Database Tier: HAProxy and the Role Authority](#5-database-tier-haproxy-and-the-role-authority)
6. [Client Tier: PeeGeeQ Connection Management](#6-client-tier-peegeeq-connection-management)
7. [Health Reporting](#7-health-reporting)
8. [PgBouncer](#8-pgbouncer)
9. [The pg-sidecar Service](#9-the-pg-sidecar-service)
10. [Required Behaviour by Scenario](#10-required-behaviour-by-scenario)
11. [Environments](#11-environments)
12. [Verification](#12-verification)
13. [Appendix A: Primary Detection Options](#appendix-a-primary-detection-options)
14. [Appendix B: The JDBC Multi-Host Pattern](#appendix-b-the-jdbc-multi-host-pattern)

---

## 1. Purpose and Scope

This document is the entry point to PeeGeeQ's PostgreSQL connection, failover, and recovery
design. It explains how application instances reach the authorised database writer, how a
replacement is selected after failure, and how database work and subscriptions resume.
It is written for developers implementing the components and operators deploying the system.
The requirements describe the target system. Implementation evidence is recorded separately.

**The problem being solved.** The design uses PostgreSQL for queue data, event history, and
durable subscription progress. Application instances need short database operations through
connection pools and long-lived connections for `LISTEN`, which subscribes to database
notification channels. A primary failure affects both kinds of connection. Recovery must
restore safe writes, replace unusable sessions, and process durable work missed during the
outage. Opening a new socket is only one part of that recovery.

A PostgreSQL **primary** is a node in the writable database role. A **standby** follows the
primary's write-ahead log (WAL), the database's record of changes. **Promotion** changes a
standby into a primary. The **writer** in this design is the primary currently authorised to
accept PeeGeeQ writes. Local writable role alone is insufficient. **Fencing** means confirmed
stop of a former or ambiguous writer with restart inhibited. It prevents a replacement from
serving while the previous node can still accept conflicting writes.

**How the components fit together.** Applications use a stable SQL address. HAProxy forwards
connections through that address to the eligible writer. One `peegeeq-pg-sidecar` runs beside
each database node and reports whether that node can receive traffic. The automatic controller
owns transition decisions through Consul. A separately secured node-control provider executes
and confirms database stop, promotion, restart restrictions, and permission to accept writes.
The selected design assigns cluster control to PeeGeeQ components and does not require Patroni.

Consul is the reference automatic protocol in this revision. Before automatic implementation,
G-7 records whether to retain it or replace it with a qualified coordination service (§5.10).
The local Java Qraft project is an option for that decision. Selecting another service requires
replacing the Consul-specific contracts consistently; a runtime toggle cannot combine authorities.

| Component | Role in this design |
|---|---|
| PostgreSQL primary and standbys | Store application data and replicate WAL under the required synchronous policy |
| HAProxy | Provide routing through redundant proxies and close sessions when writer eligibility is lost |
| `peegeeq-pg-sidecar` | Observe local database role, current authority, provider permission, and required replication coverage |
| `PgPrimaryElector` in proposed `peegeeq-pg-failover` | Own the cluster's Consul session and control record; guard ownership-sensitive updates |
| `PgFailoverMonitor` in proposed `peegeeq-pg-failover` | Detect failure suspicion and coordinate a guarded transition or restart reconciliation |
| Independent node-control provider | Enforce fencing, generation checks, promotion, writer admission, and restricted standby restart |
| PeeGeeQ connection manager and subscription clients | Apply operation deadlines, discard unusable connections, reconnect, and complete required durable catch-up |
| Optional PgBouncer | Pool database connections between PeeGeeQ and HAProxy; transaction pooling requires a separate LISTEN route |

Consul stores coordination intent and controller ownership. It does not stop a database.
The provider's **writer grant** is permission to admit application writes. The sidecar must
match that grant to current authority and live database observations before reporting the
node eligible. Application credentials use controlled proxy paths. Observation and node
control have separate credentials and access paths.

**What recovery must accomplish.** The normal production topology has one primary and two
synchronous physical standbys. Required commits wait for WAL flush acknowledgement from both
named standbys. For example, if node 1 fails, the controller first withdraws admission and
confirms its fence. It can then promote a standby covered by the last confirmed durability
policy. With node 2 promoted, node 3 must supply synchronous coverage before writes resume.
Publishing the selected writer and activating its matching provider grant are separate steps.

Readiness reports whether an application instance can perform its required work.
HAProxy then admits new connections to the replacement. Failed transactions are returned to
the caller. A missing commit acknowledgement leaves the outcome unknown and is not permission
to retry automatically. Dedicated LISTEN clients reconnect, acknowledge their channels, and
complete the required durable catch-up before application readiness returns. Notifications
request processing; durable database state determines what work remains. Rewind or rebuild
of node 1 and validated standby re-join restore redundancy after service recovery.

**Deployment scope.** Profile A uses automatic Consul-coordinated transitions. Profile B
uses operator-directed transitions through the same fencing and admission provider. Profile C
uses an externally managed writer address with equivalent safety obligations. Profile D
exercises connection recovery against independent development databases and does not qualify
replicated-data failover. A two-node synchronous pair keeps required writes unavailable after
promotion until a synchronous standby is restored. Production SQL and eligibility endpoints
require redundancy. The 45-second recovery objective requires measured qualification.

This document covers database routing and control, shared pools, dedicated LISTEN connections,
circuit breakers, operation deadlines, readiness, the optional PgBouncer layer, and acceptance
scenarios. Database failover has its own module, process lifecycle, configuration, credentials,
and Consul client. Routing between PeeGeeQ application instances and federation belong to
`peegeeq-service-manager`. Backups and point-in-time recovery are separate operational work.

The fault model includes process crashes, silent connections, partitions, delayed responses,
and restarts. It assumes correct authenticated Consul quorum, trusted node-control enforcement,
and durable storage on surviving synchronous nodes. Host administrator bypass, compromised
providers, arbitrary privileged database clients, and loss of all durable copies require
separate security and backup controls. No design document proves runtime safety.

**How to read the document set.** Continue with the data model in §1.1, the requirements in §3,
and deployment profiles in §4. Sections 5 and 6 define database transition and client recovery.
Sections 10 and 12 define the required failure scenarios and verification. The companion
documents provide the following detail:

| Document | Read it for |
|---|---|
| [Routing and detection options](PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md) | The reasons for the selected routing and failover architecture |
| [Sidecar guide](PEEGEEQ_PG_SIDECAR.md) | Eligibility endpoints, configuration, packaging, security, and lifecycle |
| [Consul failover design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md) | Controller ownership, provider operations, transition order, and interrupted-action reconciliation |
| [Implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md) | Execution phases, deployment decisions, dated findings, and recorded test evidence |

### 1.1 Data Model and Safety Contracts

This is a target design. A requirement here is not a claim that code implements it.

| Field or record | Source of truth or derivable | Contract |
|---|---|---|
| `clusterId`, cluster incarnation, node IDs, addresses, membership, and selected coordination protocol | Configuration; authoritative deployment identity | Independent database clusters and restored coordination stores use distinct namespaces. Membership and authority backend cannot change during a transition. G-7 records one automatic backend and its protocol mapping. |
| `peegeeq/pg/<clusterId>/<incarnation>/primary-lock` | Consul; authoritative control record | One key per replicated cluster. ACLs exclude unauthorised clients. Session and revision conditions guard authorised mutations; ACLs do not enforce lock ownership. |
| Lock `Session`, `LockIndex`, and `ModifyIndex` | Consul metadata; authoritative ownership and revisions | The session identifies the controller. The tuple of key, lock index, and session identifies the control generation. Do not duplicate these fields in the KV value. |
| `writerNodeId` | Consul lock value in A; provider transition record in B; authoritative intended writer | Names a database node, not a controller. Serving intent also requires a matching open provider grant and live checks before traffic. |
| `phase`: `WITHDRAWN`, `FENCING`, `PROMOTING`, or `SERVING` | Same profile transition record; authoritative intent | Only current `SERVING` intent can match an open grant. Publication alone grants no traffic. Actual PostgreSQL role is never stored as this phase. |
| `operationId` and `previousWriterNodeId` | Same profile transition record; authoritative operation and former-writer intent | Persist before side effects. Reconciliation resumes the same operation after an uncertain response. The previous writer is absent only for authenticated first-start bootstrap with verified provisioning evidence (§5.11). |
| Initial provisioning evidence and bootstrap request parameters | Authoritative deployment evidence, referenced by immutable existing provider receipt parameters | Bind evidence to cluster, incarnation, membership, and initial writer. Missing coordination history alone never proves a new cluster. Bootstrap uses existing intent, grants, and receipts; completion is derived from confirmed policy and serving admission. |
| Node-control receipts: operation, generation, target node or cluster, action, immutable request parameters, and durable result | Independent node-control provider; authoritative action evidence | Receipt identity is derived from generation, operation, action, and target. A stop request is not a completed fence. A receipt never grants writer authority by itself. |
| Accepted control generation per cluster and installed generation per node | Durable node-control provider state; authoritative command admission | A cluster-wide serialisation boundary retires old commands before new admission. Per-node counters alone are insufficient. Unknown installation requires fencing or continued withdrawal. |
| Node quarantine and writer grant: mode, generation, operation ID, policy revision, node ID, and state `CLOSED`, `PREPARED`, or `OPEN` | Durable node-control provider; authoritative execution permission | A grant is separate from transition intent. It survives restart but cannot grant traffic without matching current authority and live checks. Revocation supersedes preparation and activation. |
| `durabilityPolicy`: revision and required standby node IDs | Automatic control record; manual provider transition record; authoritative confirmed policy intent eligible for serving | Require every named standby. The acknowledgement count is computed from the set. The record is not a stored observation of replication health. Retain it while preparing a replacement. It is absent before the first confirmed bootstrap policy; absence never permits serving or ordinary promotion. |
| `pendingDurabilityPolicy`: next revision and required standby node IDs | Same profile authority; authoritative proposed policy intent | Persist before configuration effects. It grants no target eligibility. Remove it when the verified policy replaces `durabilityPolicy`. Revisions increase within an incarnation. |
| Manual transition identity and admission generation | Node-control provider; authoritative operator-issued intent | In profile B, authenticated operator actions use a provider-issued monotonic generation. It is separate from automatic Consul generations. Mode changes retire the prior admission generation before another can serve. |
| Node role, WAL positions, timeline, reachability, routing eligibility, and readiness | Derived from live observations and current authority | Compute them. Do not store independent primary or healthy flags as authority. |
| Durable subscription identity, filters, lifecycle, `last_processed_id`, and replay lease owner, generation, and expiry | Existing tenant-local `bitemporal_subscriptions`; authoritative subscription progress and ownership | Reuse the existing schema and writer-barrier replay contract in §6.6. Do not create a second failover cursor or acknowledgement table. |
| Replay upper boundary, retry attempt identity, channel acknowledgements, catch-up completion, and client eligibility response | Derived runtime observations | Recompute after reconnect or restart. Do not persist a second cursor, ready flag, or authority cache. |
| Caller idempotency result | Existing application operation contract; authoritative outcome when provided | A stable operation identity is required for automatic reconciliation of unknown commits. Failover does not create a generic result table or guarantee external exactly-once effects. |

The following invariants apply to profiles A and B:

1. At most one node accepts PeeGeeQ writes. Zero writers is permitted during an outage.
2. A replacement cannot serve until former or ambiguous writers are stopped with restart inhibited. Validated standbys may remain running under enforced writer-start and promotion inhibition. A loss of contact is not fencing evidence.
3. Retired generations cannot admit new promotion, quarantine-release, or activation commands. Previously accepted effects remain quarantined and are reconciled before new writer admission. Validated standbys remain under enforced promotion and writer-start inhibition.
4. A successful application write includes a confirmed commit under the configured durability policy.
5. A lost response can leave an operation outcome unknown. Failed transport does not prove rollback.
6. A recovered old primary remains quarantined until rewind or rebuild and standby validation complete.

Profile A uses Consul authority and an independent node-control provider. Profile B uses an
operator-controlled transition through node control and the same fencing and durability invariants. Changing
profiles requires a stopped, reconciled transition. Disabling a controller is not permission
to bypass its authority checks.

Within one automatic namespace, generation ordering uses `LockIndex`; session identity must
also match current ownership. Incarnations are not compared lexically. A new incarnation
requires the fenced restoration procedure. In manual mode, the provider owns monotonic
admission generations and serialises operator effects. Both modes reject retired-generation
commands and reconcile in-flight effects before admitting a writer.

The state of the implementation against this design, the work to reach it, and the test
evidence are recorded in
[PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md).


---

## 2. Design Principles

1. **One endpoint.** PeeGeeQ connects to a single host and port. It never holds a list of
   PostgreSQL nodes.
2. **One profile authority names the writer.** In reference profile A, `PgPrimaryElector`
   owns Consul intent. In B, the provider owns operator-directed intent. Control ownership alone
   does not prove that the database failed. Promotion requires fencing and generation enforcement at the point of effect.
   HAProxy routes by the sidecar's eligibility response.
3. **The client converges.** After a role change, every connection PeeGeeQ holds ends on the
   one node that is the primary. PeeGeeQ does not rely on the proxy alone for this.
4. **A failover has two halves.** The first is to leave the failed node. The second is to
   converge on one correct node. The design is complete only when both hold for pooled
   connections and for LISTEN connections.
5. **Failures are visible.** A lost database is reported by the health endpoint, by the circuit
   breaker, and by a failed `Future` to the caller. No component reports success while it is
   disconnected or connected to the wrong node.
6. **No blocking, no JDBC.** PeeGeeQ uses the Vert.x 5 reactive PostgreSQL client. The runtime
   connection path uses no JDBC and is not configured from a URL string.

---

## 3. Requirements

| ID | Requirement |
|---|---|
| R-1 | At most one node accepts writes from PeeGeeQ. Zero is permitted during failure and transition. |
| R-2 | HAProxy selects new connections using its latest successful scheduled sidecar observation and configured backend health state. Each sidecar response evaluates live profile eligibility. Enforced admission and fencing preserve safety during observation delay. |
| R-3 | When configured failed checks mark a backend down, HAProxy closes every session it has open to that backend. Measure detection and shutdown delay; eligibility loss does not imply instantaneous proxy shutdown. |
| R-4 | A node that has lost the primary role cannot receive traffic until it has re-joined as a standby. |
| R-15 | In profile A, a primary failure permits automatic promotion only after confirmed fencing and target validation. Writes resume through the same endpoint after writer activation and replacement synchronous coverage. Missing fences or peers preserve unavailability. |
| R-16 | Only the current control generation authorises promotion. The node-control provider serialises promotion and fencing and rejects obsolete commands at the point of effect. Retries cannot authorise a second writer. |
| R-5 | Discard a connection after a required writer operation is rejected as read-only or the server ends its session. Maximum lifetime closes it when next idle. Business read-only errors do not prove demotion. |
| R-6 | A LISTEN connection reconnects without an attempt limit, with a bounded backoff. |
| R-7 | A LISTEN connection detects that its node is not the primary and reconnects through the endpoint. |
| R-8 | After reconnect, acknowledge every LISTEN channel. Durable bitemporal subscriptions complete a finite replay boundary; native consumers complete an executed finite claim/acknowledgement pass. Skipped or deferred work does not establish readiness. Non-durable subscriptions retain their documented loss semantics. |
| R-9 | Every pooled operation in every module is guarded by the circuit breaker for its pool. |
| R-10 | The circuit breaker opens on connection-level failures. A business SQL error does not count toward opening it. |
| R-11 | The readiness endpoint returns a failure status while the instance cannot use its database or while a required LISTEN connection is down. |
| R-13 | Every configured timeout is honoured to the millisecond. No connection attempt waits without a bound. |
| R-14 | The configured schema applies to every pooled operation, including through a transaction pooler. |
| R-17 | Acknowledged writes survive promotion of a target covered by the required synchronous acknowledgement policy. No automatic asynchronous downgrade is permitted. |
| R-18 | Dependency timeout, lost response, controller restart, and stale completion are reconciled without assuming success or rollback. |
| R-19 | Every production SQL proxy layer, including optional PgBouncer, is redundant. Stable SQL and eligibility endpoints have tested ownership transfer and session recovery. |

---

## 4. Deployment Profiles

Every profile gives PeeGeeQ one endpoint. The profiles differ in who promotes a standby.

| Profile | Components | Promotion | Use |
|---|---|---|---|
| **A. Automatic failover** | Redundant HAProxy + sidecars in automatic mode + Consul + failover controller + independent node-control provider | Controller after confirmed fencing and eligibility checks | Production only after the provider and acceptance gates pass |
| **B. Manual promotion** | Redundant HAProxy + sidecars in manual mode + node-control admission, quarantine, and restart inhibition | Operator through node control after confirmed fencing and eligibility checks | First implementation target |
| **C. Managed endpoint** | One FQDN or virtual IP that DBA/DevOps re-point after a failover | DBA/DevOps | Environments that already manage the address themselves |
| **D. Development** | HAProxy with `pgsql-check` in front of two independent nodes | None | Development and connection-recovery tests only |

Profiles A and B share client, durability, session-shutdown, and re-join contracts. Their
authority mechanisms differ. In A, the sidecar requires current Consul authority. In B, the
operator fences the former writer and controls node admission. A runtime toggle cannot
silently convert one mechanism into the other.

Profile D has no component that knows a node's role. It does not meet R-1, R-2, or R-4 and is
never used with replicated data.

No profile uses Patroni.

---

## 5. Database Tier: HAProxy and the Role Authority

### 5.1 Responsibilities

**Role reporting: `peegeeq-pg-sidecar`.** One sidecar runs beside each PostgreSQL node. It
answers `GET /primary` according to the profile-specific eligibility contract (§9).
HAProxy uses that answer. PostgreSQL role and write permission are separate conditions.
`pgsql-check` and a TCP check establish neither writer authority nor standby exclusion.

**Role authority: `PgPrimaryElector`.** It holds a Consul session and a lock on the KV key
`peegeeq/pg/<clusterId>/<incarnation>/primary-lock`. The value and metadata follow §1.1.
Authority-changing operations use one Consul transaction containing `check-session`,
`check-index`, and `cas` on the same key. A rejected condition makes no change. ACLs restrict
the clients that can access the key; they do not impose these conditions. Reads
that grant traffic require Consul's consistent mode. A released key with a retained value
does not authorise traffic. The session TTL defaults to 15 seconds, renewal to TTL / 3, and
lock delay to 10 seconds. Lock delay blocks all contenders from reacquiring the released
lock temporarily. It is not a fence.

**Failure detection and promotion: `PgFailoverMonitor`.** A verticle in
the proposed `peegeeq-pg-failover` module. It holds direct observation pools for the configured
nodes. Every 500 ms it probes the intended writer with a bounded query. Consecutive failed
probes establish suspicion, not proof of death. The controller keeps its control session
while it withdraws traffic, obtains fences, checks the standby, and reconciles promotion.
Session expiry is not the normal failover trigger. On controller ownership change, the new
controller first reconciles live nodes and provider receipts. It does not promote a healthy
writer because the previous controller failed.

**Node control.** The deployment provider performs stop, restart inhibition, controlled
promotion, and re-join admission. It validates operation identity and current control
generation and serialises conflicting actions at the node. A controller-side lock check
followed by unguarded SQL is insufficient. Controllers have no direct promotion or fence
release credentials. The provider can execute `pg_promote()` through the reactive client,
but must enforce generation at the effect boundary. Lost replies require observation and
receipt reconciliation before any retry. An already accepted effect can remain in flight after
session loss. Its target stays quarantined. The successor reconciles and serialises those
effects before any admission. A lock check does not make Consul and SQL one atomic operation.

The complete provider operations and interruption rules are in
[the Consul design §4](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md#4-node-control-provider-contract).
They also apply to manual mode with provider-issued generations. Select the provider and its
admission integration before implementing either profile. Automatic mode adds Consul authority;
it does not introduce the first enforced admission boundary.

### 5.2 Topology

```text
Applications -> stable endpoint -> HAProxy A and B -> admitted PostgreSQL writer
                                       |
                                       +-> GET /primary on each node's sidecar

peegeeq-pg-failover -> Consul quorum (authority and transition intent)
          |
          +-> direct observation pools -> all configured PostgreSQL nodes
          |
          +-> independent node-control provider -> guarded stop/start/promotion
                   (durable quarantine and generation enforcement)

pg-node-1 primary == synchronous WAL ==> pg-node-2 standby
                  == synchronous WAL ==> pg-node-3 standby
```

The full production topology has one primary and two synchronous physical standbys (§5.8). PeeGeeQ application
credentials reach databases only through the controlled proxy paths. Observation and node
control use separate credentials and access paths. The provider can fence a node even when
its SQL endpoint is unreachable. Sidecar checks are routing observations. Fencing establishes
single-writer safety despite delayed or different observations by the proxies.

### 5.3 HAProxy configuration

```haproxy
global
    maxconn 500
    log     stdout format raw local0 info

defaults
    mode              tcp
    option            tcplog
    log               global
    timeout connect   5s
    # Long enough for an idle LISTEN connection between two client probes (§6.6).
    timeout client    30m
    timeout server    30m

frontend pg_frontend
    bind *:5400
    default_backend pg_primary

backend pg_primary
    option            httpchk GET /primary
    http-check        expect status 200

    # The health check goes to the sidecar on port 8008, not to PostgreSQL.
    # Routing uses scheduled sidecar observations; provider admission enforces permission.
    default-server    check port 8008 inter 500ms fall 2 rise 1 on-marked-down shutdown-sessions

    server pg-node-1  pg-node-1:5432
    server pg-node-2  pg-node-2:5432
    server pg-node-3  pg-node-3:5432
```

Design rules for this configuration:

- **Role-based check (R-2).** `httpchk GET /primary` with `expect status 200`, answered by the
  sidecar. HAProxy uses its last successful observation and health state, not a new eligibility
  request for every SQL connection. A stale route cannot bypass provider-enforced revocation.
  This is used in profiles A and B. `pgsql-check` is used only in profile D.
- **Session shutdown on a role change (R-3).** `on-marked-down shutdown-sessions` closes every
  established session when the server is marked down after its configured failed checks. Pools and LISTEN connections
  then reconnect through the endpoint and reach the new primary.
- **Detection time.** Measure check scheduling, request deadlines, and the two failed checks.
  `inter 500ms fall 2` alone does not establish a one-second worst-case bound.
- **No preferred old writer.** All nodes use the same server policy. Automatic routing
  follows authority, not a fixed `backup` preference. Two eligible responses are a fault;
  they are not resolved by preferring one node.
- **No reconfiguration.** A promotion changes what the sidecars answer. HAProxy's configuration
  does not change, and nothing reloads it.
- **Idle timeouts.** Allow for LISTEN probe scheduling and execution (§6.6). A successful probe
  supplies traffic. A stalled probe or event loop can still cause an idle disconnection.

### 5.4 Failover monitor configuration

The controller reads its own configuration. These are proposed contracts, not shipped keys.

| Property | Default | Description |
|---|---|---|
| `peegeeq.pg.failover.enabled` | `false` | Enables automatic control. Profile selection also requires explicit sidecar mode and admission policy |
| `peegeeq.pg.cluster-id` | required | Stable configured cluster identity |
| `peegeeq.pg.cluster-incarnation` | required | Namespace identity; changed by controlled restoration of coordination state |
| `peegeeq.pg.node-control.provider` | required in A and B | Shared independently reachable node-control and admission provider |
| `peegeeq.pg.failover.node-control-timeout-ms` | `10000` | Bounds a node-control request; expiry leaves its result unknown until reconciled |
| `peegeeq.pg.failover.probe-timeout-ms` | `1000` | Bounds each node observation |
| `peegeeq.pg.failover.session.ttl` | `15s` | Consul session TTL |
| `peegeeq.pg.failover.session.lock-delay` | `10s` | Consul lock delay after a session is lost |
| `peegeeq.pg.failover.poll.interval` | `500ms` | Interval between `pg_is_in_recovery()` polls |
| `peegeeq.pg.failover.poll.failure-threshold` | `3` | Consecutive failed polls before the monitor acts |
| `peegeeq.pg.nodes.<id>.host` | — | Direct host of each PostgreSQL node |
| `peegeeq.pg.nodes.<id>.port` | — | Direct port of each PostgreSQL node |

Both modes reject missing node-control configuration, ambiguous membership, and unsupported
durability. A deployment specifies its provider, credentials, failure domains, and effect
boundary. Docker process stop in a local test is not evidence for a production host fence.

Production runs a three-node Consul cluster, which tolerates the loss of one Consul node.

### 5.5 Fencing

The dangerous case is a network partition: the primary is alive and isolated, the standby is
promoted, and both accept writes. Fencing prevents it (R-1, R-4).

- **Fence before promotion.** Obtain independent confirmation that the former writer and
  every ambiguous writable node are stopped and cannot restart as writers. A live node proven
  to be a standby may remain running under provider-enforced promotion and writer-start
  inhibition. Every node other than the selected writer remains unable to become a writer.
  An unreachable node is unknown. If fencing cannot be confirmed, stop the transition and
  keep readiness down.
- **No SQL substitute.** Backend termination and pool closure are connection cleanup. They
  do not stop new connections and cannot satisfy the fencing precondition.
- **Reject stale commands.** The node-control provider durably rejects obsolete generations,
  including delayed promotion and fence-release requests. It serialises these effects with
  database start and stop. Provider restart preserves quarantine. Consul lock checks alone
  cannot enforce this at PostgreSQL.
- **Loss of authority.** The controller stops issuing mutations. Automatic sidecars return
  503 when current authority cannot be established. Proxies shut sessions down on failed
  checks. No replacement is authorised without fresh ownership and confirmed fencing.
- **Re-join.** Keep the former writer stopped and quarantined. Rewind or rebuild it, configure
  standby mode, start under restricted admission, then validate cluster identity, timeline,
  recovery mode, and replay of an observed WAL position. Only then remove the restart fence
  for standby operation. Never restore writer admission automatically.

Profile B uses the same provider protocol. The operator begins the transition, revokes writer
admission, confirms fencing, promotes a covered target, installs the replacement synchronous
policy, prepares and activates the writer, then performs controlled standby re-join.

### 5.6 What a failover looks like

Profile A transition order:

```
RECONCILE  Establish ownership. Begin the provider generation and reconcile retired effects.
WITHDRAWN  Persist intent. Revoke writer admission and confirm closure.
FENCING    Stop former or ambiguous writers. Inhibit standby promotion. Verify receipts.
PROMOTING  Validate the synchronous target. Request generation-checked promotion.
           Reconcile an uncertain reply. Observe writable role and required WAL.
           Attach the peer. Install and verify policy. Confirm policy intent. Prepare admission.
SERVING    Publish serving intent. Activate writer admission under the same live generation.
RECOVER    Proxies admit it. Pools and LISTEN reconnect. Durable work catches up.
RE-JOIN    Rewind or rebuild the former writer under quarantine. Admit it as standby.
```

The initial application recovery objective is 45 seconds for a primary crash in the three-node
production topology, with a reachable provider, eligible target, and usable surviving
synchronous standby. It is measured from injected failure to a committed application
write and required LISTEN catch-up. Qualification specifies backlog size, handler latency,
retry state, and configured deadlines. The example timeouts do not prove a 45-second bound.
Do not publish a recovery SLO until those measurements pass. There is no availability promise
during a partition or provider failure. Safety takes precedence over the objective. Record detection, fencing, promotion,
proxy admission, breaker permission, reconnect delay, and catch-up separately.

Profile B follows the same begin, withdraw, revoke, fence, validate, promote, prepare, publish,
and activate order through provider-owned intent. The operator initiates the transition.

Writer eligibility is the intersection of current profile authority, an `OPEN` provider grant,
matching node, generation, operation and policy revision, writable local role, and live
synchronous coverage. Publishing `SERVING` alone does not open admission. Provider preparation
alone does not authorise traffic. A crash between publication and activation leaves traffic
closed. Reconcile the same operation before activation. A successor first retires the previous
provider generation, closes all old grants, and reconciles in-flight effects. It can prepare
and activate the same healthy writer without promoting it. No new writer serves while a
retired promotion or admission effect can still conflict.

### 5.7 Controller and Consul Failure

Controller failure and database failure are different events. A new controller reconciles
before changing the writer. It can restore authority for the same healthy node after proving
that no conflicting writer or obsolete node-control operation can take effect.

Consul loss does not trigger promotion. Automatic sidecars cannot grant traffic without a
consistent authority read. Controller mutations stop. When quorum returns, reconcile rather
than replaying cached actions. A delayed renewal or SQL reply cannot restore an old generation.
Consul TTL expiration can be delayed beyond its configured value. Lock delay is a coordination
delay, not proof of node shutdown. [Consul sessions](https://developer.hashicorp.com/consul/docs/automate/session)

Startup with two writable nodes, no trustworthy history, restored Consul indexes, or unknown
provider operations requires quarantine and operator reconciliation. Coordination-store
restore uses a new incarnation after fencing all nodes and resetting provider admission by a
controlled procedure. Never recreate a lock namespace while old commands remain executable.
Authenticated first-start bootstrap is a separate procedure in §5.11. A missing record after
an earlier deployment must follow reconciliation or restoration, not first-start bootstrap.

### 5.8 Durability and Uncertain Writes

The full production topology has one primary and two physical standbys. The normal policy
requires every named standby to acknowledge remote WAL flush. Node names containing hyphens
need individual identifier quotes:

```postgresql
# pg-node-1 is the writer; policy revision 1 requires nodes 2 and 3.
synchronous_standby_names = 'ANY 2 ("pg-node-2", "pg-node-3")'
synchronous_commit = on
```

The count is the size of `requiredStandbyNodeIds`, not an independently stored quorum setting.
Reject an empty serving set, the writer itself, unknown nodes, and duplicate identities.
Never generate `ANY 0` or a blank policy to restore write availability.
Quorum with fewer acknowledgements than listed nodes is unsupported by this target-selection
contract. Replication application names are unique, bound to authenticated configured nodes,
and checked for case-insensitive collisions. A name supplied by an unrelated replication
client does not establish node identity. Use `synchronous_commit=on` for required writes.
The connection manager owns the transaction and reinstates the required transaction-local
commit policy at its final commit boundary. Caller operations cannot commit independently or
bypass that path. Verify attempts to weaken session or transaction policy. Privileged database
clients outside the PeeGeeQ contract are outside this guarantee.

Phase 7 must implement and test this commit enforcement in `PgConnectionManager` before any
manual or automatic application-write preservation assertion. Tests use the production manager
through the stable endpoint, attempt weaker session and transaction settings, and interrupt
commit acknowledgements. Raw SQL commits do not substitute for that evidence. Full migration
of the other application modules remains phase 11.

`durabilityPolicy` records the confirmed policy intent eligible for serving. `pendingDurabilityPolicy` records
the next configuration intent. Consul owns those records in A. The provider owns them in B.
Observed PostgreSQL settings and replication health are derived. Provider receipts identify
which policy operation completed; they do not replace live checks or become another policy
authority. A controller takeover preserves both intents until reconciliation completes.

Policy changes use this order:

1. Persist `WITHDRAWN` and the next policy revision. Revoke admission. Confirm that existing
   PeeGeeQ writes have completed or terminated. If write quiescence cannot be established,
   keep admission closed and use confirmed stop where required. A cancellation request is
   insufficient. Unknown commit outcomes remain unknown to callers.
2. For failover, fence former writers and promote only a member covered by the last confirmed
   policy. For re-join, keep the existing writer and its covered standby. A proposed standby
   is not yet an eligible promotion target.
3. Attach the required standbys to the writer's current timeline. With writes quiescent,
   observe a fresh writer WAL flush boundary. Verify every required peer's flush and replay
   reach that boundary and its authenticated identity matches configuration. Recompute the
   boundary after an interrupted validation; do not persist live WAL positions as authority.
4. Through guarded provider control, install the generated `synchronous_standby_names` and
   verify the effective setting and peers. Record the action receipt. Keep admission closed
   on a lost reply, mismatch, missing peer, or failed validation.
5. Conditionally replace `durabilityPolicy` with the verified pending intent and clear
   `pendingDurabilityPolicy`. Prepare the writer grant for that revision. Publish `SERVING`,
   then activate the matching grant. Revalidate role and coverage at activation. Confirmed
   intent alone does not grant traffic.

The concrete three-node lifecycle is:

| Step | Writer | Required standbys | Generated PostgreSQL policy | Promotion eligibility |
|---|---|---|---|---|
| Initial serving | node 1 | nodes 2 and 3 | `ANY 2 ("pg-node-2", "pg-node-3")` | Both named standbys are covered |
| Node 1 fenced; node 2 promoted | node 2 | node 3 | `ANY 1 ("pg-node-3")` | Node 3 is covered; node 1 remains excluded |
| Node 1 starts as restricted standby | node 2 | node 3 | `ANY 1 ("pg-node-3")` | Node 1 is excluded while catching up |
| Re-join policy cutover completes | node 2 | nodes 1 and 3 | `ANY 2 ("pg-node-1", "pg-node-3")` | Both named standbys are covered |

The reduction to one required peer happens only after fencing and promotion, while admission
is closed. It remains synchronous. Loss of a standby while the current writer is serving does
not automatically reduce its required set. After any later promotion, require every remaining
covered peer. If none remains, committed-write admission stays closed until a peer is restored.

Only nodes covered by the prior acknowledged-commit policy are eligible targets. A stale,
unrelated, or newly joining node is rejected. Membership changes and replacement of an
eligible target use the same withdrawn policy cutover. If the writer fails during cutover,
target selection uses the last confirmed policy, never the unconfirmed pending set. Missing
policy history or conflicting receipts requires quarantine and operator reconciliation.
Restart tests interrupt every policy step, including configuration applied before intent
confirmation. Successful re-join restores the two-peer policy before both peers become targets.
PostgreSQL's asynchronous default cannot satisfy R-17.
[PostgreSQL synchronous replication](https://www.postgresql.org/docs/current/warm-standby.html#SYNCHRONOUS-REPLICATION)
and [standby-name syntax](https://www.postgresql.org/docs/current/runtime-config-replication.html)
define the database requirements.

A two-node synchronous pair is supported with reduced availability. After promotion it has
no synchronous standby. Required writes stay unavailable until a standby is restored and
validated. It has no 45-second committed-write recovery promise. This design never silently
switches that pair to local-only commits.

The normal `ANY 2` policy trades write availability for coverage of both promotion targets.
Loss of either required standby blocks or fails writes within the operation deadline. Report
durability unavailable. Automatic admission cannot report ready merely because the primary
answers read queries.

A lost commit acknowledgement leaves the outcome unknown. Propagate the failure with that
distinction. Never retry a transaction automatically. Callers reconcile by an existing durable
operation identity or use an explicitly designed idempotency contract. External side effects
and consumer delivery are not made exactly once by database failover.

### 5.9 Production Endpoint and Security

Production uses at least two HAProxy instances and a tested stable address or managed endpoint.
Every proxy uses the same authority contract. Specify address ownership, detection, session
loss, and reconnection behaviour. With PgBouncer enabled, §8 requires redundancy for that layer
and its client address as well. The read-only eligibility endpoint in §6.6 also uses redundant
proxies. A surviving failure domain must retain a complete application-to-writer path. A
single proxy or pooler is permitted only in development.

Separate sidecar observation, application, controller, and node-control credentials. Restrict
direct application access to PostgreSQL. Consul uses authenticated encrypted access and scoped
ACLs. A generic Consul client cannot modify the control record. Restrict sidecar health access
to proxies and operators. Node-control access can stop databases and must be separately secured.

Re-join requires usable WAL, `wal_log_hints=on` or checksums, and `full_page_writes=on` for rewind.
If rewind fails, preserve quarantine and rebuild from a new base backup. Verify replication by
WAL progress, not only recovery mode. Failover restores service; a validated standby restores
redundancy. Backups and point-in-time recovery remain separate operational requirements.
[PostgreSQL pg_rewind](https://www.postgresql.org/docs/current/app-pgrewind.html)

### 5.10 Coordination Selection

G-7 records one automatic coordination backend before phase 8. Consul is the reference protocol.
The decision must identify the API, ownership lifecycle, atomic revision conditions, generation
ordering, fresh authority reads, durable recovery, namespace isolation, and authentication.
Ownership acquisition and expiry must not grant a database writer without the provider barrier.
Lost replies and stale observations must retain the same reconciliation and fencing contracts.

The local Java Qraft project can be assessed as an external coordination service. Its qualification
must demonstrate conditional ownership updates and linearizable authority reads, meaning reads
that include completed prior mutations. Raft replication alone does not specify those client
contracts. A Qraft term or leader observation cannot substitute for a defined controller ownership
generation. Test minority partitions, obsolete leaders, concurrent controllers, expiration,
lost mutation replies, snapshots, restart, restore, and unauthorised requests with real services.

If Qraft is selected, use an external service boundary. G-7 specifies a non-blocking PeeGeeQ
client adapter, request deadlines, response validation, TLS, credential separation, and lifecycle.
G-6 pins each service's JDK and dependencies independently. Do not embed Qraft's runtime in the
Vert.x application or copy Consul field names without defining their replacement semantics.

Consul remains the active reference until G-7 records a replacement and the documents replace
its data model, sidecar observations, provider validation, configuration, and acceptance together.
Only one backend may own automatic intent for an incarnation. Backend changes require stopped,
fenced reconciliation and a new incarnation. Manual B remains independent of this selection.

### 5.11 First-Start Bootstrap

Bootstrap establishes the first confirmed policy for a newly provisioned cluster with no prior
admitted PeeGeeQ writes. An empty coordination key is not provisioning evidence. Existing data
with unknown admission history, restored stores, missing receipts, or ambiguous nodes require
operator reconciliation under §5.7. Bootstrap cannot invent a lost acknowledged-write history.

1. Authenticate the provisioning operator. Validate evidence bound to cluster, new incarnation,
   configured membership, and the selected initial writer. Inspect provider state and all nodes.
   Persist an operation with `WITHDRAWN` intent and pending policy revision 1. Leave confirmed
   policy and previous writer absent. In reference A, atomically check key absence and acquire
   ownership with the withdrawn initial value; a conflicting record rejects creation. In B, use the provider's guarded
   begin-transition. Reject bootstrap when prior admission or conflicting actions are found.
2. Complete the provider generation barrier. Close all grants, stop every configured database,
   and confirm restart inhibition. Unknown or unreachable nodes prevent bootstrap completion.
3. Through a guarded provider bootstrap action, permit the selected initial primary to start
   with application admission closed. Start its provisioned physical standbys under promotion
   and writer-start inhibition. Validate independent node identities, common database identity,
   roles, timeline, and authenticated replication. Ordinary covered-target promotion is not
   used to create this initial policy.
4. With application writes excluded, observe a fresh writer WAL flush boundary. Validate every
   required standby's flush and replay against it. Install and verify the quoted policy through
   the provider. Conditionally confirm revision 1 and clear pending intent. No missing peer,
   failed observation, or incomplete receipt permits preparation.
5. Prepare, publish `SERVING`, and activate the exact matching grant under current ownership.
   Sidecars deny traffic before this conjunction. Verify a production-manager write under the
   required commit policy. Bootstrap completion is derived from the existing records and checks.

Persist immutable bootstrap parameters in the existing action receipt. Interrupt every step,
including first intent creation, guarded start, policy confirmation, publication, and activation.
Inspect a lost reply before resuming the same operation. A successor preserves pending intent
and provisioning evidence, retires prior effects, and completes a new barrier. It must not infer
bootstrap completion from running processes or restart from an empty key. No separate bootstrap
ready flag, policy authority, or progress table is introduced.

---

## 6. Client Tier: PeeGeeQ Connection Management

### 6.1 Class structure

```
PeeGeeQManager (public facade)
  └── PgClientFactory
        └── PgConnectionManager        owns every pool, keyed by serviceId
              └── PgBuilder.pool()  ──► io.vertx.pgclient  (Vert.x 5.x)
```

`PgConnectionManager` creates and owns every pool in the process. No module builds a pool of its
own. A module that needs different pool options registers a named `serviceId` with
`PgConnectionManager` and receives a pool built from the shared connection configuration and
its own pool configuration.

### 6.2 Configuration

`PeeGeeQConfiguration` is the single entry point. The profile name is passed explicitly. It is
never taken from a system property or an environment variable.

```properties
# Direct database address
peegeeq.database.host=localhost
peegeeq.database.port=5432

# Endpoint of the proxy or writer endpoint. When set, it replaces host and port above.
peegeeq.database.proxy.host=
peegeeq.database.proxy.port=

# Endpoint for dedicated LISTEN connections. Blank values inherit the effective endpoint.
peegeeq.database.listen.host=
peegeeq.database.listen.port=

peegeeq.database.name=peegeeq
peegeeq.database.username=peegeeq
peegeeq.database.password=peegeeq
peegeeq.database.schema=myschema
peegeeq.database.ssl.enabled=false

# Pool
peegeeq.database.pool.max-size=32
peegeeq.database.pool.max-wait-queue-size=128
peegeeq.database.pool.connection-timeout-ms=30000
peegeeq.database.operation-timeout-ms=30000
peegeeq.database.statement-timeout-ms=25000
peegeeq.database.pool.idle-timeout-ms=600000
peegeeq.database.pool.max-lifetime-ms=1800000
peegeeq.database.pool.shared=true

# Dedicated LISTEN connections
peegeeq.database.listen.probe-interval-ms=5000
peegeeq.database.listen.probe-timeout-ms=1000
peegeeq.database.listen.connect-timeout-ms=5000
peegeeq.database.listen.initialization-timeout-ms=10000
peegeeq.database.listen.catch-up-timeout-ms=30000
peegeeq.database.listen.replay-poll-interval-ms=1000
peegeeq.database.listen.reconnect-max-delay-ms=5000

# Authenticated read-only writer status through redundant HTTP proxies (§6.6).
# Required in A and B. The client does not receive Consul or node-control credentials.
peegeeq.database.eligibility.url=https://writer-status.example.internal/writer
peegeeq.database.eligibility.auth-reference=deployment-secret-reference
```

**Effective endpoint.** `PeeGeeQConfiguration.getDatabaseConfig()` resolves one effective host
and port: the proxy values when they are set, the direct values otherwise. Every pool uses the
effective endpoint. Every dedicated LISTEN connection uses the LISTEN endpoint, which is the
effective endpoint unless `peegeeq.database.listen.*` is set (§8).

**Schema.** Every deployment supplies `peegeeq.database.schema`. The shipped value is a
placeholder. A blank schema is rejected at configuration time.

**Environment variables.** Each key has an environment form, for example
`PEEGEEQ_DATABASE_PROXY_HOST`.

### 6.3 Pooled access

Every module performs pooled database work through two methods:

- `PgConnectionManager.withTransaction(serviceId, operation)` for writes;
- `PgConnectionManager.withConnection(serviceId, operation)` for reads.

This applies to the outbox, the native queue, the bitemporal event store, the dead-letter and
recovery managers, the metrics collector, and the subscription and consumer-group services. No
module calls `withTransaction` or `withConnection` on a `Pool` object directly.

Each call does four things in order:

1. **Acquires breaker permission** for `db.pool.<serviceId>` (§6.5). An open breaker fails the
   call with `CallNotPermittedException` before any connection is taken.
2. **Takes a connection** and begins a transaction.
3. **Applies the schema** as transaction-local state:
   `SELECT set_config('search_path', $1, true)`. The same value is also set as a startup
   parameter on every connection. The transaction-local form makes the schema correct through a
   transaction pooler (R-14).
4. **Runs the caller's operation**, reinstates the required commit durability policy for a
   write, then commits or rolls back. The manager owns transaction completion. Callers cannot
   issue independent commits.

The schema value is validated against the characters `[A-Za-z0-9_,\s]` before use.

### 6.4 Connection lifetime and discard

A pooled connection ends in one of four ways (R-5):

| Trigger | Action |
|---|---|
| The connection reaches `pool.max-lifetime-ms` | The pool closes it when it is next idle and opens a new one through the endpoint. |
| The connection is idle for `pool.idle-timeout-ms` | The pool closes it. |
| The server ends the session: SQLSTATE class `08`, or `57P01`, `57P02`, `57P03` | The connection is closed and never returned to the pool. |
| A required writer operation fails with SQLSTATE `25006` | Close the connection and reconcile writer eligibility. Deliberately read-only business operations do not establish node demotion. |

Maximum lifetime bounds reuse after the connection returns idle. It does not bound an active
transaction. Session shutdown, explicit operation deadlines, and fencing establish recovery
and safety. SQLSTATE `25006` can also result from a read-only transaction on a primary. Do not
infer role from that error alone.

The original failure is always returned to the caller. PeeGeeQ does not retry a failed
transaction on the caller's behalf.

### 6.5 Circuit breaker

Each pool has one Resilience4j circuit breaker named `db.pool.<serviceId>`. `PeeGeeQManager`
creates one `CircuitBreakerManager` and shares it with every pool and with the health checks.

- The breaker brackets the whole pooled call in §6.3 (R-9).
- It records classified database availability failures: connection refusal or loss, connection
  deadline, database resource rejection, and server shutdown. Wait-queue saturation is reported
  distinctly from database reachability. Deliberate cancellation, business read-only errors,
  and other SQL errors do not become availability failures solely by SQLSTATE class.
- It records a success for a call that reaches the database and completes, including a call
  whose statement fails with a business SQL error such as a constraint violation (R-10).
- One pool's breaker does not affect another pool.
- `getReactiveConnection()` is not bracketed. It exists for callers that own a connection
  explicitly and for recovery probes.

The breaker thresholds come from the `peegeeq.circuit-breaker.*` keys.

### 6.6 Dedicated LISTEN connections

Two components hold a dedicated, non-pooled connection for `LISTEN`: the bitemporal
`ReactiveNotificationHandler` and the native queue `PgNativeQueueConsumer`. Both follow the same
rules.

**Client eligibility interface.** Clients use one authenticated read-only HTTP endpoint,
`peegeeq.database.eligibility.url`. Redundant HTTP proxies route `/writer` to sidecars using
the same `/primary` checks as the SQL backend. A sidecar recomputes eligibility for every
response. A stale HTTP route returns 503 when its node fails the checks. Clients hold no node
host list and no Consul or node-control token. SQL and HTTP endpoints share the configured
cluster and incarnation. Profile C requires an equivalent provider contract. D does not
qualify authority-sensitive acceptance.

`GET /writer` returns 200 only for an eligible writer. Its JSON contains `profile`, `clusterId`,
`incarnation`, `nodeId`, `generation`, `operationId`, and `policyRevision`. Automatic generation
contains the control key, lock index, and session. Manual generation contains the provider's
monotonic admission generation. These fields are derived from the live check, not persisted
as another authority record. Return 503 on uncertainty. Authentication failures return 401
or 403. Do not return an old success on a dependency failure.

Deployment sets PostgreSQL's node-local `peegeeq.node_id` setting from authoritative node
configuration. Reapply it after rewind or rebuild; never copy the former node's identity.
The client reads `current_setting('peegeeq.node_id', true)` and recovery mode on its dedicated
connection. Missing identity, wrong cluster response, node mismatch, or recovery mode true
closes that connection. Sidecars also compare the database setting to their configured node
identity. This observation is not a fencing mechanism. Privileged identity spoofing is outside
the trusted deployment fault model.

**Initialization.** Each attempt has a process-local identity. Open the LISTEN endpoint,
verify SQL identity and `/writer` eligibility, then issue and acknowledge every `LISTEN`.
The connection and initialization deadlines cover transport and subscription setup. LISTEN
acknowledgement includes transaction completion. Do not start a database replay snapshot
before channel acknowledgement. [PostgreSQL LISTEN](https://www.postgresql.org/docs/current/sql-listen.html)

**Reconnect (R-6).** Use a base delay of 1 second, double after each failed attempt, and cap
at `listen.reconnect-max-delay-ms`. Choose jitter uniformly from 80% to 100% of that base.
The delay never exceeds the cap. There is no attempt limit while a subscriber remains active.
Socket establishment does not reset backoff. Reset only after eligibility, channel
acknowledgements, and required finite catch-up succeed. SQL, HTTP, LISTEN, or catch-up failure
retains the failure count. Only one initialization or replay pass runs per subscription.

**Probe (R-7).** Run every `listen.probe-interval-ms` without overlapping probes. The total
`listen.probe-timeout-ms` covers SQL identity and role plus the HTTP eligibility request.
Check before subscription initialization and after catch-up as well. A generation or policy
change invalidates the completed initialization and requires revalidation and finite catch-up.
Missing, malformed, unauthorised, mismatched, failed, or timed-out results close the connection
and fail readiness. A late completion from a retired attempt cannot retain a connection,
reset backoff, acknowledge progress, or restore readiness.

The default scheduled detection budget is the 5-second interval plus the 1-second request
deadline, subject to measured scheduler delay. Probe traffic prevents ordinary idle expiry
only while probes execute. Event-loop stalls can still cause proxy disconnection.

**Durable bitemporal catch-up (R-8).** Reuse `DurableBiTemporalSubscriptionCoordinator` and
`DurableBiTemporalDelivery`. The selected algorithm is the existing stable writer barrier:

1. Load the tenant-local definition and committed `last_processed_id`. Acquire its existing
   replay lease. Ownership conflict is not successful catch-up for readiness; wait for observed
   committed progress under the catch-up deadline or report unavailable.
2. In a short READ COMMITTED transaction, apply a bounded lock wait and acquire `LOCK TABLE
   <event table> IN SHARE MODE`. Validate the ascending, non-cycling, `CACHE 1` sequence.
   IDs are allocated by INSERT. Explicit or preallocated IDs, sequence resets, and deletion
   or mutation of replay history are unsupported by this contract. The barrier lock deadline
   is the lesser of the existing 5-second limit and the remaining catch-up budget.
3. After prior writers finish, read `MAX(id)` as a finite boundary H. Commit and release the
   table lock before invoking handlers. H is transient scan state. On reconnect, compute a
   fresh boundary. No second cursor table is needed.
4. Page rows where committed cursor < id <= H in ascending ID order. Apply the persisted
   filters. For a matching row, await handler success before cursor advancement. Advance past
   a nonmatching row only after evaluating it. Cursor updates verify the existing lease owner
   and generation in a transaction. Failure preserves the last confirmed cursor.
5. Mark finite catch-up complete only after a successful final page and confirmed cursor
   updates for all applicable rows through H. Revalidate writer eligibility. Newer rows are
   handled by later passes; sustained new traffic does not make the initial boundary infinite.

This is stable append-ID delivery order, not transaction commit-time order. The writer barrier
prevents an earlier allocated ID from becoming visible after the scan has passed it, within
the stated append contract. Aborted allocations create permitted gaps. A timeout acquiring
the barrier fails the pass; an empty page after a failed barrier is never success.
[PostgreSQL lock conflicts](https://www.postgresql.org/docs/current/explicit-locking.html)
define the database synchronisation used by this contract.

LISTEN notifications only request the same serialized durable scan. They never dispatch a
parallel live-delivery path or directly advance the cursor. Periodic reconciliation uses
`listen.replay-poll-interval-ms` and covers missing notifications. Wake-ups during a pass request
another pass. No notification payload is durable delivery history.

The logical delivery identity is derived from tenant, event table, durable subscription,
consumer group, and event row ID. Keep the existing event ID in the handler message. Handler
success followed by an unknown or failed cursor commit can cause redelivery. Delivery is at
least once. Callers use stable identity for idempotent processing. External effects are not
exactly once. Do not infer a processing result from a notification or the largest allocated ID.

**Native catch-up (R-8).** Reuse the existing topic claim, delivery, acknowledgement, visibility,
and delayed-message wake-up paths. One recovery pass is one executed claim batch, bounded by
configured batch size and available processing capacity. It is not an unbounded recursive drain.

1. After channel acknowledgement, verify the active subscription, handler, and current attempt.
   Reserve processing capacity under the catch-up deadline. Missing handler, inactive or closed
   subscription, and occupied capacity do not constitute a successful pass. Capacity exhaustion
   waits for observed capacity or fails the deadline; a skipped/deferred return cannot report ready.
2. Execute the real claim transaction through the production manager for the configured topic,
   filters, and start position. Observe its commit. Record executed-empty, claimed batch, deferred,
   or failed as transient attempt outcomes. A database-confirmed empty claim is successful work;
   it does not establish that the whole queue is empty, including rows locked by other consumers.
3. For the finite claimed batch, observe handler success and confirmed existing acknowledgement
   mutations. Failure or an uncertain claim/acknowledgement outcome fails readiness. Reconcile
   using existing message identity and visibility state before another attempt. Do not retry an
   uncertain transaction as though it rolled back or add a second acknowledgement ledger.
4. Observe the existing delayed-message query and arm its required bounded wake-up when eligible
   work is delayed. Failed scheduling or observation fails the pass. Subsequent arrivals and
   deferred drain requests schedule later work; they do not extend this pass's finite batch.
5. Revalidate SQL identity and writer eligibility, then complete this attempt's native recovery.
   A retired completion cannot acknowledge recovery, reset backoff, or restore readiness.

The catch-up deadline covers capacity waiting, claim, handlers, acknowledgements, delayed-work
scheduling, and final eligibility checks. Queue backlog is a workload metric. Native recovery
does not promise delivery of the entire outage backlog before readiness. No numeric replay cursor
or durable recovery-ready flag is introduced.

**Non-durable catch-up.** Non-durable bitemporal subscriptions have no stored position and can
lose notifications during disconnection. Required loss-free recovery uses a durable subscription.

**Deadlines and shutdown.** `listen.catch-up-timeout-ms` bounds one finite catch-up pass,
including lease acquisition, barrier, paging, handlers, and cursor commits. Expiry fails
readiness and the attempt. Observe cleanup and reconcile uncertain cursor commits before
restarting. Do not release or replace a replay lease while an earlier cursor mutation can
still take effect. Application callback effects cannot be cancelled by a client timeout;
late effects retain the at-least-once contract. Intentional close cancels timers, prevents new
attempts, observes in-flight work, issues `UNLISTEN`, and closes owned resources.

**Health.** Socket status, current eligibility, channel acknowledgements, and completed finite
catch-up are separate observations. Readiness requires all applicable conditions. Dependency
errors remain visible. The 45-second recovery objective requires measured backlog and handler
limits; independent request deadlines are not an end-to-end performance guarantee.

### 6.7 Timeouts

- Pool connection and idle timeouts are passed to Vert.x in milliseconds with an explicit unit.
  A configured value of 500 ms is applied as 500 ms (R-13).
- Every pool in the process takes its timeouts from `PgPoolConfig`. No pool sets a literal
  timeout.
- A dedicated LISTEN connection uses its own connect, initialization, probe, and catch-up
  deadlines. Validate positive values, probe timeout below probe interval, connect timeout
  no greater than initialization timeout, and replay poll interval below catch-up timeout.
- A health check is bounded by `peegeeq.health.timeout`.
- `peegeeq.database.operation-timeout-ms` bounds the full call, including acquisition,
  statements, and commit. `peegeeq.database.statement-timeout-ms` bounds server execution and
  must be shorter than the operation deadline. These are proposed configuration contracts.
- An expired client deadline stops reuse of the connection, attempts cancellation and cleanup,
  and observes their outcomes. It does not imply that a transaction rolled back. Server-side
  statement limits do not bound a frozen server or a lost network response.
- Every probe, Consul read or mutation, fencing request, promotion, and rewind observation has
  a request deadline. A timed-out mutation is reconciled from authoritative state.

---

## 7. Health Reporting

| Route | Meaning | Response |
|---|---|---|
| `/health/live` | The process is running and its HTTP server answers. | Always 200. |
| `/health/ready` | The instance can do its work. | 200 when every required check is healthy. 503 otherwise, with the failing checks in the body. |
| `/health` | Same as `/health/ready`. | Same. |
| `/api/v1/setups/:setupId/health` | Readiness of one setup. | 200 or 503. |

Readiness is the result of `HealthCheckManager.getOverallHealthAsync()` (R-11). Its required
checks are:

- **database**: bounded access through the configured pool and schema, plus node identity,
  writer eligibility, and the required durability state. A successful `SELECT 1` alone does
  not establish write capability;
- **listen**: every required subscribed connection is eligible, channels are acknowledged, and
  required catch-up has completed;
- **memory** and **disk-space**.

The queue checks (outbox backlog, native queue, dead-letter rate) report a status of their own.
They are not bracketed by the `database` breaker and do not count toward opening it. A backlog
is a workload condition, not a database failure.

`HealthCheckManager` runs a cycle on its timer (`peegeeq.health.check-interval`) and on demand
when a readiness request arrives and no cycle is in flight.

Any load balancer, orchestrator, or cluster manager in front of PeeGeeQ instances can use
`/health/ready` to decide whether an instance receives requests. How requests are routed between
instances is outside this design.

---

## 8. PgBouncer

PgBouncer is optional. When present it sits between PeeGeeQ and HAProxy, and
`peegeeq.database.proxy.*` points at its stable redundant endpoint:

```text
Pooled clients -> stable SQL address -> PgBouncer A or B -> stable HAProxy SQL address -> writer
LISTEN clients ----------------------> stable HAProxy SQL address ------------------> writer
All clients -> stable eligibility HTTP address -> redundant HTTP proxies -> eligible sidecar
```

Production has at least two PgBouncer instances in separate failure domains. Each uses the
HAProxy stable backend address and identical verified routing, authentication, and pool
settings. The client address has one selected owner or a managed load balancer with bounded
failure detection. Readiness excludes poolers whose backend path is unavailable. A surviving
failure domain must retain a complete route. Duplicating poolers behind an unqualified single
address owner is insufficient.

PgBouncer does not transfer an existing client transaction to another instance. Pooler or
address-owner loss requires new client connections. Propagate interrupted transactions and
unknown commit outcomes. Do not replay them in a proxy. G-3 qualifies both address layers,
both pool modes, new connections, active transactions, idle sessions, and LISTEN bypass.

| Pool mode | Pooled operations | LISTEN connections |
|---|---|---|
| Session | Supported. | Supported through PgBouncer. |
| Transaction | Supported. The schema is applied as transaction-local state on every call (§6.3). | Not possible through PgBouncer. `LISTEN` needs a session. Set `peegeeq.database.listen.host` and `.port` to the HAProxy endpoint so LISTEN connections bypass PgBouncer. |

Native PgBouncer configuration fragment for transaction mode. This is an INI fragment, not
container-specific environment-variable mapping:

```ini
[pgbouncer]
pool_mode = transaction
ignore_startup_parameters = extra_float_digits,search_path
max_prepared_statements = 32
server_reset_query = DISCARD ALL
server_reset_query_always = 1
```

PgBouncer accepts the client's `search_path` startup parameter and does not forward it. The
transaction-local `set_config` in §6.3 supplies the schema.

The reset runs between transactions. No pooled operation depends on retained session state.
Pin the deployed image and version. Verify prepared-statement and reset behaviour against
that version. [PgBouncer configuration](https://www.pgbouncer.org/config.html)

After HAProxy closes a pooler's server connection, that pooler must establish an eligible
connection through HAProxy. In-flight work fails or has an unknown outcome. A subsequent
application operation uses the shared manager's schema, durability, and eligibility contract.
Session transfer or transparent transaction retry is not part of this design.

---

## 9. The pg-sidecar Service

`peegeeq-pg-sidecar` reports writer eligibility for profiles A and B. One sidecar runs beside
each PostgreSQL node. It gives HAProxy the role, authority, and admission result required for
routing. It uses the reactive PostgreSQL client. Native production packaging requires the
verification described in §9.4.

### 9.1 Contract

```text
GET /primary
  200: writable local role, OPEN matching provider grant, current authority, synchronous coverage
  503: standby, quarantine, mismatched grant, missing coverage/authority, dependency failure/deadline
GET /writer
  Same eligibility predicate; 200 includes the derived JSON contract in §6.6.
  401/403 for unauthorised clients. No cached success.
Any other path: 404
```

Automatic mode requires a consistent Consul read, a present session, phase `SERVING`, and
`writerNodeId` matching this node. A retained value on an unlocked key grants no traffic.
No cached success is used when authority cannot be established.

Match the provider grant to the authority's node, mode, generation, operation, and confirmed
policy revision. Derive required-peer health from live replication observations. Prepared or
retired grants, pending policy alone, and two different generations never produce 200.
Use the same check implementation for `/primary` and `/writer`. Return `Cache-Control: no-store`
and disable successful-response caching in every HTTP proxy. Compare SQL node-local identity
with configured identity. Observe sufficient replication metadata with a dedicated role.

Manual mode requires operator-controlled admission, fencing of the former writer, and enforced
restart quarantine. Simply disabling the automatic controller cannot select manual authority.

One end-to-end deadline covers acquisition, SQL, Consul, admission checks, and HTTP response.
Observe every asynchronous result and propagate or report dependency failures. The sidecar
never modifies authority or performs node-control actions.

### 9.2 Configuration

The sidecar reads JVM system properties.

| Property | Default | Description |
|---|---|---|
| `pg.host` | `localhost` | Address of the local PostgreSQL node |
| `pg.port` | `5432` | PostgreSQL port |
| `pg.database` | `postgres` | Database to connect to |
| `pg.user` | `haproxy_check` | PostgreSQL role |
| `pg.password` | deployment secret | Non-empty secret or explicit secure local authentication |
| `pg.query-timeout-ms` | `1000` | End-to-end eligibility request deadline |
| `http.port` | `8008` | HTTP listen port |
| `pg.sidecar.mode` | required | Explicit automatic or manual authority |
| `peegeeq.pg.cluster-id`, `peegeeq.pg.cluster-incarnation` | required | Controller namespace |
| `peegeeq.pg.node-id` | required | Independent node-local identity |

### 9.3 PostgreSQL role

The sidecar logs in as `haproxy_check` and observes recovery mode, node identity, and session
write capability. The role has no application-write or node-control privileges.
`pg_is_in_recovery()` is callable by every role, so no function `GRANT` is required.
PostgreSQL's `pg_hba.conf` restricts the role's origin and database. Do not revoke `CONNECT`
from `PUBLIC` as a sidecar-specific restriction. The role cannot promote or mutate authority.

### 9.4 Build and run

The target packaging provides a runnable jar and a verified GraalVM native artifact. Native
argument parsing, artifact names, image compatibility, and configuration require release
validation. JVM-style `-D` properties are the explicit configuration contract. A runnable
example requires the selected admission integration, explicit mode and identities, and
verified loader bindings. The sidecar guide owns those packaging and configuration steps.

A container image must pass settings through its verified entry point. Docker environment
variables do not automatically become Java system properties. Packaging and resource claims
require measurements; no native startup or memory guarantee is assumed.

### 9.5 Division of work

The sidecar reports eligibility. It does not promote or fence. `PgFailoverMonitor` orchestrates
the transition. The node-control provider performs guarded promotion and fencing (§5.5).

---

## 10. Required Behaviour by Scenario

These are acceptance requirements, not current coverage claims. Profile A uses three database
nodes unless stated otherwise. Assert node identity, authority, role, admission, and durability
separately. Every dependency failure must remain visible.

| ID | Scenario | Required behaviour |
|---|---|---|
| S1 | Production primary stops | Withdraw and revoke traffic, confirm fence, promote a covered standby, attach its synchronous peer, confirm policy and activate the writer. Measure committed work and finite catch-up against the qualified recovery objective. |
| S2 | Former writer re-joins as standby | It remains ineligible for writer traffic. Validate rewind or rebuild, timeline, recovery mode, and observed WAL replay. |
| S3 | Eligible standby is promoted | Every acknowledged pre-failure write remains present. New writes commit under the replacement synchronous policy. |
| S4 | Manual mode primary stops before promotion | No replacement writer is admitted. Readiness stays down. |
| S5 | Eligibility reporting | Matching current serving intent, open grant, node identity, writable role, and live required coverage give 200. Standby, stale primary, prepared grant, quarantine, missing authority, or missing coverage gives 503. |
| S6 | Former writer restarts before rewind | Restart inhibition or quarantine prevents writer admission. No PeeGeeQ write reaches it. |
| S7 | Long LISTEN outage | Reconnect continues without an attempt limit. Acknowledged channels and required durable catch-up precede readiness. |
| S8 | LISTEN remains on an ineligible node | Authority or role probe detects it, closes it, and reconnects with separate detection and recovery deadlines. |
| S9 | Database freezes without TCP reset | Eligibility, operation, and probe deadlines expire. Cleanup is observed. Fencing remains mandatory before replacement admission. |
| S10 | Proxy restarts | Clients reconnect through the stable endpoint. No lost response is interpreted as rollback. |
| S11 | PgBouncer transaction mode | Every module applies its schema per transaction. LISTEN bypasses the transaction pooler and catches up. |
| S12 | Instance loses its database | Readiness fails with specific checks. Liveness remains available. Readiness returns only after eligibility, durability, and required catch-up succeed. |
| S13 | Write interrupted around commit | A confirmed commit survives eligible promotion. A lost acknowledgement reports unknown outcome and is reconciled by operation identity. No automatic transaction retry. |
| S14 | Read-only error | A required writer operation discards its connection and reconciles eligibility. Deliberately read-only business operations do not prove node demotion or open the availability breaker. |
| S15 | Old primary is partitioned from control but reachable by applications | No replacement serves until independent fencing is confirmed. Failed fencing preserves unavailability. |
| S16 | Two controllers | Only current generations authorise effects. Obsolete commands are rejected at the node-control boundary. Retries do not authorise a second writer. |
| S17 | One of three Consul servers fails | Quorum permits current authority and real failure handling. No promotion follows merely from server loss. |
| S18 | Consul quorum or connectivity is lost with healthy PostgreSQL | No automatic promotion. Sidecars deny unverified authority. Reconcile on return. |
| S19 | Controller dies while database remains healthy | Successor reconciles and restores the same safe writer. No database promotion solely from controller failure. |
| S20 | Fence throws, rejects, or returns malformed/null evidence | Transition fails visibly. No replacement admission. |
| S21 | Fence completes but response is lost | Inspect the same operation. No guessed success and no unguarded repeat or release. |
| S22 | Promotion completes but response is lost | Reconcile role, provider receipt, generation, and replay before publishing authority. |
| S23 | Old controller response or command arrives after ownership change | It cannot promote, release a fence, or publish serving authority. |
| S24 | Controller or provider restarts in each transition state | Durable intent, highest generation, and quarantine prevent unsafe replay. |
| S25 | Required synchronous standby is lost | Writes block or fail within the operation deadline. No automatic asynchronous downgrade. |
| S26 | Pool acquisition, statement, commit, cancellation, or close fails | Assert specific failure and unknown outcome where appropriate. Cleanup failure remains observable. |
| S27 | Notification and replay overlap, with commits arriving out of ID order | The writer barrier establishes a stable boundary. One serialized scan processes every applicable row through it in ID order. Verify acknowledged cursor updates and at-least-once redelivery semantics. |
| S28 | One production proxy or address owner fails | Stable endpoint transfers safely and clients recover. Test existing sessions and new connections. |
| S29 | Two proxies observe different eligibility states | Hard fencing prevents overlapping writers despite delayed health observations. |
| S30 | Unlocked, malformed, missing, or stale authority record | No serving response. No default-primary interpretation. |
| S31 | Rewind fails or required WAL is missing | Preserve quarantine. Rebuild and validate before standby admission. |
| S32 | Planned switchover | Drain or reject work, fence the former writer, establish replacement synchronous policy, and apply the same transition contracts. |
| S33 | Supervisor attempts to restart a fenced node | Durable restart inhibition prevents writable start. |
| S34 | Node-control validation and effect race with ownership change | Reject newly admitted retired-generation commands. Reconcile previously accepted in-flight effects under quarantine before a successor activates a writer. |
| S35 | Replicated nodes have the same PostgreSQL system identifier | Independent node-local identity identifies the actual connection destination. |
| S36 | Coordination state is restored or lock namespace is reset | Fence all nodes, reconcile provider state, and use a new incarnation before resuming. |
| S37 | Two independent database clusters share Consul | Namespace and ACL isolation prevent cross-cluster control. |
| S38 | Two-node synchronous pair loses its primary | Promotion does not silently enable local-only writes. Restore a synchronous standby before committed-write readiness. |
| S39 | Membership or profile changes | Controlled reconciliation preserves authority and durability. A toggle cannot bypass fencing. |
| S40 | Eligible target lacks prior acknowledged WAL coverage | Reject promotion. A healthy or reachable node is not automatically eligible. |
| S41 | Writer preparation, publication, activation, or revocation is interrupted | Closed or prepared grant denies traffic. Lost activation reply is inspected. Revocation defeats delayed activation. Test every restart boundary in A and B. |
| S42 | New generation has not reached one node | Per-node counters alone do not complete the barrier. Confirm installation or independently fence it; reconcile prior effects before opening any grant. |
| S43 | Synchronous configuration is generated | Load individually quoted names on real PostgreSQL. Verify effective policy, authenticated identities, case-insensitive uniqueness, and every required acknowledgement. Reject empty sets, self-reference, duplicates, and unknown members. |
| S44 | Writer fails during standby re-join or policy cutover | Use confirmed coverage; exclude the pending peer. Interrupt every step, restore the two-peer policy, then repeat failover and verify acknowledged data. |
| S45 | TCP connect succeeds but eligibility, LISTEN, or catch-up repeatedly fails | Backoff increases and remains capped with bounded jitter. Transport success does not reset it. Retired attempt completions cannot restore readiness or advance progress. |
| S46 | Client writer-status request fails or mismatches SQL identity | Failed, thrown, malformed/null, stale, unauthorised, timed-out, wrong-cluster, or wrong-node results fail readiness and retire the connection. No cached success. |
| S47 | Replay barrier times out or low ID is still uncommitted | No cursor passes the missing row. Failure does not become empty-history success. On eligible recovery, repeat a fresh finite boundary and process all committed rows in ID order. |
| S48 | PgBouncer instance or its address owner fails | The surviving complete SQL path accepts new connections. Active transactions fail or report unknown commit; no proxy retries. Test session and transaction modes and LISTEN bypass. |
| S49 | Consul mutation has wrong session/revision or unauthorised credentials | Guarded transaction makes no change when a condition fails. ACL denies outsiders. Verify acquire, retained policy history, update, lost reply, and guarded release separately. |
| S50 | Handler succeeds but cursor commit or lease cleanup is uncertain | Inspect committed progress. Redelivery uses stable identity. Lease conflict is not catch-up completion; no stale owner advances the cursor or reports ready. |
| S51 | First-start bootstrap is interrupted or requested against existing/ambiguous history | Only authenticated verified provisioning can create initial intent. Keep grants closed through fences, guarded start, peer validation, and policy confirmation. Inspect lost replies and ownership changes at every step. Missing history alone never authorises bootstrap. |
| S52 | Native recovery is skipped, capacity-bound, delayed, or interrupted | Require an executed finite claim pass. No-handler, inactive, closed, and deferred branches cannot establish readiness. Verify confirmed acknowledgements, delayed wake-up scheduling, continuous arrivals, dependency failures, unknown outcomes, and retired-attempt rejection. |
| S53 | Application operation attempts to weaken commit policy | Before failover assertions, prove the production manager reinstates required durability at its owned commit boundary. A missing required peer cannot yield an acknowledged write under weakened settings. Verify lost commit responses and acknowledged data after eligible promotion. |

## 11. Environments

### 11.1 Development stack (profile D)

`scripts/local-infra/docker-compose-failover-local.yml` runs two independent PostgreSQL nodes
behind HAProxy with `pgsql-check`, and PgBouncer in session mode, with transaction mode under
the `transaction-pool` Compose profile. It exists to exercise pool reconnection during local
development. It has no sidecar and no failover monitor, and is not a model of production.

### 11.2 Failover stack (profiles A and B)

`scripts/local-infra/docker-compose-failover-replication.yml` runs:

- `pg-node-1` as primary and `pg-node-2` and `pg-node-3` as synchronous physical standbys,
  with rewind prerequisites and the explicit acknowledgement policy in §5.8;
- one `peegeeq-pg-sidecar` per node;
- redundant HAProxy instances with the configuration in §5.3 and a stable address owner;
- a three-server Consul cluster for quorum fault tests;
- the proposed `peegeeq-pg-failover` controller;
- a real local node-control adapter with enforced stop and restart inhibition;
- redundant PgBouncer instances in both modes with a tested stable client address;
- a redundant authenticated HTTP eligibility frontend routing to sidecars.

Profile A requires automatic sidecar mode and a valid node-control provider. Profile B requires
manual mode and operator admission. Select a profile while stopped and reconcile before serving.
This is a target stack definition, not a claim that the Compose file implements it.

### 11.3 Scenario runbook

`scripts/local-infra/FAILOVER-SCENARIOS.md` gives, for each scenario in §10, the fault command,
the observation command, and the required result. Faults are injected with Docker:

| Fault | Command |
|---|---|
| Clean stop | `docker compose -f <file> stop <service>` |
| Crash | `docker compose -f <file> kill -s SIGKILL <service>` |
| Freeze with no TCP reset | `docker compose -f <file> pause <service>` |
| Network partition | `docker network disconnect <network> <container>` |
| Manual promotion (profile B) | Use the runbook's guarded promotion after fencing and synchronous-target validation; no standalone promotion command establishes safety |
| Consul node loss | `docker compose -f <file> stop <consul-service>` |
| Proxy loss | `docker compose -f <file> restart haproxy` |

---

## 12. Verification

Every scenario in §10 requires automated acceptance coverage with real components. This is a
coverage requirement. The implementation plan records dated runs and must not claim current
coverage from these requirements.

Automatic acceptance uses three PostgreSQL nodes, Consul quorum, sidecars, redundant HAProxy,
and real node-control effects. Inject a primary crash, confirm its fence, promote a covered
target, establish the surviving synchronous standby, and assert acknowledged data and new
committed work through the stable endpoint. Verify LISTEN catch-up and fenced standby re-join.
A two-node test separately proves S38.

- **Identify the node.** Use node-local identity and server address. Physical replicas share
  `system_identifier`. Recovery mode alone does not identify a node or authority.
- **Prove the safety boundary.** Continuously attempt uniquely identified writes through every
  application route during partitions and transitions. Assert fencing and authority, not
  only successful queries after recovery.
- **Observe with deadlines.** Poll real postconditions. Do not use fixed readiness waits.
- **Assert failures.** Check exception type, SQLSTATE, admission state, and unknown outcomes.
- **Inject dependency modes first.** Failed Futures, synchronous throws, null or malformed
  results, timeouts, lost responses, stale completions, and silent open connections each need
  a failing test before implementation.
- **No mocks or silent teardown.** Use established Testcontainers factories and production
  schema initialization. Observe every asynchronous result. Close failures fail the test.

Run each class with the profile matching its tag and save output through `Tee-Object`.
Rebuild the affected reactor slice before downstream testing after Java or Maven changes.
Record every per-class count. Zero executed tests is failure.

The local node-control adapter does not qualify a production provider. Each production adapter
needs deployment-specific partition, restart, stale-generation, and effect-boundary evidence.
Select that adapter and admission integration before implementing manual or automatic promotion.
Use the existing durable replay tests as source patterns, then add failover and uncertain-cursor
cases without creating a second progress mechanism. No claim of complete safety or
runtime timing is made before these gates pass.
G-7 must record the automatic backend before phase 8. A replacement backend repeats authority,
partition, expiry, restoration, and credential cases against its mapped contracts. S51 and S53
are phase 7 prerequisites; S52 must pass before native subscription readiness is integrated.

## Appendix A: Primary Detection Options

TCP and `pgsql-check` establish availability, not writer authority. A role-only sidecar does
not establish fencing. The complete comparison is in
[PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md](PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md).

| Approach | Promotion | Safety obligation | Used here |
|---|---|---|---|
| Sidecar, Consul controller, and node-control provider | Automatic | Current generation, confirmed fencing, synchronous target, and reconciliation | Profile A |
| Sidecar with operator admission and fencing | Manual | Same single-writer and durability invariants | Profile B |
| Managed writer endpoint | External operator | External evidence for equivalent contracts | Profile C |
| Role-only HTTP or shell agent | None | Missing authority and fencing | No production profile |
| Patroni or repmgr | External cluster manager | Deployment-specific guarantees still require verification | Not selected |

No profile requires Patroni. Reusing Consul does not remove the node-control dependency.
The selected PeeGeeQ controller has its own lifecycle and module. Select the provider and
admission contract first. Implement and verify B, then add and qualify automatic authority in A.

## Appendix B: The JDBC Multi-Host Pattern

The PostgreSQL JDBC driver accepts a list of hosts and selects one by role:

```
jdbc:postgresql://host1:5432,host2:5432/database?targetServerType=primary
```

PeeGeeQ does not use this pattern, for two reasons.

**It does not fit the runtime.** JDBC is a blocking protocol. PeeGeeQ runs on the Vert.x
reactive client, which takes one host and port and has no equivalent of `targetServerType`.

**It puts the role decision in every client.** Each process walks the host list on its own, so
two processes can reach different nodes during a role change. Nothing fences a returning old
primary. Adding or removing a node means reconfiguring every process. A long-lived pool keeps
its connections to a demoted node.

A single endpoint with one role authority behind it keeps the decision in one place. That is
the basis of this design.
