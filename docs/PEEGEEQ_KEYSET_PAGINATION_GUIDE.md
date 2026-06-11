# PeeGeeQ Keyset Pagination — User and Technical Guide

**Scope**: bi-temporal event store queries (`peegeeq-bitemporal`), the REST events endpoint
(`peegeeq-rest`), and the Aggregate Stream page in the Management UI.
**Introduced**: 12 Jun 2026 (task F5, `AGGREGATE-STREAM-IMPROVEMENTS-PLAN-7-Jun-2026.md`).
**Status of this document**: standalone reference; candidate for integration into the main
PeeGeeQ documentation set.

---

## 1. What it is and why it exists

### The problem: offset drift

Offset pagination ("skip N rows, give me the next page") computes row positions at query time.
In an append-only event store, positions move whenever events are written:

- With **newest-first** ordering, every new event pushes all existing rows down one position.
  A reader who fetched page 1 and then requests `offset=10` re-reads rows they already saw.
- With **oldest-first** ordering, the reverse navigation direction has the same problem.

The result for a browsing operator: duplicated rows, or silently skipped rows, whenever events
arrive between page clicks. Nothing is wrong in the database — only the page boundaries moved.

### The fix: anchor on what you last saw

Keyset (cursor) pagination replaces "skip N rows" with "give me events strictly after the last
event I saw". The anchor is a concrete event — identified by its transaction time plus its event
ID — and that anchor does not move when new events are appended. Pages therefore never overlap
and never skip, regardless of concurrent writes.

