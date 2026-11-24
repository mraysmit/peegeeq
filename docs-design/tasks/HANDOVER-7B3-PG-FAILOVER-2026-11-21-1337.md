# Handover: phase 7b.3, PostgreSQL failover supervisor

**Written:** 2026-10-26, at the end of the P2 session. **Last updated:** 2026-11-21 13:37 (UTC+8).
**Updated:**
- 2026-11-03: P3, P4 design.
- 2026-11-12: P4 to P6.
- 2026-11-13: end of the P7 session, after the owner stopped the regression run.
- 2026-11-14: P7 regression, two defect fixes from Jenkins build #37, P7 records.
- 2026-11-15 and 2026-11-16: automatic rewind and rebuild (plan §9.32).
- 2026-11-17: check against `git log` and the plan.
- 2026-11-18: restart and reload regression (plan §9.33), coordinator configuration CC1 to CC3 (plan §9.34), design of
  PostgreSQL parameters in the coordinator (plan §9.35). Committed by the owner on 2026-11-19 in `0ffaafb6`.
- 2026-11-20: review against git, the code, the plan, and `logs/`; two review findings on plan §9.35.
- 2026-11-21: Jenkins builds #41 and #42 on `0ffaafb6`; PP1 (plan §9.35, OD-76).
- 2026-11-21 13:37 (this file, `HANDOVER-7B3-PG-FAILOVER-2026-11-21-1337.md`, replaces `HANDOVER-7B3-PG-FAILOVER.md`):
  PP1 built; a review of every live document under `docs-design`; the task register rebuilt as an index; the plan's
  closed sections archived; 25 documents corrected; defects D1 to D13 recorded in the register.

**For:** the next session that continues Task 8, phase 7b.3, in `peegeeq-pg-failover`.
**Status of this document:** a briefing, not state. Status lives in code, tests, and logs. Check each claim against the
artifact before relying on it. The authoritative records are:
- the task register `docs-design/tasks/tasks.md`: status, the ordered pending list, and defects D1 to D13;
- the plan `docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`:
  contracts (§5), the evidence table (§8.1), the owner decisions of 2026-11-13 (§9.28), and the open design of the
  PostgreSQL parameters (§9.35);
- the archived plan sections
  `docs-design/_archived/completed-records/PEEGEEQ_PG_FAILOVER_PLAN_CLOSED_SECTIONS_TO_2026-11-21.md`: §2 to §4, §8.2,
  §9.1 to §9.27, and §9.29 to §9.34, moved unchanged with their numbers on 2026-11-21. A reference such as
  "plan §9.32" in this handover or in code is found there.

## 1. Task and state

- **Task.** Task 8, phase 7b.3 (manual takeover) in `peegeeq-pg-failover`. The owner directed that PeeGeeQ duplicate
  Patroni's operations. Strict TDD (red before green), one increment at a time.
- **Done before 2026-11-13.** P1 promotion with CHECKPOINT (§9.21); P2 follow (§9.22); P3 policy cutover (§9.23); P4
  member slots on the writer (§9.24, §9.25); P5 lost-slot replacement (§9.26); P6 slots on standbys advanced to published
  positions (§9.27).
- **Owner decisions of 2026-11-13.** The owner answered fifteen questions on the open decisions. They are recorded
  verbatim, with every conflict found against the design set and the code, in plan §9.28, first table. Summary:
  - Kept: OD-27 (unbounded `max_slot_wal_keep_size` refused), OD-35, OD-36, OD-37; adopting an existing cluster is not
    supported (§9.20 row 26).
  - Reversed: OD-38 (member records are session-bound), OD-39 (positions rank covered candidates), OD-40 (publication and
    advance move into the monitor, which gets a builder), OD-41 (own exception and a contract suite).
  - New scope: automatic rewind and rebuild with data-directory removal on a failed rewind (§9.20 row 10; reverses P-6);
    restart, reload, `pending_restart` (row 9); tags `nofailover`, `nosync`, `noloadbalance`, `clonefrom` (row 19);
    cluster-wide configuration in the coordinator with a guarded edit (row 15); `/cluster`, `/history`, a node status
    endpoint, `/metrics` (row 17); the five Patroni callbacks, non-blocking (row 23).
  - Corrected 2026-11-21: this line named an assistant memory file, `project-owner-answers-20261113.md`, which does
    not exist. The answers are recorded verbatim only in plan §9.28.
- **Owner direction, 2026-11-13.** "After verification proceed with implementation. Do not stop to ask me any
  questions. I expect you to complete all these tasks." The owner then stopped the session during the regression run
  and asked for this handover.
- **Owner direction, 2026-11-17** (recorded in `tasks.md`). Task 8 is completed before work on any other task starts,
  Task 9 included.
- **Done 2026-11-13: P7 (answers 5 to 8), implemented and probed; regression not finished.**
  - P7a: `PgMemberPositionException`; neutral `PgMemberPositionsContract`; `ConsulMemberPositionsIntegrationTest` is its
    Consul binding.
  - P7b: `ConsulMemberPositions` holds its own member session (TTL = lease TTL, `Behavior=delete`, `LockDelay=0s`, no
    checks, read back and validated). `publish` renews or re-creates the session and writes with `?acquire`. `read`
    fails on a record no session holds. The constructor takes the Consul node name.
  - P7c: `PgFailoverMonitor.builder()`; the public constructor is deleted; `publishPosition()` and
    `advanceMemberSlots()` are monitor operations, serialized; `PgMemberSlots` is deleted.
  - P7d: `PgDurabilityControl.candidatePosition` (larger of received and replayed WAL; timeline of the receiver, else of
    the last restartpoint; refused on a writer); `PgCandidacy`; `PgFailoverMonitor.takeoverCandidacy(observed)`.
