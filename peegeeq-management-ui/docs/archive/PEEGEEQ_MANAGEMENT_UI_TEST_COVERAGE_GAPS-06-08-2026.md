# PeeGeeQ Management UI - E2E Test Coverage Gaps

This document provides a detailed breakdown of the features and user flows implemented in the [peegeeq-management-ui](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui) codebase that are **not** currently covered by the Playwright E2E test suite.

> **Validation note (2026-06-06)**: All gaps were cross-checked against the source components and existing test specs. Inaccurate entries have been removed or corrected.
>
> **Re-validation note (2026-06-11)**: Every gap was re-verified against assertion-level evidence in the actual spec files. The detail sections below have been synced with the covering specs. Both remaining Consumer Groups items were closed the same day — duplicate-name validation (backend 409 + e2e test) and the backfill IN_PROGRESS progress bar (route-interception e2e tests + backend timestamp fields). See `PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md`.

---

## Progress Summary (validated 2026-06-11)

**29 of 29 gaps closed. 0 still open.**

| Page | Total Gaps | ✅ Closed | 🟡 Partial | ❌ Open |
|------|-----------|----------|-----------|--------|
| System Overview | 4 | 4 | 0 | 0 |
| Database Setups | 3 | 3 | 0 | 0 |
| Queues | 3 | 3 | 0 | 0 |
| Queue Details | 4 | 4 | 0 | 0 |
| Consumer Groups | 3 | 3 | 0 | 0 |
| Event Stores | 2 | 2 | 0 | 0 |
| Events | 3 | 3 | 0 | 0 |
| Causation Tree | 2 | 2 | 0 | 0 |
| Aggregate Stream | 2 | 2 | 0 | 0 |
| Message Browser | 2 | 2 | 0 | 0 |
| Settings | 1 | 1 | 0 | 0 |
| **Total** | **29** | **29** | **0** | **0** |

### All gaps at a glance

- [x] Overview: Recent Activity table rows + status tag colours (`overview-recent-activity.spec.ts` tests 02–03)
- [x] Overview: Live WebSocket `system_stats` events update stats cards/charts
- [x] Overview: Queue Overview table filtered items + "View All" link (`overview-recent-activity.spec.ts` tests 05, 07)
- [x] Overview: WS/SSE reconnecting banner (gold tags) (`overview-reconnecting-banner.spec.ts`)
- [x] Database Setups: Delete confirmation modal shows affected queues/event stores
- [x] Database Setups: Port range validation (values outside 1–65535) (`database-setup-form-defaults.spec.ts`)
- [x] Database Setups: Form field default values (Host=localhost, Port=5432, Username=peegeeq, Schema=public) (`database-setup-form-defaults.spec.ts`)
- [x] Queues: Search box ("Search queues...") (`queues-filter-sort.spec.ts` test 01)
- [x] Queues: Type / Status multi-select filters (`queues-filter-sort.spec.ts` tests 03–04)
- [x] Queues: Column sorting (`queues-filter-sort.spec.ts` tests 05–07)
- [x] Queue Details: Get Messages modal + messages table
- [x] Queue Details: View payload JSON popup
- [x] Queue Details: Pause Queue confirm + POST
- [x] Queue Details: Resume Queue confirm + POST
- [x] Queue Details: Purge Messages confirm + POST + toast
- [x] Queue Details: Delete Queue via UI + navigate away
- [x] Consumer Groups: View Details modal
- [x] Consumer Groups: Backfill IN_PROGRESS progress bar (closed 2026-06-11 — skipped test rewritten + progress bar test added in `consumer-groups-scope-selectors.spec.ts` via route interception; listing timestamps covered by `ManagementApiIntegrationTest` test 20. See `PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md` Task 2)
- [x] Consumer Groups: Duplicate group name validation (closed 2026-06-11 — backend now returns 409; covered by `consumer-groups-validation.spec.ts` test 04 and `ManagementApiIntegrationTest` test 19. See `PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md` Task 1)
- [x] Event Stores: View Details / Query Events drawer
- [x] Event Stores: List row counts update after scoping (`event-stores-scope-filter.spec.ts` tests 02–03)
- [x] Events: Aggregate Type filter
- [x] Events: Date Range (RangePicker) filter
- [x] Events: Invalid JSON in Event Data / Metadata fields
- [x] Causation Tree: Node detail drawer
- [x] Causation Tree: Empty state / "No events found"
- [x] Aggregate Stream: Details drawer
- [x] Aggregate Stream: Filter by Event Type
- [x] Message Browser: Advanced drawer filters applied to table rows
- [x] Message Browser: EventSource failure recovery
- [x] Settings: Auto-ping toggle + interval

