# Design Guide

## Event Visualization Design Proposals

### Goal
To provide intuitive visualizations for complex event sourcing relationships within the PeeGeeQ Management UI. The current flat table view is excellent for raw data inspection, but we need advanced views to understand:
1.  **Causality**: How one event triggered another (The "Story").
2.  **Entity Lifecycle**: The history of a specific domain object (The "State").

---

### Proposal A: Causation Tree View (Trace View)
This view focuses on the workflow execution. It uses the `CausationID` to link events together hierarchically and `CorrelationID` to group the entire transaction.

**Use Case:** Debugging a failed workflow step or understanding the ripple effect of a command.

**ASCII Mockup:**

```text
[ Filter: CorrelationID = "corr-workflow-567" ]

▼ order.placed (EventID: 101) ---------------------------------- [10:00:00]
  │
  ├──▶ payment.processed (EventID: 102) ------------------------ [10:00:05]
  │    │  (CausationID: 101)
  │    │
  │    └──▶ shipping-label.created (EventID: 105) --------------- [10:00:10]
  │         │  (CausationID: 102)
  │         │
  │         └──▶ order.shipped (EventID: 108) ------------------ [12:30:00]
  │              (CausationID: 105)
  │
  └──▶ inventory.reserved (EventID: 103) ----------------------- [10:00:02]
       (CausationID: 101)

[ Legend: ▼ Expanded Node  | Causation Link  ▶ Event Node ]
```

**Key Features:**
- **Root Detection**: Events with no `CausationID` (or where CausationID is external) appear at the root.
- **Indentation**: Visually represents the depth of the call stack/event chain.
- **Connectors**: Lines connect causes to effects.

---

### Proposal B: Aggregate-Centric View (Stream View)
This view focuses on the lifecycle of entities. It groups events by `AggregateID` (and `AggregateType`).

**Use Case:** Reconstructing the state of an entity (e.g., "Why is the Order status 'Cancelled'?") or auditing changes to a specific record.

**ASCII Mockup:**

```text
[ Search: Aggregate Type or ID... ]

▼ 📦 Order: ORDER-123 (4 Events)
  │
  ├── #1  order.placed          | 10:00:00 | User: Alice
  ├── #2  inventory.reserved    | 10:00:02 | System
  ├── #3  payment.processed     | 10:00:05 | PaymentGateway
  └── #4  order.shipped         | 12:30:00 | Warehouse

▶ 👤 Customer: CUST-999 (1 Event)
  (Click to expand stream)

▼ 🏷️ Product: PROD-555 (2 Events)
  │
  ├── #1  product.created       | 09:00:00 | Admin
  └── #2  price.updated         | 09:30:00 | Admin
```

**Key Features:**
- **Grouping**: Rows are Aggregates, not individual events.
- **Expansion**: Clicking an Aggregate expands to show its event stream (Version 1..N).
- **Version Ordering**: Events are strictly ordered by version/sequence number.

---

### Proposal C: Interactive Graph (Network View)
A visual node-link diagram for complex, non-linear relationships or high-volume overviews.

**ASCII Mockup:**

```text
      (inventory.reserved)
          ^
          |
(order.placed) +-----> (payment.processed) +-----> (shipping-label.created)
          |                                           |
          +-----> (email.sent)                        v
                                                (order.shipped)
```

**Key Features:**
- **Force-Directed Layout**: Nodes position themselves based on connections.
- **Cluster Detection**: Visually identify isolated groups of events.

---

### Implementation Strategy

#### Phase 1: Aggregate Grouping (Low Effort, High Value)
Implement **Proposal B** using Ant Design's `Table` component with the `expandable` prop.
- The main table lists unique Aggregates (derived from the event list).
- The expanded row renders a nested table of events for that aggregate.
- **API Limitation**: The current API does not support listing unique aggregates or filtering by `AggregateType`. We will derive the aggregate list from the loaded events on the client side for now.

