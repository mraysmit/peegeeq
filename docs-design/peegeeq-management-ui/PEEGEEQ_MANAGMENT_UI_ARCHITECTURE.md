# PeeGeeQ Management UI - Architecture and Design

**Status:** CURRENT ARCHITECTURE REFERENCE
**Last reconciled:** 2026-10-06 against commit `f1c5d25d`

Every file, route, class, and script named below was confirmed to exist at that commit. Line
references point at the files as they were at that commit; re-check them after later edits.

## Contents

1. [Overview](#overview)
2. [Design constraints](#design-constraints)
3. [Architecture](#architecture)
4. [Deployment models](#deployment-models)
5. [Technology stack](#technology-stack)
6. [Component architecture](#component-architecture)
7. [State management](#state-management)
8. [API integration](#api-integration)
9. [REST and streaming reference](#rest-and-streaming-reference)
10. [Security](#security)
11. [Development workflow](#development-workflow)
12. [Related documentation](#related-documentation)

## Overview

The PeeGeeQ Management UI is a web-based administration console for PeeGeeQ message queues,
consumer groups, event stores, and database setups. It lives in `peegeeq-management-ui` and
talks only to `peegeeq-rest` over HTTP, Server-Sent Events (SSE), and WebSocket.

### Key features

- System overview dashboard with live statistics
- Database setup management
- Queue list, queue detail tabs, create, pause, resume, purge, delete
- Consumer group list, create, delete
- Non-destructive message browsing with a live observe stream
- Event store list, event posting, event query, causation tree, aggregate stream
- Notifications page and header bell
- Settings page with REST, WebSocket, and SSE connectivity checks

### Target users

- System administrators monitoring health and throughput
- Operations engineers diagnosing incidents
- Developers inspecting message and event flows

## Design constraints

### 1. Non-destructive viewing

The UI is an administration tool. Viewing data must never consume, acknowledge, or alter a
message. Every API the UI calls for display must be read-only against the queue.

This constraint is implemented in the code:

- Message listing: `ManagementApiHandler.getRealMessages` (`peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java` L1035-1076) calls
  `queueFactory.createBrowser(queueName, Object.class)` and then `browser.browse(limit, offset)`.
  No consumer is created.
- Live stream: `ServerSentEventsHandler.handleQueueMessageStream`
  (`peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ServerSentEventsHandler.java`
  L49-55) is documented as a non-destructive stream backed by `QueueBrowser.tail(...)`, a plain
  `SELECT` that observes new rows and pushes them over SSE. The browser is closed when the client
  disconnects.
- UI side: `peegeeq-management-ui/src/pages/MessageBrowser.tsx` L166-169 states that live mode is
  a non-destructive observe, that the UI must never consume messages to display them, and that it
  subscribes to `/queues/{setupId}/{queueName}/messages/stream`.

Consumer subscriptions are never opened for display. Any new display feature must follow the same
rule and must be covered by a Playwright contract such as
`src/tests/e2e/specs/message-browser-nondestructive-live.spec.ts`.

### 2. Backend-first development

No UI feature ships against mock data. If an endpoint does not exist, the backend is implemented
first. Playwright end-to-end tests run against a real `peegeeq-rest` server and a Testcontainers
PostgreSQL instance; see `src/tests/global-setup.ts`.

### 3. Operational clarity

Every page shows explicit loading, error, and empty states. Errors surface to the user; no catch
block swallows a failure silently.

## Architecture

PeeGeeQ uses a layered ports-and-adapters architecture. The Management UI is the topmost layer.

```
peegeeq-management-ui   React/TypeScript SPA; calls peegeeq-rest over HTTP, SSE, WebSocket
        |
peegeeq-rest            Vert.x HTTP server, routing, handlers; depends on peegeeq-api + peegeeq-runtime
        |
peegeeq-runtime         Composition layer; DatabaseSetupService facade; wires db, native, outbox, bitemporal
        |
peegeeq-native | peegeeq-outbox | peegeeq-bitemporal
        |
peegeeq-db              PostgreSQL connectivity and service implementations
        |
peegeeq-api             Contracts only: interfaces, DTOs, configuration; no implementations
```

Principles:

1. `peegeeq-api` holds contracts only.
2. `peegeeq-runtime` composes modules and exposes factory methods.
3. `peegeeq-rest` depends only on `peegeeq-api` and `peegeeq-runtime`.
4. Each layer depends only on the layer below it.

Full call-propagation detail: `docs-design/peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md`.

## Deployment models

### Development

- Frontend: Vite dev server on port 3000 (`vite.config.ts` L17).
- Vite proxies `/api` to `http://localhost:8088` and `/ws` to `ws://localhost:8088`
  (`vite.config.ts` L19-29).
- Backend: `peegeeq-rest` on port 8088 (`peegeeq-rest/src/main/resources/conf/rest-server.json` L2).
- The UI's runtime backend URL comes from `src/services/configService.ts`; its default is
  `http://127.0.0.1:8088` (L15) and the value is persisted in `localStorage`.

### Production

- `npm run build` writes static files to `../peegeeq-rest/src/main/resources/webroot`
  (`vite.config.ts` L32).
- `peegeeq-rest` serves them at `/ui/*` and redirects `/` to `/ui/`
  (`PeeGeeQRestServer.java` L532-533).
- Same origin; CORS is not needed for the bundled UI.

### Known port inconsistency in the code

`src/api/endpoints.ts` L7 declares `API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'`.
That fallback (`8080`) differs from the `configService.ts` default (`127.0.0.1:8088`) and from
`rest-server.json` (`8088`). This is recorded here as an inconsistency in the code. It is not
fixed by this document.

## Technology stack

### Frontend (`peegeeq-management-ui/package.json`)

| Technology | Declared version | Purpose |
|---|---|---|
| React | `^18.2.0` | UI framework |
| TypeScript | `^5.2.2` | Type safety |
| Vite | `^6.0.0` | Build tool and dev server |
| Redux Toolkit | `^2.10.1` | RTK Query for queue API state |
| React Router | `^7.7.0` | Client-side routing |
| Ant Design | `^5.12.8` | UI component library |
| Recharts | `^3.1.2` | Charts |
| Zustand | `^5.0.8` | Lightweight UI state |
| Axios | `^1.6.2` | HTTP client in `PeeGeeQClient.ts` |
| Zod | `^4.2.1` | Response validation (`src/types/queue.validation.ts`) |
| Vitest | `^3.2.4` | Unit tests |
| Playwright | `1.60.0` | End-to-end tests |
| @testcontainers/postgresql | `^12.0.1` | PostgreSQL container for E2E setup |

### Backend

| Technology | Purpose |
|---|---|
| Java 25 (root `pom.xml` L75-77, `maven.compiler.release` 25) | Runtime |
| Vert.x | HTTP server, router, SSE, WebSocket |
| Jackson | JSON serialization |
| PeeGeeQ core modules | Queue, outbox, and event-store engines |

## Component architecture

### Directory structure (`peegeeq-management-ui/src/`)

```
src/
├── api/
│   ├── PeeGeeQClient.ts        Axios client; resolves the base URL from configService at call time
│   ├── endpoints.ts            Endpoint constants (see "Known port inconsistency" and the note below)
│   ├── types.ts                API request/response types
│   └── index.ts
├── components/
│   ├── layout/Header.tsx       Page title, ConnectionStatus, refresh, notification bell, user menu
│   └── common/
│       ├── ConnectionStatus.tsx   WS/SSE status badge (checks /ws/health and /api/v1/sse/health)
│       ├── StatCard.tsx
│       ├── FilterBar.tsx
│       ├── ConfirmDialog.tsx
│       ├── ErrorBoundary.tsx
│       └── SetupScopeBar.tsx      Setup selector shared by scoped pages
├── pages/
│   ├── Overview.tsx
│   ├── DatabaseSetups.tsx
│   ├── QueuesEnhanced.tsx         Active queue list
│   ├── QueueDetailsEnhanced.tsx   Active queue detail tabs
│   ├── Queues.tsx                 Legacy list, mounted at /queues-old
│   ├── QueueDetails.tsx           Legacy detail, mounted at /queues-old/:queueName
│   ├── ConsumerGroups.tsx
│   ├── EventStores.tsx
│   ├── EventsPage.tsx             Post event + query events
│   ├── CausationTreePage.tsx      /causation-tree
│   ├── AggregateStreamPage.tsx    /aggregate-stream
│   ├── MessageBrowser.tsx         Non-destructive browse + live observe stream
│   ├── NotificationsPage.tsx      /notifications
│   ├── Settings.tsx
│   ├── TestHarness.tsx            Rendered by pathname check, not a Route (see below)
│   ├── Monitoring.tsx             On disk; not imported or routed
│   ├── DeveloperPortal.tsx        On disk; not imported or routed
│   ├── SchemaRegistry.tsx         On disk; not imported or routed
│   └── QueueDesigner.tsx          On disk; not imported or routed
├── services/
│   ├── apiConstants.ts            API_PREFIX = /api/v1 and relative endpoint names
│   ├── configService.ts           Backend URL config persisted in localStorage
│   └── websocketService.ts        WebSocket (/ws/monitoring) and SSE (sse/metrics, sse/queues/:setupId) services
├── store/
│   ├── index.ts                   Redux store
│   └── api/
│       ├── apiBase.ts             RTK Query createApi; baseUrl = configService apiUrl + /api/v1
│       └── queuesApi.ts           Queue endpoints
├── stores/
│   └── managementStore.ts         Zustand store: notifications and shared UI state
├── hooks/
│   └── useRealTimeUpdates.ts      WebSocket/SSE hook
├── types/
│   ├── queue.ts
│   └── queue.validation.ts        Zod schemas
├── tests/                         Vitest setup, fixtures, Playwright specs and page objects
├── App.tsx                        Router + inline Ant Design Sider/Menu (no separate Sidebar component)
└── main.tsx
```

### Routes (`src/App.tsx` L148-165)

| Path | Page |
|---|---|
| `/` | `Overview` |
| `/database-setups` | `DatabaseSetups` |
| `/queues` | `QueuesEnhanced` |
| `/queues/:setupId/:queueName` | `QueueDetailsEnhanced` |
| `/queues-old` | `Queues` |
| `/queues-old/:setupId/:queueName` | `QueueDetailsEnhanced` |
| `/queues-old/:queueName` | `QueueDetails` |
| `/consumer-groups` | `ConsumerGroups` |
| `/event-stores` | `EventStores` |
| `/events` | `EventsPage` |
| `/causation-tree` | `CausationTreePage` |
| `/aggregate-stream` | `AggregateStreamPage` |
| `/messages` | `MessageBrowser` |
| `/notifications` | `NotificationsPage` |
| `/settings` | `Settings` |

Notes:

- `TestHarness.tsx` is rendered when `location.pathname === '/test-harness'` (`App.tsx` L134-135).
  It is not a `<Route>`.
- `Monitoring.tsx`, `DeveloperPortal.tsx`, `SchemaRegistry.tsx`, and `QueueDesigner.tsx` exist
  on disk but are not imported in `App.tsx` and have no route or menu entry. They are unreachable.
- The notification bell in `Header.tsx` is wired: L75-80 read `notifications`, `unreadCount`,
  and `markAllNotificationsRead` from `useManagementStore`, and L119-123 bind `BellOutlined` to
  `openNotifications`. The user menu's logout item is a no-op (`Header.tsx` L95-96).
- The Queue Details "Bindings" tab is a placeholder with no API call
  (`QueueDetailsEnhanced.tsx` L818-823). Bindings are a RabbitMQ concept and do not exist in PeeGeeQ.
- There is no separate Sidebar component; the sidebar is inline in `App.tsx`.
- No generic `Card`, `Table`, `LoadingSpinner`, or `ErrorMessage` components exist; Ant Design
  components are used directly.

## State management

### RTK Query (`src/store/api/queuesApi.ts`)

`queuesApi.ts` is the only RTK Query slice. Its endpoints and the backend paths they call
(relative to `/api/v1`):

| Endpoint | Method and path |
|---|---|
| `getQueues` | `GET /management/queues?type&status&setupId&search&sortBy&sortOrder&page&pageSize` |
| `getQueueDetails` | `GET /queues/:setupId/:queueName` |
| `createQueue` | `POST /management/queues` |
| `updateQueueConfig` | `PATCH /management/queues/:setupId/:queueName/config` |
| `getMessages` | `GET /queues/:setupId/:queueName/messages?count&ackMode&offset&filter` |
| `publishMessage` | `POST /queues/:setupId/:queueName/publish` |
| `performQueueOperation` | `POST .../purge`, `POST .../pause`, `POST .../resume`, `DELETE /management/queues/:setupId/:queueName` |
| `moveMessages` | `POST /management/queues/:setupId/:queueName/move` |
| `getQueueChartData` | see `queuesApi.ts` L236 |

`updateQueueConfig` and `moveMessages` target paths that `PeeGeeQRestServer.java` does not
register. They are recorded here as UI-side constants without a backend route.

### Direct Axios calls from pages

Most pages call the backend directly with `axios` and `getVersionedApiUrl(...)` from
`configService.ts` (for example `QueuesEnhanced.tsx` L94, `ConsumerGroups.tsx` L90,
`EventStores.tsx` L94, `DatabaseSetups.tsx` L68, `Overview.tsx` L121, `EventsPage.tsx` L141,
`MessageBrowser.tsx` L126, `QueueDetailsEnhanced.tsx` L121).

### Shared client (`src/api/PeeGeeQClient.ts`)

`peeGeeQClient` is imported by two pages only: `CausationTreePage.tsx` (L23, `queryEvents`) and
`AggregateStreamPage.tsx` (L25, `getUniqueAggregates`, `queryEvents`). Its other methods (setups,
dead-letter, subscriptions, health, webhooks, consumer groups, ack/nack) are defined but not called
by any page. It resolves the backend base URL from `configService.ts` at call time and takes its
paths from `src/api/endpoints.ts`.

`endpoints.ts` also declares constants with no registered backend route at commit `f1c5d25d`:
`QUEUE_ENDPOINTS.ACK`, `QUEUE_ENDPOINTS.NACK`, `EVENT_STORE_ENDPOINTS.LIST` (`/eventstores/:setupId`),
`CONSUMER_GROUP_ENDPOINTS.STATS`, `MANAGEMENT_ENDPOINTS.QUEUE_DETAILS` (GET),
`MANAGEMENT_ENDPOINTS.INFO`, `SSE_ENDPOINTS.QUEUE_UPDATES` (`/sse/queues/:setupId/:queueName`),
and `SSE_ENDPOINTS.ALL_QUEUES` (`/sse/queues`). Treat them as unimplemented.

### Zustand (`src/stores/managementStore.ts`)

Holds notifications and cross-page UI state consumed by `Header.tsx` and `NotificationsPage.tsx`.

### Local state

React `useState` holds form inputs, modal visibility, and per-page loading and error state.
There is no theme or user-preference context.

## API integration

### Base URL resolution

1. `configService.ts` returns the stored backend config or `DEFAULT_CONFIG`
   (`apiUrl: 'http://127.0.0.1:8088'`, `wsUrl: 'ws://127.0.0.1:8088'`, L14-16).
2. `apiBase.ts` builds the RTK Query `baseUrl` as `<apiUrl>/api/v1` (L16).
3. `PeeGeeQClient.ts` and `websocketService.ts` resolve URLs the same way at call time.
4. `endpoints.ts` L7 still carries the unused `8080` fallback described above.

### Error handling

- Network and HTTP errors surface as Ant Design messages or inline alerts on the page.
- Form validation errors render inline.
- `ErrorBoundary.tsx` isolates render crashes.
- Backend error responses are JSON objects with `error` or `message` fields; see the handler
  classes in `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/`.

## REST and streaming reference

Source of truth: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`
L349-543 (routes) and L202-221 (`routeWebSocket`). Only registered routes are listed. Paths are
relative to the server root. "UI caller" names the module that issues the call.

### Health

| Method | Path | Handler | UI caller |
|---|---|---|---|
| GET | `/api/v1/health` | inline (L349) | `Settings.tsx`, `ConnectionStatus.tsx` |
| GET | `/api/v1/sse/health` | inline (L362) | `ConnectionStatus.tsx`, `configService.ts` |
| GET | `/health` | inline (L536) | none |
| GET | `/metrics` | inline (L543) | none |

### Database setups

| Method | Path | Handler | UI caller |
|---|---|---|---|
| GET | `/api/v1/setups` | `setupHandler::listSetups` | `DatabaseSetups.tsx` L68, `QueuesEnhanced.tsx` L94, `ConsumerGroups.tsx` L129, `EventStores.tsx` L122, `EventsPage.tsx` L100, `CausationTreePage.tsx` L63 |
| POST | `/api/v1/setups` | `setupHandler::createSetup` | none (`DatabaseSetups.tsx` uses the legacy create route) |
| GET | `/api/v1/setups/:setupId` | `setupHandler::getSetupDetails` | `DatabaseSetups.tsx` L74, `Overview.tsx` L91 |
| GET | `/api/v1/setups/:setupId/status` | `setupHandler::getSetupStatus` | `PeeGeeQClient` method only |
| DELETE | `/api/v1/setups/:setupId` | `setupHandler::deleteSetup` | `DatabaseSetups.tsx` L159 |
| POST | `/api/v1/setups/:setupId/detach` | `setupHandler::detachSetup` | `DatabaseSetups.tsx` L142 |
| POST | `/api/v1/setups/:setupId/database/drop` | `setupHandler::dropSetupDatabase` | `DatabaseSetups.tsx` L179 |
| GET | `/api/v1/setups/:setupId/queues` | `setupHandler::listQueues` | `PeeGeeQClient` method only |
| POST | `/api/v1/setups/:setupId/queues` | `setupHandler::addQueue` | `PeeGeeQClient` method only |
| GET | `/api/v1/setups/:setupId/eventstores` | `setupHandler::listEventStores` | `PeeGeeQClient` method only |
| POST | `/api/v1/setups/:setupId/eventstores` | `setupHandler::addEventStore` | `PeeGeeQClient` method only |
| POST | `/api/v1/database-setup/create` | `setupHandler::createSetup` (legacy) | `DatabaseSetups.tsx` L230 |
| POST | `/api/v1/database-setup/connect` | `setupHandler::connectToExistingSetup` (legacy) | `DatabaseSetups.tsx` L273 |
| DELETE | `/api/v1/database-setup/:setupId` | `setupHandler::destroySetup` (legacy) | none |
| GET | `/api/v1/database-setup/:setupId/status` | `setupHandler::getSetupStatus` (legacy) | `endpoints.ts` constant only |
| POST | `/api/v1/database-setup/:setupId/queues` | `setupHandler::addQueue` (legacy) | none |

There is no `/api/v1/database-setup/list` route. Setup listing is `GET /api/v1/setups`.

### Management overview, queues, metrics

| Method | Path | Handler | UI caller |
|---|---|---|---|
| GET | `/api/v1/management/overview` | `managementHandler::getSystemOverview` | `Overview.tsx` L121 |
| GET | `/api/v1/management/queues` | `managementHandler::getQueues` | `queuesApi.getQueues`, `MessageBrowser.tsx` L101 |
| POST | `/api/v1/management/queues` | `managementHandler::createQueue` | `queuesApi.createQueue`, `QueuesEnhanced.tsx` L164 |
| PUT | `/api/v1/management/queues/:setupId/:queueName` | `managementHandler::updateQueue` | none |
| DELETE | `/api/v1/management/queues/:setupId/:queueName` | `managementHandler::deleteQueue` | `queuesApi.performQueueOperation` |
| GET | `/api/v1/management/messages` | `managementHandler::getMessages` | `MessageBrowser.tsx` L126 |
| GET | `/api/v1/management/metrics` | `managementHandler::getMetrics` | `apiConstants.ENDPOINTS.METRICS` constant |

`GET /api/v1/management/queues` accepts `type`, `status`, `setupId`, `search`, `sortBy`,
`sortOrder`, `page`, and `pageSize` (see `queuesApi.getQueues`). The `search` filter is covered by
`peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/ManagementQueueSearchIntegrationTest.java`.

### Queue details and operations

| Method | Path | Handler | UI caller |
|---|---|---|---|
| GET | `/api/v1/queues/:setupId/:queueName` | `managementHandler::getQueueDetails` | `queuesApi.getQueueDetails` (`QueueDetailsEnhanced.tsx` L43) |
| GET | `/api/v1/queues/:setupId/:queueName/stats` | `queueHandler::getQueueStats` | `PeeGeeQClient` method only |
| GET | `/api/v1/queues/:setupId/:queueName/consumers` | `managementHandler::getQueueConsumers` | `QueueDetailsEnhanced.tsx` L121 |
| GET | `/api/v1/queues/:setupId/:queueName/bindings` | `managementHandler::getQueueBindings` | none; always returns an empty array |
| GET | `/api/v1/queues/:setupId/:queueName/messages` | `managementHandler::getQueueMessages` (browse, non-destructive) | `QueueDetailsEnhanced.tsx` L152, L370; `queuesApi.getMessages` |
| POST | `/api/v1/queues/:setupId/:queueName/messages` | `queueHandler::sendMessage` | `QueueDetailsEnhanced.tsx` L347 |
| POST | `/api/v1/queues/:setupId/:queueName/messages/batch` | `queueHandler::sendMessages` | none |
| POST | `/api/v1/queues/:setupId/:queueName/publish` | `queueHandler::sendMessage` | `queuesApi.publishMessage` |
| POST | `/api/v1/queues/:setupId/:queueName/purge` | `managementHandler::purgeQueue` | `QueueDetailsEnhanced.tsx` L251; `queuesApi.performQueueOperation` |
| POST | `/api/v1/queues/:setupId/:queueName/pause` | `managementHandler::pauseQueue` | `QueueDetailsEnhanced.tsx` L221; `queuesApi.performQueueOperation` |
| POST | `/api/v1/queues/:setupId/:queueName/resume` | `managementHandler::resumeQueue` | `QueueDetailsEnhanced.tsx` L221; `queuesApi.performQueueOperation` |
| DELETE | `/api/v1/queues/:setupId/:queueName` | `managementHandler::deleteQueueByName` | `QueueDetailsEnhanced.tsx` L287 |

Pause and resume act on the queue's consumer-group subscriptions and return the affected count.
Purge deletes rows from the queue's message tables and returns the purged count.

### Consumer groups

Management (cross-setup) routes:

| Method | Path | Handler | UI caller |
|---|---|---|---|
| GET | `/api/v1/management/consumer-groups` | `managementHandler::getConsumerGroups` | `ConsumerGroups.tsx` L90 |
| POST | `/api/v1/management/consumer-groups` | `managementHandler::createConsumerGroup` | `ConsumerGroups.tsx` L194 |
| DELETE | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName` | `managementHandler::deleteConsumerGroup` | `ConsumerGroups.tsx` L178 |
| POST | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/pause` | `managementHandler::pauseConsumerGroup` | `ConsumerGroups.tsx` L243 |
| POST | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/resume` | `managementHandler::resumeConsumerGroup` | `ConsumerGroups.tsx` L255 |
| POST | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill` | `managementHandler::backfillConsumerGroup` | `ConsumerGroups.tsx` L267 |

Queue-scoped routes (no page calls these; `PeeGeeQClient` defines methods for some of them):

| Method | Path | Handler |
|---|---|---|
| POST | `/api/v1/queues/:setupId/:queueName/consumer-groups` | `consumerGroupHandler::createConsumerGroup` |
| GET | `/api/v1/queues/:setupId/:queueName/consumer-groups` | `consumerGroupHandler::listConsumerGroups` |
| GET | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `consumerGroupHandler::getConsumerGroup` |
| DELETE | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `consumerGroupHandler::deleteConsumerGroup` |
| POST | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members` | `consumerGroupHandler::joinConsumerGroup` |
| DELETE | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId` | `consumerGroupHandler::leaveConsumerGroup` |
| POST/GET/DELETE | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `consumerGroupHandler::*SubscriptionOptions` |

Subscription lifecycle, backfill, and partitioned-consumption routes under
`/api/v1/setups/:setupId/subscriptions/:topic/...` (L497-516) are registered. `PeeGeeQClient`
defines methods for list, get, pause, resume, heartbeat, and cancel; no page calls them.

### Event stores

| Method | Path | Handler | UI caller |
|---|---|---|---|
| GET | `/api/v1/management/event-stores` | `managementHandler::getEventStores` | `EventStores.tsx` |
| POST | `/api/v1/management/event-stores` | `managementHandler::createEventStore` | `EventStores.tsx` |
| DELETE | `/api/v1/management/event-stores/:storeId` | `managementHandler::deleteEventStore` | `EventStores.tsx` |
| DELETE | `/api/v1/eventstores/:setupId/:eventStoreName` | `managementHandler::deleteEventStoreByName` | none |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/stream` | `eventStoreHandler::handleEventStream` (SSE) | `PeeGeeQClient.ts` (`EVENT_STORE_ENDPOINTS.STREAM`) |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/events` | `eventStoreHandler::storeEvent` | `EventsPage.tsx` |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events` | `eventStoreHandler::queryEvents` | `EventsPage.tsx`, `CausationTreePage.tsx`, `AggregateStreamPage.tsx` |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId` | `eventStoreHandler::getEvent` | `PeeGeeQClient.ts` |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions` | `eventStoreHandler::getAllVersions` | `PeeGeeQClient.ts` |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at` | `eventStoreHandler::getAsOfTransactionTime` | none |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections` | `eventStoreHandler::appendCorrection` | `PeeGeeQClient.ts` |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/aggregates` | `eventStoreHandler::getUniqueAggregates` | `AggregateStreamPage.tsx` |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/aggregate-summary/reconcile` | `eventStoreHandler::reconcileAggregateSummary` | none |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/stats` | `eventStoreHandler::getStats` | none |

There is no `GET /api/v1/management/event-stores/:storeId` detail route.

### Dead-letter, health, telemetry, alerts, webhooks

Routes under `/api/v1/setups/:setupId/deadletter/...` (L489-494),
`/api/v1/setups/:setupId/health[...]` (L527-529), `/api/v1/setups/:setupId/db-telemetry` (L519),
`/api/v1/setups/:setupId/consumer-alerts/...` (L522-524), and the webhook-subscription routes
(L414-417) are registered. `DEAD_LETTER_ENDPOINTS`, `HEALTH_ENDPOINTS`, and `WEBHOOK_ENDPOINTS`
in `endpoints.ts` cover the dead-letter, health, and webhook routes.

### Server-Sent Events

| Path | Handler | UI caller |
|---|---|---|
| `/api/v1/sse/health` | inline (L362) | `ConnectionStatus.tsx`, `configService.ts`, `Settings.tsx` |
| `/api/v1/sse/metrics` | `monitoringHandler::handleSSEMetrics` | `websocketService.ts` L267, `Overview.tsx` |
| `/sse/metrics` | `monitoringHandler::handleSSEMetrics` (legacy unversioned) | none |
| `/api/v1/sse/queues/:setupId` | `sseHandler::handleQueueUpdates` (`event: queue-changed`) | `websocketService.ts` L286, `useRealTimeUpdates.ts` L170 |
| `/api/v1/queues/:setupId/:queueName/messages/stream` | `sseHandler::handleQueueMessageStream` (non-destructive tail) | `MessageBrowser.tsx` L190 |
| `/api/v1/queues/:setupId/:queueName/stats/stream` | `sseHandler::handleQueueStatsStream` | `QueueDetailsEnhanced.tsx` |

### WebSocket (`PeeGeeQRestServer.routeWebSocket`, L202-221)

| Path | Behaviour | UI caller |
|---|---|---|
| `/ws/queues/...` | `webSocketHandler.handleQueueStream` | none in the management UI |
| `/ws/monitoring` | System stats stream | `websocketService.ts` L150 |
| `/ws/health` | One-shot health reply | `ConnectionStatus.tsx` L50, `Settings.tsx` L90 |

## Security

Current state at `f1c5d25d`:

- No authentication or authorization exists in `peegeeq-rest` or the UI. The header's user menu
  shows a static label and a no-op logout.
- CORS: `PeeGeeQRestServer.java` L149-161 requires a non-empty `allowedOrigins` list and installs
  a `CorsHandler` (L317, L592). `rest-server.json` L3-12 lists localhost and 127.0.0.1 origins on
  ports 3000, 3001, 5173, and 8088. A single `*` entry is handled separately at L155; its exact
  effect was not verified for this document.
- Credentials entered in the Database Setups form are sent to the backend and are not masked in
  transit; use HTTPS in front of `peegeeq-rest` outside a trusted network.

Authentication, RBAC, CSRF protection, and rate limiting are not implemented. They remain a
product decision; see `docs-design/tasks/tasks.md` backlog.

## Development workflow

### Start the backend

```bash
cd peegeeq-rest
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.StartRestServer"
```

`StartRestServer.main` (`peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/StartRestServer.java`
L82) ignores command-line arguments. The port comes from `conf/rest-server.json` (8088), overridden
by environment variables and then system properties (L87-104).

### Start the frontend

```bash
cd peegeeq-management-ui
npm install
npm run dev
```

Open `http://localhost:3000`. The Vite proxy forwards `/api` and `/ws` to port 8088.

### Build for production

```bash
npm run build
```

Output goes to `../peegeeq-rest/src/main/resources/webroot`.

### Code quality and tests (`package.json` scripts)

```bash
npm run lint          # eslint
npm run type-check    # tsc --noEmit
npm run test:run      # vitest run
npm run test:e2e      # node scripts/run-e2e-tests.js (headed Playwright, real backend)
npm run test:all      # inventory guard + unit + e2e; used by the Maven all-tests profile
```

There is no `format` script. The Playwright inventory guard
(`scripts/check-playwright-inventory.js --functional 419 --screenshots 72`) runs first in
`test:all`. Documentation screenshots are regenerated by
`src/tests/e2e/specs/take-screenshots.spec.ts` into `docs-design/peegeeq-management-ui/screenshots/`.

## Related documentation

- `docs-design/peegeeq-management-ui/archive/EXECUTION_CHECKLIST.md` — archived execution plan
- `peegeeq-management-ui/docs/PEEGEEQ_MANAGMENT_UI_TESTING_GUIDE.md` — testing approach
- `peegeeq-management-ui/docs/tasks/MANAGEMENT_UI_ENHANCEMENTS-14-Jun-2026.md` — functionality inventory, screenshots, stub catalogue
- `peegeeq-management-ui/docs/archive/IMPLEMENTATION_PLAN.md` — original plan (archived)
- `docs-design/peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md` — layer call propagation
- `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` — test standards
- `docs-design/tasks/tasks.md` — live task register

External references: React (https://react.dev/), Redux Toolkit (https://redux-toolkit.js.org/),
Ant Design (https://ant.design/), Vite (https://vitejs.dev/guide/), Playwright (https://playwright.dev/).
