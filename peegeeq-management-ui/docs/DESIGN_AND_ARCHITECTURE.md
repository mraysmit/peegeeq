# PeeGeeQ Management UI - Design & Architecture

**Consolidated Design Documentation**  
*Last Updated: 2025-12-23*

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Technology Stack](#technology-stack)
4. [Design Principles](#design-principles)
5. [Component Architecture](#component-architecture)
6. [State Management](#state-management)
7. [API Integration](#api-integration)
8. [Feature Comparison with RabbitMQ](#feature-comparison-with-rabbitmq)
9. [UI/UX Design](#uiux-design)
10. [Security & Performance](#security--performance)

---

## Overview

The PeeGeeQ Management UI is a modern web-based administration console for managing PeeGeeQ message queues, consumer groups, and event stores. It provides real-time monitoring, configuration management, and operational tools similar to RabbitMQ Management Console.

### Key Features

- **Real-time Monitoring**: Live statistics, metrics, and system health
- **Queue Management**: Create, configure, pause, resume, and delete queues
- **Consumer Group Management**: Monitor and manage consumer groups
- **Message Browser**: Browse, inspect, and manage messages
- **Event Store Management**: Configure and monitor event stores
- **System Overview**: Dashboard with key metrics and recent activity

### Target Users

- **System Administrators**: Monitor system health and performance
- **DevOps Engineers**: Troubleshoot issues and manage deployments
- **Developers**: Test message flows and debug applications

---

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Browser (Client)                          │
│  ┌────────────────────────────────────────────────────────┐ │
│  │         React Application (Port 3000)                   │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │ │
│  │  │   UI Layer   │  │  State Mgmt  │  │  API Client  │ │ │
│  │  │  (Components)│  │  (RTK Query) │  │   (Axios)    │ │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘ │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ HTTP/REST (CORS enabled)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              PeeGeeQ REST Server (Port 8080)                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  REST API Layer (/api/v1/management/*)                 │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │ │
│  │  │   Queues     │  │   Consumer   │  │ Event Stores │ │ │
│  │  │   Endpoints  │  │   Groups     │  │  Endpoints   │ │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘ │ │
│  └────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────┐ │
│  │         PeeGeeQ Core Engine (Java)                      │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │ │
│  │  │ Queue Engine │  │  Consumer    │  │ Event Store  │ │ │
│  │  │              │  │  Management  │  │   Engine     │ │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘ │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Deployment Models

#### Development Mode
- **Frontend**: Vite dev server on port 3000
- **Backend**: Java REST server on port 8080
- **CORS**: Enabled for localhost:3000

#### Production Mode
- **Frontend**: Built static files served from `/webroot` by REST server
- **Backend**: Java REST server on port 8080
- **Access**: http://localhost:8080/ui/
- **CORS**: Not required (same origin)

---

## Technology Stack

### Frontend

| Technology | Version | Purpose |
|------------|---------|---------|
| **React** | 18.3.1 | UI framework |
| **TypeScript** | 5.6.2 | Type safety |
| **Vite** | 5.4.11 | Build tool & dev server |
| **RTK Query** | 2.3.0 | API state management |
| **React Router** | 7.1.1 | Client-side routing |
| **Tailwind CSS** | 3.4.17 | Styling framework |
| **Recharts** | 2.15.0 | Data visualization |
| **Lucide React** | 0.469.0 | Icon library |
| **Vitest** | 2.1.8 | Unit testing |
| **Playwright** | 1.49.1 | E2E testing |

### Backend

| Technology | Purpose |
|------------|---------|
| **Java 21** | Runtime |
| **Vert.x** | HTTP server & routing |
| **Jackson** | JSON serialization |
| **PeeGeeQ Core** | Message queue engine |

---

## Design Principles

### 1. **Progressive Enhancement**
- Start with core functionality
- Add advanced features incrementally
- Graceful degradation for missing features

### 2. **Real Data First**
- Prioritize real backend integration over mock data
- Use placeholders only when backend endpoints don't exist yet
- Clear visual indicators for placeholder vs. real data

### 3. **Responsive Design**
- Mobile-first approach
- Tablet and desktop optimizations
- Accessible on all screen sizes

### 4. **Performance**
- Lazy loading for routes and components
- Efficient re-rendering with React.memo
- Debounced search and filters
- Pagination for large datasets

### 5. **Developer Experience**
- TypeScript for type safety
- Comprehensive testing (unit, integration, E2E)
- Clear error messages
- Hot module replacement in development

### 6. **User Experience**
- Consistent UI patterns
- Clear loading and error states
- Helpful empty states
- Keyboard navigation support

---

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
│   └── MessageBrowser.tsx
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

**`Layout.tsx`** - Main application layout
- Responsive sidebar navigation
- Header with system status
- Main content area
- Footer

**`Sidebar.tsx`** - Navigation sidebar
- Route links with active state
- Collapsible on mobile
- Icon + label navigation

**`Header.tsx`** - Application header
- System health indicator
- Connection status
- User actions (future)

#### Common Components

**`Card.tsx`** - Reusable card container
- Consistent styling
- Optional header/footer
- Loading and error states

**`Table.tsx`** - Data table component
- Sortable columns
- Pagination
- Row selection
- Empty states

**`StatCard.tsx`** - Statistics display card
- Metric value
- Label and icon
- Trend indicator (future)

**`LoadingSpinner.tsx`** - Loading indicator
- Consistent loading UI
- Size variants

**`ErrorMessage.tsx`** - Error display
- User-friendly error messages
- Retry actions

#### Feature Components

**Queue Components**
- `QueueList.tsx` - Queue table with filters
- `QueueCard.tsx` - Individual queue card
- `CreateQueueForm.tsx` - Queue creation form
- `QueueActions.tsx` - Queue action buttons

**Consumer Group Components**
- `ConsumerGroupList.tsx` - Consumer group table
- `ConsumerGroupCard.tsx` - Individual group card
- `ConsumerGroupDetails.tsx` - Detailed view

**Message Components**
- `MessageList.tsx` - Message browser table
- `MessageDetails.tsx` - Message detail view
- `MessageFilters.tsx` - Message filtering UI

---

## State Management

### RTK Query API Slices

The application uses **RTK Query** for server state management with automatic caching, refetching, and optimistic updates.

#### `queuesApi.ts`

```typescript
export const queuesApi = createApi({
  reducerPath: 'queuesApi',
  baseQuery: fetchBaseQuery({ baseUrl: API_BASE_URL }),
  tagTypes: ['Queue'],
  endpoints: (builder) => ({
    getQueues: builder.query<QueueListResponse, QueueFilters>({
      query: (filters) => ({
        url: '/api/v1/management/queues',
        params: filters,
      }),
      providesTags: ['Queue'],
    }),
    createQueue: builder.mutation<Queue, CreateQueueRequest>({
      query: (queue) => ({
        url: '/api/v1/management/queues',
        method: 'POST',
        body: queue,
      }),
      invalidatesTags: ['Queue'],
    }),
    // ... more endpoints
  }),
});
```

**Features:**
- Automatic caching with configurable TTL
- Optimistic updates for mutations
- Automatic refetching on window focus
- Tag-based cache invalidation

#### `consumerGroupsApi.ts`

Manages consumer group data with similar patterns:
- List consumer groups with filters
- Get consumer group details
- Update consumer group configuration
- Delete consumer groups

#### `eventStoresApi.ts`

Manages event store data:
- List event stores
- Get event store details
- Create/update event stores
- Event store statistics

### Local State Management

**React Context** for:
- Theme preferences
- User settings
- UI state (sidebar collapsed, etc.)

**Component State** for:
- Form inputs
- Local UI interactions
- Temporary data

---

## API Integration

### Base Configuration

```typescript
// src/config/api.ts
export const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
export const API_TIMEOUT = 30000; // 30 seconds
```

### Axios Instance

```typescript
// src/utils/axios.ts
const axiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
axiosInstance.interceptors.request.use(
  (config) => {
    // Add auth token if available
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor
axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    // Handle common errors
    if (error.response?.status === 401) {
      // Handle unauthorized
    }
    return Promise.reject(error);
  }
);
```

### Error Handling

**Error Types:**
1. **Network Errors**: Connection refused, timeout
2. **HTTP Errors**: 4xx, 5xx status codes
3. **Validation Errors**: Invalid request data
4. **Business Logic Errors**: Queue already exists, etc.

**Error Display:**
- Toast notifications for transient errors
- Inline error messages for form validation
- Error boundaries for component crashes
- Retry mechanisms for failed requests

---

## Feature Comparison with RabbitMQ

This section compares PeeGeeQ Management UI features with RabbitMQ Management Console to guide feature prioritization.

### ✅ Implemented Features

| Feature | RabbitMQ | PeeGeeQ | Status |
|---------|----------|---------|--------|
| **System Overview** | ✅ | ✅ | Complete |
| **Queue List** | ✅ | ✅ | Complete |
| **Queue Details** | ✅ | ✅ | Complete |
| **Consumer Groups** | ✅ | ✅ | Complete |
| **Message Browser** | ✅ | ✅ | Complete |
| **Health Check** | ✅ | ✅ | Complete |

### 🚧 Partially Implemented

| Feature | RabbitMQ | PeeGeeQ | Gap |
|---------|----------|---------|-----|
| **Real-time Updates** | ✅ WebSocket | ⚠️ Polling | Need WebSocket |
| **Charts & Graphs** | ✅ | ⚠️ | Need real data |
| **Message Operations** | ✅ Full CRUD | ⚠️ Read-only | Need publish/delete |
| **Queue Operations** | ✅ Full CRUD | ⚠️ Partial | Need pause/resume |

### ❌ Not Yet Implemented

| Feature | RabbitMQ | PeeGeeQ | Priority |
|---------|----------|---------|----------|
| **User Management** | ✅ | ❌ | Medium |
| **Permissions/ACL** | ✅ | ❌ | Medium |
| **Virtual Hosts** | ✅ | ❌ | Low (N/A) |
| **Clustering** | ✅ | ❌ | High |
| **Policies** | ✅ | ❌ | Medium |
| **Federation** | ✅ | ❌ | Low |
| **Shovel** | ✅ | ❌ | Low |

### Feature Priority Matrix

**High Priority** (Production Critical):
1. Real-time updates via WebSocket
2. Complete queue operations (pause/resume/delete)
3. Message publish functionality
4. Clustering support
5. Performance metrics with real data

**Medium Priority** (Enhanced Management):
1. User management and authentication
2. Role-based access control
3. Policy management
4. Advanced filtering and search
5. Export/import configurations

**Low Priority** (Nice to Have):
1. Virtual host support (if applicable)
2. Federation features
3. Advanced routing visualization
4. Custom dashboards
5. Alerting and notifications

---

## UI/UX Design

### Design System

#### Color Palette

**Light Mode:**
- Primary: Blue (#3B82F6)
- Success: Green (#10B981)
- Warning: Yellow (#F59E0B)
- Error: Red (#EF4444)
- Background: White (#FFFFFF)
- Surface: Gray-50 (#F9FAFB)
- Text: Gray-900 (#111827)

**Dark Mode:**
- Primary: Blue (#60A5FA)
- Success: Green (#34D399)
- Warning: Yellow (#FBBF24)
- Error: Red (#F87171)
- Background: Gray-900 (#111827)
- Surface: Gray-800 (#1F2937)
- Text: Gray-50 (#F9FAFB)

#### Typography

- **Font Family**: Inter (system fallback: -apple-system, BlinkMacSystemFont, "Segoe UI")
- **Headings**:
  - H1: 2.25rem (36px), font-weight: 700
  - H2: 1.875rem (30px), font-weight: 600
  - H3: 1.5rem (24px), font-weight: 600
- **Body**: 1rem (16px), font-weight: 400
- **Small**: 0.875rem (14px)

#### Spacing

- Base unit: 4px
- Common spacing: 8px, 12px, 16px, 24px, 32px, 48px
- Container max-width: 1280px

#### Components

**Buttons:**
- Primary: Blue background, white text
- Secondary: Gray background, dark text
- Danger: Red background, white text
- Ghost: Transparent background, colored text
- Sizes: sm (32px), md (40px), lg (48px)

**Cards:**
- Border radius: 8px
- Shadow: 0 1px 3px rgba(0,0,0,0.1)
- Padding: 16px (sm), 24px (md), 32px (lg)

**Tables:**
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
│ │     │  │  ┌─────────────────────────────────┐   │   │
│ │     │  │  │ Time | Event | Details          │   │   │
│ │     │  │  └─────────────────────────────────┘   │   │
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

- **Mobile**: < 640px (sm)
- **Tablet**: 640px - 1024px (md, lg)
- **Desktop**: > 1024px (xl, 2xl)

**Mobile Adaptations:**
- Collapsible sidebar (hamburger menu)
- Stacked statistics cards (1 column)
- Horizontal scroll for tables
- Simplified charts
- Bottom navigation (optional)

### Accessibility

**WCAG 2.1 Level AA Compliance:**
- ✅ Color contrast ratios (4.5:1 for text)
- ✅ Keyboard navigation support
- ✅ ARIA labels and roles
- ✅ Focus indicators
- ✅ Screen reader support
- ⚠️ Skip navigation links (TODO)
- ⚠️ Form validation announcements (TODO)

**Keyboard Shortcuts:**
- `Ctrl/Cmd + K`: Global search (future)
- `Esc`: Close modals/dialogs
- `Tab`: Navigate between elements
- `Enter`: Activate buttons/links
- `Arrow keys`: Navigate tables

---

## Security & Performance

### Security Considerations

#### Authentication & Authorization
**Current State**: No authentication (development only)

**Production Requirements**:
1. **Authentication**:
   - JWT-based authentication
   - Session management
   - Secure token storage (httpOnly cookies)
   - Token refresh mechanism

2. **Authorization**:
   - Role-based access control (RBAC)
   - Permission levels: Admin, Operator, Viewer
   - Resource-level permissions

3. **API Security**:
   - CORS configuration (production: same-origin only)
   - CSRF protection
   - Rate limiting
   - Input validation and sanitization

#### Data Security
- **Sensitive Data**: Mask credentials in UI
- **Audit Logging**: Track all management operations
- **Secure Communication**: HTTPS in production
- **Content Security Policy**: Prevent XSS attacks

### Performance Optimization

#### Frontend Performance

**Current Optimizations:**
1. **Code Splitting**: Route-based lazy loading
2. **Memoization**: React.memo for expensive components
3. **Debouncing**: Search and filter inputs (300ms)
4. **Pagination**: Limit data fetching (20 items/page)
5. **Caching**: RTK Query automatic caching

**Planned Optimizations:**
1. **Virtual Scrolling**: For large tables (1000+ rows)
2. **Image Optimization**: Lazy loading, WebP format
3. **Bundle Size**: Tree shaking, code splitting
4. **Service Worker**: Offline support, caching
5. **Web Workers**: Heavy computations off main thread

#### Backend Performance

**API Response Times** (Target):
- Health check: < 50ms
- List endpoints: < 200ms
- Detail endpoints: < 100ms
- Mutations: < 500ms

**Optimization Strategies:**
1. **Caching**: Redis for frequently accessed data
2. **Pagination**: Server-side pagination for large datasets
3. **Compression**: Gzip/Brotli for responses
4. **Connection Pooling**: Efficient database connections
5. **Async Processing**: Non-blocking I/O

#### Monitoring & Metrics

**Frontend Metrics:**
- Page load time (target: < 2s)
- Time to interactive (target: < 3s)
- First contentful paint (target: < 1s)
- API call latency
- Error rates

**Backend Metrics:**
- Request throughput (requests/sec)
- Response times (p50, p95, p99)
- Error rates (4xx, 5xx)
- Active connections
- Resource utilization (CPU, memory)

---

## Implementation Status

### Phase 1: Core Foundation ✅ COMPLETE

**Completed:**
- ✅ Project setup with Vite + React + TypeScript
- ✅ Tailwind CSS configuration
- ✅ React Router setup
- ✅ RTK Query integration
- ✅ Basic layout and navigation
- ✅ All main pages created
- ✅ Integration with backend API
- ✅ E2E test suite (178 tests passing)

### Phase 2: Real Data Integration 🚧 IN PROGRESS

**Status:**
- ✅ Backend health check integration
- ✅ Queue list API integration
- ✅ Consumer group API integration
- ⚠️ Event store API (partial - needs backend endpoints)
- ⚠️ Message browser API (partial - needs backend endpoints)
- ⚠️ System overview API (partial - needs real metrics)

**Blockers:**
- Missing backend endpoints for some features
- Need real-time data updates (WebSocket)
- Need complete CRUD operations

### Phase 3: Advanced Features ❌ NOT STARTED

**Planned:**
- Real-time updates via WebSocket
- Advanced filtering and search
- Message publish functionality
- Queue pause/resume operations
- Performance charts with real data
- Export/import configurations
- User management and authentication

---

## Development Workflow

### Local Development

1. **Start Backend**:
   ```bash
   cd peegeeq-rest
   mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"
   ```

2. **Start Frontend**:
   ```bash
   cd peegeeq-management-ui
   npm install
   npm run dev
   ```

3. **Access UI**: http://localhost:3000

### Testing

```bash
# Unit tests
npm run test

# Integration tests
npm run test:integration

# E2E tests (requires backend)
npm run test:e2e

# All tests
npm run test:all
```

### Building for Production

```bash
npm run build
```

Output: `../peegeeq-rest/src/main/resources/webroot`

### Code Quality

```bash
# Linting
npm run lint

# Type checking
npm run type-check

# Format code
npm run format
```

---

## Future Enhancements

### Short-term (Next 3 months)

1. **Complete Backend Integration**
   - Implement missing REST endpoints
   - Add WebSocket support for real-time updates
   - Complete CRUD operations for all entities

2. **Enhanced Monitoring**
   - Real-time charts and graphs
   - Historical data visualization
   - Performance metrics dashboard

3. **Improved UX**
   - Advanced filtering and search
   - Bulk operations
   - Keyboard shortcuts
   - Dark mode toggle

### Medium-term (3-6 months)

1. **Authentication & Authorization**
   - User management
   - Role-based access control
   - Audit logging

2. **Advanced Features**
   - Message publish/replay
   - Queue templates
   - Configuration import/export
   - Alerting and notifications

3. **Performance**
   - Virtual scrolling for large datasets
   - Optimistic UI updates
   - Service worker for offline support

### Long-term (6-12 months)

1. **Clustering Support**
   - Multi-node management
   - Cluster health monitoring
   - Node failover visualization

2. **Advanced Analytics**
   - Custom dashboards
   - Trend analysis
   - Capacity planning tools

3. **Integration**
   - Prometheus/Grafana integration
   - External monitoring tools
   - CI/CD pipeline integration

---

## References

### Documentation
- [Quick Start Guide](QUICK_START.md)
- [Testing Guide](TESTING_GUIDE.md)
- [API Reference](API_REFERENCE.md)
- [Production Readiness](PRODUCTION_READINESS.md)

### External Resources
- [React Documentation](https://react.dev/)
- [TypeScript Handbook](https://www.typescriptlang.org/docs/)
- [RTK Query](https://redux-toolkit.js.org/rtk-query/overview)
- [Tailwind CSS](https://tailwindcss.com/)
- [Vite Guide](https://vitejs.dev/guide/)

### Inspiration
- [RabbitMQ Management Console](https://www.rabbitmq.com/management.html)
- [Apache Kafka UI](https://github.com/provectus/kafka-ui)
- [Redis Commander](https://github.com/joeferner/redis-commander)

---

**Document Version**: 1.0
**Last Updated**: 2025-12-23
**Maintained By**: PeeGeeQ Development Team


