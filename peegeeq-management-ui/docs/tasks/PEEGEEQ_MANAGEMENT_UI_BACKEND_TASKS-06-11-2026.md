# PeeGeeQ Management UI - Backend Tasks for Remaining E2E Coverage Gaps

This document defines the backend work needed to close the two remaining gaps in
[PEEGEEQ_MANAGEMENT_UI_TEST_COVERAGE_GAPS-06-08-2026.md](./PEEGEEQ_MANAGEMENT_UI_TEST_COVERAGE_GAPS-06-08-2026.md)
(Consumer Groups: duplicate group name validation, and backfill IN_PROGRESS progress bar).

> **Validation note (2026-06-11)**: All findings below were verified against the current backend source,
> not the comments in the e2e specs. Notably, the spec comment claiming "the management API does not return
> `backfillStatus` in the consumer group listing" is **stale** — the field was added on 2026-06-07
> (commit `d75d48d9`). Task 2 is therefore mostly test-side work, not a backend blocker.

---

## Task 1: Reject duplicate consumer group names in the Management API (backend change)

**Status**: ✅ COMPLETED 2026-06-11 (TDD).

- **Backend test (RED)**: `testCreateDuplicateConsumerGroupReturns409` added to
  `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/ManagementApiIntegrationTest.java` (test 19) —
  failed with `expected: <409> but was: <201>` against the old upsert behaviour.
- **Backend change (GREEN)**: `ManagementApiHandler.createConsumerGroup()` now checks
  `subscriptionService.getSubscription(queueName, groupName)` and fails with
  `ResponseException(409, "Consumer group '<name>' already exists for queue '<queue>' in setup '<setup>'")`
  when an ACTIVE subscription exists — mirroring the `SubscriptionHandler.createSubscription` pattern.
  CANCELLED/PAUSED subscriptions are still re-creatable (matches `SubscriptionHandler` semantics).
- **Regression**: full `ManagementApiIntegrationTest` (23 tests) plus `ManagementApiHandlerTest`,
  `ManagementApiHandlerErrorTest`, and `QueuePauseResumeStatusIntegrationTest` (28 tests) — all green.
- **UI**: `ConsumerGroups.tsx` create-error handler now surfaces the backend error message via the
  established `error.response?.data?.error || error.message || fallback` pattern (as in `DatabaseSetups.tsx`).
- **E2E**: test 04 added to `consumer-groups-validation.spec.ts` (duplicate → 409 toast naming the group,
  modal stays open, exactly one table row); stale header comment corrected. Full
  `13b-consumer-groups-validation` chain (35 tests) passed.

### Current behaviour

- The UI creates consumer groups via `POST /api/v1/management/consumer-groups`
  (`ConsumerGroups.tsx`, line ~193), handled by
  `ManagementApiHandler.createConsumerGroup()`
  (`peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java`, line ~1264).
- That handler calls `subscriptionService.subscribe(queueName, groupName)` with **no duplicate check**.
- `SubscriptionManager.subscribe()`
  (`peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`, line ~158)
  issues `INSERT ... ON CONFLICT (topic, group_name) DO UPDATE` — an **upsert**. Creating a group whose
  name already exists silently succeeds with `201` and reactivates/updates the existing subscription
  (CANCELLED subscriptions stay CANCELLED).
- The DB already enforces uniqueness: `UNIQUE(topic, group_name)` in
  `peegeeq-db/src/main/resources/db/templates/base/08b-consumer-table-subscriptions.sql` (line ~23) —
  so duplicates never corrupt data; they are just never *reported* to the caller.

### Precedent in the codebase

The alternative create path already does this correctly:
`SubscriptionHandler.createSubscription()`
(`peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SubscriptionHandler.java`, line ~111,
endpoint `POST /api/v1/setups/:setupId/subscriptions/:topic`) checks `service.getSubscription(topic, groupName)`
first and returns **`409 CONFLICT`** when an ACTIVE subscription already exists (lines ~145-149).

### Required change

In `ManagementApiHandler.createConsumerGroup()`, mirror the `SubscriptionHandler` pattern:

