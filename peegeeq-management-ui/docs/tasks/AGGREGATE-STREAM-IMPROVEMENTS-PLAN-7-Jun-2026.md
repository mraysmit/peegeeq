# Aggregate Stream — Improvement Plan

## Status: NOT COMPLETE — re-validated against the code 11 Jun 2026

- I1 (aggregate list truncation + pagination), I2 (enriched metadata), and I5 (Causation Tree
  cross-link) are implemented and covered by tests (verified at backend, REST, UI, and e2e levels).
- **I3 is only partially done**: the short-term fix shipped (truncation warning driven by the API's
  `hasMore` flag), but the full fix outlined below — `totalCount` on the event query response and
  API-driven page-by-page table pagination — was NOT implemented. `fetchAggregateEvents` still
  hardcodes `limit: 1000` (`AggregateStreamPage.tsx`), so events beyond the first 1,000 of an
  aggregate remain unreachable in the UI. Users are warned, but the data is still inaccessible.
- I4 (materialised aggregate summary table) remains explicitly deferred until scale demands it.

**Remaining work: the I3 full fix.** The 10 Jun status incorrectly declared I3 "fully implemented".
See "Remaining Work — I3 Full Fix: Task Breakdown" below.

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

| # | Task | Layer | Depends on |
|---|------|-------|------------|
| T1 | Apply offset in event query SQL | peegeeq-bitemporal | — |
| T2 | `countEvents(EventQuery)` | peegeeq-api / peegeeq-bitemporal | — |
| T3 | `totalCount` + exact `hasMore` in REST response | peegeeq-rest | T1, T2 |
| T4 | `EventQueryResult.totalCount` type | UI client | T3 |
| T5 | API-driven table pagination, remove 1000 cap | UI page | T3, T4 |
| T6 | E2E pagination tests | e2e specs | T5 |
| T7 | Re-run suites, flip status to COMPLETE | docs | T1–T6 |

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

**Scope:** `peegeeq-bitemporal` schema templates, `PgBiTemporalEventStore.java`

**When:** Only needed when a single event store contains hundreds of thousands of distinct aggregates. Not required for the initial improvements above. Implement after I1–I3 are in place and if profiling confirms query latency is a problem.

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
