# PostgreSQL Failover with Consul and Local Supervision — Design

**Author**: Mark A Ray-Smith Cityline Ltd.
**Document type**: Target design
**Design revision**: 2026-10-08, Patroni-style local supervision
**Module**: Proposed `peegeeq-pg-failover`

## 1. Purpose and Scope

This document defines PeeGeeQ's PostgreSQL control model. One PeeGeeQ supervisor runs with
each PostgreSQL node. It implements the Patroni approach: the writer's supervisor maintains
its own coordination lease, stops its local PostgreSQL on lease loss, and uses an independent
watchdog to protect against supervisor failure. A surviving standby acquires ownership and
promotes locally. It does not require a remote shutdown reply from the failed host.

The document is for developers implementing the supervisor and operators deploying the same
containerised components on Linux hosts/VMs, Docker hosts, and Kubernetes. It defines target
contracts, not current runtime behaviour or qualification. The Patroni product is not required.

Consul is selected for the first implementation. Both automatic A and operator-initiated B
use the same lease and enforcement. B disables autonomous takeover; it does not remove
coordination or self-demotion. Qraft remains a possible replacement after equivalent lease,
conditional-update, authoritative-read, and timing contracts are implemented and qualified.

The failure model includes host failure, network partitions, supervisor death, frozen
PostgreSQL, delayed messages, and VM pause/resume. An application connectivity failure alone
does not authorise promotion. An isolated PostgreSQL can remain healthy and serve other clients.
Survivors must therefore establish exclusive ownership under the qualified exclusion contract.

Read the data model first. Sections 3 and 4 define lease ownership and local enforcement.
Sections 5 and 6 define takeover, bootstrap, durability, and standby re-join. Section 8 defines
required fault evidence. Application connections and LISTEN recovery are in
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md).
[The sidecar guide](PEEGEEQ_PG_SIDECAR.md) defines read-only eligibility.
[The implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md)
defines phase order and qualification gates.

### 1.1 Data Model and Contracts

