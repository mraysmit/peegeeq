# PeeGeeQ Management UI - Architecture and Design

Last Updated: 2025-12-28

## Overview

The PeeGeeQ Management UI is a modern web-based administration console for managing PeeGeeQ message queues, consumer groups, and event stores. It provides real-time monitoring, configuration management, and operational tools inspired by RabbitMQ Management Console.

### Key Features

- Real-time monitoring with live statistics and system health
- Queue management (create, configure, pause, resume, delete)
- Consumer group management and monitoring
- Message browsing and inspection
- Event store configuration and monitoring
- Database setup management
- System overview dashboard

### Target Users

- System Administrators: Monitor system health and performance
- DevOps Engineers: Troubleshoot issues and manage deployments
- Developers: Test message flows and debug applications

## Architecture

### High-Level Architecture

PeeGeeQ follows a strict **hexagonal/ports & adapters** architecture with layered separation. The Management UI is the topmost layer that interacts with the system via REST APIs.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         MANAGEMENT UI LAYER                              │
│                        peegeeq-management-ui                             │
│                    (React/TypeScript web application)                    │
│                  Uses: peegeeq-rest via HTTP REST client                 │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ HTTP/REST
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                            REST LAYER                                    │
│                           peegeeq-rest                                   │
│                     (HTTP handlers, routing)                             │
│         Exposes: peegeeq-runtime services over REST/SSE endpoints        │
│              Uses: peegeeq-api (types) + peegeeq-runtime (services)      │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                       RUNTIME/COMPOSITION LAYER                          │
│                          peegeeq-runtime                                 │
│         Provides DatabaseSetupService facade via PeeGeeQRuntime          │
│            Wires together: peegeeq-db, native, outbox, bitemporal        │
│                  Single entry point for all PeeGeeQ services             │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    │              │              │
                    ▼              ▼              ▼
      ┌─────────────────┐ ┌─────────────────┐ ┌──────────────────┐
      │ peegeeq-native  │ │ peegeeq-outbox  │ │peegeeq-bitemporal│
      │ (Native queues) │ │ (Outbox pattern)│ │ (Event store)    │
      └─────────────────┘ └─────────────────┘ └──────────────────┘
                    │              │              │
                    └──────────────┼──────────────┘
                                   │
                                   ▼
      ┌──────────────────────────────────────────────────────────┐
      │                    DATABASE LAYER                        │
      │                      peegeeq-db                          │
      │   (PostgreSQL connectivity + service implementations)    │
      └──────────────────────────────────────────────────────────┘
                                   ▲
                                   │
      ┌──────────────────────────────────────────────────────────┐
      │                   CONTRACTS LAYER                        │
      │                     peegeeq-api                          │
      │            (Interfaces, DTOs, configs)                   │
      │              NO implementations                          │
      │              NO infrastructure                           │
      └──────────────────────────────────────────────────────────┘