- **Done 2026-11-14.** P7 regression green on Jenkins (build #36). Two defects found by build #37 fixed:
  `PgSqlPromotionControl.follow` now requires the WAL receiver's `wait_event = WalReceiverMain` (PostgreSQL 15 shows
  `streaming` with the writer's address and slot before `START_REPLICATION` fails on a missing slot); 20 integration
  classes declare `io.vertx.junit5.Timeout` equal to their JUnit class limit. P7 records written (plan §8.1, §9.28;
  system design §1.1, §5.10; coordinator design §1.1, §3.3; `tasks.md`).
- **Done 2026-11-17 and 2026-11-18: restart, reload, `pending_restart` (answer 10, plan §9.33).** RS0 to RS3 are
  built (code `a74652b9` to `e2f7a3ad`, tests `64e5d525` to `eda4ef45`). The integration tier did not pass in one
  run on the workstation; it passed in one run in Jenkins build #41 on 2026-11-20 (§3).
- **Verified 2026-11-20 and 2026-11-21: Jenkins builds #41 and #42 on `0ffaafb6`.** Every module from
  `peegeeq-pg-failover` on passed, the UI modules included. Re-join, restart and reload, and CC1 to CC3 are in this
  commit. Details in §3.
- **Done 2026-11-18: coordinator configuration CC1 to CC3 (answer 12, plan §9.34).** The four lease timing values are
  one coordinator record per incarnation, edited by a revision check; bootstrap writes the first record. Code and tests
  `2b32dd40` to `169c05e3`; design documents `7bb6eeaf`, `2fb6c8e8`.
- **Designed 2026-11-18: PostgreSQL parameters in the coordinator (answer 12, plan §9.35).** Plan `e57e31c2`. The
  feature: an operator sets PostgreSQL settings once for the whole cluster; each node's supervisor writes them into
  `peegeeq-cluster.conf`, reloads, and checks that they took effect, so that no node runs different settings after a
  failover. PP2 to PP5 are not built.
- **Done 2026-11-21, uncommitted: PP1, `PgClusterParameters` (plan §9.35, contract 1, OD-76).** The list of
  cluster-wide settings. It refuses a denied name, a name that is not lower case, and a value with a line break or NUL;
  converts to and from the JSON the coordinator will hold; and renders the file text with quotes and backslashes
  doubled. OD-76 decided review finding 2: `recovery_min_apply_delay`, the eight recovery target parameters, and
  `promote_trigger_file` are denied. Nothing calls the class yet. Files:
  `peegeeq-pg-failover/src/main/java/dev/mars/peegeeq/failover/PgClusterParameters.java` and
  `peegeeq-pg-failover/src/test/java/dev/mars/peegeeq/failover/PgClusterParametersTest.java` (16 tests).
- **Open for the owner (2026-11-21):** whether the cluster-wide parameters may contain settings PostgreSQL runs as a
  shell command or loads as code (plan §9.35, "Danger found in PP1"); raising the Jenkins pipeline limit of 150
  minutes; whether defect D3 means the code or the guide is wrong.
- **Document review, 2026-11-21.** Every live document under `docs-design` was read and checked against the code. The
  task register is now a short index; its earlier text is in `docs-design/tasks/archive/TASKS_REGISTER_TO_2026-11-21.md`.
  Six documents were archived. The review confirmed in code that nothing runs failover in production: no module
  depends on `peegeeq-pg-failover`, and it has no `main`.
- **Git on 2026-11-21 13:37.** `master` is still `0ffaafb6`. The working tree holds the uncommitted PP1 files, the
  document review edits (41 files modified or deleted), and 8 new paths. The archived files were moved with `mv`, so
  git shows a deletion and a new file for each; `git add -A` records them as renames.
- **Git, earlier.** On 2026-11-20 local `master`, `origin/master`, and the remote `refs/heads/master` were `0ffaafb6` (checked
  with `git log`, `git rev-parse`, and `git ls-remote`), and the working tree was clean before that day's edits. `0ffaafb6` (owner,
  2026-11-19) changes only this handover, `tasks.md`, and the plan. The 18 commits `2b32dd40` to `00a933fe` were made
  by Claude at the owner's request on 2026-11-18, one file each (checked 2026-11-20). The owner makes all commits
  unless they ask Claude to commit; do not reset or push without the owner's instruction.

## 2. What exists in the module

