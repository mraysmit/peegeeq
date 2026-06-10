# PeeGeeQ Management UI - E2E Test Coverage Gaps

This document provides a detailed breakdown of the features and user flows implemented in the [peegeeq-management-ui](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui) codebase that are **not** currently covered by the Playwright E2E test suite.

> **Validation note (2026-06-06)**: All gaps were cross-checked against the source components and existing test specs. Inaccurate entries have been removed or corrected.

---

## Progress Summary (validated 2026-06-09)

**21 of 29 gaps closed. 8 still open.**

| Page | Total Gaps | ✅ Closed | ❌ Open |
|------|-----------|----------|--------|
| System Overview | 4 | 3 | 1 |
| Database Setups | 3 | 3 | 0 |
| Queues | 3 | 1 | 2 |
| Queue Details | 4 | 4 | 0 |
| Consumer Groups | 3 | 2 | 1 |
| Event Stores | 2 | 1 | 1 |
| Events | 3 | 3 | 0 |
| Causation Tree | 2 | 2 | 0 |
| Aggregate Stream | 2 | 2 | 0 |
| Message Browser | 2 | 0 | 2 |
| Settings | 1 | 0 | 1 |
| **Total** | **29** | **16** | **13** |

### All gaps at a glance

- [x] Overview: Recent Activity table rows + status tag colours
- [ ] Overview: Live WebSocket `system_stats` events update stats cards/charts
- [x] Overview: Queue Overview table filtered items + "View All" link
- [x] Overview: WS/SSE reconnecting banner (gold tags)
- [x] Database Setups: Delete confirmation modal shows affected queues/event stores
- [x] Database Setups: Port range validation (values outside 1–65535)
- [x] Database Setups: Form field default values (Host=localhost, Port=5432, Username=peegeeq, Schema=public)
- [ ] Queues: Search box ("Search queues...")
- [ ] Queues: Type / Status multi-select filters
- [ ] Queues: Column sorting
- [x] Queue Details: Get Messages modal + messages table
- [x] Queue Details: View payload JSON popup
- [x] Queue Details: Pause Queue confirm + POST
- [x] Queue Details: Resume Queue confirm + POST
- [x] Queue Details: Purge Messages confirm + POST + toast
- [x] Queue Details: Delete Queue via UI + navigate away
- [x] Consumer Groups: View Details modal
- [~] Consumer Groups: Backfill IN_PROGRESS progress bar (menu item click covered; bar rendering NOT verified)
- [ ] Consumer Groups: Duplicate group name validation
- [x] Event Stores: View Details / Query Events drawer
- [ ] Event Stores: List row counts update after scoping
- [x] Events: Aggregate Type filter
- [x] Events: Date Range (RangePicker) filter
- [x] Events: Invalid JSON in Event Data / Metadata fields
- [x] Causation Tree: Node detail drawer
- [x] Causation Tree: Empty state / "No events found"
- [x] Aggregate Stream: Details drawer
- [x] Aggregate Stream: Filter by Event Type
- [ ] Message Browser: Advanced drawer filters applied to table rows
- [ ] Message Browser: EventSource failure recovery
- [ ] Settings: Auto-ping toggle + interval

---

## 1. System Overview Page
**Source File**: [Overview.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/Overview.tsx)

### Covered by Tests
*   Dynamic setup scoping changes via `SetupScopeBar`.
*   Setup details drawer pop-up and descriptions load.
*   WebSocket/SSE status badge visibility states.
*   Total statistics cards rendering (Total setups, total queues, total event stores).

### Missing Test Coverage Gaps
1.  **Recent Activity Table** `[ ]`:
    *   No tests verify that the Recent Activity table (populated via `GET /api/v1/management/overview`) renders rows.
    *   Row status tags (`success` / `warning` / `error` colors) are not checked.
2.  **Live Updates Integration** `[ ]`:
    *   Websocket SSE status indicator is checked for static tags, but there are no tests asserting that an incoming `system_stats` event on the WebSocket updates the statistics cards or charts in real-time.
