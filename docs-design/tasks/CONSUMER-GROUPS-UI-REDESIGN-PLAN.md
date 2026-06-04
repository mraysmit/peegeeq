# Consumer Groups UI Redesign — Change Plan

## Status: DRAFT — awaiting review

---

## Problem Statement

The Consumer Groups management page (`ConsumerGroups.tsx`) is misaligned with the PeeGeeQ backend in several ways. Some are clear mistakes; others are premature (concepts exist in the Java API but are not yet implemented or exposed via the management REST endpoint). The distinction matters for prioritisation.

**Clear mistakes (wrong concept or fake data):**
- `totalPartitions` / `assignedPartitions` — no database-level partition concept; these are displayed using `Math.random()` values
- `messagesPerSecond` / `totalProcessed` — filled with `Math.random()`; the management API returns none of this
- `status: 'active'|'inactive'|'rebalancing'|'error'` — `inactive` and `rebalancing` do not exist; actual states are `active|paused|dead|cancelled`
- `queueName` derived by string-stripping the group name — the API returns `queueName` directly
- `lastRebalance` column — no rebalancing concept in PeeGeeQ; groups do not rebalance
- Delete action never calls the API — only filters local state

**Premature (real Java API concepts, not yet implemented/exposed):**
- `loadBalancingStrategy` — `LoadBalancingStrategy` enum is real (`ROUND_ROBIN|RANGE|STICKY|RANDOM`) and is on the `ConsumerGroup` interface, but is marked `TODO` in the implementation. Both `getLoadBalancingStrategy()` and `getMaxMembers()` and `getSessionTimeout()` currently return hardcoded defaults. They are NOT returned by the management API.
- `maxMembers` — real concept on `ConsumerGroup` interface, currently always `10` (default, TODO)
- `sessionTimeout` — real concept on `ConsumerGroup` interface, currently always `30000ms` (default, TODO)

**Missing (returned by the API, never shown):**
- `implementationType` (`NATIVE_QUEUE` / `OUTBOX`)
- `subscribedAt`, `lastActiveAt`, `lastHeartbeatAt`
- `backfillStatus` and backfill progress

Additionally the backend management API has two broken operations:
- **POST** calls `queueFactory.createConsumerGroup(...)` and discards the returned object without calling `start()` or writing any subscription row. The group never appears in a subsequent GET because GET reads from `listSubscriptions()` and no row was written. The created `ConsumerGroup` instance is immediately garbage collected.
- **DELETE** only verifies the setup exists and returns 200. It never calls `subscriptionService.cancel()`. The subscription row remains in the database unchanged.

---

## How Consumer Groups Actually Work (from peegeeq-outbox tests)

Reading the outbox tests confirms the real model:

### In-process consumer group (what application code uses)

```java
// Create a group attached to a queue ("topic")
ConsumerGroup<String> group = factory.createConsumerGroup("order-processors", "orders", String.class);

// Add one or more competing consumers (QUEUE delivery: each message goes to exactly one consumer)
group.addConsumer("consumer-1", msg -> Future.succeededFuture());
group.addConsumer("consumer-2", msg -> Future.succeededFuture());

// Start with position options: FROM_NOW, FROM_BEGINNING, FROM_TIMESTAMP
group.start(SubscriptionOptions.builder().startPosition(StartPosition.FROM_NOW).build());
```

- `createConsumerGroup(groupName, queueName, type)` — group name + queue name are the two key fields
- `addConsumer(consumerId, handler)` — in-process consumers competing for messages
- State machine: NEW → ACTIVE → (stop → NEW again, restartable) → CLOSED
- `ConsumerGroupStats` exists (tracks processed/failed/filtered counts, msg/s) but is **in-process only** — not exposed by the REST API
- `ConsumerGroupMember` is a real Java interface (consumerId, groupName, topic, joinedAt, handler) but is **in-process only** — not exposed by the REST API

### What the REST API can know

The REST API can only see what is persisted in the database: the subscription record. It cannot see:
- How many in-process consumers a running application has added to the group
- What the in-process stats are (processed/failed/filtered counts)
- Member-level detail (consumerId, joinedAt for in-process members)

