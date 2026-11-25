# PeeGeeQ Connection Management and HAProxy Failover — Implementation Plan

**Document revision**: 2026-11-21. PP1 of §9.35 built (`PgClusterParameters`, OD-76). On the same day the closed
sections (§2 to §4, §8.2, §9.1 to §9.27, §9.29 to §9.34) moved unchanged, with their numbers, to
[the archived closed sections](../_archived/completed-records/PEEGEEQ_PG_FAILOVER_PLAN_CLOSED_SECTIONS_TO_2026-11-21.md).
This plan now holds the contracts and gates (§5), the method (§6, §7), the phases and the evidence table (§8), the
owner decisions of 2026-11-13 (§9.28), and the open design (§9.35). The decisions are OD-1 to OD-76.
**Created**: 2026-10-07 at commit `ff5c17da`
**Controls**: Task 8 of the [task register](../tasks/tasks.md). The register holds the status and the next step;
§8.1 here lists what is built and where its evidence is.
**Design**: [PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md),
design revision 2026-10-08, Patroni-style local supervision with a coordinator port and optional watchdog.

**Contract corrections, 2026-10-08:** watchdog support is optional (§5.2.1), and failover
depends on a coordinator port, not on Consul (P-38). The four design documents were aligned
with both on 2026-10-08 (§9.10). Machine-reset testing and additional host/VM access are not
prerequisites for the next implementation phase.

| Section | Content |
|---|---|
| §1 | Purpose and scope |
| §2 to §4 | Baseline gap, findings, and run evidence of 2026-10-07 to 2026-10-11 (archived) |
| §5 | Adopted design contracts and deployment gates |
| §6 | TDD method |
| §7 | Scenario coverage |
| §8 | Phases, and §8.1 what is built with its evidence |
| §9 | Map of the increment records; §9.28 owner decisions of 2026-11-13; §9.35 PostgreSQL parameters (open) |

---

## 1. Purpose and Scope

This plan defines the work and verification required to implement PeeGeeQ's PostgreSQL
connection, failover, and recovery design. It is for developers selecting the next phase,
reviewers assessing completion evidence, and operators selecting production dependencies.
The design documents specify the target behaviour. This plan records dated findings,
implementation order, acceptance obligations, deployment gates, and the evidence from runs.

**The outcome the work must deliver.** PeeGeeQ application instances must use a stable SQL
endpoint to reach one authorised PostgreSQL writer. A writer is a primary permitted to accept
application writes under current node-owned lease and local admission. Failover must exclude the
former writer before a replacement serves, preserve acknowledged writes under the synchronous
policy, replace unusable pool and LISTEN connections, and complete required durable catch-up.
LISTEN is the database subscription mechanism for notification channels. Its notifications
request processing; durable database state determines the work still to deliver.

The intended production topology has one primary and two synchronous standbys, redundant
HAProxy and status endpoints, and optional redundant PgBouncer poolers. Sidecars observe
writer eligibility. Per-node `peegeeq-pg-failover` supervisors will coordinate writer ownership
through a coordinator port. Consul is the first adapter. Qraft is a planned second adapter. Local lease-loss shutdown is the normal exclusion mechanism. Watchdog support
provides optional independent protection when the supervisor cannot execute shutdown.
The lease belongs to that node. Safe handover must establish old-writer exclusion.
The qualified expiry path requires no remote stop reply from an unreachable host.

**How the execution order is organised.** Manual profile B uses operator-directed transitions
through the same local supervisor, lease, admission, and configured watchdog mode as A. Automatic A
adds autonomous takeover initiation. Both modes use the same coordinator adapter. G-7 qualifies an adapter
and its safety claims incrementally alongside the supervisor implementation.
The common architecture supports Linux hosts/VMs,
Docker hosts, and Kubernetes. Deployment bindings are qualified before production support. Phase 7 first
implements production-manager commit enforcement, then bootstrap and manual transitions.
Manual transition and re-join tests precede automatic transition work.
Later phases complete operation deadlines, migration to shared pooled access, LISTEN recovery, readiness,
and endpoint failure handling. Full recovery qualification follows those integrations.

For example, a test that promotes a standby and runs a query does not finish the work for a
primary-crash scenario. Its evidence must also establish the former writer's fence, surviving
acknowledged data, replacement synchronous coverage, application connection recovery, and
required subscription catch-up. Local Docker process tests do not prove watchdog or whole-VM pause safety.
Production qualification requires real enforcement evidence for every claimed environment.
The 45-second recovery objective is a qualified workload target, not a result inferred from
configuration defaults or an earlier component test.

**How to interpret the records.** Requirement identifiers `R-` describe system obligations.
Scenario identifiers `S1` to `S59` describe acceptance cases in the system design. The plan's
`P-` entries record adopted design contracts; `G-` entries identify deployment selections and
qualification gates. `IMP-`, `TST-`, and `CFG-` entries record dated implementation, test, and
configuration findings. Read their dates and evidence before using them to scope new work.

Sections 2 to 4 retain historical assessments and run records. They do not establish the
current repository state or revised scenario coverage. A requirement table does not prove
an implementation. Completion requires the current code, configuration, and fresh logs for
the relevant scope. Record per-class executed test counts and failures. Keep dependency
failure and teardown failure visible. No old test count closes a new acceptance obligation.

**Reading guide and boundaries.** Begin with
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#1-purpose-and-scope) to understand
the architecture and terms. Use [the Consul design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md) and
[the sidecar guide](PEEGEEQ_PG_SIDECAR.md) for component contracts. In this plan, §5 contains
adopted decisions and deployment gates, §6 defines the test-driven implementation method,
§7 groups acceptance cases by phase, and §8 defines execution order. Section 9 records source
reads and revision history. Use Task 8 in the consolidated task register to locate assigned
work, then verify its reported state against the corresponding artifacts and run evidence.

The scope below includes connection management, database transitions, client recovery,
configuration contracts, and real-component acceptance. Application-instance federation and
routing are separate work. External side effects retain their application delivery and
idempotency contracts. This plan does not promise exactly-once processing or qualify an
unselected production deployment.

The data model and field sources of truth are in the design §1.1. Configuration is authoritative
for cluster identity and membership. The coordinator's control record stores transition intent and ownership metadata.
Each local supervisor stores action receipts, quarantine, and local execution grants. The writer's
supervisor owns the coordinator lease. Lease deadlines and watchdog health are derived, never stored
as reusable authority. The central provider and all-node generation barrier are removed. Profile authority stores confirmed and pending
durability policy intent. Counts, role, peer health, replay boundary, and readiness are computed.
Bitemporal replay reuses the existing tenant cursor and lease with the stable writer barrier.
No parallel cursor, acknowledgement ledger, or generic idempotency-result table is introduced.

In scope:

- The single endpoint, sidecar eligibility, fencing, synchronous durability, and safe re-join.
- Shared pooled access, operation deadlines, error classification, LISTEN recovery, and health.
- The existing `peegeeq-pg-failover` module, a planned supervisor per node, local process control,
  and optional independent watchdog support.
- Real-component acceptance for S1 to S59, local infrastructure, and a scenario runbook.
- Configuration loader, defaults, property contract tests, and configuration guide updates
  when proposed keys are implemented.

Out of scope:

- PeeGeeQ federation and application-instance routing in `peegeeq-service-manager`.
- Patroni as an implementation requirement.
- An implicit exactly-once guarantee for external side effects or consumer delivery.
- Treating a local Docker adapter as qualification of production watchdog enforcement.

Each revision updates this implementation plan and Task 8 from source and saved logs. Section 9
records what each revision read, changed, and ran.

Full production write recovery uses three PostgreSQL nodes with synchronous acknowledgement
coverage. A two-node pair preserves safety but cannot resume required synchronous writes
after promotion until a standby is restored. There is no automatic asynchronous downgrade.

_Sections 2 to 4 (the gap, findings, and run evidence of 2026-10-07 to 2026-10-11) were moved on 2026-11-21 to [the archived closed sections](../_archived/completed-records/PEEGEEQ_PG_FAILOVER_PLAN_CLOSED_SECTIONS_TO_2026-11-21.md), numbering unchanged._

## 5. Adopted Design Contracts and Deployment Gates

The user requested application of the review recommendations on 2026-10-07. These are the
resulting design contracts. They replace the former open-position table. They are design
decisions, not implementation completion or runtime evidence.

| ID | Adopted contract | Canonical design reference |
|---|---|---|
| P-0 | Local supervision belongs to the existing `peegeeq-pg-failover` module, one planned process per database node. It is independent of federation. | §1, §5.1 |
| P-1 | Implement Patroni's node-owned lease and local-demotion approach, with optional watchdog support, without requiring the Patroni product. | §4; plan §5.2.1 |
| P-2 | Production HAProxy checks sidecar eligibility. Protocol checks are development only. | §5.3, §9 |
| P-3 | Local self-demotion/watchdog excludes the writer before safe lease handover. Qualified expiry requires no failed-host stop acknowledgement. Voluntary release requires confirmed local stop. | §5.5 |
| P-4 | Route-only failure does not establish database failure. An active watchdog provides independent protection on writer-supervisor failure. Without it, supervisor-death exclusion is not established by the lease alone. A sidecar restart does not transfer ownership. | §5.7; plan §5.2.1 |
| P-5 | Writer ID names a database node. The lease belongs to its local supervisor. Control record, generation, and lease holder identify a writer generation. | §1.1, §5.10 |
| P-6 | Reversed by owner answer 9 (§9.28, row 9) and implemented 2026-11-15 (§9.32): rewind and rebuild are automatic under quarantine, followed by standby and WAL validation. A different system identifier and an unreadable data directory outside an interrupted re-join stay with the operator. The text before the reversal was "Rewind or rebuild is operator-controlled under quarantine, followed by standby and WAL validation." | §5.5, §5.9 |
| P-7 | HAProxy uses scheduled observations and closes sessions when configured failures mark a backend down. Admission enforcement and fencing protect safety during delay. No fixed backup preference chooses a former writer. | §5.3 |
| P-8 | Every module uses the shared pooled access path and schema contract. | §6.1, §6.3 |
| P-9 | Maximum lifetime limits reuse at idle. Active operations have independent deadlines. Read-only errors require context. | §6.4, §6.7 |
| P-10 | Classify database availability failures. Business SQL, deliberate cancellation, and local saturation are distinct outcomes. | §6.5 |
| P-11 | LISTEN reconnects indefinitely with bounded jitter, checks eligibility, re-establishes channels, and catches up. | §6.6 |
| P-12 | Readiness checks eligibility, durability, and required catch-up. Liveness remains separate. | §7 |
| P-13 | Sidecar's request deadline covers acquisition and every eligibility dependency. | §9.1 |
| P-14 | Profile D remains a connection-recovery exercise. Move production convergence assertions to role-and-authority tests. Preserve the dated red evidence. | §4, §12 |
| P-15 | Effective endpoints apply consistently to setup service and every module. LISTEN can bypass transaction pooling. | §6.2, §8 |
| P-16 | Infrastructure partitions, supervisor/VM pauses, coordination quorum, and S1 to S58 are acceptance obligations. | §10 |
| P-17 | At most one writer. Zero is permitted during recovery. | §1.1, R-1 |
| P-18 | Three-node production topology protects acknowledged commits and retains a synchronous peer after promotion. Two-node recovery waits for restored redundancy. | §5.8 |
| P-19 | Unknown mutation and commit outcomes require reconciliation. No automatic transaction retry or exactly-once claim. | §5.6, §5.8 |
| P-20 | Local supervisor serialises node effects with lease-loss shutdown and configured optional watchdog protection. Controller-side checks or session cleanup alone are insufficient. | §5.1, §5.5; plan §5.2.1 |
| P-21 | Cluster and incarnation namespace isolation protects independent deployments and coordination restoration. | §1.1, §5.7 |
| P-22 | Redundant proxies and tested stable-address transfer are production requirements. | §5.9 |
| P-23 | Replicated system identifier is cluster identity. Tests need independent node-local identity. | §12 |
| P-24 | The writer's local supervisor alone renews its lease. Standbys and remote controllers cannot retain ownership for an unreachable writer. | §5.1, §5.6 |
| P-25 | Winner acquires safe ownership with withdrawn intent, applies the configured watchdog mode, and reconciles its local effects. Local prepare/publish/activate remains recoverable. Remove central generation installation and provider-owned manual authority. | §1.1, §5.6; Consul design §4; plan §5.2.1 |
| P-26 | Generate individually quoted standby names. Every listed peer is required. Acknowledgement count is derived. | §5.8 |
| P-27 | Confirmed and pending policy intent govern repeated failover and standby re-join. A withdrawn quiescent cutover restores two-peer coverage. Pending peers are excluded. | §1.1, §5.8 |
| P-28 | Reuse tenant cursor, replay lease, short writer barrier, finite upper boundary, and one serialized scan. Delivery is stable append-ID order and at least once. | §6.6 |
| P-29 | Clients use authenticated read-only `/writer`, matching SQL node identity, and separate deadlines. Backoff resets after initialized channels and finite catch-up. | §6.2, §6.6, §9 |
| P-30 | Consul ACLs restrict access. Atomic session/revision conditions guard ownership-sensitive writes; ACLs do not impose lock ownership. | §1.1, §5.1; Consul design §3 |
| P-31 | Optional PgBouncer and its address owner receive the same redundancy and session-loss qualification as HAProxy. | §5.9, §8 |
| P-32 | Consul is the first coordinator adapter for A/B supervision. G-7 qualifies an adapter: TTL-only ownership, conditional revisions, authoritative reads, safe release, restore, and security. Qraft is a second adapter behind the same port, qualified by the same contract suite. | §5.10 |
| P-33 | Production-manager commit enforcement precedes phase 7 application-write preservation assertions. Broader module migration remains phase 11. | §5.8, §6.3 |
| P-34 | Authenticated bootstrap requires verified provisioning, initial node-owned lease, configured watchdog-mode checks, closed admission, guarded start, and confirmed initial policy. Missing history is not bootstrap permission. | §1.1, §5.11; plan §5.2.1 |
| P-35 | Native recovery requires one executed finite claim batch and its confirmed processing acknowledgements. Skipped/deferred work never establishes readiness. Delayed scheduling and final eligibility are observed. | §6.6 |
| P-36 | Same containerised local-supervisor protocol on Linux hosts/VMs, Docker hosts, and Kubernetes. Runtime enforcement is qualified per environment before support is claimed. | §5.2, §12 |
| P-37 | Watchdog modes are planned as `automatic` (default), `off`, and `required`. Only `required` refuses writer start/promotion because the watchdog is unavailable. Qualify independent exclusion before claiming supervisor-death or whole-VM pause safety. Device/reset tests are conditional qualification work, not a prerequisite for implementing local supervision. | Plan §5.2.1; S54–S58 |
| P-38 | Database failover has no hard dependency on Consul. The supervisor, the sidecar, and the elector use a coordinator port, `PgLeaseCoordinator`, with neutral terms: control record, lease, lease holder, generation, revision, and intent. Consul is one adapter, `ConsulLeaseCoordinator`. A Qraft adapter is a separate module. One adapter serves an incarnation. Every adapter passes one shared contract suite against its real service. Directed by the owner on 2026-10-08, because Qraft may be used in place of Consul. | §3 R-20, §5.4, §5.10, S59; coordinator design §3 |

### 5.1 Required Deployment Selections

Phase 7a selects the common architecture. These gates qualify its implementation and deployment.

