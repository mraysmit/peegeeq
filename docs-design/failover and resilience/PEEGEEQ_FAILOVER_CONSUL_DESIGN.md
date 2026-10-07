# PostgreSQL Automatic Failover Using Consul — Design

**Author**: Mark A Ray-Smith Cityline Ltd.
**Document type**: Target design
**Design revision**: 2026-10-07, coordination, bootstrap, and recovery prerequisites revision
**Module**: Proposed `peegeeq-pg-failover`

## 1. Purpose and Scope

This document defines the control mechanism for automatic PostgreSQL failover in PeeGeeQ.
It is for developers implementing the proposed `peegeeq-pg-failover` module and operators
selecting its deployment dependencies. Read it to understand who can decide a transition,
how that decision reaches PostgreSQL, and how an interrupted transition is resumed safely.
It describes target contracts. It does not report current implementation or test coverage.

Consul is the reference automatic protocol. G-7 records its retention or a qualified replacement
before automatic implementation. The local Java Qraft service is an option for that decision,
with qualification requirements in §3.1. This document's Consul operations apply only to the
reference backend; a replacement requires a consistent replacement of those contracts.

**Why a controller is required.** In this design, one PostgreSQL primary accepts application
writes and two physical standbys follow its write-ahead log (WAL), the record of database
changes. Promotion changes a standby into a writable primary. A proxy can direct connections,
but the design also needs an owner for promotion decisions and a mechanism that prevents the
old primary from continuing to accept writes. A failed SQL probe creates suspicion. It cannot
establish whether the database is stopped or isolated by a network partition.

**The division of responsibility.** Consul is the coordination service. Its session and
cluster-specific key identify the controller currently authorised to update transition intent.
`PgPrimaryElector` maintains that ownership. `PgFailoverMonitor` observes configured database
nodes and coordinates the transition. An independent node-control provider performs guarded
stop, promotion, configuration, and restart operations through an access path separate from
application SQL. The sidecars report writer eligibility; HAProxy uses their answers to route
traffic. Neither sidecar checks nor Consul ownership replace a confirmed fence.

A **fence** is confirmed database stop with restart inhibited. **Writer admission** is the
provider-enforced permission for a particular node to accept application writes. A **control
generation** identifies one ownership period. The provider must reject new commands from retired
generations and reconcile already accepted effects before another writer can be admitted.
**Reconciliation** means inspecting current intent, provider receipts, and database state after
restart or an uncertain reply, then completing only a transition still authorised by that state.

The synchronous durability policy names the standbys whose WAL flush acknowledgements are
required before a commit is confirmed. Its last confirmed set determines eligible promotion
targets. A newly joining standby is excluded until the policy change is validated.

**The expected transition.** Automatic profile A follows this sequence:

1. Establish current Consul ownership and begin the provider generation. Retire previous
   command admission and reconcile unfinished effects before issuing new writer permission.
2. Persist withdrawn intent, revoke writer admission, and confirm stop and restart inhibition
   for the former or ambiguous writers. Keep admission closed when confirmation is unavailable.
3. Validate a target covered by the last confirmed synchronous durability policy. Promote it
   under the guarded provider boundary and establish its required synchronous peer coverage.
4. Confirm policy intent, prepare the writer grant, publish serving intent, and activate the
   matching grant. Sidecars can report eligibility only when permission, authority, and live
   database checks agree.
5. Keep the former writer quarantined. Rewind or rebuild it and validate standby re-join before
   restoring replication membership through a separate policy change.

For example, when node 1 fails, node 2 can become the writer only after node 1 is fenced and
node 3 supplies the required synchronous coverage. A timed-out promotion reply does not
justify issuing an unrelated promotion. Inspect the original action and actual role first.
When only the controller fails, its successor reconciles the healthy writer; controller death
alone does not require database promotion. Loss of Consul authority stops automatic mutations
and denies unverified traffic. Safety permits a period with no admitted writer.