| Item | Purpose |
|---|---|
| `PgFailoverMonitor.builder()` | Requires config, elector, store, process, gate, watchdog, durability, promotion, positions, configuration (added 2026-11-17); `build()` names a missing one (`NullPointerException` message = the name) |
| `PgFailoverMonitor.publishPosition()` | Serialized. Refused under quarantine. Observes `durability.position(self)` and publishes it |
| `PgFailoverMonitor.advanceMemberSlots()` | Serialized. Refused under quarantine. Ensures slots for other members, reads positions, advances. Returns the members advanced |
| `PgFailoverMonitor.takeoverCandidacy(observed)` | Serialized. Refused (synchronous, "rejected" log) when the confirmed policy does not name this node; refused under quarantine. Not preferred when another member of the confirmed policy published a higher timeline, or the same timeline beyond this node's candidate position. Missing records ignored |
| `PgCandidacy` | Record `(preferred, reason)`. Derived, never stored |
| `PgDurabilityControl.candidatePosition(nodeId)` | Standby only. SQL in `PgSqlDurabilityControl.CANDIDATE` |
| `PgMemberPositions` / `ConsulMemberPositions` | Port and adapter as in §1. Failures are `PgMemberPositionException` |
| `PgMemberPositionsContract` | Abstract contract: read-back, replace, empty, expiry, keep-alive, re-publish after expiry, unheld record, rejected write, malformed, non-member, filtered, unanswered, unreachable |
| `PgFailoverMonitor.rejoin(observed, writer, budget)` | Added 2026-11-15 (plan §9.32). Automatic rewind, and a rebuild when the rewind does not produce a caught-up standby, under the re-join quarantine. Refuses a record naming this node or with no lease holder, and a foreign quarantine. Checks the source (writable, same system, usable slot) before the stop and again before any removal. Another system or an unreadable directory outside an interrupted re-join gets the operator quarantine |
| `PgProcessControl.inspect`, `rewind`, `rebuild` | Added 2026-11-15. `pg_ctl status`, `pg_controldata` (C locale), `pg_rewind`, `pg_basebackup -S <own slot>`; binaries derived from the `pg_ctl` path. The node's top-level `*.conf` files are saved to `<data directory>.rejoin-saved` and restored; the inhibitor is recreated after every rewind and base backup |
| `PgPromotionControl.observeSource`, `confirmCaughtUp` | Added 2026-11-15. `IDENTIFY_SYSTEM`, `READ_REPLICATION_SLOT`, and `pg_is_in_recovery()` through the node's replication role; catch-up = same system, streaming from the writer's address on its timeline, replay at or after its flush position |
| `PgConfigurationControl` / `PgSqlConfigurationControl` | Added 2026-11-17 (plan §9.33, contract 1). `settings()`: postmaster start time, server version, recovery, parameters pending a restart (`PgServerSettings`). `reload()`: refuses a file PostgreSQL rejects before the reload, confirms by `pg_conf_load_time()`, and fails on an invalid value after the reload. Failures are `PgConfigurationException` |
| `PgConfigurationFiles` | Package-private. The one error query: `rejected`, `hba`, `invalid` lists. Also used by `PgSqlDurabilityControl.install`, `PgSqlPromotionControl.follow`, and `PgHbaAdmissionGate`, which refuse a `rejected` file; the gate's close ends the sessions first, then fails (OD-62) |
| `PgFailoverMonitor.reload()` | Serialized. No lease, no receipt. Allowed under quarantine (OD-61) |
| `PgFailoverMonitor.restart(observed, request)` | Serialized. `PgRestartRequest` (own operation, `onlyIfPending`, role, `belowVersion`); `PgRestartResult(restarted, reason)`. Reload first; an unmet condition is a result (OD-57). Own stop and guarded start under the `restart` receipt, never `pg_ctl restart` (OD-54). A serving writer is admitted again with the same mode and revision (OD-58). Confirmed by a later postmaster start time |
| `PgFailoverMonitor.scheduleRestart`, `cancelScheduledRestart`, `runScheduledRestart(observed, now)` | One `PgScheduledRestart` per node in `scheduled-restart.json` (OD-59), with the postmaster start time when scheduled; a later start cancels it; removed after its attempt. Nothing outside tests calls `runScheduledRestart` |
| `PgClusterSettings`, `PgNodeConfig.clusterSettings()` / `withClusterSettings(settings)` | Added 2026-11-18 (plan §9.34, CC1). The four lease timing values with the lease and local-stop rule, the one copy of that rule; stored form `leaseTtlMillis`, `loopIntervalMillis`, `requestTimeoutMillis`, `stopTimeoutMillis`, exact fields, never adjusted |
| `PgClusterConfiguration` / `ConsulClusterConfiguration` | Added 2026-11-18 (plan §9.34, CC2). `read()` (consistent; no known leader, ACL filtering, or a refused status fails), `create(settings)` (`?cas=0`), `update(expected, settings)` (`?cas=<revision>`); a reply other than `true` fails; the record is read back. Key `peegeeq/pg/<cluster>/<incarnation>/config`. `PgClusterSettingsRecord(revision, settings)`. Failures are `PgClusterConfigurationException`. `PgBootstrap` calls `read` and `create`; nothing calls `update`, and nothing outside tests constructs `PgBootstrap` |
| `PgBootstrap(vertx, config, elector, settings, store, monitor, loop)` | Changed 2026-11-18 (plan §9.34, CC3). After the control-record check and before the initial intent, `start` writes the deployment timing as the first settings record, accepts an equal record, and refuses other values (`PgBootstrapException`); a failed read or write fails before the initial intent |
| `PgClusterParameters` | Added 2026-11-21 (plan §9.35, PP1), uncommitted. The cluster-wide PostgreSQL settings: a sorted, unmodifiable map of name to value. The constructor refuses a denied name (the supervisor's own settings, per-node endpoints, file locations and directives, and the replay and promotion settings of OD-76), a name that is not lower case, and a value with a carriage return, line feed, or NUL. `toJson`/`fromJson`: exactly the field `parameters` with string values. `render()`: a header line, then `name = 'value'` lines in name order with each quote and backslash doubled. Stores nothing, writes no file; no caller yet |
| Everything listed in the 2026-11-12 handover | Unchanged except bootstrap (row above): promotion, follow, slots, lost-slot replacement, policy cutover, renewal loop, watchdog modes, admission gate, local store |

## 3. Evidence

**Where the logs are.** On 2026-11-20 `logs/` in the checkout at `c:\Users\mraysmit\dev\idea-projects\peegeeq` held no
file dated after 2026-11-15. Missing there: every 2026-11-17 and 2026-11-18 log named below (`rs-*`, `cc1-*`, `cc2-*`,
`cc3-*`, `parameters-probe*`), and every 2026-11-13 P7 log named below except the three `p7-acquire` logs. `logs/` is
not tracked by git. A search of `C:\Users\mraysmit` to depth 6 for `*cc3-core-20261118*` found nothing, and the
repository has one worktree. Where these logs are now is not known. Until they are found, the counts below for 2026-11-13 and
for 2026-11-17 and 2026-11-18 rest on the plan's records only. The Jenkins host logs in
`/var/lib/jenkins/peegeeq-work/logs` were not checked on 2026-11-20.

**2026-11-13, as reported at that handover.** On 2026-11-18 each count below was checked against its log's
`Tests run:` lines, and the `-p7ab-mutation-sha-` before and after records were compared; they matched. (A check on
2026-11-14 had not found them.)

- **Red, then green, per increment.**
  - P7a/P7b red: `peegeeq-pg-failover-p7ab-red-20261113.log` (16 run, 12 failures, each the old exception type, no
    session, or a missing argument check). Green: `-p7ab-green-` 16 of 16, then `-p7ab-green2-` 18 of 18 after two added
    session tests.
  - P7c: the builder test was written after the builder code, so no compile-red log exists. Probe 43 is its behaviour red.
  - P7d red: `peegeeq-pg-failover-p7d-red-20261113.log` (9 run, 4 failures, 5 errors, all on the stub). Green:
    `-p7d-green-candidacy-` 6 of 6; `-p7d-green-adapter-` member-slot adapter 13 and positions binding 18;
    `-p7d-green-received2-` 1 of 1 (the paused-replay test, added before the probes; its first run hit the 30-second
    Vert.x default).
- **Mutation probes 34 to 50, all detected.** SHA-256 records: `-p7ab-mutation-sha-before-`/`-after-`
  (`ConsulMemberPositions.java` `20D3F01B...180D68`), `-p7c-mutation-sha-before-`/`-after-` (`PgFailoverMonitor.java`
  `1DE3229A...B2B102`, before P7d), `-p7d-mutation-sha-before-`/`-after-` (`PgFailoverMonitor.java` `72CC32B2...CB02728`,
  `PgSqlDurabilityControl.java` `95A83E1E...265C2C`). 34 acquire; 35 `Behavior=delete`; 36 unheld-record check; 37 session
  renewal; 38 re-create on 404; 39 session read-back; 40 and 41 quarantine in publish and advance; 42 slot step in
  advance; 43 builder null check; 44 higher-timeline rule; 45 same-timeline rule; 46 policy-name refusal; 47 candidacy
  quarantine; 48 writer refusal; 49 restartpoint-timeline fallback; 50 received position used.
- **Integration runs after P7c and before P7d** (`-p7c-*-20261113.log`): slot 11, takeover 11, monitor 23, bootstrap 7,
  renewal loop 13, bootstrap serving 15, promotion 17, follow 10, policy installation 14. Core after P7c: 410 in 11
  classes (`-p7c-core-`).
- **Regression on the final code** (rebuild `rebuild-peegeeq-pg-failover-p7-restored-20261113.log`, BUILD SUCCESS):
  durability 29, promotion adapter 19, promotion 17, bootstrap serving 15, all 0 failures (`-p7-regress-*-`). Also on
  the final P7d code: candidacy 6, member-slot adapter 13 plus the added test, positions binding 18. The takeover run
  (`-p7-regress-takeover-`) was stopped by the owner while running; its log has no result.

**2026-11-14.** Jenkins build #36 (`4b1e2a87`): `peegeeq-pg-failover` 704 tests, downstream modules and UI suites
green. Jenkins build #38 (`ec62d499`): `peegeeq-pg-failover` 706 tests, 0 failures; for the downstream modules read
the build. Runs on the Jenkins host, logs in `/var/lib/jenkins/peegeeq-work/logs`: red runs of the follow test on the
pre-fix code (`red2-` to `red4-`), promotion class 20 of 20, integration tier 296 tests, core 410, guards 77,
acquire-refusal mutation probe with SHA-256 restored. Details in plan §9.28. Jenkins build #39 (`ed29d191`):
SUCCESS, `peegeeq-pg-failover` 717 tests and every module after it green. Jenkins build #40 (`b3f7ddc9`, member tags,
2026-11-15): SUCCESS. Test step: `peegeeq-pg-failover` 728, examples 178, benchmarking 153, migrations 56,
integration-tests 109, OpenAPI none ("No tests to run."). UI stage: management UI 128 unit and 419 e2e, utilities UI
836 unit and 246 e2e. The test step of these builds runs only the seven reactor modules from `peegeeq-pg-failover` on.