| Gate | Required artifact before implementation or release |
|---|---|
| G-1 Local supervision and optional watchdog | Implement the same node-local PostgreSQL lifecycle on all three environments. Bind writer start/promotion to own lease, admission, and the selected watchdog mode. Docker tests qualify local shutdown and restart quarantine. Actual device/reset and whole-VM tests qualify independent protection only when claimed. Without that evidence, freeze and orphan-writer safety remain unqualified. No failed-host stop reply is required by the qualified expiry path. |
| G-2 Local admission and observations | Implement local SQL/LISTEN admission, quiescence, grants/receipts, and sidecar read-only status. Prove withdrawn intent before effects, safe lease preparation, selected watchdog-mode checks, prepare/publish/activate, revocation, local reconciliation, and late-effect exclusion. Loaded grants start closed. |
| G-3 Stable production endpoints | Use configurable stable SQL/HTTP addresses through redundant proxies; optional redundant poolers. Bind the deployed address owner/load balancer before deployment. Qualify surviving routes, address loss, active transactions, idle sessions, and LISTEN bypass. No platform-specific address choice blocks common implementation. |
| G-4 Durability enforcement | Implement and verify the production-manager owned commit boundary in 7b.1 before preservation assertions. Prove quoted policy, weaker caller settings, authenticated peers, confirmed/pending policy cutover, restored coverage, and repeated failover. Full migration remains phase 11. |
| G-5 Replay and idempotency | Qualify the existing cursor/lease/writer-barrier finite replay and native finite claim/acknowledgement contract. Cover delayed low IDs, handler failure, unknown cursor/acknowledgement commits, capacity, scheduling, and stale completions. No second progress mechanism. |
| G-6 Runtime and timing | Pin separate service/artifact versions. Measure application recovery under declared workload, deadlines, and failure cases. Defaults and historical counts do not prove an SLO. |
| G-7 Coordinator qualification | An adapter is qualified by the shared coordinator contract suite (S59) against its real service. Consul is the first adapter; its protocol slice has recorded component evidence in §8.1. Complete stopped-before-release, restore/incarnation, TLS, supervisor integration, and expiry versus old-writer exclusion alongside the remaining implementation. These are qualification obligations, not a requirement to obtain machine-reset infrastructure before coding. A Qraft adapter requires Qraft to provide the port's operations first (§5.1.1). |

No production gate is closed by a document edit. G-1/G-2/G-7 apply to B as well as A because both
use the same continuously enforced writer lease. B's only difference is operator initiation.

#### 5.1.1 Qraft Assessment and Decision Artifact

The Qraft option means the local Java project, not an unrelated public project with the same name.
The source assessment on 2026-10-07 read `../qraft/src/main/proto/distributed_state.proto` and
`DistributedStateGrpcService`. The reviewed API exposes unconditional Put/Delete requests and
Get/List read local state without a quorum-read step in those methods. It does not expose the
conditional ownership, revision, or lease fields required by the coordinator port (P-38).
These are static observations of that API, not results of a Qraft runtime or full-source audit.

The reviewed POMs target Java 27 for Qraft and Java 25 for PeeGeeQ. PeeGeeQ moved to Java 27 on
2026-11-21. Qraft's project standards
prohibit Vert.x in Qraft. If selected, use Qraft as an external service and define an asynchronous
PeeGeeQ adapter. Do not change either project's runtime standards to embed the other runtime.

G-7 must produce an operation-by-operation authority mapping and qualification evidence. Required
cases include simultaneous owners, obsolete leaders and minority partitions, ownership expiry,
stale reads, conflicting revisions, lost replies, restart/snapshot/restore, namespace isolation,
and unauthorised calls. Define how ownership generations reach local supervision and watchdog enforcement and
how sidecars observe authority. A Raft term alone is not the reference lock generation.
Retaining Consul also requires a recorded decision and its existing real-component acceptance.

No Qraft capability is marked implemented or qualified by this document. For Qraft work, read
its `docs/TESTING.md` and `docs/PROJECT_STANDARDS.md`, reuse their Maven commands in the visible
VS Code integrated terminal, and retain output through `Tee-Object`. Do not create verification
wrapper scripts. A Qraft adapter implements the coordinator port in its own module. No other
component changes when it is selected.

### 5.2 Phase 7a Architecture Selection, 2026-10-08

The canonical data model is system design §1.1. Phase 7a selects Patroni-style node-local
supervision. Linux hosts/VMs, Docker hosts, and Kubernetes are all deployment targets.
No selection of one hosting platform is required to finish this design phase.

| Selected input | Source of truth or derivable | Contract |
|---|---|---|
| Cluster/incarnation/membership/node identities | Authoritative configuration | Fixed namespace and one supervisor per node. Replica system identifier is not node identity. |
| Writer lease and intent | Authoritative coordinator metadata/value | Owned and renewed by writer's supervisor; same protocol in A/B. |
| Local receipts, quarantine, grants | Authoritative persistent node storage | No central store. On restart load grants closed and reconcile local effects. |
| Lease TTL, loop/retry/stop budgets, watchdog device/mode | Authoritative configuration | Separate local-demotion timing from optional watchdog timing. The modes and their safety limits are below. Their implementation is recorded in §8.1. |
| Lease freshness, watchdog health, roles, WAL, eligibility | Derived observations | No persisted lease expiry or watchdog-ready permission. |
| SQL/LISTEN/status/local control endpoints | Authoritative configuration; mapped test addresses derived from fixtures | Redundant stable routing; separate credentials; no platform API in takeover. |

#### 5.2.1 Common Runtime Selection

Use one PeeGeeQ supervisor per PostgreSQL node. Its entry point owns PostgreSQL process startup,
local admission, lease renewal, promotion, shutdown, and re-join. The sidecar remains read-only.
The supervisor renews ownership independently of SQL probes and slow actions. Do not let another
controller renew its lease. Do not start PostgreSQL through a second orchestrator entry point.

Use Linux local process control. Lease loss withdraws admission and stops local PostgreSQL
within the verified shutdown budget. Implement and test this path with the available Docker
workflow. It does not require a watchdog device or another Linux host/VM.

Optional watchdog support follows Patroni's modes:

| Mode | Contract |
|---|---|
| `automatic` (default) | Use the watchdog when available. Report absence or activation failure. Continue through verified lease and local-demotion checks without claiming independent watchdog protection. |
| `off` | Do not open or activate a watchdog. Retain lease-loss shutdown, guarded startup, and admission checks. |
| `required` | Refuse writer startup/promotion when the watchdog cannot be activated or its timing is unsafe. |