This is why the backend correctly returns `members: 0` — it has no way to know. **The TypeScript `ConsumerGroupMember` interface representing Kafka-style partition-assigned members was wrong for two separate reasons:** (1) PeeGeeQ uses competing consumers within a group, not partition assignment, and (2) even that in-process concept cannot be observed from the REST API.

### Key conclusion for UI design

The management UI shows **database-level subscription state**, not in-process runtime state. The correct information to display per consumer group is exactly what the REST API returns: subscription status, heartbeat health, backfill progress, and the queue/setup it belongs to.

The premature fields (`loadBalancingStrategy`, `maxMembers`, `sessionTimeout`) should be removed from the UI for now. They should be re-introduced once:
1. The `ConsumerGroup` implementations actually honour the configured values (the TODO is resolved), and
2. The management API is extended to return them (currently it does not).

---

## Two Separate REST APIs (important architectural context)

Reading `ConsumerGroupHandler.java` reveals there are **two completely separate REST handler classes** for consumer groups:

### 1. Queue-scoped API — `ConsumerGroupHandler` (incomplete)
Routes: `GET/POST /api/v1/queues/{setupId}/{queueName}/consumer-groups`, `POST .../members`, `DELETE .../members/{memberId}`, etc.

This handler maintains an **in-memory `ConcurrentHashMap`** of live `ConsumerGroup<Object>` instances. When a member joins via `POST .../members`, the handler registers a **placeholder handler that silently discards every message**:

```java
MessageHandler<Object> placeholderHandler = message -> {
    logger.debug("REST consumer {} received message: {}", consumerId, message.getId());
    return Future.succeededFuture();
};
```

There is no SSE, no WebSocket, no callback mechanism to forward messages back to a REST caller. This path is incomplete — the design intent was presumably a push mechanism that was never built.

This handler is therefore not useful to the management UI and not relevant to this plan.

### 2. Management API — `ManagementApiHandler` (subscription state)
Routes: `GET /api/v1/management/consumer-groups`, `POST /api/v1/management/consumer-groups`

**GET** is correct: queries `subscriptionService.listSubscriptions(queueName)` for each queue in each active setup and returns the subscription rows. This is the right data source.

**POST** is broken: calls `queueFactory.createConsumerGroup(groupName, queueName, Object.class)` and discards the returned `ConsumerGroup` object. Never calls `subscriptionService.subscribe()`. No subscription row is written, so the group never appears in a subsequent GET.

**DELETE** is broken: parses path params, verifies setup exists, returns 200. Never calls `subscriptionService.cancel()`. Subscription row remains in the database.

### Which API should the management UI use?

The management UI must use the management API. It is the only API that queries the database and sees all groups regardless of how they were created. The queue-scoped API is incomplete and not fit for purpose.

---

## Source of Truth

### GET /api/v1/management/consumer-groups — actual response shape

Each element in `consumerGroups[]`:

| Field            | Type    | Notes                                                   |
|------------------|---------|---------------------------------------------------------|
| `name`           | string  | Consumer group name                                     |
| `setup`          | string  | Setup ID                                                |
| `queueName`      | string  | Queue this group subscribes to — returned directly      |
| `implementationType` | string | `"NATIVE_QUEUE"` or `"OUTBOX"`                     |
| `status`         | string  | `"active"` \| `"paused"` \| `"dead"` \| `"cancelled"`  |
| `subscribedAt`   | string  | ISO instant; when the subscription was created          |
| `lastActiveAt`   | string  | ISO instant; when the group last processed a message    |
| `lastHeartbeatAt`| string \| null | ISO instant; null if no heartbeat yet          |
| `backfillStatus` | string  | `"NONE"` \| `"IN_PROGRESS"` \| `"COMPLETED"` \| `"FAILED"` |
| `members`        | number  | Always `0` — not yet populated in backend (acceptable)  |
| `lag`            | number  | Always `0` — not yet populated in backend (acceptable)  |
| `createdAt`      | string  | Setup creation time used as proxy                       |

