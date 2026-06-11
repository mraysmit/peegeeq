# Aggregate Stream — Improvement Plan

## Status: PENDING — I4 open (owner decision 11 Jun 2026: no deferrals)

> Complete: I1, I2, I3 (incl. full fix), I5, T1–T7, F1–F6 — all implemented with TDD and
> verified. F5 (keyset pagination) completed 12 Jun 2026: cursor support in `EventQuery`
> (`after(transactionTime, eventId)`), `(transaction_time, event_id)` row-comparison SQL with
> cursor-matched ordering, REST `afterTransactionTime`/`afterEventId` params (accepting both
> ISO-8601 and the API's own epoch-decimal timestamp form), and sequential previous/next
> pagination in the Aggregate Stream UI. Verified: bitemporal 122/122 CORE + 341/341
> INTEGRATION, REST 47/47, e2e `aggregate-stream` 49/49 — including a mid-browse-append test
> proving page 2 never repeats page 1.
>
> **Pending: I4 (configurable materialised aggregate summary table with verify/rebuild
> reconciliation)** — design agreed, see the I4 section.
>
> Background on the F-tasks: while closing I3, a verification pass over the shared query path
> found that **six declared query criteria were silently ignored at the SQL level** — the same
> bug class as the ignored `offset` fixed in T1. F1–F4 and F6 were implemented the same day.
>
> F-task verification: `peegeeq-bitemporal` full suite 121/121 (CORE) + 341/341 (INTEGRATION);
> REST `EventStore*` suites 52/52; e2e `8b-events-filter` 46/46 (incl. new truncation test 15).

- I1 (aggregate list truncation + pagination), I2 (enriched metadata), and I5 (Causation Tree
  cross-link) are implemented and covered by tests (verified at backend, REST, UI, and e2e levels).
- **I3 full fix completed 11 Jun 2026** (the 10 Jun status had incorrectly declared I3 "fully
  implemented" when only the short-term truncation warning had shipped). The event stream is now
  paginated end to end: `EventQuery.offset` is applied in the SQL (it was previously silently
  ignored — a latent bug), `countEvents(EventQuery)` provides a real total, the REST response
  carries `totalCount` with an exact `hasMore`, and the UI table fetches page-by-page from the API.
  The hardcoded `limit: 1000` and the "stream may be truncated" warning are gone — every event of an
  aggregate is reachable. See the task breakdown below for the per-task evidence.
- I4 (materialised aggregate summary table): originally deferred until scale demands it; on
  11 Jun 2026 the owner re-prioritised it to **PENDING** — it is scheduled work (see the pending
  task table at the end of this document).

Verification on completion: `peegeeq-bitemporal` 117/117, `EventStoreIntegrationTest` 46/46,
e2e `aggregate-stream` 48/48 (incl. new pagination tests 16–17), `8-event-store-workflow` +
`9-event-visualization` regression 22/22.

---

## Remaining Work — I3 Full Fix: Task Breakdown (added 11 Jun 2026)

Verified current state that shapes these tasks:

- The UI client already sends `limit` and `offset` (`PeeGeeQClient.queryEvents`, `src/api/PeeGeeQClient.ts` ~line 352).
- The REST handler already parses `limit`/`offset` and echoes them back, but computes `hasMore` with the
  heuristic `eventResponses.size() == limit` (`EventStoreHandler.java` ~line 249) — false positive when the
  total is an exact multiple of the limit — and returns no `totalCount`.
- **Latent backend bug**: `EventQuery` carries `offset` (`peegeeq-api/.../EventQuery.java`), but
  `PgBiTemporalEventStore`'s query SQL applies only `LIMIT` (~lines 943-946) and **silently ignores the
  offset**. Paging via the existing API parameters returns the same first page every time.
- Only `AggregateStreamPage.tsx` consumes `hasMore` from this response, so correcting its computation
  breaks no other consumer.

Tasks in TDD order (each backend task: failing test first, then implementation, then green run):

### T1 — Apply `EventQuery.offset` in the event query SQL (bug fix)

**Scope:** `peegeeq-bitemporal/.../PgBiTemporalEventStore.java` (query SQL builder, ~line 943)
**Test first:** unit test in `PgBiTemporalEventStoreComplexTest` mirroring `testGetUniqueAggregatesPagination`:
append N events for one aggregate, query page 1 (`limit=k, offset=0`) and page 2 (`limit=k, offset=k`),
assert the pages do not overlap and ordering is stable. RED today because offset is ignored (page 2 == page 1).
**Change:** append `OFFSET $n` when `query.getOffset() > 0`, parameterized, mirroring the
`getUniqueAggregates` pattern (~lines 1185-1191).

### T2 — Total count for an event query

**Scope:** `peegeeq-api/.../EventStore.java`, `peegeeq-bitemporal/.../PgBiTemporalEventStore.java`
**Test first:** unit test asserting the count for a filtered query (same WHERE semantics, independent of
limit/offset).
**Change:** add a count capability for an `EventQuery` (e.g. `countEvents(EventQuery)`), reusing the same
WHERE-clause construction as the list query — the two-query approach already established by
`getUniqueAggregates` (count + list). No limit/offset in the count.

### T3 — REST response: `totalCount` + exact `hasMore`

**Scope:** `peegeeq-rest/.../EventStoreHandler.java` (query events handler, ~lines 239-252)
**Test first:** extend the REST integration coverage (pattern: `EventVisualizationIntegrationTest`) to assert
the response contains `totalCount`, and that `hasMore` is exact — including the boundary case where the
total is an exact multiple of the limit (`hasMore` must be `false`; the current heuristic returns `true`).
**Change:** call the count alongside the list query; respond with `totalCount` and
`hasMore = offset + eventCount < totalCount`. `limit`/`offset` are already echoed.

### T4 — UI client types

**Scope:** `peegeeq-management-ui/src/api/types.ts` (`EventQueryResult`)
**Change:** add `totalCount: number` to the event query result type (additive; `hasMore` stays).

### T5 — Drive the event stream table from the API page-by-page

**Scope:** `peegeeq-management-ui/src/pages/AggregateStreamPage.tsx`
**Change:**
- Remove the hardcoded `limit: 1000` in `fetchAggregateEvents` (~line 133); fetch with
  `limit = pageSize`, `offset = (page - 1) * pageSize`.
- Wire the AntD `Table` pagination: `total: totalCount`, `current`, `onChange` → re-fetch.
- Remove the "stream may be truncated" Alert and the `eventsTruncated` state — with real pagination every
  event is reachable and the pager itself shows the total. (The warning was the short-term I3 mitigation
  this work supersedes.)
- Reset to page 1 when the selected aggregate changes.

### T6 — E2E coverage

**Scope:** `peegeeq-management-ui/src/tests/e2e/specs/aggregate-stream.spec.ts`
**Add tests (existing spec patterns):**
- Seed more events than one page for a single aggregate (e.g. 15 with `pageSize` 10), open the stream,
  assert the pager shows the full total and page 1 row count.
- Navigate to page 2, assert different rows are shown and the request carried `offset=10`
  (request-param assertion, as in `queues-filter-sort.spec.ts`).

### T7 — Close out

- Re-run: backend unit (`PgBiTemporalEventStoreComplexTest`), REST integration, and the e2e projects
  touching the Aggregate Stream page (`aggregate-stream`, `8-event-store-workflow`,
  `9-event-visualization`).
- Update this document's status to COMPLETE only when T1–T6 are verified green.

| # | Task | Layer | Depends on | Status |
|---|------|-------|------------|--------|
| T1 | Apply offset in event query SQL | peegeeq-bitemporal | — | ✅ Done 2026-06-11 (TDD; `testQueryWithOffsetPagination`; module suite 117/117) |
| T2 | `countEvents(EventQuery)` | peegeeq-api / peegeeq-bitemporal | — | ✅ Done 2026-06-11 (TDD; `testCountEventsIgnoresLimitAndOffset`; shared `appendQueryCriteria` helper) |
| T3 | `totalCount` + exact `hasMore` in REST response | peegeeq-rest | T1, T2 | ✅ Done 2026-06-11 (TDD; `testQueryEventsPaginationMetadata` incl. exact-multiple boundary; `EventStoreIntegrationTest` 46/46) |
| T4 | `EventQueryResult.totalCount` type | UI client | T3 | ✅ Done 2026-06-11 (type now mirrors the response: `eventCount`/`totalCount`/`limit`/`offset`/`hasMore`; dead `total` field removed) |
| T5 | API-driven table pagination, remove 1000 cap | UI page | T3, T4 | ✅ Done 2026-06-11 (`fetchAggregateEvents(aggregateId, page)`, pager driven by `totalCount`, truncation Alert removed) |
| T6 | E2E pagination tests | e2e specs | T5 | ✅ Done 2026-06-11 (`aggregate-stream.spec.ts` tests 16–17: 15-event aggregate, page totals, `offset=10` request assertion; 48/48 passed) |
| T7 | Re-run suites, flip status to COMPLETE | docs | T1–T6 | ✅ Done 2026-06-11 (regression: 8-event-store-workflow + 9-event-visualization 22/22) |

---

## Follow-Up — Critical Findings (added 11 Jun 2026, NOT yet implemented)

Found by verifying the query path after the I3 work. Evidence verified against the code, not inferred.

**The core defect**: `EventStoreHandler.parseQueryParameters` / `buildEventQuery` parse and validate
`correlationId`, `sortOrder`, `includeCorrections`, `minVersion`, and `maxVersion` into the
`EventQuery` (EventStoreHandler.java ~lines 504–505, 540–544, 654–655, 689) and echo them back in the
response `filters` object — but `PgBiTemporalEventStore` contains **zero references** to
`getCorrelationId()`, `getSortOrder()`, `isIncludeCorrections()`, `getMinVersion()`, or
`getMaxVersion()`. None of the five reach the SQL. Clients are told their filters were applied;
they were not. Same bug class as the ignored `offset` fixed in T1.

A module audit on the same day (see `docs-design/tasks/OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md` for the
audit's outbox half) found a **sixth ignored criterion**: `EventQuery.headerFilters` — full builder
support (`EventQuery.java` lines 43, 165, 221), never applied to any SQL. Folded into F3 below. The
same audit verified the rest of peegeeq-bitemporal CLEAN on these defect classes: `subscribe`
genuinely filters by eventType/aggregateId, all append overloads propagate headers/correlation/
causation, `getStats()` computes every field from real SQL, and there is no exception string-matching
or test-aware code in the module.

### F1 — CRITICAL: honor `correlationId` in the event query SQL

- **Impact**: `CausationTreePage.tsx` (~line 193) queries events by `correlationId` to build the
  causation tree — and receives **unfiltered events** (everything in the store up to the limit).
  The tree-builder links nodes by `causationId`, so small test stores look correct, but in a real
  store the tree is built from the wrong dataset and can include unrelated roots. The I5 deep-link
  from Aggregate Stream lands on this broken filter.
- **Fix**: add `AND correlation_id = $n` to `appendQueryCriteria` (one change covers both `query()`
  and `countEvents()` — the shared helper exists precisely for this).
- **TDD**: unit test in `PgBiTemporalEventStoreComplexTest` mirroring `testQueryWithOffsetPagination`
  (two correlation IDs, query one, assert isolation — RED today); REST integration assertion;
  e2e: causation tree with two seeded correlation chains shows only the requested one.

### F2 — CRITICAL: honor `sortOrder` in the event query SQL

- **Impact**: the SQL hardcodes `ORDER BY transaction_time DESC, valid_time DESC`
  (`PgBiTemporalEventStore.query`). The Aggregate Stream requests `VERSION_ASC` and silently gets
  newest-first. The T5 pagination makes this user-visible: "page 1" of a stream currently shows the
  **latest** events, not version 1. Existing e2e tests pass only because their assertions are
  order-independent.
- **Fix**: map `EventQuery.SortOrder` (`VALID_TIME_ASC/DESC`, `TRANSACTION_TIME_ASC/DESC`,
  `VERSION_ASC/DESC`) to the ORDER BY clause; keep the current ordering as the default when
  unspecified.
- **TDD**: unit tests asserting row order per sort value (RED today for `VERSION_ASC`);
  e2e: stream page 1 starts at version 1 with `VERSION_ASC`.

### F3 — HIGH: honor `includeCorrections`, `minVersion`, `maxVersion`, and `headerFilters`

- **Impact**: parsed and documented in the API (the handler even lists valid `sortOrder` values in
  its 400 message) but ignored — correction filtering and version-range queries return wrong results.
  `headerFilters` (added by the 11 Jun module audit) is the same: full builder support in
  `EventQuery`, never applied to any SQL — header-based filtering silently returns everything.
- **Fix**: extend `appendQueryCriteria` (`version >= / <=`; exclude `is_correction` rows when
  `includeCorrections=false`; `headers @> $n::jsonb` or per-key `headers->>$k = $v` for
  `headerFilters` — pick whichever matches how headers are stored and indexed).
- **TDD**: unit tests per criterion (RED today).

### F4 — HIGH: EventsPage silent truncation (sibling of the original P3)

- **Impact**: `EventsPage.tsx` (~line 138) hardcodes `events?limit=1000` — no `totalCount` use, no
  warning, no pagination. Identical to the P3 problem fixed for the Aggregate Stream; the backend
  support (T2/T3 `totalCount` + exact `hasMore`) already exists, the page just ignores it.
- **Fix**: mirror T4/T5 — drive the events table page-by-page from the API (or, minimum, surface the
  truncation with `totalCount`).
- **TDD**: e2e in `events-filter.spec.ts` following the aggregate-stream tests 16–17 pattern.

### F5 — PENDING: keyset pagination for the event stream (owner decision 11 Jun 2026: implement, do not defer)

- **Problem**: offset-based pages shift when events are appended mid-browse — an event added while
  viewing page 1 changes what page 2 contains, so a browsing operator can see duplicated rows (or
  skip rows, depending on sort direction).
- **Fix**: keyset pagination — fetch "events after the last seen anchor" (e.g.
  `transaction_time/version of the last row on the current page`) instead of `OFFSET n`. The anchor
  does not move when new rows arrive, so pages never overlap or skip.
- **Scope**: backend (anchor-based variant of the event query — the `appendQueryCriteria` helper is
  the natural seam), REST (cursor parameters alongside or replacing `offset`), UI (the AntD table's
  free page-jumping must be constrained to next/previous or cursor-tracked pages).
- **TDD**: backend unit test appending events between page fetches and asserting no overlap/skip;
  e2e extension of `aggregate-stream.spec.ts` tests 16–17.

### F6 — LOW: subscriber callback failures in `ReactiveNotificationHandler` are log-only

- `ReactiveNotificationHandler.notifySubscription()` (peegeeq-bitemporal, ~lines 801–816) invokes
  subscriber handlers fire-and-forget: `handler.handle(message).onFailure(log)`. A failing handler is
  logged but nothing else observes it — no retry, no dead-subscriber escalation, no metric.
- This is the accepted terminal-observer pattern for pub-sub fan-out (per the testing standards doc),
  so it is not a correctness bug. The gap is observability: persistent handler failures never
  escalate. Fix when touched: add a consecutive-failure counter with WARN→ERROR escalation, the same
  pattern `DeadConsumerDetectionJob` uses for its timer failures.

### Order

F1 → F2 → F3 share the same `appendQueryCriteria`/ORDER BY change surface — one TDD pass each, in
that order (F1 fixes a shipped feature's correctness; F2 is made user-visible by T5). F4 follows
(consumes existing backend support). F5 is a recorded decision.

| # | Task | Severity | Layer | Status |
|---|------|----------|-------|--------|
| F1 | Honor `correlationId` filter in SQL | CRITICAL | peegeeq-bitemporal (+ e2e) | ✅ Done 2026-06-11 (TDD; `testQueryFiltersByCorrelationId`) |
| F2 | Honor `sortOrder` in SQL | CRITICAL | peegeeq-bitemporal (+ e2e) | ✅ Done 2026-06-11 (TDD; `testQueryHonorsSortOrder`; all six SortOrder values mapped — this also makes the declared default `TRANSACTION_TIME_ASC` real, replacing the hardcoded DESC) |
| F3 | Honor `includeCorrections` / `minVersion` / `maxVersion` / `headerFilters` | HIGH | peegeeq-bitemporal | ✅ Done 2026-06-11 (TDD; `testQueryHonorsIncludeCorrectionsAndVersionRange`, `testQueryFiltersByHeaders`; shared `appendQueryCriteria` covers `countEvents` too) |
| F4 | EventsPage truncation surfaced via `totalCount` | HIGH | UI (+ e2e) | ✅ Done 2026-06-11 — **minimum variant by design**: the page's filters are client-side over the loaded set, so server-side page-by-page fetching would silently change filter semantics. Truncation Alert + "Loaded X of Y" message added; e2e test 15 (`events-filter.spec.ts`) covers it via response patching. Full server-side paging requires moving the filters server-side first (now possible since F1–F3) — record as future work if needed. |
| F5 | Keyset pagination for the event stream (no overlap/skip under concurrent appends) | LOW | peegeeq-bitemporal + REST + UI | ✅ Done 2026-06-12 (TDD at all three layers; `testQueryKeysetPaginationStableUnderConcurrentAppends`, `testQueryEventsKeysetCursorPagination`, e2e tests 16–18). Reference: `docs/PEEGEEQ_KEYSET_PAGINATION_GUIDE.md` |
| I4 | Materialised aggregate summary table (trigger-maintained; replaces query-time `GROUP BY`) | HIGH at scale | peegeeq-bitemporal + schema templates | **PENDING** (owner decision 11 Jun 2026: re-prioritised from deferred) |
| F6 | Notification handler failures — consecutive-failure escalation | LOW | peegeeq-bitemporal | ✅ Done 2026-06-11 (TDD; `testHandlerFailuresEscalateWarnToError`; WARN below 3 consecutive failures, ERROR with count at 3+, counter resets on success — same idiom as `PeeGeeQManager` timer failures) |

### Test fixes applied alongside this work (10 Jun 2026)

The aggregate stream changes (row-click navigation replacing "View Stream" button) caused three
pre-existing specs in other projects to break. All were fixed:

| File | Fix |
|------|-----|
| `event-store-workflow.spec.ts` | `getByText('View Stream').click()` → `aggRow.click()` |
| `event-visualization.spec.ts` | same |
| `take-screenshots.spec.ts` | same |
| `queues-setup-selector.spec.ts` | 5× `waitForLoadState('networkidle')` → `'load'` (SSE keeps page active) |
| `consumer-groups-scope-selectors.spec.ts` | `IN_PROGRESS` backfill test marked `test.skip` — recorded reason ("management API does not return `backfillStatus` in the listing") was already false at the time (field shipped 2026-06-07, commit `d75d48d9`). The skip was removed and the test rewritten on 2026-06-11. |

Full re-run of all 5 affected projects: **151 passed, 1 skipped** (exit code 0).

---

## Background

The Aggregate Stream page (`AggregateStreamPage.tsx`) lets users browse the event history for a specific aggregate within a bi-temporal event store. An aggregate is not a declared or registered concept in PeeGeeQ — it exists implicitly as a distinct `aggregate_id` value on event rows. The page discovers aggregates on demand via `SELECT DISTINCT aggregate_id` and then fetches all events for the selected one.

This design works correctly at small scale but has several correctness, usability, and performance problems that will become significant as event stores grow.

---

## Problems

### P1 — Silent truncation of aggregate list (correctness, HIGH)

`getUniqueAggregates` in `PgBiTemporalEventStore` hardcodes `LIMIT 1000`:

```java
sql.append(" ORDER BY aggregate_id LIMIT 1000");
```

The REST handler and the UI receive no indication that results were truncated. A store with 1,200 aggregates silently shows only 1,000. There is no `truncated` flag, no `totalCount`, and no pagination support at the API or UI layer.

**Risk:** Operators investigating a missing aggregate may conclude it does not exist when it is simply beyond the limit.

---

### P2 — Aggregate list carries no metadata (usability, HIGH)

The aggregate list in the left panel is a flat list of ID strings. Users have no way to assess relevance before clicking:

- How many events does this aggregate have?
- When was the last event recorded?
- What event types are present?

A store with hundreds of aggregates named `order-1`, `order-2` … becomes impossible to navigate without clicking each one.

---

### P3 — Event stream fetch is unbounded (correctness, MEDIUM)

`fetchAggregateEvents` hardcodes `limit: 1000` regardless of the actual stream length:

```typescript
const response = await peeGeeQClient.queryEvents(selectedSetupId, selectedEventStore, {
    aggregateId,
    limit: 1000,
    ...
})
```

The table renders with `pageSize: 10` but all 1,000 rows are fetched upfront. A long-lived aggregate (e.g. an account with years of daily transactions) may silently truncate its stream at 1,000 events, and the UI shows no indication this has happened.

---

### P4 — `getUniqueAggregates` does not scale (performance, MEDIUM)

`SELECT DISTINCT aggregate_id` requires a full index scan over the `aggregate_id` column at query time. As event volumes grow this becomes progressively slower. There is no materialised summary, no count pre-computation, and no background maintenance of aggregate metadata.

At 10M events with 100k distinct aggregates, this query will be slow enough to time out on a busy system.

---

### P5 — Aggregate Stream and Causation Tree are disconnected (usability, LOW)

Both pages visualise the same underlying event data from different angles:

- **Aggregate Stream** answers: *"what happened to entity X over time?"*
- **Causation Tree** answers: *"what chain of events did action Y trigger?"*

When investigating an aggregate's history, the natural next question is often *"what caused this particular event?"* There is no navigation path between the two pages. Users must manually copy a `correlationId` and paste it into the Causation Tree page.

---

## Proposed Improvements

### I1 — Surface truncation and paginate the aggregate list

**Scope:** `PgBiTemporalEventStore.java`, `EventStoreHandler.java`, `AggregateStreamPage.tsx`

**Backend changes:**

Add `limit` and `offset` query parameters to `GET /api/v1/eventstores/:setupId/:eventStoreName/aggregates`. Return a `truncated` flag and `totalCount` alongside the `aggregates` array:

```json
{
  "aggregates": ["order-123", "order-124"],
  "count": 2,
  "totalCount": 1247,
  "truncated": true,
  "limit": 1000,
  "offset": 0
}
```

`totalCount` comes from a `SELECT COUNT(DISTINCT aggregate_id) FROM {table}` executed in the same request.

**UI changes:**

- Show a warning banner when `truncated: true`: *"Showing 1,000 of 1,247 aggregates. Use the event type filter to narrow results."*
- Add a Load More button (or AntD `Pagination` below the list) driven by `limit` + `offset`.

---

### I2 — Enrich the aggregate list with metadata

**Scope:** `PgBiTemporalEventStore.java`, `EventStoreHandler.java`, `AggregateStreamPage.tsx`

**Backend changes:**

Replace the `SELECT DISTINCT` query with a `GROUP BY` query that returns per-aggregate metadata in a single round-trip:

```sql
SELECT
    aggregate_id,
    COUNT(*)                    AS event_count,
    MIN(valid_from)             AS first_event_time,
    MAX(transaction_time)       AS last_event_time,
    array_agg(DISTINCT event_type) AS event_types
FROM {table}
WHERE aggregate_id IS NOT NULL
[AND event_type = $1]
GROUP BY aggregate_id
ORDER BY last_event_time DESC
LIMIT $n OFFSET $m
```

Return the enriched shape from the API:

```json
{
  "aggregates": [
    {
      "aggregateId": "order-123",
      "eventCount": 14,
      "firstEventTime": "2026-01-15T09:00:00Z",
      "lastEventTime": "2026-06-01T14:32:11Z",
      "eventTypes": ["OrderCreated", "OrderShipped", "OrderDelivered"]
    }
  ]
}
```

**UI changes:**

Replace the two-column table (Aggregate ID / Actions) with a richer list showing event count, last active time, and event type tags. Clicking any row still loads the stream — the detail is just available without the extra click.

---

### I3 — Paginate the event stream fetch

**Scope:** `AggregateStreamPage.tsx`

Wire the AntD `Table`'s `onChange` pagination callback to re-fetch from the API with the correct `limit` and `offset`, rather than fetching all events upfront. Show a truncation warning if the total count exceeds the hardcoded 1,000 limit while the proper pagination is not yet in place.

Short-term (low effort):
- After the fetch, if `response.events.length === 1000`, display: *"Showing first 1,000 events. Stream may be truncated."*

Full fix (medium effort):
- Add a `totalCount` to the event query API response.
- Drive table pagination from the API page-by-page.

---

### I4 — Materialised aggregate summary table

**Scope:** `peegeeq-bitemporal` schema templates, `PgBiTemporalEventStore.java`, `EventStoreConfig`
(peegeeq-api), `PeeGeeQDatabaseSetupService` (peegeeq-db), `EventStoreHandler` (peegeeq-rest)

**Status (11 Jun 2026): PENDING — re-prioritised by owner decision.** Originally deferred until a
single event store contained hundreds of thousands of distinct aggregates; the owner decided not to
defer. I1–I3 are in place, so the prerequisite work is done.

**Configurability requirement (added 11 Jun 2026; design reviewed and corrected 12 Jun 2026)**:
the summary table is opt-in per event store, and both query paths (live `GROUP BY` over the event
log, and the summary table) must remain queryable — the option selects the default, not the only
path.

> **Review corrections (12 Jun 2026)**: the first draft of this design had two correctness flaws,
> fixed below: (a) a single summary row per aggregate cannot honor the `eventType` filter with
> correct metadata — the summary is now keyed by `(aggregate_id, event_type)`; (b) "install +
> rebuild in one transaction" misses events written during the transaction — enablement is now an
> explicit two-transaction sequence. Concurrency rules for rebuild/verify were also pinned down.

1. **Creation option** — `EventStoreConfig` gains `aggregateSummaryEnabled` (default `false`,
   fully backward compatible), flowing exactly like the existing `biTemporalEnabled` flag: setup
   request JSON → Jackson-bound `EventStoreConfig` → setup service. When enabled, setup applies an
   additional SQL template via the same mechanism that creates the events table
   (`templateProcessor.applyTemplate(connection, "eventstore-aggregate-summary", params)` with the
   same `tableName`/`schema` params, schema-qualified — see
   `OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md` for why qualification is explicit). Creation-time
   enablement needs no backfill: the store is empty.

2. **Summary schema — keyed by `(aggregate_id, event_type)`.** The live query computes
   `event_count`/`first_event_time`/`last_event_time` over the rows that survive the optional
   `event_type` filter — so per-type metadata must be stored per type, or filtered queries return
   wrong counts. One summary row per `(aggregate_id, event_type)`:

   ```sql
   CREATE TABLE {schema}.{tableName}_aggregate_summary (
       aggregate_id    VARCHAR(255) NOT NULL,
       event_type      VARCHAR(255) NOT NULL,
       event_count     BIGINT       NOT NULL DEFAULT 0,
       first_event_at  TIMESTAMPTZ,
       last_event_at   TIMESTAMPTZ,
       PRIMARY KEY (aggregate_id, event_type)
   );
   ```

   Trigger (fires on every insert, corrections included — matching the live `GROUP BY`, which
   counts all rows). Note `LEAST`/`GREATEST` on conflict: bi-temporal valid times arrive out of
   order by design, so `first_event_at` must be able to move backwards; the column is
   `valid_time`, not `valid_from`:

   ```sql
   INSERT INTO {schema}.{tableName}_aggregate_summary
       (aggregate_id, event_type, event_count, first_event_at, last_event_at)
   VALUES (NEW.aggregate_id, NEW.event_type, 1, NEW.valid_time, NEW.transaction_time)
   ON CONFLICT (aggregate_id, event_type) DO UPDATE SET
       event_count    = {tableName}_aggregate_summary.event_count + 1,
       first_event_at = LEAST({tableName}_aggregate_summary.first_event_at, NEW.valid_time),
       last_event_at  = GREATEST({tableName}_aggregate_summary.last_event_at, NEW.transaction_time);
   ```

   The summary listing reconstructs the API shape with a cheap aggregation over the (small)
   summary, replicating the live ordering and the 1000 cap exactly:
   `SELECT aggregate_id, SUM(event_count), MIN(first_event_at), MAX(last_event_at),
   array_agg(DISTINCT event_type) ... [WHERE event_type = $1] GROUP BY aggregate_id
   ORDER BY MAX(last_event_at) DESC NULLS LAST LIMIT/OFFSET`. No array maintenance in the
   trigger — `event_types` is derived at query time.

3. **Dual query paths** — `EventStore` gains an `AggregateSource` enum: `AUTO` (summary when the
   store was created with one, live otherwise — the default), `EVENT_LOG` (force the live
   `GROUP BY`), `SUMMARY` (force the summary; fails with a clear error if the store has none).
   The existing 3-arg `getUniqueAggregates(eventType, limit, offset)` keeps its signature and
   means `AUTO`; a new overload takes the source. Both paths return the same
   `AggregateListResult` shape. `AUTO` is decided by a construction-time flag, not by runtime
   table-sniffing.

4. **Wiring (verified against the code)** — `EventStoreFactory.createEventStore(Class, String)`
   never receives `EventStoreConfig`; the setup service has the config at the call site
   (`PeeGeeQDatabaseSetupService.createEventStores`, ~line 880) but passes only the table name.
   Required: a config-taking overload on the `EventStoreFactory` interface (peegeeq-api, with a
   default method delegating to the existing signature so other implementers don't break),
   implemented by `BiTemporalEventStoreFactory`, reaching a new `PgBiTemporalEventStore`
   constructor that records the flag. Direct constructions (tests, programmatic use) default to
   summary-disabled.

5. **REST exposure** — optional `source=log|summary` query parameter on
   `GET /eventstores/:setupId/:name/aggregates` (absent = AUTO); invalid value → 400;
   `summary` on a store without a summary table → 400 naming the `aggregateSummaryEnabled` option.

6. **Reconciliation operation — one mechanism, two purposes.** The event log is the source of
   truth; the summary is derived state; the live `GROUP BY` (per `(aggregate_id, event_type)`)
   is both the verifier and the rebuilder. API: `reconcileAggregateSummary(mode)`; REST:
   `POST /eventstores/:setupId/:name/aggregate-summary/reconcile?mode=verify|rebuild`.
   - **`verify`** — diff live vs summary and return a report; changes nothing. **Concurrency
     rule**: both reads execute in a single `REPEATABLE READ` transaction — the trigger commits
     atomically with its event insert, so any snapshot is internally consistent; separate
     READ COMMITTED reads would report false drift under load.
   - **`rebuild`** — `INSERT ... SELECT ... GROUP BY aggregate_id, event_type` from the event log
     with `ON CONFLICT (aggregate_id, event_type) DO UPDATE` (overwriting counts/timestamps) plus
     deletion of orphaned summary rows, in one transaction that first takes
     `LOCK TABLE {summary} IN EXCLUSIVE MODE`. **The lock is required for correctness**: without
     it, a concurrent trigger increment between the rebuild's snapshot and its upsert is
     overwritten with a stale count. Event appends block briefly on their trigger write while a
     rebuild runs — accepted and documented.
   - **Result shape** (API object and REST JSON):
     `{ mode, aggregatesChecked, missingInSummary, staleInSummary, orphanedInSummary,
        repaired (rebuild only), timestamp }` — counts plus a bounded sample of affected
     `(aggregateId, eventType)` pairs for diagnosis.

7. **Enabling on a store that already has events — two transactions, in this order.**
   Txn A: create the summary table + install the trigger (commit). From this moment every new
   event maintains the summary. Txn B: run `rebuild` (with the EXCLUSIVE lock), which fills in
   all history — including any events written between A and B, since the rebuild reads the full
   event log. A single combined transaction is **incorrect**: the uncommitted trigger does not
   fire for concurrent inserts, and those events can also be invisible to the rebuild's snapshot
   — they would be permanently missing from the summary.

**TDD order**: (1) config round-trip test for the new flag; (2) setup-with-flag creates table +
trigger via the template (integration); (3) trigger maintenance correctness — per-type rows,
corrections counted, `first_event_at` moving backwards on an out-of-order valid time;
(4) **parity tests** — both sources return identical `AggregateListResult`s for the same store
and inputs, **both unfiltered and with an `eventType` filter** (the filtered case is what the
keying exists for); (5) source-override semantics (forced `EVENT_LOG` on a summary store;
`SUMMARY` on a non-summary store fails cleanly); (6) `verify` reports deliberately-introduced
drift (direct SQL tamper) and `rebuild` repairs it — verify-after-rebuild reports clean;
(7) enable-on-existing-store: txn A + txn B sequence produces a summary identical to the live
query, including events appended between A and B; (8) REST `source` and `reconcile` endpoint
tests.

Create a summary table maintained by a PostgreSQL trigger:

```sql
CREATE TABLE {schema}.{name}_aggregate_summary (
    aggregate_id    TEXT        NOT NULL PRIMARY KEY,
    event_count     BIGINT      NOT NULL DEFAULT 0,
    first_event_at  TIMESTAMPTZ,
    last_event_at   TIMESTAMPTZ,
    CONSTRAINT aggregate_summary_id_nonempty CHECK (aggregate_id <> '')
);

CREATE OR REPLACE FUNCTION {schema}.maintain_{name}_aggregate_summary()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO {schema}.{name}_aggregate_summary (aggregate_id, event_count, first_event_at, last_event_at)
    VALUES (NEW.aggregate_id, 1, NEW.valid_from, NEW.transaction_time)
    ON CONFLICT (aggregate_id) DO UPDATE SET
        event_count  = {schema}.{name}_aggregate_summary.event_count + 1,
        last_event_at = GREATEST({schema}.{name}_aggregate_summary.last_event_at, NEW.transaction_time);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER {name}_aggregate_summary_trigger
AFTER INSERT ON {schema}.{name}_events
FOR EACH ROW
WHEN (NEW.aggregate_id IS NOT NULL)
EXECUTE FUNCTION {schema}.maintain_{name}_aggregate_summary();
```

`getUniqueAggregates` then queries the summary table instead of the event log — O(1) lookup, no index scan.

---

### I5 — Cross-link Aggregate Stream to Causation Tree

**Scope:** `AggregateStreamPage.tsx`

Add a **"View causation tree"** action button to each row in the event stream table. Clicking it navigates to the Causation Tree page pre-populated with that event's `correlationId`:

```typescript
// In eventStreamColumns actions:
<Button
    type="link"
    icon={<BranchesOutlined />}
    onClick={() => navigate(`/causation-tree?correlationId=${record.correlationId}&setupId=${selectedSetupId}&eventStore=${selectedEventStore}`)}
    disabled={!record.correlationId}
>
    Causation Tree
</Button>
```

The Causation Tree page would need to read these query params on mount and auto-populate its selectors. This is a two-page change but no backend work is required.

---

## Implementation Priority

| # | Improvement | Effort | Impact | Prerequisites |
|---|---|---|---|---|
| I1 | Surface truncation + paginate aggregate list | Small | High — correctness fix | None |
| I3 | Event stream truncation warning | Tiny | Medium — correctness | None |
| I2 | Enrich aggregate list with metadata | Medium | High — usability | I1 (shares the query change) |
| I5 | Cross-link to Causation Tree | Small | Medium — usability | None |
| I4 | Materialised aggregate summary table | Large | High at scale | I1, I2, schema migration tooling |

I1 and I3 are pure correctness fixes and should be done first — they prevent operators from being misled by silently incomplete data. I2 builds naturally on I1 since both touch the same query. I5 is independent and low risk. I4 is an infrastructure investment for when scale demands it.

---

## Files Affected

| File | Changes |
|---|---|
| `peegeeq-bitemporal/src/main/java/.../PgBiTemporalEventStore.java` | I1, I2, I3, I4 |
| `peegeeq-api/src/main/java/.../EventStore.java` | I1, I2 (interface change) |
| `peegeeq-rest/src/main/java/.../EventStoreHandler.java` | I1, I2 |
| `peegeeq-management-ui/src/pages/AggregateStreamPage.tsx` | I1, I2, I3, I5 |
| `peegeeq-management-ui/src/api/PeeGeeQClient.ts` | I1, I2 (new response shape) |
| Schema SQL templates | I4 only |