Mode and device configuration are authoritative. Watchdog activation and health are derived.
No persisted watchdog-ready flag grants permission after restart. Device errors remain visible.
The modes are implemented in `PgFailoverMonitor` with the `PgDeviceWatchdog` adapter (OD-5,
§8.1). No test has armed a real device.
See [Patroni's watchdog documentation](https://patroni.readthedocs.io/en/latest/watchdog.html)
and [the pinned v4.1.5 mode implementation](https://github.com/patroni/patroni/blob/v4.1.5/patroni/watchdog/base.py).

Optional support does not remove old-writer exclusion from takeover. Local shutdown cannot
prove exclusion while its supervisor is dead or the whole VM is paused. Coordinator lease expiry
alone does not stop PostgreSQL. Record these cases as unqualified until independent exclusion
is demonstrated. A paused guest's own timer is not sufficient evidence. Implementing the
common runtime proceeds before this production qualification. The protocol does not call
Docker, Kubernetes, or hypervisor remote stop APIs.

Use local persistent node storage for quarantine, grants, and action receipts. The coordinator's control record owns
transition and durability intent. Do not create a separate central generation/admission service.
Local grants and admission observe current ownership and the selected watchdog-mode checks. First-start provisioning
requires verified node state; ordinary unreachable-host takeover does not require all-node replies.

#### 5.2.2 Local Acceptance Fixture Selection

Use the existing Docker/Testcontainers workflow.
Reuse [PostgreSQLTestConstants](../../peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/PostgreSQLTestConstants.java)
factories/image constant and the production schema initializer.
The [streaming-replication test](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/HaProxyStreamingReplicationFailoverTest.java)
provides source patterns. Its exit-code-only readiness query and direct container construction
must not be copied. Assert real authenticated replication rows and fresh flush/replay.

7b.1 uses an isolated primary plus two physical standbys and a fixed test-only HAProxy route to
qualify the production manager's commit boundary. Weaker policy, missing peers, rollback, and
lost replies must have observable assertions. This fixture does not qualify writer admission.

7b.2 has implemented the coordinator port, the Consul adapter, local supervision and admission,
the renewal loop, the optional watchdog modes, and bootstrap scopes A and B (§8.1). Bootstrap scope B
uses a separate fresh fixture of one primary and two standbys (OD-8). 7b.3 verifies
manual takeover through the full admitted route. Persist local node storage across restarts.
Destroying a test container is teardown, not restart-inhibition proof.

Docker/Testcontainers supplies the next implementation fixtures. No machine reset or additional
host/VM access is a prerequisite. If watchdog device support is qualified later, its reset tests
need an isolated environment for the actual reset scope. Container process tests do not prove
whole-VM or independent watchdog exclusion.

#### 5.2.3 Deployment Bindings Before Production Qualification

| Target | Same runtime contract | Binding to qualify |
|---|---|---|
| Linux host/VM | Supervisor owns local PostgreSQL, lease, and admission | Process privileges, persistent storage, restart control; optional watchdog device and pause/resume exclusion if claimed |
| Docker host | Same supervisor image/entry point | Storage, process privileges, restart through supervisor; optional device access and ownership if enabled |
| Kubernetes | Same supervisor image/entry point | Storage, scheduling/failure domains, lifecycle/startup; optional node/device policy and ownership if enabled |

When watchdog support is enabled, a watchdog resetting a host affects every colocated workload. Define exclusive device ownership
and the actual reset scope. Do not assume several database containers can independently own one
host watchdog. Verify VM resume behaviour against the actual facility before support is declared.

Stable endpoints are configured addresses through redundant proxies and optional poolers. Bind
the deployed load balancer/address owner and credentials before qualification. No specific
orchestrator service is a design dependency. G-1/G-2/G-3/G-7 remain implementation/qualification
gates. Phase 7a closes architecture selection only; it does not qualify production safety.

### 5.3 Design Decisions of Phase 7b.2 (OD-1 to OD-8, all decided)

A full read of all five documents on 2026-10-08 found three points the design requires and
does not specify. Each is the owner's decision. The search covered every document in this
folder. Later scopes found five more, OD-4 to OD-8. All eight are decided below: OD-1 to OD-3
by the implementer, not reviewed by the owner; OD-4 to OD-8 by the owner.

| ID | What the design says | What is not specified | Blocked, before the decision |
|---|---|---|---|
| OD-1 Local admission mechanism | A local admission gate covers every application SQL and LISTEN path, sits between HAProxy and PostgreSQL, blocks new work when closed, and observes quiescence of existing writes (system design §5.1, §5.2) | What the gate is. The documents name no mechanism: not `pg_hba.conf` management, not a local proxy, not a listen-address or firewall control. `pg_hba` appears twice in the set, both times about the sidecar role | §8.2 item 5 |
| OD-2 Local process control | The supervisor owns PostgreSQL start, stop, promotion, and rewind admission through local process control, and no second entry point can start a writable PostgreSQL (system design §5.1; coordinator design §2) | The commands used, the container entry point, and how the supervisor and PostgreSQL share a container or host | §8.2 item 4 |
| OD-3 Node-local storage | Grants, action receipts, and quarantine are durable node-local supervisor storage, with the fields in system design §1.1 | Location, format, and durability mechanism | §8.2 item 3 |

**OD-3 decided for implementation, 2026-10-08.** The owner instructed that all §8.2 steps
proceed without stopping. The implementer chose the mechanism; the owner has not reviewed it and
can reverse it. The choice is confined to `PgLocalStateStore`.

- Location: one existing directory on the node's persistent storage, given to the supervisor as
  configuration. The store does not create it. One supervisor process writes it.
- Format: one JSON file per record. `grant.json`, `quarantine.json`, and
  `receipts/<generation>+<operationId>+<action>+<targetNodeId>.json`. Unknown fields, unknown
  enum values, a record for another node, and a receipt whose content differs from its file
  name are rejected.
- Durability: write a temporary file opened for synchronous writes, flush, close, then replace
  the record by an atomic move. The parent directory is not synced; Vert.x offers no call for it.
- Nothing is cached. Every operation reads the files. Operations run one at a time in
  submission order.

**OD-2 decided for implementation, 2026-10-08.** Same basis as OD-3: the implementer's choice
under the owner's instruction to proceed, not reviewed by the owner, reversible. The choice is
confined to `PgCtlProcessControl` and `LocalCommandRunner`, behind the `PgProcessControl` port.

- Commands: PostgreSQL's own `pg_ctl status`, `start`, and `stop`, run as child processes of the
  supervisor against the local data directory. A configured command prefix runs them as the
  PostgreSQL operating-system user. `touch` and `rm` manage the inhibitor file.
- Co-location: the supervisor and PostgreSQL share one host or container. The container entry
  point is the supervisor. The image entry point does not start PostgreSQL.
- Start inhibitor: `standby.signal` in the data directory. While it exists, a start by any entry
  point comes up in recovery and accepts no writes. `stop` creates it before stopping. Only the
  guarded primary start removes it. An experiment against `postgres:15.13-alpine3.20` on
  2026-10-08 showed: `pg_ctl status` exits 0 when running and 3 when stopped; a fast stop ends
  an existing session; with the file present a direct `pg_ctl start` gives
  `pg_is_in_recovery() = t` and rejects `CREATE TABLE`; `pg_ctl promote` removes the file.
- Stop: fast stop for half the remaining budget, then immediate stop for the rest. The exit code
  of the stop command is not the evidence; the following `pg_ctl status` observation is.
- Not decided: promotion, rewind, and rebuild commands. They belong to 7b.3.

**OD-1 decided for implementation, 2026-10-08.** Same basis as OD-3 and OD-2: the implementer's
choice under the owner's instruction to proceed, not reviewed by the owner, reversible. This
entry was written on 2026-10-09 from the source and from the message of commit `ea045d7f`. The
choice is confined to `PgHbaAdmissionGate`, behind the `PgAdmissionGate` port.

- Mechanism: PostgreSQL host-based authentication. The deployment provisions two files. One
  admits application connections and one rejects them. Both keep the local socket for the
  supervisor and keep the replication rules. The gate copies one of them over `pg_hba.conf`,
  compares the result with the source, and runs `pg_ctl reload`.
- Reload evidence: the value of `pg_conf_load_time()` changes, and `pg_hba_file_rules` then has
  no row with an error.
- Close: installs the closed file and reloads. A reload blocks new connections only, so the gate
  also ends every client backend that has a client address and polls `pg_stat_activity` until
  none remains. All of it runs inside one budget. With PostgreSQL stopped, the gate installs the
  closed file for the next start and does nothing else.
- Open: requires PostgreSQL to be running. Installs the open file and reloads.
- Scope: connections over the network. A connection through the local socket is not gated.
- The gate is not the takeover fence. Writer exclusion is the local stop.
- Not decided: the content of the two files for a deployment, and how they are provisioned.

**OD-4 decided by the owner, 2026-10-21: ownership operations queue.** Before this decision
`PgPrimaryElector` refused `renew`, `update`, and `release` while another of them was in flight.
With a renewal timer, a tick during an update was refused, and `PgFailoverMonitor.renew()`
demoted the writer on that refusal; an update during a renewal was refused as well. The owner
chose queueing over a separate busy signal and over pausing the timer around updates.

- `renew`, `update`, and `release` run one at a time in arrival order. A waiting operation is
  checked against the ownership state when it starts, not when it arrives.
- The request deadline and the freshness deadline of a waiting renewal are measured from the
  start of its request. A reply after either deadline is still rejected.
- An operation queued behind one that failed meets the retired state and is refused.
- Acquisitions keep their refusal rule: they are the first operation of an instance.

**OD-5 decided by the owner, 2026-10-21: watchdog device mechanism and its verification.**
Linux sets a watchdog timeout with an `ioctl`, which plain Java cannot call. The owner chose a
plain-Java adapter over Java FFM `ioctl` calls and over a port without a device adapter, and chose
to defer real-device tests to a Linux host.

- `PgDeviceWatchdog` opens the device to arm it, writes one byte other than `V` per keepalive,
  and writes `V` before closing to disarm it (magic close). It never sets the timeout. The
  deployment sets it.
- Before opening the device the adapter reads the actual timeout from the device's sysfs
  `timeout` file. A device whose timeout cannot be read is never armed.
- The monitor accepts the timeout only when it is at most half the lease TTL, longer than one
  loop-and-retry cycle, and longer than the stop budget. Otherwise it disarms the watchdog and
  treats it as unavailable.
- Device activation, keepalive, magic close, `nowayout` behaviour, and the timeout check
  against a real device run later on a Linux host with `softdog soft_noboot=1`. The development
  machine's Docker kernel (WSL2 6.18) builds softdog as a module that is not loaded, and has no
  `/dev/watchdog` (`logs/probe-docker-watchdog-20261021.log`).

**OD-6, OD-7, OD-8 decided by the owner, 2026-10-21: first-start bootstrap.** System design
§5.11 does not define the form of provisioning evidence, how the initial writer learns the state
of the other members, or where policy installation is built.

- OD-6 Evidence: a request file, `bootstrap-request.json`, in the supervisor's state directory.
  Filesystem permissions are its only authentication. It has exactly the fields `clusterId`,
  `incarnation`, `memberNodeIds`, `initialWriterNodeId`, `operationId`, and
  `requiredStandbyNodeIds`, and each must match the node's configuration. The owner chose this
  over a signed request and over a caller-authenticated value. **This does not authenticate an
  operator, which system design §5.11 step 1 requires.** The difference is a recorded gap.
- OD-7 All-node evidence: left until later. The initial writer verifies its own node only.
- OD-8 Scope: two scopes. Scope A: request verification, refusal against local and coordinator
  history, the conditional initial intent, the renewal loop, the bootstrap receipt, and the
  guarded primary start with admission closed. Scope B: standby provisioning, the replication
  check and policy installer, revision-1 confirmation, and prepare/publish/activate, on a
  primary-plus-two-standby fixture shared with 7b.3.

**OD-9 to OD-12 decided by the owner, 2026-10-22: bootstrap scope B.** System design §5.8 and
§5.11 require a guarded policy installation, provisioned standbys, and replication identity
bound to authenticated configured nodes. They do not name the mechanisms.

- OD-9 Policy installation: `ALTER SYSTEM SET synchronous_standby_names` through the local
  socket, then `pg_ctl reload`. The reload is confirmed when `pg_conf_load_time()` changes. The
  setting in effect must then equal the generated value, and every required standby must report
  quorum synchronous state. The setting persists in `postgresql.auto.conf`. The owner chose this
  over a managed include file.
- OD-10 Standby data: the deployment provisions each standby's data directory by base backup with
  `standby.signal` before bootstrap. Scope B starts and validates standbys; it takes no base
  backup. A supervisor base-backup action stays with rebuild and re-join (7b.3).
- OD-11 Peer identity: each standby authenticates with its own replication role. A peer is bound
  to its node when `pg_stat_replication.usename` is that node's role and `application_name` equals
  the node identity. The implementer chose, and the owner can reverse, that the role name equals
  the node identity, so the mapping is derived from membership and not stored.
- OD-12 Production-manager write: included in scope B. `peegeeq-db` becomes a test-scope
  dependency of `peegeeq-pg-failover`. The write goes directly to the writer node, not through
  HAProxy, because the sidecar is still role-only.

Defaults applied without a separate decision, because each follows an existing pattern or
decision: SQL runs through `psql` on the local socket through `LocalCommandRunner`, as in
`PgHbaAdmissionGate`; the writer observes standbys only through its own `pg_stat_replication`,
as OD-7 leaves all-node evidence until later; each standby is started by its own supervisor.

## 6. TDD method

Every phase that changes Java follows these steps in order.

1. **Pre-work.** Complete the six mandatory steps in `AGENTS.md`. Read the coding principles
   and testing antipattern standard in full. Read every file the phase will
   modify, in full. Read the existing tests in the same module. Search each file for the banned
   patterns and record the count.
2. **Failure modes.** List the failure modes of each dependency the new code calls: a failed
   Future, a thrown exception, a null, a timeout, a connection that stays open and silent.
3. **Red.** Write one failing test per failure mode, and one for the normal path. Each test uses
   real containers. The test follows the pattern of the nearest existing test in the module.
4. **Prove red.** Rebuild with `mvn clean install -DskipTests -pl :<module> -am` through
   `Tee-Object`. Run the single test class with its profile. Read the saved log. Record the
   per-class `Tests run:` line and the failure message. A test that passes at this step is
   recorded as existing coverage, and no production change is made for it.
5. **Green.** Make the smallest production change that passes the test.
6. **Prove green.** Rebuild. Run the new class and the existing classes for the changed code.
   Record every per-class `Tests run:` line. `Tests run: 0` is a failure.
7. **Standards check.** Validate the changed files against
   `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`. Run the guard tests in `peegeeq-test-support`.
8. **Record.** Update Task 8 in the register with the counts. Update §8.1 of this plan.
   Preserve the dated §2 to §4 baseline.
   Report, and state what was not checked.

The test rules are in §12 of the design: identify the node, wait on an observable with a
deadline, assert the failure type, no mocks. In addition: one subject per test class, and every
endpoint is read from the live container.

---

## 7. Acceptance Coverage Obligations

The design §10 defines S1 to S59. No current runtime coverage audit was run in this documentation phase.
The historical seven-class baseline in §4 concerns the earlier design. Do not mark any revised
contract met from those counts.

| Scenario group | Implementation phase | Required evidence |
|---|---|---|
| S2 to S5, S6, S31, S32, S35 | 7: manual profile and re-join | Role, node identity, fencing, quarantine, data continuity, and replacement synchronous policy |
| S13, S25, S38, S40 | 7: durability | Acknowledged-write preservation, unknown commit outcomes, blocked writes without a synchronous peer, and target rejection |
| S1, S15 to S24, S30, S33, S34, S36, S37, S39 | 8: automatic authority and local supervision | Real control and node effects, generations, reconciliation, restart inhibition, and partition safety |
| S9, S14, S26 | 9 and 13: deadlines and connection failures | Specific failures, operation deadlines, connection discard, and observed cleanup |
| S7, S8, S27 | 10: LISTEN and durable replay | Unlimited reconnect, eligibility, acknowledged channels, stable append-ID catch-up after the writer barrier, and delivery identity |
| S11 | 11: shared pooled path and PgBouncer | Every module's configured schema and separate LISTEN endpoint |
| S12 | 12: readiness | Eligibility, durability, catch-up, specific failing checks, and recovery |
| S10, S28, S29 | 13: endpoint recovery | Redundant proxies, stable-address transfer, session recovery, and differing proxy observations |
| S41, S42 | 7b and 8a to 8c: local admission | Own lease, selected watchdog-mode checks, withdrawn intent, local reconciliation, interrupted publication/activation/revocation, and lease loss during in-flight node effects |
| S43, S44 | 7b and 8c: policy lifecycle | Loaded quoted syntax, effective policy, rebuilt-peer exclusion, restored two-peer coverage, and repeated failover |
| S45, S46, S47, S50 | 10 and 12: client recovery | Bounded retry, authenticated writer status, SQL identity matching, writer barrier, lease ownership, uncertain cursor commit, and finite readiness |
| S48 | 13: pooler and address recovery | Both pool modes, complete surviving paths, interrupted transactions, and LISTEN bypass |
| S49 | 8b: Consul conditions and credentials | Atomic rejection, unauthorised access, retained policy history, lost replies, and guarded release |
| S51 | 7b.2 and 8b/8c: first-start bootstrap | Verified stopped or standby-only provisioning, conditional initial intent, guarded starts, confirmed first policy, every interruption, and refusal against existing or ambiguous history |
| S52 | 10 and 12: native recovery | Executed bounded claim, capacity waiting, confirmed processing acknowledgements, delayed scheduling, continuous arrivals, uncertain outcomes, and no readiness from skipped or retired work |
| S53 | 7b.1 and 7b.3: application commit enforcement | Production manager rejects weaker durability outcomes before transition assertions; lost commit replies and acknowledged data are verified through eligible promotion |
| S54 to S58 | Local fault cases in 7b.2/7b.3 and 8c/8d; independent exclusion at production qualification | Supervisor death and starvation, VM pause/resume, delayed renewal/keepalive, route-only failure, and safe takeover without failed-host remote acknowledgement. Device/reset cases apply to optional watchdog qualification. Unqualified cases remain open; they do not block local-supervisor implementation. |
| S59 | 7b.2: coordinator port | One contract suite run against each adapter's real service. The suite is `PgLeaseCoordinatorContract`; its Consul binding is `ConsulLeaseCoordinatorIntegrationTest`. It includes acquisition after release and guarded release. |

Each dependency needs separate failed-Future, throw, null or malformed, timeout, lost-response,
and stale-completion cases where its interface admits that mode. Add class and method mappings
only after reading the relevant module's tests. Record fresh per-class counts and logs after
each implementation phase. A requirements table is not test evidence.

## 8. Phases

One phase at a time. Each Java or Maven phase rebuilds its affected reactor slice before
targeted verification. Report exact per-class counts and unverified paths. No phase advances
on zero executed tests or swallowed teardown failure.

| Phase | Work and completion condition |
|---|---|
| 1 to 4 | Earlier document review and baseline. Dated records remain in §4 and §9. They do not establish revised acceptance. |
| 5 | Finish the existing profile D test repair under P-14. Inspect the current source and rerun it. Move production convergence to phase 7 without deleting the historical red result. Open: the repair `f391a7f0` has not run in a Jenkins build from `beginning`, and such a build cannot finish inside the pipeline's 150-minute limit (Jenkins build #41, §9.33 in the archived sections). |
| 6 | Completed earlier document alignment. The optional-watchdog correction superseded its mandatory wording. The companion designs were aligned on 2026-10-08 (§9.10). No Java or Maven change in this reconciliation. |
| 7a | Architecture selection complete. One local supervisor, node-owned Consul lease, optional watchdog modes, local admission, persistent node storage, and configurable redundant endpoints. Same protocol on Linux hosts/VMs, Docker hosts, and Kubernetes. G-1 to G-7 remain qualification gates. |
| 7b.1 | Complete: production-manager owned commit-policy enforcement and bounded failure observation. Recorded evidence in §8.1. Broad caller migration remains phase 11. |
| 7b.2 | Partial: the coordinator port, the Consul adapter, acquisition after release, guarded release, the watchdog timing correction, local persistent contracts, supervisor-owned start and lease-loss shutdown, local admission, and the renewal loop with queued ownership operations are implemented and tested (§8.1, §9.11, §9.12). Optional watchdog modes are implemented with no-device tests (§9.13). Bootstrap scope A is implemented and tested (§9.14). Scope B increment B1, the durability boundary, is implemented and tested (§9.15). Scope B increment B2 is implemented and tested (§9.16 to §9.19, complete 2026-10-25). Remaining in 7b.2: the real-device watchdog run on a Linux host. Complete G-7 incrementally. Machine-reset qualification is not an implementation prerequisite. |
| 7b.3 | Partial, 2026-11-21: increments P1 to P7, takeover after real lease expiry, planned stop with release, member tags, automatic rewind and rebuild, restart, reload, and `pending_restart`, coordinator configuration CC1 to CC3, and PP1 of the PostgreSQL parameters are implemented and tested (§8.1). Remaining: PP2 to PP5 (§9.35), status and metrics endpoints and callbacks (§9.28 answers 13 and 14), and the items of §8.1 row "7b.3 remaining". Prove old-writer exclusion, stale-effect rejection, repeated failover, and two-node unavailability with production-manager data assertions. |
| 8a | Enable autonomous initiation on the verified common A/B boundary. No second provider, authority, or generation barrier. Test concurrent standby contenders, invalid requests, lease loss during effects, and local activation/revocation races. |
| 8b | Complete per-node automatic ownership and bootstrap reconciliation. Prove quorum loss, namespace/restore handling, invalid records, and distinction between sidecar/observer failure and writer-supervisor death. |
| 8c | Verify automatic infrastructure failover: host loss, asymmetric partition, supervisor death, pauses/resume, delayed ownership/keepalive, in-flight effects, and unreachable-host takeover. No remote stop receipt requirement. |
| 8d | Verify automatic database transition safety with three PostgreSQL nodes, Consul quorum, supervisors, selected watchdog mode, proxies, and confirmed policies. Use the production-manager commit path. Full application timing waits for phases 10 to 14. Qualify independent protection separately before claiming supervisor-death or whole-VM pause safety. |
| 9 | Complete operation and statement deadlines, millisecond precision, lifetime at idle, context-aware discard, and observed cleanup. Retain the verified phase 7 commit boundary and its required bounded failure observation. Add loader, defaults, property-contract, and guide changes for proposed keys. |
| 10 | Implement authenticated `/writer` observation, SQL node identity, separate LISTEN deadlines, initialized-success backoff reset, and shutdown. Reuse the stable writer-barrier replay and lease contract. Implement finite native recovery with executed/skipped/deferred distinctions, capacity waiting, confirmed acknowledgements, and delayed wake-ups. Verify continuous arrivals, uncertain mutations, and retired completions. No second progress mechanism. |
| 11 | Move each remaining module to the verified shared pooled access and classified availability breakers. Preserve phase 7 commit enforcement; do not first introduce it here. Verify schemas through PgBouncer transaction mode. One module per sub-phase. |
| 12 | Implement readiness for writer eligibility, durability, and required catch-up. Keep workload conditions separate from database availability. |
| 13 | Complete frozen-node, in-flight operation, redundant HAProxy/PgBouncer, SQL/HTTP address ownership, and differing-observation scenarios. Qualify the selected G-3 topology and remaining fault cases. |
| 14 | Build target stacks and runbook. Execute every scenario, including policy cutover/repeated failover, controlled membership/profile change, and restored coordination. Measure the recovery objective under specified backlog, handler latency, retry state, and deployed deadlines. Save platform-specific evidence. |
| 15 | Reassess every historical finding against code and fresh logs. Complete G-1 to G-7. Update task register and external documentation links. Close only when all revised requirements and S1 to S59 have matching evidence. |

Phase 7b.1 is a commit-enforcement component test. Use an isolated real synchronous fixture
with a fixed test-only proxy route to its known primary. It verifies the production manager
before the local admission integration is implemented; it does not qualify profile A or B routing.
Bootstrap tests use separate newly provisioned fixtures. Phases 7b.2/7b.3 and 8 repeat application
write evidence through the full admitted stable endpoint. No component fixture closes an
admission, fencing, or whole-application recovery obligation.

The full production recovery objective is 45 seconds for the qualified three-node workload and
fault case. The deadline defaults do not prove it. Two-node synchronous promotion has no committed-write recovery target until a standby
is restored. Do not admit a replacement where the selected fault case lacks established
old-writer exclusion. Optional watchdog mode alone is not exclusion evidence.

This reconciliation records completed implementation slices. It does not close any new
runtime or production qualification obligation. Task 8 carries the same status and next scope.

### 8.1 Current Implementation and Recorded Evidence, 2026-10-08 to 2026-11-21

Source inventory and saved logs were checked for the 2026-10-08 reconciliation. The runs dated
2026-10-08 are recorded runs, not new test executions. The runs dated 2026-10-09 were executed
for that day's revision. The rows dated 2026-10-21 and 2026-10-22 have their runs in §9.12 to
§9.15. The rows dated 2026-10-24 to 2026-11-18 have their runs in §9.16 to §9.34. None is a
release gate.

| Slice | State | Artifact and limit |
|---|---|---|
| 7a architecture | Complete | Dedicated local-supervisor architecture. The mandatory-watchdog decision is superseded by §5.2.1. No deployment qualification follows from this status. |
| 7b.1 managed commit boundary | Complete | `PgConnectionManager` owns begin/commit/rollback and observed close, restores transaction-local `synchronous_commit=on`, checks caller-completed transactions, and reports uncertain commits through `PgCommitOutcomeUnknownException`. The fixture uses HAProxy, a primary, and two physical synchronous standbys. Direct-pool callers and post-promotion durability remain unqualified. |
| 7b.2 coordinator port (P-38, S59) | Implemented and component-tested, 2026-10-08 | `PgLeaseCoordinator` is the port with seven operations. `PgControlRecord` carries `controlName`, `generation`, `revision`, `leaseHolder`, and `intent`. `PgPrimaryElector(PgNodeConfig, PgLeaseCoordinator)` holds intent validation, the freshness deadline, retirement, late-reply rejection, and one ownership operation at a time (queued since OD-4); it makes no product call. `ConsulLeaseCoordinator` in package `dev.mars.peegeeq.failover.consul` holds every Consul HTTP call and the Consul TTL range check. A search of `peegeeq-pg-failover/src` outside `consul/` for `/v1/`, `X-Consul`, `session`, `LockIndex`, `ModifyIndex`, and `consul` returns no match. |
| 7b.2 acquisition after release, and guarded release | Implemented and component-tested, 2026-10-08 | Acquisition after release is one Consul transaction: `check-index`, `lock` with a new session, `get`. Release is one transaction: `check-session`, `check-index`, `unlock` with the value resent, `get`. The elector requires withdrawn intent that names this node, names the released writer as previous writer, and carries both policies unchanged. Release retires the elector whatever the outcome. Release does not require local freshness; the coordinator's conditions guard it. |
| 7b.2 policy validation | Corrected, 2026-10-08 | The earlier elector rejected any policy that named the writer. A takeover intent must carry the former writer's policy, which names the new writer. The rule is now: a policy this node introduces never names the writer; a policy carried unchanged from the prior record may; a `SERVING` confirmed policy never names the writer. |
| 7b.2 timing and watchdog configuration | Corrected and tested, 2026-10-08 | `PgWatchdogMode` is `AUTOMATIC`, `OFF`, or `REQUIRED`. `PgNodeConfig` applies the lease rule in every mode: `loop + 2 × retry <= TTL` and `loop + retry + stop < TTL`. Only `REQUIRED` rejects a configuration without watchdog slack (`loop + retry < TTL/2` and `stop < TTL/2`). `ownershipBudget()` is derived: `TTL − stop` in `OFF` or when watchdog timing is not usable; otherwise the smaller of that and `TTL − TTL/2`. The elector's freshness deadline uses it. The watchdog device adapter was added on 2026-10-21 (watchdog modes row). |
| 7b.2 local persistent contracts | Implemented and tested, 2026-10-08 | `PgLocalStateStore` with `PgWriterGrant` (mode, generation, operation, policy revision, node, state), `PgQuarantine`, and `PgActionReceipt` (generation, operation, action, target node, immutable parameters, result, effect). A grant loaded at open is closed durably before the store is returned. Quarantine blocks preparation and activation and survives restart. A receipt begun twice with equal parameters is one receipt; changed parameters are rejected; a final result is never replaced. `PgLocalStateStoreTest` runs 29 tests against a real directory: `logs/phase7b2-local-state-core-20261008.log`; guards 14: `logs/phase7b2-local-state-guards-20261008.log`. `PgFailoverMonitor` and `PgBootstrap` use the store. |
| 7b.2 supervisor-owned start and local shutdown | Implemented and tested, 2026-10-08 | `PgProcessControl` port; `PgCtlProcessControl` adapter; `LocalCommandRunner`. `PgFailoverMonitor` has `startStandby`, `startPrimary(held)`, `renew`, and `demote`. Primary start is refused without fresh ownership or under quarantine, runs with admission closed, and records a `start-primary` receipt. A failed renewal retires ownership, closes the grant, stops PostgreSQL within the stop budget, and records a `stop-writer` receipt. An unconfirmed stop fails the demotion, quarantines the node, and records the receipt as `UNKNOWN`. Local effects run one at a time; renewal does not queue behind them. Tests use a real PostgreSQL whose container entry point does not start it, a real Consul server, and a real state directory. The loop timer that calls `renew` was added on 2026-10-21 (renewal loop row). Promotion was added by 7b.3 increment P1 on 2026-10-26 (P1 row). |
| 7b.2 local admission (OD-1) | Implemented 2026-10-08, compiled and tested 2026-10-09 | `PgAdmissionGate` port; `PgHbaAdmissionGate` adapter (§5.3). `PgFailoverMonitor` has `prepareWriter`, `activateWriter`, and `revokeWriter`; `PgPrimaryElector` has `holds`. Standby start and primary start close the gate first. Preparation requires fresh ownership and a confirmed policy. Activation requires fresh ownership of `SERVING` intent and the matching prepared grant; if the gate does not open, admission is revoked and the call fails. Revocation closes the grant and the gate and leaves PostgreSQL running. Commit `ea045d7f` contained this code before it had been compiled. `PgHbaAdmissionGateIntegrationTest` (6 tests) covers the gate's failure modes: an active file that does not hold the installed rules, a `pg_ctl status` exit code other than 0 or 3, a reload with invalid rules on open and on close, an exhausted or non-positive budget, and `open` while PostgreSQL is stopped. A close with invalid rules fails and PostgreSQL keeps the rules that admit applications, so the caller must stop the writer. Each test was confirmed by removing the behaviour from the gate (§9.11). |
| 7b.2 failure logging | Corrected and tested, 2026-10-09 | The module had twelve WARN statements, and eleven of them reported failed, refused, or forced operations. The test-support gate fails a test on an undeclared ERROR and does not see WARN, so no test had to declare them. Those eleven now log at ERROR or, for a requested demotion or revocation, at INFO. `pg_ctl stop` returning a non-zero exit code stays at WARN because the following status check decides. `PgLocalStateStore` reports a storage or decoding fault as "Node-local storage failed", apart from a refusal by one of its rules. Failures that were returned to the caller without any log are now reported too: `PgFailoverMonitor` logs every failed operation at its boundary, and `PgPrimaryElector` logs every refused operation and every late reply. 75 test methods declare the errors they cause with 82 `@ExpectedErrorLog` declarations (§9.11). |
| Sidecar | Role-only | `PgPrimaryCheckVerticle` answers `/primary` from `pg_is_in_recovery()` alone. Commit `8b96af9a` (2026-11-08) made `stop` fail when the pool close fails and added `PgPrimaryCheckReplicaIntegrationTest`; findings IMP-05 and IMP-12 of §3 (archived) were not reassessed against it. It has no coordinator read, grant check, node identity check, coverage check, `/writer` route, authentication, or request deadline. |
| 7b.2 renewal loop (OD-4) | Implemented and tested, 2026-10-21 | `PgRenewalLoop` calls `PgFailoverMonitor.renew()` on the loop interval, never with a renewal in flight, and starts renewals at least one interval apart. Start requires fresh ownership and is allowed once. A failed renewal ends the loop after the monitor has demoted the writer; `completion()` fails with that failure. `stop()` cancels the next renewal, waits for one in flight, and changes neither ownership nor the writer. A timer that cannot be scheduled fails `start()`; mid-run it demotes the writer and ends the loop. `PgPrimaryElector` queues `renew`, `update`, and `release` (OD-4, §5.3). `PgBootstrap` starts the loop directly after the initial acquisition. No production entry point constructs the loop or `PgBootstrap`. |
| 7b.2 optional watchdog modes (OD-5) | Implemented 2026-10-21; no-device paths tested | `PgWatchdog` port; `PgDeviceWatchdog` adapter (§5.3). `PgFailoverMonitor` applies the mode before a primary start: `off` never uses the watchdog; `automatic` arms it or reports at ERROR and continues; `required` refuses the start when it cannot be armed with a safe timeout. An armed watchdog is fed only after a successful renewal; a failed keepalive demotes the writer. A confirmed stop disarms it; an unconfirmed stop leaves it armed and unfed. The armed state is in memory only. Nothing has run against a real device. Since 7b.3 increment P1 (2026-10-26) the mode is applied before a primary start and before a promotion (`PgFailoverMonitor.promoteStandby`). |
| 7b.2 bootstrap scope A (OD-6 to OD-8) | Implemented and tested, 2026-10-21 | `PgBootstrapRequest` reads and verifies the request file. `PgBootstrap.start` refuses a quarantined node, a node with any stored grant, and an existing control record; a record of the same operation is reported as an interrupted bootstrap. It then creates the withdrawn initial intent with pending policy revision 1, starts the renewal loop, records the `bootstrap` receipt with the request as parameters, and starts PostgreSQL through the monitor with admission closed. A failed start stops the loop. The receipt has no result; completion is derived in scope B. A rerun is refused, not resumed. The request is not operator-authenticated (OD-6) and other members are not inspected (OD-7). |
| 7b.2 bootstrap scope B, increment B1 (OD-9 to OD-11) | Implemented and tested, 2026-10-22 | `PgDurabilityControl` port; `PgSqlDurabilityControl` adapter. It reads the writer's WAL flush boundary (refused in recovery), lists replication peers, and installs a policy by OD-9. `PgDurabilityPolicy` is the typed form of the control record's policy: it generates the quoted setting and holds the coverage rule (OD-11). `PgLsn` and `PgReplicationPeer` are derived observations. No command argument contains a double quote: the installed value is an escape string. Nothing calls the port yet. |
| 7b.2 bootstrap scope B, increment B2, step 1: guarded policy installation | Implemented and tested, 2026-10-24 | `PgDurabilityControl.confirmCovered` observes one fresh flush boundary and waits for every required standby to cover it within the adapter's budget. `PgFailoverMonitor.installPendingPolicy(held)` derives the policy from the held record's pending policy, closes the grant and the gate, confirms coverage, and installs under the `install-policy` receipt (`COMPLETED`, or `UNKNOWN` after a failed install). The control record is not changed. `PgFailoverMonitor` takes a `PgDurabilityControl`. See §9.16. |
| 7b.2 bootstrap scope B, increment B2, step 2: confirmation, publication, activation | Implemented and tested, 2026-10-24 | `PgBootstrap.serve(held, mode)` runs after `start`: install the pending policy, confirm revision 1 (the pending policy becomes `durabilityPolicy`), prepare the grant, publish `SERVING`, activate the grant. A failure at any step leaves admission closed and the record where the step left it. No bootstrap receipt result is written; completion is derived. See §9.17. |
| 7b.2 bootstrap scope B, increment B2, step 3: production-manager write (OD-12) | Implemented and tested, 2026-10-25 | `peegeeq-db` is a test-scope dependency. The production `PgConnectionManager` connects directly to the writer node (the fixture publishes its port), not through HAProxy. Three tests in `PgBootstrapServingIntegrationTest`: a write before serving is rejected by the closed gate; a write after serving is acknowledged by both standbys (flush position checked); a caller that sets `synchronous_commit = off` still waits for both standbys and gets `PgCommitOutcomeUnknownException` with one standby stopped. See §9.19. |
| 7b.2 bootstrap scope B, remaining | Not implemented | A production entry point that calls `PgBootstrap`, the renewal loop, and the watchdog; resuming an interrupted `serve` (the takeover path, 7b.3). The planned stop before release is implemented (2026-11-14, §9.30). |
| 7b.3 increment P1: guarded local promotion with CHECKPOINT | Implemented and tested, 2026-10-26 | `PgPromotionControl` and `PgSqlPromotionControl` (`pg_ctl promote`, role observation, forced and verified CHECKPOINT); `PgFailoverMonitor.promoteStandby(held)` under `promote` and `checkpoint-after-promote` receipts, with admission closed. See §9.21. Not in P1: slots, repointing standbys, the policy cutover, planned stop with release, expiry takeover. |
| 7b.3 increment P2: a standby follows the new writer | Implemented and tested, 2026-10-26 | `PgPromotionControl.follow`, `PgWriterEndpoint` (host, port, password-file path; no password), `PgFailoverMonitor.followWriter` under a `follow-writer` receipt, no lease required. See §9.22. Not in P2: the policy cutover, slots, following after a rewind. |
| 7b.3 increment P3: the policy cutover on the new writer | Implemented and tested, 2026-11-03 | `PgTakeover.cutover(held, mode)` derives the next policy (confirmed set minus the new writer, next revision), persists it as pending, then runs the serving sequence shared with `PgBootstrap.serve`. Fixture finding: standby admission files lacked replication rules. See §9.23. |
| 7b.3 increment P4: member replication slots on the writer | Implemented and tested, 2026-11-12 | `PgNodeConfig.slotName` (derived, never stored); `PgDurabilityControl.ensureSlots` (creates missing physical slots; refuses an unbounded `max_slot_wal_keep_size` (OD-27) and a logical slot under a member name; its refusal in recovery was removed by P6, OD-35); `PgFailoverMonitor.ensureMemberSlots`; the slot step after the checkpoint in `promoteStandby` and first in `PgPolicyCutover.serve`; `follow` streams through the node's own slot; coverage fails at once for a required standby whose slot is lost (OD-33). See §9.24 and §9.25. Not in P4: slots on replicas, removing a departed member's slot. |
| 7b.3 increment P5: replacing a lost member slot for a rebuild | Implemented and tested, 2026-11-12 | `PgDurabilityControl.replaceLostSlot` (only a lost slot is dropped, then re-created reserved) and `PgFailoverMonitor.replaceLostMemberSlot` (ownership, membership, quarantine). The fixture rebuilds the member through the new slot. See §9.26. Not in P5: the rebuild decision and the member-side commands outside the fixture (§9.20 row 10). |
| 7b.3 increment P6: slots on standbys, advanced to published member positions | Implemented and tested, 2026-11-12 | A second coordinator port `PgMemberPositions` with its Consul adapter; every node holds the member slots (OD-35); `PgMemberSlots` publishes this node's position and advances a standby's slots to the smaller of the published and the replay position, same timeline only. Closes the gap of §9.24: a standby stopped before a takeover follows the new writer. See §9.27. Not in P6: the loop that runs it, removing a departed member's record. |
| 7b.3 increment P7: session-bound member positions, their contract, the monitor builder, candidate ranking | Implemented and tested, 2026-11-13; module green on Jenkins build #36 | `PgMemberPositionException` and `PgMemberPositionsContract` with the Consul binding; each member record is held by the member's own Consul session and disappears at its expiry, which removes a departed member's record; `PgFailoverMonitor.builder()`, with `publishPosition` and `advanceMemberSlots` as serialized monitor operations (`PgMemberSlots` deleted); `PgDurabilityControl.candidatePosition` and `PgFailoverMonitor.takeoverCandidacy`. See §9.28. Not in P7: the automatic initiation that acts on the candidacy (phase 8a), the supervisor loop. |
| 7b.3 takeover after real lease expiry | Implemented and tested, 2026-11-14; module green on Jenkins build #39 | `PgTakeover.acquire(observed, mode)` builds the withdrawn takeover intent from an unowned record and acquires it. It refuses before any coordinator write a record still owned, a record naming this node as writer, a node outside the confirmed policy, quarantine, and in automatic mode a candidacy that is not preferred. `PgTakeoverAfterExpiryIntegrationTest` covers a lost former host and a former writer cut off from the coordinator whose own supervisor stops PostgreSQL before the successor acquires. See §9.29. |
| 7b.3 planned stop with release (OD-15) | Implemented and tested, 2026-11-14; module green on Jenkins build #39 | `PgFailoverMonitor.stopAndRelease(held, reason)` stops PostgreSQL, then releases the lease only after a confirmed stop. An unconfirmed stop retires ownership and fails without a release. `demoteNow` retires, then stops. See §9.30. |
| 7b.3 member tags (answer 11) | Implemented and tested, 2026-11-14 (Jenkins host runs; Jenkins build #40 on `b3f7ddc9`, 2026-11-15: SUCCESS, `peegeeq-pg-failover` 728 tests, 0 failures) | `PgMemberTags` (`nofailover`, `nosync`, `noloadbalance`, `clonefrom`) in `PgNodeConfig` as deployment configuration. A node tagged `nofailover` or `nosync` never acquires a takeover, is never promoted, and is ignored as a rival candidate. A new policy cannot require a `nosync` member, and the cutover does not count a `nosync` standby. `noloadbalance` and `clonefrom` are validated and stored; nothing acts on them yet. See §9.31. |
| 7b.3 automatic rewind and rebuild (answer 9) | Implemented and tested, 2026-11-16 (Jenkins host runs); module green in Jenkins build #41 on `0ffaafb6`, 2026-11-20 (§9.33, "Jenkins builds #41 and #42") | `PgFailoverMonitor.rejoin(observed, writer, budget)` under the re-join quarantine: a rewind (`pg_rewind`, after a crash recovery that keeps the inhibitor), and a rebuild (`pg_basebackup` through the node's slot) when the rewind does not produce a caught-up standby. The source must hold the lease, be writable, share the system identifier, and hold the node's usable slot, checked before the stop and before any removal. Own configuration files kept; inhibitor recreated after every rewind and base backup. Reverses P-6. See §9.32. |
| 7b.3 restart, reload, and `pending_restart` (answer 10) | Implemented and tested, 2026-11-17 and 2026-11-18 (owner's workstation, local Docker; the integration tier did not pass in one run there); module green in Jenkins build #41 on `0ffaafb6`, 2026-11-20 (§9.33, "Jenkins builds #41 and #42") | `PgConfigurationControl` and `PgSqlConfigurationControl`: `settings()` and a `reload()` that refuses a file PostgreSQL rejects and reports an invalid value after the reload. The three existing reload sites (policy installation, follow, the admission gate) refuse a rejected file; the gate's close still installs the closed rules and ends the sessions before it fails (OD-62). `PgFailoverMonitor.reload`, `restart(observed, request)` (own stop and guarded start, re-admission of a serving writer, confirmation by postmaster start time), `scheduleRestart`, `cancelScheduledRestart`, `runScheduledRestart(observed, now)`; the schedule is node-local (`scheduled-restart.json`). Nothing outside tests calls them. See §9.33. |
| 7b.3 coordinator configuration CC1 to CC3 (answer 12) | Implemented and tested, 2026-11-18 (owner's workstation; the classes touched, core, and guards ran); module green in Jenkins build #41 on `0ffaafb6`, 2026-11-20 (§9.33, "Jenkins builds #41 and #42") | `PgClusterSettings` holds the lease and local-stop rule, the one copy of it; `PgNodeConfig.clusterSettings()` and `withClusterSettings(settings)`. `PgClusterConfiguration` (`read`, `create`, `update` under a revision check) with `PgClusterConfigurationContract` and the Consul adapter on `peegeeq/pg/<cluster>/<incarnation>/config`. `PgBootstrap.start` writes the deployment timing as the first record, accepts an equal record, and refuses other values before the initial intent. Nothing calls `update`; only tests construct `PgBootstrap`. See §9.34. |
| 7b.3 remaining | Not implemented | The re-join policy cutover that restores two-peer coverage; for coordinator configuration (answer 12, §9.34), the supervisor loop that reads the settings before an acquisition, and PostgreSQL parameters in the coordinator (§9.35: PP1 built 2026-11-21, uncommitted; PP2 to PP5 not built); status and metrics endpoints (answer 13), callbacks (answer 14), manual takeover through an operator entry point, repeated failover, controlled re-join, the production entry point and supervisor loop (no production entry point exists: `PgBootstrap`, `PgPolicyCutover`, `PgRenewalLoop`, and `PgTakeover` call monitor operations, and only tests construct them or the monitor), and routed production-manager assertions. |
| G-7 full coordinator qualification | Open | Snapshot/restore, TLS, stopped-before-release, and expiry versus independent old-writer exclusion remain unverified. Component success does not close G-7. |

Recorded clean installs used `mvn clean install -DskipTests -pl :<changed-module> -am`:

| Module | Saved successful rebuild |
|---|---|
| `peegeeq-db` | `logs/phase7b1-standby-compatible-rebuild-20261008.log` |
| `peegeeq-pg-failover` | `logs/phase7b2-lease-verified-rebuild-20261008.log` |

Every class below reports zero failures, errors, and skips in its saved final run.

| Slice and profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| 7b.1 `integration-tests` | `PgConnectionManagerDurabilityIntegrationTest` | 15 | `phase7b1-completion-tests-20261008.log` |
| 7b.1 `integration-tests` | `PgConnectionManagerCoreTest` | 22 | `phase7b1-completion-tests-20261008.log` |
| 7b.1 `integration-tests` | `PgPoolCircuitBreakerIntegrationTest` | 1 | `phase7b1-completion-tests-20261008.log` |
| 7b.1 default/core guards | `DisabledTestsGuardTest` | 2 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `InvalidDurationLiteralGuardTest` | 2 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `OnSuccessExceptionSwallowingGuardTest` | 8 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `SchemaInitializerTestInfrastructureGuardTest` | 1 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `VertxAsyncForbiddenPatternsGuardTest` | 1 | `phase7b1-completion-guards-20261008.log` |
| 7b.2 `integration-tests` | `ConsulLeaseProtocolIntegrationTest` | 28 | `phase7b2-lease-verified-integration-20261008.log` |
| 7b.2 default/core | `PgNodeConfigTest` | 8 | `phase7b2-lease-verified-core-20261008.log` |
| 7b.2 default/core guards | `DisabledTestsGuardTest` | 2 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `InvalidDurationLiteralGuardTest` | 2 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `OnSuccessExceptionSwallowingGuardTest` | 8 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `SchemaInitializerTestInfrastructureGuardTest` | 1 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `VertxAsyncForbiddenPatternsGuardTest` | 1 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 port and timing `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 60 | `phase7b2-watchdog-timing-integration-20261008.log` |
| 7b.2 port and timing default/core | `PgNodeConfigTest` | 17 | `phase7b2-watchdog-timing-core-20261008.log` |
| 7b.2 port and timing guards | `DisabledTestsGuardTest` | 2 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `InvalidDurationLiteralGuardTest` | 2 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `OnSuccessExceptionSwallowingGuardTest` | 8 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `SchemaInitializerTestInfrastructureGuardTest` | 1 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `VertxAsyncForbiddenPatternsGuardTest` | 1 | `phase7b2-watchdog-timing-guards-20261008.log` |

Final runs for §8.2 items 1 to 4, after the last source change on 2026-10-08. Rebuild:
`phase7b2-process-control-rebuild-20261008.log`. Every class reports zero failures, errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 60 | `phase7b2-process-control-integration-20261008.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 9 | `phase7b2-process-control-integration-20261008.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `phase7b2-process-control-integration-20261008.log` |
| default/core | `LocalCommandRunnerTest` | 5 | `phase7b2-process-control-core-20261008.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `phase7b2-process-control-core-20261008.log` |
| default/core | `PgNodeConfigTest` | 17 | `phase7b2-process-control-core-20261008.log` |
| default/core guards | five guard classes | 14 | `phase7b2-process-control-guards-20261008.log` |

Red evidence for each scope: `phase7b2-port-red-20261008.log` (compilation),
`phase7b2-watchdog-timing-red-20261008.log` (compilation),
`phase7b2-watchdog-budget-red-20261008.log` (2 run, 1 failure: ownership lapsed before its
budget in `off` mode), `phase7b2-local-state-red-20261008.log` (compilation), and
`phase7b2-process-control-red-20261008.log` (compilation).

`ConsulLeaseProtocolIntegrationTest` (28 cases) was deleted on 2026-10-08. Its cases run in
`ConsulLeaseCoordinatorIntegrationTest`: 20 through the neutral contract suite
`PgLeaseCoordinatorContract`, and 8 as Consul wire cases in the binding. The rebuild for the
60-test run is `phase7b2-watchdog-timing-rebuild-20261008.log`.

The intermediate `phase7b2-lease-generation-tests-20261008.log` records 22 tests and one
failure: the close-verification read returned HTTP 403. The isolated close diagnostic ran
one test successfully. The final full class ran 28 successfully. The intermediate failure's
cause remains unverified. Do not report it as fixed or add a retry to conceal it.

Not checked by the 2026-10-08 reconciliation: new runtime behavior, Java regressions after a code
change, end-to-end admission/takeover, production TLS/restore, independent watchdog
exclusion, whole-VM pause/resume, or the 45-second recovery objective.

Runs of 2026-10-09, executed on a second development machine after the last source change of
that day. The logs dated 2026-10-08 named above are untracked files (`logs/` is ignored by git)
and were not present on that machine, so none of them was reread. Rebuild:
`rebuild-peegeeq-pg-failover-gaps-green-20261009.log`. Every class reports zero failures,
errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 20 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| default/core | `LocalCommandRunnerTest` | 6 | `peegeeq-pg-failover-core-gaps-green-20261009.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `peegeeq-pg-failover-core-gaps-green-20261009.log` |
| default/core | `PgNodeConfigTest` | 17 | `peegeeq-pg-failover-core-gaps-green-20261009.log` |
| default/core guards | five guard classes | 14 | `peegeeq-test-support-core-guards-errorlevels-20261009.log` |

After these runs the gate source was changed for a probe and restored to the same SHA-256.
`PgHbaAdmissionGateIntegrationTest` then passed 6 of 6 again:
`peegeeq-pg-failover-gate-restored-20261009.log`.

Red evidence of 2026-10-09:

- `peegeeq-pg-failover-integration-20261009.log`: 88 run, 1 failure. The first test of
  `ConsulLeaseCoordinatorIntegrationTest` failed in its setup with HTTP 403 (§9.11).
- `pg-failover-consul-acl-red-run1-20261009.log` to `-run6-`: one contract test run alone six
  times before the fixture correction. Four runs failed, two with HTTP 403 in setup and two with
  HTTP 500 from session creation. `pg-failover-consul-acl-green-run1-20261009.log` to `-run10-`:
  the same test passed ten times after the correction.
- `peegeeq-pg-failover-core-errorlevels-red-20261009.log`: 51 run, 23 failures.
  `peegeeq-pg-failover-integration-errorlevels-red-20261009.log`: 91 run, 43 failures. These runs
  had the `@ExpectedErrorLog` declarations and the unchanged production code. Every failure is
  "Expected ERROR occurrence mismatch" with `observed=0`.
- `peegeeq-pg-failover-integration-gaps-red-20261009.log`: 97 run, 14 failures, the same reason,
  for the refusals and late replies that were not yet logged.
- `peegeeq-pg-failover-core-gaps-red-20261009.log`: 52 run, 1 failure. The new
  interrupted-command test found an output file that could not be deleted.
- `peegeeq-pg-failover-gate-mutation-red-20261009.log` and
  `peegeeq-pg-failover-gate-mutation-c-red-20261009.log`: the six gate tests against a gate with
  each behaviour removed. All six failed.

Whole-repository run of 2026-10-09, after the last source change. `mvn clean test -Pall-tests`
stopped at `peegeeq-db`: `peegeeq-test-support` 87 tests and `peegeeq-api` 228 tests passed;
`peegeeq-db` ran 1,124 tests with 1 failure. The failure is
`HaProxyConnectionFailoverTest.testFailbackAfterPrimaryRecovery`, at the step that §4.2 records
as red since 2026-10-07: the pool that served queries during the outage did not reach the
replacement primary within 30,000 ms. Log: `all-tests-20261009.log`. No file in `peegeeq-db` was
changed on 2026-10-09. After `mvn clean install -DskipTests -pl :peegeeq-db -am`, the run was
resumed with `mvn test -Pall-tests -rf :peegeeq-outbox`. All 17 remaining modules passed.
Log: `all-tests-resume-outbox-20261009.log`.

| Module | Tests run | Failures |
|---|---:|---:|
| `peegeeq-outbox` | 667 | 0 |
| `peegeeq-native` | 361 | 0 |
| `peegeeq-bitemporal` | 506 | 0 |
| `peegeeq-runtime` | 48 | 0 |
| `peegeeq-rest` | 521 | 0 |
| `peegeeq-rest-client` | 48 | 0 |
| `peegeeq-service-manager` | 76 | 0 |
| `peegeeq-pg-sidecar` | 9 | 0 |
| `peegeeq-pg-failover` | 149 | 0 |
| `peegeeq-examples` | 178 | 0 |
| `peegeeq-benchmarking` | 151 | 0 |
| `peegeeq-migrations` | 53 | 0 |
| `peegeeq-integration-tests` | 109 | 0 |
| `peegeeq-management-ui` | Vitest 128, Playwright 419 | 0 |
| `peegeeq-utilities-ui` | Vitest 836, Playwright 246 | 0 |

No module total reports a skipped test. Twelve classes report zero tests. Each is an outer class
whose tests are in nested classes, and the nested counts equal the test annotations in the
source. The whole-repository gate is not green: it cannot pass while the phase 5 test is red.

Not checked on 2026-10-09: end-to-end admission through a proxy, takeover, production
TLS/restore, independent watchdog exclusion, whole-VM pause/resume, and the 45-second recovery
objective.

_§8.2 (the completed 7b.2 scope) was moved on 2026-11-21 to [the archived closed sections](../_archived/completed-records/PEEGEEQ_PG_FAILOVER_PLAN_CLOSED_SECTIONS_TO_2026-11-21.md), numbering unchanged._

## 9. Records

Where the records of each increment are:

| Sections | Where |
|---|---|
| §9.1 to §9.27 (reading record, document history, 7b.2 increments, P1 to P6) | [Archived closed sections](../_archived/completed-records/PEEGEEQ_PG_FAILOVER_PLAN_CLOSED_SECTIONS_TO_2026-11-21.md) |
| §9.28 (owner decisions of 2026-11-13, and P7) | Below |
| §9.29 to §9.34 (takeover after expiry, planned stop, member tags, rewind and rebuild, restart and reload, coordinator configuration) | [Archived closed sections](../_archived/completed-records/PEEGEEQ_PG_FAILOVER_PLAN_CLOSED_SECTIONS_TO_2026-11-21.md) |
| §9.35 (PostgreSQL parameters in the coordinator, open) | Below |

### 9.28 Owner Decisions of 2026-11-13, and Increment P7: Session-Bound Member Positions, Their Contract, the Monitor Builder, and Candidate Ranking (design)

**Owner decisions, 2026-11-13.** The owner answered fifteen questions on the open decisions of §9.20, §9.24, and §9.27,
then directed: "After verification proceed with implementation. Do not stop to ask me any questions." The questions
were asked before the whole document set and the module were read. The full reading was done afterwards, on the same
day: the five design documents, this plan, the Task 8 register entries, every production file of
`peegeeq-pg-failover`, and its test classes. Each row below states the decision and every conflict that reading found,
with its location. Where a decision conflicts with a design document, the document is changed in the increment that
implements the decision, not before.

| # | Item | Decision | Conflicts found, and what changes |
|---|---|---|---|
| 1 | OD-27 | Kept: an unbounded `max_slot_wal_keep_size` is refused | None. OD-27 is reviewed |
| 2 | OD-35 | Kept: every node holds a slot for each other member | None |
| 3 | OD-36 | Kept: member positions have their own port | None |
| 4 | OD-37 | Kept: the writer publishes its position too | None |
| 5 | OD-38 | Reversed: each member's record is held by that member's own coordinator session, and disappears when the session expires (Patroni's member keys) | System design §1.1, row "Published member position" ("It has no lease and no expiry"); §5.10, "Member positions" ("It has no lease and no expiry"); coordinator design §3.3, row "Member positions" ("plain PUT with no session"). Built code: `ConsulMemberPositions`. Increment P7b |
| 6 | OD-39 | Reversed in part: published positions rank the takeover candidates, as Patroni's leader race does | System design §1.1, same row ("Never read for eligibility, promotion, coverage, routing, or policy"); §5.10 ("a position is never authority"); coordinator design §1.1 ("Never an eligibility, promotion, or policy input"); OD-17 (§9.21). Eligibility stays membership in the last confirmed policy (system design §5.8 "Only nodes covered by the prior acknowledged-commit policy are eligible targets"; S40). A position only orders eligible candidates; it never makes a node eligible. Increment P7d |
| 7 | OD-40 | Reversed: publication and slot advancing move into `PgFailoverMonitor`, which is built by a builder instead of a constructor | OD-21 (§9.22), OD-29 (§9.24), and OD-40 each avoided a ninth constructor argument. That reason is removed. OD-21 and OD-29 keep their placement, which has other reasons. Increment P7c |
| 8 | OD-41 | Reversed: the positions port gets its own exception and a contract suite now, with Consul as the first binding | None beyond §9.27 "As built". Increment P7a |
| 9 | §9.20 row 10 | Rewind and rebuild are automatic; when a rewind fails, the data directory is removed and the member is rebuilt | Reverses P-6 (§5, "Rewind or rebuild is operator-controlled under quarantine"). Consistent with S31 and system design §5.9: quarantine is kept during the rewind or rebuild, a failed rewind is followed by a rebuild, and admission waits for standby validation (invariant 6). Operator reconciliation stays required for missing history, unexpected key deletion, two writable nodes, and restored coordination state (system design §5.7), and for missing policy history or conflicting receipts (§5.8). Coordinator design §6 rewind prerequisites apply. Its own increment |
| 10 | §9.20 row 9 | Restart (conditional and scheduled), reload, and `pending_restart` | Not in the design. Its own increment |
| 11 | §9.20 row 19 | Member tags `nofailover`, `nosync`, `noloadbalance`, `clonefrom` | `nosync`: a member that is never in a required set is never covered, so it is never a promotion target (§5.8); `nosync` therefore implies no promotion. `noloadbalance` needs a read-only replica endpoint, which does not exist (§9.20 row 16). `clonefrom` selects a rebuild source (row 10). Its own increment |
| 12 | §9.20 row 15 | Cluster-wide settings in the coordinator, changed by a guarded edit | System design §1.1, row "Lease TTL, HA-loop interval, retry budget, watchdog mode/device, and manual/automatic mode" ("Authoritative cluster configuration"); §5.4. Identity, membership, endpoints, the coordinator selection, and credentials stay in deployment configuration, because the supervisor needs them to reach the coordinator, and membership changes stay with phase 14 (S39). Every stored value must satisfy the timing rule of §5.4. Its own increment |
| 13 | §9.20 row 17 | `/cluster`, `/history`, a node status endpoint, and `/metrics` | Sidecar guide §1.1 and system design §9.1 ("Other paths: 404"); the sidecar is read-only and observes only eligibility. The supervisor has no HTTP server. The placement is decided in that increment's design. Its own increment |
| 14 | §9.20 row 23 | Callbacks `on_start`, `on_stop`, `on_restart`, `on_reload`, `on_role_change`; each failure logged at ERROR, none blocks the operation | The optional-step pattern of the coding principles §3 applies. Its own increment |
| 15 | §9.20 row 26 | Adopting an existing cluster is not supported | None; bootstrap keeps refusing existing history (§5.11) |

The owner also asked for these decisions not to be lost. They are this table; the register entry of 2026-11-13 points
to it.

**Order of work.** P7 (answers 5 to 8) comes first, because the next item of §9.27, takeover after a real lease
expiry, depends on the monitor's construction and on the candidate rule. Then, in order: takeover after real lease
expiry; planned stop with release (OD-15); member tags (11); automatic rewind and rebuild (9); restart and reload (10);
coordinator configuration (12); the status and metrics endpoints (13); callbacks (14). Each is designed in its own
section before its tests.

#### P7 data model

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Published member position | Coordinator record per member, under the incarnation's `members/` prefix, held by that member's own session; a copy of the member's live observation | It exists only while the member's session lives. A member that stops publishing loses its record when the session expires. It is never authority for exclusion, coverage, policy, or routing. It orders covered candidates (P7d) |
| Member session | Coordinator metadata, created and renewed by the adapter; its identifier is held in the adapter's memory only | One per member supervisor, separate from the writer lease, so the lease port and its contract are unchanged (OD-36). TTL equals the lease TTL. Expiry deletes the records it holds. Closing the adapter does not destroy it: the record ends at expiry |
| Candidate order | Derived on each call from this node's live position and the published positions of the other covered candidates | Never stored |
| Candidate position of a standby | Derived live: the larger of its received and replayed WAL position, on the timeline of its WAL receiver, or of its last restartpoint when it has no receiver | Observed after the writer is lost, when the standby no longer streams. Never published |

#### P7 contracts

1. **P7a, own exception and contract suite.** Every failure of `PgMemberPositions` is a `PgMemberPositionException`,
   including a synchronous throw. `PgMemberPositionsContract` states the port's obligations as tests: a publication is
   read back by every member; a later one replaces the earlier; an empty namespace reads as no positions; another
   node's position is refused before anything is sent; a publication the service rejects fails; a malformed record and
   a record of a non-member fail the read; a filtered read fails; an unanswered request fails within the request
   deadline; an unreachable service fails both operations. `ConsulMemberPositionsIntegrationTest` becomes its binding
   and keeps the Consul wire cases.
2. **P7b, session-bound records.** `ConsulMemberPositions` takes the Consul node name, as the lease adapter does, and
   validates it and the TTL range. `publish` renews the adapter's session, or creates one when it has none or the
   renewal finds it gone, and then writes the record with `?acquire=<session>`. The session has the lease TTL,
   `Behavior=delete`, `LockDelay=0s`, and no checks; it is read back and any differing setting fails. A write that
   Consul does not acquire (`false`) fails. `read` fails on a record that no session holds, because it is not a
   session-bound publication. Contract additions: a record whose member stops publishing disappears after the session
   TTL; a record without a session fails the read. ACL: each node's token writes its own member key, reads the prefix
   without a `list` rule (§3.3), and writes sessions on its own Consul node.
3. **P7c, the monitor builder.** `PgFailoverMonitor.builder()` sets the configuration, elector, store, process control,
   gate, watchdog, durability control, promotion control, and member positions; `build()` refuses a missing one by name.
   The public constructor is deleted. `PgFailoverMonitor.publishPosition()` and `advanceMemberSlots()` replace
   `PgMemberSlots`, with its contract, and run serialized with the other local effects. `PgMemberSlots` is deleted.
   Their failures are logged by the monitor's single boundary log line.
4. **P7d, candidate ranking.** `PgDurabilityControl.candidatePosition(nodeId)` observes the candidate position of the
   table above; it is refused on a writer. `PgFailoverMonitor.takeoverCandidacy(observed)` is refused when the observed
   record's confirmed policy does not name this node (`PgLeaseProtocolException`), and under quarantine. It reads this
   node's candidate position and the published positions, and returns `PgCandidacy(preferred, reason)`: not preferred
   when another member of the confirmed policy published a position on a higher timeline, or on the same timeline
   strictly beyond this node's position. A missing or expired record is ignored, as Patroni ignores a member it cannot
   reach. Equal positions leave both preferred; the lease acquisition decides. A published position is at most the
   member's present position, so a node defers only to a member that is ahead. Manual takeover names its target and is
   not ranked. The automatic initiation that acts on the result is phase 8a.

#### P7 dependency failure modes, each with a failing test before its code

| Dependency | Modes | Test class |
|---|---|---|
| Positions port, every adapter | failed request, rejected write, malformed or filtered read, non-member record, unanswered request, unreachable service, another node's position | `PgMemberPositionsContract` through its Consul binding |
| Consul member session | creation rejected; setting differs on read-back; renewal finds the session gone; record not acquired; record expires with the session | `ConsulMemberPositionsIntegrationTest` |
| Monitor builder | each dependency missing | `PgFailoverMonitorBuilderTest` (core) |
| Monitor publication and advance | quarantine; unreachable coordinator; the advance end to end | `PgSlotIntegrationTest`, `PgTakeoverIntegrationTest` |
| Candidate position | writer; streaming standby; standby without a receiver; command failure; malformed output; no result; no budget | `PgSqlMemberSlotIntegrationTest` |
| Candidacy | not named by the confirmed policy; quarantine; another candidate ahead; equal; another candidate's record expired; unreachable coordinator | `PgCandidacyIntegrationTest` |

**Order of work.** P7a, P7b, P7c, P7d, each with tests and stubs, red, implementation, green, probes with SHA-256
records, the touched classes, core, and the guards last.

**As built, where the code differs from the design text above.**

- P7a and P7b were written as one TDD cycle: one red run covered the exception type, the session, and the argument
  checks.
- P7c: the builder test was written after the builder code; probe 43 (the builder's null check) is its red.
- P7d: `PgSqlDurabilityControl.CANDIDATE` reads `pg_last_wal_receive_lsn()`, `pg_last_wal_replay_lsn()`,
  `received_tli` of the receiver, and `pg_control_checkpoint().timeline_id`. The adapter takes the larger of the two
  positions, on the receiver's timeline, else on the checkpoint's; a standby with neither position fails. A
  paused-replay test shows that the received position is used when replay is behind.
- PostgreSQL 15.13 facts used by P7d: after the writer is stopped, a standby has no `pg_stat_wal_receiver` row with a
  timeline, `pg_last_wal_receive_lsn()` keeps its last value, and `pg_control_checkpoint().timeline_id` was 1; with
  `pg_wal_replay_pause()` the receiver keeps receiving while replay stays behind.
- One positions adapter per member: a second adapter for the same member cannot acquire the key while the first
  adapter's session lives. Test added 2026-11-14:
  `aPublicationThatConsulDoesNotAcquireFailsAndLeavesTheHeldRecord`.

**Status, 2026-11-14.** Implemented (commits `c038a4ba`, `4b1e2a87`, `025ec74d`), green, and the module green on
Jenkins. The design documents describe P7 (system design §1.1 and §5.10; coordinator design §1.1 and §3.3). The red,
green, and probe logs of 2026-11-13 that the handover names (`peegeeq-pg-failover-p7*-20261113.log`, probes 34 to 50)
are not in `logs/` on the owner's workstation; their results are as reported in the handover and were not re-checked.
On 2026-11-25 the 17 probes were rerun on local Docker, each built from its rule name in the handover: 17 of 17
detected, with SHA-256 of `ConsulMemberPositions.java`, `PgFailoverMonitor.java`, and `PgSqlDurabilityControl.java`
equal before and after (`logs/review-p7-mutation-summary-run3-20261125.log`; probe 43 in
`logs/review-p7-mutation-summary-20261125.log`).

| Step | Evidence |
|---|---|
| Module on `4b1e2a87` | Jenkins build #36: `all` from `peegeeq-pg-failover`, SUCCESS in 101 minutes; `peegeeq-pg-failover` 30 classes, 704 tests, 0 failures, errors, or skips; downstream modules and both UI suites green |
| Acquire refusal | `aPublicationThatConsulDoesNotAcquireFailsAndLeavesTheHeldRecord` on `025ec74d`: Jenkins build #37, `ConsulMemberPositionsIntegrationTest` 19 of 19. Probe on the Jenkins host (`logs/mutation-acquire-20261114.log`): with the `"true"` check removed the test fails ("A write that Consul did not acquire was reported as stored"); SHA-256 of `ConsulMemberPositions.java` `20d3f01b...7a180d68` before and after, equal to the handover's record |
| Build #37 defects | Build #37 failed two tests on `025ec74d`. (1) `aWriterWithoutTheFollowersSlotIsNotReportedAsFollowedAndTheWaitEndsWithinItsBudget`: `follow` confirmed a receiver whose slot did not exist on the writer. Fixed below. (2) `aStoppedServerFailsTheInstall` timed out at the 30-second Vert.x default. Fixed below |

**Follow confirmation, 2026-11-14 (defect from build #37).** PostgreSQL 15 sets `status = streaming` in
`pg_stat_wal_receiver` when the receiver starts, and records `sender_host` and `sender_port` once it has connected;
only then does it send `START_REPLICATION` with the slot, which fails when the slot is missing (walreceiver.c,
REL_15_STABLE). `follow` accepted the first row showing the writer's address and the slot, so a poll inside that window
confirmed a follow that never started, and `PgFailoverMonitor.followWriter` recorded a `FOLLOW_WRITER` receipt for it.
The confirmation now also requires the receiver's `wait_event` in `pg_stat_activity` to be `WalReceiverMain`, which the
receiver waits on only inside its streaming loop. Probe on PostgreSQL 15.13 (Jenkins host, two containers, 3000
samples each): with no slot, 2998 samples had no receiver row and 2 showed `streaming`, the address, and the slot with no
wait event; after the slot was created, 3000 of 3000 showed `WalReceiverMain`.

| Step | Evidence under `logs/` on the Jenkins host (`/var/lib/jenkins/peegeeq-work`) |
|---|---|
| Test | `aReceiverObservedBeforeItsReplicationStartsIsNotReportedAsFollowed`, with `ReceiverWindowCommandRunner`: the receiver query runs on the real follower, repeated inside one `DO` block until the row shows the writer's address and the slot, and that real result is returned. A short `wal_retrieve_retry_interval` makes the window recur. The test also asserts that at least one observation from inside the window was returned |
| Red | `red2-` and `red3-receiver-window-20261114.log`, and `red4-` with the committed test: on the pre-fix code, `follow` reported the receiver as following. The first attempt (`red-`) failed for a test defect: `CREATE FUNCTION` is refused on a read-only standby |
| Green | `green2-promotion-class-20261114.log`: `PgSqlPromotionControlIntegrationTest` 20 of 20 |
| Module | `green-module-20261114.log`: 19 classes, 296 tests, 1 failure, the new test's earlier assertion that the last observation lies inside the window, which the committed test replaced with the count above |
| Core | `core-pg-failover-20261114.log`: 11 classes, 410 tests, 0 failures |
| Guards | `guards2-20261114.log`, after the last test edit: 77 tests, 0 failures |

**Vert.x test-context timeouts, 2026-11-14 (defect from build #37).** The integration classes declare a JUnit class
timeout of 120 to 300 seconds, but the Vert.x extension reads only `io.vertx.junit5.Timeout` (method, then class;
checked in the 5.1.10 bytecode; the annotation is not inherited), so every method without its own annotation had 30
seconds. `aStoppedServerFailsTheInstall` runs a forced stop with a 20-second `pg_ctl` wait and an install with a
20-second budget, and timed out at 30.58 seconds. Each of the 18 classes that declare a JUnit class timeout, and the
two Consul subclasses of the abstract contracts, now declares `io.vertx.junit5.Timeout` equal to its JUnit limit (20
classes). In the module run above every class passed with these limits, apart from the one failure named there.

### 9.35 Phase 7b.3: PostgreSQL Parameters in the Coordinator (Owner Answer 12), 2026-11-18 (design)

The rest of owner answer 12 (§9.28, row 12; §9.20 row 15). §9.34, "Not decided here", leaves PostgreSQL parameters in
the coordinator (Patroni's `postgresql.parameters`) out of its own design, and §9.33's data model row "Configuration
content" names them as the later item of answer 12. This section is their design. §9.28 row 12's scope boundary
applies: identity, membership, endpoints, the coordinator selection, and credentials stay in deployment configuration.

**Purpose.** An operator sets PostgreSQL parameters for the whole cluster in one place, under a revision check. Each
supervisor writes them into a file it owns, reloads, and reports the parameters that wait for a restart. Success: a
parameter that a contract of this design sets or depends on is never taken from the record; a value that PostgreSQL
rejects never stays in the file; a value that another file overrides is reported, not hidden; the order in which the
members restart never keeps a standby from starting because of a hot-standby parameter.

**Sources read, 2026-11-18.** Patroni v4.1.5 source: `patroni/postgresql/config.py` (`CMDLINE_OPTIONS`,
`write_postgresql_conf`, `reload_config`, `get_server_parameters`, `effective_configuration`, `_sanitize_auto_conf`),
`patroni/config.py` (`_process_postgresql_parameters`, `_safe_copy_dynamic_configuration`,
`_build_effective_configuration`, `set_dynamic_configuration`), `patroni/postgresql/__init__.py`
(`handle_parameter_change`, the start through `effective_configuration`), `patroni/ha.py` (the call of
`handle_parameter_change`), and `patroni/__main__.py` (`_run_cycle`, `reload_config`). This plan's §9.20, §9.22,
§9.24, §9.28, §9.32, §9.33, and §9.34; system design §1.1 and §5.4; coordinator design §1.1, §3.3, §6, and §7. The code
of `PgConfigurationControl`, `PgSqlConfigurationControl`, `PgConfigurationFiles`, `PgServerSettings`,
`PgProcessControl`, `PgCtlProcessControl`, `PgDataDirectory`, `PgCommandRunner`, and `PgHbaAdmissionGate`, and the
`ALTER SYSTEM` sites of `PgSqlDurabilityControl` and `PgSqlPromotionControl`.

Facts from those sources that the design rests on:

1. Patroni writes its own `postgresql.conf`. It renames the deployment's file to `postgresql.base.conf` and writes an
   `include` of it as the first line, then the parameters. Later lines win. It removes recovery parameters from
   `postgresql.auto.conf`.
2. Patroni lists fifteen parameters (`CMDLINE_OPTIONS`, among them `max_connections`, `wal_level`, `hot_standby`,
   `max_wal_senders`, `max_replication_slots`, `wal_log_hints`, `listen_addresses`, `port`) and passes them, except
   `wal_keep_segments` and `wal_keep_size`, on the command line, so that `ALTER SYSTEM` cannot change them. It drops
   them from the local configuration, and validates them in the dynamic configuration; a value that fails is replaced
   by the default, with a WARNING. `listen_addresses`, `port`, and
   `cluster_name` come from local configuration, and `hot_standby` is always on.
3. Patroni sets `synchronous_standby_names` itself when synchronous mode is on, and keeps `pg_hba` and `pg_ident` as
   separate settings.
4. Patroni applies a changed dynamic configuration on its next loop: it compares each parameter with `pg_settings`,
   rewrites the file, reloads, and reads `pg_settings.pending_restart` afterwards. A name that `pg_settings` does not
   know is removed from the parameters with a WARNING, except custom names with a dot.
5. On a replica, Patroni starts PostgreSQL with the higher of the configured value and the control file's value for
   `max_connections`, `max_prepared_transactions`, `max_locks_per_transaction`, `max_worker_processes`, and
   `max_wal_senders` (`effective_configuration`), and flags a pending restart. In its replica path, on PostgreSQL 14
   and later, it resumes a paused replay (`handle_parameter_change`).

**Checks run, 2026-11-18,** on throwaway `postgres:15.13-alpine3.20` containers (PostgreSQL 15.13) on the owner's
workstation with local Docker 29.9.3 (the Jenkins host did not answer within 8 seconds at 17:42 local time). Logs under
`logs/`: `parameters-probe-20261118.log` (checks 1 to 6, 8, 9; its check 7 is void: `psql` sets its own
`application_name`), `-check7d-` (check 7; `-check7-` to `-check7c-` are void: the shell or the tool input dropped a
backslash), `-check10-`, `-check11-`, `-check12-` (check 12a; its 12b is void: the standby's name did not match), and
`-check12b-`. The containers were removed.

1. `include_if_exists 'peegeeq-cluster.conf'` as the last line of `postgresql.conf`, with no such file: the server
   started, and logged "skipping missing configuration file" each time the files were read.
2. `work_mem = '8MB'` in that file, then a reload: in effect, `pg_settings.sourcefile` is the file, and its
   `pg_file_settings` row has `applied = true`.
3. Then `ALTER SYSTEM SET work_mem = '16MB'` and a reload: `16MB` in effect; the file's row has `applied = false` and no
   error; the `postgresql.auto.conf` row has `applied = true`.
4. `max_connections = '150'` in the file, then a reload: `100` in effect, `pending_restart = true`; the file's row has
   `applied = false` and error "setting could not be applied".
5. `checkpoint_timeout = 'nonsense'` in the file: the same error, before and after the reload; after it,
   `pending_restart = false`. This is §9.33 check 8 for this file.
6. `no_such_parameter = '1'` in the file: error "unrecognized configuration parameter"; a reload left the load time
   unchanged and applied nothing of the file (`work_mem` stayed `8MB`). This is §9.33 check 6. A custom name
   `peegeeq.probe = 'x'` was accepted and applied.
7. The line `log_line_prefix = 'it''s a\\b "q" '`, written by decoding base64 inside the container and renamed into
   place, was read back byte for byte. In effect: `it's a\b "q" ` (13 characters). A doubled single quote is one quote,
   a doubled backslash is one backslash, and a double quote is literal. A single backslash is an escape: `\b` became one
   character.
8. A standby whose file says `max_connections = '100'`, base-backed-up from a writer running `150`: the start failed,
   `FATAL: recovery aborted because of insufficient parameter settings`, `DETAIL: max_connections = 100 is a lower
   setting than on the primary server, where its value was 150`.
9. The same standby with `150`: it started and streamed.
10. The standby's `pg_controldata` shows `max_connections setting: 150` after the base backup. The writer lowered the
    value to `120` and restarted: the running standby (`150`) kept streaming, and its control file showed `120`. The
    writer raised it to `200` and restarted: the running standby (`150`) stayed running in recovery with replay paused,
    `WARNING: hot standby is not possible because of insufficient parameter settings`, `LOG: recovery has paused`,
    `DETAIL: If recovery is unpaused, the server will shut down`; its control file showed `200`.
11. Resuming replay on that paused standby shut it down (`FATAL: recovery aborted ...`). `postgres -D <dir> -C
    max_connections` printed `150`, the value of its files, both while stopped and while running. `pg_controldata`
    showed `200`. Started with `-o "-c max_connections=200"`, it streamed with replay not paused; `pg_settings` showed
    `200`, source `command line`, `pending_restart = false`; the file's row (`150`) had `applied = false` and error
    "setting could not be applied".
12. While a standby was paused as in check 10: it kept receiving and flushing WAL (receive position equal to the
    writer's flush position, replay position unchanged, receiver `streaming`); as the writer's only synchronous standby
    it stayed `sync`, and a commit with `synchronous_commit = on` succeeded. `postgres -C max_connections` with
    `max_connections = 'nonsense'` in a file exited 1 with `FATAL: configuration file ... contains errors`.

So check 11's start option makes §9.33 contract 1's error rule report an invalid value: an error entry whose parameter
is not pending a restart. Contract 5 below narrows that rule.

**Data model.** One stored record is added: the cluster parameters.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Cluster parameters: PostgreSQL parameter name to value | Coordinator, one record per incarnation, `peegeeq/pg/<clusterId>/<incarnation>/postgresql`; authoritative | Names and values satisfy contract 1 when written and when read; a denied name, an invalid name, or a value that is not a string fails. Never adjusted (as OD-66) |
| Parameters revision | Coordinator metadata (Consul `ModifyIndex`) | Guards the edit. Not stored in the value |
| Managed file `peegeeq-cluster.conf` in the data directory | Derived: the rendering of the record (contract 1); written only by the supervisor | Replaced whole, never edited in place. No revision or time is written into it. Saved and restored with the other top-level `*.conf` files by a re-join (§9.32) |
| Include line `include_if_exists 'peegeeq-cluster.conf'` | Deployment: the last line of each node's `postgresql.conf` | Without it no entry of the file takes effect, and an apply with parameters fails (contract 4). A missing file is skipped (check 1) |
| Whether a node applied the record | Derived live: the file's content against the record's rendering; the file's `pg_file_settings` rows; `pg_settings.pending_restart` | Never stored |
| The writer's hot-standby values as a standby last replayed them: `max_connections`, `max_worker_processes`, `max_wal_senders`, `max_prepared_transactions`, `max_locks_per_transaction` | Derived live: the standby's `pg_controldata` (check 10) | Read before every start in recovery (contract 5) |
| The value a start would read from the files | Derived live: `postgres -D <dir> -C <name>` (check 11) | Read before every start in recovery |
| Start option `-c <name>=<value>` | Derived per start: the control-file value, for each of the five parameters whose control-file value is higher than the file's | Never stored. Observed afterwards as `pg_settings.source = 'command line'` |
| Parameters the supervisor sets itself: `synchronous_standby_names`, `primary_conninfo`, `primary_slot_name` | `postgresql.auto.conf` through `ALTER SYSTEM` (existing: OD-9, §9.22) | Denied in the record (contract 1) |

**Contracts.**

1. `PgClusterParameters` (record): a map of name to value, kept in name order.
   - A name is lower case: `[a-z_][a-z0-9_]*`, or a custom name `[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)+`. A value is a
     string with no carriage return, line feed, or NUL.
   - Denied names, each because a contract of this design sets it, requires its value, or reads it from deployment
     configuration:
     - `synchronous_standby_names` (policy installation, OD-9);
     - `primary_conninfo` and `primary_slot_name` (follow, §9.22);
     - `synchronous_commit` and `fsync` (R-17; coordinator design §6);
     - `wal_level`, `wal_log_hints`, and `full_page_writes` (replication and rewind, §9.32; coordinator design §6);
     - `hot_standby` (the supervisor's SQL on a standby);
     - `max_slot_wal_keep_size` (deployment and observed, OD-27, §9.24);
     - `listen_addresses`, `port`, and `unix_socket_directories` (node endpoints and the supervisor's local socket,
       §9.28 row 12);
     - `data_directory`, `config_file`, `hba_file`, `ident_file`, and `external_pid_file` (file locations; the admission
       gate owns `pg_hba.conf`, §9.33);
     - `include`, `include_if_exists`, and `include_dir` (file directives);
     - `recovery_min_apply_delay`, the eight parameters of PostgreSQL 15's "Recovery Target" category
       (`recovery_target`, `recovery_target_action`, `recovery_target_inclusive`, `recovery_target_lsn`,
       `recovery_target_name`, `recovery_target_time`, `recovery_target_timeline`, `recovery_target_xid`), and
       `promote_trigger_file` (re-join, follow, and the lease; OD-76).
   - A name is checked part by part between the dots, not by one expression that repeats a group: in Java such an
     expression recurses once per repetition, and a stored name of 100 001 parts ended in a `StackOverflowError`
     (PP1).
   - `toJson` and `fromJson`: exactly the field `parameters`, an object of string values. An unknown or missing field,
     a value that is not a string, or a name or value that breaks the rules above fails.
   - `render()`: the line `# PeeGeeQ cluster-wide PostgreSQL settings, written by the PeeGeeQ supervisor. Do not
     edit: the supervisor replaces this file.` (changed in PP1 from a line that said "coordinator record", which a
     person reading the file on a server cannot interpret),
     then one line per parameter in name order, `name = 'value'`, with each single quote and each backslash in the
     value doubled (check 7).
2. `PgClusterConfiguration` gains `readParameters()`, `createParameters(parameters)`, and
   `updateParameters(expected, parameters)`, returning `PgClusterParametersRecord(revision, parameters)`, with the rules
   of §9.34 contract 3: an authoritative read; a create that fails when a record exists; an update that fails and
   changes nothing when the record moved; an unanswered request fails within the request deadline; every failure is a
   `PgClusterConfigurationException`. `PgClusterConfigurationContract` gains the cases, and
   `ConsulClusterConfiguration` binds them to the second key with the wire rules of §9.34 contract 4 (`?consistent`,
   `?cas=0`, `?cas=<revision>`, reply `true`, read back).
3. `PgConfigurationControl` gains the managed file:
   - `managedFile()`: the file's content, or empty when it does not exist.
   - `writeManagedFile(content)`: writes through the runner as
     `sh -c 'printf %s $1 | base64 -d > $2.tmp && mv -f $2.tmp $2' sh <content as base64> <path>`, then reads the file
     back and requires it equal to `content`. No argument contains a double quote, because Windows drops it from an
     argument (`PgConfigurationFiles`). The adapter refuses a data directory path outside `[A-Za-z0-9_./-]+` when it is
     constructed, because the path is unquoted.
   - `removeManagedFile()`: removes it and confirms it is gone.
   - `managedEntries()`: for each `pg_file_settings` row of the file, the name, `applied`, the error, and the
     parameter's `pending_restart` and `source` from `pg_settings`.
   Every failure is a `PgConfigurationException`, as in §9.33 contract 1.
4. `PgFailoverMonitor.applyParameters(parameters)`. Serialized. No lease and no receipt: the effect is observable (the
   file, the load time), as OD-28 and OD-34 decide for slot steps and §9.33 contract 2 for reload. Allowed under
   quarantine, as reload is (OD-61). Refused before any effect while PostgreSQL is stopped
   (`PgProcessControlException`), because the file cannot be checked without the server. Then:
   1. Render the record. When the file already holds the rendering, go to step 4.
   2. Keep the current content, or its absence. Write the rendering.
   3. Reload through §9.33 contract 1. A refusal for a whole-file error (check 6) or a reported invalid value (check 5)
      restores the kept content, or removes the file when there was none, and fails the call. After an invalid value
      the restore is followed by a reload; a failure of the restore or of that reload is attached to the failure.
   4. The record has parameters but the file has no `pg_file_settings` row: fail, "the include line is missing".
   5. Check every row of the file: `applied`, or its parameter pending a restart, or its parameter set on the command
      line (contract 5). Any other row fails the call and names it: another file overrides the value (check 3). The
      file is kept: its values are valid, and the failure shows the conflict.
   6. Return `PgParametersApplied(changed, pendingRestart)`: whether the file changed, and the names pending a restart.
5. Every start in recovery (`PgCtlProcessControl.startInRecovery` and the crash-recovery start of a rewind) reads the
   five control-file values and, through `postgres -C`, the five file values, and passes `-c <name>=<value>` for each
   parameter whose control-file value is higher (checks 8, 10, 11; fact 5). A failed or malformed read fails the start
   before `pg_ctl start`; an invalid value in a file fails `postgres -C` (check 12). `PgConfigurationFiles`'s `invalid`
   list leaves out a parameter whose `pg_settings.source` is `command line`: its file value cannot take effect until a
   start without the option, and that start's `postgres -C` reads it first.

**Danger, and the safety net.** One record edit reaches every member at its next apply: the blast radius is the
cluster. An invalid value would stop the next start of every member (§9.33 check 8); the apply reloads first and
restores the previous file on a rejection (contract 4.3), so a member keeps such a value only when its apply is
interrupted between the write and the restore. A raised hot-standby parameter that takes effect on the writer first,
at the writer's restart, pauses the replay of every standby that runs with a lower value (check 10); the
standby still flushes WAL and still counts as a synchronous standby (check 12), so commits succeed while its replay
stops. Whether a promotion of such a standby succeeds was not checked. A restart of that standby starts it with the
writer's value (contract 5), and a restart of every standby before the writer avoids the pause. Every parameter whose
value a contract of this design sets or requires is denied (contract 1). A parameter that bounds a resource, such as
`max_replication_slots` or `max_wal_senders`, is allowed; the effect of a value too low on the operations that need
it was not checked. Other parameters can still stop service, for example `default_transaction_read_only = on`.
Access control limits who edits: the record has its own key and its own write tokens, so a token that edits parameters
cannot change the lease timing.

**Conflict found, 2026-11-18.** Coordinator design §7 says "Use the single configuration contract in system design §5.4.
No second setting family." The record holds PostgreSQL's own parameters, not supervisor settings. PP2 adds the record
to system design §1.1 and §5.4, so that the single contract names it.

**Decisions of the implementer, 2026-11-18, under the owner's direction of 2026-11-13 to proceed without questions.
Reversible.**

- OD-69: the parameters are a second record under their own key, `peegeeq/pg/<clusterId>/<incarnation>/postgresql`, in
  the same port. Reason: the lease timing of §9.34 keeps its contracts and tests unchanged, the two records are applied
  by different steps (an acquisition and an apply), and separate keys let access control separate who may change the
  lease timing from who may tune PostgreSQL. Patroni keeps both in one key (§9.34 fact 1); that is a difference: an
  edit of both is two edits.
- OD-70: the parameters reach PostgreSQL through the managed file and a deployment include line, not `ALTER SYSTEM`.
  Reason: `postgresql.auto.conf` holds the supervisor's own three settings and any operator `ALTER SYSTEM`; a file that
  the supervisor replaces whole removes a parameter by leaving it out, can be compared with the record, and is not
  edited by `ALTER SYSTEM`. Patroni also writes its own file (fact 1); unlike Patroni, the supervisor does not rename or
  rewrite the deployment's `postgresql.conf`.
- OD-71: the denied names of contract 1 are refused, not replaced by defaults. Reason: OD-66; Patroni replaces a value
  that fails validation (fact 2), which changes a setting nobody asked for.
- OD-72: names are lower case and values are strings. Reason: PostgreSQL names are not case sensitive, so two keys that
  differ only in case would be one parameter; a string value renders one way.
- OD-73: no record means no managed parameters, and nothing is written; the caller does not apply. A record with no
  parameters renders a file with only its first line. Bootstrap writes no parameters record. Reason: the deployment's
  files then apply unchanged, as they do today; a record that disappears does not change a running node's
  configuration.
- OD-74: an overridden value fails the apply and keeps the file (contract 4.5). Reason: the value is valid; restoring
  the old file would hide which file overrides it, and the failure names it.
- OD-75: a start in recovery uses the control-file value when it is higher, as Patroni does (fact 5), and does not
  refuse. Reason: check 8 shows that a standby cannot start otherwise, so a refusal would leave the standby down after a
  writer restart. The option is visible as source `command line`. The resume of a paused replay (fact 5) is not
  adopted here: it shuts the standby down (check 11), and only a caller that then starts it, the supervisor loop, may
  do it.
- OD-76, 2026-11-21 (PP1, review finding 2): deny `recovery_min_apply_delay`, the eight recovery target parameters,
  and `promote_trigger_file`. Reason: the purpose of this design requires it, because contracts of this design depend
  on their values. `rewindOrRebuild` treats a re-join that does not reach `confirmCaughtUp` as failed and rebuilds.
  `follow` (§9.22) and the re-join need a standby that follows the new timeline. Only the lease holder may promote:
  PostgreSQL documents that a standby leaves recovery when the file `promote_trigger_file` names exists, so a record
  that names an existing file would promote a standby without the lease. The list is the "Recovery Target" category of
  `pg_settings` on PostgreSQL 15.13 (`logs/parameters-probe-recovery-names-20261121.log`). The denial is by exact name:
  `recovery_prefetch` and `recovery_init_sync_method` stay allowed. Static reading: none of the three effects was
  reproduced.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Mode | Test class |
|---|---|---|
| Parameters value | each denied name; upper case, empty, and malformed names; a custom name; a value with a line feed, a carriage return, or NUL; a non-string value; unknown or missing field; round trip; rendering of a quote and a backslash | `PgClusterParametersTest` (core) |
| Settings port, parameters, every adapter | absent record; create and read back; second create refused; update at the read revision; update after another edit refused and nothing changed; malformed stored value; stored denied name; unanswered request; unreachable service | `PgClusterConfigurationContract` through its Consul binding |
| Consul wire, parameters key | read without a known leader; read filtered by ACLs; write answered `false`; token without write on the parameters key, and the timing token on it | `ConsulClusterConfigurationIntegrationTest` |
| Managed file through the runner | absent; written and read back; a quote, a backslash, and a double quote in a value; read back different; command failure; no result; data directory path with a space refused at construction; entries of an applied, an overridden, a pending, and an invalid row | `PgSqlConfigurationControlIntegrationTest` |
| Apply | reloadable value in effect; postmaster value pending; unchanged file not rewritten; parameter removed; unknown parameter restored and refused; invalid value restored, reloaded, and refused; missing include line; overridden by `ALTER SYSTEM`; stopped server; quarantine | `PgParameterApplyIntegrationTest` (new) |
| Start in recovery | control-file value higher: started with the option, streaming; equal: no option; invalid file value: start refused before `pg_ctl start`; `pg_controldata` failure; the start option's row is not an invalid value | `PgCtlProcessControlIntegrationTest`, `PgConfigurationFilesIntegrationTest` |

**Order of work.** PP1: `PgClusterParameters` (contract 1, core tests). PP2: the port's parameters operations, the
contract cases, and the Consul binding (contract 2). PP3: the managed file in `PgConfigurationControl` (contract 3).
PP4: `applyParameters` (contract 4). PP5: the start in recovery and the narrowed `invalid` list (contract 5). Each:
tests and stubs that fail with "not implemented", red, implementation, green, mutation probes with SHA-256 records, the
touched classes, core, and guards last. The design documents change in PP2 and PP5: system design §1.1 (a row for the
cluster parameters) and §5.4, coordinator design §1.1 and §3.3.

**Review findings, 2026-11-20.** Found by a review of this section before PP1. Finding 2 concerns contract 1's
denied names, so PP1 decides it. Finding 1 concerns contract 4, so PP4 decides it.

1. `pg_file_settings.applied` describes the files as they are now, not what PostgreSQL applied. Run on a throwaway
   `postgres:15.13-alpine3.20` container on the owner's workstation (local Docker 29.9.3), log
   `logs/parameters-probe-filesettings-20261120.log`: with `work_mem = '8MB'` written to `peegeeq-cluster.conf` and no
   reload, the file's row had `applied = t` and no error, while `work_mem` in effect was `4MB` (`pg_settings.source`
   `default`) and the load time had not changed. After a reload, `pg_settings` showed `8192` with source
   `configuration file` and the managed file as `sourcefile`. Consequence for contract 4: an apply interrupted after
   step 2's write and before step 3's reload leaves a file that holds the rendering. The next apply takes step 1's
   shortcut, skips the reload, passes step 5 on `applied`, and returns success with none of the values in effect.
   Recommendation: the shortcut skips only the write, and the reload still runs. Step 5 confirms each entry through
   `pg_settings`: `sourcefile` is the managed file, or `pending_restart`, or source `command line`. Patroni compares
   with `pg_settings` (fact 4). `sourcefile` is visible only to a superuser or a member of `pg_read_all_settings`. The
   supervisor's local role must have one of them, and PP4 must check this.
2. Static reading, not reproduced: contract 1 allows parameters that change replay on a standby.
   `PgFailoverMonitor.rewindOrRebuild` treats a rewind that does not end in `confirmCaughtUp` as failed, and rebuilds,
   which removes the data directory. If `recovery_min_apply_delay` is longer than the re-join budget, every re-join
   becomes a rebuild, and the rebuild then fails the same check. `recovery_target_timeline` other than `latest` stops
   a standby from following the new timeline after a promotion, which `follow` (§9.22) and the re-join depend on.
   Whether the other recovery target parameters (`recovery_target`, `_lsn`, `_name`, `_time`, `_xid`, `_action`,
   `_inclusive`) stop a standby's replay in PostgreSQL 15 was not checked. Recommendation: deny
   `recovery_min_apply_delay` and the `recovery_target*` family in contract 1. Contract 1's purpose already requires
   this: a contract of this design depends on their values. Decided in PP1 as OD-76, which also denies
   `promote_trigger_file`.

**Not decided here.** Who reads the record and calls `applyParameters`, and when (the supervisor loop), including the
apply before a start. The resume of a paused replay and the start that follows it (the supervisor loop). How an
operator reaches `updateParameters` (answer 13). Restarting every member in turn, standbys before the writer
(§9.33, "Not decided here"). `pg_hba` and `pg_ident` content in the coordinator, which Patroni has (fact 3): the
admission gate owns `pg_hba.conf`.

**Danger found in PP1, not decided.** Contract 1 allows parameters whose value PostgreSQL runs as a shell command or
loads as code: `archive_command`, `restore_command`, `archive_cleanup_command`, `recovery_end_command`,
`ssl_passphrase_command`, `shared_preload_libraries`, `session_preload_libraries`, `local_preload_libraries`, and
`dynamic_library_path`. A token that may write the parameters key can then run a command as the PostgreSQL operating
system user on every member, at the next apply or the next start. Patroni allows them, and archiving needs
`archive_command`. Static reading: not reproduced. Whether the record allows them is a product decision for the owner;
until it is made, the write token for the parameters key carries the same trust as a shell on every node.

**PP1 status, 2026-11-21.** Built: `PgClusterParameters` (contract 1, OD-76) and its core test
`PgClusterParametersTest` (16 cases). Nothing outside tests uses it. Owner's workstation, local Docker 29.9.3 used only
for the name list of OD-76. Logs under `logs/`:

| Step | Evidence |
|---|---|
| Red, against the stub | `rebuild-peegeeq-pg-failover-pp1-stub-20261121.log` BUILD SUCCESS; `peegeeq-pg-failover-pp1-red-20261121.log`: 15 run, 8 failures, 4 errors, each a missing check or "not implemented". `parametersNextToTheDeniedNamesAreAllowed`, `aCustomNameWithADotIsAllowed`, and `anEmptyValueAndOtherCharactersAreAllowed` passed on the stub, which accepts everything; probes 147, 146, and 148 are their red |
| Green | `rebuild-...-pp1-impl-`, `-pp1-green-`: 15 of 15 |
| Long name | An IDE warning (S5998) on the name expression `[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*`. Test `aNameWithManyPartsIsCheckedWithoutAStackOverflow` added: `-pp1-longname-red-`, 16 run, 1 error, `java.lang.StackOverflowError` in `java.util.regex.Pattern$Loop`. The check now matches each part between the dots: `rebuild-...-pp1-impl2-`, `-pp1-green2-`: 16 of 16 |
| Probes 132 to 137 | On the first version, SHA-256 `034777CA...03D17FA0` (`-pp1-mutation-sha-before-`). The first run of 132 used a class the IDE had compiled into `target/` (Maven printed "Nothing to compile"); it was repeated with `clean`, and every later probe ran `mvn clean test`. 132 the name check, 133 the denied check, 134 `promote_trigger_file` dropped from the list, 135 the value check, 136 the NUL clause alone, 137 the sorted copy: each guarding test failed with the message of its own behaviour |
| Probes 138 to 149 | On the revised version, SHA-256 `2B89B3EF...CA33246B` before (`-pp1-mutation-sha-before2-`) and after (`-pp1-mutation-sha-after-`). 138 the part check, 139 the empty last part (`work_mem.`), 140 the exact field set, 141 the object check, 142 the string check, 143 the backslash doubling, 144 the quote doubling, 145 the header line, 146 names with a dot refused, 147 denial by the prefix `recovery`, 148 empty value and tab refused, 149 the unmodifiable wrapper: each guarding test failed with the message of its own behaviour. Logs `-pp1-mutation132-` to `-149-20261121.log` |
| Core | `rebuild-...-pp1-final-` BUILD SUCCESS; `-pp1-core-`: 13 classes, 452, 0 failures (436 plus 16) |
| Guards | `peegeeq-test-support-guards-pp1-20261121.log`: 14 classes, 77, 0 failures; again after these document edits, `-guards-pp1-docs-`: 14 classes, 77, 0 failures |

The banned-pattern grep of `.claude/CLAUDE.md` step 5 over the two new files found no match. Probes 133 to 137 ran on
the first version; the code they changed is the same in the revised version, apart from the constant `PARAMETERS`
renamed `FIELD`. Not run: any integration class, because nothing outside the new test uses the value.

After the probes, the comments of both files were corrected, and no code changed. They had said that the coordinator
stores the record and that each supervisor renders it into its managed file. No code does either: storage is PP2, the
file is PP3. The production file's SHA-256 therefore differs from the probe record. `rebuild-...-pp1-javadoc-`
BUILD SUCCESS; `-pp1-javadoc-`: 16 of 16.