---

## 1. System Overview Page
**Source File**: [Overview.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/Overview.tsx)

### Covered by Tests
*   Dynamic setup scoping changes via `SetupScopeBar`.
*   Setup details drawer pop-up and descriptions load.
*   WebSocket/SSE status badge visibility states.
*   Total statistics cards rendering (Total setups, total queues, total event stores).

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Recent Activity Table** `[x]` — covered in `overview-recent-activity.spec.ts` (tests 02–03):
    *   Test 02 asserts the Recent Activity table renders rows after backend operations.
    *   Test 03 asserts each row status tag carries a valid colour class (`ant-tag-green` / `ant-tag-orange` / `ant-tag-red`).
2.  **Live Updates Integration** `[x]` — covered in `overview-live-stats-update.spec.ts`:
    *   Asserts that an incoming `system_stats` event on the WebSocket updates the statistics cards and charts in real-time.
3.  **Queue Overview Table** `[x]` — covered in `overview-recent-activity.spec.ts` (tests 05, 07):
    *   Test 05 asserts the Queue Overview table shows rows for the selected setup.
    *   Test 07 asserts clicking "View All" navigates to `/queues`.
4.  **Reconnecting Banner States** `[x]` — covered in `overview-reconnecting-banner.spec.ts`:
    *   Asserts the WS status tag shows "Reconnecting" with the `ant-tag-gold` class when the connection drops.

---

## 2. Database Setups Page
**Source File**: [DatabaseSetups.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/DatabaseSetups.tsx)

### Covered by Tests
*   Creating a new setup via the Form Modal.
*   Validating setup addition in setups inventory table.
*   Simulated API connection failure gracefully shows error toast (503 paths).
*   "View Details" action shows "coming soon" info alert.
*   Empty field submission triggers validation errors.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Delete Setup Confirmation Stats** `[x]` — covered in `database-setup.spec.ts`:
    *   Asserts the delete confirmation modal displays the affected queues and event stores that will be wiped.
2.  **Port Range Validation** `[x]` — covered in `database-setup-form-defaults.spec.ts`:
    *   Asserts the Ant Design InputNumber clamps out-of-range values on blur: above 65535 → `65535`, `0` → `1`.
3.  **Form Field Default States** `[x]` — covered in `database-setup-form-defaults.spec.ts`:
    *   Asserts default values (Host = `localhost`, Port = `5432`, Username = `peegeeq`, Schema = `public`).

---

## 3. Queues Page
**Source File**: [QueuesEnhanced.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/QueuesEnhanced.tsx)

### Covered by Tests
*   Queue creation modal and dynamic setups select.
*   Refetching queue stats via the Sync button.
*   Deleting a queue via row action.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Search & Filtering Controls** `[x]` — covered in `queues-filter-sort.spec.ts` (tests 01, 03–04):
    *   Test 01 asserts typing in the search box sends the `search` param to the queues API.
    *   Tests 03–04 assert selecting Type / Status filters sends `type=` / `status=` params.
    *   Note: coverage asserts API request parameters, not rendered table rows.
2.  **Table Column Sorting** `[x]` — covered in `queues-filter-sort.spec.ts` (tests 05–07):
    *   Asserts clicking Queue Name / Message Rate headers sends `sortBy`, and a second click sends `sortOrder=desc`.
    *   Note: Message Count column sorting is not explicitly tested.
3.  **Purge Action** `[x]` — covered in `queue-details-operations.spec.ts` (test 06):
    *   Asserts clicking the "Purge Messages" dropdown action triggers the purge and shows the success alert.