**2026-11-15 and 2026-11-16, automatic rewind and rebuild (plan §9.32).** Runs on the Jenkins host clone
`/var/lib/jenkins/peegeeq-phase5` (files equal to `7489cd41` for the regression), logs in
`/var/lib/jenkins/peegeeq-work/logs`: PostgreSQL probes `rewind-probe*-20261115.log`; R1 red 21 on the stub, green 22;
R2 red 12 on the stub, green 12; probes 51 to 70 and 53b, all detected after the 53 test fix, SHA-256 equal;
regression `rejoin-regress-*-20261115.log`: integration tier 23 classes, 348 tests, core 416, guards 77, all 0
failures. No Jenkins job build has run this code.

**2026-11-24, local rerun of the 2026-11-15 and 2026-11-14 Jenkins-host evidence.** The Jenkins host did not answer
within 8 seconds; local Docker 29.9.3. Logs in `logs/`.
- Checks 1 to 11 (`review-rewind-facts-probe2-`, `review-rewind-checks-probe-`, `review-lost-slot-probe-20261124.log`):
  as recorded, except check 4 (D27) and check 10 (D28).
- `PgRejoinControlIntegrationTest` 23 of 23 (`-review-conf-green-class-`). `PgRejoinIntegrationTest` 12 of 13
  (`-review-r2-class-`); the 13th stopped on the fixture's standby base backup, a `docker exec` over its 30-second
  limit, and passed alone, 1 of 1 (`-review-r2-rerun-`).
