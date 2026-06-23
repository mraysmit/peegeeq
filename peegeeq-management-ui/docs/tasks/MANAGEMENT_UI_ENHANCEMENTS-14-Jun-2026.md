# PeeGeeQ Management UI — Enhancement Plan

**Date**: 2026-06-14  
**Scope**: `peegeeq-management-ui` frontend + `peegeeq-rest` backend where noted.

---

## Open Items (gaps needing attention — updated 2026-06-18)

Consolidated from the gaps surfaced during the SSE/non-destructive-observe and schema work,
prioritised. Each links to where it is tracked in detail.

**High**
1. **Finish the no-`public`-in-tests schema sweep — Phases B–E.** Phase A (`ManagementApiIntegrationTest`)
   done/green; ~59 files remain across `peegeeq-rest` (B), `peegeeq-db`/`peegeeq-integration-tests`/
   `peegeeq-runtime`/`peegeeq-rest-client` (C), `peegeeq-examples`/`peegeeq-native`/`peegeeq-outbox` (D),
   and the frontend e2e/TS create flows + fixtures (E). Run each module separately — moving off `public`
   may surface masked `search_path` defects. **Phase B (`peegeeq-rest`) converted 2026-06-18 — pending its integration run.** *Tracked: `docs-design/tasks/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md` → "Follow-up: no `public` in any test".*
2. ✅ **DONE (2026-06-18) — real-consumer leg added to the observe-≠-consume test (native + outbox).**
   Test 1 now proves "still browsable" via `browse()` **and** that a real `createConsumer().subscribe()`
   still receives the message — green for both `PgNativeQueueBrowserTailIntegrationTest` and
   `OutboxQueueBrowserTailIntegrationTest`. *Tracked: §7.12 Phase 12.2;
   `docs-design/dev/non-destructive-queue-observer-design-18-Jun-2026.md` §11.*

**Medium**
3. **SSE message-stream failure/reconnect coverage.** The deleted `message-browser-sse-failure.spec.ts`
   covered EventSource dropout/recovery; with the new non-destructive `/messages/stream` that coverage is
   valid again and currently absent end-to-end (the backend observer reconnect *is* tested; the UI
   Live-mode dropout → `message.error` → auto-reconnect path is not). *Source: commit `c609bd4d` deletion + §7.12 Phase 12.5.*
4. **Phase 13 — remove latent destructive clients.** Delete `PeeGeeQClient.streamMessages`,
   `createMessageStreamService` + `useMessageStream`, and the dead `endpoints.ts QUEUE.STREAM` URL (the
   Phase 12.0 carve-out) so a consuming read cannot be wired into an admin view by accident; then the
   §13.2 naming contract. *Tracked: §7.13.*

**Low**
5. **Confirm + close the last two backend-coverage tests.** `testUpdateQueueEndpoint` (`@Order 23`) and
   `testGetQueueBindingsEndpoint` (`@Order 24`) in `ManagementApiIntegrationTest` were written but not
   confirmed green; run them, then flip their §10.5 rows to resolved. *Tracked: §10.5.*
6. **REST module guide doc pass.** `peegeeq-rest/docs/PEEGEEQ_REST_MODULE_GUIDE.md`'s implementation
   walkthrough (~lines 340–460, 745–760) still narrates the removed consuming SSE stream. *Tracked: §7.12 Phase 12.0 carve-out.*
7. ✅ **DONE (2026-06-23).** Phase 7 (Consumer Groups success toasts) and 7a (Notifications page
   `/notifications`) both implemented and green. *Tracked: §7.7, §7.7a.*

**Optional**
8. **Phase 12.6 — WebSocket parity.** Wire `WebSocketHandler.handleQueueStream` (a non-consuming stub)
   to `tail()` so WS clients get the same non-destructive push. Defer unless a WS consumer is needed.

---

## 0. Complete Functionality Inventory

Every piece of functionality on every screen, as implemented.

---

### Overview (`/`)

**Stats cards (top row)**
- Total Queues count
- Active Consumers count
- Messages Today count
- System Status indicator

**Real-time charts**
- Throughput chart — live line/area chart of messages per second, driven by WebSocket (`/ws/monitoring`) and SSE (`/sse/metrics`)
- Active Connections chart — live line/area chart of connection count over time

**System status banner**
- `WebSocket: Connected / Disconnected` tag (`data-testid="websocket-status"`)
- `SSE: Connected / Disconnected` tag (`data-testid="sse-status"`)

**Queue summary table**
- Lists all queues with: name, setup ID tag, type tag, message count, consumer count, message rate, error rate, status tag
- Columns are sortable

**Recent Activity table**
- Timestamp, Action, Resource, Status tag (success/warning/error), Details

**Refresh button** — manual refresh of all data

---

### Database Setups (`/database-setups`)

**Stats cards**
- Total Setups, Active Setups, Total Queues across all setups, Total Event Stores across all setups

**Setups table**
- Columns: Setup ID, Database Name, Host, Port, Queues count, Event Stores count, Status tag (active/creating/failed), Created At
- Per-row actions menu (three-dot): **View Details**, **Delete**

**Create Setup button** → modal with fields:
- Setup ID (required, no hyphens)
- Host (default: localhost)
- Port (default: 5432)
- Database Name (required)
- Username (required)
- Password (required)
- Schema (required)
- SSL checkbox
- Submit triggers backend schema creation + Flyway migrations (up to 60 s)

**Delete Setup** — confirmation modal listing: database name, queue count, event store count, irreversibility warning

---

### Queues (`/queues`)

**Stats cards**
- Total Queues, Active Queues, Total Messages, Avg Message Rate

**Filter toolbar** (FilterBar component)
- Search by queue name (text input)
- Filter by Type (native / outbox / dlq) — multi-select dropdown
- Filter by Status (active / paused / error) — multi-select dropdown
- Clear Filters button

**Queues table**
- Columns: Queue Name (link to details) + Setup ID tag + Type tag, Messages, Consumers, Message Rate (msg/s), Error Rate (coloured green/orange/red), Status tag, Actions
- Sortable columns: Queue Name, Messages, Message Rate
- Pagination with page-size selector and quick-jumper
- Clicking Queue Name navigates to Queue Details

**Per-row actions menu (three-dot)**
- View Details (navigates to `/queues/:setupId/:queueName`)
- Purge Messages
- Delete Queue (confirmation modal)

**Create Queue button** → modal with fields:
- Queue Name (required)
- Setup dropdown (with Refresh Setups button)
- Queue Type (defaults to `native`)

---

### Queue Details (`/queues/:setupId/:queueName`)

**Breadcrumb** — Queues › {setupId} › {queueName}

**Header actions**
- Refresh button
- Actions menu (three-dot): Pause / Resume, Purge Messages, Delete Queue — each with a confirmation modal

**Tab: Overview**
- Queue Information card: Setup ID, Queue Name, Type tag, Status tag, Created At, Updated At
- Performance Metrics card (StatCard components): Message Rate (msg/s), Consumer Count, Error Count, Avg Processing Time

**Tab: Consumers**
- Table of active/idle consumer connections
- Columns: Consumer ID, Name, Status tag (ACTIVE/IDLE/DISCONNECTED), Connected At, Last Heartbeat, Messages Processed, Messages/sec, Error Count, Avg Processing Time

**Tab: Messages**
- Table of messages currently in the queue
- Columns: Message ID (truncated tag), Type, Priority, Delivery Count, Timestamp
- **Publish Message button** → modal with fields: Payload (JSON textarea), Headers (JSON textarea), Priority (number), Delay Seconds (number)
- **Get Messages button** → modal: Count input → fetches and displays messages inline

**Tab: Bindings**
- Table of routing-key bindings configured for the queue

---

### Event Stores (`/event-stores`)

**Stats cards**
- Total Event Stores, Active Stores, Total Events, Unique Aggregates, Storage Used (estimated)

**Event Stores table**
- Columns: Store Name, Setup ID, Event Count, Aggregate Count (streams), Status tag, Last Event At, Storage (estimated), Actions
- Per-row actions menu: **View Details** (modal), **Delete** (confirmation modal)

**View Details modal**
- Store name, Setup ID, status, event count, stream count, created at, last event at
- Aggregate types list, event types list

**Create Event Store button** → modal with fields:
- Event Store Name (required)
- Setup dropdown (with Refresh Setups button)

**Delete Event Store** — confirmation modal noting all events will be permanently deleted

---

### Events (`/events`)

**Post Event card**
- Setup dropdown (required)
- Event Store dropdown (required, filtered by selected setup — shows event count per store)
- Event Type text input (required)
- Event Data JSON textarea (required, validated as valid JSON)
- **Show / Hide Advanced toggle button**

  *Advanced — Temporal section*
  - Valid Time date-time picker (business time — when event actually happened)

  *Advanced — Event Sourcing section*
  - Aggregate ID input
  - Correlation ID input
  - Causation ID input

  *Advanced — Metadata section*
  - Headers JSON textarea (validated as valid JSON)

- **Clear Form button** — resets all fields and hides advanced sections
- **Post Event button** — submits, shows success toast with event ID, or error toast

**Query Events card**
- Setup dropdown (`data-testid="query-setup-select"`)
- Event Store dropdown (`data-testid="query-eventstore-select"`, disabled until setup selected)
- **Load Events button** — fetches up to 1000 events, shows success toast with count
- **Refresh button** — re-fetches with same selection

**Filter Loaded Events card** (client-side, no re-query)
- Event Type text filter (prefix icon, clearable)
- Aggregate Type text filter
- Correlation / Causation ID text filter — also auto-populated by clicking a correlation/causation ID link in the table
- Valid Time range picker (date-from / date-to with time)

**Events table**
- Columns: Event # (row number), Event Type (purple tag), Aggregate ID + Aggregate Type (cyan tag + code), Version badge, Valid Time, Transaction Time, Correlation ID link (clickable → auto-fills filter), Causation ID link (clickable → auto-fills filter), Actions
- Pagination: 20 per page, page-size selector, quick-jumper, total count label
- Table footer shows: `Total Events: N` and `(Showing M filtered)` when filter is active
- Empty state shows instruction to select setup + event store

**Per-row action: View Details button** → modal with:
- Event Information card: Event ID, Event Type, Aggregate ID, Aggregate Type, Version, Valid Time, Transaction Time, Event Number, Correlation ID, Causation ID
- Event Data card: formatted JSON
- Metadata / Headers card: formatted JSON
- Export button (footer)

---

### Consumer Groups (`/consumer-groups`)

**Stats cards**
- Total Groups, Active Groups, Total Members, Total Processed Messages

**Consumer Groups table**
- Columns: Group Name, Setup ID, Queue, Members / Max Members, Load Balancing Strategy tag, Status tag (active/inactive/rebalancing/error), Messages/sec, Last Rebalance, Actions
- Per-row actions menu: **View Details** (modal), **Delete** (confirmation)

**Create Consumer Group button** → modal with fields:
- Group Name, Setup dropdown, Queue dropdown, Max Members, Load Balancing Strategy (ROUND_ROBIN / RANGE / STICKY / RANDOM), Session Timeout

**View Details modal**
- Group details: Group ID, Name, Setup, Queue, Status, Strategy, Session Timeout, Created At, Last Rebalance
- Members table: Member ID, Name, Status tag, Joined At, Last Heartbeat, Assigned Partitions, Messages Processed, Error Count

---

### Message Browser (`/messages`)

**Toolbar**
- Setup dropdown filter
- Queue dropdown filter (populated from all queues)
- Message Type text filter
- Status dropdown filter (pending / processing / completed / failed)
- Search text input (searches payload content)
- Date range picker (from / to with time)
- **Search button**
- **Refresh button**
- **Advanced Filters button** → slide-out drawer with additional filters
- **Export button** — download filtered messages
- **Clear button** — reset all filters

**Messages table**
- Columns: Message ID (truncated), Queue, Setup, Type, Status tag, Priority, Size, Timestamp, Correlation ID, Actions
- Pagination
- Row click or View button → Message Detail modal

**Message Detail modal**
- Message ID, Queue, Setup, Type, Status, Priority, Size, Timestamp, Correlation ID, Causation ID
- Payload card (formatted JSON)
- Headers card (formatted JSON)
- Consumer Info card (if consumed): Consumer ID, Consumer Group, Processed At

---

### Causation Tree (`/causation-tree`) & Aggregate Stream (`/aggregate-stream`)

#### Causation Tree (`/causation-tree`) — `CausationTreePage.tsx`
- Title "Causation Tree" (BranchesOutlined)
- **Select Event Store card**: Setup dropdown (`data-testid="causation-setup-select"`) + Event Store dropdown (`data-testid="causation-eventstore-select"`, disabled until a setup is chosen, filtered to that setup)
- **Causation Tree card**: Correlation ID input (`data-testid="causation-correlation-input"`, Enter or Trace) + **Trace button** (disabled until setup + store selected) → `peeGeeQClient.queryEvents({ correlationId, limit: 1000, includeCorrections: true })`; builds a directed tree by causation-parent links, sorted by transaction time; Ant Design `Tree` (showLine, expanded by default); each node shows event type tag (purple), transaction time `HH:mm:ss.SSS`, aggregate ID tag (cyan), magnifier button → Details drawer
- **Deep-link**: reads `correlationId` / `setupId` / `eventStore` from the URL query string and auto-traces on arrival (used by the Aggregate Stream → Causation Tree jump)
- **Event Details drawer**: Event ID, Event Type, Aggregate ID, Correlation ID (with inline Trace), Causation ID, Valid Time, Transaction Time, Payload (JSON), Headers (JSON)

#### Aggregate Stream (`/aggregate-stream`) — `AggregateStreamPage.tsx`
- Title "Aggregate Stream" (DatabaseOutlined)
- **Select Event Store card**: Setup dropdown (`data-testid="aggregate-setup-select"`) + Event Store dropdown (`data-testid="aggregate-eventstore-select"`) + **Load Aggregates** button
- **Aggregates panel** (left): Event Type filter input + **Refresh List** button → `peeGeeQClient.getUniqueAggregates(...)` (page size 1000); table columns: Aggregate ID, Events (purple badge), Last Active, Event Types (tags); a truncation warning + **Load More** button when the result set exceeds the page size; clicking a row loads that aggregate's stream
- **Stream panel** (right): table columns Version (green badge), Event Type (purple tag), Valid Time, Transaction Time, Actions (Details, Causation Tree); **keyset pagination** — Previous / Next only, anchored to the last event of each page (`data-testid="stream-pagination"` / `stream-prev-page` / `stream-next-page` / `stream-pagination-status`, page size 10)
- **Causation Tree** row action navigates to `/causation-tree?correlationId=…&setupId=…&eventStore=…`
- **Event Details drawer**: same fields as Causation Tree, plus a "View Causation Tree" deep-link button

---

### Settings (`/settings`)

**Connection Status card**
- Current connection state badge (Connected / Disconnected / Checking)
- Live check runs on load

**Backend Configuration form** (`data-testid="settings-form"`)
- API URL input (`data-testid="api-url-input"`) — base REST URL
- WebSocket URL input (`data-testid="ws-url-input"`) — optional override
- **Save Configuration button**
- **Reset to Defaults button**

**REST API health section**
- **Ping REST button** — calls `GET /api/v1/health`, shows success/fail result with timestamp
- Auto-ping toggle + interval (seconds) input
- Last result badge (green tick / red cross + timestamp)

**WebSocket health section**
- **Ping WS button** — opens `ws://host/ws/health`, shows success/fail with timestamp
- Auto-ping toggle + interval input
- Last result badge

**SSE health section**
- **Ping SSE button** — calls `GET /api/v1/sse/health`, shows success/fail with timestamp
- Auto-ping toggle + interval input
- Last result badge

---

### Header (all pages)

- Page title (current page name)
- Connection status badge: Online (REST ✓ WS ✓ SSE ✓) / Offline / Checking — polls `/api/v1/health` and `/ws/health`
- Refresh button
- Notifications bell (wired client-side — opens a notifications drawer; see §1.4 / §6.5)
- User menu

### Sidebar (all pages)

Nav items (in order):
1. Overview (`/`) — DashboardOutlined — `nav-overview`
2. Database Setups (`/database-setups`) — SettingOutlined — `nav-database-setups`
3. Queues (`/queues`) — InboxOutlined — `nav-queues`
4. Consumer Groups (`/consumer-groups`) — TeamOutlined — `nav-consumer-groups`
5. Event Stores (`/event-stores`) — DatabaseOutlined — `nav-event-stores`
6. Events (`/events`) — FileTextOutlined — `nav-events`
7. Causation Tree (`/causation-tree`) — BranchesOutlined — `nav-causation-tree`
8. Aggregate Stream (`/aggregate-stream`) — DatabaseOutlined — `nav-aggregate-stream`
9. Message Browser (`/messages`) — SearchOutlined — `nav-messages`

Settings (`/settings`) is **not** in the sidebar — it is reached from the Header user menu. Collapsible — collapse/expand trigger at bottom.

---



### 1.1 Pages and navigation

| Page | Route | Status |
|---|---|---|
| Overview | `/` | Working — loads stats, queue summary, recent activity, real-time charts |
| Database Setups | `/database-setups` | Working — create, list setups |
| Queues | `/queues` | Working — create, list, delete queues per setup |
| Consumer Groups | `/consumer-groups` | Working |
| Event Stores | `/event-stores` | Working — create, list event stores |
| Events | `/events` | Working — post events, query and filter events |
| Causation Tree | `/causation-tree` | Working — correlation-ID causation tree |
| Aggregate Stream | `/aggregate-stream` | Working — aggregate list + keyset-paginated event stream |
| Message Browser | `/messages` | Working |
| Settings | `/settings` | Working — configure backend URL, test connection, reset to defaults |

#### Overview page

![Overview page](../../../docs-design/peegeeq-management-ui/screenshots/01-overview.png)

*System Overview — stats cards, live throughput and connections charts, queue summary table, recent activity table. WebSocket and SSE status tags are visible in the system status banner.*

#### Header bar

![Header bar](../../../docs-design/peegeeq-management-ui/screenshots/02-header.png)

*Header shows the current page title, the connection status badge, the refresh button, the notification bell (wired — see §1.4 / §6.5), and the user menu.*

#### WebSocket and SSE status

![WS and SSE connected](../../../docs-design/peegeeq-management-ui/screenshots/03-ws-sse-status.png)

*The system status banner on the Overview page shows `WebSocket: Connected` and `SSE: Connected` tags (green) when both the `/ws/monitoring` WebSocket and `/sse/metrics` SSE channels are established. These are the `data-testid="websocket-status"` and `data-testid="sse-status"` elements asserted by the `websocket-sse-connection.spec.ts` E2E test.*

#### Queues page

![Queues page](../../../docs-design/peegeeq-management-ui/screenshots/04-queues.png)

*The Queues page shows per-queue stats cards (total, active, messages, avg rate), a search/filter toolbar, and the queues table with sortable columns. The **Create Queue** button opens a modal with setup selection and queue configuration.*

#### Queues — Create Queue modal

![Create Queue modal](../../../docs-design/peegeeq-management-ui/screenshots/04b-queues-create-queue-modal.png)

*The Create Queue modal requires selecting a database setup from the dropdown and entering a queue name. Queue type defaults to `native`.*

#### Database Setups page

![Database Setups page](../../../docs-design/peegeeq-management-ui/screenshots/05-database-setups.png)

*Database Setups lists all configured PostgreSQL connections. Each setup has its own isolated schema. The **Create Setup** button opens the creation form.*

#### Create Setup modal

![Create Setup modal](../../../docs-design/peegeeq-management-ui/screenshots/06-create-setup-modal.png)