**Scope and dependencies.** This document covers ownership, guarded Consul updates, generation
retirement, fencing, writer grants, promotion, durability changes, restart reconciliation,
standby re-join, configuration budgets, and dependency-failure acceptance. Manual profile B
uses operator-directed transitions and provider-issued generations under the same node-control
contracts. Select the production provider and admission integration before implementing either
profile. A local Docker test adapter does not qualify a production platform.

PeeGeeQ federation, routing between application instances, backups, and point-in-time recovery
are outside this scope. Application pool recovery and durable LISTEN catch-up are defined in
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md). The controller does not make
interrupted transactions safe to retry or provide exactly-once external effects.

**Reading guide.** Start with the field sources and invariants in §1.1. Section 3 defines
Consul ownership rules. Section 4 defines the provider operations and their interruption cases.
Sections 5 and 6 explain transition order, durability, and re-join. Section 8 defines acceptance.
Use [the sidecar guide](PEEGEEQ_PG_SIDECAR.md) for eligibility reporting and
[the implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md)
for execution order and deployment qualification gates.

### 1.1 Data Model and Contracts

The canonical field contracts are in
[the system design §1.1](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#11-data-model-and-safety-contracts).
This document defines automatic profile A. It does not report implementation state.

| Data | Source of truth or derivable | Contract |
|---|---|---|
| Cluster ID, incarnation, node membership, and endpoint configuration | Authoritative deployment configuration | One coordination namespace per replicated cluster and incarnation. Membership is fixed during transitions. |
| Control key | Authoritative Consul record | `peegeeq/pg/<clusterId>/<incarnation>/primary-lock`. No global key shared by unrelated clusters. |
| Session and lock generation | Authoritative Consul metadata | Session identifies the controller. Key, `LockIndex`, and session form the control generation. `ModifyIndex` identifies a value revision. |
| `writerNodeId`, `phase`, `operationId`, `previousWriterNodeId` | Authoritative transition intent in the lock value | Writer ID names a database node. Current `SERVING` intent also requires a matching open provider grant and live checks. Persist intent before effects. Previous writer is absent only in verified first-start bootstrap. |
| Bootstrap provisioning evidence and immutable request parameters | Authoritative deployment evidence referenced in existing provider receipts | Bind cluster, incarnation, membership, and initial writer. Missing history is not evidence. Reuse pending/confirmed policy and grants; compute bootstrap completion from existing records. |
| Action receipts, accepted cluster generation, installed node generations, and quarantine | Authoritative durable node-control provider state | Outside application replication. Survives restart. Cluster serialisation and installed node guards reject obsolete effects. |
| Writer grant: mode, generation, operation, policy revision, node, and `CLOSED`/`PREPARED`/`OPEN` state | Authoritative provider execution permission | Independent permission combined with matching current authority. A prepared grant never serves. |
| Confirmed and pending durability policy revisions and required standby sets | Authoritative profile transition intent | Consul owns automatic intent. Provider owns manual intent. Count and live replication health are derived. Pending membership grants no promotion eligibility. |
| Role, timeline, WAL positions, failure counters, and readiness | Derived observations | No stored primary or healthy flag acts as authority. |
| Credentials | Deployment secret store | Separate observation, Consul mutation, and node-control privileges. No plaintext secret in the control record. |

At most one node accepts PeeGeeQ writes. Zero is permitted during recovery. A replacement
cannot serve until former or ambiguous writers are confirmed stopped with restart inhibited.
Validated standbys can remain running with enforced promotion and writer-start inhibition.
A lost connection or expired controller session does not prove database failure.

## 2. Responsibilities and Topology

| Component | Responsibility |
|---|---|
| `PgNodeConfig` | Immutable node identity and direct observation endpoint. Secret references come from deployment configuration. |
| `PgPrimaryElector` | Session renewal, generation ownership, consistent reads, and conditional control-record mutations. |
| `PgFailoverMonitor` | Bounded node observations, failure suspicion, transition orchestration, and restart reconciliation. |
| Independent node-control provider | Generation retirement, fencing, restart inhibition, guarded promotion, policy installation, writer activation/revocation, and standby admission. |
| `peegeeq-pg-sidecar` | Read-only eligibility reporting to HAProxy. It never promotes or releases a fence. |
| HAProxy | Routes using scheduled sidecar observations and closes sessions when configured failures mark a backend down. Provider enforcement and fencing preserve safety during delay. |

The first three components belong to the proposed `peegeeq-pg-failover` module. Database
failover is independent of `peegeeq-service-manager`, which handles PeeGeeQ federation.
Sharing a Consul installation does not imply sharing clients, credentials, or lifecycle.

Applications use the stable redundant proxy endpoint. The monitor observes database nodes
directly. Node control has an independent path that remains usable when database SQL fails.
Both manual and automatic modes require a selected, tested provider and admission integration.
A SQL-only monitor is unsupported.

## 3. Consul Contract

- Use a three-server production cluster with separate failure domains.
- Session TTL defaults to 15 seconds. Renew every TTL / 3. Use lock delay of 10 seconds.
- Lock delay blocks every contender from reacquisition temporarily. It does not fence a node.
- Traffic-granting reads use consistent mode. Missing session means no authority even if the
  released key retains its old value.
- Ownership-sensitive writes use one `/v1/txn` request with `check-session`, `check-index`,
  and `cas` on the same key and expected revision. A condition failure rejects the transaction.
  A lost reply requires a consistent read before retrying the same operation.
- ACLs exclude unauthorised clients and isolate namespaces. They do not require lock ownership
  for an authorised key writer. Trusted controllers must never use unguarded set, deletion,
  or another controller's release. Guard voluntary unlock with session and revision conditions,
  after provider revocation. Normal transitions retain the key and policy history.
- A successor acquires ownership with withdrawn intent. It does not retain an old serving
  phase as new authority. Read the prior value consistently, then use atomic `check-index`
  and `lock` with the new session. Preserve confirmed and pending policy intent and the
  previous writer for reconciliation. A conflicting revision or existing owner rejects
   acquisition. Missing policy history closes admission and requires operator reconciliation.
   An interrupted verified bootstrap retains closed admission and resumes §5.1 using its
   preserved evidence and pending intent; missing history alone never creates that exception.
- Never infer database failure from controller-session loss.
- Expiration can occur later than TTL. Do not derive an exact recovery bound from TTL.
- Coordination-store restoration requires a new incarnation and controlled fencing of all
  nodes. Provider admission is reconciled before control resumes.

Consul locks are advisory. PostgreSQL does not enforce their ownership. Generation enforcement
must exist at the action boundary.

Within a fixed namespace, order generations by `LockIndex` and validate the matching current
session. Do not sort incarnation or session IDs to infer authority. A new incarnation requires
the fenced restoration procedure. Manual profile B uses provider-issued admission generations,
not an absent Consul session interpreted as permission.
[Consul session semantics](https://developer.hashicorp.com/consul/docs/automate/session)
and [transaction conditions](https://developer.hashicorp.com/consul/api-docs/txn) define the
coordination primitives. Consul transactions do not include PostgreSQL or provider effects.

### 3.1 Options Without Consul and the Selection Gate

| Option | Authority | Required decision |
|---|---|---|
| Manual profile B | Provider-owned operator intent and monotonic admission generation | No Consul dependency. Retain fencing, synchronous durability, bootstrap, admission, and re-join qualification. |
| Managed profile C | External operator's writer authority and endpoint | Require equivalent authority, fencing, durability, status, and client recovery evidence. Repointing an address alone is insufficient. |
| Qraft as an external coordinator | Defined replicated ownership and transition protocol | Qualify the local Java API before substitution. Raft consensus does not itself specify controller leases, conditional revisions, or fresh authority reads. |

G-7 must record the selected automatic backend before phase 8. For a Qraft replacement, define
atomic ownership acquisition/renewal/expiry, conditional intent revisions, linearizable reads,
provider generation ordering, restore/incarnation handling, namespace isolation, and authentication.
Test obsolete leaders, minority partitions, conflicting owners, lost replies, snapshots, restart,
and restore with real services. Define a non-blocking PeeGeeQ adapter with bounded requests and
independent service runtime configuration. The plan records the dated local API assessment.

Consul remains the reference until replacement contracts and qualification are complete. Replace
its metadata, sidecar checks, provider ownership validation, configuration, and acceptance mapping
together. One incarnation cannot use two automatic authorities. Switching backend requires stopped,
fenced reconciliation and a new incarnation. Any choice still requires independent node control.

## 4. Node-Control Provider Contract

The provider is a deployment dependency for both profiles. Its interface exposes bounded asynchronous
operations and authoritative reconciliation. Implementation signatures must follow the
project's established Vert.x Future patterns.

| Operation | Preconditions | Required result |
|---|---|---|
| Begin generation | Current profile ownership and persisted withdrawn intent | Durably retire prior command admission. Close old grants. Install node guards or confirm fences. Reconcile all previously accepted effects before returning a completed barrier receipt. |
| Bootstrap initial primary | Authenticated verified first-start provisioning; persisted withdrawn intent and pending revision 1; completed barrier; all nodes stopped with restart inhibited | Permit only the selected provisioned initial primary to start with application admission closed. Serialise the guarded start with generation retirement and other node effects. Persist evidence references and immutable parameters in the existing receipt. Never use this operation to bypass covered-target promotion for an existing cluster. |
| Revoke writer | Current generation and operation; activation may be pending | Close the node's grant and deny new application admission. Cancel unexecuted activation. Observe existing writes until quiescent; unknown outcomes remain unknown. Stop the node if quiescence cannot be established. |
| Fence a node | Current generation and persisted transition intent | Confirm database stopped and restart inhibited. Keep the fence until controlled re-join. Revocation alone is not this result. |
| Inspect cluster | Authenticated observation for the configured cluster and incarnation | Enumerate authoritative grants, installed generations, and pending/completed action receipts across generations. Discover unfinished actions even when a controller lost their IDs. Incomplete enumeration is uncertainty. |
| Inspect an action | Generation, operation ID, action, and target node or cluster | Completed receipt, pending state, rejected generation, or unknown outcome. Never fabricate success. |
| Promote the selected target | Completed generation barrier; current intent; former or ambiguous writers fenced; target covered by confirmed policy | Serialise with stop/start and generation changes. Observe role and replay. Keep application admission closed. Validated standbys remain under promotion inhibition. |
| Install durability policy | Current generation; persisted pending intent; application writes quiescent; required peers validated | Apply and observe effective configuration. Return a receipt bound to policy revision. A lost reply leaves admission closed until inspected. |
| Prepare writer | Current generation barrier; selected writable node; fences and confirmed policy validation complete | Create a `PREPARED` grant bound to node, mode, generation, operation, and policy revision. It grants no traffic. |
| Activate writer | Matching prepared grant; current `SERVING` intent; no conflicting effect; live role and synchronous coverage | Change the matching grant to `OPEN` at the serialised effect boundary. Rejected, interrupted, or uncertain activation never fabricates eligibility. |
| Start restricted standby | Current generation; quarantined node stopped; completed rewind/rebuild and standby configuration | Permit one guarded standby start with promotion and writer admission inhibited. Live re-join validation follows this start. |
| Admit validated standby | Current generation; restricted start; live identity, recovery, timeline, and WAL checks complete | Permit guarded standby restart. Keep writer admission and independent promotion inhibited. Policy cutover is separate. |

The provider persists the accepted cluster generation, installed node guards, grants, and
quarantine. It rejects
obsolete requests at the point of effect. Checking Consul in the controller and later issuing
unguarded SQL does not meet this requirement. The controller has observation credentials,
not unrestricted promotion or start credentials.

A promotion operation may execute `pg_promote()` with the reactive client inside the
enforced action boundary. A fence cannot be implemented by closing a pool or terminating
backend sessions. Those actions permit reconnection.

A command acknowledgement is not evidence that stop completed. Independently observe stopped
state and restart inhibition. A process stop that a supervisor immediately reverses is not a
fence. A provider that cannot reach the node must report unknown or failure.

Mutation idempotency uses the tuple of generation, operation ID, action, and target node or
cluster. Different actions within one transition have separate receipts. Store immutable
request parameters in the receipt and reject reuse of that tuple with changed parameters.
Observation operations do not issue effects. A delayed response cannot change current
authority. Cancellation of a Future does not cancel an already issued external effect.
An effect accepted
under valid ownership can remain in flight after session loss. Keep its target quarantined.
A successor waits for reconciliation, provider serialisation, and any required fence before
admission. A prior lock check does not make SQL and Consul one atomic operation.

### 4.1 Generation Barrier and Admission Order

All commands for one cluster use one durable provider serialisation boundary. A new generation
retires admission of older commands before it installs guards on nodes. A node that has not
received the new guard is not safe merely because another node has a larger counter. The
provider confirms its guard or independently fences it. It waits for or reconciles prior
accepted effects. Unknown state prevents barrier completion and all new writer grants.
There is no provider-success response based solely on queued work.

Automatic command admission validates current consistent Consul ownership and transition
identity. Manual admission validates provider-issued ownership. Consul expiry and an accepted
node effect are not atomic. An accepted effect may finish after expiry while its application
grant remains closed. A successor cannot activate a writer before the new barrier reconciles
that effect. Qualification must interrupt ownership between validation and external execution.

The provider enforces revocation on every allowed application path and observes write
quiescence for policy changes. Closing a grant is not proof of a physical fence. Replacement
promotion still requires confirmed stop and restart inhibition for former or ambiguous writers.
Sidecar health and proxy shutdown are additional session recovery controls.

Admission proceeds in this order:

1. Complete the generation barrier and withdrawn intent. Revoke existing writer admission.
2. Confirm fences, promotion or healthy-writer reconciliation, and policy installation.
3. Confirm the new policy intent and prepare the matching writer grant.
4. Conditionally publish `SERVING` under the live generation.
5. Activate that exact grant. Sidecars require both the open grant and matching serving intent.

Revocation invalidates a prepared grant. Reopening requires new withdrawn intent with a new
operation ID and fresh preparation under current ownership. Provider receipts
contain request identity and result, not independently editable authority. Authentication
separates controller mutation from sidecar and application observation.

In manual mode, authenticated begin-transition allocates the next provider generation and
persists withdrawn intent at the same durable serialisation boundary. Provider operations
conditionally update that intent. Manual `SERVING` publication is a guarded provider mutation;
it is not a missing Consul session or an operator setting interpreted as permission. The
provider retains the same confirmed/pending policy fields used by automatic intent.

### 4.2 Interruption and Reconciliation

| Interrupted point | Required reconciliation |
|---|---|
| Begin generation or revoke reply is lost | Inspect the original action. No new grant until retirement, closure, and quiescence are confirmed. |
| Promotion or policy installation is uncertain | Keep admission closed. Inspect receipt and live state. Recompute validation before preparing. |
| Writer is prepared; serving publication has not completed | It remains closed to applications. Verify current intent before publishing. |
| `SERVING` is published; activation has not completed | Sidecars return 503. Inspect activation and finish only under matching live ownership. |
| Activation completes but its reply is lost | Inspect the grant and current authority. Matching live open permission permits derived eligibility; receipt loss alone does not authorise another writer. |
| Session changes at any step | Successor starts withdrawn and completes a new provider generation barrier. It does not reuse an old open grant. |
| Revoke races with delayed activation | Provider ordering and revoked preparation prevent reopening. A stale activation request is rejected. |
| Provider restarts | Load durable generation, receipts, and grants. Observe live state before reporting eligibility. Missing state requires closure and reconciliation. |

The production adapter must document its host or orchestrator control, authentication,
persistence, generation ordering, restart behaviour, independent reachability, and confirmation
mechanism. No production adapter is selected by this design revision. Implementation of A or B
requires the provider and admission selection first. Release requires proving these contracts
on the selected deployment platform.

## 5. State Machine and Recovery

The following transitions persist intent before issuing effects.

| State | Action | Admission |
|---|---|---|
| Reconciliation | Establish ownership, begin provider generation, retire old effects, and inspect live roles and policy intent | No new authority before completed barrier |
| `WITHDRAWN` | Record operation and target; revoke prior writer and observe closure | Sidecars return 503 |
| `FENCING` | Stop former or ambiguous writers. Keep validated standbys under promotion and writer-start inhibition. Verify completed receipts | Closed |
| `PROMOTING` | Promote a covered target; attach peers; install and verify pending policy; confirm policy intent; prepare writer grant | Closed |
| `SERVING` | Publish writer intent, then activate its matching grant with current role and coverage | Only matching open writer |
| Re-join | Rewind/rebuild; start restricted; validate; admit standby; perform a separate policy cutover | Standby only until policy covers it |

Three consecutive failed probes create failure suspicion. The current controller retains and
renews its control session while orchestrating a normal failover. It does not deliberately
expire its session to trigger promotion.

A new controller first reconciles. If the writer remains healthy, the provider proves there
is no conflicting writable node or pending obsolete effect before authority is restored for
that same writer. No promotion follows solely from controller death.

Loss of Consul or uncertain ownership stops controller mutations and makes automatic sidecars
deny traffic. Existing sessions close when proxy checks fail. No other node is promoted until
fresh ownership and completed fences exist. The system accepts downtime to preserve safety.

Startup with two writable nodes, missing history, or unknown actions requires quarantine and
operator reconciliation. Missing or ambiguous values are failures, not default-primary choices.

### 5.1 First-Start Bootstrap

The canonical sequence and field rules are in
[system design §5.11](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#511-first-start-bootstrap).
Only authenticated verified provisioning with no prior admitted PeeGeeQ writes permits initial
intent creation. An absent key after an existing deployment does not permit bootstrap.

In reference A, use one `/v1/txn` with `check-not-exists` and `lock` on the same key to acquire
it with the new session and withdrawn bootstrap value. The
[Consul transaction API](https://developer.hashicorp.com/consul/api-docs/txn) defines these verbs.
This initial acquisition is the sole creation path without prior
confirmed policy. Preserve its pending revision 1, operation, and evidence references on takeover.
Existing, conflicting, malformed, or ambiguously missing history rejects first-start creation.
Manual B uses guarded provider-owned begin-transition instead.

Complete the generation barrier and confirm every node stopped with restart inhibited. The guarded
bootstrap action starts only the provisioned initial primary with admission closed. Start validated
provisioned standbys under restricted admission, verify authenticated replication and a fresh WAL
boundary, install and confirm the first policy, then prepare, publish, and activate. A primary
process starting is not serving authority. Sidecars return 503 until all serving checks agree.

Inspect the original action after a lost reply. Restart or ownership change retires earlier effects
before continuing the preserved operation. Missing evidence or receipts stops bootstrap and requires
reconciliation. Test every creation, start, policy, publication, and activation interruption in S51.

## 6. Durability and Re-Join

The full production topology has one primary and two synchronous physical standbys. The
initial `synchronous_standby_names='ANY 2 ("pg-node-2", "pg-node-3")'` policy covers both eligible
targets. Required commits use `synchronous_commit=on`. After promotion, serving waits for the
surviving standby to follow the new timeline and supply remote flush acknowledgements.
The connection manager enforces the required policy at its owned commit boundary. No
automatic asynchronous downgrade is permitted.
Implement and verify this boundary in phase 7b.1 before manual or automatic application-write
preservation assertions. Use the production manager through the stable endpoint; raw test SQL
does not qualify the application path. S53 verifies weaker settings and uncertain commits.

The system design §5.8 owns the complete policy lifecycle. With node 2 promoted, require
`ANY 1 ("pg-node-3")`. A rebuilt node 1 remains excluded from promotion while catching up.
Withdraw and revoke writer admission, establish write quiescence, validate both peers against
a fresh WAL boundary, install `ANY 2 ("pg-node-1", "pg-node-3")`, confirm the policy revision,
then prepare, publish, and activate. Every named peer is required. Do not reduce the set merely
because a standby failed while the current writer remains serving. A second failure during
cutover selects from confirmed coverage and excludes pending membership.

A two-node pair cannot resume required writes after promotion until a synchronous standby is
restored. It has no 45-second committed-write recovery promise.

If the synchronous standby fails, required writes block or fail within their operation
deadline. A lost commit response leaves an unknown outcome. Never automatically retry the
transaction. Reconcile using durable operation identity.

The former writer remains fenced. The operator rewinds or rebuilds it. Rewind requires checksums
or `wal_log_hints=on`, `full_page_writes=on`, and required WAL. Failed rewind requires a new
base backup. Validate identity, timeline, recovery mode, and replay of a known WAL position.
Removing quarantine for standby operation never authorises writer operation.

[PostgreSQL replication](https://www.postgresql.org/docs/current/warm-standby.html#SYNCHRONOUS-REPLICATION)
and [pg_rewind](https://www.postgresql.org/docs/current/app-pgrewind.html) define the database
requirements. The controller does not replace backups or point-in-time recovery.

## 7. Configuration and Time Budgets

The system design §5.4 owns the controller keys. This document does not define a second set.
All new keys are proposed until implemented, tested, and added to the configuration contract
and guide.

Every database probe, Consul request, provider operation, and reconciliation poll has a
deadline. A timed-out mutation remains unknown until inspected. Polling is observable and
bounded. No fixed wait establishes readiness.

The initial recovery objective is 45 seconds for a primary crash in the three-node production
topology with an eligible target, usable surviving synchronous standby, and reachable provider.
Measure a committed application write and required LISTEN catch-up. Record detection,
ownership, fencing, promotion, proxy admission, breaker recovery, reconnect, and catch-up
separately. Qualification fixes backlog, handler latency, retry state, and deployed timeouts.
The request deadlines do not establish the objective. Partitions and missing fences have no
availability promise.

## 8. Acceptance and Dependency Failures

Use real PostgreSQL, Consul, HAProxy, and node-control effects. No mocks. A real Docker
test adapter proves local behaviour only. The production provider requires platform-specific
tests before deployment.

Required fault coverage includes:

- Failed, thrown, null or malformed, timed-out, and lost-reply results from every dependency.
- Healthy database with controller death. No promotion.
- Consul loss with a healthy writer. No promotion without fresh authority and fencing.
- Partitioned old primary still reachable by application traffic. No replacement admission
  before confirmed fencing.
- Fence rejected, pending, unknown, lost response, provider restart, and supervisor restart.
- Stale promotion and fence-release commands after ownership changes.
- Promotion completed with response lost. Reconciliation retains one authorised writer.
- Two controllers and controller restart in each transition state.
- Synchronous standby loss and no automatic durability downgrade.
- Former writer restarted before rewind, failed rewind, and successful standby re-join.
- Coordination restore, isolated cluster namespaces, and controlled membership changes.
- Proxy node loss and differing proxy observations during a transition.
- Writer activation/revocation interrupted at every boundary, with delayed obsolete activation.
- Provider generation installation incomplete on one node, with old commands still pending.
- Policy cutover interrupted, standby re-join, and a second primary failure during cutover.
- Unauthorised Consul mutations and rejected session/revision conditions on authorised mutations.
- Verified first-start bootstrap and every interrupted creation/start/policy/admission step;
  bootstrap rejection for existing or ambiguous history (S51).
- Production-manager commit enforcement before transition assertions, including weaker caller
  settings and lost commit replies (S53).
- Measured proxy observation and backend-down shutdown delays, with provider revocation and
  confirmed fencing preserving safety while proxies retain earlier observations.

Identify each database node using node-local identity and server address. Physical replicas
share their PostgreSQL system identifier. Assert role, authority generation, fencing state,
durability, and application outcome separately. Observe conditions with deadlines. Assert
exception type and SQLSTATE where applicable. Teardown failure fails the test.

The complete scenario matrix and implementation gates are in the system design and
[implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md).