- The list of probes 51 to 70 is only in the Jenkins log. In its place, 38 mutations, one per rule of §9.32 contracts
  1 to 6, the 2026-11-14 follow fix, and the acquire refusal (`review-rejoin-mutation-summary-20261124.log`): 31
  detected, each on an assertion of the mutated rule; SHA-256 of the four production files equal before and after.
  Seven not detected (D29): restore and saved copy after a failed rewind, the source slot-row check, the timeline and
  system-identifier conditions of the catch-up, the monitor's catch-up step, and the second source check before the
  removal. The follow fix (P9) and the acquire refusal (K1) were detected, as recorded on 2026-11-14.

**2026-11-17 and 2026-11-18, restart, reload, `pending_restart` (plan §9.33).** Owner's workstation, local Docker
29.9.3; the Jenkins host did not answer either day. Logs in `logs/`. RS1 to RS3 red and green, probes 71 to 103 (no
89), SHA-256 equal: plan §9.33. Integration tier, 2026-11-17, 18:20 to 20:13, on files equal to the 20:08 commits
(checked 2026-11-18, plan §9.33) (`peegeeq-pg-failover-rs-regress-integration-20261117.log`): 26 classes, 396 tests,
1 failure, 11 errors, each of the 12 on a `docker exec` over its limit. 2026-11-18 at `4c1567d7`: rebuild BUILD SUCCESS; those 12 alone 12 of 12
(`-rs-rerun-failed-20261118.log`); core 424 in 11 classes (`-rs-core-20261118.log`); guards 77 in 14 classes
(`peegeeq-test-support-guards-rs-20261118.log`). Second tier run, 2026-11-18, 13:12:50 to 15:12:01, on the classes
at `4c1567d7` (`peegeeq-pg-failover-rs-regress-integration-20261118.log`): 396 tests, 2 errors, both in
`PgFailoverMonitorIntegrationTest` (a set-up `initdb` over 30 s; a primary start that outlasted the class's 12-second
ownership budget while `docker exec` calls stalled); 25 classes whole; the two alone 2 of 2
(`-rs-rerun-monitor-20261118.log`). The tier has not passed in one run. No Jenkins job build has run this code.

**2026-11-18, coordinator configuration CC1 to CC3 (plan §9.34).** Owner's workstation, local Docker; the Jenkins host
did not answer within 8 seconds at 13:12 and at 15:46. Logs in `logs/`, `peegeeq-pg-failover-cc1-*`, `-cc2-*`,
`-cc3-*`, with their rebuild logs. CC1: red 11 on the stubs, `PgClusterSettingsTest` 9 and `PgNodeConfigTest` 29,
probes 104 to 114. CC2: red 21 on the stubs, `ConsulClusterConfigurationIntegrationTest` 27 of 27 (`-cc2-final-`),
probes 115 to 127. CC3: red 3 on the stub, `PgBootstrapIntegrationTest` 11 and `PgBootstrapServingIntegrationTest` 15
(`-cc3-touched-`), probes 128 to 131. Each probe set detected, SHA-256 equal before and after (plan §9.34). Core 436
(`-cc3-core-`), guards 77 (`peegeeq-test-support-guards-cc3-20261118.log`). No Java file changed after the CC3 final
rebuild (15:59). The module's integration tier and a Jenkins job build have not run this code.

**2026-11-18, PostgreSQL parameter checks (plan §9.35).** Throwaway `postgres:15.13-alpine3.20` containers on local
Docker 29.9.3 (the Jenkins host did not answer at 17:42). Logs `logs/parameters-probe*-20261118.log`; the void runs are
named in plan §9.35. Checks 1 to 12.

**2026-11-20, plan §9.35 review probe.** Throwaway `postgres:15.13-alpine3.20` container, local Docker 29.9.3, log
`logs/parameters-probe-filesettings-20261120.log`: `pg_file_settings.applied` is true for a value written but not yet
reloaded. Plan §9.35, "Review findings, 2026-11-20", finding 1.

**2026-11-20 and 2026-11-21, Jenkins builds #41 and #42 on `0ffaafb6`.** Both checked out `0ffaafb6`. The consoles are
saved as `logs/jenkins-build41-console-20261120.log` and `logs/jenkins-build42-console-20261121.log`.
- Build #41 used `TEST_SUITE=all` and `ALL_TESTS_START_MODULE=peegeeq-pg-failover`. Java stage:
  `-Pall-tests -rf :peegeeq-pg-failover`, BUILD SUCCESS in 2 h 21 min. `peegeeq-pg-failover` ran 863 tests in 39
  classes, 27 of them integration classes, with 0 failures, 0 errors, and 0 skipped. It took 2 h 10 min. The other
  modules: examples 178, benchmarking 153, migrations 56, integration-tests 109, all 0 failures; OpenAPI "No tests to
  run.". The pipeline's 150-minute limit (`Jenkinsfile` line 48) then stopped the UI stage, after the management UI
  unit tests had passed 128 of 128. Result `ABORTED` after 150.2 min.
- Build #42 used the same commit and `ALL_TESTS_START_MODULE=peegeeq-management-ui`. The Java stage ran no test, by
  design. UI stage: management UI unit 128 and e2e 419; utilities UI unit 836 and e2e 246; all passed. Result
  `SUCCESS` in 31.8 min.
- Longest `peegeeq-pg-failover` classes in build #41 (Surefire "Time elapsed"): durability 849 s, re-join control
  774 s, promotion adapter 643 s, promotion 550 s, bootstrap serving 533 s, re-join 453 s, takeover 447 s, policy
  installation 433 s. The JUnit `@Timeout` on these classes applies to each test method (for example 180 s in
  durability and 300 s in re-join control), not to the class.