*The Create Setup form collects Setup ID (identifier, no hyphens), host, port, database name, username, password, schema, and SSL flag. Creating a setup triggers schema creation and Flyway migrations on the backend — this is why the modal timeout in E2E tests is set to 60 seconds.*

#### Event Stores page

![Event Stores page](../../../docs-design/peegeeq-management-ui/screenshots/07-event-stores.png)

*The Event Stores page shows aggregate stats (stores, active, total events, unique aggregates, storage used) and lists all configured event stores with status, event count, and storage.*

#### Event Stores — details modal

![Event Store details modal](../../../docs-design/peegeeq-management-ui/screenshots/07b-event-store-details-modal.png)

*The per-row actions menu (three-dot button) offers View Details, Edit, and Delete. The View Details modal shows the full event store configuration including connection details and schema.*

#### Events page — post form (empty)

![Events page post form](../../../docs-design/peegeeq-management-ui/screenshots/07c-events-post-form.png)

*The Events page provides a Post Event form (setup, event store, event type, and JSON payload) together with a Query Events panel and a Filter Loaded Events toolbar.*

#### Events page — events loaded

![Events page with events loaded](../../../docs-design/peegeeq-management-ui/screenshots/07e-events-loaded.png)

*After selecting a setup and event store and clicking Load Events, the results table shows all events with type tags, aggregate ID, version, bi-temporal columns, and correlation/causation chain links.*

#### Events page — filtered by event type

![Events page filtered](../../../docs-design/peegeeq-management-ui/screenshots/07f-events-filtered.png)

*The Filter Loaded Events toolbar narrows the visible rows by event type without re-querying the backend.*

#### Events page — event detail modal

![Events page event detail modal](../../../docs-design/peegeeq-management-ui/screenshots/07g-event-detail-modal.png)

*Clicking the view action on any event row opens the event detail modal, showing the full JSON payload, all bi-temporal timestamps, and the correlation/causation chain.*

#### Events page — advanced options expanded

![Events page advanced options](../../../docs-design/peegeeq-management-ui/screenshots/07d-events-post-form-advanced.png)

*Clicking Show Advanced reveals three optional sections: Temporal (valid time range), Event Sourcing (aggregate ID, correlation ID, causation ID), and Metadata (arbitrary JSON headers).*

#### Settings page

![Settings page](../../../docs-design/peegeeq-management-ui/screenshots/08-settings.png)

*The Settings page configures the backend API URL and optional WebSocket URL. It includes individual health-check ping buttons for REST, WebSocket (`/ws/health`), and SSE (`/api/v1/sse/health`) channels, each with an auto-ping toggle.*

#### Consumer Groups page

![Consumer Groups page](../../../docs-design/peegeeq-management-ui/screenshots/09-consumer-groups.png)

*The Consumer Groups page lists all consumer group subscriptions across setups. Each group can be browsed for its queue assignments and offset positions.*

#### Message Browser page

![Message Browser page](../../../docs-design/peegeeq-management-ui/screenshots/10-message-browser.png)

*The Message Browser allows searching and inspecting messages across any queue in any setup. Filters by setup, queue, and message content.*

#### Causation Tree page (`/causation-tree`)

![Causation Tree empty state](../../../docs-design/peegeeq-management-ui/screenshots/11-causation-tree-empty.png)

*Empty state — shown until a setup + event store are selected and a Correlation ID is traced.*

![Causation Tree store selected](../../../docs-design/peegeeq-management-ui/screenshots/11b-causation-tree-store-selected.png)

![Causation Tree traced](../../../docs-design/peegeeq-management-ui/screenshots/12-causation-tree-traced.png)

*After entering a Correlation ID and clicking Trace, the directed causation tree renders, grouped by causation-parent links.*

#### Aggregate Stream page (`/aggregate-stream`)

![Aggregate Stream](../../../docs-design/peegeeq-management-ui/screenshots/12b-aggregate-stream.png)

*Aggregate list on the left; clicking an aggregate loads its keyset-paginated event stream on the right.*

#### Queue Details — Overview tab

Queue Details is reached by clicking the View action on any row in the Queues list. The URL is `/queues/:setupId/:queueName`.

![Queue Details Overview tab](../../../docs-design/peegeeq-management-ui/screenshots/04c-queue-details-overview.png)

*The **Overview** tab shows a Queue Information card (setup ID, queue name, type tag, status tag, created/updated timestamps) and a Performance Metrics card (message rates, consumer count, error statistics).*

#### Queue Details — Consumers tab

![Queue Details Consumers tab](../../../docs-design/peegeeq-management-ui/screenshots/04d-queue-details-consumers.png)

*The **Consumers** tab lists all active and idle consumer connections for the queue, including consumer ID, name, status (ACTIVE/IDLE/DISCONNECTED), connected-at timestamp, last heartbeat, messages processed, messages per second, error count, and average processing time.*

#### Queue Details — Messages tab

![Queue Details Messages tab](../../../docs-design/peegeeq-management-ui/screenshots/04e-queue-details-messages.png)

*The **Messages** tab shows the messages currently in the queue. It supports filtering by state and includes a **Publish Message** button to send a message to the queue directly from the UI.*

#### Queue Details — Bindings tab

![Queue Details Bindings tab](../../../docs-design/peegeeq-management-ui/screenshots/04f-queue-details-bindings.png)

*The **Bindings** tab shows the routing-key bindings configured for this queue.*

### 1.2 Real-time channels in use

(Frontend wiring status as of 2026-06-14. The backend side of each channel is in §6.4.)

| Channel | Endpoint | Usage | Status |
|---|---|---|---|
| WebSocket monitoring | `ws://host/ws/monitoring` | System stats (`type: "system_stats"`) → throughput/connection charts | ✅ Wired — `Overview.tsx` via `createSystemMonitoringService` |
| SSE metrics | `http://host/sse/metrics` | Continuous metrics → same chart path | ✅ Wired — `Overview.tsx` via `createSystemMetricsSSE` |
| SSE queue updates | `http://host/api/v1/sse/queues/{setupId}` | Queue lifecycle events → list `refetch()` | ✅ Wired — `QueuesEnhanced.tsx:126` via `createQueueUpdatesSSE` |
| SSE queue stream | `http://host/api/v1/queues/{setupId}/{queueName}/stream` | Live messages → Message Browser | ✅ Wired — `MessageBrowser.tsx:195` via native `EventSource` |
| WS queue stream | `ws://host/ws/queues/{setupId}/{queueName}` | `createMessageStreamService` exists; `useMessageStream` hook commented out | ❌ Not connected to any page |
| WS health | `ws://host/ws/health` | One-shot health ping | ✅ Used by `ConnectionStatus.tsx` |
| SSE health | `http://host/api/v1/sse/health` | One-shot health ping | ✅ Used by `configService.ts:125` (Settings) |

### 1.3 Connection status surfaces

- **`ConnectionStatus` component** (`src/components/common/ConnectionStatus.tsx`): embedded in the Header. Polls REST `/api/v1/health`, opens a WS to `/ws/health` to verify connectivity. Shows an Online / Offline / Checking badge. Appears in the header of every page.
- **Overview page status tags**: `data-testid="websocket-status"` and `data-testid="sse-status"`. Driven by `onConnect` / `onDisconnect` callbacks from the WS monitoring and SSE metrics services. Turn green when the respective channel is established.

### 1.4 Notification bell

The bell is wired client-side. `Header.tsx:101` binds the badge to `unreadCount` from `managementStore`; clicking it opens a notifications `Drawer` (with a Clear action) and marks all entries read. Entries are added by `addNotification(...)` on setup / queue / event-store create & delete (`DatabaseSetups.tsx`, `QueuesEnhanced.tsx`, `EventStores.tsx`); the store caps history at 50.

`Overview.tsx:190` also contains a listener for a backend `management_event` message on `/ws/monitoring`, but the backend never emits that type (`grep management_event` over `peegeeq-rest` = 0 hits), so that path is dead. Full breakdown in §6.5.

### 1.5 E2E test coverage

The suite is **47 spec files** in `src/tests/e2e/specs/`, run with `workers: 1` (sequential — see §4). Beyond the original CRUD/smoke set it now covers:
- **Real-time receipt**: `queue-updates-sse`, `message-sse-stream`, `system-metrics-sse`, `overview-live-stats-update`; reconnection: `overview-reconnecting-banner`
- **Negative / error paths**: `consumer-groups-validation`, `api-error-paths`, setup/queue error-toast specs
- **Setup-scope selectors**: `*-scope-selector*`, `*-setup-selector`, `scope-selector-persistence`
- **Visualization pages**: `causation-tree`, `aggregate-stream`
- **Queue details + filters**: `queue-details-operations`, `queue-details-overview`, `queues-filter-sort`, `queue-config-create-and-display`, `message-browser-advanced-filters`, `events-filter`, `event-detail-modal`
- **Settings**: `settings-health-checks`, `settings-auto-ping`

The draft's earlier coverage gaps (negative tests, real-time delivery proof, WS/SSE health from the browser) have largely been closed by the specs above. (File count is exact; individual `test()` cases were not tallied.)

### 1.6 Stub features — not yet implemented

The following features exist in the UI as placeholders or no-op stubs. None have backend API calls wired up.

#### Queue Details tabs

| Tab | Component | Status |
|---|---|---|
| **Consumers** | `QueueDetailsEnhanced.tsx` | ✅ Done (2026-06-16, §7.2) — real `Table` fetching `/consumers`. No longer a stub. |
| **Bindings** | `QueueDetailsEnhanced.tsx` | Stub — shows info banner "Bindings Tab - Coming in Week 5". No API call, no data. |
| **Charts** | `QueueDetailsEnhanced.tsx` | Stub — shows info banner "Charts Tab - Coming in Week 2". No API call, no data. |

#### Queues page — Purge action

The three-dot actions dropdown on each queue row includes a **Purge** item that fires `message.info('Purge functionality coming in Week 4')` and does nothing else. No API call is made.

#### Database Setups — View Details action

The actions dropdown on each setup row includes a **View Details** item that fires `message.info('View details coming soon')` and does nothing else.

#### Entire pages that are complete stubs

| Page | File | Placeholder text |
|---|---|---|
| Developer Portal | `DeveloperPortal.tsx` | "Developer Portal interface coming soon" |
| Schema Registry | `SchemaRegistry.tsx` | "Schema Registry interface coming soon" |
| Queue Designer | `QueueDesigner.tsx` | "Visual Queue Designer interface coming soon" |
| Monitoring | `Monitoring.tsx` | "Real-time Monitoring dashboards coming soon" |

Each of these pages renders only an Ant Design `<Empty>` component with no further content. `App.tsx` does not import or route any of the four and none appears in the sidebar nav — there is no way to navigate to them in the running app. Stale `pageTitle` entries for their paths linger in `Header.tsx:27-30`. There is also one undocumented hidden page, `TestHarness.tsx` at `/test-harness`, special-cased outside the normal layout (`App.tsx:127`).

#### Header — user menu and authentication

- **Profile** menu item — click handler is a commented-out `console.log`. Does nothing.
- **Logout** menu item — click handler is a commented-out `console.log`. Does nothing.
- **Username** — hardcoded `"Admin"` literal in `Header.tsx`. No authentication layer supplies a real identity.
- **Notification bell** — wired, **not a stub** (see §1.4 / §6.5).

---

## 1.7 Setup scope selector (`SetupScopeBar`)

`components/common/SetupScopeBar.tsx` is a cross-cutting setup/scope selector rendered near the top of several pages (Overview `Overview.tsx:304`, Event Stores `EventStores.tsx:329`, and others). It scopes a page to a chosen setup, with the selection persisted across navigation. E2E coverage: `overview-setup-selector`, `queues-setup-selector`, `events-scope-selector`, `message-browser-scope-selectors`, `consumer-groups-scope-selectors`, `visualization-scope-selector`, `event-stores-scope-filter`, `event-stores-setup-selector`, `scope-selector-persistence`.

---

## 2. Proposed Enhancements

### 2.1 Wire the notification bell to a backend event stream  *(Priority: Medium)*

The badge, store slice, and drawer are fully implemented client-side (§1.4 / §6.5). The outstanding work is the backend source: `/ws/monitoring` does not emit `management_event`, so the listener at `Overview.tsx:190` is dead.

**Proposed**:
1. Add `type: "management_event"` messages to `/ws/monitoring` (or a new dedicated endpoint).
2. In `websocketService.ts` add `createManagementEventsService` factory.

Events should include: setup created/deleted, queue created/deleted, event store created/deleted, consumer group changed, backend health alerts. Steps 3–4 (badge wired to store, drawer on click) are already done.

**Files affected**:
- Backend: `SystemMonitoringHandler.java` or new handler
- `websocketService.ts` — new factory function

**Test coverage to add**:
- E2E: bell badge increments from a backend-emitted event; drawer lists the event; badge resets to 0 when dismissed

---

### 2.2 Live queue message count in the Queues table  *(Priority: Medium)*

`createQueueUpdatesSSE` is already wired in `QueuesEnhanced.tsx:126` and triggers `refetch()`. The remaining gap is on the backend: `ManagementApiHandler.publishQueueChanged` fires only on queue create/update/delete, not on message publish or purge, so the SSE channel never fires when a message is sent. The fix is a backend change — `QueueHandler.sendMessage` must call `publishQueueChanged` after a successful send (see §7.9).

**Current state**: the queues table polls REST every 30 seconds. Message count is stale between polls.

**Proposed**:
- Backend: call `publishQueueChanged(setupId, queueName)` from `QueueHandler.sendMessage` and `sendMessages`.
- Frontend: no change needed — `QueuesEnhanced.tsx:126` already calls `refetch()` on each SSE event.

**Files affected**:
- `Queues.tsx` / `QueuesEnhanced.tsx` — subscribe to SSE on mount, unsubscribe on unmount
- `managementStore.ts` — `updateQueueMessageCount(queueName, count)` action

**Test coverage to add**:
- E2E: publish a message, assert the count in the queues table updates without a manual refresh within 5 seconds

---

### 2.3 Toast notifications on resource create / delete  *(Priority: Low)*

**Current state**: After creating a queue or event store, the modal closes silently. There is no success toast in most flows.

**Proposed**:
- Use Ant Design `message.success(...)` (already used in `Overview.tsx` for errors) uniformly after every successful resource creation, deletion, and configuration change.
- Standardise the pattern across all page components.

**Files affected**:
- `DatabaseSetups.tsx`, `Queues.tsx`/`QueuesEnhanced.tsx`, `EventStores.tsx`, `ConsumerGroups.tsx`

**Test coverage to add**:
- E2E assertion: `await expect(page.locator('.ant-message-success')).toBeVisible()` after each resource creation

---

### 2.4 Reconnection UI feedback  *(Priority: Low)*

**Current state**: When WS or SSE loses connection, the `websocket-status` and `sse-status` tags on the Overview page go dark (driven by `onDisconnect` callback). The `ConnectionStatus` header badge shows Offline. There is no "Reconnecting…" intermediate state.

**Proposed**:
- Add a `RECONNECTING` state to `managementStore` alongside the existing connected/disconnected booleans.
- `WebSocketService.scheduleReconnect()` already fires reconnect attempts — expose this via an `onReconnecting` callback.
- The header `ConnectionStatus` badge and the Overview status tags both render a yellow "Reconnecting…" tag while attempts are in progress.
- After `maxReconnectAttempts` (currently 10) is exhausted, show a persistent `Alert` banner on the Overview page prompting the user to check the backend and reload.

**Files affected**:
- `websocketService.ts` — add `onReconnecting` callback to `WebSocketConfig`
- `managementStore.ts` — add `wsReconnecting` / `sseReconnecting` state fields
- `Overview.tsx` — render "Reconnecting…" tag variant
- `ConnectionStatus.tsx` — render yellow badge variant

**Test coverage to add**:
- E2E: configure a bad backend URL in Settings, navigate to Overview, assert status tags transition from Connected → Reconnecting (yellow) → Offline within timeout

---

### 2.5 Queue stream subscription in Queue Details page  *(Priority: Medium)*

**Current state**: `createMessageStreamService` exists in `websocketService.ts` but nothing in the frontend calls it. `QueueDetails.tsx` and `QueueDetailsEnhanced.tsx` load messages by polling REST.

**Proposed**:
- In `QueueDetailsEnhanced.tsx`, subscribe to `ws://host/ws/queues/{setupId}/{queueName}` on mount.
- Each incoming WS message is prepended to the messages list in real time without requiring a manual refresh.
- Add a "Live" indicator badge (green dot) next to the queue name in the details header when the WS stream is open.

**Files affected**:
- `QueueDetailsEnhanced.tsx` — subscribe/unsubscribe WS stream
- `websocketService.ts` — expose `onConnect` / `onDisconnect` callbacks from `createMessageStreamService`

**Test coverage to add**:
- E2E: navigate to queue details, publish a message via REST API, assert the new message appears in the details table within 5 seconds without a page reload

---

## 3. Real-time Test Coverage — Planned New Specs

The specs below were proposed by this draft. **Most now exist** (see §1.5 and §6.7) — `causation-tree`, `aggregate-stream`, `queue-updates-sse`, `message-sse-stream`, `system-metrics-sse`, `overview-live-stats-update`, `consumer-groups-validation`, `api-error-paths`, and the setup-error-toast specs all landed. The remaining list is kept for the few items not yet covered.

### 3.1 Negative / error-recovery tests

**File**: additions to existing spec files

| Spec | Test | What to verify |
|---|---|---|
| `queue-management.spec.ts` | Duplicate queue name | Attempt to create a queue that already exists → error message visible inside the modal, modal stays open |
| `queue-management.spec.ts` | Invalid queue name (hyphens) | Submit name `bad-queue-name` → backend or frontend validation error shown |
| `event-store-management.spec.ts` | Duplicate event store name | Same name twice → error in modal |
| `database-setup.spec.ts` | Duplicate setup ID | Create `default` twice → error in modal |
| `database-setup.spec.ts` | Wrong DB credentials | Submit setup with incorrect password → backend returns error → error shown in modal |

### 3.2 Real-time WS/SSE receipt tests

**File**: new `realtime-notifications.spec.ts`

| Test | Mechanism | What to prove |
|---|---|---|
| WS health check delivers message | `page.evaluate` → native browser `WebSocket` to `/ws/health` | One message received with `status: "UP"` and `type: "websocket"` proves the WS channel is open end-to-end from the browser |
| SSE health check delivers event | `page.evaluate` → `fetch('/api/v1/sse/health')` reads the response stream | Body contains `"status":"UP"` and `"type":"sse"` |
| Overview WS/SSE status indicators | Navigate to `/`, assert `data-testid="websocket-status"` and `data-testid="sse-status"` contain "Connected" | UI state correctly reflects live channel status |
| Queue message delivered via SSE | Create setup + queue via REST API (not UI); open `EventSource` in `page.evaluate`; POST message; assert event received within 10 s | The SSE queue stream endpoint (`/api/v1/queues/{id}/{q}/stream`) works end-to-end |
| Queue message delivered via WebSocket | Same setup; open native `WebSocket` to `/ws/queues/{id}/{q}`; POST message; assert WS frame received within 5 s | The WS queue stream endpoint works end-to-end |

---

## 4. Worker and execution model rationale

`playwright.config.ts` sets `workers: 1`. This is intentional and must not be changed back to `undefined` (which defaults to half the available CPU cores).

The test suite shares a single `SETUP_ID = 'default'` across spec files. When multiple workers run in parallel:
- Two workers simultaneously attempt to create the `default` setup
- The first succeeds; the second hits a duplicate-ID error
- The second then continues with a setup that does not exist in its intended state
- Flaky failures cascade from that point forward

