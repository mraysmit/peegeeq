# PeeGeeQ Management UI — Enhancement Plan

**Date**: 2026-06-14  
**Scope**: `peegeeq-management-ui` frontend + `peegeeq-rest` backend where noted.

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
| **Consumers** | `QueueDetailsEnhanced.tsx` | Stub — shows info banner "Consumers Tab - Coming in Week 5". No API call, no data. |
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
| Setup "View Details" action | `DatabaseSetups.tsx:185` `message.info('View details coming soon')` | — | none | ⛔ |

**Asymmetry:** event-store *create* is real but *delete* is a no-op — `deleteEventStoreImpl` contains no removal logic, visible directly in the source.

### 6.2 Queue operations

| Flow | Frontend | REST route → handler | Backend reality | Status |
|---|---|---|---|---|
| Publish message | `QueueDetailsEnhanced.tsx:226` POST `queues/{s}/{q}/messages` | `POST /api/v1/queues/:setupId/:queueName/messages` → `QueueHandler.sendMessage` | `producer.send(...)` (`QueueHandler.java:482`) → DB | ✅ |
| Get messages (browse) | `QueueDetailsEnhanced.tsx:250` GET `queues/{s}/{q}/messages` | `GET …/messages` → `getQueueMessages` (2108) | `createBrowser().browse()` → DB | ✅ |
| Pause / Resume | `QueueDetailsEnhanced.tsx:116` POST `queues/{s}/{q}/pause`\|`resume` | `POST …/pause`, `…/resume` → `pauseQueue` (2255) / `resumeQueue` (2311) | lists subs → `subscriptionService.pause`/`resume` each | ✅ |
| Purge (details page) | `QueueDetailsEnhanced.tsx:146` POST `queues/{s}/{q}/purge` | `POST …/purge` → `purgeQueue` (2207) | `queueFactory.purgeMessages` → DB | ✅ |
| **Purge (list page)** | `QueuesEnhanced.tsx:250` `message.info('Purge functionality coming in Week 4')` | — (no call) | none | ⛔ |
| Queue details (Overview tab) | `QueueDetailsEnhanced.tsx` RTK `useGetQueueDetailsQuery` | `GET /api/v1/queues/:setupId/:queueName` → `getQueueDetails` (1875) | live stats + subscription-derived status | ✅ |

**Purge split:** the same labelled action is real on the Queue Details page but a no-op toast on the Queues list page.

### 6.3 Queue Details tabs

| Tab | Frontend | Backend endpoint | Status |
|---|---|---|---|
| Overview | real cards (`QueueDetailsEnhanced.tsx:493`) | `getQueueDetails` (1875) | ✅ |
| Messages | real (publish / get / table) | as §6.2 | ✅ |
| **Consumers** | stub banner "Coming in Week 5" (`:588`) | `getQueueConsumers` (1994) returns **real** subscription data | ⚠️ backend-only — UI never calls the working endpoint |
| **Bindings** | stub banner "Coming in Week 5" (`:637`) | `getQueueBindings` (2067) **always returns an empty array** | ⛔ both ends (PeeGeeQ has no binding concept) |
| **Charts** | stub banner "Coming in Week 2" (`:649`) | none | ⛔ |

### 6.4 Real-time channels

| Channel | Frontend | Backend | Status | E2E spec |
|---|---|---|---|---|
| System stats WS `/ws/monitoring` | `Overview.tsx:183` `createSystemMonitoringService` | `SystemMonitoringHandler.handleWebSocketMonitoring` → `system_stats` | ✅ | `overview-live-stats-update` |
| Metrics SSE `/sse/metrics` | `Overview.tsx` `createSystemMetricsSSE` | `SystemMonitoringHandler.handleSSEMetrics` | ✅ | `system-metrics-sse` |
| Queue-updates SSE `/sse/queues/{setupId}` | `QueuesEnhanced.tsx:126` `createQueueUpdatesSSE` → `refetch()` | `ServerSentEventsHandler.handleQueueUpdates` (58): event bus `peegeeq.queues.changed.{setupId}` → SSE | ⚠️ wired, but the bus event fires **only on queue create/update/delete** (`publishQueueChanged`, 2430) — not on message publish/purge, so no live message-count | `queue-updates-sse` |
| Message-stream SSE `/queues/{s}/{q}/stream` | `MessageBrowser.tsx:195` native `EventSource` | `ServerSentEventsHandler.handleQueueStream` (58→534/640): `createConsumer` + `subscribe` → SSE `data` events | ✅ | `message-sse-stream`, `message-browser-sse-failure` |
| Message-stream WS `/ws/queues/{s}/{q}` | `createMessageStreamService` exists; `useMessageStream` hook **commented out** (`MessageBrowser.tsx:173`) | `WebSocketHandler.handleQueueStream` (46→275): `createConsumer` — real | ❌ frontend-disconnected | — |
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

