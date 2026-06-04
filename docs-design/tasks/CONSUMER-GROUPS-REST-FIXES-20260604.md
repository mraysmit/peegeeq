# Consumer Groups REST API Fixes

**Date:** 2026-06-04
**Scope:** `peegeeq-rest` — `ManagementApiHandler`, `PeeGeeQRestServer`; `peegeeq-management-ui` — `ConsumerGroups.tsx`
**Status:** Not started

---

## Summary

The Consumer Groups management REST API has two broken operations and two missing operations. The management UI has a matching set of problems from building against those broken endpoints. This task tracks the full implementation sequence.

---

## Reference Documents

| Document | What it covers |
|---|---|
| `docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20 | **Authoritative implementation plan.** Pre-work checklists, phase specifications, test discipline, verification steps for all four phases. Start here. |
| `docs-design/tasks/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md` | Frontend rewrite spec (Phase 2). Problem statement, actual API response shape, every field and column change required in `ConsumerGroups.tsx`. |
| `docs-design/dev/pgq-coding-principles.md` | Mandatory read before touching any Java file. |
| `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` | Mandatory read before writing or modifying any test. |
| `docs-design/dev/main-prompt.md` | Pre-work gate — all six steps must be completed before writing a single line of code per phase. |

---

## Problem Summary

| Route | Status | Root cause |
|---|---|---|
| `GET /api/v1/management/consumer-groups` | ✅ Correct | Reads `subscriptionService.listSubscriptions(topic)` |
| `POST /api/v1/management/consumer-groups` | ❌ Broken | Calls `queueFactory.createConsumerGroup(...)` and discards result; `subscriptionService.subscribe()` never called; no row written |
| `DELETE /api/v1/management/consumer-groups/:groupId` | ❌ Broken | Never calls `subscriptionService.cancel()`; single path param split on `-` is ambiguous; subscription row stays ACTIVE |
| Pause endpoint | ❌ Missing | No route registered; `subscriptionService.pause()` never called |
| Resume endpoint | ❌ Missing | No route registered; `subscriptionService.resume()` never called |

---

## Work Phases

### Phase 1 — Fix broken management REST endpoints

> Full specification in `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20, sub-sections 1a, 1b, 1c.

Files:
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java`

| Sub-task | Change |
|---|---|
| 1a | Change DELETE route from `/:groupId` to `/:setupId/:queueName/:groupName` |
| 1b | Fix `deleteConsumerGroup()`: read three path params, call `subscriptionService.cancel(queueName, groupName)` |
| 1c | Fix `createConsumerGroup()`: keep `queueFactory` lookup for 404 validation; replace `queueFactory.createConsumerGroup(...)` with `subscriptionService.subscribe(queueName, groupName)` |

Test gate: `mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260604b.txt`

> **Note:** The full `peegeeq` test suite (`-Pall-tests`) takes **more than 60 minutes** to run. Start it as a background/manual step; do not treat it as a blocking check within a single work session.
> When only one module was changed, test that module first: `mvn clean test -pl peegeeq-rest -Pall-tests 2>&1 | Tee-Object -FilePath logs\rest-tests-20260604.txt`. The full suite is still the final gate before declaring a phase complete.

### Phase 2 — Management UI rewrite

> Full specification in `CONSUMER-GROUPS-UI-REDESIGN-PLAN.md` Phase 2.

File:
- `peegeeq-management-ui/src/pages/ConsumerGroups.tsx`

Key changes: fix TypeScript interfaces, remove `Math.random()` fake data, fix status values, fix `queueName` mapping, wire delete to correct endpoint, fix table columns, fix summary cards, fix create modal (3 fields + queue Select), fix details modal.

Test gate:
```powershell
cd peegeeq-management-ui
npx playwright test --project=13-consumer-groups-scope-selectors --headed --reporter=list 2>&1 | Tee-Object -FilePath ..\logs\consumer-groups-20260604b.txt
```

### Phase 3 — Pause / Resume REST endpoints

> Full specification in `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20, Phase 3.

Files: same as Phase 1.

Add routes:
```
POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/pause
POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/resume
```

State machine: ACTIVE→PAUSED (pause), PAUSED→ACTIVE (resume). CANCELLED→anything is invalid (409).

Test gate: `mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260604c.txt`

### Phase 4 — Backfill REST endpoint

> Full specification in `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20, Phase 4.

Lower priority. `BackfillService` is fully implemented; this phase exposes it via a management route.

Add route:
```
POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill
```

---

## Completion Criteria

- [ ] Phase 1: `mvn clean test -Pall-tests` shows zero regressions
- [ ] Phase 1: POST creates a row in `outbox_topic_subscriptions` (verifiable in DB)
- [ ] Phase 1: DELETE sets `subscription_status = 'CANCELLED'` in DB
- [ ] Phase 2: Playwright `13-consumer-groups-scope-selectors` passes
- [ ] Phase 2: No `Math.random()` calls in `ConsumerGroups.tsx`
- [ ] Phase 2: No banned patterns introduced in any changed file
- [ ] Phase 3: Pause/resume round-trip verified manually and via tests
- [ ] Phase 3: `mvn clean test -Pall-tests` shows zero regressions