3.  **Queue Overview Table** `[ ]`:
    *   No verification that the Queue Overview table displays correctly filtered items.
    *   No E2E click-through test of the "View All" link redirection.
4.  **Reconnecting Banner States** `[ ]`:
    *   The reconnecting UI states (`wsReconnecting` / `sseReconnecting` showing gold status tags) are never asserted.

---

## 2. Database Setups Page
**Source File**: [DatabaseSetups.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/DatabaseSetups.tsx)

### Covered by Tests
*   Creating a new setup via the Form Modal.
*   Validating setup addition in setups inventory table.
*   Simulated API connection failure gracefully shows error toast (503 paths).
*   "View Details" action shows "coming soon" info alert.
*   Empty field submission triggers validation errors.

### Missing Test Coverage Gaps
1.  **Delete Setup Confirmation Stats** `[x]` — covered in `database-setup.spec.ts`:
    *   The confirmation modal for deleting a setup displays the affected queues and event stores that will be wiped. This counts rendering logic is never tested.
2.  **Port Range Validation** `[ ]`:
    *   Empty field submission is tested, but port range validation (e.g. values outside 1–65535) is not verified.
3.  **Form Field Default States** `[ ]`:
    *   No tests assert default values (Host = `localhost`, Port = `5432`, Username = `peegeeq`, Schema = `public`).

---

## 3. Queues Page
**Source File**: [QueuesEnhanced.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/QueuesEnhanced.tsx)

### Covered by Tests
*   Queue creation modal and dynamic setups select.
*   Refetching queue stats via the Sync button.
*   Deleting a queue via row action.

### Missing Test Coverage Gaps
1.  **Search & Filtering Controls** `[ ]`:
    *   The "Search queues..." text box is never tested.
    *   Multi-select filters for **Type** (Native, Outbox, Bitemporal) and **Status** (Active, Paused, Idle, Error) are never toggled or asserted.
2.  **Table Column Sorting** `[ ]`:
    *   Sorting by Queue Name, Message Count, and Message Rate is untested.
3.  **Purge Action** `[x]` — covered in `queue-details-operations.spec.ts` (test 06):
    *   Clicking the "Purge Messages" dropdown action, which fires a placeholder notice, is untested.

---

## 4. Queue Details Page
**Source File**: [QueueDetailsEnhanced.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/QueueDetailsEnhanced.tsx)

### Covered by Tests
*   Tabbed navigation (Overview, Consumers, Messages, Bindings, Charts).
*   Publishing a message payload via the Messages tab modal.
*   Summary statistics cards rendering.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Get Messages Interaction** `[x]` — covered in `queue-details-operations.spec.ts` (tests 01–02):
    *   The "Get Messages" modal (limit count query) and its messages table display are not covered.
    *   Viewing message payloads in the JSON popup dialog is untested.
2.  **Queue State Operations (Pause / Resume)** `[x]` — covered in `queue-details-operations.spec.ts` (tests 04–05):
    *   Clicking "Pause Queue" or "Resume Queue" in the actions menu, confirming the prompt, and checking that the POST endpoints are called is untested.
3.  **Purging Queue via Details** `[x]` — covered in `queue-details-operations.spec.ts` (test 06):
    *   Toggling "Purge Messages" from the details page dropdown and verifying the purge count success alert is untested.
4.  **Queue Deletion via UI** `[x]` — covered in `queue-details-operations.spec.ts` (test 07):
    *   Deleting the queue using the Details dropdown action button is untested (current E2E scripts use direct Playwright API HTTP requests to clean up queues rather than using the UI workflow).

---

## 5. Consumer Groups Page
**Source File**: [ConsumerGroups.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/ConsumerGroups.tsx)

### Covered by Tests
*   Filtering lists via scope selector bar.
*   Mocked endpoint hits for Pause Group, Resume Group, and Start Backfill actions.

### Missing Test Coverage Gaps
1.  **Details Modal** `[x]` — covered in `consumer-groups-scope-selectors.spec.ts`:
    *   Opening "View Details" to display descriptions, processed/total messages, active lag statistics, and execution timestamps is untested.