---

## 4. Queue Details Page
**Source File**: [QueueDetailsEnhanced.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/QueueDetailsEnhanced.tsx)

### Covered by Tests
*   Tabbed navigation (Overview, Consumers, Messages, Bindings, Charts).
*   Publishing a message payload via the Messages tab modal.
*   Summary statistics cards rendering.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Get Messages Interaction** `[x]` — covered in `queue-details-operations.spec.ts` (tests 01–02):
    *   Covers the "Get Messages" modal (limit count query), the messages table display, and viewing message payloads in the JSON popup dialog.
2.  **Queue State Operations (Pause / Resume)** `[x]` — covered in `queue-details-operations.spec.ts` (tests 04–05):
    *   Asserts clicking "Pause Queue" / "Resume Queue" in the actions menu, confirming the prompt, and that the POST endpoints are called.
3.  **Purging Queue via Details** `[x]` — covered in `queue-details-operations.spec.ts` (test 06):
    *   Asserts toggling "Purge Messages" from the details page dropdown and verifies the purge count success alert.
4.  **Queue Deletion via UI** `[x]` — covered in `queue-details-operations.spec.ts` (test 07):
    *   Asserts deleting the queue via the Details dropdown action button and navigating away (other E2E scripts still use direct API requests for cleanup, which is fine).

---

## 5. Consumer Groups Page
**Source File**: [ConsumerGroups.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/ConsumerGroups.tsx)

### Covered by Tests
*   Filtering lists via scope selector bar.
*   Mocked endpoint hits for Pause Group, Resume Group, and Start Backfill actions.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Details Modal** `[x]` — covered in `consumer-groups-scope-selectors.spec.ts`:
    *   Asserts "View Details" opens the modal displaying descriptions, processed/total messages, lag statistics, and execution timestamps.
2.  **Backfill Progress Bar** `[x]` — closed 2026-06-11:
    *   The stale `test.skip` (whose premise — "listing lacks `backfillStatus`" — had been false since 2026-06-07, commit `d75d48d9`) is rewritten as a real test: the row shows the `IN_PROGRESS` tag and the action menu hides "Start Backfill".
    *   A new test asserts the details modal renders the `.ant-progress` bar at the correct percentage during `IN_PROGRESS`.
    *   Both stub the GET listing via `page.route()` because a real backfill's `IN_PROGRESS` window (single 10k batch, no delay) is too short to observe through the UI; the real listing fields — including the newly added `backfillStartedAt`/`backfillCompletedAt` — are covered backend-side by `ManagementApiIntegrationTest` test 20. See [PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md](./PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md) Task 2.
3.  **Group Validation Checks** `[x]` — closed 2026-06-11:
    *   Required-field validation and successful creation are covered in `consumer-groups-validation.spec.ts` (tests 01–03).
    *   Duplicate group name validation is now covered: the backend returns `409 CONFLICT` (`ManagementApiHandler.createConsumerGroup`, verified by `ManagementApiIntegrationTest` test 19), the UI surfaces the backend error message in the toast, and `consumer-groups-validation.spec.ts` test 04 asserts the toast, the open modal, and the single table row. See [PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md](./PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md) Task 1.

---

## 6. Event Stores Page
**Source File**: [EventStores.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/EventStores.tsx)

### Covered by Tests
*   Creating a new event store.
*   Deleting an event store.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Details Modal** `[x]` — covered in `event-store-management.spec.ts`:
    *   Asserts clicking "View Details" (or "Query Events") opens the drawer displaying statistics for Events, Streams, Corrections, Event Types, and Aggregate Types.
2.  **List Row Counts after Scoping** `[x]` — covered in `event-stores-scope-filter.spec.ts` (tests 02–03):
    *   Test 02 asserts every listed row carries the selected setup's tag and both seeded stores appear.
    *   Test 03 asserts clearing the selection restores the full list.

---

## 7. Events Page
**Source File**: [EventsPage.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/EventsPage.tsx)