The trade: navigation is **sequential**. You can go to the next page (anchored on the current
page's last event) and back to previously visited pages (their anchors are known), but you cannot
jump to an arbitrary page number, because "page 7" has no stable meaning in a moving dataset.

---

## 2. User guide

### 2.1 REST API

Endpoint: `GET /api/v1/eventstores/:setupId/:eventStoreName/events`

Cursor parameters (must be provided **together**):

| Parameter | Meaning |
|---|---|
| `afterTransactionTime` | The anchor event's transaction time |
| `afterEventId` | The anchor event's ID (tie-breaker for equal transaction times) |

Constraints:

- `sortOrder` must be `TRANSACTION_TIME_ASC` or `TRANSACTION_TIME_DESC`. Other sort orders
  have no stable cursor column and are rejected.
- Providing only one of the two cursor parameters → `400`.
- A `sortOrder` other than the two transaction-time orders together with a cursor → `400`
  ("Invalid query parameters: Keyset cursor (after) requires ...").

`afterTransactionTime` accepts **two formats**:

1. ISO-8601 instant: `2026-06-12T09:15:30.123456Z`
2. Epoch-seconds decimal: `1781193038.056218` — this is the API's **own serialization** of
   event timestamps, so the value can be copied verbatim from a previous response's
   `transactionTime` field. No client-side conversion needed.

#### Worked example

Page 1 — no cursor:

```
GET /api/v1/eventstores/my-setup/orders/events
        ?aggregateId=order-123&sortOrder=TRANSACTION_TIME_ASC&limit=10
```

```json
{
  "eventCount": 10,
  "totalCount": 23,
  "limit": 10,
  "offset": 0,
  "events": [
    { "eventId": "9a1f...", "transactionTime": 1781193038.056218, "...": "..." },
    { "...": "..." },
    { "eventId": "c44d...", "transactionTime": 1781193141.220504, "...": "..." }
  ]
}
```

Page 2 — anchor on the **last event of page 1** (here `c44d...`), sending its `transactionTime`
back exactly as received:

```
GET /api/v1/eventstores/my-setup/orders/events
        ?aggregateId=order-123&sortOrder=TRANSACTION_TIME_ASC&limit=10
        &afterTransactionTime=1781193141.220504&afterEventId=c44d...
```

The response contains the next 10 events strictly after the anchor. Repeat: each page's last
event is the next page's anchor.

#### Knowing when you are done

`totalCount` is the **full filtered total** (the cursor does not shrink it). Track progress
client-side: you are on the last page when the events you have consumed reach `totalCount`.

**Do not use `hasMore` in cursor mode.** `hasMore` is computed for offset pagination
(`offset + eventCount < totalCount`); with a cursor the offset is 0, so `hasMore` stays `true`
until the full set fits in one page. This is a documented limitation of the shared response
shape — cursor clients use `totalCount` arithmetic instead. Note also that `totalCount` is
re-computed per request, so it grows if events are appended mid-browse; treat it as
"total as of this page".

### 2.2 Management UI (Aggregate Stream page)

The event stream table pages with **Previous / Next** buttons and a "Page X of Y (N events)"
indicator. There is no jump-to-page control — that is deliberate (see §1). Behavior:

- Clicking an aggregate loads page 1 and resets the cursor trail.
- **Next** fetches the page after the current page's last event.
- **Previous** re-fetches an already-visited page using its recorded anchor.
- Events appended while you browse never cause a row you have seen to reappear on a later
  page; new events become visible at the chronological end of the stream.
- The page total is computed from `totalCount` at page-size 10 and may increase mid-browse
  if events arrive.

### 2.3 When to use offset vs cursor

| Use offset (`offset=N`) | Use cursor (`afterTransactionTime`/`afterEventId`) |
|---|---|
| Jump-to-arbitrary-page UX | Sequential browsing / scrolling |
| Data effectively static during browsing | Concurrent writers are expected |
| Any sort order | Transaction-time sort orders only |
| Tolerates occasional duplicate/skipped rows | Requires no-overlap/no-skip guarantees |

Both modes coexist on the same endpoint; a request uses whichever parameters it sends.

---

## 3. Technical guide

### 3.1 Java API (`peegeeq-api`)

`EventQuery` carries the cursor:

```java
EventQuery query = EventQuery.builder()
    .aggregateId("order-123")
    .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_ASC)
    .limit(10)
    .after(lastEvent.getTransactionTime(), lastEvent.getEventId())
    .build();
```

- `Builder.after(Instant, String)` — both arguments non-null (enforced); getters are
  `getAfterTransactionTime()` / `getAfterEventId()` returning `Optional`.
- A query with a cursor and a non-transaction-time `sortOrder` fails the returned `Future`
  with `IllegalArgumentException` — the error is surfaced, never silently ignored.
- `countEvents(EventQuery)` deliberately **ignores the cursor**: the count is the full
  filtered total, which is what pagination metadata needs.

### 3.2 SQL mechanics (`PgBiTemporalEventStore`)

The cursor compiles to a PostgreSQL **row-value comparison** appended to the WHERE clause:

```sql
-- TRANSACTION_TIME_ASC
AND (transaction_time, event_id) > ($n, $m)
-- TRANSACTION_TIME_DESC
AND (transaction_time, event_id) < ($n, $m)
```

and forces a cursor-matched ORDER BY:

```sql
ORDER BY transaction_time ASC,  event_id ASC   -- ASC cursor
ORDER BY transaction_time DESC, event_id DESC  -- DESC cursor
```

Why the two-column form matters:

- **Tie safety.** PostgreSQL timestamps have microsecond precision; two events can share a
  transaction time. Comparing on `transaction_time` alone would either skip or duplicate the
  tied rows at a page boundary. The row-value comparison `(t, id) > (anchor_t, anchor_id)`
  totally orders the stream: ties on time are broken by `event_id` (a `VARCHAR(255)` UUID,
  arbitrary but stable).
- **Order/comparison agreement.** The ORDER BY must sort by exactly the same column pair and
  direction as the comparison, otherwise the "next page" predicate and the row order disagree
  and rows can leak across boundaries. This is why the cursor path overrides the normal
  sort-order → ORDER BY mapping.

The cursor predicate is added in `query()` itself — **not** in the shared
`appendQueryCriteria()` helper — precisely so `countEvents()` (which uses the helper) keeps
returning the full filtered total.

### 3.3 Guarantees and non-guarantees

Guaranteed:

- A page fetched with a cursor contains no event from any earlier page of the same trail
  (no overlap), and no event between two consecutive pages is ever skipped — under any amount
  of concurrent appending. Events are immutable and append-only; an anchor comparison over
  immutable columns cannot shift.

Not guaranteed:

- **Stable totals**: `totalCount` reflects the store at the time of each request.
- **Arbitrary page access**: page N is only reachable through pages 1..N-1 (the UI keeps the
  visited anchors; an API client must do the same).
- **Cursor portability across sort directions**: an ASC anchor is meaningless for a DESC trail.

### 3.4 REST layer details (`EventStoreHandler`)

- `afterTransactionTime` + `afterEventId` are parsed in `parseQueryParameters`; providing one
  without the other → `400 "afterTransactionTime and afterEventId must be provided together"`.
- `parseInstantOrEpochSeconds` tries ISO-8601 first, then epoch-seconds decimal via
  `BigDecimal` (seconds + nanos). The dual format exists because the API serializes event
  timestamps as epoch-seconds decimals (Jackson `Instant` default in this codebase), and a
  cursor must round-trip the API's own output without client-side conversion.
- Applied cursor values are echoed in the response `filters` object — the `filters` object
  only ever claims filters that were actually applied.
- Precision note: epoch-decimal values survive double-typed JSON parsing down to microseconds
  (a microsecond epoch count fits in 2^53), which matches PostgreSQL timestamp precision —
  so JavaScript clients round-trip cursors losslessly.

### 3.5 UI implementation (`AggregateStreamPage.tsx`)

- Page size 10 (`EVENT_PAGE_SIZE`); sort `TRANSACTION_TIME_ASC` (chronological; the previous
  `VERSION_ASC` tied at version 1 for independent events and was not cursor-compatible).
- A `useRef` cursor trail: `eventsCursors[page]` = `{transactionTime, eventId}` of the last
  event on that page (the anchor for page+1); page 1 anchor is `null`; the trail resets when
  a new aggregate is selected.
- The AntD table's built-in pagination is disabled (`pagination={false}`); navigation is two
  buttons (`stream-prev-page`, `stream-next-page`) plus a status text
  (`stream-pagination-status`), all with test IDs.
- "Page X of Y" is `ceil(totalCount / pageSize)` — recomputed per fetch, so Y can grow
  mid-browse.

### 3.6 Test coverage (the proof)

| Layer | Test | What it proves |
|---|---|---|
| peegeeq-bitemporal | `PgBiTemporalEventStoreComplexTest.testQueryKeysetPaginationStableUnderConcurrentAppends` | Events appended **between** page fetches: cursor page 2 returns exactly the pre-anchor events, zero overlap (RED proved offset-style behavior returned the new events instead) |
| peegeeq-rest | `EventStoreIntegrationTest.testQueryEventsKeysetCursorPagination` | Cursor round-trips through HTTP using the response's own `transactionTime` value verbatim |
| e2e (UI) | `aggregate-stream.spec.ts` test 16 | Page 1 rows + "Page 1 of 2 (15 events)" status, Previous disabled |
| e2e (UI) | test 17 | Next issues a request with `afterEventId`/`afterTransactionTime` (not offset); Previous returns to page 1 |
| e2e (UI) | test 18 | 3 events appended mid-browse via the API: page 2 repeats nothing from page 1 (verified by diffing the captured API responses' event IDs) |

Verification at completion: `peegeeq-bitemporal` 122/122 (CORE) + 341/341 (INTEGRATION),
`EventStoreIntegrationTest` 47/47, e2e `aggregate-stream` project 49/49.

---

## 4. Quick reference

### Request parameters

| Parameter | Required | Format | Notes |
|---|---|---|---|
| `afterTransactionTime` | with `afterEventId` | ISO-8601 or epoch-seconds decimal | Copy from previous response's `transactionTime` |
| `afterEventId` | with `afterTransactionTime` | string (event ID) | Tie-breaker |
| `sortOrder` | yes, for cursor use | `TRANSACTION_TIME_ASC` \| `TRANSACTION_TIME_DESC` | Other values rejected with a cursor |
| `limit` | no (default 100, max 1000) | int | Page size |
| `offset` | ignore in cursor mode | int | Offset and cursor modes should not be mixed |

### Errors

| Condition | Response |
|---|---|
| Only one cursor parameter provided | `400` — "afterTransactionTime and afterEventId must be provided together" |
| Unparseable `afterTransactionTime` | `400` — "Invalid query parameters: ..." |
| Cursor with non-transaction-time `sortOrder` | `400` — "Invalid query parameters: Keyset cursor (after) requires TRANSACTION_TIME_ASC or TRANSACTION_TIME_DESC ..." |

### Client recipe (any language)

1. Fetch page 1 with `sortOrder=TRANSACTION_TIME_ASC&limit=N` (no cursor). Record `totalCount`.
2. Take the **last** event of the page; send its `transactionTime` (verbatim) and `eventId`
   as `afterTransactionTime` / `afterEventId` for the next page.
3. Stop when consumed events ≥ `totalCount` (do not rely on `hasMore` — see §2.1).
4. For "previous", re-use the anchors you recorded on the way forward.