2.  **Backfill Progress Bar** `[~]` — partially covered (menu item click only; bar rendering NOT verified):
    *   If a backfill is in progress (`backfillStatus === 'IN_PROGRESS'`), a progress bar represents progress based on processed/total count. This progress bar rendering is untested.
3.  **Group Validation Checks** `[ ]`:
    *   Form validations in the create consumer group modal (such as duplicate name errors) are untested.

---

## 6. Event Stores Page
**Source File**: [EventStores.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/EventStores.tsx)

### Covered by Tests
*   Creating a new event store.
*   Deleting an event store.

### Missing Test Coverage Gaps
1.  **Details Modal** `[x]` — covered in `event-store-management.spec.ts`:
    *   Asserting that clicking "View Details" (or "Query Events") opens the drawer displaying statistics for Events, Streams, Corrections, Event Types, and Aggregate Types is untested.
2.  **List Row Counts after Scoping** `[ ]`:
    *   E2E tests select setups in the scope bar but do not assert that the listed event store rows update to match the selected setup.

---

## 7. Events Page
**Source File**: [EventsPage.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/EventsPage.tsx)

### Covered by Tests
*   Posting events (including correlation IDs).
*   Filtering loaded events by Event Type and Correlation ID.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Aggregate Type & Date Range filters** `[x]` — covered in `events-filter.spec.ts` (tests 09–12):
    *   Filtering loaded events using the "Aggregate Type" input and `RangePicker` (Valid From / Valid To) is untested.
2.  **Bi-temporal date selection** `[x]` — covered in `events-filter.spec.ts`:
    *   Selecting a custom Business/Valid Time via the `DatePicker` in the Advanced options during Posting is untested.
3.  **JSON Validation Errors** `[x]` — covered in `events-filter.spec.ts` (tests 13–14):
    *   Validation errors when inputting invalid JSON text in the Event Data or Metadata fields are untested.

---

## 8. Causation Tree Page
**Source File**: [CausationTreePage.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/CausationTreePage.tsx)

### Covered by Tests
*   Basic trace loading.

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Node Details Drawer** `[x]` — covered in `causation-tree.spec.ts` (tests 09–10):
    *   Clicking the small query detail icon button (`SearchOutlined`) next to a node to open the Event Details Drawer is untested.
2.  **Warning / Empty States** `[x]` — covered in `causation-tree.spec.ts` (test 08):
    *   Handling of empty inputs or missing event store selections, and "No events found" alert states are not explicitly asserted.

---

## 9. Aggregate Stream Page
**Source File**: [AggregateStreamPage.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/AggregateStreamPage.tsx)

### Covered by Tests
*   Aggregates loading list and clicking "View Stream".

### Missing Test Coverage Gaps — ALL CLOSED
1.  **Details Drawer** `[x]` — covered in `aggregate-stream.spec.ts` (tests 10–11):
    *   Clicking the "Details" action button next to an event stream row to display event details is untested.
2.  **Filter by Event Type** `[x]` — covered in `aggregate-stream.spec.ts` (tests 12–13):
    *   Entering text in "Filter by Event Type" and reloading the aggregates list is untested.

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

### Missing Test Coverage Gaps
1.  **Advanced Filters In Drawer** `[ ]`:
    *   Although the drawer's elements are verified, the tests do **not** verify that inputs (Message Type, Status selection, date range in the RangePicker, and text area content search) are applied to filter rows in the table.
2.  **EventSource Failure Recovery** `[ ]`:
    *   No tests check handling of EventSource connection timeouts, dropouts, or backend API socket errors.

---

## 11. Settings Page
**Source File**: [Settings.tsx](file:///c:/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-management-ui/src/pages/Settings.tsx)

### Covered by Tests
*   REST endpoint settings saving.
*   Health-check ping utility.
*   Reset to Defaults button resets input fields.
*   Invalid URL format triggers validation errors.

### Missing Test Coverage Gaps
1.  **Auto-ping configuration** `[ ]`:
    *   Toggling "Auto-ping", modifying the interval number, and verifying background intervals are triggered.