```

**Key Architectural Principles:**

1. **peegeeq-api**: Pure contracts only (interfaces, DTOs, configs) with NO implementations
2. **peegeeq-runtime**: Composition layer that wires all modules together and exposes factory methods
3. **peegeeq-rest**: REST layer depends ONLY on peegeeq-api and peegeeq-runtime (never on db/native/outbox/bitemporal directly)
4. **Strict layering**: Each layer depends only on the layer directly below it - no cross-layer dependencies
5. **Dependency inversion**: All modules depend on peegeeq-api contracts, not concrete implementations

For complete architecture details, see: `docs-design/design/peegeeq-call-propagation/PEEGEEQ_CALL_PROPAGATION_GUIDE.md`

### Deployment Models

#### Development Mode
- Frontend: Vite dev server on port 3000
- Backend: Java REST server on port 8080
- CORS: Enabled (allows all origins - suitable for development only)
- **Why:** Separate dev server enables fast HMR and better debugging; CORS allows frontend to call backend on different port

#### Production Mode
- Frontend: Built static files served from /webroot by REST server
- Backend: Java REST server on port 8080
- Access: http://localhost:8080/ui/
- CORS: Enabled but not required (same origin)
- **Why:** Single-server deployment simplifies operations; no CORS issues; reduces infrastructure complexity and attack surface
- **Note:** CORS handler currently allows all origins; consider restricting to specific origins in production deployments

## Technology Stack

### Frontend

| Technology | Version | Purpose | Why Chosen |
|------------|---------|---------|------------|
| React | 18.2.0 | UI framework | Industry standard with excellent ecosystem, component reusability, and strong TypeScript support |
| TypeScript | 5.2.2 | Type safety | Catches errors at compile time, improves IDE support, and makes refactoring safer |
| Vite | 5.0.8 | Build tool and dev server | Extremely fast HMR (Hot Module Replacement), modern ESM-based builds, superior developer experience compared to Webpack |
| Redux Toolkit | 2.10.1 | State management | Includes RTK Query for API state management, eliminates boilerplate, provides automatic caching/refetching and optimistic updates |
| React Router | 7.7.0 | Client-side routing | De facto standard for React routing, supports nested routes and lazy loading |
| Ant Design | 5.12.8 | UI component library | Enterprise-grade React components with comprehensive design system, accessibility support, and extensive customization options |
| Recharts | 3.1.2 | Data visualization | React-native charting library with composable components, easier to customize than D3.js while maintaining good performance |
| Zustand | 5.0.8 | Lightweight state management | Simple, unopinionated state management for local UI state, complements Redux Toolkit for global state |
| Axios | 1.6.2 | HTTP client | Promise-based HTTP client with interceptors, request/response transformation, and automatic JSON handling |
| Vitest | 3.2.4 | Unit testing | Vite-native testing framework with same config, extremely fast execution, Jest-compatible API |
| Playwright | 1.54.1 | E2E testing | Cross-browser support, reliable auto-waiting, excellent debugging tools, better than Selenium/Cypress for modern web apps |

### Backend

| Technology | Purpose | Why Chosen |
|------------|---------|------------|
| Java 21 | Runtime | Latest LTS version with virtual threads, pattern matching, and modern language features |
| Vert.x | HTTP server and routing | Non-blocking I/O for high performance, lightweight compared to Spring Boot, excellent for REST APIs |
| Jackson | JSON serialization | Industry standard, fast performance, extensive annotation support for complex serialization scenarios |
| PeeGeeQ Core | Message queue engine | Core business logic for queue operations, provides PostgreSQL-backed message queue functionality |

## Design Principles

### 1. Progressive Enhancement
- Start with core functionality
- Add advanced features incrementally
- Graceful degradation for missing features
- **Why:** Allows rapid delivery of MVP while maintaining path to advanced features; reduces risk of over-engineering

### 2. Backend-First Development
- UI development is blocked until backend endpoints are implemented
- No mock data or placeholder data - all data comes from real backend APIs
- If an endpoint doesn't exist, implement the backend first before building the UI
- **Why:** Prevents building UI that doesn't work with real data; ensures integration issues are discovered early, not late in development

### 3. Responsive Design
- Mobile-first approach
- Tablet and desktop optimizations
- Accessible on all screen sizes
- **Why:** System administrators need to monitor queues from anywhere; mobile-first ensures core functionality works on smallest screens

### 4. Performance
- Lazy loading for routes and components
- Efficient re-rendering with React.memo
- Debounced search and filters
- Pagination for large datasets
- **Why:** Management UI must remain responsive even with thousands of queues/messages; poor performance leads to operational delays

### 5. Developer Experience
- TypeScript for type safety
- Comprehensive testing (unit, integration, E2E)
- Clear error messages
- Hot module replacement in development
- **Why:** Fast feedback loops and type safety reduce bugs and development time; good DX leads to better code quality

### 6. User Experience
- Consistent UI patterns
- Clear loading and error states
- Helpful empty states
- Keyboard navigation support
- **Why:** Operators use this tool under pressure during incidents; consistent, clear UI reduces cognitive load and prevents mistakes

## Component Architecture

### Directory Structure

```
src/
├── components/          # Reusable UI components
│   ├── layout/         # Layout components (Header, Sidebar, etc.)
│   ├── common/         # Common components (Button, Card, etc.)
│   └── features/       # Feature-specific components
├── pages/              # Page components (routes)
│   ├── Overview.tsx
│   ├── Queues.tsx
│   ├── ConsumerGroups.tsx
│   ├── EventStores.tsx
│   ├── MessageBrowser.tsx
│   └── DatabaseSetups.tsx
├── store/              # State management
│   ├── api/           # RTK Query API slices
│   └── slices/        # Redux slices
├── hooks/              # Custom React hooks
├── utils/              # Utility functions
├── types/              # TypeScript type definitions
└── styles/             # Global styles
```

### Core Components

#### Layout Components

- Layout.tsx: Main application layout with responsive sidebar, header, and content area
- Sidebar.tsx: Navigation sidebar with route links and active state
- Header.tsx: Application header with system health indicator and connection status

#### Common Components

- Card.tsx: Reusable card container with consistent styling
- Table.tsx: Data table with sorting, pagination, and row selection
- StatCard.tsx: Statistics display card with metric value and label
- LoadingSpinner.tsx: Consistent loading indicator
- ErrorMessage.tsx: User-friendly error display with retry actions

#### Feature Components

Queue Components:
- QueueList.tsx: Queue table with filters
- QueueCard.tsx: Individual queue card
- CreateQueueForm.tsx: Queue creation form
- QueueActions.tsx: Queue action buttons

Consumer Group Components:
- ConsumerGroupList.tsx: Consumer group table
- ConsumerGroupCard.tsx: Individual group card
- ConsumerGroupDetails.tsx: Detailed view

Message Components:
- MessageList.tsx: Message browser table
- MessageDetails.tsx: Message detail view
- MessageFilters.tsx: Message filtering UI

## State Management

### RTK Query API Slices

The application uses RTK Query for server state management with automatic caching, refetching, and optimistic updates.

#### queuesApi.ts

Manages queue data with endpoints for:
- List queues with filters
- Get queue details
- Create/update/delete queues
- Queue operations (pause, resume, purge)

Features:
- Automatic caching with configurable TTL
- Optimistic updates for mutations
- Automatic refetching on window focus
- Tag-based cache invalidation

#### consumerGroupsApi.ts

Manages consumer group data:
- List consumer groups with filters
- Get consumer group details
- Update consumer group configuration
- Delete consumer groups

#### eventStoresApi.ts

Manages event store data:
- List event stores
- Get event store details
- Create/update event stores
- Event store statistics

### Local State Management

React Context for:
- Theme preferences
- User settings
- UI state (sidebar collapsed, etc.)
- **Why:** Context avoids prop drilling for global UI state; lightweight alternative to Redux for non-server state

Component State for:
- Form inputs
- Local UI interactions
- Temporary data
- **Why:** useState keeps component-specific state local; prevents unnecessary global state pollution and re-renders

## API Integration

### Base Configuration

```typescript
// src/config/api.ts
export const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
export const API_TIMEOUT = 30000; // 30 seconds
```

### Error Handling

Error Types:
1. Network Errors: Connection refused, timeout
2. HTTP Errors: 4xx, 5xx status codes
3. Validation Errors: Invalid request data
4. Business Logic Errors: Queue already exists, etc.

Error Display:
- Toast notifications for transient errors
- Inline error messages for form validation
- Error boundaries for component crashes
- Retry mechanisms for failed requests

**Why this approach:** Different error types require different UX; transient errors (network) should be retryable, validation errors need inline context, crashes need graceful degradation. This layered approach ensures users always know what went wrong and what to do next.

## REST API Reference

### System and Health APIs

#### Health Check

```
GET /api/v1/health
```

Purpose: Check if backend service is running
Response: 200 OK

#### System Overview

```
GET /api/v1/management/overview
```

Purpose: Get system statistics and overview data

Response:
```json
{
  "systemStats": {
    "totalQueues": 15,
    "totalConsumerGroups": 8,
    "totalEventStores": 3,
    "totalMessages": 12500,
    "messagesPerSecond": 45.2,
    "activeConnections": 12,
    "uptime": "5d 3h 22m"
  },
  "recentActivity": []
}
```

Implementation Status:
- Endpoint exists
- Queue statistics use real database queries
- Consumer group counts use actual subscription data
- Event store counts use real event store stats
- recentActivity returns empty array (TODO)

### Queue Management APIs

#### List All Queues

```
GET /api/v1/management/queues
```

Query Parameters:
- type: Filter by queue type (comma-separated)
- status: Filter by status (comma-separated)
- setupId: Filter by setup ID
- search: Search query string
- sortBy: Field to sort by
- sortOrder: Sort order (asc, desc)
- page: Page number (1-based)
- pageSize: Items per page

Status: Implemented

#### Get Queue Details

```
GET /api/v1/queues/:setupId/:queueName
```

Status: Implemented

#### Create Queue

```
POST /api/v1/management/queues
```

Request Body:
```json
{
  "name": "orders-queue",
  "setup": "production",
  "type": "standard",
  "durability": "durable",
  "maxLength": 10000,
  "autoDelete": false,
  "ttl": 3600000
}
```

Status: Implemented

#### Delete Queue

```
DELETE /api/v1/management/queues/:queueName
```

Query Parameters:
- ifEmpty: Only delete if queue is empty
- ifUnused: Only delete if no consumers

Status: Implemented

#### Queue Operations

Pause Queue:
```
POST /api/v1/queues/:setupId/:queueName/pause
```

Status: Implemented (December 24, 2025)
- Pauses all consumer group subscriptions for the queue
- Returns count of paused subscriptions

Resume Queue:
```
POST /api/v1/queues/:setupId/:queueName/resume
```

Status: Implemented (December 24, 2025)
- Resumes all consumer group subscriptions for the queue
- Returns count of resumed subscriptions

Purge Queue:
```
POST /api/v1/queues/:setupId/:queueName/purge
```

Status: Partial (endpoint exists, implementation incomplete)

Get Queue Consumers:
```
GET /api/v1/queues/:setupId/:queueName/consumers
```

Status: Partial (returns empty array)

Get Queue Bindings:
```
GET /api/v1/queues/:setupId/:queueName/bindings
```

Status: Partial (returns empty array)

### Consumer Group APIs

#### List Consumer Groups

```
GET /api/v1/management/consumer-groups
```

Query Parameters:
- queueName: Filter by queue name
- status: Filter by status
- page: Page number
- pageSize: Items per page

Status: Implemented (uses real subscription data)

#### Get Consumer Group Details

```
GET /api/v1/management/consumer-groups/:groupName
```

Status: Implemented

#### Create Consumer Group

```
POST /api/v1/management/consumer-groups
```

Request Body:
```json
{
  "name": "new-processors",
  "queueName": "orders-queue",
  "prefetchCount": 10,
  "ackMode": "auto"
}
```

Status: Implemented

#### Delete Consumer Group

```
DELETE /api/v1/management/consumer-groups/:groupName
```

Status: Implemented

### Event Store APIs

#### List Event Stores

```
GET /api/v1/management/event-stores
```

Status: Implemented (uses real event store data)

#### Get Event Store Details

```
GET /api/v1/management/event-stores/:storeName
```

Status: Implemented (uses real event store stats)

#### Create Event Store

```
POST /api/v1/management/event-stores
```

Status: Partial (endpoint exists, needs completion)

#### Delete Event Store

```
DELETE /api/v1/management/event-stores/:storeName
```

Status: Partial (endpoint exists, needs completion)

### Message APIs

#### Browse Messages

```
GET /api/v1/management/queues/:queueName/messages
```

Query Parameters:
- limit: Max messages to return (default: 50)
- offset: Offset for pagination
- filter: Filter expression

Status: Partial (endpoint exists, returns empty array)

#### Get Message Details

```
GET /api/v1/management/queues/:queueName/messages/:messageId
```

Status: Partial

#### Publish Message

```
POST /api/v1/queues/:setupId/:queueName/publish
```

Request Body:
```json
{
  "payload": "{\"orderId\": 12345}",
  "headers": {
    "content-type": "application/json"
  }
}
```

Status: Implemented

### Database Setup APIs

#### List Database Setups

```
GET /api/v1/database-setup/list
```

Status: Implemented

#### Create Database Setup

```
POST /api/v1/database-setup/create
```

Request Body:
```json
{
  "setupId": "production",
  "host": "localhost",
  "port": 5432,
  "database": "peegeeq_prod",
  "username": "peegeeq",
  "password": "secret",
  "schema": "public",
  "sslEnabled": false
}
```

Status: Implemented

#### Delete Database Setup

```
DELETE /api/v1/database-setup/:setupId
```

Status: Implemented

### Error Handling

Standard Error Response:
```json
{
  "error": {
    "code": "QUEUE_NOT_FOUND",
    "message": "Queue 'orders-queue' not found",
    "details": {
      "queueName": "orders-queue",
      "setupId": "prod-01"
    },
    "timestamp": "2024-11-20T10:30:00Z"
  }
}
```

HTTP Status Codes:
- 200 OK: Success
- 201 Created: Resource created
- 204 No Content: Success, no response body
- 400 Bad Request: Invalid request data
- 401 Unauthorized: Authentication required
- 403 Forbidden: Insufficient permissions
- 404 Not Found: Resource not found
- 409 Conflict: Resource conflict
- 422 Unprocessable Entity: Validation error
- 500 Internal Server Error: Server error
- 503 Service Unavailable: Service temporarily unavailable

## UI/UX Design

### Design System

**Why a design system:** Ensures consistency across all UI components, speeds up development by providing reusable patterns, and makes the application feel professional and cohesive.

#### Color Palette

Light Mode:
- Primary: Blue (#3B82F6)
- Success: Green (#10B981)
- Warning: Yellow (#F59E0B)
- Error: Red (#EF4444)
- Background: White (#FFFFFF)
- Surface: Gray-50 (#F9FAFB)
- Text: Gray-900 (#111827)

Dark Mode:
- Primary: Blue (#60A5FA)
- Success: Green (#34D399)
- Warning: Yellow (#FBBF24)
- Error: Red (#F87171)
- Background: Gray-900 (#111827)
- Surface: Gray-800 (#1F2937)
- Text: Gray-50 (#F9FAFB)

**Why these colors:** Blue conveys trust and stability (appropriate for infrastructure tools); semantic colors (green/yellow/red) match universal conventions for success/warning/error; dark mode reduces eye strain during long monitoring sessions.

#### Typography

- Font Family: Inter (system fallback: -apple-system, BlinkMacSystemFont, "Segoe UI")
- Headings:
  - H1: 2.25rem (36px), font-weight: 700
  - H2: 1.875rem (30px), font-weight: 600
  - H3: 1.5rem (24px), font-weight: 600
- Body: 1rem (16px), font-weight: 400
- Small: 0.875rem (14px)

**Why Inter font:** Designed specifically for UI/screens with excellent readability at small sizes; open-source; system font fallbacks ensure fast loading and native feel.

#### Spacing

- Base unit: 4px
- Common spacing: 8px, 12px, 16px, 24px, 32px, 48px
- Container max-width: 1280px

#### Components

Buttons:
- Primary: Blue background, white text
- Secondary: Gray background, dark text
- Danger: Red background, white text
- Ghost: Transparent background, colored text
- Sizes: sm (32px), md (40px), lg (48px)

Cards:
- Border radius: 8px
- Shadow: 0 1px 3px rgba(0,0,0,0.1)
- Padding: 16px (sm), 24px (md), 32px (lg)

Tables:
- Striped rows for better readability
- Hover state on rows
- Sticky header for long tables
- Responsive: horizontal scroll on mobile

### Page Layouts

#### Overview Dashboard

```
┌─────────────────────────────────────────────────────────┐
│  Header: System Health | Connection Status              │
├─────────────────────────────────────────────────────────┤
│ ┌─────┐  ┌─────────────────────────────────────────┐   │
│ │     │  │  Statistics Cards (4 columns)           │   │
│ │ S   │  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐   │   │
│ │ i   │  │  │Queues│ │Groups│ │Stores│ │ Msgs │   │   │
│ │ d   │  │  └──────┘ └──────┘ └──────┘ └──────┘   │   │
│ │ e   │  ├─────────────────────────────────────────┤   │
│ │ b   │  │  Charts (2 columns)                     │   │
│ │ a   │  │  ┌──────────────┐ ┌──────────────┐     │   │
│ │ r   │  │  │ Message Rate │ │ Queue Status │     │   │
│ │     │  │  └──────────────┘ └──────────────┘     │   │
│ │     │  ├─────────────────────────────────────────┤   │
│ │     │  │  Recent Activity Table                  │   │
│ └─────┘  └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