[System design §1.1](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#11-data-model-and-safety-contracts)
owns the canonical field contract. This document creates no second writer authority.

| Data | Source of truth or derivable | Contract |
|---|---|---|
| Cluster, incarnation, node IDs, membership, mode, endpoints, and timing/device settings | Authoritative deployment configuration | One supervisor owns one node. Fixed membership and one coordinator per incarnation. |
| `peegeeq/pg/<clusterId>/<incarnation>/primary-lock` | Authoritative Consul control record | Contains writer and transition intent plus confirmed/pending policy. Retain history after normal lease release. |
| Session, lock index, and value revision | Authoritative Consul metadata | Session belongs to the writer's node-local supervisor. Key/index/session identify generation; value revision guards updates. |
| `writerNodeId`, phase, operation, previous writer, confirmed/pending policy | Authoritative Consul intent in both modes | Persist withdrawn intent before local effects. Missing policy never grants ordinary promotion eligibility. |
| Operator takeover request | Authoritative authenticated request in transition intent | Manual target and operation approval. Not a separate lease or provider-owned authority. |
| Quarantine, local action receipts, and local writer grants | Authoritative node-local supervisor storage | Reuse existing receipt/grant contracts locally. Load admission closed on restart. Receipts never authorise writing by themselves. |
| Lease freshness, safe deadline, watchdog health, PostgreSQL role, WAL, peer coverage, and eligibility | Derived live observations | Recompute on each required check and after restart. No persisted reusable expiry or watchdog-ready flag. |
| Provisioning evidence | Authoritative deployment evidence referenced by immutable bootstrap receipt parameters | New cluster/incarnation/membership/initial writer. Empty Consul state is not evidence. |
| Credentials | Deployment secret store | Separate application, sidecar observation, supervisor mutation, local process control, and watchdog privileges. |

At most one node accepts PeeGeeQ writes. The old writer must be excluded before lease
handover. Qualified TTL expiry plus local self-demotion/watchdog enforcement permits takeover
without a former-host acknowledgement. Voluntary release requires confirmed local writer
exclusion. Unknown histories or unsupported watchdog semantics require stopped reconciliation.

The former central provider, cluster-wide generation-installation barrier, and provider-owned
manual generations are removed. Each supervisor reconciles its own effects under its node-owned
lease. No remote controller can keep another node's writer lease alive.

## 2. Responsibilities and Topology

| Component | Responsibility |
|---|---|
| `PgNodeConfig` | Immutable identity, membership, secret references, endpoints, and local process/watchdog configuration |
| `PgPrimaryElector` | Per-node lease acquisition, renewal, authoritative reads, and conditional intent updates |
| `PgFailoverMonitor` | Per-node HA loop, local process supervision, ownership-loss shutdown, takeover, and reconciliation |
| Local process/admission boundary | Guarded start/stop/promotion, closed/prepared/open grants, write quiescence, receipts, and restart quarantine |
| Independent watchdog | Exclude the local writer when its supervisor cannot complete exclusion before handover |
| `peegeeq-pg-sidecar` | Read-only eligibility combining lease, local grant, watchdog, database role/identity, and synchronous coverage |
| HAProxy | Stable routing and backend-down session shutdown; health observations do not fence writers |

The first four responsibilities belong to the proposed `peegeeq-pg-failover` supervisor.
It runs once per database node. It is separate from `peegeeq-service-manager` federation and
from the read-only sidecar. All supervisors use the same coordination namespace and protocol.

The writer supervisor renews the lease itself. Standbys cannot renew it for the writer.
Renewal and watchdog maintenance cannot queue behind SQL probes, promotion, rewind, or handlers.
Local process control remains usable when PostgreSQL's SQL endpoint is frozen.

Containers start through the supervisor. Docker restart policies, Kubernetes reconciliation,
and host service startup must not start a writable PostgreSQL outside it. The watchdog facility
is supplied by the host/VM. Its concrete pause/reset behaviour is qualified before deployment;
the takeover algorithm contains no Docker, Kubernetes, or hypervisor shutdown API.

## 3. Consul Contract

- Use a three-server quorum across appropriate failure domains.
- Use explicit TTL-only sessions with no health checks that can invalidate the lease early.
  Verify the creation payload and returned configuration. Prevent unsafe node deregistration.
- The initial TTL sent to Consul is 30 seconds. The HA loop is 5 seconds and bounded retry
  budget is 3 seconds. Lock delay is zero. These are design baselines, not timing measurements.
- Use release behaviour to preserve policy history in the control value. An unlocked retained
  serving value grants no authority.
- Only the node-local lease owner renews its session. A delayed renewal cannot reopen a
  closed grant or feed the watchdog under obsolete ownership.
- Read authority consistently. Mutation uses atomic session/revision conditions. ACLs restrict
  clients; they do not impose lock ownership on otherwise authorised writes.
- On takeover, read retained intent and atomically check revision and acquire with a new
  session and withdrawn value. Preserve confirmed/pending policy and previous writer history.
  An existing owner or conflicting revision rejects acquisition.
- Do not deliberately expire or destroy a lease to make an unreachable node fail over early.
  Normal expiry is safe only under the qualified local exclusion contract.
- Guard voluntary unlock after confirmed local writer stop and effect reconciliation.
  Do not release in a generic exception handler, supervisor exit hook, or cancellation path.
- Lease expiry may be later than the configured TTL. A timeout is not proof of successful
  acquisition and does not establish a recovery-time upper bound.
- Missing history after a prior deployment requires stopped reconciliation. Restoration uses
  a new incarnation after excluding every old writer.

[Consul sessions](https://developer.hashicorp.com/consul/docs/automate/session)
defines advisory ownership, release behaviour, early invalidation paths, and TTL semantics.
[Consul transactions](https://developer.hashicorp.com/consul/api-docs/txn)
defines conditional operations. Neither API directly stops PostgreSQL.

The adapter must qualify the earliest possible ownership handover against watchdog exclusion,
including actual session configuration, clock rates, node deregistration, explicit destruction,
and any server/client TTL conversion. Do not copy Patroni's adapter conversion by name alone.

### 3.1 Options Without Consul and the Selection Gate

| Option | Contract |
|---|---|
| Qualified Qraft or another coordinator | Replace Consul with equivalent ownership/expiry, conditional revisions, authoritative reads, namespace, and recovery semantics |
| Managed profile C | External cluster manager supplies equivalent writer exclusion, synchronous durability, stable endpoint, and client status |
| Manual profile B | Still requires the selected coordinator; manual initiation does not remove the writer lease |

Consul is selected for the first A/B implementation. G-7 qualifies that selection before
takeover implementation. Qraft is not an available drop-in replacement merely because it
uses Raft. Define the missing client contracts, asynchronous PeeGeeQ adapter, deadlines,
credentials, and service-runtime boundary. Test obsolete leaders, minority partitions,
concurrent owners, expiration, lost replies, snapshots, restart, restore, and unauthorised calls.

## 4. Node-Control Provider Contract

The section anchor is retained for existing document links. The implementation is now the
node-local supervisor. There is no independent central provider service.

| Local operation | Preconditions | Required result |
|---|---|---|
| Prepare local generation | This node owns the live lease; matching withdrawn intent | Close loaded/previous grants; reconcile this node's in-flight effects. No remote node installation barrier. |
| Bootstrap initial primary | Authenticated provisioning; initial lease; safe armed watchdog; withdrawn intent/pending revision 1 | Start only this provisioned primary with admission closed. Observe local start and retain immutable evidence. |
| Revoke writer | Local policy transition or ownership loss | Block new application work on every local route. Observe quiescence. On ownership loss stop PostgreSQL within the exclusion budget. |
| Self-demote | Ownership rejected or renewal budget exhausted | Stop local PostgreSQL using process control. Keep watchdog armed until exclusion is confirmed. Do not depend on SQL responsiveness. |
| Inspect local action | Matching receipt identity | Pending/completed/rejected/unknown result and observed local effect. Never fabricate success. |
| Promote local standby | Safe ownership handover; this node's live lease; armed watchdog; covered target; withdrawn intent | Serialise with local stop/start and ownership-loss handling. Observe role and replay. Admission remains closed. |
| Install policy | Current ownership and pending intent; writes quiescent; authenticated peers validated | Apply and observe the effective quoted policy. Uncertain result keeps admission closed. |
| Prepare writer | Current ownership; writable role; confirmed policy and safe watchdog | Create matching `PREPARED` local grant; no application traffic yet. |
| Activate writer | Matching `SERVING` intent and prepared grant; fresh lease/watchdog/role/coverage | Open exactly that grant. Loss of ownership/revocation defeats delayed activation. |
| Start/admit standby | Closed writer admission; rewind/rebuild and standby configuration validated | Start in recovery; validate identity/timeline/WAL. Never start writable from an old data directory by default. |

Receipts use generation/operation/action/target identity and immutable parameters. Reuse with
changed parameters is rejected. A lost effect reply requires local observation, not an
unguarded duplicate action. A client timeout does not cancel an accepted PostgreSQL effect.

### 4.1 Lease and Watchdog Boundary

Before writable start or promotion, arm the qualified independent watchdog. Production mode is
`required`. A missing device, unsafe actual timeout, activation failure, or lost protection
prevents writer admission. Watchdog health is live; a stored grant does not prove it.

Ownership-loss shutdown and watchdog enforcement must exclude local PostgreSQL before the
earliest next-owner acquisition. Use the canonical timing settings and qualification in
[system design §5.4/§5.5](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#54-supervisor-configuration-and-timing).
Maintain protection throughout promotion/start and uncertain stop. Do not disable or feed it
after ownership loss merely to keep the host alive.

The admission gate closes new SQL/LISTEN routes and confirms quiescence during policy changes.
It is not the takeover fence. Buffered or already executing transactions require actual writer
exclusion before another node can take over. Stale HAProxy observations cannot bypass that boundary.

Whole-VM pause/resume is a separate qualification case. A watchdog running only inside the
paused guest is not assumed to exclude stale execution before resume. The host/VM facility must
prove that property. Container pause and supervisor death must also be tested against the
independent watchdog, not against a substitute timer.

[Patroni watchdog support](https://patroni.readthedocs.io/en/latest/watchdog.html)
is the reference control model. The safety contract, not a product name, determines acceptance.

### 4.2 Interruption and Reconciliation

| Interrupted point | Required action |
|---|---|
| Acquisition or renewal response lost | Establish current ownership within the safe budget. No grant opening or stale keepalive from a late response. |
| Promotion/start in flight when lease is lost | Close admission, cancel unstarted work, stop any resulting local writer, and retain watchdog protection. |
| Stop response lost | Observe local process state. Do not voluntarily release until exclusion is confirmed. Survivors can use qualified expiry independently. |
| Policy or publication reply lost | Keep admission closed, read intent and inspect the same local action. |
| Activation reply lost | Observe matching grant plus live lease/watchdog/role/coverage. Receipt loss is not permission for another writer. |
| Supervisor restarts | Load local evidence with grants closed. Establish fresh ownership and safety; otherwise stop/reconcile and re-join as standby. |
| Former host returns after takeover | Exclude writer startup and stale effects. Rewind/rebuild before standby operation. |

## 5. State Machine and Recovery

| Stage | Action |
|---|---|
| Existing writer | Renew own lease. On renewal failure close admission and self-demote. Watchdog protects stalled supervisor. |
| Surviving nodes | Observe suspicion; only covered standbys can attempt safe lease handover. |
| `WITHDRAWN` | Winner acquires ownership, preserves history, persists operation, arms watchdog, and reconciles local effects. |
| `FENCING` | Establish qualified old-ownership exclusion. No failed-host reply or all-node generation barrier. |
| `PROMOTING` | Promote local running standby; establish surviving synchronous coverage; confirm policy and prepare local grant. |
| `SERVING` | Publish intent, then activate exact matching local grant. |
| Re-join | Former node returns closed; rewind/rebuild and validate standby; restore policy coverage separately. |

Manual B requires an authenticated request naming the target. The target owns the lease;
an operator or remote monitor does not renew on its behalf. Planned switchover confirms old
local stop before voluntary release. Unplanned takeover uses qualified expiry.

A healthy writer retaining ownership is not replaced because an application or observer cannot
reach it. Sidecar death alone does not transfer ownership. Writer-supervisor death is different:
the watchdog excludes orphan PostgreSQL before another owner can take over.

A Consul outage denies unverified eligibility. A writer unable to renew self-demotes.
No surviving node promotes without quorum acquisition. The initial implementation does not
enable the optional Patroni DCS failsafe extension.
[Patroni failsafe mode](https://patroni.readthedocs.io/en/latest/dcs_failsafe_mode.html)

### 5.1 First-Start Bootstrap

Follow [system design §5.11](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#511-first-start-bootstrap).
Only authenticated verified provisioning permits initial intent without confirmed policy.
Use conditional absence-and-acquisition in Consul for both modes. B also requires the operator
request. Empty state after a prior deployment is not provisioning evidence.

Confirm provisioned nodes cannot independently start writable. Acquire the initial writer's
lease, arm its watchdog, and start with closed admission. Validate standbys and fresh WAL,
install/confirm initial policy, prepare, publish, and activate. All-node provisioning evidence
is a first-start requirement; it is not an unreachable-host takeover barrier.

## 6. Durability and Re-Join

The Patroni control approach does not replace PeeGeeQ's durability policy.
[System design §5.8](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#58-durability-and-uncertain-writes)
requires both named physical standbys initially:
`ANY 2 ("pg-node-2", "pg-node-3")`, with required commits using `synchronous_commit=on`.
After node 2 promotion, node 3 must provide `ANY 1 ("pg-node-3")` coverage before writes resume.

Implement the production manager's owned commit enforcement in phase 7b.1 before preservation
assertions. Raw test SQL does not qualify that path. No automatic asynchronous downgrade or
automatic transaction retry is permitted. Unknown commit acknowledgement remains unknown.

A rebuilt former writer remains excluded until a withdrawn, quiescent policy cutover validates
it and restores the two-peer policy. Pending membership grants no promotion eligibility.
A two-node pair cannot resume required writes after promotion until a standby is restored.

The local supervisor keeps former-writer startup closed. Rewind requires checksums or
`wal_log_hints=on`, `full_page_writes=on`, and usable WAL. Failure requires rebuild.
Validate identity, recovery mode, timeline, and observed replay before standby admission.
[PostgreSQL rewind](https://www.postgresql.org/docs/current/app-pgrewind.html)

## 7. Configuration and Time Budgets

Use the single proposed configuration contract in system design §5.4. No second setting family.
Every observation, lease request, local action, and cleanup result is bounded and observed.

The 45-second objective is an application-recovery measurement target. Lease expiry may be
delayed. Promotion, surviving-peer attachment, proxy selection, pool recovery, and required
LISTEN catch-up each contribute. No default timeout or documentation revision proves the target.

## 8. Acceptance and Dependency Failures

Use real PostgreSQL, Consul quorum, HAProxy, local supervisor effects, and actual watchdog
enforcement. Container process tests do not qualify host/VM pause semantics.

The system design now defines S1 to S58. Required added evidence includes supervisor death with
PostgreSQL still running, container pause, whole-VM pause/resume, renewal/keepalive races,
unexpected early session invalidation, route-only failure, and takeover without any remote
control connection to the old host. Continuously attempt uniquely identified writes on old and
new routes. Reject overlap and verify acknowledged data, not only post-failover connectivity.

Inject failed, thrown, null/malformed, timed-out, lost-reply, and stale-completion results for
every called dependency before implementation. Include unsafe/missing watchdog activation,
uncertain local stop, in-flight promotion after lease loss, closed grants after restart,
manual requests, quorum outage, namespaces, bootstrap, policy cutover, and former-writer re-join.

Observe real postconditions with deadlines. Assert authority, watchdog, process state, role,
identity, durability, and application outcomes separately. Teardown failure fails the test.
Qualify each claimed Linux/VM, Docker, and Kubernetes operating environment before production
support is reported. Platform selection is not a prerequisite for the common design.
