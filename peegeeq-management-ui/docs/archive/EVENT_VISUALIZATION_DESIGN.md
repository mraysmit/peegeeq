# Event Visualization Design Proposals

## Goal
To provide intuitive visualizations for complex event sourcing relationships within the PeeGeeQ Management UI. The current flat table view is excellent for raw data inspection, but we need advanced views to understand:
1.  **Causality**: How one event triggered another (The "Story").
2.  **Entity Lifecycle**: The history of a specific domain object (The "State").

---

## Proposal A: Causation Tree View (Trace View)
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

## Proposal B: Aggregate-Centric View (Stream View)
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

## Proposal C: Interactive Graph (Network View)
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

## Implementation Strategy

### Phase 1: Aggregate Grouping (Low Effort, High Value)
Implement **Proposal B** using Ant Design's `Table` component with the `expandable` prop.
- The main table lists unique Aggregates (derived from the event list).
- The expanded row renders a nested table of events for that aggregate.
- **API Limitation**: The current API does not support listing unique aggregates or filtering by `AggregateType`. We will derive the aggregate list from the loaded events on the client side for now.

### Phase 2: Causation Tree (Medium Effort)
Implement **Proposal A** using a Tree component or a recursive list.
- Requires preprocessing the flat event list to build a tree structure based on `id` -> `causationId` maps.
- **Data Fetching**: We will fetch all events for a specific `CorrelationID` to construct the complete tree. The API supports filtering by `CorrelationID`.

### Phase 3: Visual Graph (High Effort)
Use a library like `React Flow` or `vis.js` to render the node-link diagram from **Proposal C**.

## API Gap Analysis
A review of the `peegeeq-rest` and `peegeeq-api` modules revealed the following:

1.  **Supported Filters**:
    -   `CorrelationID`: Supported. Essential for fetching the full scope of a workflow for the Causation Tree.
    -   `AggregateID`: Supported. Essential for the Aggregate View.

2.  **Missing Capabilities**:
    -   **CausationID Filter**: The `EventQuery` class does not support filtering by `CausationID`. We cannot directly query "children of event X".
    -   **AggregateType Filter**: The API does not support filtering by `AggregateType`.
    -   **Aggregate List Endpoint**: There is no endpoint to list unique aggregates.

## Recommendations
To fully support the proposed designs efficiently, we recommend extending the API in the future:
1.  Add `causationId` filter to `EventQuery` and REST API.
2.  Add `aggregateType` filter to `EventQuery` and REST API.
3.  Create an endpoint to list unique Aggregates (e.g., `GET /event-stores/{name}/aggregates`).

## Interaction Design
- **Drill-down**: Clicking an event in any view should open a "Details Drawer" on the right side, showing the full JSON payload, headers, and metadata.
- **Cross-Linking**: Clicking a `CorrelationID` in the Details Drawer should switch the view to the **Causation Tree** filtered by that ID.

## Backend Design Requirements

To support the visualization features described above, the following changes are required in the backend layers (`peegeeq-api`, `peegeeq-bitemporal`, `peegeeq-rest`).

### 1. Database Schema (`peegeeq-db`)
*No changes required.* The `event_store_template` already includes `causation_id` and `aggregate_id`. We will rely on `event_type` for categorization instead of adding a new column.

### 2. API Layer (`peegeeq-api`)
The core interfaces and query objects need to be updated to expose the existing database capabilities.

-   **`EventStore` Interface**:
    -   Add `CompletableFuture<List<String>> getUniqueAggregates(String eventType)` to support the Aggregate List view.
-   **`EventQuery` Class**:
    -   Add `causationId` field and builder method (currently missing, though `correlationId` exists).

### 3. Implementation Layer (`peegeeq-bitemporal`)
The PostgreSQL implementation needs to be updated to handle the new query parameters.

-   **`PgBiTemporalEventStore` Class**:
    -   **Query**: Update `queryReactive` to add `AND causation_id = $Y` to the WHERE clause if the filter is present.
    -   **New Method**: Implement `getUniqueAggregates` using `SELECT DISTINCT aggregate_id FROM {table} WHERE event_type = $1` (or `SELECT DISTINCT aggregate_id FROM {table}` if eventType is null).

### 4. REST API (`peegeeq-rest`)
The REST layer needs to expose these new capabilities to the frontend.

-   **`EventStoreHandler`**:
    -   Update `queryEvents` to pass the `causationId` query parameter to the `EventQuery`.
    -   Add new endpoint `GET /:storeName/aggregates` to expose `getUniqueAggregates`.