**2026-11-21, PP1 (plan §9.35).** Owner's workstation, local Docker 29.9.3 (used only for the name list of OD-76,
`logs/parameters-probe-recovery-names-20261121.log`). Logs `logs/peegeeq-pg-failover-pp1-*-20261121.log`. Red against
the stub: 15 run, 8 failures, 4 errors. Green 15 of 15. A test for a name of 100 001 dot-separated parts reproduced a
`StackOverflowError` in the first name check (a regular expression with a repeated group); the check now matches each
part. Green 16 of 16. Mutation probes 132 to 149, each detected; SHA-256 equal before and after
(`-pp1-mutation-sha-before2-`, `-after-`). Core 452 in 13 classes (`-pp1-core-`); guards 77 in 14 classes. The
comments of both files were rewritten afterwards (no code change; rebuild, and the class 16 of 16, `-pp1-comments-`),
so the production file's SHA-256 now differs from the probe record. No integration class ran: nothing uses the class.

**2026-11-21, document review.** No build or test of product code. Guards after the last document edit: 77 in 14
classes, 0 failures (`logs/peegeeq-test-support-guards-docreview-20261121.log`). A link check of the live documents
found no broken relative link.

## 4. Next steps

The ordered list of pending Task 8 work is in the task register, `docs-design/tasks/tasks.md`, section "Task 8"; it
is not repeated here, so that the two cannot disagree. As of 2026-11-21 the next step is PP2: the parameters
operations on the cluster configuration port, their contract cases, and the Consul binding (plan §9.35, contract 2).

Completed items and their commits, for orientation (the run records are in the plan and its archived sections):
P7 regression and records, the build #37 defect fixes, takeover after real lease expiry (`bafe52b2`, `6ac4b5b3`,
`c5c44640`), planned stop with release (`ed29d191`), member tags (`f2b03bb1`), automatic rewind and rebuild
(`4aa9dc8e`, `7489cd41`, `4aa72780`), restart and reload (code `a74652b9` to `e2f7a3ad`, tests `64e5d525` to
`eda4ef45`), coordinator configuration CC1 to CC3 (`2b32dd40` to `00a933fe`), and PP1 (uncommitted on 2026-11-21).

Each item is designed in its own plan section, with its data model and contracts, before its tests.

## 5. How to work here