Fields NOT returned by management API (must not be shown using fake data): `maxMembers`, `loadBalancingStrategy`, `sessionTimeout`, `totalPartitions`, `assignedPartitions`, `messagesPerSecond`, `totalProcessed`, `lastRebalance`, member details.

### POST /api/v1/management/consumer-groups — accepted body

```json
{ "name": "...", "setup": "...", "queueName": "..." }
```

Only these three fields. The backend currently validates these three and rejects nulls (400). However the handler body is broken — it must be changed to call `subscriptionService.subscribe()` instead of `queueFactory.createConsumerGroup()` (see Phase 1).

The frontend `handleCreateModalOk` already sends the correct body shape.

### DELETE /api/v1/management/consumer-groups/:groupId — current (broken)

Two problems:
1. Uses `{setupId}-{groupName}` as a single path param split on `-`. Both `setupId` and `groupName` can contain hyphens — the split is ambiguous.
2. The handler body never calls `subscriptionService.cancel()`. It only verifies the setup exists and returns 200. The subscription row is never removed.

**Fix:** Change route to use separate path params AND call `subscriptionService.cancel(queueName, groupName)`.

### SubscriptionState enum (Java source of truth)
`ACTIVE`, `PAUSED`, `DEAD`, `CANCELLED`

Mapped by `mapSubscriptionState()` in `ManagementApiHandler` to lowercase: `active`, `paused`, `dead`, `cancelled`.

---

## Changes Required

### Phase 1 — Backend: Fix POST and DELETE