#### Phase 2: Causation Tree (Medium Effort)
Implement **Proposal A** using a Tree component or a recursive list.
- Requires preprocessing the flat event list to build a tree structure based on `id` -> `causationId` maps.
- **Data Fetching**: We will fetch all events for a specific `CorrelationID` to construct the complete tree. The API supports filtering by `CorrelationID`.

#### Phase 3: Visual Graph (High Effort)
Use a library like `React Flow` or `vis.js` to render the node-link diagram from **Proposal C**.

## Runtime Validation Design

### Problem Statement

The application was crashing when the backend returned incomplete or malformed data because:

1. **No runtime type validation** - TypeScript types are compile-time only and don't validate actual API responses
2. **No defensive programming** - Code assumed backend always returns perfect data
3. **No error boundaries** - No graceful error handling when components crash
4. **Math operations with undefined** - `sum + undefined = NaN`, causing rendering failures

### Solution: Three-Layer Defense

#### Layer 1: Runtime Schema Validation with Zod

**File**: `src/types/queue.validation.ts`

- Defines Zod schemas that mirror TypeScript types
- Validates API responses at runtime
- Provides default values for missing fields
- Logs validation errors for debugging

**Key Features**:
```typescript
// Schema with defaults
export const QueueSchema = z.object({
  setupId: z.string(),
  queueName: z.string(),
  messageCount: z.number().default(0),  // ✅ Default to 0 if missing
  consumerCount: z.number().default(0),
  messagesPerSecond: z.number().default(0),
  // ... other fields
});

// Safe validation function
export function validateQueueListResponse(data: unknown) {
  try {
    return QueueListResponseSchema.parse(data);
  } catch (error) {
    console.error('Validation failed:', error);
    // Return safe defaults instead of crashing
    return { queues: [], total: 0, page: 1, pageSize: 10 };
  }
}
```

#### Layer 2: API Response Transformation

**File**: `src/store/api/queuesApi.ts`

- Uses `transformResponse` in RTK Query to validate all API responses
- Ensures data is validated before it reaches components
- Provides consistent data structure across the application

**Implementation**:
```typescript
getQueues: builder.query<QueueListResponse, QueueFilters | void>({
  query: (filters) => { /* ... */ },
  transformResponse: (response: unknown) => {
    // Validate and sanitize the response data
    return validateQueueListResponse(response);
  },
  // ... rest of config
}),
```

#### Layer 3: Defensive Programming in Components

**File**: `src/pages/QueuesEnhanced.tsx`

- Uses nullish coalescing operator (`??`) to handle undefined values
- Prevents `NaN` from propagating through calculations
- Ensures UI always has valid numbers to display

**Before** (crashes on undefined):
```typescript
const totalMessages = queues.reduce((sum, q) => sum + q.messageCount, 0);
// If q.messageCount is undefined: sum + undefined = NaN ❌
```

**After** (safe with defaults):
```typescript
const totalMessages = queues.reduce((sum, q) => sum + (q.messageCount ?? 0), 0);
// If q.messageCount is undefined: sum + 0 = sum ✅
```

#### Layer 4: Error Boundary

**File**: `src/components/common/ErrorBoundary.tsx`

- Catches any JavaScript errors in component tree
- Displays user-friendly error message instead of blank screen
- Provides "Try Again" and "Reload Page" options
- Shows error details in development for debugging

**Usage** in `App.tsx`:
```typescript
<Content>
  <ErrorBoundary>
    <Routes>
      {/* All routes wrapped in error boundary */}
    </Routes>
  </ErrorBoundary>
</Content>
```

### Benefits

1. **Graceful Degradation**: Missing data shows as 0 instead of crashing
2. **Better Debugging**: Validation errors logged to console with details
3. **Type Safety at Runtime**: Zod validates actual data, not just compile-time types
4. **User Experience**: Error boundary shows helpful message instead of blank screen
5. **Maintainability**: Centralized validation logic, easy to update schemas