#### Queue Management

```
┌─────────────────────────────────────────────────────────┐
│  Header: Queues | Create Queue Button                   │
├─────────────────────────────────────────────────────────┤
│ ┌─────┐  ┌─────────────────────────────────────────┐   │
│ │     │  │  Filters & Search                       │   │
│ │ S   │  │  [Search] [Type▾] [Status▾] [Clear]    │   │
│ │ i   │  ├─────────────────────────────────────────┤   │
│ │ d   │  │  Queue Table                            │   │
│ │ e   │  │  ┌────────────────────────────────────┐ │   │
│ │ b   │  │  │Name│Setup│Msgs│Consumers│Actions  │ │   │
│ │ a   │  │  ├────────────────────────────────────┤ │   │
│ │ r   │  │  │ ... queue rows ...                 │ │   │
│ │     │  │  └────────────────────────────────────┘ │   │
│ │     │  │  Pagination: [< 1 2 3 >]               │   │
│ └─────┘  └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Responsive Breakpoints

- Mobile: < 640px (sm)
- Tablet: 640px - 1024px (md, lg)
- Desktop: > 1024px (xl, 2xl)

Mobile Adaptations:
- Collapsible sidebar (hamburger menu)
- Stacked statistics cards (1 column)
- Horizontal scroll for tables
- Simplified charts
- Bottom navigation (optional)

### Accessibility

WCAG 2.1 Level AA Compliance:
- Color contrast ratios (4.5:1 for text)
- Keyboard navigation support
- ARIA labels and roles
- Focus indicators
- Screen reader support

Keyboard Shortcuts:
- Ctrl/Cmd + K: Global search (future)
- Esc: Close modals/dialogs
- Tab: Navigate between elements
- Enter: Activate buttons/links
- Arrow keys: Navigate tables

**Why accessibility matters:** System administrators may have disabilities; keyboard navigation is faster for power users; WCAG compliance is often a legal requirement for enterprise software; accessible design benefits all users.

## Security and Performance

### Security Considerations

#### Authentication and Authorization

Current State: No authentication (development only)

Production Requirements:
1. Authentication:
   - JWT-based authentication
   - Session management
   - Secure token storage (httpOnly cookies)
   - Token refresh mechanism
   - **Why JWT:** Stateless authentication scales horizontally; httpOnly cookies prevent XSS attacks; refresh tokens enable long sessions without security risk

2. Authorization:
   - Role-based access control (RBAC)
   - Permission levels: Admin, Operator, Viewer
   - Resource-level permissions
   - **Why RBAC:** Prevents unauthorized queue deletion/purging; separates read-only monitoring from write operations; supports principle of least privilege

3. API Security:
   - CORS configuration (currently allows all origins; should be restricted in production)
   - CSRF protection
   - Rate limiting
   - Input validation and sanitization
   - **Why these measures:** CORS prevents unauthorized domains from calling API; CSRF protection prevents malicious sites from triggering actions; rate limiting prevents DoS attacks; input validation prevents injection attacks

#### Data Security

- Sensitive Data: Mask credentials in UI
- Audit Logging: Track all management operations
- Secure Communication: HTTPS in production
- Content Security Policy: Prevent XSS attacks

### Performance Optimization

#### Frontend Performance

Current Optimizations:
1. Code Splitting: Route-based lazy loading
2. Memoization: React.memo for expensive components
3. Debouncing: Search and filter inputs (300ms)
4. Pagination: Limit data fetching (20 items/page)
5. Caching: RTK Query automatic caching

**Why these optimizations:** Code splitting reduces initial bundle size (faster first load); memoization prevents unnecessary re-renders; debouncing reduces API calls during typing; pagination prevents loading thousands of queues at once; caching eliminates redundant network requests.

Planned Optimizations:
1. Virtual Scrolling: For large tables (1000+ rows)
2. Image Optimization: Lazy loading, WebP format
3. Bundle Size: Tree shaking, code splitting
4. Service Worker: Offline support, caching
5. Web Workers: Heavy computations off main thread

#### Backend Performance

API Response Times (Target):
- Health check: < 50ms
- List endpoints: < 200ms
- Detail endpoints: < 100ms
- Mutations: < 500ms

**Why these targets:** Health checks must be fast for load balancer probes; list endpoints are called frequently so 200ms keeps UI responsive; mutations can be slower since they're less frequent and users expect some delay for write operations.

Optimization Strategies:
1. Caching: Redis for frequently accessed data
2. Pagination: Server-side pagination for large datasets
3. Compression: Gzip/Brotli for responses
4. Connection Pooling: Efficient database connections
5. Async Processing: Non-blocking I/O

**Why these strategies:** Caching reduces database load for read-heavy workloads; pagination prevents transferring megabytes of data; compression reduces bandwidth (especially for JSON); connection pooling eliminates connection overhead; async I/O maximizes throughput on limited threads.

#### Monitoring and Metrics

Frontend Metrics:
- Page load time (target: < 2s)
- Time to interactive (target: < 3s)
- First contentful paint (target: < 1s)
- API call latency
- Error rates

Backend Metrics:
- Request throughput (requests/sec)
- Response times (p50, p95, p99)
- Error rates (4xx, 5xx)
- Active connections
- Resource utilization (CPU, memory)

## Development Workflow

### Local Development

1. Start Backend:
   ```bash
   cd peegeeq-rest
   mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"
   ```

2. Start Frontend:
   ```bash
   cd peegeeq-management-ui
   npm install
   npm run dev
   ```

3. Access UI: http://localhost:3000

### Building for Production

```bash
npm run build
```

Output: ../peegeeq-rest/src/main/resources/webroot

### Code Quality

```bash
# Linting
npm run lint

# Type checking
npm run type-check

# Format code
npm run format
```

## References

### Related Documentation

- PEEGEEQ_MANAGMENT_UI_TESTING.md: Testing approach and design
- PEEGEEQ_MANAGMENT_UI_STATUS.md: Implementation status and production readiness

### External Resources

- React Documentation: https://react.dev/
- TypeScript Handbook: https://www.typescriptlang.org/docs/
- Redux Toolkit & RTK Query: https://redux-toolkit.js.org/
- Ant Design: https://ant.design/
- Vite Guide: https://vitejs.dev/guide/
- Playwright: https://playwright.dev/

### Inspiration

- RabbitMQ Management Console: https://www.rabbitmq.com/management.html
- Apache Kafka UI: https://github.com/provectus/kafka-ui
- Redis Commander: https://github.com/joeferner/redis-commander