> **The full specification for Phase 1 — including pre-work checklist, implementation steps, test discipline, and verification — is in `docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20.** Do not duplicate that content here.

Phase 1 backend changes (summary only):

| Sub-task | File | Change |
|---|---|---|
| 1a | `PeeGeeQRestServer.java` | Change DELETE route from `/:groupId` to `/:setupId/:queueName/:groupName` |
| 1b | `ManagementApiHandler.java` | Fix `deleteConsumerGroup()`: read three path params, call `subscriptionService.cancel(queueName, groupName)` |
| 1c | `ManagementApiHandler.java` | Fix `createConsumerGroup()`: keep `queueFactory` lookup for 404 validation, replace `queueFactory.createConsumerGroup(...)` with `subscriptionService.subscribe(queueName, groupName)` |

Phase 1 must be complete and all tests passing before starting Phase 2.

---

### Phase 2 — Frontend: Rewrite ConsumerGroups.tsx

**File:** `peegeeq-management-ui/src/pages/ConsumerGroups.tsx`

#### 2a. Interface: Remove TypeScript ConsumerGroupMember entirely

The TypeScript `ConsumerGroupMember` interface has fields like `assignedPartitions: number[]`, `processedMessages`, `errorCount` — these are not the same as the Java `ConsumerGroupMember` interface. The Java interface has `consumerId`, `groupName`, `topic`, `joinedAt`, `handler` — but that runtime concept is not visible via the management API. Remove the TypeScript interface and all references.

#### 2b. Interface: Fix ConsumerGroup

Remove these fields (reasons in parentheses):
```
maxMembers           (premature — real Java API, unimplemented, not returned by management API)
loadBalancingStrategy (premature — real Java API, unimplemented, not returned by management API)
sessionTimeout        (premature — real Java API, unimplemented, not returned by management API)
totalPartitions       (wrong — misleading display of Math.random() values)
assignedPartitions    (wrong — misleading display of Math.random() values)
messagesPerSecond     (wrong — Math.random() fake data)
totalProcessed        (wrong — Math.random() fake data)
lastRebalance         (wrong — no rebalancing event concept in PeeGeeQ)
members: ConsumerGroupMember[] (wrong — runtime concept, not visible via management API)
```

Add these fields (all returned by the management API):
```
implementationType: string
subscribedAt: string
lastActiveAt: string
lastHeartbeatAt: string | null
backfillStatus: string
lag: number
```

#### 2c. Data mapping in fetchConsumerGroups

| Current (wrong)                                                          | Fix                                   |
|--------------------------------------------------------------------------|---------------------------------------|
| `queueName: group.name.replace('-processors', '').replace(...)`          | `queueName: group.queueName`          |
| `maxMembers: group.members + Math.floor(Math.random() * 3) + 1`          | Remove                                |
| `loadBalancingStrategy: 'ROUND_ROBIN'`                                   | Remove                                |
| `sessionTimeout: 30000`                                                  | Remove                                |
| `messagesPerSecond: Math.floor(Math.random() * 100) + 10`                | Remove                                |
| `lastRebalance: group.lastRebalance || new Date().toISOString()`         | Remove                                |
| `members: []`                                                            | Remove                                |
| Not mapped: implementationType, subscribedAt, lastActiveAt, lastHeartbeatAt, backfillStatus | Add all |

#### 2d. Status helpers

`getStatusColor` and `getStatusIcon` currently map `active|inactive|rebalancing|error`.  
Replace with `active|paused|dead|cancelled`:

| Status      | Color    | Icon                        |
|-------------|----------|-----------------------------|
| `active`    | `green`  | `CheckCircleOutlined`       |
| `paused`    | `orange` | `PauseCircleOutlined`       |
| `dead`      | `red`    | `ExclamationCircleOutlined` |
| `cancelled` | `default`| `StopOutlined`              |

Remove `getMemberStatusColor` — no member status concept in PeeGeeQ.

#### 2e. Table columns

| Column       | Action  | Detail                                                                                    |
|--------------|---------|-------------------------------------------------------------------------------------------|
| Group Name   | Fix     | Add `implementationType` tag (colour: `geekblue` for NATIVE_QUEUE, `gold` for OUTBOX)    |
| Members      | Fix     | Remove `maxMembers` (premature, unimplemented) and progress bar; show only `memberCount` as a plain number |
| Partitions   | Remove  | Misleading numeric column backed by `Math.random()` — no persistent partition concept     |
| Performance  | Remove  | `Math.random()` fake data — delete column entirely                                        |
| Status       | Fix     | Replace `lastRebalance` tooltip with `lastHeartbeatAt` (relative time via `dayjs.fromNow()`) |
| Heartbeat    | Add     | Show `lastHeartbeatAt` as relative time; if null show "Never" in secondary text           |
| Subscribed   | Add     | Show `subscribedAt` formatted as `MMM DD, YYYY`                                           |
| Backfill     | Add     | Show `backfillStatus` as a tag (NONE → grey, IN_PROGRESS → blue, COMPLETED → green, FAILED → red) |

#### 2f. Summary stat cards

| Card position | Current               | Replacement                                            |
|---------------|-----------------------|--------------------------------------------------------|
| Card 3        | "Total Members"       | **Dead Groups** — `filteredGroups.filter(g => g.status === 'dead').length` |
| Card 4        | "Avg Throughput msg/s"| **Backfill Active** — `filteredGroups.filter(g => g.backfillStatus === 'IN_PROGRESS').length` |

Cards 1 ("Total Groups") and 2 ("Active Groups") are correct and unchanged.

#### 2g. Action menu

Remove "Trigger Rebalance" — PeeGeeQ does not have partition rebalancing.

Wire Delete to call the updated backend endpoint:
```
DELETE /api/v1/management/consumer-groups/{setupId}/{queueName}/{groupName}
```
On success: close the confirm modal, call `fetchConsumerGroups()`.  
On failure: show `message.error(...)`.

#### 2h. Create modal form fields

| Field                | Action  | Detail                                                                                   |
|----------------------|---------|------------------------------------------------------------------------------------------|
| Group Name           | Keep    | No change                                                                                |
| Setup                | Keep    | No change (already pre-fills from `selectedSetupId`)                                     |
| Queue Name           | Change  | Replace free-text `Input` with a `Select` populated from `GET /api/v1/setups/{setupId}` → `queueFactories` keys. Load queue options when setup field changes via `onSelect` or Form `onValuesChange`. Pre-fill if `selectedQueueName` is set. |
| Max Members          | Remove  | Real Java API concept but unimplemented (always 10); not accepted by management POST endpoint |
| Load Balancing Strategy | Remove | Real Java API concept but unimplemented (always ROUND_ROBIN); not accepted by management POST endpoint |
| Session Timeout      | Remove  | Real Java API concept but unimplemented (always 30000ms); not accepted by management POST endpoint |

Add state: `const [availableQueues, setAvailableQueues] = useState<string[]>([])`  
Add helper:
```typescript
const fetchQueuesForSetup = async (setupId: string) => {
    try {
        const response = await axios.get(getVersionedApiUrl(`setups/${setupId}`))
        const keys = Object.keys(response.data?.queueFactories || {})
        setAvailableQueues(keys)
    } catch {
        setAvailableQueues([])
    }
}
```
Call `fetchQueuesForSetup` when setup changes in the form (use `Form.Item` `onSelect` or `onValuesChange`).

#### 2i. Details modal

Replace the Kafka-derived content with real subscription data.

**"Group Information" Descriptions — remove:**
- Load Balancing Strategy (premature — unimplemented, not returned by management API)
- Session Timeout (premature — unimplemented, not returned by management API)
- Last Rebalance (wrong — no rebalancing event concept in PeeGeeQ)

**"Group Information" Descriptions — add:**
- Implementation Type (tag)
- Subscribed At
- Last Heartbeat
- Backfill Status

**"Performance Metrics" mini-cards — replace with "Subscription Details":**
- Subscribed At
- Last Active At
- Last Heartbeat At (with `HeartOutlined` icon; coloured green if within timeout, red if overdue)
- Consumer Lag

**"Members Table" section — replace with "Backfill Details":**
Show as Descriptions with fields: `backfillStatus`, `backfillProcessedMessages`, `backfillTotalMessages`, `backfillStartedAt`, `backfillCompletedAt`. Show a progress bar `(processed / total * 100)` when status is IN_PROGRESS.

**"Partition Assignment" visualization — remove entirely.**

---

## Files Changed

> Phase 1 backend files are fully specified in `docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20. The table below is a summary for reference.