Single-worker execution makes the setup-creation step idempotent (one worker checks existence, creates if missing, moves on). The full suite passes reliably with `workers: 1`.

If parallelism is needed in future, the fix is to assign each spec file a unique `SETUP_ID` rather than reverting `workers`.

---

## 5. Open questions

1. **Management events backend endpoint**: Does the backend team plan to add a `type: "management_event"` message to `/ws/monitoring`, or is a separate `/ws/management` endpoint preferred? The frontend implementation of Enhancement 2.1 depends on this.
   - *Answered 2026-06-14:* No such producer exists. `/ws/monitoring` (`SystemMonitoringHandler`) only emits `welcome` / `system_stats` / `pong` / `configured` / `error`, and `grep management_event` over `peegeeq-rest` returns nothing. The frontend listener at `Overview.tsx:190` is dead until the backend emits the message. (Meanwhile the bell already works from client-side resource events — §6.5.)
2. **SSE queue updates endpoint**: `GET /api/v1/sse/queues/{setupId}` is referenced in `websocketService.ts` but it is unclear whether this endpoint exists in the current backend or is planned. Needs confirmation before Enhancement 2.2 proceeds.
   - *Answered 2026-06-14:* The endpoint exists and is real — `PeeGeeQRestServer` route → `ServerSentEventsHandler.handleQueueUpdates` (consumes event-bus address `peegeeq.queues.changed.{setupId}` and forwards as `queue-changed` SSE events). It is already consumed by `QueuesEnhanced.tsx:126`. See the caveat under §2.2 / §6.4 about *what* it actually pushes.
3. **Notification persistence**: Should dismissed notifications be stored in `localStorage` across page reloads, or only held in memory for the current session?
4. **Authentication**: The user menu currently shows a hardcoded "Admin" label with a logout item that does nothing. Is there a planned authentication layer that would supply a real username and drive the logout action?

---

## 6. Front-to-Back Flow Status (verified 2026-06-14)

**Method.** Each flow was traced through every layer: frontend call site (file:line and the URL it builds) → REST route in `peegeeq-rest/PeeGeeQRestServer.java` (`createRouter()`) → handler method → the core service / Postgres call it makes. Backend handler logic was read in full; the route table was cross-referenced.

**Basis.** This is a source review. Every status below is a fact fixed by the code: the wiring (which call reaches which handler reaches which core method) and the presence or absence of specific logic are determined by reading the source across all layers — not runtime guesses. Existing E2E specs are cited where present as extra corroboration. The event-store delete finding (§6.1) is a definite source-level conclusion, not a suspicion: the handler contains no removal logic.

**Status legend**
- ✅ **Wired** — full chain present and connected on both ends
- ⚠️ **Backend-only** — backend is real; the UI does not consume it
- ⚠️ **Partial** — connected, but does less than the label implies
- ❌ **Dead** — one end has no counterpart; the path can never complete
- ⛔ **Stub** — placeholder (no real work) on the relevant end(s)
- ❗ **Defect** — the code path returns success without performing the action it claims

Backend handlers (all in `peegeeq-rest`): router `PeeGeeQRestServer.java`; `ManagementApiHandler.java`, `QueueHandler.java`, `ServerSentEventsHandler.java`, `WebSocketHandler.java`, `SystemMonitoringHandler.java`, `DatabaseSetupHandler.java`.

### 6.1 Resource lifecycle (create / delete)

| Flow | Frontend | REST route → handler | Backend reality | Status |
|---|---|---|---|---|
| Create setup | `DatabaseSetups.tsx:163` POST `database-setup/create` | `POST /api/v1/database-setup/create` → `DatabaseSetupHandler.createSetup` | `setupService.createSetup` → schema + Flyway → DB | ✅ |
| Delete setup | `DatabaseSetups.tsx:129` DELETE `database-setup/{id}` | `DELETE /api/v1/database-setup/:setupId` → `DatabaseSetupHandler.destroySetup` | real | ✅ |
| Create queue | `QueuesEnhanced.tsx:164` POST `management/queues` | `POST /api/v1/management/queues` → `ManagementApiHandler.createQueue` (1060) | `setupService.addQueue` → DB; emits `publishQueueChanged` | ✅ |
| Delete queue (list) | `QueuesEnhanced.tsx:220` RTK `performQueueOperation {DELETE}` | `DELETE /api/v1/queues/:setupId/:queueName` → `deleteQueueByName` (2367) | purge messages + close factory + deregister | ✅ (purges + deregisters; shared tables not dropped) |
| Delete queue (details) | `QueueDetailsEnhanced.tsx:182` DELETE `queues/{s}/{q}` | same as above | real | ✅ |
| Create event store | `EventStores.tsx:422` POST `management/event-stores` | `POST /api/v1/management/event-stores` → `createEventStore` (1584) | `setupService.addEventStore` → table created | ✅ |
| **Delete event store** | `EventStores.tsx:174` DELETE `management/event-stores/{storeId}` | `DELETE /api/v1/management/event-stores/:storeId` → `deleteEventStore` → `deleteEventStoreImpl` (1796) | **Verifies the store exists, then returns `"deleted successfully"` — performs no `getEventStores().remove(...)` and no DROP.** A subsequent list (`getEventStores`, which reads `setupResult.getEventStores()`) would still show it. Contrast with queue delete, which does deregister. | ❗ |
| Create consumer group | `ConsumerGroups.tsx:193` POST `management/consumer-groups` | `POST /api/v1/management/consumer-groups` → `createConsumerGroup` (~1270) | `subscriptionService.subscribe` → DB | ✅ |
| Delete consumer group | `ConsumerGroups.tsx:178` DELETE `management/consumer-groups/{s}/{q}/{g}` | `DELETE …/consumer-groups/:setupId/:queueName/:groupName` → `deleteConsumerGroup` (1345) | `subscriptionService.cancel` → DB | ✅ |
| Setup "View Details" action | `DatabaseSetups.tsx` row action → `setup-details-modal` | `GET /api/v1/setups/:setupId` → `getSetupDetails` | real modal (§7.4) | ✅ |

**Asymmetry:** event-store *create* is real but *delete* is a no-op — `deleteEventStoreImpl` contains no removal logic, visible directly in the source.

### 6.2 Queue operations

| Flow | Frontend | REST route → handler | Backend reality | Status |
|---|---|---|---|---|
| Publish message | `QueueDetailsEnhanced.tsx:226` POST `queues/{s}/{q}/messages` | `POST /api/v1/queues/:setupId/:queueName/messages` → `QueueHandler.sendMessage` | `producer.send(...)` (`QueueHandler.java:482`) → DB | ✅ |
| Get messages (browse) | `QueueDetailsEnhanced.tsx:250` GET `queues/{s}/{q}/messages` | `GET …/messages` → `getQueueMessages` (2108) | `createBrowser().browse()` → DB | ✅ |
| Pause / Resume | `QueueDetailsEnhanced.tsx:116` POST `queues/{s}/{q}/pause`\|`resume` | `POST …/pause`, `…/resume` → `pauseQueue` (2255) / `resumeQueue` (2311) | lists subs → `subscriptionService.pause`/`resume` each | ✅ |
| Purge (details page) | `QueueDetailsEnhanced.tsx:146` POST `queues/{s}/{q}/purge` | `POST …/purge` → `purgeQueue` (2207) | `queueFactory.purgeMessages` → DB | ✅ |
| Purge (list page) | `QueuesEnhanced.tsx` `handlePurgeQueue` → `showPurgeConfirm` | `POST …/purge` → `purgeQueue` (2207) | real (§7.3) | ✅ |
| Queue details (Overview tab) | `QueueDetailsEnhanced.tsx` RTK `useGetQueueDetailsQuery` | `GET /api/v1/queues/:setupId/:queueName` → `getQueueDetails` (1875) | live stats + subscription-derived status | ✅ |

**Purge split:** the same labelled action is real on the Queue Details page but a no-op toast on the Queues list page.

### 6.3 Queue Details tabs

| Tab | Frontend | Backend endpoint | Status |
|---|---|---|---|
| Overview | real cards (`QueueDetailsEnhanced.tsx:493`) | `getQueueDetails` (1875) | ✅ |
| Messages | real (publish / get / table) | as §6.2 | ✅ |
| **Consumers** | real `Table` fetching `/consumers` (`QueueDetailsEnhanced.tsx`, commit 518517bb) | `getQueueConsumers` (1994) returns **real** subscription data | ✅ wired (2026-06-16, §7.2) |
| **Bindings** | stub banner "Coming in Week 5" (`:637`) | `getQueueBindings` (2067) **always returns an empty array** | ⛔ both ends (PeeGeeQ has no binding concept) |
| **Charts** | stub banner "Coming in Week 2" (`:649`) | none | ⛔ |

### 6.4 Real-time channels

| Channel | Frontend | Backend | Status | E2E spec |
|---|---|---|---|---|
| System stats WS `/ws/monitoring` | `Overview.tsx:183` `createSystemMonitoringService` | `SystemMonitoringHandler.handleWebSocketMonitoring` → `system_stats` | ✅ | `overview-live-stats-update` |
| Metrics SSE `/sse/metrics` | `Overview.tsx` `createSystemMetricsSSE` | `SystemMonitoringHandler.handleSSEMetrics` | ✅ | `system-metrics-sse` |
| Queue-updates SSE `/sse/queues/{setupId}` | `QueuesEnhanced.tsx:126` `createQueueUpdatesSSE` → `refetch()` | `ServerSentEventsHandler.handleQueueUpdates` (58): event bus `peegeeq.queues.changed.{setupId}` → SSE | ⚠️ wired, but the bus event fires **only on queue create/update/delete** (`publishQueueChanged`, 2430) — not on message publish/purge, so no live message-count | `queue-updates-sse` |
| Message-stream SSE `/queues/{s}/{q}/stream` | `MessageBrowser.tsx:195` native `EventSource` | `ServerSentEventsHandler.handleQueueStream` (58→534/640): `createConsumer` + `subscribe` → SSE `data` events | ✅ | `message-sse-stream`, `message-browser-sse-failure` |
| Message-stream WS `/ws/queues/{s}/{q}` | `createMessageStreamService` exists but **intentionally unused by the admin UI** — Queue Details uses a non-destructive browse-poll instead (§7.5) | `WebSocketHandler.handleQueueStream` — **non-consuming by design** (a consuming impl was added then reverted: admin views must not consume/ack) | ⚪ not used by admin UI (non-destructive browse, §7.5) | `queue-details-live-messages` |
| WS health `/ws/health` | `ConnectionStatus.tsx` | inline in `PeeGeeQRestServer` (201–211) | ✅ | `connection-status` |
| SSE health `/api/v1/sse/health` | `configService.ts:125` ping | inline in `PeeGeeQRestServer` (346–362) | ✅ | `settings-ping-utilities` |
| REST health `/api/v1/health` | `ConnectionStatus` / Settings | inline (333) | ✅ | — |

### 6.5 Notification bell

| Source | Mechanism | Status |
|---|---|---|
| Resource events (setup / queue / event-store create & delete) | `addNotification(...)` in frontend success handlers — `DatabaseSetups.tsx:131,168`, `QueuesEnhanced.tsx:166,226`, `EventStores.tsx:176,424` → `managementStore` → badge `unreadCount` (`Header.tsx:101`) + drawer | ✅ client-side only (no backend round-trip for the notification itself) |
| Backend `management_event` over `/ws/monitoring` | `Overview.tsx:190` `else if (msg.type === 'management_event')` → `addNotification` | ❌ **dead** — `/ws/monitoring` never emits this type; `grep management_event` over `peegeeq-rest` = 0 hits. The listener can never fire. |

Consumer-group changes do **not** feed the bell (no `addNotification` call). The bell **is** wired (contradicting §1.4) — but only from client-side resource events.

### 6.6 Fully-stub pages (unchanged from §1.6)

Developer Portal, Schema Registry, Queue Designer, Monitoring — `<Empty>`-only, no endpoints. ⛔ Confirmed.

### 6.7 Discrepancies vs the 2026-05-30 draft

1. **Notification bell** (§1.4, §1.6) — draft says inert (`count={0}`, no store/handler). Now fully wired client-side; only the backend `management_event` path is dead. → §6.5
2. **Queue-updates SSE** (§1.2, Enhancement §2.2) — draft says "not connected to any page." It is connected (`QueuesEnhanced.tsx:126`). Caveat: fires on queue lifecycle, not message count. → §6.4
3. **Message-stream SSE** (§1.2 "No factory exists / not implemented") — implemented: Message Browser opens a native `EventSource` to `/queues/{s}/{q}/stream`. → §6.4
4. **Real-time receipt tests** (§3.2 "planned") — already exist: `queue-updates-sse.spec.ts`, `message-sse-stream.spec.ts`, `system-metrics-sse.spec.ts`, `overview-live-stats-update.spec.ts`, `message-browser-sse-failure.spec.ts`. (§1.5 has been rewritten to the current 47-file suite.)
5. **Event-store delete** (not noted in draft) — `deleteEventStoreImpl` returns a success response but contains no removal logic, so the store is not deleted (§6.1 ❗).

---

## 7. TDD Implementation Plan (2026-06-14)

**Process**: write the failing spec first, implement to green, verify the full suite passes, then stop for sign-off before the next phase.

**Backend test mandate**: for every phase that touches `peegeeq-rest` or `peegeeq-db`, "write the failing spec first" means writing **both** a Playwright E2E spec **and** a JUnit `@Tag("integration")` test in the relevant backend test class. The JUnit test must be written, confirmed failing against the current code, and listed in the phase plan before implementation begins. A phase with backend changes that only has a Playwright spec is not done — the JUnit test is required. Tests that touch the database must use a real `PostgreSQLContainer` via TestContainers; no Mockito, no H2, no in-memory substitutes.

### Phase order

> **Codebase review 2026-06-14** refined Phases 6 and 7: both are largely already implemented. Phase 6 (reconnecting UI) only needs E2E coverage and SSE parity checks. Phase 7 (toasts) only needs Consumer Groups. A new Phase 4a was added for the missing header title mappings.

| # | What it delivers | Layer(s) | Prerequisite |
|---|---|---|---|
| 1 | Fix event-store delete | `peegeeq-rest` | — | ✅ Complete |
| 2 | Queue Details — Consumers tab wired | `peegeeq-management-ui` | — | ✅ Complete (commit 518517bb) |
| 3 | Queues list — Purge action wired | `peegeeq-management-ui` | — | ✅ Complete |
| 4 | Database Setups — View Details modal | `peegeeq-management-ui` | — | ✅ Complete |
| 4a | Header title mapping (quick fix) | `peegeeq-management-ui` | — | ✅ Complete (2026-06-16) |
| 5 | Queue Details live messages (non-destructive browse-poll; WS consume approach rejected) | `peegeeq-management-ui` | — | ✅ Complete (2026-06-17) |
| 6 | Reconnection UI — E2E coverage + SSE parity *(UI already implemented)* | `peegeeq-management-ui` | — | ✅ Complete (2026-06-16) |
| 7 | Consumer Groups success toasts *(other resource flows already done)* | `peegeeq-management-ui` | — | ✅ Complete (2026-06-23) |
| 7a | Notifications page (`/notifications`) | `peegeeq-management-ui` | — | ✅ Complete (2026-06-23) |
| 8 | Backend `management_event` + bell end-to-end | `peegeeq-rest` + `peegeeq-management-ui` | 7 |
| 9 | Live queue message count via SSE | `peegeeq-rest` + `peegeeq-management-ui` | — |
| 10 | Authentication layer | TBD — architecture decision required | — |
| 11 | Split `activeConnections` — meaningful connection metrics | `peegeeq-rest` + `peegeeq-management-ui` | — |
| 12 | Non-destructive live message stream (LISTEN/NOTIFY → browse → push; replace the consuming SSE/WS reads) | `peegeeq-api` + `peegeeq-native` + `peegeeq-outbox` + `peegeeq-rest` + `peegeeq-management-ui` | — |
| 13 | Destructive-read safeguards (remove latent consuming clients; naming contract; guard test) | `peegeeq-management-ui` + `peegeeq-rest` | 12 |

Phases 1–4a are the lowest-risk deliveries: Phase 1 is a confirmed defect, Phases 2–4 have a fully-real backend endpoint waiting for a UI stub to be removed, Phase 4a is a two-line lookup-table fix. Phases 5–9 add new real-time behaviour. Phase 10 is gated on an architecture call (§7.10). Phase 11 replaces the meaningless `activeConnections` composite metric with three distinct, accurately named connection dimensions (see §8.3 and §7.11).

---

### 7.1 Fix event-store delete ✅ Complete (verified 2026-06-15)

**Source**: §6.1 ❗. `deleteEventStoreImpl` (`ManagementApiHandler.java:1796`) verifies the store exists and returns `"deleted successfully"` but performs no removal.

**Failing test (write first)**

Add to `src/tests/e2e/specs/event-store-management.spec.ts`:
```
test('delete event store — store no longer appears in list after deletion')
  1. Create an event store with a unique name via the UI
  2. Verify it appears in the table
  3. Three-dot → Delete → confirm
  4. Assert the store name no longer appears in the table
  5. Reload the page; assert it is still absent
```

**Implementation**

`ManagementApiHandler.java:deleteEventStoreImpl` — after confirming the store exists, add the removal steps that mirror `deleteQueueByName` (line 2367): retrieve the `EventStore` instance, call its shutdown/close method, drop the underlying Postgres table via the same teardown path that `addEventStore` uses in reverse, then call `setupResult.getEventStores().remove(storeName)`. Only then send the success response.

**Acceptance**: delete → list shows the store absent; E2E test green; all 47 existing specs pass.

**Verification (2026-06-15)**: Both the backend fix and the E2E test are present and match the spec above. `PeeGeeQDatabaseSetupService.removeEventStore()` closes the store, drops both Postgres tables (`CASCADE`), deregisters from `setup.getEventStores()`, and clears `eventStoreConfigs`. `ManagementApiHandler.deleteEventStoreImpl` delegates to this via `RestDatabaseSetupService`. The E2E test at `event-store-management.spec.ts:236` creates a uniquely-named store, deletes via the UI, asserts absence, reloads and re-asserts — exactly matching the plan.

**Backend JUnit test gap** — ✅ CLOSED (2026-06-17). `EventStoreIntegrationTest.testDeleteEventStoreRemovesItFromSetupListing` (`@Tag(INTEGRATION)`) now asserts the handler-level contract against a real TestContainers PostgreSQL:

```
testDeleteEventStoreRemovesItFromSetupListing
  1. POST /api/v1/management/event-stores  { name, setup, biTemporalEnabled, retentionDays } → assert 200/201
  2. GET  /api/v1/setups/:setupId          → assert eventStores[] contains the new store
  3. DELETE /api/v1/management/event-stores/{setupId}-{name}  → assert 200/204
  4. GET  /api/v1/setups/:setupId          → assert eventStores[] no longer contains it
```

The `pg_tables` step from the original sketch was intentionally **omitted**: raw JDBC in tests is prohibited (testing-standards §10) and there is no REST surface that lists physical tables. The CASCADE table drop is covered at the service level (`PeeGeeQDatabaseSetupService.removeEventStore`); the setup-listing absence is the meaningful guard against re-introducing the no-op `deleteEventStoreImpl`.

---

### 7.2 Queue Details — Consumers tab wired ✅ Complete (verified 2026-06-16)