- **Pre-work (mandatory, from `.claude/CLAUDE.md`).** Read `docs-design/dev/pgq-coding-principles.md` and
  `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full. Read every file to modify and its tests in
  full. Run the banned-pattern grep and show its output.
- **Read before you ask or recommend.** On 2026-11-13 the owner's questions were framed before the document set and
  the code were read, and the owner corrected this. Read the five design documents, the plan, the Task 8 entries, and the
  module before any question, recommendation, or design text.
- **Design first.** Data model and contracts go into the plan before any test. Record each implementer choice as
  reversible. Record where the code differs from the design text ("As built").
- **Style.** Short declarative sentences. State only gaps the repository cannot answer.
- **Comments and messages say why, in plain words (owner, 2026-11-21).** A class comment starts with what the feature
  is for and why it is needed. Every reason is written out; a plan section or decision number may follow a reason,
  never replace it. A comment never states unbuilt design as current behaviour and never carries a dated status.
  Error messages and file headers that a person reads at run time use no plan terms.
- **Commands.** From `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`, PowerShell, `Tee-Object`, the full console stream
  shown, then read the saved log. Corrected 2026-11-21: this line said to send console output to `$null`; the owner's
  rule is that test runs are shown in the console.
  - A mutation probe runs `mvn clean test`: without `clean`, Maven can report "Nothing to compile" and run a class the
    IDE compiled into `target/` (PP1, probe 132).
  - Rebuild: `mvn clean install -DskipTests -pl ":peegeeq-pg-failover" -am 2>&1 | Tee-Object -FilePath logs\<name>-<YYYYMMDD>.log`
  - One class: `mvn test -Pintegration-tests -pl ":peegeeq-pg-failover" "-Dtest=<Class>"`; methods: `"-Dtest=Class#a+b"`
  - Core: `mvn test -pl ":peegeeq-pg-failover"`; guards: `mvn test -pl ":peegeeq-test-support"`
- **Class run times on 2026-11-13** (Surefire "Time elapsed"; the command adds build time): durability 8 min 26 s
  (29 tests), promotion 6 min 4 s, promotion adapter
  6 min 1 s, bootstrap serving 5 min 33 s, slot 4 min 40 s, policy installation 4 min 30 s, takeover 4 min 19 s,
  member-slot adapter 3 min 53 s, follow 3 min 47 s, candidacy 2 min 47 s, monitor plus renewal loop plus bootstrap
  3 min 28 s together, positions binding 1 min 16 s. Jenkins build #41 times are in §3. Corrected 2026-11-21: this
  line said the durability class was "near the 10-minute limit". No class-level limit exists. The class's `@Timeout`
  and the `junit.jupiter.execution.timeout.*` defaults in the poms apply to each test method, and the class ran 849 s
  in build #41.
- **TDD sequence.** Tests; stubs that fail with "not implemented"; red; implementation; green; mutation probes with
  SHA-256 before and after, restored from a scratchpad copy; the touched classes; core; guards last.

## 6. Pitfalls found

All pitfalls of the 2026-11-12 handover still apply (teardown noise, `io.vertx.junit5.Timeout`, `TestCleanup` instead of
`.eventually(`, close calls inside `context.verify`, chained refusal checks, standby admission, replication rules,
slots and the fixture, slots on a standby, Consul ACL `list` rule, server-start overrides, `andThen` for declared ERROR
logs, Windows quoting, Git Bash paths, stale IDE diagnostics, no Python, Jenkins). New on 2026-11-13:

- **One positions adapter per member.** The member session belongs to the adapter instance. A second adapter for the
  same member cannot acquire the key while the first adapter's session is live (`Consul did not store the member
  position under the member's session`). Each monitor holds one adapter; tests reuse the monitor for every publication.
- **Session timing.** Consul accepts session TTLs of 10 s to 86 400 s. A record can outlive its TTL by up to the TTL
  again; tests wait up to three TTLs.
- **ACL for publication.** A member token needs `session "<consul node>" { policy = "write" }` as well as write on its
  own key and read on the prefix.
- **Unexpected-ERROR gate in a contract.** A refusal before sending is logged under the adapter's own logger, so that
  case lives in the binding, not in the neutral contract.
- **Vert.x test-context timeout.** The extension reads only `io.vertx.junit5.Timeout` (method, then class; not
  inherited). Since 2026-11-14 each integration class declares it equal to its JUnit limit; a new class must do the same,
  and a concrete subclass of an abstract contract declares it itself.
- **Where to run probes and targeted tests.** First check whether the Jenkins host is available. If it is, run there
  through `Invoke-QraftJenkinsShell` (`.env/jenkins-tools.ps1`): a clone under `/var/lib/jenkins/peegeeq-work`, changed
  files copied in base64 chunks below 64 KB, long commands started with `nohup` and read from their log. Do not run there
  while a PeeGeeQ job build runs (shared `.m2`). If it is not available, run locally: Docker is available on every
  machine used in this project. (Corrected 2026-11-17: this line earlier said the workstation has no Docker.)
- **WAL receiver observations.** `pg_stat_wal_receiver` shows `streaming` with the sender address and slot before
  replication has started. Only `wait_event = WalReceiverMain` shows a receiver inside its streaming loop.
- **PostgreSQL 15.13 facts (observed in tests and probe 49).** After the writer is stopped, a standby has no
  `pg_stat_wal_receiver` row with a timeline, `pg_last_wal_receive_lsn()` keeps its last value, and
  `pg_control_checkpoint().timeline_id` was 1. With `pg_wal_replay_pause()`, the receiver keeps receiving while replay
  stays behind.
- **The `PgDurabilityControl` port grew a method.** Five test wrappers implement it (takeover, bootstrap serving, policy
  installation twice, promotion); each passes `candidatePosition` through.

New on 2026-11-15 (plan §9.32):

- **`pg_rewind` removes `standby.signal`** and copies the source's `postgresql.conf`, `postgresql.auto.conf`,
  `pg_hba.conf`, and `pg_ident.conf`. The 2026-11-15 record does not name `postgresql.conf`. A probe on 2026-11-24
  on 15.13 showed it: source and target had different ports, and after the rewind the target had the source's port
  (`logs/review-rewind-facts-probe2-20261124.log`). The adapter saves and restores every top-level `*.conf` file.
  `PgRejoinControlIntegrationTest` marks `postgresql.conf`, `pg_hba.conf`, and `pg_ident.conf` of the target, so a
  lost copy of either of the first two fails it (mutation probes M1 and M2, 2026-11-24). A base backup without `-R`
  writes no `standby.signal`. Either would start the node writable; the adapter recreates the inhibitor after both.
- **`pg_rewind` cannot complete crash recovery while `standby.signal` exists** ("standby mode is not supported by
  single-user servers"). The adapter starts the target in recovery with `primary_conninfo=''` and stops it fast first.
  A test of the rewind must put the inhibitor in place, as the monitor's stop does; without it `pg_rewind` recovers the
  target itself and the adapter's step is not exercised (probe 53).
- **`pg_ctl status` exit 4** is both "no such directory" and "not a database cluster directory". Absence is `test -e`.
- **A server started in recovery does not write its parameters to the control file.** To make a test target without
  `wal_log_hints`, run it once outside recovery and check `pg_controldata`.
- **Surefire `-Dtest`**: classes are separated by commas, methods of one class by `+`. `A#m+B#n` runs only `A#m`.
- **The IDE compiles into `target/` (seen 2026-11-21, probe 132).** `mvn test` then reports "Nothing to compile" and
  runs the IDE's class, not Maven's. Mutation probes use `mvn clean test`.
- **A Java regular expression with a repeated group recurses once per repetition (2026-11-21).** A long input then
  ends in `StackOverflowError`, not a refusal. Check repeated parts in a loop instead.
- **Python `os.walk` on Windows returns backslash paths.** A folder filter written for `/` can pass everything; check
  the output before trusting it.
- **Host tooling.** `.env/jenkins-tools.ps1` fails under Windows PowerShell 5.1 (`powershell.exe`,
  `NullReferenceException` in `Invoke-WebRequest`); call it from `pwsh.exe`. A poll loop must not send stderr to
  `/dev/null` and must have a deadline: the 2026-11-15 build-#40 poller looped for 12 hours. In a PowerShell sync
  script, keep a one-item file list an array (`@(...)`); splatting a string passes its characters.

New on 2026-11-17 and 2026-11-18 (plan §9.33):

- **Slow `docker exec` on the workstation.** During long runs single `docker exec` calls stall: `initdb` over 30 s
  (2.3 to 2.4 s alone), `pg_basebackup` over 30 s, and `psql` or `pg_ctl` past a short remaining budget. 12 of 396
  tests in the 2026-11-17 tier run failed this way and passed when rerun without the rest of the tier. The cause is
  not known. Read the cause of each failure before treating it as a defect. The tier log also holds
  `AccessDeniedException` traces on receipt `.tmp` files; those are the storage-fault tests, which place a directory
  where the store writes its temporary file and expect the error.
- **The gate has one more command.** Since RS1b, `PgHbaAdmissionGate.close` reads the configuration file errors after
  it installs the closed file and before the reload, inside its budget (the stop budget, 8 s in
  `PgFollowIntegrationTest` and `PgPromotionIntegrationTest`). `open` reads them before it installs the open file,
  inside the gate's command timeout.
- **Configuration error views.** `pg_file_settings` gives the same error text for a valid change pending a restart and
  for an invalid value; only `pg_settings.pending_restart` after a reload separates them (check 8). With a rejected
  `postgresql.conf`, a reload still applies `pg_hba.conf` but leaves the load time unchanged (check 9).
- **The builder grew a dependency.** Every test that builds a monitor passes `configuration(...)`; a new test class
  must too.
- **No renewal loop in `PgFailoverMonitorIntegrationTest`.** Its ownership lasts 12 s (TTL 20 s less the 8 s stop
  budget) from acquisition. A start path slowed by `docker exec` stalls outlasts it and the node is demoted ("Ownership
  was lost while PostgreSQL started"). Read the timing before treating that message as a defect.
- **Timing rule has one home (plan §9.34, CC1).** `PgClusterSettings` holds the lease and local-stop rule;
  `PgNodeConfig` calls it. Change the rule there only.
- **`PgBootstrap` takes a settings port (plan §9.34, CC3).** A test that builds a `PgBootstrap` passes a
  `PgClusterConfiguration` and closes it in teardown. A bootstrap on an incarnation whose settings record holds other
  values is refused.

New on 2026-11-18 (plan §9.35 checks):

- **Hot-standby parameters.** A standby whose `max_connections` is below the writer's does not start (checked for
  `max_connections` only; Patroni treats four more parameters the same way). A running standby pauses replay when the
  writer restarts with a higher value; it still flushes WAL and counts as a synchronous standby. Read
  `pg_get_wal_replay_pause_state()` before treating a stalled replay as a defect.
- **Backslashes in tool input.** A doubled backslash typed into a Bash tool command reached the shell as one. Build
  such bytes with `printf` octal escapes (`\134`) and check them with `od -c`.
- **`docker exec` paths from Git Bash.** Set `MSYS_NO_PATHCONV=1`, or Git Bash rewrites `/tmp/...` into a Windows path
  (seen 2026-11-18: `tail` opened `C:/Users/.../Temp/s.log`).
- **`pg_basebackup` without `-c fast`** was still running several minutes after the check 10 probe started at 09:48
  UTC, while the writer's checkpointer ran; the cause was not measured. Checks 11 and 12 used `-c fast` and finished in
  under a minute.

## 7. Not checked at handover

- The follow confirmation rests on PostgreSQL 15 behaviour: the REL_15_STABLE source and one 3000-sample probe on
  15.13. Other major versions were not checked.
- `twoCandidatesAtTheSamePositionAreBothPreferred` assumes both standbys received the same WAL before the writer stopped.
  `PgCandidacyIntegrationTest` ran 6 of 6 in each of Jenkins builds #36 to #39 (checked 2026-11-15). Whether it can be
  timing-dependent under heavier load was not measured.
- The items carried from 2026-11-12: re-join through an advanced slot, a lost slot on a standby, the production
  retention bound, the publication and advance cadence, Consul token provisioning outside tests, a second coordinator
  adapter, an armed watchdog, password-file provisioning, member address configuration, any production entry point, and
  a Jenkins build from `beginning` (the whole-repository gate). Takeover after real expiry is done (plan §9.29), and
  Jenkins job builds of the module ran (#36 to #41).
- The whole-repository gate cannot finish within the pipeline's 150-minute limit (`Jenkinsfile` line 48). In build #41
  the Java stage from `peegeeq-pg-failover` alone took 2 h 21 min. A build from `beginning` adds the modules before
  `peegeeq-pg-failover`. Changing the limit needs a `Jenkinsfile` commit by the owner.
- Re-join (plan §9.32): a real host loss and return; a large data directory (budget, spread checkpoint of
  `pg_basebackup`); a rebuild from a `clonefrom` member (OD-52); configuration files outside the data directory; the
  caller that decides to re-join (no supervisor loop); the re-join policy cutover.
- Restart and reload (plan §9.33): PostgreSQL versions other than 15.13; configuration files outside the data directory; a restart under a running renewal loop and an
  armed watchdog (`PgRestartIntegrationTest` has no renewal loop and runs watchdog mode `OFF`); who calls `runScheduledRestart` (no
  supervisor loop); the four as-built cases of §9.33.
- Coordinator configuration (plan §9.34): TLS to Consul (G-7); a Consul quorum of three servers; the supervisor loop
  that reads the settings before an acquisition; the operator's entry point to `update`.
- PostgreSQL parameters (plan §9.35): whether a paused standby can be promoted; the effect of a too-low
  `max_replication_slots` or `max_wal_senders`; the base64 file write through Java's process runner on Windows (the
  checks used Git Bash); whether `recovery_min_apply_delay` or a recovery target stops or delays a PostgreSQL 15
  standby's replay (review finding 2 is static reading, not reproduced).
- The 2026-11-13, 2026-11-17, and 2026-11-18 logs named in §3 are not in this checkout's `logs/` (checked 2026-11-20).
- PP1 (2026-11-21): PostgreSQL has not read a file rendered by `PgClusterParameters`; the escaping rests on the
  check 7 probe of 2026-11-18. No limit on name length (none is designed).
- The document review of 2026-11-21 read code and did not run it. Defects D4 and D10 to D12 in the register come from
  reading by review agents and need a probe. Java comments still name documents by paths archived before 2026-11-21
  (D13).
- Task 8 phase 5: the repaired `HaProxyConnectionFailoverTest` (`f391a7f0`) has never run in a Jenkins job build
  (checked 2026-11-15). On 2026-11-15 it ran on the Jenkins host at `b3f7ddc9`: 6 tests, 0 failures
  (archived register, Task 8 phase 5). The phase closes on a Jenkins build from `beginning`.