| File | Phase | Scope |
|------|-------|-------|
| `peegeeq-rest/.../PeeGeeQRestServer.java` | 1a | DELETE route pattern change only — see Section 20 |
| `peegeeq-rest/.../ManagementApiHandler.java` | 1b + 1c | `deleteConsumerGroup` and `createConsumerGroup` fixes — see Section 20 |
| `peegeeq-management-ui/src/pages/ConsumerGroups.tsx` | 2 | Full UI rewrite as described in Phase 2 above |

## Files NOT Changed

| File | Reason |
|------|--------|
| `SubscriptionState.java` | Source of truth — correct |
| `SubscriptionInfo.java` | Source of truth — correct |
| `ManagementApiHandler.java` (GET) | Already correct — reads `listSubscriptions()` |
| All test files | Update only after changes are confirmed working |
| `consumer-groups-scope-selectors.spec.ts` | Will need review after UI changes; update separately |

---

## Verification Steps

1. **After Phase 1 (backend only):** See verification steps in `docs-design/consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md` Section 20. The canonical command is `mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-YYYYMMDD.txt`.

2. **After Phase 2 (frontend):** Run Playwright suite:
   ```powershell
   cd peegeeq-management-ui; npx playwright test --project=13-consumer-groups-scope-selectors --headed --reporter=list 2>&1 | Tee-Object -FilePath ..\logs\consumer-groups-YYYYMMDD.txt
   ```

3. **Manual browser checks after Phase 2:**
   - Consumer Groups table shows status tags: active / paused / dead / cancelled
   - No Partitions column visible
   - No msg/s Performance column visible
   - Heartbeat, Subscribed, Backfill columns visible
   - Create modal shows 3 fields only: Group Name, Setup, Queue (Select)
   - Queue Select populates after a Setup is chosen
   - Delete action calls `DELETE /api/v1/management/consumer-groups/{setupId}/{queueName}/{groupName}` (visible in browser Network tab)
   - Details modal shows Subscription Details and Backfill Details, no Partition Assignment section

4. **Full regression after everything:** See Section 20 of the fanout design doc for the complete post-phase verification sequence.