> **Status — Complete.** Delivered in commit `518517bb`. `QueueDetailsEnhanced.tsx` fetches
> `GET /api/v1/queues/:setupId/:queueName/consumers` into `consumers` state (with a
> `consumersLoading` flag), surfaces failures via `message.error`, and renders an Ant Design
> `Table` (Group Name, Topic, Status tag, …) with empty-state text — the "Coming in Week 5"
> stub banner is gone (only Bindings remains stubbed). E2E coverage:
> `queue-details-consumers.spec.ts` (5 tests — table-not-stub, row with group name, status
> tag, plus API setup/cleanup), passing in the full suite.

**Source**: §6.3. `QueueDetailsEnhanced.tsx:588` shows a stub banner. `GET /api/v1/queues/:setupId/:queueName/consumers` → `getQueueConsumers` (line 1994) is fully real.

**Response schema** (from source — the §0 column spec was aspirational; implement against what the endpoint actually returns):

```json
{
  "consumers": [
    {
      "groupName": "...",
      "topic": "...",
      "status": "ACTIVE|IDLE|DISCONNECTED",
      "subscribedAt": "ISO",
      "lastActiveAt": "ISO",
      "lastHeartbeatAt": "ISO",
      "heartbeatIntervalSeconds": N,
      "heartbeatTimeoutSeconds": N,
      "backfillStatus": "...",
      "backfillProcessedMessages": N,
      "backfillTotalMessages": N
    }
  ],
  "consumerCount": N
}
```

**Failing test (write first)**

New `src/tests/e2e/specs/queue-details-consumers.spec.ts`:
```
test('consumers tab shows active consumer data')
  1. Ensure a consumer group subscribed to the test queue exists
  2. Navigate to /queues/default/{queueName}, click the Consumers tab
  3. Assert the tab body shows a table, not the stub banner
  4. Assert at least one row with groupName and status visible
```

**Implementation**

`QueueDetailsEnhanced.tsx:588` — remove the stub banner; fetch `GET /api/v1/queues/{setupId}/{queueName}/consumers` (via `axios.get` or RTK, matching the pattern already used in the same file); render an Ant Design `Table` with columns: Group Name, Topic, Status (tag), Subscribed At, Last Active At, Last Heartbeat, Backfill Status, Backfill Progress. Keep the live consumer count on the tab label (`queue.consumers?.length`).

**Acceptance**: Consumers tab shows real subscription data; stub banner gone; all tests pass.

---

### 7.3 Queues list — Purge action wired ✅ Complete (verified 2026-06-16)

> **Status — Complete.** `QueuesEnhanced.tsx` `handlePurgeQueue` uses `showPurgeConfirm`,
> calls the purge API, and shows success/error toasts (the "Coming in Week 4" stub is gone).
> E2E coverage in `queue-management.spec.ts` (purge-from-list test).

**Source**: §6.2 ⛔. `QueuesEnhanced.tsx:250` fires `message.info('Purge functionality coming in Week 4')`. `POST /api/v1/queues/{s}/{q}/purge` → `purgeQueue` (2207) is real and already used by `QueueDetailsEnhanced.tsx:146`.

**Failing test (write first)**

Add to `src/tests/e2e/specs/queue-management.spec.ts`:
```
test('purge from queue list page empties the queue')
  1. Publish N messages to the test queue via REST API
  2. Navigate to /queues, verify message count column shows ≥ N
  3. Three-dot → Purge Messages → confirm modal
  4. Assert message count drops to 0 (after refetch)
```

**Implementation**

`QueuesEnhanced.tsx:250` — replace the `message.info` no-op with an `Ant Design Modal.confirm` (matching the existing Delete modal in the same file); on confirm call `POST /api/v1/queues/{setupId}/{queueName}/purge`; on success call `refetch()` and `message.success('Queue purged')`.

**Acceptance**: purge from list page empties the queue; all tests pass.

---

### 7.4 Database Setups — View Details modal ✅ Complete (verified 2026-06-16)

> **Status — Complete.** The row action opens a real `setup-details-modal` (the
> "View details coming soon" toast is gone). E2E coverage: `database-setup.spec.ts:237`
> ("view details modal shows setup configuration") and `overview-setup-details-modal.spec.ts`.

**Source**: §6.1 ⛔. `DatabaseSetups.tsx:185` fires `message.info('View details coming soon')`. `GET /api/v1/setups/:setupId` → `DatabaseSetupHandler.getSetupDetails` is real (`PeeGeeQRestServer.java:379`).

**Failing test (write first)**

Add to `src/tests/e2e/specs/database-setup.spec.ts`:
```
test('view details modal shows setup configuration')
  1. Navigate to /database-setups
  2. Three-dot → View Details on the default setup row
  3. Assert a modal opens (not a toast)
  4. Assert the modal body contains: setup ID, host, port, database name, schema
  5. Close button dismisses the modal
```

**Implementation**

`DatabaseSetups.tsx` — add `viewDetailsSetupId` state; on "View Details" click set it instead of calling `message.info`; fetch `GET /api/v1/setups/{setupId}` on change; render an Ant Design `Modal` with a `Descriptions` block (Setup ID, Host, Port, Database Name, Schema, Status, Created At — no password field). Mirror the existing Delete modal pattern in the same file.

**Acceptance**: View Details opens a real modal; info toast gone; all tests pass.

---

### 7.4a Header title mapping (quick fix) — ✅ Complete (2026-06-16)

> **Status — Complete 2026-06-16.** `Header.tsx` `pageTitle` now includes `/events`,
> `/causation-tree`, and `/aggregate-stream`; a new `resolvePageTitle()` helper matches the
> dynamic `/queues/:setupId/:queueName` route → "Queue Details" (the `/queues` list page
> stays "Queues" via exact match). E2E coverage: `header-page-title.spec.ts`, parameterized
> over all five routes including the list-page-vs-details guard. Registered in
> `playwright.config.ts` as project `header-page-title` — **note:** the config is an explicit
> per-spec project allow-list, so a new spec must be added there or it silently never runs.
> ✅ Confirmed green (2026-06-17).

**Source**: codebase review 2026-06-14. `Header.tsx:18` has a `pageTitle` lookup map that does not include `/events`, `/causation-tree`, `/aggregate-stream`, or `/queues/:setupId/:queueName`. Navigating to any of these shows the fallback `"PeeGeeQ Management"` as the page title. (Reconciliation 2026-06-16 confirmed `/events` is also missing — add it alongside the other three.)

**Failing test (write first)**

Add to the relevant spec files (e.g. `causation-tree.spec.ts`, `aggregate-stream.spec.ts`, `queue-details-overview.spec.ts`):
```
test('header shows correct page title on navigation')
  1. Navigate to /causation-tree
  2. Assert page header title text equals "Causation Tree"
  3. Navigate to /aggregate-stream
  4. Assert page header title text equals "Aggregate Stream"
  5. Navigate to /queues/default/{queueName}
  6. Assert page header title text equals "Queue Details" (or equivalent)
```

**Implementation**

`Header.tsx` — add three entries to the `pageTitle` map (line ~18):
- `'/causation-tree'` → `'Causation Tree'`
- `'/aggregate-stream'` → `'Aggregate Stream'`
- Match `/queues/:setupId/:queueName` (a dynamic segment) — use `location.pathname.startsWith('/queues/')` with a guard that excludes the list page `/queues` exactly, or use the same pattern already used for any other parameterised route in the file.

**Acceptance**: all three pages show their correct title in the header; fallback is not triggered; all tests pass.

---

### 7.5 Queue Details live messages (non-destructive)  ✅ Complete (rebuilt 2026-06-17)