### Phase order

> **Codebase review 2026-06-14** refined Phases 6 and 7: both are largely already implemented. Phase 6 (reconnecting UI) only needs E2E coverage and SSE parity checks. Phase 7 (toasts) only needs Consumer Groups. A new Phase 4a was added for the missing header title mappings.

| # | What it delivers | Layer(s) | Prerequisite |
|---|---|---|---|
| 1 | Fix event-store delete | `peegeeq-rest` | — |
| 2 | Queue Details — Consumers tab wired | `peegeeq-management-ui` | — |
| 3 | Queues list — Purge action wired | `peegeeq-management-ui` | — |
| 4 | Database Setups — View Details modal | `peegeeq-management-ui` | — |
| 4a | Header title mapping (quick fix) | `peegeeq-management-ui` | — |
| 5 | WS queue stream in Queue Details | `peegeeq-management-ui` | — |
| 6 | Reconnection UI — E2E coverage + SSE parity *(UI already implemented)* | `peegeeq-management-ui` | — |
| 7 | Consumer Groups success toasts *(other resource flows already done)* | `peegeeq-management-ui` | — |
| 7a | Notifications page (`/notifications`) | `peegeeq-management-ui` | — |
| 8 | Backend `management_event` + bell end-to-end | `peegeeq-rest` + `peegeeq-management-ui` | 7 |
| 9 | Live queue message count via SSE | `peegeeq-rest` + `peegeeq-management-ui` | — |
| 10 | Authentication layer | TBD — architecture decision required | — |

Phases 1–4a are the lowest-risk deliveries: Phase 1 is a confirmed defect, Phases 2–4 have a fully-real backend endpoint waiting for a UI stub to be removed, Phase 4a is a two-line lookup-table fix. Phases 5–9 add new real-time behaviour. Phase 10 is gated on an architecture call (§7.10).

---

### 7.1 Fix event-store delete

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

---

### 7.2 Queue Details — Consumers tab wired

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

### 7.3 Queues list — Purge action wired

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

### 7.4 Database Setups — View Details modal

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

### 7.4a Header title mapping (quick fix)

**Source**: codebase review 2026-06-14. `Header.tsx:18` has a `pageTitle` lookup map that does not include `/causation-tree`, `/aggregate-stream`, or `/queues/:setupId/:queueName`. Navigating to any of these shows the fallback `"PeeGeeQ Management"` as the page title.

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

### 7.5 WS queue stream in Queue Details

**Source**: §6.4 ❌. `createMessageStreamService` in `websocketService.ts` exists; `useMessageStream` hook is commented out; `QueueDetailsEnhanced.tsx` polls REST. Backend `WebSocketHandler.handleQueueStream` is real (`ws://host/ws/queues/{setupId}/{queueName}`).

**Failing test (write first)**

Add to `src/tests/e2e/specs/queue-details-operations.spec.ts`:
```
test('new message appears in Messages tab in real time without page reload')
  1. Navigate to /queues/default/{queueName}, click Messages tab
  2. Publish a message via REST API directly
  3. Assert a new row appears in the tab within 5 seconds
  4. No manual refresh performed
```

**Implementation**

`QueueDetailsEnhanced.tsx` — on Messages tab activation, call `createMessageStreamService({setupId, queueName})`; prepend each received WS message to the local list (cap at 50, matching Message Browser); disconnect on tab change / unmount (store the service in a `useRef`). Add a "Live" badge to the Messages tab label when the WS connection is open. Expose `onConnect` / `onDisconnect` from `createMessageStreamService` in `websocketService.ts` if not already present.

**Acceptance**: new messages appear within 5 s; "Live" badge visible while connected; all tests pass.

---

### 7.6 Reconnection UI — E2E coverage + SSE parity

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

### 7.7 Consumer Groups success toasts

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

### 7.7a Notifications page (`/notifications`)

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

**Failing tests (write first)**

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

**Acceptance**: bell increments from a backend-emitted event; drawer shows the event; client-side notifications from resource events coexist; all tests pass.

---

### 7.9 Live queue message count via SSE

**Source**: §2.2 / §6.4. `QueuesEnhanced.tsx:126` already calls `refetch()` on each SSE `queue-changed` event. The gap is on the backend: `QueueHandler.sendMessage` does not call `publishQueueChanged` after a successful send (confirmed by source — 0 calls in that method), so the SSE never fires on message publish.

**Failing test (write first)**

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

### Running the suite

```powershell
npx playwright test --workers=1
```

`workers: 1` is mandatory — the suite shares `SETUP_ID = 'default'` across spec files (§4).