1. Before calling `subscribe()`, look up the existing subscription for `(queueName, groupName)`.
2. If one exists in an ACTIVE (or PAUSED) state, fail with `409 CONFLICT` and a message naming the group.
3. Otherwise proceed with the existing `subscribe()` call (the upsert remains correct for the
   internal/programmatic subscribe path — only the Management API create endpoint changes).

**Decision needed**: behaviour for an existing CANCELLED subscription with the same name — reject with 409,
or allow re-creation (reactivation)? `SubscriptionHandler` only rejects ACTIVE; recommend matching that
for consistency unless the UI needs stricter semantics.

### Follow-up UI test work (after backend change)

- Add a duplicate-name e2e test to
  `peegeeq-management-ui/src/tests/e2e/specs/consumer-groups-validation.spec.ts`:
  create a group, attempt to create it again, assert the error toast appears and the table contains
  exactly one row for the name.
- Update the spec's header comment (lines ~8-13), which currently documents that the duplicate path
  is untestable.
- Verify `ConsumerGroups.tsx` surfaces the 409 as a user-readable error toast (it currently shows a
  generic create-failure message; including the backend message text would improve the assertion).

---

## Task 2: Backfill IN_PROGRESS progress bar — un-skip and extend tests (mostly test-side)

**Status**: backend listing support EXISTS since 2026-06-07; the skipped test's premise is stale.

### Verified current state

- `GET /api/v1/management/consumer-groups` **does** return `backfillStatus`,
  `backfillProcessedMessages`, and `backfillTotalMessages` per group —
  `ManagementApiHandler.getConsumerGroupsForSetup()`
  (`ManagementApiHandler.java`, lines ~807-813, added in commit `d75d48d9`).
- `ConsumerGroups.tsx` already maps these fields (lines ~104-109) and renders an `ant-progress` bar in
  the details modal when `backfillStatus === 'IN_PROGRESS'`.
- The skipped test in
  `peegeeq-management-ui/src/tests/e2e/specs/consumer-groups-scope-selectors.spec.ts` (lines ~282-287)
  cites the missing field as the skip reason — no longer true.
- Backfill state is persisted in `outbox_topic_subscriptions`
  (`08b-consumer-table-subscriptions.sql`, lines ~15-20: status CHECK constraint
  `NONE | IN_PROGRESS | COMPLETED | CANCELLED | FAILED`, checkpoint, processed/total counts) and driven by
  `BackfillService` (`peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`),
  which updates progress after each batch.

### Test-side work

1. **Un-skip / rewrite** the skipped test: action menu for a group with backfill `IN_PROGRESS` hides
   "Start Backfill" and the row shows the `IN_PROGRESS` tag.
2. **New test — progress bar rendering**: start a backfill, open "View Details" while
   `backfillStatus === 'IN_PROGRESS'`, assert the `.ant-progress` element is visible and reflects
   `backfillProcessedMessages / backfillTotalMessages`.
3. **Timing strategy**: the backfill must stay `IN_PROGRESS` long enough to observe. `BackfillService`
   processes batches with a configurable batch size and inter-batch delay — seed enough messages
   (and/or configure a small batch size with a delay) so the window is reliably observable; poll with
   `expect(...).toPass()` as the existing backfill specs do.

### Optional backend enhancement

The listing response omits `backfillStartedAt` and `backfillCompletedAt`, which `ConsumerGroups.tsx`
(lines ~108-109) already tries to map (they come back `undefined` today). `SubscriptionInfo`
(`peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/SubscriptionInfo.java`) carries both fields,
and the single-group response at `ManagementApiHandler.java` (lines ~2022-2024) shows the pattern.
Add both to the listing JSON in `getConsumerGroupsForSetup()` so the details modal can display
backfill timestamps without an extra fetch.

---

## Summary

| # | Task | Type | Blocker for coverage gap | Status |
|---|------|------|--------------------------|--------|
| 1 | Return `409 CONFLICT` for duplicate group names in `POST /api/v1/management/consumer-groups` | Backend change | Consumer Groups: duplicate name validation | ✅ Done 2026-06-11 |
| 2 | Un-skip stale test + add progress bar rendering test (backend support already shipped) | Test work (+ optional backend enhancement) | Consumer Groups: backfill IN_PROGRESS progress bar | Open |
