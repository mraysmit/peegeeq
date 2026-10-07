# HAProxy PostgreSQL Routing and Failover Options

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Document type**: Design rationale  
**Design revision**: 2026-10-07, coordination, bootstrap, and recovery prerequisites revision

## 1. Purpose and Scope

This document explains the routing and failover choices behind PeeGeeQ's PostgreSQL design.
It is for readers deciding how a deployment identifies its writer and why the selected
architecture includes sidecars, a controller, and independent node control. It records design
rationale and deployment obligations. It does not report current implementation or run evidence.

**The decision being made.** Applications need a stable database address while the authorised
writer can change. A PostgreSQL primary is in the writable role. A standby follows replicated
database changes and can be promoted to primary. HAProxy can direct connections through one
address, but the design must also decide when promotion is safe and what excludes the former
writer. A reachable database, a writable database, and an authorised writer are different
conditions. Selecting a routing check must account for those differences.

For example, node 1 can lose contact with the controller while remaining reachable by
applications. Promoting node 2 and directing new connections to it would leave conflicting
writers unless node 1 is independently stopped and prevented from restarting as a writer.
That stop and restart inhibition is **fencing**. Closing some connections or losing a
coordination lock does not establish the same result.

**The responsibilities used to compare options.** Each alternative is assessed against the
following requirements. These responsibilities can belong to different components.

| Responsibility | Required result |
|---|---|
| Reachability observation | Establish whether a database endpoint answers within a deadline |
| Role observation | Establish whether the particular database is primary or standby |
| Writer authority | Identify the node currently permitted to serve under live transition ownership and provider admission |
| Promotion and fencing | Guard the transition and exclude former or ambiguous writers before replacement admission |
| Durability | Preserve acknowledged writes on eligible promotion targets through the required synchronous replication policy |
| Client recovery | Replace unusable sessions and complete required subscription catch-up before readiness |

Section 2 explains why TCP and PostgreSQL protocol checks do not meet all these requirements.
Sections 3 and 4 compare the selected PeeGeeQ components with role-only sidecars, agent checks,
and external cluster managers. The comparison concerns the configured guarantees of a
deployment. A product name alone does not establish safe fencing or acknowledged-write recovery.

**The selected approach.** Redundant HAProxy instances use a sidecar beside each PostgreSQL
node to report current writer eligibility. The sidecar combines role, identity, authority,
provider permission, and synchronous coverage. The proposed PeeGeeQ controller coordinates
automatic transitions through Consul. A selected independent provider enforces stop, restart
inhibition, guarded promotion, and writer grants. Application clients recover through stable
SQL and authenticated status endpoints. Optional PgBouncer adds a pooler layer that also
requires redundancy. LISTEN subscriptions require a dedicated route in transaction-pooling mode.

Consul is the reference automatic protocol. G-7 records retention or a qualified replacement
before automatic implementation. The local Java Qraft service is an option for that decision.
Its qualification and replacement obligations are in system design §5.10 and the implementation
plan. Selecting a coordinator never removes the independent fencing and admission provider.

The system design names four deployment profiles. **A** uses automatic Consul-coordinated
failover. **B** uses operator-directed promotion through the same provider safety contracts
and is the first implementation target. **C** uses an externally managed writer endpoint
whose operator must supply equivalent safety and durability evidence. **D** uses independent
development databases for connection-recovery exercises. D does not qualify replicated-data
failover. No profile requires Patroni.

**Scope of the decision.** This document covers routing signals, component responsibilities,
promotion ownership, fencing requirements, synchronous durability, recovery expectations, and
deployment selection. The production design has one primary and two synchronous standbys.
After promotion, required writes need a surviving synchronous peer. A two-node deployment
waits for restored synchronous coverage. Recovery timing requires measured qualification.
The comparison does not prescribe an unselected production provider or certify a deployment.
PeeGeeQ federation, routing between application instances, and backup restoration are separate.

**Reading guide.** Read §1.1 for the shared data contracts and §2 before selecting a health
check. Sections 3 and 4 explain architecture and alternatives; §5 explains recovery obligations;
§6 maps those obligations to deployments. Continue with
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md) for the complete protocols,
[the sidecar guide](PEEGEEQ_PG_SIDECAR.md) for eligibility reporting, and
[the Consul design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md) for guarded transitions.
[The implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md)
defines selection gates, implementation order, and required evidence.

### 1.1 Data Model and Contracts