> **Rebuilt 2026-06-17 — non-destructive.** Investigation found the WS `/stream` backend
> (`WebSocketHandler.startMessageStreaming`) was a TODO stub. An initial attempt implemented it
> as a consuming `createConsumer().subscribe()` stream — then **reverted**, because the
> management UI is an admin/observability tool and must **never consume/ack messages just to
> display them** (that would steal them from the application's real consumers). See memory
> *"Admin UI Non-Destructive Reads"*.
>
> **Implemented instead — browse-poll:** the Queue Details "Messages" tab gains a **Live** toggle
> (`data-testid="messages-live-switch"`, default OFF) that browse-polls the non-destructive
> `GET /queues/{s}/{q}/messages` (server-side `createBrowser().browse()`) every 3 s while the tab
> is active — **no consumer, never drains the queue**. A "Live · read-only (auto-refresh)"
> indicator and a tab badge show when on; polling stops on toggle-off / tab change / unmount and
> self-stops with one error toast on failure (`QueueDetailsEnhanced.tsx`).
>
> **Backend:** `WebSocketHandler.handleQueueStream` reverted to non-consuming, with a comment
> documenting that admin streams must not consume (a future real-time tail must use LISTEN/NOTIFY
> + browse, not `createConsumer/subscribe`); the consuming JUnit test was removed.
>
> **E2E:** `queue-details-live-messages.spec.ts` (registered project `7c3-queue-details-live-messages`) —
> **✅ confirmed green 2026-06-17**: (1) Live auto-surfaces a published message with no manual
> refresh; (2) viewing does **not** consume — the message is still browsable on a fresh load.
> (A rules-of-hooks bug — the live `useEffect` placed after the component's early returns — was
> caught by this spec and fixed; `npm run build`/tsc didn't flag it, `npm run lint` would have.)
>
> **Follow-up (now planned):** the existing SSE `/stream` "Live" toggle in Message Browser is
> still a consuming read — same violation of the non-destructive principle. Tracked as
> **Phase 12** (non-destructive live tail) and **Phase 13** (destructive-read safeguards).

**Original plan (superseded):** §6.4 marked the WS backend "real" (it was a stub). The original
intent was to wire `createMessageStreamService` (a consuming WS stream) into Queue Details. That
approach was dropped in favour of the non-destructive browse-poll above; `createMessageStreamService`
remains unused by the admin UI.

---

### 7.6 Reconnection UI — E2E coverage + SSE parity  ✅ Complete (2026-06-16)

> **Status — Complete 2026-06-16.**
> - **SSE parity (was missing, now implemented):** `createSystemMetricsSSE` passed no
>   `onReconnecting` callback and `SSEService` had no concept of one, so a dropped metrics SSE
>   went straight to orange "Disconnected" — the gold SSE "Reconnecting…" tag could never
>   appear. `SSEService` now maps a native EventSource auto-reconnect (`readyState` =
>   `CONNECTING`) to `onReconnecting`, threaded through `createSystemMetricsSSE` →
>   `Overview.tsx` `setSseReconnecting(true)`, mirroring the WS path. Its error log was also
>   downgraded `console.error` → `console.warn`.
> - **E2E coverage added:** `overview-reconnect-recovery.spec.ts` (WS dropped then proxied
>   back to the real backend → tag returns to green "Connected"), `overview-sse-reconnecting-banner.spec.ts`
>   (SSE abort → gold "Reconnecting…"), and `overview-stats-values.spec.ts` (strengthened
>   §8.1/§8.2 value assertions: `activeConnections` a non-negative integer, `messagesPerSecond`
>   a finite non-negative number). The pre-existing `overview-reconnecting-banner.spec.ts`
>   already covers the WS reconnecting tag. (These use `page.routeWebSocket` / `page.route`
>   fault injection rather than the invalid-URL-in-Settings approach sketched below — more
>   reliable and self-contained.)
> - **Backend reconnection tests** also added in `SystemMonitoringHandlerTest`:
>   `testWebSocketReconnectAfterDropResumesWithFreshSession` and
>   `testSseReconnectResumesWithFreshEventNumbering` (confirming clean re-accept — fresh
>   monotonic `connectionId`, stream resumes, counter stays consistent). Note: there is **no
>   session-resume protocol** (no `Last-Event-ID`); reconnection means clean re-accept, not
>   event replay — a possible future enhancement.
> - **Local retries:** `playwright.config.ts` now uses `retries: process.env.CI ? 2 : 1` so a
>   transient headed-browser death self-heals instead of failing the run.
> - **⚠️ Registration correction (2026-06-16):** the Playwright config is an explicit per-spec
>   project allow-list. The three specs above were initially **not registered and therefore
>   never ran** in the suite (the earlier "448 passed" did not include them). They are now
>   registered as projects `10f-overview-reconnect-recovery`, `10g-overview-sse-reconnecting-banner`,
>   and `10h-overview-stats-values`, and are now **✅ confirmed green (2026-06-17)** — note `10f`
>   was rewritten to a stateful fault-injection pattern after a StrictMode double-mount made the
>   transient reconnecting window unobservable. The backend reconnection tests
>   (`SystemMonitoringHandlerTest`) also pass.

**Source**: §2.4 and codebase review 2026-06-14. The codebase review confirmed that the yellow `"Reconnecting…"` tag is **already implemented**: `Overview.tsx:314` renders it using `wsReconnecting` / `sseReconnecting` from the Zustand store, and the WS service `onReconnecting` callback is already wired. No implementation work is needed for WS reconnection.

This phase is therefore scoped to:
1. E2E test coverage for the existing reconnecting state (currently no spec asserts this transition).
2. Verify SSE reconnection parity — confirm that `sseReconnecting` in the store is driven by an equivalent `onReconnecting` callback from the SSE service, and add it if missing.

**Failing test (write first)**

New `src/tests/e2e/specs/connection-recovery.spec.ts`:
```
test('WS status shows Reconnecting state after connection is lost')
  1. Navigate to /; assert websocket-status = "Connected"
  2. Configure an invalid WS URL in Settings; return to /
  3. Assert websocket-status tag transitions through "Reconnecting" (yellow) before reaching "Disconnected"

test('SSE status shows Reconnecting state after connection is lost')
  1. Same pattern for sse-status tag
```

**Implementation**

Check only: if the SSE service (`createSystemMetricsSSE`, `createSystemMonitoringService`) does not have an `onReconnecting` callback driving `sseReconnecting`, add one to match the WS pattern. No WS-side changes needed.

**Acceptance**: both WS and SSE reconnecting states are asserted by E2E tests; states clear on successful reconnect; all tests pass.

---

### 7.7 Consumer Groups success toasts — ✅ Complete (2026-06-23)

> **Status — Complete 2026-06-23.** `ConsumerGroups.tsx` `handleCreateModalOk` and
> `handleDeleteGroup` now fire `message.success(...)` on success (mirroring the
> `DatabaseSetups`/`QueuesEnhanced`/`EventStores` pattern). E2E: the creation toast is
> asserted in `consumer-groups-validation.spec.ts` test 03 (which was already titled
> "...and shows success toast" but never asserted it), and the deletion toast in the
> existing delete-via-UI test in `consumer-groups-scope-selectors.spec.ts`. Both green
> (projects `13b` / `13`). No new spec file was needed — the doc's original
> `consumer-groups.spec.ts` target does not exist; the assertions were added to the two
> registered specs that already exercise these flows.

**Source**: §2.3 and codebase review 2026-06-14. The codebase review confirmed that `DatabaseSetups.tsx`, `QueuesEnhanced.tsx`, and `EventStores.tsx` **already fire `message.success(...)`** on create and delete. The only resource type that does not is `ConsumerGroups.tsx`: both `handleCreateModalOk` and `handleDeleteGroup` close silently on success.

**Failing tests (write first)**

Add to `src/tests/e2e/specs/consumer-groups.spec.ts`:
```
test('shows success toast after consumer group creation')
  1. Create a consumer group via the UI
  2. Assert .ant-message-success is visible

test('shows success toast after consumer group deletion')
  1. Delete a consumer group via the UI
  2. Assert .ant-message-success is visible
```

**Implementation**

`ConsumerGroups.tsx` — add `message.success('Consumer group created')` at the end of `handleCreateModalOk`'s success branch, and `message.success('Consumer group deleted')` at the end of `handleDeleteGroup`'s success branch. No other files need changes.

**Acceptance**: consumer group create and delete each show a success toast; all tests pass.

---

### 7.7a Notifications page (`/notifications`) — ✅ Complete (2026-06-23)

> **Status — Complete 2026-06-23.** New `NotificationsPage.tsx` reads the `managementStore`
> notification feed and renders an Ant `Table` (Timestamp, Action, Resource, Status tag
> New/Read) with **Mark All Read** (`markAllNotificationsRead()`) and **Clear All**
> (`clearNotifications()` behind a `Modal.confirm`), plus an `<Empty>` state. `App.tsx`
> gains the `/notifications` route and a `nav-notifications` sidebar item (`BellOutlined`)
> after Message Browser; `Header.tsx` `pageTitle` gains `'/notifications': 'Notifications'`.
> E2E `notification-page.spec.ts` (3 tests — history shows a "New" row after a UI
> queue-create, Mark All Read clears the tags + resets the bell badge, Clear All empties
> the list + badge 0), registered as project `15-notifications-page` (depends on
> `3c-setup-prerequisite`). Type-check + lint clean; full project green. The feed remains
> in-memory (resets on reload) until Phase 8 adds the backend `management_event` source.

**Design**: the existing bell drawer (§1.4) is unchanged — it remains the quick-glance surface. The notifications page is a full history view, reachable from a new sidebar nav item. Both read from the same `managementStore` slice.

**What the page shows**: the full `notifications` array from `managementStore` (capped at 50, reset on page reload until Phase 8 adds a backend source). A table with columns derived from `ManagementNotification` — at minimum: Timestamp, Action (create / delete), Resource Type (setup / queue / event store / consumer group), Resource Name, Read (tag: New / Read). Page-level actions: **Mark All Read** (`markAllNotificationsRead()`) and **Clear All** (`clearNotifications()`). Empty state when the array is empty.

**Nav item**: add to `App.tsx` sidebar between Message Browser and the bottom of the list. Icon: `BellOutlined`. Route: `/notifications`. Testid: `nav-notifications`.

**Failing test (write first)**

New `src/tests/e2e/specs/notification-page.spec.ts`:
```
test('notifications page shows events from resource actions')
  1. Create a queue via the UI (generates a notification)
  2. Navigate to /notifications via the sidebar nav item
  3. Assert the page renders a table (not empty state)
  4. Assert the table contains a row for the queue creation (resource name visible)
  5. Assert the row has a "New" read status tag

test('mark all read clears unread status')
  1. Navigate to /notifications with at least one unread entry
  2. Click Mark All Read
  3. Assert no rows show the "New" tag
  4. Assert the bell badge in the header resets to 0

test('clear all empties the notifications list')
  1. Navigate to /notifications with at least one entry
  2. Click Clear All
  3. Assert the empty state is shown
  4. Assert the bell badge in the header shows 0
```

**Implementation**

New file: `peegeeq-management-ui/src/pages/NotificationsPage.tsx`
- Read `notifications` and `unreadCount` from `managementStore`.
- Render an Ant Design `Table` with columns built from the `ManagementNotification` type fields. Check the actual type definition in `managementStore.ts` and map all available fields to columns; do not invent fields.
- **Mark All Read** button calls `markAllNotificationsRead()`.
- **Clear All** button calls `clearNotifications()` (with an `Ant Design Modal.confirm`).
- Empty state: Ant Design `<Empty>` with text "No notifications yet".

`App.tsx`:
- Import and add route `<Route path="/notifications" element={<NotificationsPage />} />`.
- Add nav item `{ key: '/notifications', icon: <BellOutlined />, label: <Link to="/notifications" data-testid="nav-notifications">Notifications</Link> }` after the Message Browser item.

**After Phase 8**: backend `management_event` messages will also feed `addNotification`, so the page will populate from backend events without any further frontend change.

**Acceptance**: nav item navigates to the page; table shows notification history; Mark All Read and Clear All work; bell badge reflects unread count correctly after both actions; all tests pass.

---

### 7.8 Backend management_event + notification bell end-to-end

**Source**: §6.5 ❌ and §2.1. `Overview.tsx:190` listens for `msg.type === 'management_event'` on `/ws/monitoring` and calls `addNotification`. The backend never emits it (`grep management_event` over `peegeeq-rest` = 0 hits).

Prerequisite: Phase 7.7 (toasts) done — confirms the frontend CRUD paths are clean before adding another side-effect.

**Backend failing tests (write first — JUnit)**

New test class `ManagementEventPublishingIntegrationTest.java` in `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/`. Follows `ManagementApiIntegrationTest` for class structure and container creation, and `SystemMonitoringHandlerTest` for the WebSocket assertion idiom:

```java
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementEventPublishingIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ManagementEventPublishingIntegrationTest.class);
    private static final int TEST_PORT = 18114;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_mgmt_event_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private String deploymentId;
    private WebClient webClient;
    private WebSocketClient wsClient;
    private String testSetupId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "mgmt-event-" + System.currentTimeMillis();
        webClient = WebClient.create(vertx);
        wsClient = vertx.createWebSocketClient();
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .timeout(30000)
                    .sendJsonObject(new JsonObject()
                        .put("setupId", testSetupId)
                        .put("databaseConfig", new JsonObject()
                            .put("host", postgres.getHost())
                            .put("port", postgres.getFirstMappedPort())
                            .put("databaseName", "mgmt_event_db_" + System.currentTimeMillis())
                            .put("username", postgres.getUsername())
                            .put("password", postgres.getPassword())
                            .put("schema", "peegeeq_test")
                            .put("templateDatabase", "template0")
                            .put("encoding", "UTF8"))
                        .put("queues", new JsonArray())
                        .put("eventStores", new JsonArray()))
                    .compose(r -> r.statusCode() == 201 || r.statusCode() == 200
                        ? Future.succeededFuture()
                        : Future.failedFuture("Setup failed: " + r.statusCode() + " " + r.bodyAsString()));
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (wsClient != null) wsClient.close();
        if (webClient != null) webClient.close();
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test @Order(1) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testManagementEventEmittedAfterQueueCreation(Vertx vertx, VertxTestContext testContext) {
        String newQueue = "mgmt_event_q_" + System.currentTimeMillis();
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
            .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        wsClient.connect(opts)
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);

                        if ("welcome".equals(msg.getString("type")) && !mutationTriggered.getAndSet(true)) {
                            webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
                                .putHeader("content-type", "application/json")
                                .timeout(10000)
                                .sendJsonObject(new JsonObject()
                                    .put("setupId", testSetupId)
                                    .put("name", newQueue)
                                    .put("type", "native"))
                                .onFailure(testContext::failNow);
                        }

                        if ("management_event".equals(msg.getString("type"))) {
                            assertEquals("create", msg.getString("action"));
                            assertEquals("queue", msg.getString("resource"));
                            assertEquals(newQueue, msg.getString("name"));
                            assertNotNull(msg.getLong("timestamp"));
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                vertx.setTimer(25000, id -> {
                    if (!testContext.completed()) {
                        ws.close();
                        testContext.failNow(new AssertionError(
                            "management_event frame not received within 25s after queue creation"));
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test @Order(2) @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testManagementEventNotEmittedForMessagePublish(Vertx vertx, VertxTestContext testContext) {
        String queueName = "mgmt_event_msg_" + System.currentTimeMillis();
        AtomicBoolean managementEventReceived = new AtomicBoolean(false);
        AtomicBoolean welcomeReceived = new AtomicBoolean(false);

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
            .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(new JsonObject().put("setupId", testSetupId).put("name", queueName).put("type", "native"))
            .compose(r -> wsClient.connect(opts))
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        if ("welcome".equals(msg.getString("type")) && !welcomeReceived.getAndSet(true)) {
                            webClient.post(TEST_PORT, "localhost",
                                    "/api/v1/queues/" + testSetupId + "/" + queueName + "/messages")
                                .putHeader("content-type", "application/json")
                                .timeout(10000)
                                .sendJsonObject(new JsonObject()
                                    .put("payload", new JsonObject().put("test", true))
                                    .put("headers", new JsonObject()))
                                .onFailure(testContext::failNow);
                        }
                        if ("management_event".equals(msg.getString("type"))) {
                            managementEventReceived.set(true);
                        }
                    });
                });

                vertx.setTimer(3000, id -> testContext.verify(() -> {
                    assertFalse(managementEventReceived.get(),
                        "management_event must not be emitted for message-level publish operations");
                    ws.close();
                    testContext.completeNow();
                }));
            })
            .onFailure(testContext::failNow);
    }
}
```

These tests fail today: `grep management_event` over `peegeeq-rest` returns 0 hits — the WS server never emits this frame type. They pass after `publishManagementEvent()` is wired in `ManagementApiHandler` and `SystemMonitoringHandler` forwards events from the `peegeeq.management.events` event-bus address.

**Frontend failing tests (write first — Playwright)**

New `src/tests/e2e/specs/notification-bell.spec.ts`:
```
test('bell badge increments when a resource is created')
  1. Navigate to /; note bell badge count
  2. Create a queue via the UI
  3. Assert bell badge increments by 1 within 5 s
  4. Open drawer; assert it lists the create event
  5. Dismiss; assert badge resets to 0

test('management_event is emitted from backend WS')
  1. Open a native WebSocket to /ws/monitoring via page.evaluate
  2. Create a resource via REST API
  3. Assert a frame with type === "management_event" is received within 5 s
```

**Implementation**

**Backend**: add `publishManagementEvent(setupId, action, resource, name)` in `ManagementApiHandler` (mirroring `publishQueueChanged` at line 2430) that publishes to a new event-bus address, e.g. `peegeeq.management.events`. Call it after: setup create/delete, queue create/delete, event-store create/delete, consumer-group create/delete. In `SystemMonitoringHandler`: register a consumer on `peegeeq.management.events` and forward each event as a WS frame `{"type":"management_event","action":"...","resource":"...","name":"...","timestamp":...}`.

**Frontend**: `Overview.tsx:190` listener is already in place; verify the payload field names match what the backend emits and adjust if needed.

**Acceptance**: JUnit tests green; bell increments from a backend-emitted event; drawer shows the event; client-side notifications from resource events coexist; all Playwright tests pass.

---

### 7.9 Live queue message count via SSE

**Source**: §2.2 / §6.4. `QueuesEnhanced.tsx:126` already calls `refetch()` on each SSE `queue-changed` event. The gap is on the backend: `QueueHandler.sendMessage` does not call `publishQueueChanged` after a successful send (confirmed by source — 0 calls in that method), so the SSE never fires on message publish.

**Backend failing test (write first — JUnit)**

New test class `QueueHandlerIntegrationTest.java` in `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/`. Follows `SSEQueueUpdatesIntegrationTest` exactly — same container factory, same `httpClient` + `response.handler(buffer ->...)` idiom, same `AtomicBoolean mutationTriggered` pattern:

```java
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueHandlerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueHandlerIntegrationTest.class);
    private static final int TEST_PORT = 18115;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private String deploymentId;
    private WebClient webClient;
    private HttpClient httpClient;
    private String testSetupId;
    private static final String TEST_QUEUE = "qh_integ_test_queue";

    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "qh-integ-" + System.currentTimeMillis();
        webClient = WebClient.create(vertx);
        httpClient = vertx.createHttpClient();
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .timeout(30000)
                    .sendJsonObject(new JsonObject()
                        .put("setupId", testSetupId)
                        .put("databaseConfig", new JsonObject()
                            .put("host", postgres.getHost())
                            .put("port", postgres.getFirstMappedPort())
                            .put("databaseName", "qh_integ_db_" + System.currentTimeMillis())
                            .put("username", postgres.getUsername())
                            .put("password", postgres.getPassword())
                            .put("schema", "peegeeq_test")
                            .put("templateDatabase", "template0")
                            .put("encoding", "UTF8"))
                        .put("queues", new JsonArray()
                            .add(new JsonObject()
                                .put("queueName", TEST_QUEUE)
                                .put("maxRetries", 3)
                                .put("visibilityTimeoutSeconds", 30)))
                        .put("eventStores", new JsonArray()))
                    .compose(r -> r.statusCode() == 201 || r.statusCode() == 200
                        ? Future.succeededFuture()
                        : Future.failedFuture("Setup failed: " + r.statusCode() + " " + r.bodyAsString()));
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDownAll(Vertx vertx, VertxTestContext testContext) {
        if (httpClient != null) httpClient.close();
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private String sseUrl() {
        return "/api/v1/sse/queues/" + testSetupId;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test @Order(1) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSSEQueueChangedFiredAfterMessageSend(Vertx vertx, VertxTestContext testContext) {
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl())
            .compose(HttpClientRequest::send)
            .onSuccess(response -> response.handler(buffer -> {
                String data = buffer.toString();

                if (data.contains("event: connected") && !mutationTriggered.getAndSet(true)) {
                    webClient.post(TEST_PORT, "localhost",
                            "/api/v1/queues/" + testSetupId + "/" + TEST_QUEUE + "/messages")
                        .putHeader("content-type", "application/json")
                        .timeout(10000)
                        .sendJsonObject(new JsonObject()
                            .put("payload", new JsonObject().put("test", true))
                            .put("headers", new JsonObject()))
                        .onFailure(testContext::failNow);
                }

                if (data.contains("event: queue-changed") && data.contains(testSetupId)) {
                    testContext.verify(() -> {
                        assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\""));
                        assertTrue(data.contains("\"queueName\":\"" + TEST_QUEUE + "\""));
                    });
                    response.request().connection().close();
                    testContext.completeNow();
                }
            }))
            .onFailure(testContext::failNow);
    }

    @Test @Order(2) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSSEQueueChangedFiredAfterPurge(Vertx vertx, VertxTestContext testContext) {
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl())
            .compose(HttpClientRequest::send)
            .onSuccess(response -> response.handler(buffer -> {
                String data = buffer.toString();

                if (data.contains("event: connected") && !mutationTriggered.getAndSet(true)) {
                    webClient.post(TEST_PORT, "localhost",
                            "/api/v1/queues/" + testSetupId + "/" + TEST_QUEUE + "/purge")
                        .timeout(10000)
                        .send()
                        .onFailure(testContext::failNow);
                }

                if (data.contains("event: queue-changed") && data.contains(testSetupId)) {
                    testContext.verify(() ->
                        assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\"")));
                    response.request().connection().close();
                    testContext.completeNow();
                }
            }))
            .onFailure(testContext::failNow);
    }
}
```

These tests fail today: `QueueHandler.sendMessage()` (line ~482) never calls `publishQueueChanged`; only `ManagementApiHandler` does (line 2430). The SSE client receives nothing after a send. They pass after `publishQueueChanged(setupId, queueName)` is added to `QueueHandler.sendMessage()` and the purge and batch paths.

**Frontend failing test (write first — Playwright)**

Add to `src/tests/e2e/specs/queue-updates-sse.spec.ts`:
```
test('queue message count updates in real time after a message is published')
  1. Navigate to /queues; note message count for the test queue
  2. Publish a message via REST API directly
  3. Assert the message count column updates within 5 s — no manual refresh
```

**Implementation**

`QueueHandler.java` — in `sendMessage()`, after `producer.send(...)` succeeds, call `publishQueueChanged(setupId, queueName)` (the same event-bus publish that `ManagementApiHandler` uses at line 2430). Apply the same to the batch `sendMessages()` method and, if it is handled separately from `ManagementApiHandler.purgeQueue`, to the queue purge path.

**Frontend**: no change needed — the SSE consumer and `refetch()` are already wired.

**Acceptance**: message count updates within 5 s of a publish; SSE is the mechanism; all tests pass.

---

### 7.10 Authentication layer

Not yet implementable. The following are stubs with no decision made:

- `Header.tsx:155` — username hardcoded `"Admin"`.
- Profile / Logout click handlers are commented-out `console.log` stubs.
- No auth middleware in the REST layer.

Requires architecture decisions before implementation:
1. Authentication protocol (JWT, session cookie, OAuth2/OIDC)?
2. Does the backend supply the identity, or is there a separate IdP?
3. Session persistence across page reload?

Raise a separate plan for the auth layer once these are resolved.

---

### 7.11 Split `activeConnections` — meaningful connection metrics

**Source**: §8.3. The single `activeConnections` field in `system_stats` is a meaningless sum of management UI browser sessions and registered subscription count. PostgreSQL pool connections are entirely absent. All three must be tracked and surfaced separately.

#### Backend changes (`peegeeq-rest` + `peegeeq-db`)

**Step 1 — Query `pg_stat_activity` per setup**

Add a helper method to `SystemMonitoringHandler` (or `collectSetupMetrics`) that, for each active setup, executes the following query through that setup's pool:

```sql
SELECT
  COUNT(CASE WHEN state = 'active'  THEN 1 END) AS active,
  COUNT(CASE WHEN state = 'idle'    THEN 1 END) AS idle,
  COUNT(CASE WHEN state IS NULL     THEN 1 END) AS pending,
  COUNT(*)                                       AS total
FROM pg_stat_activity
WHERE application_name = $1
```

`$1` should be a per-setup application name such as `peegeeq-{setupId}`. Confirm that `PgConnectionManager` sets `application_name` in `PgConnectOptions` when creating pools (and add it if not — it is a standard property on `PgConnectOptions.setProperties(Map.of("application_name", "peegeeq-" + setupId))`).

**Step 2 — Call `updateConnectionPoolMetrics()` from `collectSetupMetrics`**

Retrieve the `PeeGeeQMetrics` instance for each setup and call `updateConnectionPoolMetrics(active, idle, pending)` with the values from Step 1. This brings the existing-but-dead Micrometer gauges (`peegeeq.connection.pool.active/idle/pending`) to life.

**Step 3 — Restructure `system_stats` payload**

Replace the single `activeConnections` integer with three named fields:

```json
{
  "type": "system_stats",
  "monitoringSessions": 2,
  "activeSubscriptions": 5,
  "dbPool": {
    "active": 12,
    "idle": 20,
    "pending": 0,
    "max": 64,
    "perSetup": [
      { "setupId": "default", "active": 8, "idle": 15, "pending": 0, "max": 32 }
    ]
  }
}
```

- `monitoringSessions` = `totalConnections.get()` (unchanged tracking logic)
- `activeSubscriptions` = current `activeConsumerConnections` (same computation, renamed)
- `dbPool.active/idle/pending/max` = aggregate across all setups; `perSetup` = per-setup breakdown
- `max` = sum of configured `pool.max-size` across all active setups (from `PgPoolConfig`)

Remove the `activeConnections` key. The `totalConnections` bug fix from §8.1 is a prerequisite for `monitoringSessions` to be accurate — complete Phase 8.1 first.

**Files affected (backend)**:
- `peegeeq-rest/…/handlers/SystemMonitoringHandler.java` — new `collectDbPoolMetrics(setupId)` helper; restructure the `.map()` block at line 484; remove `activeConnectionsTotal`
- `peegeeq-db/…/connection/PgConnectionManager.java` — add `application_name` property to `PgConnectOptions` if absent
- `peegeeq-db/…/metrics/PeeGeeQMetrics.java` — `updateConnectionPoolMetrics()` now called; verify it is thread-safe for the Vert.x event-loop context

#### Frontend changes (`peegeeq-management-ui`)

**`managementStore.ts`**

Update `SystemStats` type — remove `activeConnections`, add:
```ts
monitoringSessions: number
activeSubscriptions: number
dbPool: {
  active: number
  idle: number
  pending: number
  max: number
  perSetup: Array<{ setupId: string; active: number; idle: number; pending: number; max: number }>
}
```

Update `setSystemStats` action and `updateChartData` accordingly. The `connectionData` chart series changes to `{ time, active, idle, pending }` from `dbPool`.

**`Overview.tsx` — stats cards**

Replace the single "Active Connections" stats card with three separate cards:

| Card | Value | Colour | Icon |
|---|---|---|---|
| Monitoring Sessions | `stats.monitoringSessions` | blue | `MonitorOutlined` |
| Active Subscriptions | `stats.activeSubscriptions` | green | `TeamOutlined` |
| DB Connections | `stats.dbPool.active` / `stats.dbPool.max` (fraction display) | orange | `DatabaseOutlined` |

**`Overview.tsx` — Active Connections chart**

Replace the single-series area chart with a stacked area chart showing three series against the same time axis:

- `active` (orange / filled) — live DB connections doing work
- `idle` (blue / lighter fill) — connections open but waiting
- `pending` (red / thin line) — requests queued waiting for a pool slot

Add a dashed `ReferenceLine` at `y={stats.dbPool.max}` labelled "Pool max". This gives instant visual warning when the pool is saturating.

**Files affected (frontend)**:
- `peegeeq-management-ui/src/stores/managementStore.ts` — type + action updates
- `peegeeq-management-ui/src/pages/Overview.tsx` — stats cards + chart series

#### Backend failing tests (write first — JUnit)

New test class `SystemMonitoringHandlerConnectionMetricsTest.java` in `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/`. Follows `SystemMonitoringHandlerTest` for class structure, container creation, WebSocket client, and `textMessageHandler` + `testContext.verify` idiom:

```java
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemMonitoringHandlerConnectionMetricsTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemMonitoringHandlerConnectionMetricsTest.class);
    private static final int TEST_PORT = 18116;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_conn_metrics_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private String deploymentId;
    private WebClient client;
    private WebSocketClient wsClient;
    private String testSetupId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "conn-metrics-" + System.currentTimeMillis();
        client = WebClient.create(vertx);
        wsClient = vertx.createWebSocketClient();
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                return client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .timeout(30000)
                    .sendJsonObject(new JsonObject()
                        .put("setupId", testSetupId)
                        .put("databaseConfig", new JsonObject()
                            .put("host", postgres.getHost())
                            .put("port", postgres.getFirstMappedPort())
                            .put("databaseName", "conn_metrics_db_" + System.currentTimeMillis())
                            .put("username", postgres.getUsername())
                            .put("password", postgres.getPassword())
                            .put("schema", "peegeeq_test")
                            .put("templateDatabase", "template0")
                            .put("encoding", "UTF8"))
                        .put("queues", new JsonArray())
                        .put("eventStores", new JsonArray()))
                    .compose(r -> r.statusCode() == 201 || r.statusCode() == 200
                        ? Future.succeededFuture()
                        : Future.failedFuture("Setup failed: " + r.statusCode()));
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (client != null) client.close();
        if (wsClient != null) wsClient.close();
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test @Order(1) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSystemStatsPayloadContainsSplitConnectionFields(Vertx vertx, VertxTestContext testContext) {
        WebSocketConnectOptions opts = new WebSocketConnectOptions()
            .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        wsClient.connect(opts)
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        if ("system_stats".equals(msg.getString("type"))) {
                            JsonObject data = msg.getJsonObject("data");
                            assertNull(data.getInteger("activeConnections"),
                                "activeConnections field must be absent after payload restructure");
                            assertNotNull(data.getInteger("monitoringSessions"),
                                "monitoringSessions must be present");
                            assertTrue(data.getInteger("monitoringSessions") >= 1,
                                "monitoringSessions must be >= 1 while the observer WS is open");
                            assertNotNull(data.getInteger("activeSubscriptions"),
                                "activeSubscriptions must be present");
                            assertTrue(data.getInteger("activeSubscriptions") >= 0);
                            JsonObject dbPool = data.getJsonObject("dbPool");
                            assertNotNull(dbPool, "dbPool object must be present");
                            assertNotNull(dbPool.getInteger("active"), "dbPool.active must be present");
                            assertNotNull(dbPool.getInteger("idle"),   "dbPool.idle must be present");
                            assertNotNull(dbPool.getInteger("pending"), "dbPool.pending must be present");
                            assertNotNull(dbPool.getInteger("max"),    "dbPool.max must be present");
                            assertNotNull(dbPool.getJsonArray("perSetup"), "dbPool.perSetup must be present");
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                vertx.setTimer(25000, id -> {
                    if (!testContext.completed()) {
                        ws.close();
                        testContext.failNow(new AssertionError(
                            "system_stats frame not received within 25s"));
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test @Order(2) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testDbPoolValuesAreNonNegativeAcrossMultipleFrames(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger frameCount = new AtomicInteger(0);
        AtomicBoolean configured = new AtomicBoolean(false);

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
            .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        wsClient.connect(opts)
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);

                        if ("welcome".equals(msg.getString("type")) && !configured.getAndSet(true)) {
                            ws.writeTextMessage(new JsonObject()
                                .put("type", "configure").put("interval", 2).encode());
                        }

                        if ("system_stats".equals(msg.getString("type")) && configured.get()) {
                            JsonObject dbPool = msg.getJsonObject("data").getJsonObject("dbPool");
                            assertNotNull(dbPool, "dbPool must be present");
                            assertTrue(dbPool.getInteger("active") >= 0, "dbPool.active must be >= 0");
                            assertTrue(dbPool.getInteger("idle") >= 0,   "dbPool.idle must be >= 0");
                            assertTrue(dbPool.getInteger("pending") >= 0, "dbPool.pending must be >= 0");
                            assertTrue(dbPool.getInteger("active") <= dbPool.getInteger("max"),
                                "dbPool.active must not exceed dbPool.max");

                            if (frameCount.incrementAndGet() >= 3) {
                                ws.close();
                                testContext.completeNow();
                            }
                        }
                    });
                });

                vertx.setTimer(25000, id -> {
                    if (!testContext.completed()) {
                        ws.close();
                        testContext.failNow(new AssertionError(
                            "Did not collect 3 system_stats frames within 25s"));
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test @Order(3) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMonitoringSessionsCountReflectsOpenConnections(Vertx vertx, VertxTestContext testContext) {
        // Open 3 WS connections; the observer (4th) must see monitoringSessions >= 3
        int extraConnections = 3;
        java.util.List<WebSocket> extras = new java.util.ArrayList<>();
        AtomicInteger connectedCount = new AtomicInteger(0);

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
            .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        for (int i = 0; i < extraConnections; i++) {
            wsClient.connect(opts)
                .onSuccess(ws -> {
                    extras.add(ws);
                    connectedCount.incrementAndGet();
                })
                .onFailure(testContext::failNow);
        }

        vertx.setTimer(1000, tid -> wsClient.connect(opts)
            .onSuccess(observerWs -> {
                observerWs.exceptionHandler(testContext::failNow);
                observerWs.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        if ("system_stats".equals(msg.getString("type"))) {
                            int sessions = msg.getJsonObject("data").getInteger("monitoringSessions", 0);
                            assertTrue(sessions >= extraConnections,
                                "monitoringSessions must be >= " + extraConnections + " while " +
                                extraConnections + " extra WS connections are open; got " + sessions);
                            extras.forEach(WebSocket::close);
                            observerWs.close();
                            testContext.completeNow();
                        }
                    });
                });

                vertx.setTimer(20000, id -> {
                    if (!testContext.completed()) {
                        extras.forEach(WebSocket::close);
                        observerWs.close();
                        testContext.failNow(new AssertionError(
                            "system_stats frame not received within 20s on observer WS"));
                    }
                });
            })
            .onFailure(testContext::failNow));
    }
}
```

These tests fail today: `system_stats` emits `activeConnections` (not the three split fields); `dbPool` key is absent; `application_name` is not set in `PgConnectOptions`. They pass after Step 1 (`collectDbPoolMetrics`), Step 2 (`updateConnectionPoolMetrics`), Step 3 (restructured payload), and the `application_name` property addition in `PgConnectionManager` are all complete.

#### Frontend failing tests (write first — Playwright)

New `src/tests/e2e/specs/overview-connection-metrics.spec.ts`:

```
test('Monitoring Sessions card shows 1 when one browser session is connected')
  1. Navigate to /
  2. Wait for system_stats frame
  3. Assert the "Monitoring Sessions" stats card value >= 1

test('DB Connections chart renders active/idle/pending series')
  1. Navigate to /
  2. Assert the chart container has three distinct coloured series (use data-testid on each <Area>)
  3. Assert a reference line for pool max is visible

test('DB pool active value is non-negative and does not exceed max')
  1. Intercept system_stats WS frames via page.evaluate
  2. Collect 5 frames
  3. Assert dbPool.active >= 0 and dbPool.active <= dbPool.max for all frames

test('activeSubscriptions matches consumer group count')
  1. Create N consumer groups via REST API
  2. Wait for next system_stats frame
  3. Assert stats.activeSubscriptions >= N
```

**Acceptance**: `activeConnections` field removed from payload; three separate metrics surfaced in stats cards and chart; DB pool chart never shows negative values; pool saturation line visible; all tests pass.

---

### Running the suite

```powershell
npx playwright test --workers=1
```

`workers: 1` is mandatory — the suite shares `SETUP_ID = 'default'` across spec files (§4).

---

### 7.12 Live non-destructive message stream — Phase 12

**Objective.** Provide a *push* stream of new queue messages to the admin UI that **observes
without consuming** — the live, push-based counterpart to `browse`.

**Status (2026-06-18) — live push IMPLEMENTED end-to-end.** The old destructive consuming SSE
endpoint `GET /api/v1/queues/{s}/{q}/stream` was **removed** (commit `7a5b0a66`; it ran
`createConsumer().subscribe()` and acked/removed messages, stealing them from real consumers) and its
fallout cleaned up (Phase 12.0 ✅). The correct **non-destructive** replacement is now built: native
observer (12.2 ✅), outbox observer (12.3 ✅), REST SSE endpoint `GET …/messages/stream` (12.4 ✅), and
UI Live mode on that stream (12.5 ✅). Admin live views push again — without consuming. Remaining:
optional WS parity (12.6) and the latent destructive-client cleanup (Phase 13).

**Principle.** *Observe ≠ consume.* Every admin API is non-destructive by default (memory
*Admin UI Non-Destructive Reads*). The live stream must never `subscribe`/ack/`FOR UPDATE`/
change status/delete. A destructive stream is "a `SELECT` that also deletes the rows it read".

**Reuse — do not rebuild:**
- Non-destructive read: `QueueBrowser.browse(...)` — plain `SELECT`, no status change — in
  `PgNativeQueueBrowser` and `OutboxQueueBrowser`, via `QueueFactory.createBrowser(...)`.
- Push signal (native): `PgNativeQueueProducer` emits `pg_notify(channelFor(schema,topic), newId)`
  on insert (`PgNativeQueueProducer.java:192`).
- **Established observer pattern: `ReactiveNotificationHandler` (peegeeq-bitemporal).** It is the
  canonical non-destructive LISTEN/NOTIFY observer: dedicated connection, `start()/stop()`
  lifecycle, `closeHandler`-driven reconnect with bounded backoff, `vertx.runOnContext`
  dispatch, read-full-row-by-id, and WARN→ERROR handler-failure escalation. **This is the
  pattern Phase 12.2 follows.**

**Design decision — where the observe logic lives.** The live observe is a *dedicated
non-destructive observer* that **mirrors `ReactiveNotificationHandler`**, not hand-rolled LISTEN
code inside the browser. The browser stays a thin point-in-time reader (`browse`); the observer
owns the LISTEN connection, reconnection, and lifecycle. `QueueBrowser.tail()` is the public
entry point and **delegates** to the observer. *(Rejected: embedding connection lifecycle in
`PgNativeQueueBrowser` — it mixes concerns and creates a fourth divergent copy of the
LISTEN/reconnect logic. The first native `tail()` attempt did this and was discarded.)*

---

#### Phase 12.0 — Stabilise after the destructive-endpoint removal  ✅ DONE (2026-06-18)
*No new feature; make the tree green and free of dead references first.*

> **Done 2026-06-18:** Deleted 3 wholesale `/stream` test classes (`SSEBasicStreamingIntegrationTest`,
> `SSEBatchingIntegrationTest`, `SSEReconnectionIntegrationTest`); trimmed the `/stream` methods from
> 4 mixed classes (`RealTimeStreamingIntegrationTest` ×1, `ServerSentEventsHandlerTest` ×4,
> `CrossLayerPropagationIntegrationTest` ×2, `ConsumerGroupSubscriptionIntegrationTest` ×6 + the
> now-unused `extractEventData` helper + unused imports); deleted orphaned `SSEConnection.java`.
> Frontend: deleted `message-sse-stream.spec.ts` (+ unregistered `14c` in `playwright.config.ts`),
> removed test '15 Live SSE' from `message-browser.spec.ts`. Doc: corrected the route table, file
> tree, and constructor example in `PEEGEEQ_REST_MODULE_GUIDE.md`.
> **Carved out:** (1) `endpoints.ts` `QUEUE.STREAM` URL is left until **Phase 13.1** because
> `PeeGeeQClient.streamMessages` still references it — removing it alone breaks the TS build; the two
> go together. (2) The REST guide's deeper *implementation walkthrough* (≈ lines 340–460, 745–760)
> still narrates the removed SSE streaming and needs a dedicated doc pass (non-blocking).
- **Delete** the integration tests that exercised the removed `/stream` route (they now 404):
  `ServerSentEventsHandlerTest`, `SSEBasicStreamingIntegrationTest`,
  `SSEBatchingIntegrationTest`, `SSEReconnectionIntegrationTest`,
  `RealTimeStreamingIntegrationTest`, and the SSE-`/stream` cases in
  `ConsumerGroupSubscriptionIntegrationTest` and `CrossLayerPropagationIntegrationTest`.
- **Delete** orphaned top-level `SSEConnection.java` (only the removed handler used it;
  `SystemMonitoringHandler`/`EventStoreHandler` have their own connection types).
- **Frontend:** remove specs asserting the consuming stream (`message-sse-stream.spec.ts`, the
  `/stream` wait in `message-browser.spec.ts`) and the dead `QUEUE.STREAM` URL in `endpoints.ts`.
- **Docs:** correct `PEEGEEQ_REST_MODULE_GUIDE.md` (no `handleQueueStream`, `/stream`, old
  4-arg constructor, `SSEConnection`).
- **Exit:** `peegeeq-rest` integration + Playwright suites green; zero references to the removed
  route/handler/class; live view documented as browse-poll interim.

#### Phase 12.1 — API contract (`peegeeq-api`)
- `QueueBrowser.tail(MessageHandler<T>): Future<Void>` — *already added.* Refine Javadoc:
  non-destructive; resolves when the subscription is established; `close()` tears it down;
  default impl throws `UnsupportedOperationException`.
- **Exit:** compiles; contract documented.

#### Phase 12.2 — Native non-destructive observer (`peegeeq-native`) — core  ✅ DONE (2026-06-18)
> **Full design:** `docs-design/dev/non-destructive-queue-observer-design-18-Jun-2026.md`.
> **Done 2026-06-18:** Implemented as `PgNativeQueueObserver` (package-private `final class`) per the
> design doc. NOTE the design **superseded** the read-by-id sketch in the bullets below with a
> **watermark drain** (`SELECT … WHERE id > highWaterId ORDER BY id ASC`, FROM_NOW seed via
> `MAX(id)`) — immune to out-of-order commits and reconnect gaps, which read-by-id is not.
> `PgNativeQueueBrowser.tail()` delegates to it; shared row-mapper extracted to `PgNativeMessages`.
> Tests (`PgNativeQueueBrowserTailIntegrationTest`): observe-not-consume, reconnect/catch-up
> (`pg_terminate_backend`), and 3 fail-fast guards — green; no banned patterns.
> **Gap closed (2026-06-18):** test #1 now adds the `createConsumer().subscribe()` real-consumer leg —
> after the tail observes and `browse()` still sees the message, a real consumer still receives it.
> Green for native and outbox.
- New observer class mirroring `ReactiveNotificationHandler`: `connectDedicated()` LISTEN on
  `channelFor(schema,topic)`; `notificationHandler` → `runOnContext` → read new id via
  non-destructive `SELECT … WHERE topic=$1 AND id=$2` → invoke handler; `closeHandler` reconnect
  with bounded backoff; `start()/stop()`; WARN→ERROR escalation.
- `PgNativeQueueBrowser.tail()` delegates to it; `browser.close()` stops it. Remove the
  hand-rolled tail currently in `PgNativeQueueBrowser`.
- **Tests** (JUnit `@Tag(INTEGRATION)`, TestContainers, no mocking):
  1. **Observe-not-consume** — tail, publish, assert pushed; then `browse()` asserts the message
     is *still present* **and** a real `createConsumer().subscribe()` still receives it.
  2. **Reconnect** — terminate the LISTEN backend (`pg_terminate_backend`), assert resubscribe
     and a subsequently-published message is observed.
  3. **Fail-fast guards** — closed browser / null handler / double-tail each surface as a failed
     `Future` (no silent 30 s hang).
- **Exit:** all native tail tests green; no banned patterns; conforms to the reference observer.

#### Phase 12.3 — Outbox tail (`peegeeq-outbox`)  ✅ DONE (2026-06-18)
> **Done 2026-06-18:** Confirmed the outbox emits **no** insert NOTIFY (poll-based queue), so
> implemented `tail()` as a non-destructive **browse-poll**: new `OutboxQueueObserver` (seed
> `MAX(id)` FROM_NOW → `vertx.setPeriodic` → `SELECT … WHERE id > highWaterId` → push), shared
> row-mapper `OutboxMessages`, `OutboxQueueBrowser` made Vert.x-aware (existing constructors kept),
> `OutboxFactory.createBrowser` passes `databaseService.getVertx()`. Test
> `OutboxQueueBrowserTailIntegrationTest` green (observe-not-consume); 13 existing browser tests
> still green (no regression).
- **Confirm first:** does the outbox emit an insert `NOTIFY`? If yes, mirror Phase 12.2; if not,
  implement `tail()` as an internal non-destructive browse-poll (`id > lastSeenId`) so the API
  contract holds without consuming.
- **Tests:** same observe-not-consume proof as 12.2.
- **Exit:** outbox tail green; behaviour (LISTEN vs poll) documented.

#### Phase 12.4 — REST non-destructive stream endpoint (`peegeeq-rest`)  ✅ DONE (2026-06-18)
> **Done 2026-06-18:** Added `GET /api/v1/queues/{setupId}/{queueName}/messages/stream` →
> `ServerSentEventsHandler.handleQueueMessageStream` (resolves setup→factory→`createBrowser().tail()`,
> pushes each new message over SSE, emits a `subscribed` readiness event, `browser.close()` on
> disconnect — no consumer, no subscribe). `SseMessageStreamDemoIntegrationTest` proves 10 messages
> produced over REST stream back over SSE and remain browsable (non-destructive). NOTE: requires
> `peegeeq-native`/`peegeeq-outbox` installed to the local repo first (the rest module resolves them
> as JARs).

- Add a **new, clearly-named** route (the `/stream` name is retired): e.g.
  `GET /api/v1/queues/{setupId}/{queueName}/messages/stream` → SSE, backed by
  `createBrowser().tail()`. Keep the existing message-filter logic (`messageType`/header
  filters); push `event: message` with `id:` for SSE resume; heartbeat; tear down the browser
  (`browser.close()`) on client disconnect.
- (`ServerSentEventsHandler` is currently updates-only; either extend it or add a small
  dedicated handler — do **not** reintroduce a consumer.)
- **Tests** (JUnit integration): open the SSE endpoint, publish, assert the event is pushed;
  then `GET …/messages` (browse) asserts the message is still present (observe-not-consume at
  the HTTP layer); disconnect closes the browser.
- **Exit:** endpoint green; no `createConsumer`/`subscribe` anywhere in the path.

#### Phase 12.5 — Frontend: restore SSE push (`peegeeq-management-ui`)  ✅ DONE (2026-06-18)
> **Done 2026-06-18:** `MessageBrowser` Live mode now opens an `EventSource` on the non-destructive
> `/api/v1/queues/{s}/{q}/messages/stream` (replacing the Phase-5 3s browse-poll); each push wakes a
> debounced browse refresh (full detail, no polling), terminal disconnect surfaces `message.error`,
> stream closed on toggle-off/unmount. `npm run build` green. E2E `message-browser-nondestructive-live`
> spec expanded to 7 tests (added the 10-message burst, made self-contained) — full project run
> 37/37 passed, confirming Live opens the non-destructive stream, the burst surfaces live, and all
> messages stay browsable; the consuming `/stream` is never opened.

- Point `MessageBrowser` (and optionally Queue Details live) at the new `…/messages/stream`
  `EventSource`; surface errors (`message.error`), handle native auto-reconnect, close on unmount.
- **Tests** (Playwright E2E, `workers:1`): live push appears on send; **and** after viewing,
  the message is still listed by browse (still-browsable assertion); register the spec in
  `playwright.config.ts`.
- **Exit:** live push works non-destructively end-to-end; E2E green.

#### Phase 12.6 — WebSocket parity (optional)
- Wire `WebSocketHandler.handleQueueStream` (currently a non-consuming stub) to the same
  `tail()` so WS clients get the same non-destructive push. Defer unless a WS consumer is needed.

**Sequencing:** 12.0 → 12.1 → 12.2 → 12.3 → 12.4 → 12.5 → (12.6). One phase at a time; the tree
stays green and the user runs the tests at each gate.

---

### 7.13 Destructive-read safeguards — Phase 13

*Runs after Phase 12.5 (the non-destructive stream must exist before the latent consuming
clients are deleted and the guard is locked in).*

**Source.** 2026-06-17 audit of every queue message-read path in `peegeeq-rest` and the UI. As an
admin/observability tool, a destructive read must be rare, explicit, and impossible to introduce
by accident.

*Audit summary —*
- ✅ Safe (browse): `GET management/messages` (`ManagementApiHandler:1022`),
  `GET /queues/{s}/{q}/messages` (`:2125`) — both `createBrowser().browse()`.
- ✅ Destructive view: **removed** 2026-06-17 (the consuming `/stream`).
- ⚠️ **Latent destructive clients, no guard** (defined, currently unused — one keystroke from a
  view): `PeeGeeQClient.streamMessages()` (`PeeGeeQClient.ts:588`),
  `createMessageStreamService()` (`websocketService.ts:143`) + `useMessageStream()`
  (`useRealTimeUpdates.ts:187`).
- ✅ Intentional destructive **actions** (keep, already gated): queue Purge/Delete (confirm
  dialogs), consumer-group CRUD, webhook subscription. Event-store `subscribe`/`streamEvents` is
  non-destructive (events are immutable).

#### Phase 13.1 — Remove latent destructive clients
- Delete `streamMessages` (`PeeGeeQClient`), `createMessageStreamService` + `useMessageStream`
  (dead code), so a consuming read cannot be wired into an admin view by accident.
- **Exit:** no admin-reachable consuming client remains; build green.

#### Phase 13.2 — Naming contract
- Non-destructive endpoints are `…/messages` (browse) or `…/messages/stream` (observe, §7.12);
  any genuinely-consuming endpoint is named `…/consume` and is **never** called by the admin UI.
- **Exit:** routes/clients renamed to match; documented.

#### Phase 13.3 — Guard test
- Static check (mirroring `OnSuccessExceptionSwallowingGuardTest`, `@Tag(CORE)`, no DB) that
  admin-facing read/view/stream handlers and the UI never use `createConsumer`/`.subscribe(` for
  a queue; destructive consumption only via explicitly-named, confirmed paths.
- **Exit:** guard runs in the default profile and fails the build on any regression.

---

## 8. Overview Page Chart Defects (2026-06-15)

Three defects identified in the real-time charts on the System Overview page (`/`).
**§8.1 and §8.2 are fixed (2026-06-16); §8.3 (split `activeConnections`) remains open as Phase 11.**

---

### 8.1 Active Connections graph shows negative values  ✅ FIXED (2026-06-16)

> **Status — Fixed 2026-06-16.** The decrement (and per-IP bookkeeping) is now inside the
> `if (connection != null)` guard in **both** `cleanupWebSocketConnection` and
> `cleanupSSEConnection` — `remove()` is the idempotency gate, so a connection cleaned up
> twice (e.g. TCP RST firing both `exceptionHandler` and `closeHandler`) is decremented
> exactly once. Verified by `testActiveConnectionCountNeverNegativeAcrossLifecycle` (WS,
> `@Order(11)`) and the new `testSseAbruptDisconnectKeepsConnectionCountNonNegative` (SSE) —
> both green; the full 18-test `SystemMonitoringHandlerTest` passes with zero ERROR log noise.
> As part of the same change, client disconnects (FIN/RST) and malformed input now log at
> DEBUG via an `isClientDisconnect(...)` helper instead of ERROR-with-stack-trace.

**Symptom**: The "Active Connections" area chart on the Overview page occasionally shows negative numbers on the Y-axis.

**Root cause**: `SystemMonitoringHandler.java` — `cleanupWebSocketConnection` (line 730) and `cleanupSSEConnection` (line 750) both call `totalConnections.decrementAndGet()` **unconditionally**, outside the null-check on the removed connection:

```java
// cleanupWebSocketConnection (line 730)
WebSocketConnection connection = wsConnections.remove(connectionId);
if (connection != null) {
    // cancel timers ...
}
// ← decrement is here, NOT inside the if-block
totalConnections.decrementAndGet();   // line 739
```

If the same connection triggers cleanup twice (e.g., both an idle-timeout timer and the socket close event fire), `totalConnections` is decremented twice for a single increment — driving it below zero. The emitted `system_stats.activeConnections` field therefore sends a negative integer to the frontend, which the chart renders faithfully.

**Frontend path**: `system_stats.activeConnections` → `managementStore.ts:215` (`connectionData` point `{ connections }`) → `Overview.tsx:467` `<Area dataKey="connections" />`.

**Fix** (`peegeeq-rest`): guard both decrements inside the `if (connection != null)` block so they only execute when this call actually performed the removal:

```java
// cleanupWebSocketConnection
WebSocketConnection connection = wsConnections.remove(connectionId);
if (connection != null) {
    if (connection.timerId > 0) vertx.cancelTimer(connection.timerId);
    if (connection.idleCheckerId > 0) vertx.cancelTimer(connection.idleCheckerId);
    totalConnections.decrementAndGet();   // ← moved inside
    AtomicInteger ipCount = connectionsByIp.get(clientIp);
    if (ipCount != null) {
        ipCount.decrementAndGet();
        if (ipCount.get() <= 0) connectionsByIp.remove(clientIp);
    }
}
```

Apply the same guard in `cleanupSSEConnection` (line 750).

**Files affected** (current locations after the fix — the line numbers in the root-cause prose above are pre-fix):
- `peegeeq-rest/…/handlers/SystemMonitoringHandler.java` — `cleanupWebSocketConnection` ~759–776 (guarded decrement ~768) and `cleanupSSEConnection` ~779–800 (guarded decrement ~791); disconnect classification helper `isClientDisconnect(...)` ~887.

**Backend regression test (write first — JUnit)**

This test exists in `SystemMonitoringHandlerTest.java` as `testActiveConnectionCountNeverNegativeAcrossLifecycle` (`@Order(11)`, `@Tag("regression")`). It was failing before the fix and is **now green** (2026-06-16); a companion SSE test `testSseAbruptDisconnectKeepsConnectionCountNonNegative` was added to cover the `cleanupSSEConnection` path.

To reproduce the original failure on pre-fix code:
```
mvn test -pl peegeeq-rest -Pintegration-tests -Dtest=SystemMonitoringHandlerTest#testActiveConnectionCountNeverNegativeAcrossLifecycle
```

The test opens N=5 raw TCP sockets that complete the WebSocket upgrade then send a TCP RST (via `sock.setSoLinger(true, 0)`). This triggers both `exceptionHandler` and `closeHandler` on the server for each socket — the double-decrement point. After all 5 abrupt closes settle (2s timer), an observer WS is opened and the next `system_stats` frame is asserted to have `activeConnections >= 1`. With the current bug the frame shows `-4`; after the guard fix it shows `1`.

Apply the guard fix described above; then re-run the test to confirm it passes.

**Frontend failing test (write first — Playwright)**

Add to `src/tests/e2e/specs/overview-live-stats-update.spec.ts` (or a new `overview-chart-correctness.spec.ts`):
```
test('Active Connections value is never negative')
  1. Navigate to /; wait for at least 3 system_stats WS frames (via page.evaluate intercepting messages)
  2. Assert that the connectionData points stored in the chart are all >= 0
  3. Optionally force a reconnect cycle and re-assert
```

**Acceptance**: JUnit regression test green; `totalConnections` never goes below 0; Active Connections chart Y-axis min is 0; all Playwright tests pass.

---

### 8.2 Message Throughput graph does not reflect real-time rate  ✅ FIXED (2026-06-16)

> **Status — Fixed 2026-06-16, with an implementation that supersedes the proposal below.**
> `messagesPerSecond` is now a **per-connection delta rate** computed by a shared
> `withPerConnectionRate(metrics, now, prevTotal, prevTs)` helper, applied on **both** the
> WebSocket and SSE send paths (each connection tracks its own `prevTotalMessages` /
> `prevMessagesTimestampMs`). This was chosen over the handler-level
> `lastTotalMessages`/`lastMeasurementTime` fields proposed below because the shared metrics
> cache TTL (5 s) can exceed the stream interval, so a single handler-level delta produced
> stale/zero or cross-test-polluted values; a per-connection sample seeded on connect does
> not. Applying the same helper to WS **and** SSE also **reconciles the two transports** —
> previously WS reported a delta while SSE forwarded the cached lifetime value, so the UI's
> merged number flipped between two different computations. Verified by
> `testMessagesPerSecondIsZeroWhenPendingCountUnchangedBetweenTicks` (`@Order(12)`) — green.

**Symptom**: The "Message Throughput" chart title implies a live throughput view, and the "Messages/sec" stats card label implies a per-second rate. In practice the chart shows a flat or very slowly drifting line that does not respond to bursts of messages.

**Root cause**: The backend computes `messagesPerSecond` as a **lifetime average**, not an interval rate:

```java
// SystemMonitoringHandler.java:488–489
double messagesPerSecond = totalMessages > 0 && uptime > 0
        ? totalMessages / (uptime / 1000.0) : 0.0;
```

`totalMessages` is the cumulative count of all messages ever processed since the JVM started; `uptime` is the JVM uptime in milliseconds. The result is "average messages per second across the entire lifetime of the process". This value:
- barely changes between polling ticks even under heavy load
- never reflects a burst of messages published right now
- makes the chart appear flat long after startup

Both the chart (`Overview.tsx:436` `<Area dataKey="messages" />`) and the stats card (`Overview.tsx:404` `value={Math.round(stats.messagesPerSecond)} suffix="msg/s"`) read the same `stats.messagesPerSecond` field, so the two surfaces are internally consistent — the mismatch is between what users expect ("current throughput") and what the backend actually computes ("historical average").

**Frontend path**: `system_stats.messagesPerSecond` → `managementStore.ts:211` (`throughputData` point `{ messages: stats.messagesPerSecond }`) → `Overview.tsx:436` `<Area dataKey="messages" />`.

**Fix** (`peegeeq-rest`): track the previous `totalMessages` snapshot and the previous timestamp between polling ticks, and compute a **delta rate** over the polling interval:

```java
// Add fields to SystemMonitoringHandler:
private long lastTotalMessages = 0;
private long lastMeasurementTime = System.currentTimeMillis();

// In the .map() block replacing lines 488–489:
long now_ms = System.currentTimeMillis();
long intervalMs = now_ms - lastMeasurementTime;
double messagesPerSecond = intervalMs > 0
        ? (totalMessages - lastTotalMessages) / (intervalMs / 1000.0)
        : 0.0;
lastTotalMessages = totalMessages;
lastMeasurementTime = now_ms;
```

This gives a true per-interval rate that reacts to real-time message traffic. The chart will now rise and fall with actual throughput. The stats card label "Messages/sec" will then be accurate.

**Note on thread safety**: `SystemMonitoringHandler` is a Vert.x verticle; the `sendMetricsToWebSocket` / `sendMetricsToSSE` paths execute on the event loop, so `lastTotalMessages` and `lastMeasurementTime` are accessed from a single thread and do not need synchronization. Confirm this holds if `collectSetupMetrics` dispatches to worker threads.

**Files affected** (current locations after the fix — the proposed handler-level field approach below was superseded; see the §8.2 status note):
- `peegeeq-rest/…/handlers/SystemMonitoringHandler.java` — `withPerConnectionRate(...)` helper ~821–830, applied in `sendMetricsToWebSocket` ~806 and `sendMetricsToSse` ~856; per-connection `prevTotalMessages`/`prevMessagesTimestampMs` fields on each connection. The old lifetime-average calculation (formerly ~488–489) no longer exists.
- Frontend: no changes needed once the backend emits a correct value

**Backend regression test (write first — JUnit)**

This test exists in `SystemMonitoringHandlerTest.java` as `testMessagesPerSecondIsZeroWhenPendingCountUnchangedBetweenTicks` (`@Order(12)`, `@Tag("regression")`). It was failing before the fix and is **now green** (2026-06-16).

To reproduce the original failure on pre-fix code:
```
mvn test -pl peegeeq-rest -Pintegration-tests -Dtest=SystemMonitoringHandlerTest#testMessagesPerSecondIsZeroWhenPendingCountUnchangedBetweenTicks
```

The test pre-seeds 5 messages into the test queue (no consumer, so they stay pending), configures a 2-second WS interval, then collects two consecutive `system_stats` ticks. Between tick-1 and tick-2 no new messages are published. The delta formula gives `(5 - 5) / 2 = 0.0`; the lifetime-average formula gives `5 / uptimeSeconds > 0`. The assertion is `assertEquals(0.0, rate, 0.01)` on tick-2 — it fails with the current code and passes after the fix.

Apply the delta-rate fix described above (add `lastTotalMessages`/`lastMeasurementTime` fields and update lines 488–489); then re-run the test to confirm it passes.

**Frontend failing test (write first — Playwright)**

Add to `src/tests/e2e/specs/overview-live-stats-update.spec.ts`:
```
test('Message Throughput chart value increases after publishing messages')
  1. Navigate to /; record the current messagesPerSecond value from the chart
  2. Publish 20 messages to the test queue via REST API in rapid succession
  3. Wait for the next system_stats frame (up to 10 s)
  4. Assert the messagesPerSecond value in the new frame is greater than the value from step 1
```

**Acceptance**: JUnit regression test green; the chart reacts to published messages within one polling interval; values return toward 0 when idle; all Playwright tests pass.

---

### 8.3 `activeConnections` is an arbitrary composite of three unrelated concepts  ❗

**Symptom**: The "Active Connections" stats card and area chart on the Overview page are meaningless in practice. The value is a sum of things that have nothing to do with each other.

**Root cause analysis**

```java
// SystemMonitoringHandler.java ~519–520 (still present; emitted in system_stats ~533)
int activeConnectionsTotal = totalConnections.get()
        + agg.getInteger("activeConsumerConnections", 0);
```

The two operands are:

| Operand | What it actually counts |
|---|---|
| `totalConnections` | WS + SSE browser sessions currently watching the Overview page (`/ws/monitoring`, `/sse/metrics`) — incremented in `handleWebSocketMonitoring:224` and `handleSSEMetrics:328`, decremented on close |
| `activeConsumerConnections` | `subs.size()` summed across every topic across every setup (`collectTopicSubscriptionMetrics:612–613`) — this is the count of **registered subscriptions**, and is identical to `totalConsumerGroups` |

Neither operand measures actual TCP connections from consumer processes, and the sum of the two is not a useful number for any purpose.

A third category — **PostgreSQL pool connections per setup** — is entirely absent from the metric despite being the most operationally valuable:

- Each setup has its own isolated Vert.x reactive `Pool` (`PeeGeeQManager.java:98`), owned by a per-setup `PeeGeeQManager` instance managed by `PeeGeeQDatabaseSetupService.java:54`.
- Pool defaults: `max-size=32`, `min-size=8`, `max-wait-queue-size=128` (`peegeeq-default.properties`).
- `PeeGeeQMetrics.java` already declares `peegeeq.connection.pool.active`, `.idle`, and `.pending` Micrometer gauges (lines 154–167) and exposes `updateConnectionPoolMetrics(active, idle, pending)` (lines 357–367).
- **`updateConnectionPoolMetrics()` is never called anywhere in the codebase.** The gauges always return 0.
- Vert.x 5.x `Pool` does not expose synchronous state queries, so pool stats must be obtained by querying `pg_stat_activity` from within each setup's pool.

**The three correct dimensions**

| Dimension | Meaning | Source |
|---|---|---|
| **Monitoring sessions** | Browser tabs currently connected to the Overview live feed | `totalConnections` in `SystemMonitoringHandler` — already correct, just needs to be emitted separately |
| **Active subscriptions** | Registered consumer group subscriptions per setup/queue | `subs.size()` already computed — needs renaming from `activeConsumerConnections` to `activeSubscriptions` and should not be conflated with connections |
| **DB pool connections** (per setup) | Live PostgreSQL connections: active / idle / pending / max | Must be obtained by querying `pg_stat_activity` per setup; `PeeGeeQMetrics.updateConnectionPoolMetrics()` provides the storage but is currently never called |

See §7.11 for the TDD implementation plan.

---

## 9. Test Coverage Audit (2026-06-15)

Complete inventory of every test layer in `peegeeq-management-ui`.

---

### 9.0 Architectural principle — why there are no server-side unit tests

The E2E suite uses **TestContainers** (via `global-setup-testcontainers.ts`) to start a real PostgreSQL container before every run. Every Playwright test therefore exercises the full stack with real infrastructure:

```
Playwright browser → React UI → axios → Vert.x REST API → peegeeq-db → real PostgreSQL
```

This makes a mocked-server unit test layer redundant and actively harmful:

- A mock that returns `{ queues: [...] }` only proves your code processes the response you invented. It cannot catch a backend shape change, a missing migration, a wrong SQL query, or a handler routing error.
- If the backend changes, mocked tests stay green while the real integration silently breaks — false confidence at a maintenance cost.

The division of responsibility is therefore:

| Layer | Tested by | Real dependencies used |
|---|---|---|
| Pure client-side state and logic | Vitest unit tests | None — Zustand state, Zod schemas, hook state |
| Client → server contract + all server logic | Playwright E2E | Real Vert.x + TestContainers PostgreSQL |

Async store actions (`fetchSystemData`, `fetchQueues`, `fetchConsumerGroups`) that call the backend are **not** unit-tested — they are covered by the E2E suite, which is the correct and only necessary test layer for anything that crosses the network boundary.

---

### 9.1 Test infrastructure summary

| Layer | Tool | Version | Count |
|---|---|---|---|
| Playwright E2E | Playwright | 1.60.0 | 49 spec files / ~329 tests |
| Documentation screenshots | Playwright (manual spec) | 1.60.0 | 72 serial tests / 70 PNGs (69 on disk pre-Phase-7a; `13-notifications.png` on next regen) |
| Vitest unit tests | Vitest | 3.2.4 | 2 files / 31 tests |
| Storybook stories | — | not configured | 0 |
| Visual regression snapshots | — | not configured | 0 |
| Integration tests | Vitest (`src/tests/integration/`) | — | directory exists, 0 files |

---

### 9.2 Playwright E2E specs (49 files, ~329 tests)

Workers: 1 (sequential — shared `SETUP_ID = 'default'` — see §4). TestContainers PostgreSQL via `global-setup-testcontainers.ts`. Playwright projects define explicit ordering with named dependencies so setup data exists before dependent specs run.

**Config** (`playwright.config.ts`):
- Timeout: 60 s per test, 10 s assertion
- Retry: 0 local, 2 CI
- Screenshots: on (every test)
- Video: on-first-retry
- Reporters: HTML (`playwright-report/`), JSON (`test-results/results.json`), JUnit (`test-results/junit.xml`)
- baseURL: `http://localhost:3000`
- Web server: `npm run dev -- --mode test` (Vite, auto-started, reused if running)

#### Specs by area

**Settings & configuration (8 specs, ~57 tests)**
- `settings.spec.ts` — REST connection validation, form submit, defaults, URL format
- `settings-health-checks.spec.ts` — ping button states, auto-ping toggle
- `settings-ping-utilities.spec.ts` — individual REST / WS / SSE ping buttons, timeout
- `settings-auto-ping.spec.ts` — interval input, background ping, toggle persistence
- `connection-status.spec.ts` — WS/SSE state, reconnection, status badge
- `system-integration.spec.ts` — header layout, sidebar nav, page routing, load states

**Overview page (6 specs, ~32 tests)**
- `overview-system-status.spec.ts` — stats cards, manual refresh
- `overview-setup-selector.spec.ts` — setup scope selector interaction
- `overview-setup-details-modal.spec.ts` — details panel content and layout
- `overview-recent-activity.spec.ts` — activity table, status tags, queue overview table
- `overview-live-stats-update.spec.ts` — SSE metrics delivery, chart data updates
- `overview-reconnecting-banner.spec.ts` — reconnecting status tag on WS/SSE drop

**Database setups (2 specs, ~18 tests)**
- `database-setup.spec.ts` — CRUD, form validation, API integration
- `database-setup-form-defaults.spec.ts` — port range (1–65535), field defaults (localhost:5432, schema, user)

**Queue management (8 specs, ~68 tests)**
- `queue-management.spec.ts` — CRUD operations
- `queue-messaging-workflow.spec.ts` — publish, receive, workflow validation
- `queue-details-overview.spec.ts` — detail page field mapping against backend response
- `queue-details-operations.spec.ts` — Pause/Resume, Get Messages, Purge, Delete
- `queue-details-consumers.spec.ts` — Consumers tab real subscription data (Phase 7.2 covered here)
- `queue-config-create-and-display.spec.ts` — creation form, stats card display on Overview
- `queues-filter-sort.spec.ts` — search, type/status multi-select, column sort
- `queues-setup-selector.spec.ts` — setup scope selector

**Event store management (8 specs, ~81 tests)**
- `event-store-management.spec.ts` — CRUD including delete-removes-from-list (Phase 7.1)
- `event-store-workflow.spec.ts` — end-to-end event posting workflow
- `events-filter.spec.ts` — all filter controls, client-side filtering
- `event-detail-modal.spec.ts` — event info, bi-temporal fields, correlation, metadata
- `events-scope-selector.spec.ts`, `event-stores-setup-selector.spec.ts`, `event-stores-scope-filter.spec.ts` — setup/store selector variants
- `consumer-groups-scope-selectors.spec.ts` — setup + queue selectors, comprehensive validation
- `consumer-groups-validation.spec.ts` — duplicate name error, validation rules

**Event visualization (5 specs, ~38 tests)**
- `causation-tree.spec.ts` — full causation tree page, parent-child event flow
- `aggregate-stream.spec.ts` — aggregate list, keyset-paginated stream
- `visualization-scope-selector.spec.ts` — setup/store selectors on visualization pages
- `visualization-tab-smoke.spec.ts` — quick smoke for tab loading
- `event-visualization.spec.ts` — standalone causation tree + aggregate stream

**Message browser (7 specs, ~54 tests)**
- `message-browser.spec.ts` — retrieval, filtering, SSE Live mode
- `message-browser-advanced-filters.spec.ts` — drawer filters applied to table
- `message-browser-scope-selectors.spec.ts` — setup + queue selectors
- `message-sse-stream.spec.ts` — direct API, REST + EventSource end-to-end
- `message-browser-sse-failure.spec.ts` — EventSource abort, dropout, recovery (×2 entries in audit — same file)
- `queue-updates-sse.spec.ts` — `GET /api/v1/sse/queues/:setupId` direct API tests

**Infrastructure & utilities (5 specs, ~31 tests)**
- `websocket-sse-connection.spec.ts` — WS/SSE connection validation
- `system-metrics-sse.spec.ts` — `/api/v1/sse/metrics` versioned URL
- `api-error-paths.spec.ts` — backend error responses surface as UI toasts
- `setup-prerequisite.spec.ts` — creates default setup for dependent specs
- `scope-selector-persistence.spec.ts` — setup/queue selection survives reload

**Documentation screenshots (1 spec, 72 serial tests — manual run only)**
- `take-screenshots.spec.ts` — see §9.3

---

### 9.3 Documentation screenshot spec (`take-screenshots.spec.ts`)

This is a standalone serial spec run manually (`npx playwright test take-screenshots.spec.ts --headed --reporter=list`). It is **not part of the standard `npm run test:e2e` suite** — it has no project dependency entry and is excluded from the default run.

**What it does**: creates a complete live data set (queue, event store, 5 correlated events with causation chain, consumer group, 5 queued messages), then navigates to every page and captures every meaningful functional state. Screenshots are written to `docs-design/peegeeq-management-ui/screenshots/` and are the source images embedded in the enhancement documents.

**State persistence**: between tests via `screenshots-state.json` — allows individual tests to be re-run without recreating all data.

**Coverage**: 72 tests capturing 70 PNG files (the new `13-notifications.png` is written on the next manual regeneration; 69 on disk before Phase 7a), including:

| Range | Pages / states covered |
|---|---|
| 01–03 | Overview (empty, setup selected, setup details panel, SSE cards with data), header, WS/SSE status banner |
| 04–04n | Queues (list, create modal, delete confirm, type filter active), Queue Details (all 4 tabs, actions menu, get-messages modal, pause/purge confirm dialogs, error toast) |
| 05–06c | Database Setups (list, create modal, create error toast, delete error toast) |
| 07–07o | Event Stores (list, details modal), Events page (post form, advanced open, events loaded, 9 filter states, event detail modal, JSON validation error) |
| 08–08d | Settings (base, REST ping result, all pings done, auto-ping enabled) |
| 09–09d | Consumer Groups (list, setup+queue selected, create modal filled, validation errors) |
| 10–10q | Message Browser (empty, queue selected, filters drawer empty/filled/time-range, messages table, controls bar, live mode, message detail modal and payload card, status/search/combined/clear filter states) |
| 11–12b | Causation Tree (empty, store selected, tree traced), Aggregate Stream (with data) |
| 13 | Notifications page (`13-notifications.png`) — history table populated with two "queue created" entries marked New, Mark All Read / Clear All controls, header bell badge (Phase 7a) |

**Note**: `04g-queue-details-charts.png` captures the Charts tab stub banner ("Coming in Week 2"). This screenshot documents the current stub state and should be regenerated after Phase 7.5 (WS queue stream) is implemented, since the Charts tab would become meaningful at that point.

---

### 9.4 Vitest unit tests (2 files, 31 tests)

**Config** (`vitest.config.ts`): environment jsdom, globals false (avoids Playwright conflicts), timeout 10 s, slow threshold 5 s. Coverage via v8, reporters: text / json / html. E2E specs excluded from unit runs.

**Setup**: `src/tests/vitest.setup.ts` — initialises jsdom environment.

| File | Tests | What is covered |
|---|---|---|
| `src/services/configService.test.ts` | 13 | `getBackendConfig()` defaults + stored config + invalid JSON recovery; `saveBackendConfig()` localStorage persistence; `getApiUrl()` / `getVersionedApiUrl()` URL construction; `resetBackendConfig()` |
| `src/services/websocketService.test.ts` | 18 | Connection lifecycle (open, close, reconnect); message handling and event emission; error recovery and backoff; cleanup. Uses a `MockWebSocket` class with simulated async connection. |

**Scripts**:
```powershell
npm run test          # vitest watch
npm run test:run      # single run
npm run test:coverage # v8 coverage report
```

---

### 9.5 Coverage gaps

The following source areas have no unit test coverage and are tested only via Playwright E2E (which requires a live backend and TestContainers database to run):

| Area | Files | Gap |
|---|---|---|
| React components | `src/components/` (7 files) | No unit or component tests |
| Page components | `src/pages/` (18 files) | E2E only |
| Zustand store | `src/stores/managementStore.ts` | No unit tests — store actions, state transitions, notification capping untested in isolation |
| API client / RTK Query | `src/api/` or equivalent | No unit tests |
| React hooks | `src/hooks/` | No unit tests |
| Remaining services | `src/services/` — all except `configService` and `websocketService` | No unit tests (SSE service, metrics service, etc.) |
| Storybook | — | Not configured — no isolated component visual development or snapshot testing |
| Visual regression | — | No `toHaveScreenshot()` assertions — the 25 PNGs in `playwright-report/data/` are failure screenshots from the last run, not baseline comparisons |

**Integration test directory** (`src/tests/integration/`) is referenced by the `test:integration` npm script with `--passWithNoTests` but contains no files.

**Coverage of these gaps** — the store, validation, and connection-status hook gaps are addressed by three new unit test files added 2026-06-15:
- `src/stores/managementStore.test.ts` — 20 tests covering all pure-state actions: connection status flags, notification capping (50-entry), chart series capping (20-point), localStorage persistence. Async fetch actions (`fetchSystemData`, `fetchQueues`, `fetchConsumerGroups`) are excluded — server-side interactions are covered by E2E tests only, not mocked at the unit level.
- `src/types/queue.validation.test.ts` — 18 tests covering all Zod schemas, the `createdAt` number→ISO transform, `queueCount`→`total` mapping, and safe-default fallback behaviour
- `src/hooks/useRealTimeUpdates.test.ts` — 6 tests covering `useConnectionStatus` state logic

Visual regression (`toHaveScreenshot()`), Storybook, and the integration directory remain open gaps.

---

### 9.6 Test scripts reference

```powershell
# Unit tests
npm run test             # Vitest watch mode
npm run test:run         # Single pass
npm run test:coverage    # v8 coverage (text + JSON + HTML)
npm run test:integration # integration dir (currently empty, passes with no tests)

# E2E tests
npm run test:e2e         # Standard run via scripts/run-e2e-tests.js (workers=1)
npm run test:e2e:direct  # Direct: npx playwright test
npm run test:e2e:ui      # Playwright UI mode (interactive)
npm run test:e2e:debug   # Debug mode (step-through)
npm run test:e2e:headed  # Headed browser (visible)
npm run test:e2e:report  # Open last HTML report

# Documentation screenshots (manual, not in standard run)
npx playwright test src/tests/e2e/specs/take-screenshots.spec.ts --headed --reporter=list

# All layers
npm run test:all         # test:run + test:integration + test:e2e
npm run test:ci          # test:run + test:integration + test:e2e --reporter=junit
```

---

## 10. Backend Test Independence

> **Status**: Existing — both backend modules already have comprehensive standalone JUnit/TestContainers test suites. No React UI or npm is involved.

The Maven reactor in `peegeeq/` (the parent of this repo) contains all Java modules as siblings. Backend tests run directly against a real PostgreSQL container via TestContainers 2.0.2. The management UI (`peegeeq-management-ui`) is a separate Maven module and is never a dependency of the backend test modules — its presence or absence has no effect on backend test execution.

---

### 10.1 Test module inventory

| Maven module | Test classes | Coverage focus |
|---|---|---|
| `peegeeq-db` | 135 | DB pool, connection management, consumer groups, subscriptions, dead letter queue, cleanup jobs, backfill, partitioning, circuit breakers, resilience, metrics, performance |
| `peegeeq-rest` | 65 | All REST/WS/SSE handlers, queue lifecycle, message sending/consumption, setup management, health checks, CORS, dead letter, webhook delivery, monitoring |
| `peegeeq-test-support` | — (infrastructure) | `SharedPostgresTestExtension`, `PeeGeeQTestContainerFactory`, `PeeGeeQTestSchemaInitializer`, performance harness |
| `peegeeq-integration-tests` | 0 (empty, reserved) | Cross-module smoke tests — module exists in reactor but contains no tests yet |

---

### 10.2 JUnit 5 tag strategy

All tests are tagged; the parent `pom.xml` is the single source of truth for tag filtering — no module overrides it.

| Tag | Meaning | Typical run time |
|---|---|---|
| `@Tag("core")` | Pure unit tests — no I/O, no containers | < 1 s each |
| `@Tag("integration")` | Real PostgreSQL via TestContainers | 5–30 s each |
| `@Tag("performance")` | Throughput benchmarks, load tests | Minutes |
| `@Tag("smoke")` | Critical-path subset | Seconds |
| `@Tag("slow")` | Long-running stability tests | Minutes–hours |

---

### 10.3 Maven profiles and run commands

Run from the repo root (`C:\Users\markr\dev\java\corejava\peegeeq`). All commands must pipe through `Tee-Object` — see `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md` for the canonical command reference and mandatory `-Pall-tests` rule.

> **RULE**: After ANY code change, the only acceptable validation command is `-Pall-tests`. Partial profiles below are only for (a) pre-change baselines or (b) re-running a specific failure already identified by `-Pall-tests`.

```powershell
# ── REQUIRED after any code change ───────────────────────────────────────────

mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260615.txt


# ── Pre-change baseline (establish green before touching a module) ────────────

mvn test -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-core-20260615.txt
mvn test -Pintegration-tests -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-integration-20260615.txt
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20260615.txt


# ── Targeted debug (only after -Pall-tests identifies a specific failure) ─────

# DB module — core
mvn test -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-core-20260615.txt

# DB module — integration (PostgreSQL container)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20260615.txt

# REST module — core
mvn test -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-core-20260615.txt

# REST module — integration
mvn test -Pintegration-tests -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-integration-20260615.txt

# Integration-tests module
mvn test -Pintegration-tests -pl :peegeeq-integration-tests 2>&1 | Tee-Object -FilePath logs\peegeeq-integration-tests-integration-20260615.txt

# Smoke tests — all modules
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260615.txt

# Audit: find untagged tests (should report Tests run: 0 in every module)
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20260615.txt


# ── Coverage ─────────────────────────────────────────────────────────────────

mvn test jacoco:report -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-coverage-20260615.txt
# Report at: peegeeq-db/target/site/jacoco/index.html
```

---

### 10.4 TestContainers wiring

**Shared container pattern** (peegeeq-db): a single `SharedPostgresTestExtension` JUnit 5 extension starts one PostgreSQL container for the entire test class run. Schema is created once; tests share it with `@ResourceLock` guards for thread safety. Container is reused across classes within the same JVM (not across Maven forks).

**Per-test container** (peegeeq-rest): each integration test class starts its own `PostgreSQLContainer` (`withReuse(false)`) for clean isolation. Slightly slower but avoids cross-test state leakage at the handler level.

**Vert.x async context**: integration tests use `@ExtendWith(VertxExtension.class)` which injects a `Vertx` instance and `VertxTestContext` — async assertions complete via `testContext.completeNow()` / `testContext.failNow(t)`. Awaitility is available for polling-style waits.

---

### 10.5 Open gaps in backend test coverage

The following backend scenarios have no dedicated test coverage (identified during audit):

| Area | Gap |
|---|---|
| `SystemMonitoringHandler` — negative `totalConnections` | ✅ RESOLVED 2026-06-16 — decrement guarded by the null-check in both WS and SSE cleanup (§8.1); covered by `testActiveConnectionCountNeverNegativeAcrossLifecycle` (WS) + `testSseAbruptDisconnectKeepsConnectionCountNonNegative` (SSE) |
| `SystemMonitoringHandler` — lifetime-average `messagesPerSecond` | ✅ RESOLVED 2026-06-16 — per-connection delta rate, WS/SSE reconciled (§8.2); covered by `testMessagesPerSecondIsZeroWhenPendingCountUnchangedBetweenTicks` |
| `PeeGeeQMetrics.updateConnectionPoolMetrics()` | Method exists (lines 357–367) but is never called — no test covers pool metric propagation to Micrometer |
| `ConsumerAlertHandler` | No dedicated test class |
| Auth / RBAC | No tests — not yet implemented |
| `peegeeq-integration-tests` module | Reserved for cross-module smoke tests; currently empty |
| `ManagementApiHandler.getQueueConsumers` — `GET /api/v1/queues/:setupId/:queueName/consumers` | ✅ RESOLVED 2026-06-16 — covered by `ManagementApiIntegrationTest.testGetQueueConsumersEndpoint` (`@Order(21)`): subscribes a group, then asserts it appears in the consumers response with the documented fields. Backs the Phase 2 Consumers tab (§7.2). |
| `ManagementApiHandler.getQueueBindings` — `GET /api/v1/queues/:setupId/:queueName/bindings` | **No JUnit integration test** (2026-06-16). Returns an empty array by design (PeeGeeQ has no binding concept), but the handler is untested. |
| `ManagementApiHandler.getQueueDetails` — `GET /api/v1/queues/:setupId/:queueName` | ✅ RESOLVED 2026-06-16 — covered by `ManagementApiIntegrationTest.testGetQueueDetailsEndpoint` (`@Order(22)`): asserts the per-queue details contract (name/setup/implementationType/status + nested `statistics` and `config`) the Queue Details Overview tab uses. |
| `ManagementApiHandler.updateQueue` — `PUT /api/v1/management/queues/:setupId/:queueName` | **Only the 404 error path is covered** (`ManagementApiHandlerErrorTest.updateQueue_queueNotFound_returns404`); no happy-path config-update integration test (2026-06-16). |

The §8.1 and §8.2 bugs (negative connections, flat throughput) were the highest-priority backend fixes; both are **fixed (2026-06-16)** with `@Tag("regression")` tests in `SystemMonitoringHandlerTest.java` (see §8.1 / §8.2). The remaining gaps above (`updateConnectionPoolMetrics`, `ConsumerAlertHandler`, auth) are still open.

**Management-endpoint coverage audit (2026-06-16):** the management API surface is otherwise well covered — `ManagementApiIntegrationTest` (~28 tests incl. purge via `testQueuePurge_E2E`, full consumer-group lifecycle, messages, metrics, browsing), `SetupManagementIntegrationTest` (setup CRUD + `getSetupDetails`), `SSEQueueUpdatesIntegrationTest`, `SystemMonitoringHandlerTest`. The four queue-endpoint rows above are the only UI-consumed management endpoints with no (or error-path-only) JUnit integration coverage. Method note: path-grep audit — `getQueueConsumers`/`getQueueBindings` are definitively uncovered; `getQueueDetails`/`updateQueue` happy-path are "no direct test found".

---

### 10.6 Backend tests added (2026-06-15)

All three gaps from §10.5 that were actionable without first implementing a fix have been addressed:

**`SystemMonitoringHandlerTest.java` — two new regression tests (Tests 11 & 12)**

| Test | Order | Tag | What it verifies |
|---|---|---|---|
| `testActiveConnectionCountNeverNegativeAcrossLifecycle` | 11 | `integration, regression` | §8.1: After 5 abrupt WS disconnects (`ws.connection().close()` — triggers both `exceptionHandler` and `closeHandler` on the server), an observer WS must see `activeConnections >= 1` (itself). With the double-decrement bug each abrupt close leaves `totalConnections` one below its true value; after 5 closes the observer sees `1 - 5 = -4` → assertion fails. |
| `testMessagesPerSecondIsZeroWhenPendingCountUnchangedBetweenTicks` | 12 | `integration, regression` | §8.2: Seeds 5 pending messages (no consumer), configures a 2-second WS interval, collects two consecutive `system_stats` ticks. Between tick-1 and tick-2 the pending count is unchanged. The delta formula gives `(5 - 5) / 2 = 0`; the lifetime-average formula gives `5 / uptime > 0`. Asserts `messagesPerSecond == 0.0 ± 0.01` on tick-2 — **now passes** (fix applied 2026-06-16). |

The original Test 11 (SSE disconnect log-level) is renumbered to Test 13.

**Further tests added (2026-06-16)** — `SystemMonitoringHandlerTest` is now 18 tests, all green with zero ERROR log noise:
- `testSseAbruptDisconnectKeepsConnectionCountNonNegative` — SSE variant of §8.1 (covers `cleanupSSEConnection`).
- `testMalformedAndUnknownCommandsAreReportedAndStreamSurvives` — bad WS input is reported and the stream survives, logged at DEBUG not ERROR (quiet recovery).
- `testWebSocketReconnectAfterDropResumesWithFreshSession` / `testSseReconnectResumesWithFreshEventNumbering` — clean re-accept on reconnect (fresh `connectionId`, stream resumes, counter consistent).

Frontend E2E (Phase 6, §7.6): `overview-reconnect-recovery`, `overview-sse-reconnecting-banner`, `overview-stats-values` added; `event-stores-scope-filter` rewritten to genuinely exercise the scope filter.

**`peegeeq-integration-tests` — new `PeeGeeQCriticalPathSmokeTest.java`**

Six-step critical-path smoke suite in `dev.mars.peegeeq.integration`, tagged `@Tag("integration")`, running with a fresh TestContainers PostgreSQL. Exercises the full cross-module REST path without touching the React UI:

| Step | Test | What it checks |
|---|---|---|
| 1 | `testHealthEndpointResponds` | `GET /health` returns 200 |
| 2 | `testCreateSetupWithQueue` | `POST /api/v1/database-setup/create` returns 200/201, status = ACTIVE |
| 3 | `testSendMessagesToQueue` | Three `POST /api/v1/queues/:setupId/:queue/messages` all return 200 with messageId |
| 4 | `testQueueDetailsShowPendingMessages` | `GET /api/v1/queues/:setupId/:queue` shows `messageCount >= 3` |
| 5 | `testListSetupsContainsCreatedSetup` | `GET /api/v1/setups` response `{count, setupIds[]}` includes the setup |
| 6 | `testDeleteSetupAndVerifyRemoval` | `DELETE /api/v1/setups/:setupId` returns 204; subsequent `GET /api/v1/setups` confirms it is absent |

**Run the new backend tests (follow PEEGEEQ-TEST-COMMANDS.md):**

After ANY code change, the mandatory validation command is `-Pall-tests`:

```powershell
# REQUIRED after any code change — runs every test in every module
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260615.txt
```

Only use targeted commands below when re-running a **specific already-identified failure** from a prior `-Pall-tests` run:

```powershell
# Targeted debug — peegeeq-rest integration (after -Pall-tests identifies a failure here)
mvn test -Pintegration-tests -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-integration-20260615.txt

# Targeted debug — peegeeq-integration-tests (after -Pall-tests identifies a failure here)
mvn test -Pintegration-tests -pl :peegeeq-integration-tests 2>&1 | Tee-Object -FilePath logs\peegeeq-integration-tests-integration-20260615.txt
```

**Remaining open gaps** (§10.5):
- `PeeGeeQMetrics.updateConnectionPoolMetrics()` is dead code — wire it and add a Micrometer gauge test (blocked on §8.3 / Phase 7.11 implementation)
- No `ConsumerAlertHandler` test class