### Covered by Tests
*   Posting events (including correlation IDs).
*   Filtering loaded events by Event Type and Correlation ID.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Aggregate Type & Date Range filters** `[x]` — covered in `events-filter.spec.ts` (tests 09–12):
    *   Asserts filtering loaded events using the "Aggregate Type" input and the `RangePicker` (Valid From / Valid To).
2.  **Bi-temporal date selection** `[x]` — covered in `events-filter.spec.ts`:
    *   Asserts selecting a custom Business/Valid Time via the `DatePicker` in the Advanced options during posting.
3.  **JSON Validation Errors** `[x]` — covered in `events-filter.spec.ts` (tests 13–14):
    *   Asserts validation errors when entering invalid JSON in the Event Data or Metadata fields.

---

## 8. Causation Tree Page
**Source File**: [CausationTreePage.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/CausationTreePage.tsx)

### Covered by Tests
*   Basic trace loading.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Node Details Drawer** `[x]` — covered in `causation-tree.spec.ts` (tests 09–10):
    *   Asserts clicking the query detail icon button (`SearchOutlined`) next to a node opens the Event Details Drawer.
2.  **Warning / Empty States** `[x]` — covered in `causation-tree.spec.ts` (test 08):
    *   Asserts handling of empty inputs / missing event store selections and the "No events found" alert state.

---

## 9. Aggregate Stream Page
**Source File**: [AggregateStreamPage.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/AggregateStreamPage.tsx)

### Covered by Tests
*   Aggregates loading list and clicking "View Stream".

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Details Drawer** `[x]` — covered in `aggregate-stream.spec.ts` (tests 10–11):
    *   Asserts clicking the "Details" action button next to an event stream row displays the event details.
2.  **Filter by Event Type** `[x]` — covered in `aggregate-stream.spec.ts` (tests 12–13):
    *   Asserts entering text in "Filter by Event Type" reloads the aggregates list with the filter applied.

---

## 10. Message Browser Page
**Source File**: [MessageBrowser.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/MessageBrowser.tsx)

### Covered by Tests
*   **Initial Render**: Confirms standard UI headers, empty tables, search text box, refresh buttons, and Live switch render correctly.
*   **Dual Scope Selection**: Scopes message query options to active Database Setups and Message Queues using global layout selectors.
*   **Message Data Retrieval**: Verifies that 4 test messages published via the backend API load successfully in the table with correct payload snippets displayed in cells.
*   **Quick Content Search**: Toggles search queries against payload keys/values and exact Message IDs to assert that lists filter down correctly.
*   **Clear Filters Action**: Resolves that clicking the "Clear" quick action resets inputs and restores all 4 baseline messages.
*   **Refresh Action Trigger**: Validates that clicking "Refresh" issues HTTP GET requests to `/management/messages` for the active queue.
*   **Advanced Drawer Structure**: Opens the Advanced drawer panel and checks for the presence of "Message Filters", "Time Range", and "Content Search" form sections.
*   **Message Details Modal**: Verifies that clicking the eye icon button opens the message details modal, displays the raw message payload, and closes successfully.
*   **Live SSE Mode Connection**: Validates that enabling the "Live" toggle opens an active SSE EventSource stream connection (`/queues/{setupId}/{queueName}/stream`), displays the "Real-time Mode Active" warning banner, and dynamically appends incoming messages to the top of the table in real-time.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Advanced Filters In Drawer** `[x]` — covered in `message-browser-advanced-filters.spec.ts`:
    *   Asserts the drawer inputs (Message Type, Status selection, RangePicker date range, content search text) are applied to filter the table rows.
2.  **EventSource Failure Recovery** `[x]` — covered in `message-browser-sse-failure.spec.ts`:
    *   Asserts handling of EventSource connection timeouts, dropouts, and backend API socket errors.

---

## 11. Settings Page
**Source File**: [Settings.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/Settings.tsx)

### Covered by Tests
*   REST endpoint settings saving.
*   Health-check ping utility.
*   Reset to Defaults button resets input fields.
*   Invalid URL format triggers validation errors.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Auto-ping configuration** `[x]` — covered in `settings-auto-ping.spec.ts`:
    *   Asserts toggling "Auto-ping", modifying the interval number, and that background ping intervals fire.