[The system design §1.1](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#11-data-model-and-safety-contracts)
owns the data model. This document adds no stored field.

| Information | Source of truth or derivable | Meaning |
|---|---|---|
| Serving intent | Consul control record in automatic mode; provider transition record in manual mode | Intended writer, operation, and confirmed policy revision |
| Writer execution permission | Authoritative provider grant bound to node, mode, generation, operation, and policy revision | Only an open grant matching serving intent permits traffic |
| Actual database role | Derived live query | Whether the local database is in recovery |
| Completed fence and restart inhibition | Authoritative node-control provider | Evidence that another possible writer cannot accept writes |
| Routing eligibility | Derived matching authority/grant, identity, role, reachability, and synchronous coverage | Same predicate for HAProxy `/primary` and client `/writer` |
| Acknowledged durability | Commit under synchronous replication policy | Separate from routing and election |

Routing, promotion, fencing, and durability are distinct responsibilities. Passing one check
does not establish all four. At most one writer is permitted. Zero is permitted during a
transition. A replacement cannot serve while another possible writer remains unfenced.

## 2. PostgreSQL Health and Role

A TCP check establishes a reachable listener. HAProxy `option pgsql-check` establishes a
PostgreSQL protocol response. Neither identifies the authorised writer.

`SELECT pg_is_in_recovery()` distinguishes local recovery mode. False does not prove that
the node is the authorised writer or that no stale primary exists. A role-only sidecar can
return 200 on two divergent primaries.

A physical primary and its standby share the PostgreSQL system identifier. Test node identity
with independent node-local configuration and server address. Assert role and authority
separately. [PostgreSQL replication protocol](https://www.postgresql.org/docs/current/protocol-replication.html)

## 3. Selected PeeGeeQ Architecture

| Responsibility | Selected component |
|---|---|
| Stable application endpoint | Redundant HAProxy and optional redundant PgBouncer layers with tested address failover |
| Client authority observation | Authenticated read-only `/writer` through a redundant HTTP endpoint; no client Consul credentials |
| Eligibility response | `peegeeq-pg-sidecar` with explicit automatic or manual mode |
| Automatic transition authority | Cluster-scoped Consul record and `PgPrimaryElector` |
| Failure suspicion and reconciliation | `PgFailoverMonitor` in proposed `peegeeq-pg-failover` |
| Fencing and guarded node actions | Independent provider with cluster generation retirement, writer preparation/activation/revocation, and standby-only admission |
| Acknowledged-write preservation | Three-node synchronous topology covering both eligible targets before failure and a surviving synchronous peer after promotion |
| Client recovery | Shared pool manager, deadlines, LISTEN reconnect, and durable catch-up |

No profile requires Patroni. Database failover is independent of PeeGeeQ federation.

Automatic mode requires consistent authority observations and durable generation enforcement
at the node-control boundary. A SQL-only monitor is insufficient. A lock is advisory and
cannot stop PostgreSQL accepting writes. [Consul sessions](https://developer.hashicorp.com/consul/docs/automate/session)

Manual mode is the first implementation target. The operator fences the old writer, verifies
standby eligibility, promotes, and performs controlled re-join. The sidecar's manual admission
cannot be enabled by merely stopping the automatic controller.
Select and specify provider integration before implementing manual mode. Reuse that boundary
for automatic mode. Publishing serving intent and opening its matching provider grant are
separate recoverable steps. A published intent with a closed grant remains unavailable.

## 4. Alternatives and Their Limits

| Approach | Routing signal | Automatic promotion | Required safety work |
|---|---|---|---|
| PeeGeeQ manual profile B | Sidecar eligibility | Operator | Confirmed fence, restart inhibition, synchronous durability, and re-join |
| PeeGeeQ automatic profile A | Role plus current Consul authority and admission | PeeGeeQ controller | Tested node-control provider, generation enforcement, reconciliation, and all B contracts |
| PeeGeeQ automatic control with Qraft | Role plus qualified Qraft authority and admission | PeeGeeQ controller after G-7 qualification | Define conditional ownership, expiry, revisions, linearizable authority reads, generation mapping, durable recovery, and security. Replace the reference Consul contracts before implementation. |
| Role-only HTTP sidecar | Local role | None | Does not prevent two writable primaries |
| HAProxy agent-check with role query | Local role | None | Same authority and fencing limitations as role-only HTTP |
| repmgr/repmgrd | Deployment-specific role integration | Can automate promotion | Verify its configured fencing, topology, and re-join guarantees |
| Patroni | Cluster-manager eligibility API | Cluster manager | Verify its configured DCS, fencing, synchronous policy, and recovery behaviour |

No entry receives unconditional split-brain safety from a product name. Safety depends on
the deployed authority and fencing mechanisms. A quorum service alone does not stop a stale
database process.

Patroni is context for the responsibilities a cluster manager supplies. Recreating only its
HTTP role query does not recreate its complete eligibility or failover contract. This design
does not claim equivalent guarantees from a minimal sidecar.

## 5. Recovery Guarantees

HAProxy uses `httpchk GET /primary` and `on-marked-down shutdown-sessions`. All backends use
the same policy. No fixed backup preference sends traffic to a returning former primary.
Routing uses the last successful scheduled sidecar observation and configured backend health.
Session shutdown occurs after failed checks mark a backend down. Measure the observation and
shutdown delay. Provider-enforced admission and hard fencing protect single-writer safety while
different proxies retain different observations.

An unreachable old primary remains unknown until independently fenced. Session termination,
pool closure, and lock delay do not satisfy that precondition. PostgreSQL requires protection
against a former primary continuing as primary.
[PostgreSQL failover](https://www.postgresql.org/docs/current/warm-standby-failover.html)

Synchronous replication protects acknowledged commits on both eligible standbys in the
three-node production topology. Serving after promotion requires the surviving synchronous
peer. A two-node pair waits for restored redundancy before required writes resume. Asynchronous
replication can lose acknowledged writes. No automatic asynchronous downgrade is permitted.
A failed response can leave commit outcome unknown. Applications reconcile or use an explicit
idempotency contract before retrying.
[PostgreSQL replication](https://www.postgresql.org/docs/current/warm-standby.html#SYNCHRONOUS-REPLICATION)

The initial policy is `ANY 2 ("pg-node-2", "pg-node-3")`. After node 2 is promoted, require
`ANY 1 ("pg-node-3")`. Rebuilt node 1 remains excluded until a withdrawn, quiescent policy
cutover validates it and installs `ANY 2 ("pg-node-1", "pg-node-3")`. Policy revisions and
required sets are authoritative intent. Count and live peer health are computed. A second
failure during cutover uses confirmed coverage, not pending membership. Losing either standby
under the normal two-peer policy blocks required writes. There is no automatic reduction
while that writer remains serving.

Durable bitemporal recovery uses the existing tenant cursor and replay lease. A short writer
barrier establishes a finite committed event-ID boundary before handlers run. Delivery follows
stable append-ID order, not commit-time order. Notifications and periodic reconciliation
request the same serialized scan. At-least-once redelivery remains possible after handler
success followed by an uncertain cursor commit. No parallel failover cursor is introduced.

Native notification recovery requires one executed bounded claim batch with confirmed handler
processing acknowledgements, delayed-message wake-up scheduling, and final writer revalidation.
Skipped or capacity-deferred work cannot establish readiness. Continuous arrivals schedule later
passes and do not extend the finite recovery batch. Queue backlog remains a workload metric (S52).

First-start bootstrap requires verified provisioning, closed admission, all-node fences, guarded
starts, initial synchronous policy confirmation, and prepare/publish/activate (S51). Missing
coordination history alone is not bootstrap permission. Phase 7b.1 must verify the production
manager's commit-policy enforcement before bootstrap or failover write-preservation assertions
(S53). Full application-module migration remains phase 11.

The initial 45-second recovery objective applies to the qualified three-node primary-crash
case with a reachable provider, eligible target, usable synchronous peer, specified backlog,
and measured handler latency. Request timeouts alone do not establish it. Measure committed
application work and finite required catch-up. A partition without a confirmed fence has no
availability promise. PgBouncer and address-owner failures are separate qualification cases.

## 6. Deployment Selection

| Deployment | Profile and obligations |
|---|---|
| Production with operator promotion | B; verified fencing, synchronous standby, redundant proxy, and tested re-join |
| Production requiring automatic promotion | A after provider and partition acceptance gates pass |
| Externally managed writer endpoint | C; external operator supplies equivalent fencing and durability evidence |
| Development connection-recovery exercises | D; independent databases and protocol checks, with no replicated-data guarantee |

The production provider remains a deployment decision. Select and specify it before implementing
manual or automatic promotion. Prove it before release. Do not describe host-control dependencies as absent merely because Consul
is already installed.

G-7 is an additional selection gate for automatic control. Manual B does not require Consul.
Managed C requires equivalent external contracts. Qraft requires its own service qualification,
asynchronous PeeGeeQ adapter, and independent runtime configuration. Only one automatic backend
may own an incarnation; changing it requires stopped, fenced reconciliation and a new incarnation.

## 7. Build and Operations References

[The sidecar guide](PEEGEEQ_PG_SIDECAR.md) owns sidecar packaging and configuration.
[The Consul design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md) owns controller and provider contracts.
[The system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md) owns deadlines, durability,
proxy configuration, scenarios, and client recovery.
[The implementation plan](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md)
owns execution phases and dated evidence.

Duplicated Java, Docker, authentication, and native-build sketches were removed. They omitted
authority and lifecycle handling and could be mistaken for complete implementations. New
examples require verified configuration and observed asynchronous results.
