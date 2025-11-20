# PeeGeeQ Management UI - Complete Design Specification

**Date:** November 20, 2025  
**Version:** 1.0  
**Status:** Design Phase  
**Author:** PeeGeeQ Development Team

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [PeeGeeQ Feature Catalog](#peegeeq-feature-catalog)
   - [Native Queue Features & Configuration](#native-queue-features--configuration)
   - [Outbox Pattern Features & Configuration](#outbox-pattern-features--configuration)
   - [Bitemporal Event Store Features](#bitemporal-event-store-features)
   - [Consumer Group Features](#consumer-group-features)
3. [RabbitMQ-Inspired UI Patterns](#rabbitmq-inspired-ui-patterns)
   - [Overview Dashboard](#overview-dashboard)
   - [Queue List & Details Pages](#queue-list--details-pages)
   - [Consumer Group Management](#consumer-group-management)
   - [Event Store Browser](#event-store-browser)
   - [Message Browser & Publish Tool](#message-browser--publish-tool)
4. [Management API Specification](#management-api-specification)
   - [Existing APIs (Analysis)](#existing-apis-analysis)
   - [Required API Extensions](#required-api-extensions)
   - [WebSocket/SSE Real-time Updates](#websocketsse-real-time-updates)
5. [Implementation Roadmap](#implementation-roadmap)
   - [Phase 1: Queue Management Enhancement](#phase-1-queue-management-enhancement)
   - [Phase 2: Consumer Group Visualization](#phase-2-consumer-group-visualization)
   - [Phase 3: Event Store Explorer](#phase-3-event-store-explorer)
   - [Phase 4: Advanced Features](#phase-4-advanced-features)
6. [Technical Considerations](#technical-considerations)

---

## Executive Summary

### Purpose

This document provides a comprehensive design specification for the **PeeGeeQ Management UI**, a modern web-based interface for managing and monitoring PeeGeeQ's PostgreSQL-based message queue system. The design is inspired by RabbitMQ's proven management console patterns while incorporating PeeGeeQ's unique features and advantages.

### Scope

The management UI will provide:

- **Queue Management**: Create, configure, monitor, and manage all three queue types (Native, Outbox, Bitemporal)
- **Consumer Group Management**: Visualize and manage consumer groups with load balancing and partition assignment
- **Event Store Browser**: Query and explore bitemporal events with time-travel capabilities
- **Message Operations**: Publish, retrieve, and inspect messages for debugging and testing
- **System Monitoring**: Real-time metrics, health checks, and performance analytics
- **Administrative Tools**: Configuration management, policy enforcement, and operational controls

### Key Differentiators

PeeGeeQ offers unique advantages over traditional message brokers:

| Feature | Traditional Brokers | PeeGeeQ Advantage |
|---------|-------------------|-------------------|
| **Infrastructure** | Requires separate broker servers | Zero infrastructure - uses existing PostgreSQL |
| **Transactions** | Limited transactional support | Full ACID transactions with business data |
| **Temporal Queries** | Not available | Bitemporal event store with time-travel |
| **Integration** | Separate system to manage | Leverages existing PostgreSQL skills |
| **Performance** | High throughput | 10,000+ msg/sec (native), <10ms latency |
| **Event Sourcing** | Requires additional tools | Built-in bitemporal event store |

### Target Audience

- **Developers**: Testing, debugging, and development workflows
- **Operations Teams**: Monitoring, troubleshooting, and system health
- **Architects**: System design, capacity planning, and performance analysis
- **Business Analysts**: Event exploration and temporal queries

---

## PeeGeeQ Feature Catalog

This section catalogs all PeeGeeQ features across three queue types and consumer groups, providing the foundation for UI design.

### Native Queue Features & Configuration

**Overview**: Real-time LISTEN/NOTIFY based messaging with <10ms latency and 10,000+ msg/sec throughput.

#### Core Capabilities

1. **Real-time Processing**
   - PostgreSQL LISTEN/NOTIFY mechanism
   - Immediate message delivery
   - Sub-10ms latency
   - Event-driven architecture

2. **Consumer Modes**
   - `LISTEN_NOTIFY_ONLY`: Pure real-time, lowest database load
   - `POLLING_ONLY`: Scheduled polling, works with connection issues
   - `HYBRID`: Both LISTEN/NOTIFY and polling (default, maximum reliability)

3. **Performance Characteristics**
   - Throughput: 10,000+ messages/second
   - Latency: <10ms average
   - Connection pooling: Configurable pool size
   - Batch processing: Configurable batch sizes

#### Configuration Options

```yaml
Native Queue Configuration:
  consumer-mode:
    type: enum
    values: [LISTEN_NOTIFY_ONLY, POLLING_ONLY, HYBRID]
    default: HYBRID
    description: Operational mode for message consumption
  
  polling-interval:
    type: duration
    default: PT1S
    range: PT0.01S to PT60S
    description: Interval between polling cycles
  
  batch-size:
    type: integer
    default: 10
    range: 1 to 1000
    description: Number of messages to process in a batch
  
  connection-pool-size:
    type: integer
    default: 5
    range: 1 to 100
    description: Size of connection pool for LISTEN/NOTIFY
  
  enable-notifications:
    type: boolean
    default: true
    description: Enable PostgreSQL LISTEN/NOTIFY
  
  consumer-threads:
    type: integer
    default: 1
    range: 1 to 32
    description: Number of concurrent consumer threads
```

#### Performance Presets

**High-Throughput Preset**:
```yaml
batch-size: 100
polling-interval: PT0.1S
prefetch-count: 50
concurrent-consumers: 10
buffer-size: 1000
```

**Low-Latency Preset**:
```yaml
batch-size: 1
polling-interval: PT0.01S
prefetch-count: 1
concurrent-consumers: 1
buffer-size: 10
consumer-mode: LISTEN_NOTIFY_ONLY
```

**Reliable Preset**:
```yaml
max-retries: 10
dead-letter-enabled: true
retention-period: P30D
visibility-timeout: PT300S
consumer-mode: HYBRID
```

#### Monitoring Metrics

- **Message Rates**: Messages sent/received per second
- **Queue Depth**: Current number of available messages
- **Consumer Count**: Active consumers on the queue
- **Processing Time**: Average message processing duration
- **Error Rate**: Failed messages per second
- **Connection Pool**: Active/idle/pending connections

#### UI Requirements

1. **Queue Creation Form**
   - Queue name (required)
   - Consumer mode selection (radio buttons)
   - Performance preset dropdown (optional)
   - Advanced configuration toggle
   - Validation and error handling

2. **Queue Details View**
   - Real-time metrics dashboard
   - Consumer mode indicator
   - Configuration display
   - Edit configuration button
   - Performance charts (last 1hr, 24hr, 7d)

3. **Performance Tuning Panel**
   - Preset selector
   - Custom configuration editor
   - Performance impact estimator
   - Apply/revert controls

---

### Outbox Pattern Features & Configuration

**Overview**: Transactional outbox pattern with ACID guarantees, at-least-once delivery, and 5,000+ msg/sec throughput.

#### Core Capabilities

1. **Transactional Guarantees**
   - Messages written in same transaction as business data
   - ACID compliance
   - No message loss
   - Exactly-once semantics within transaction

2. **Polling-Based Processing**
   - Background polling service
   - Configurable polling intervals
   - Batch processing
   - Stuck message recovery

3. **Consumer Groups**
   - Multiple consumer groups per topic
   - Load balancing strategies
   - Partition assignment
   - Heartbeat monitoring

4. **Resilience Features**
   - Automatic retry with exponential backoff
   - Dead letter queue integration
   - Stuck message recovery
   - Circuit breaker protection

#### Configuration Options

```yaml
Outbox Queue Configuration:
  polling-interval:
    type: duration
    default: PT1S
    range: PT0.1S to PT60S
    description: Interval for polling outbox table

  batch-size:
    type: integer
    default: 100
    range: 1 to 1000
    description: Number of messages to process per batch

  max-retries:
    type: integer
    default: 3
    range: 0 to 100
    description: Maximum retry attempts before DLQ

  visibility-timeout:
    type: duration
    default: PT30S
    range: PT1S to PT3600S
    description: Time message remains invisible after consumption

  retention-period:
    type: duration
    default: P7D
    range: P1D to P365D
    description: How long to retain processed messages

  cleanup-interval:
    type: duration
    default: PT1H
    range: PT1M to P1D
    description: Interval for cleanup operations

  dead-letter-enabled:
    type: boolean
    default: true
    description: Enable dead letter queue for failed messages

  recovery-enabled:
    type: boolean
    default: true
    description: Enable stuck message recovery

  recovery-processing-timeout:
    type: duration
    default: PT5M
    description: Timeout before message considered stuck

  recovery-check-interval:
    type: duration
    default: PT10M
    description: Interval for stuck message checks
```

#### Message States

- **PENDING**: Newly created, awaiting processing
- **PROCESSING**: Currently being processed by a consumer
- **PROCESSED**: Successfully processed
- **FAILED**: Processing failed, awaiting retry
- **DEAD_LETTER**: Exceeded max retries, moved to DLQ

#### UI Requirements

1. **Outbox Queue Dashboard**
   - Message state distribution (pie chart)
   - Processing rate graph
   - Retry statistics
   - DLQ message count

2. **Message State Viewer**
   - Filter by state
   - Search by message ID, correlation ID
   - Bulk operations (retry, move to DLQ)
   - Message details modal

3. **Stuck Message Recovery Panel**
   - List of stuck messages
   - Recovery status
   - Manual recovery trigger
   - Configuration editor

4. **Cleanup Configuration**
   - Retention period slider
   - Cleanup schedule
   - Storage usage metrics
   - Manual cleanup trigger

---

### Bitemporal Event Store Features

**Overview**: Append-only event store with valid-time and transaction-time dimensions, enabling temporal queries and corrections.

#### Core Capabilities

1. **Bi-temporal Dimensions**
   - **Valid Time**: When the event actually happened (business time)
   - **Transaction Time**: When the event was recorded (system time)
   - Independent tracking of both dimensions
   - Point-in-time queries on either dimension

2. **Append-Only Storage**
   - Events never deleted
   - Corrections create new versions
   - Complete audit trail
   - Immutable history

3. **Event Versioning**
   - Version tracking for corrections
   - Previous version linking
   - Correction reason metadata
   - Full version history

4. **Temporal Queries**
   - Query as-of valid time
   - Query as-of transaction time
   - Range queries on both dimensions
   - Combined temporal filters

5. **Real-time Processing**
   - LISTEN/NOTIFY integration
   - Immediate event notifications
   - Event subscriptions
   - Stream processing

#### Event Query Capabilities

```yaml
Event Query Options:
  event-type:
    type: string
    description: Filter by event type
    example: "OrderCreated"

  aggregate-id:
    type: string
    description: Filter by aggregate/entity ID
    example: "ORDER-12345"

  correlation-id:
    type: string
    description: Filter by correlation ID for request tracking
    example: "req-abc-123"

  valid-time-range:
    type: temporal-range
    description: Filter by valid time (when event happened)
    example: "2025-01-01T00:00:00Z to 2025-01-31T23:59:59Z"

  transaction-time-range:
    type: temporal-range
    description: Filter by transaction time (when recorded)
    example: "2025-01-15T00:00:00Z to now"

  header-filters:
    type: key-value-map
    description: Filter by custom headers
    example: {region: "US", source: "web"}

  include-corrections:
    type: boolean
    default: true
    description: Include correction events in results

  version-range:
    type: integer-range
    description: Filter by event version
    example: "1 to 5"

  sort-order:
    type: enum
    values: [VALID_TIME_ASC, VALID_TIME_DESC, TRANSACTION_TIME_ASC, TRANSACTION_TIME_DESC, VERSION_ASC, VERSION_DESC]
    default: TRANSACTION_TIME_ASC

  limit:
    type: integer
    default: 1000
    range: 1 to 10000
    description: Maximum number of events to return

  offset:
    type: integer
    default: 0
    description: Pagination offset
```

#### Temporal Query Patterns

**Point-in-Time Query (Valid Time)**:
```sql
-- "What did we know about orders as of January 15, 2025?"
SELECT * FROM events
WHERE valid_time <= '2025-01-15T23:59:59Z'
  AND transaction_time = (
    SELECT MAX(transaction_time)
    FROM events e2
    WHERE e2.aggregate_id = events.aggregate_id
      AND e2.transaction_time <= NOW()
  )
```

**Point-in-Time Query (Transaction Time)**:
```sql
-- "What was recorded in the system as of 2 hours ago?"
SELECT * FROM events
WHERE transaction_time <= NOW() - INTERVAL '2 hours'
```

**Correction History**:
```sql
-- "Show all versions of event ORDER-12345"
SELECT * FROM events
WHERE aggregate_id = 'ORDER-12345'
ORDER BY version ASC
```

#### UI Requirements

1. **Event Store Browser**
   - Advanced query builder
   - Temporal timeline visualization
   - Event version tree
   - Correction history viewer

2. **Query Builder Interface**
   - Visual query constructor
   - Temporal range selectors (calendar + time)
   - Filter chips for active filters
   - Query templates (common patterns)
   - Save/load queries

3. **Event Timeline View**
   - Dual-axis timeline (valid time vs transaction time)
   - Event markers with tooltips
   - Zoom and pan controls
   - Highlight corrections

4. **Event Details Panel**
   - Event metadata display
   - Payload viewer (JSON formatted)
   - Version history
   - Correction chain
   - Related events

5. **Correction Workflow**
   - Select original event
   - Edit payload
   - Provide correction reason
   - Preview before submit
   - Confirmation dialog

#### Event Store Configuration

```yaml
Event Store Configuration:
  compression-enabled:
    type: boolean
    default: true
    description: Enable payload compression

  retention-period:
    type: duration
    default: P365D
    description: Event retention period (append-only, so very long)

  batch-size:
    type: integer
    default: 100
    description: Batch size for event queries

  notifications-enabled:
    type: boolean
    default: true
    description: Enable real-time event notifications

  partition-count:
    type: integer
    default: 16
    description: Number of partitions for scaling
```

---

### Consumer Group Features

**Overview**: Kafka-style consumer groups with load balancing, partition assignment, and fault tolerance.

#### Core Capabilities

1. **Load Balancing Strategies**
   - **ROUND_ROBIN**: Distribute partitions evenly in round-robin fashion
   - **RANGE**: Assign contiguous ranges of partitions to members
   - **STICKY**: Minimize partition reassignment during rebalancing
   - **RANDOM**: Randomly distribute partitions to members

2. **Topic Semantics**
   - **QUEUE**: Messages distributed to ONE consumer group (load balancing)
   - **PUB_SUB**: Messages replicated to ALL consumer groups (broadcast)

3. **Partition Management**
   - Configurable partition count (default: 16)
   - Automatic partition assignment
   - Rebalancing on member join/leave
   - Partition-aware message routing

4. **Member Management**
   - Consumer registration
   - Heartbeat monitoring
   - Dead consumer detection
   - Automatic failover

5. **Subscription Options**
   - Heartbeat interval configuration
   - Heartbeat timeout configuration
   - Message filtering
   - Prefetch count

#### Configuration Options

```yaml
Consumer Group Configuration:
  group-name:
    type: string
    required: true
    description: Unique consumer group name
    example: "order-processing-service"

  topic:
    type: string
    required: true
    description: Topic to subscribe to
    example: "orders.created"

  topic-semantics:
    type: enum
    values: [QUEUE, PUB_SUB]
    default: QUEUE
    description: Message distribution semantics

  load-balancing-strategy:
    type: enum
    values: [ROUND_ROBIN, RANGE, STICKY, RANDOM]
    default: ROUND_ROBIN
    description: Partition assignment strategy

  max-members:
    type: integer
    default: 10
    range: 1 to 100
    description: Maximum number of members in group

  session-timeout:
    type: duration
    default: PT30S
    description: Member session timeout

  heartbeat-interval:
    type: duration
    default: PT10S
    description: Heartbeat interval for members

  heartbeat-timeout:
    type: duration
    default: PT120S
    description: Timeout before marking member dead

  partition-count:
    type: integer
    default: 16
    range: 1 to 256
    description: Number of partitions for the topic
```

#### Member States

- **ACTIVE**: Member is healthy and processing messages
- **IDLE**: Member is connected but not processing
- **REBALANCING**: Member is participating in rebalance
- **DEAD**: Member failed heartbeat timeout

#### UI Requirements

1. **Consumer Group List**
   - Group name and topic
   - Member count
   - Topic semantics badge
   - Load balancing strategy
   - Health status indicator

2. **Consumer Group Details**
   - Member list with status
   - Partition assignment visualization
   - Rebalancing history
   - Performance metrics per member

3. **Partition Assignment Visualizer**
   - Visual representation of partitions
   - Color-coded by assigned member
   - Drag-and-drop for manual assignment
   - Rebalance trigger button

4. **Member Management Panel**
   - Add member button
   - Remove member button
   - Force rebalance button
   - Heartbeat status indicators

---

## RabbitMQ-Inspired UI Patterns

This section maps PeeGeeQ concepts to RabbitMQ's proven UI patterns, adapting them for PeeGeeQ's unique features.

### Overview Dashboard

**Purpose**: Provide at-a-glance system health and activity monitoring.

#### Layout Structure

```
┌─────────────────────────────────────────────────────────────┐
│  PeeGeeQ Management Console                    [User] [⚙️]  │
├─────────────────────────────────────────────────────────────┤
│  Overview | Queues | Consumer Groups | Event Stores | ...   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Total Queues │  │ Consumer Grps│  │ Event Stores │      │
│  │     42       │  │     18       │  │      8       │      │
│  │  ↑ 3 today   │  │  ↑ 2 today   │  │  → stable    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Messages/sec │  │ Active Conns │  │ System Health│      │
│  │   1,247      │  │     156      │  │   ✅ UP      │      │
│  │  ↑ 12% vs 1h │  │  → stable    │  │  All healthy │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Message Rate (last 24 hours)                        │    │
│  │                                                      │    │
│  │  [Line chart showing publish/deliver/ack rates]     │    │
│  │                                                      │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Recent Activity                                      │    │
│  │ ┌─────────┬──────────┬────────────┬──────────────┐  │    │
│  │ │ Time    │ Type     │ Resource   │ Action       │  │    │
│  │ ├─────────┼──────────┼────────────┼──────────────┤  │    │
│  │ │ 2m ago  │ Queue    │ orders     │ Created      │  │    │
│  │ │ 5m ago  │ Consumer │ processor-1│ Joined group │  │    │
│  │ │ 8m ago  │ Event    │ trade-001  │ Appended     │  │    │
│  │ └─────────┴──────────┴────────────┴──────────────┘  │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

#### Statistics Cards

**Card 1: Total Queues**
- Count of all queues (native + outbox + bitemporal)
- Trend indicator (up/down/stable)
- Breakdown by type (pie chart on hover)
- Click to navigate to queue list

**Card 2: Consumer Groups**
- Total consumer group count
- Active members count
- Trend indicator
- Click to navigate to consumer group list

**Card 3: Event Stores**
- Total event store count
- Total events stored
- Storage size
- Click to navigate to event store list

**Card 4: Messages/sec**
- Real-time message throughput
- Publish rate
- Delivery rate
- Acknowledgment rate

**Card 5: Active Connections**
- Current connection count
- Connection pool utilization
- Idle vs active breakdown

**Card 6: System Health**
- Overall health status (UP/DEGRADED/DOWN)
- Component health breakdown
- Circuit breaker states
- Click for detailed health report

#### Message Rate Chart

- **Time Range Selector**: Last 5min, 1hr, 8hr, 24hr, 7d
- **Metrics Displayed**:
  - Publish rate (messages/sec)
  - Delivery rate (messages/sec)
  - Acknowledgment rate (messages/sec)
  - Error rate (messages/sec)
- **Interactive Features**:
  - Hover for exact values
  - Zoom and pan
  - Export to CSV/PNG

#### Recent Activity Table

- **Columns**:
  - Timestamp (relative time)
  - Activity type (Queue, Consumer, Event, System)
  - Resource name
  - Action performed
  - User (if applicable)
- **Features**:
  - Real-time updates via WebSocket
  - Filter by activity type
  - Search by resource name
  - Click row for details

#### Real-time Updates

- WebSocket connection for live data
- Auto-refresh every 5 seconds (configurable)
- Visual indicators for updates
- Connection status indicator

---

### Queue List & Details Pages

**Purpose**: Comprehensive queue management inspired by RabbitMQ's queue interface.

#### Queue List Page

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│  Queues                                    [+ Create Queue]  │
├─────────────────────────────────────────────────────────────┤
│  [Search...] [Type: All ▼] [Status: All ▼] [🔄 Refresh]    │
├─────────────────────────────────────────────────────────────┤
│  Name          │Type    │Messages│Rate  │Consumers│Status  │
│  ──────────────┼────────┼────────┼──────┼─────────┼────────│
│  orders        │Native  │  1,234 │ 45/s │    3    │ ✅ UP  │
│  payments      │Outbox  │    567 │ 12/s │    2    │ ✅ UP  │
│  trade-events  │Bitemp  │ 10,234 │  8/s │    1    │ ✅ UP  │
│  notifications │Native  │     12 │  2/s │    5    │ ✅ UP  │
└─────────────────────────────────────────────────────────────┘
```

**Table Columns**:

1. **Name**: Queue name (clickable to details)
2. **Type**: Badge indicating queue type
   - 🔵 Native (blue)
   - 🟢 Outbox (green)
   - 🟣 Bitemporal (purple)
3. **Messages**: Total message count with breakdown on hover
   - Ready
   - Processing
   - Failed
4. **Rate**: Messages per second (in/out)
5. **Consumers**: Active consumer count
6. **Status**: Health indicator
   - ✅ UP (green)
   - ⚠️ DEGRADED (yellow)
   - ❌ DOWN (red)

**Actions**:
- **Create Queue**: Opens creation modal
- **Search**: Filter by queue name
- **Type Filter**: Filter by queue type
- **Status Filter**: Filter by health status
- **Refresh**: Manual refresh
- **Row Actions**: Edit, Delete, Purge (dropdown menu)

#### Queue Details Page

**Route**: `/queues/:setupId/:queueName`

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│  ← Back to Queues                                            │
│  Queue: orders (Native)                          [⚙️ Edit]   │
├─────────────────────────────────────────────────────────────┤
│  Overview | Consumers | Messages | Bindings | Charts        │
├─────────────────────────────────────────────────────────────┤
│  [Tab Content]                                               │
└─────────────────────────────────────────────────────────────┘
```

**Tab 1: Overview**

```
┌─────────────────────────────────────────────────────────────┐
│  Queue Properties                                            │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Name:           orders                              │    │
│  │ Type:           Native (LISTEN/NOTIFY)              │    │
│  │ Consumer Mode:  HYBRID                              │    │
│  │ Durable:        Yes                                 │    │
│  │ Created:        2025-11-15 10:30:00                 │    │
│  │ Node:           peegeeq-node-1                      │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Message Statistics                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Total        │  │ Ready        │  │ Processing   │      │
│  │   1,234      │  │    856       │  │     378      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  Message Rates (per second)                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Publish      │  │ Deliver      │  │ Acknowledge  │      │
│  │    45.2      │  │    43.8      │  │    43.5      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  Performance Metrics                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Avg Latency  │  │ Error Rate   │  │ Memory Usage │      │
│  │    8.5ms     │  │    0.02%     │  │    45.2 MB   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

**Tab 2: Consumers**

```
┌─────────────────────────────────────────────────────────────┐
│  Active Consumers (3)                                        │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Consumer ID    │ Group      │ Prefetch │ Unacked   │    │
│  ├────────────────┼────────────┼──────────┼───────────┤    │
│  │ consumer-1     │ processors │    10    │     5     │    │
│  │ consumer-2     │ processors │    10    │     8     │    │
│  │ consumer-3     │ analytics  │     5    │     2     │    │
│  └────────────────┴────────────┴──────────┴───────────┘    │
│                                                               │
│  Consumer Details (consumer-1)                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Connection:     conn-192.168.1.100:54321            │    │
│  │ Channel:        channel-5                           │    │
│  │ Ack Mode:       Manual                              │    │
│  │ Prefetch:       10                                  │    │
│  │ Unacked:        5                                   │    │
│  │ State:          ACTIVE                              │    │
│  │ Last Activity:  2 seconds ago                       │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 3: Messages**

This tab provides RabbitMQ-style message debugging capabilities.

**Get Messages Section**:
```
┌─────────────────────────────────────────────────────────────┐
│  Get Messages (for debugging)                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Message Count:  [1 ──────●────── 100]              │    │
│  │                                                      │    │
│  │ Ack Mode:       ○ Automatic ack                     │    │
│  │                 ○ Reject with requeue               │    │
│  │                 ● Reject without requeue            │    │
│  │                                                      │    │
│  │ Encoding:       [Auto detect ▼]                     │    │
│  │                                                      │    │
│  │                 [Get Messages]                       │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Retrieved Messages (2)                                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Message 1                                            │    │
│  │ ┌───────────────────────────────────────────────┐   │    │
│  │ │ Message ID:    msg-12345                      │   │    │
│  │ │ Routing Key:   order.created                  │   │    │
│  │ │ Exchange:      orders-exchange                │   │    │
│  │ │ Priority:      5                              │   │    │
│  │ │ Timestamp:     2025-11-20 14:30:00           │   │    │
│  │ │                                               │   │    │
│  │ │ Headers:                                      │   │    │
│  │ │   x-source: web-app                          │   │    │
│  │ │   x-user-id: user-789                        │   │    │
│  │ │                                               │   │    │
│  │ │ Payload:                                      │   │    │
│  │ │ {                                             │   │    │
│  │ │   "orderId": "ORD-12345",                    │   │    │
│  │ │   "customerId": "CUST-789",                  │   │    │
│  │ │   "amount": 99.99,                           │   │    │
│  │ │   "status": "CREATED"                        │   │    │
│  │ │ }                                             │   │    │
│  │ │                                               │   │    │
│  │ │ [Copy] [Download] [Republish]                │   │    │
│  │ └───────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Publish Message Section**:
```
┌─────────────────────────────────────────────────────────────┐
│  Publish Message (for testing)                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Routing Key: [order.created                    ]    │    │
│  │                                                      │    │
│  │ Payload:                                             │    │
│  │ ┌────────────────────────────────────────────────┐  │    │
│  │ │ {                                              │  │    │
│  │ │   "orderId": "ORD-TEST-001",                  │  │    │
│  │ │   "customerId": "CUST-TEST",                  │  │    │
│  │ │   "amount": 49.99,                            │  │    │
│  │ │   "status": "CREATED"                         │  │    │
│  │ │ }                                              │  │    │
│  │ └────────────────────────────────────────────────┘  │    │
│  │                                                      │    │
│  │ ▼ Properties (Optional)                              │    │
│  │ ┌────────────────────────────────────────────────┐  │    │
│  │ │ Delivery Mode:  ● Persistent ○ Non-persistent │  │    │
│  │ │ Priority:       [5 ──────●────── 10]          │  │    │
│  │ │ Expiration:     [60000] ms                    │  │    │
│  │ │ Content Type:   [application/json ▼]          │  │    │
│  │ │                                                │  │    │
│  │ │ Headers:                                       │  │    │
│  │ │ [+ Add Header]                                 │  │    │
│  │ │ x-source:       [test-ui]                     │  │    │
│  │ │ x-test-run:     [true]                        │  │    │
│  │ └────────────────────────────────────────────────┘  │    │
│  │                                                      │    │
│  │                 [Clear] [Publish Message]            │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ✅ Message published successfully (msg-id: msg-67890)       │
└─────────────────────────────────────────────────────────────┘
```

**Tab 4: Bindings**

```
┌─────────────────────────────────────────────────────────────┐
│  Queue Bindings                              [+ Add Binding] │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Source Exchange │ Routing Key    │ Arguments │ [×]  │    │
│  ├─────────────────┼────────────────┼───────────┼──────┤    │
│  │ orders-exchange │ order.created  │ {}        │ [×]  │    │
│  │ orders-exchange │ order.updated  │ {}        │ [×]  │    │
│  │ events-exchange │ order.*        │ {}        │ [×]  │    │
│  └─────────────────┴────────────────┴───────────┴──────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 5: Charts**

```
┌─────────────────────────────────────────────────────────────┐
│  Time Range: [Last 1 hour ▼]                                │
│                                                               │
│  Message Rate                                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Line chart: Publish, Deliver, Ack rates over time] │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Queue Depth                                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Area chart: Total messages over time]              │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Consumer Count                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Line chart: Active consumers over time]            │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Queue Actions Menu**

Available actions (context-dependent):
- **Edit Configuration**: Modify queue settings
- **Purge Queue**: Delete all messages (with confirmation)
- **Delete Queue**: Remove queue (with if-empty option)
- **Set Policy**: Apply or modify queue policy
- **Move Messages**: Transfer messages to another queue
- **Export Configuration**: Download queue config as JSON
- **View Logs**: Show queue-related logs

---

### Consumer Group Management

**Purpose**: Visualize and manage Kafka-style consumer groups with partition assignment and load balancing.

#### Consumer Group List Page

**Route**: `/consumer-groups`

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│  Consumer Groups                      [+ Create Group]       │
├─────────────────────────────────────────────────────────────┤
│  [Search...] [Topic: All ▼] [Status: All ▼] [🔄 Refresh]   │
├─────────────────────────────────────────────────────────────┤
│  Group Name    │Topic      │Members│Strategy  │Lag  │Status│
│  ──────────────┼───────────┼───────┼──────────┼─────┼──────│
│  processors    │orders     │   3   │ROUND_ROB │  12 │ ✅   │
│  analytics     │orders     │   2   │STICKY    │   5 │ ✅   │
│  notifications │events     │   5   │RANGE     │ 234 │ ⚠️   │
│  archiver      │trades     │   1   │RANDOM    │   0 │ ✅   │
└─────────────────────────────────────────────────────────────┘
```

**Table Columns**:

1. **Group Name**: Consumer group identifier (clickable)
2. **Topic**: Subscribed topic name
3. **Members**: Active member count
4. **Strategy**: Load balancing strategy badge
5. **Lag**: Total consumer lag (messages behind)
6. **Status**: Health indicator
   - ✅ Healthy (all members active)
   - ⚠️ Degraded (some members down)
   - ❌ Down (no active members)

#### Consumer Group Details Page

**Route**: `/consumer-groups/:groupName`

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│  ← Back to Consumer Groups                                   │
│  Consumer Group: processors                    [⚙️ Edit]     │
├─────────────────────────────────────────────────────────────┤
│  Overview | Members | Partitions | Lag | Configuration      │
├─────────────────────────────────────────────────────────────┤
│  [Tab Content]                                               │
└─────────────────────────────────────────────────────────────┘
```

**Tab 1: Overview**

```
┌─────────────────────────────────────────────────────────────┐
│  Group Properties                                            │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Group Name:         processors                      │    │
│  │ Topic:              orders                          │    │
│  │ Topic Semantics:    QUEUE (load balancing)         │    │
│  │ Strategy:           ROUND_ROBIN                     │    │
│  │ Members:            3 active, 0 idle                │    │
│  │ Partitions:         16 total, 16 assigned           │    │
│  │ Created:            2025-11-10 08:00:00            │    │
│  │ Last Rebalance:     2025-11-20 10:15:00            │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Consumer Lag                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Total Lag    │  │ Max Lag      │  │ Avg Lag      │      │
│  │     12       │  │      5       │  │     4.0      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  Processing Metrics                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Messages/sec │  │ Avg Latency  │  │ Error Rate   │      │
│  │    43.5      │  │    125ms     │  │    0.01%     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

**Tab 2: Members**

```
┌─────────────────────────────────────────────────────────────┐
│  Active Members (3)                                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Member ID   │Partitions│Lag │Last HB │Status │[...]│    │
│  ├─────────────┼──────────┼────┼────────┼───────┼─────┤    │
│  │ member-1    │  0-5     │  5 │  2s ago│ ✅    │ ... │    │
│  │ member-2    │  6-10    │  4 │  1s ago│ ✅    │ ... │    │
│  │ member-3    │ 11-15    │  3 │  3s ago│ ✅    │ ... │    │
│  └─────────────┴──────────┴────┴────────┴───────┴─────┘    │
│                                                               │
│  Member Details (member-1)                                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Member ID:          member-1                        │    │
│  │ Client ID:          processor-service-pod-1         │    │
│  │ Host:               192.168.1.100:8080              │    │
│  │ Joined:             2025-11-20 10:15:00            │    │
│  │ Last Heartbeat:     2 seconds ago                   │    │
│  │ Session Timeout:    30s                             │    │
│  │ Heartbeat Interval: 10s                             │    │
│  │ State:              ACTIVE                          │    │
│  │                                                      │    │
│  │ Assigned Partitions: 0, 1, 2, 3, 4, 5              │    │
│  │                                                      │    │
│  │ Processing Stats:                                   │    │
│  │   Messages/sec:     14.5                            │    │
│  │   Avg Latency:      120ms                           │    │
│  │   Errors:           0                               │    │
│  │                                                      │    │
│  │ [Force Rebalance] [Remove Member]                   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 3: Partitions**

This tab provides a visual representation of partition assignment.

```
┌─────────────────────────────────────────────────────────────┐
│  Partition Assignment Visualizer                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Strategy: ROUND_ROBIN                [Force Rebal]  │    │
│  │                                                      │    │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐     │    │
│  │  │  P0  │ │  P1  │ │  P2  │ │  P3  │ │  P4  │     │    │
│  │  │ M-1  │ │ M-1  │ │ M-1  │ │ M-1  │ │ M-1  │     │    │
│  │  │ Lag:2│ │ Lag:1│ │ Lag:0│ │ Lag:1│ │ Lag:1│     │    │
│  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘     │    │
│  │                                                      │    │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐     │    │
│  │  │  P5  │ │  P6  │ │  P7  │ │  P8  │ │  P9  │     │    │
│  │  │ M-1  │ │ M-2  │ │ M-2  │ │ M-2  │ │ M-2  │     │    │
│  │  │ Lag:0│ │ Lag:2│ │ Lag:1│ │ Lag:0│ │ Lag:1│     │    │
│  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘     │    │
│  │                                                      │    │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐     │    │
│  │  │ P10  │ │ P11  │ │ P12  │ │ P13  │ │ P14  │     │    │
│  │  │ M-2  │ │ M-3  │ │ M-3  │ │ M-3  │ │ M-3  │     │    │
│  │  │ Lag:0│ │ Lag:1│ │ Lag:1│ │ Lag:1│ │ Lag:0│     │    │
│  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘     │    │
│  │                                                      │    │
│  │  ┌──────┐                                           │    │
│  │  │ P15  │                                           │    │
│  │  │ M-3  │                                           │    │
│  │  │ Lag:0│                                           │    │
│  │  └──────┘                                           │    │
│  │                                                      │    │
│  │  Legend:                                            │    │
│  │  🟦 Member-1  🟩 Member-2  🟪 Member-3             │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Partition Details                                           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Partition │ Member   │ Offset │ Lag │ Rate  │ [...]│    │
│  ├───────────┼──────────┼────────┼─────┼───────┼──────┤    │
│  │     0     │ member-1 │ 12,345 │  2  │ 2.5/s │ ...  │    │
│  │     1     │ member-1 │ 23,456 │  1  │ 3.1/s │ ...  │    │
│  │     2     │ member-1 │ 34,567 │  0  │ 2.8/s │ ...  │    │
│  │    ...    │   ...    │  ...   │ ... │  ...  │ ...  │    │
│  └───────────┴──────────┴────────┴─────┴───────┴──────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Color Coding**:
- Each member gets a distinct color
- Partitions colored by assigned member
- Hover shows partition details
- Click partition for detailed view

**Tab 4: Lag**

```
┌─────────────────────────────────────────────────────────────┐
│  Consumer Lag Analysis                                       │
│                                                               │
│  Lag Over Time (Last 1 hour)                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Line chart: Total lag, per-member lag over time]   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Lag by Partition                                            │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Bar chart: Lag per partition]                      │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Lag Alerts                                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ ⚠️ Partition 6 lag exceeds threshold (234 > 100)    │    │
│  │ ℹ️ Member-2 processing slower than average          │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 5: Configuration**

```
┌─────────────────────────────────────────────────────────────┐
│  Consumer Group Configuration                  [Edit]        │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Load Balancing Strategy:                            │    │
│  │   ● ROUND_ROBIN                                     │    │
│  │   ○ RANGE                                           │    │
│  │   ○ STICKY                                          │    │
│  │   ○ RANDOM                                          │    │
│  │                                                      │    │
│  │ Topic Semantics:                                    │    │
│  │   ● QUEUE (load balancing)                          │    │
│  │   ○ PUB_SUB (broadcast)                             │    │
│  │                                                      │    │
│  │ Session Timeout:      [30] seconds                  │    │
│  │ Heartbeat Interval:   [10] seconds                  │    │
│  │ Heartbeat Timeout:    [120] seconds                 │    │
│  │ Max Members:          [10]                          │    │
│  │ Partition Count:      [16]                          │    │
│  │                                                      │    │
│  │ [Cancel] [Save Changes]                             │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

### Event Store Browser

**Purpose**: PeeGeeQ-specific feature for exploring bitemporal event stores with time-travel capabilities.

#### Event Store List Page

**Route**: `/event-stores`

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│  Event Stores                         [+ Create Store]       │
├─────────────────────────────────────────────────────────────┤
│  [Search...] [Type: All ▼] [🔄 Refresh]                     │
├─────────────────────────────────────────────────────────────┤
│  Store Name    │Events    │Size     │Partitions│Status     │
│  ──────────────┼──────────┼─────────┼──────────┼───────────│
│  trade-events  │ 1,234,567│ 2.3 GB  │    16    │ ✅ UP     │
│  order-events  │   456,789│ 890 MB  │    16    │ ✅ UP     │
│  audit-log     │ 5,678,901│ 8.9 GB  │    32    │ ✅ UP     │
└─────────────────────────────────────────────────────────────┘
```

#### Event Store Browser Page

**Route**: `/event-stores/:storeName`

This is the most unique and powerful UI component in PeeGeeQ, with no direct RabbitMQ equivalent.

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│  ← Back to Event Stores                                      │
│  Event Store: trade-events                                   │
├─────────────────────────────────────────────────────────────┤
│  Query Builder | Timeline | Events | Analytics              │
├─────────────────────────────────────────────────────────────┤
│  [Tab Content]                                               │
└─────────────────────────────────────────────────────────────┘
```

**Tab 1: Query Builder**

```
┌─────────────────────────────────────────────────────────────┐
│  Temporal Query Builder                                      │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Query Templates:                                    │    │
│  │ [Current State ▼] [Load] [Save As...]              │    │
│  │                                                      │    │
│  │ Templates:                                          │    │
│  │   • Current State (as of now)                       │    │
│  │   • Point in Time (valid time)                      │    │
│  │   • Historical Snapshot (transaction time)          │    │
│  │   • Event Corrections                               │    │
│  │   • Audit Trail                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Filters                                             │    │
│  │                                                      │    │
│  │ Event Type:                                         │    │
│  │ [TradeExecuted ▼] [+ Add]                          │    │
│  │   • TradeExecuted                                   │    │
│  │   • TradeAmended                                    │    │
│  │                                                      │    │
│  │ Aggregate ID:                                       │    │
│  │ [TRADE-12345                              ]         │    │
│  │                                                      │    │
│  │ Correlation ID:                                     │    │
│  │ [req-abc-123                              ]         │    │
│  │                                                      │    │
│  │ Custom Headers:                                     │    │
│  │ [+ Add Header Filter]                               │    │
│  │ region = [US        ]  [×]                          │    │
│  │ desk   = [EQUITY    ]  [×]                          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Temporal Dimensions                                 │    │
│  │                                                      │    │
│  │ Valid Time (When it happened):                      │    │
│  │ ○ All time                                          │    │
│  │ ● Range                                             │    │
│  │   From: [2025-11-01 00:00:00] 📅                   │    │
│  │   To:   [2025-11-20 23:59:59] 📅                   │    │
│  │ ○ As of: [2025-11-15 12:00:00] 📅                  │    │
│  │                                                      │    │
│  │ Transaction Time (When it was recorded):            │    │
│  │ ○ All time                                          │    │
│  │ ○ Range                                             │    │
│  │   From: [                   ] 📅                    │    │
│  │   To:   [                   ] 📅                    │    │
│  │ ● As of: [Now               ] 📅                    │    │
│  │                                                      │    │
│  │ ☑ Include corrections                               │    │
│  │ ☐ Only corrections                                  │    │
│  │ ☐ Latest version only                               │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Result Options                                      │    │
│  │                                                      │    │
│  │ Sort By:    [Transaction Time ▼] [Ascending ▼]     │    │
│  │ Limit:      [1000 ──────●────── 10000]              │    │
│  │ Offset:     [0]                                     │    │
│  │                                                      │    │
│  │ [Clear All] [Execute Query]                         │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 2: Timeline**

This tab provides a unique bi-temporal visualization.

```
┌─────────────────────────────────────────────────────────────┐
│  Bi-Temporal Timeline Visualization                          │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                                                      │    │
│  │  Transaction Time (When Recorded) ↑                 │    │
│  │                                    │                 │    │
│  │  Now ─────────────────────────────●─────────────    │    │
│  │       │                            │                 │    │
│  │  14:00 ───────────────────────────●─────────────    │    │
│  │       │                       ╱    │                 │    │
│  │  13:00 ──────────────────────●─────┼─────────────    │    │
│  │       │                 ╱           │                 │    │
│  │  12:00 ─────────────────●───────────┼─────────────    │    │
│  │       │            ╱                │                 │    │
│  │  11:00 ────────────●─────────────────────────────    │    │
│  │       │                                              │    │
│  │       └──────────────────────────────────────────→   │    │
│  │      11:00   12:00   13:00   14:00   Now            │    │
│  │                Valid Time (When It Happened)         │    │
│  │                                                      │    │
│  │  Legend:                                            │    │
│  │  ● Original Event  ● Correction  ─ Event Chain     │    │
│  │                                                      │    │
│  │  Hover over points for event details                │    │
│  │  Click point to view event                          │    │
│  │  Drag to select time range                          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Selected Event: TRADE-12345-v2                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Event Type:        TradeAmended                     │    │
│  │ Aggregate ID:      TRADE-12345                      │    │
│  │ Version:           2 (correction of v1)             │    │
│  │ Valid Time:        2025-11-20 11:30:00             │    │
│  │ Transaction Time:  2025-11-20 13:45:00             │    │
│  │ Correction Reason: Price adjustment                 │    │
│  │                                                      │    │
│  │ [View Full Event] [View Version History]            │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 3: Events**

```
┌─────────────────────────────────────────────────────────────┐
│  Query Results (1,234 events)                [Export CSV]    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Time │Type │Aggregate│Ver│Valid Time│Tx Time│[...]│    │
│  ├──────┼─────┼─────────┼───┼──────────┼───────┼─────┤    │
│  │ 14:30│Trade│TRADE-123│ 1 │14:30:00  │14:30:01│ ... │    │
│  │ 14:25│Trade│TRADE-122│ 1 │14:25:00  │14:25:01│ ... │    │
│  │ 14:20│Amend│TRADE-121│ 2 │14:15:00  │14:20:00│ 🔧  │    │
│  │ 14:15│Trade│TRADE-121│ 1 │14:15:00  │14:15:01│ ... │    │
│  │  ...  │ ... │   ...   │...│   ...    │  ...  │ ... │    │
│  └──────┴─────┴─────────┴───┴──────────┴───────┴─────┘    │
│                                                               │
│  🔧 = Correction event                                       │
│                                                               │
│  [< Previous] Page 1 of 13 [Next >]                         │
└─────────────────────────────────────────────────────────────┘
```

**Event Details Modal**:
```
┌─────────────────────────────────────────────────────────────┐
│  Event Details: TRADE-12345-v2                         [×]   │
├─────────────────────────────────────────────────────────────┤
│  Metadata | Payload | Version History | Related Events      │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Event Metadata                                      │    │
│  │                                                      │    │
│  │ Event ID:          evt-67890                        │    │
│  │ Event Type:        TradeAmended                     │    │
│  │ Aggregate ID:      TRADE-12345                      │    │
│  │ Aggregate Type:    Trade                            │    │
│  │ Version:           2                                │    │
│  │ Previous Version:  evt-12345                        │    │
│  │ Correlation ID:    req-abc-123                      │    │
│  │                                                      │    │
│  │ Valid Time:        2025-11-20 11:30:00 UTC         │    │
│  │ Transaction Time:  2025-11-20 13:45:00 UTC         │    │
│  │                                                      │    │
│  │ Is Correction:     Yes                              │    │
│  │ Correction Reason: Price adjustment due to error    │    │
│  │                                                      │    │
│  │ Headers:                                            │    │
│  │   region:          US                               │    │
│  │   desk:            EQUITY                           │    │
│  │   user:            trader-001                       │    │
│  │   source:          trading-system                   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Event Payload (JSON)                                │    │
│  │                                                      │    │
│  │ {                                                   │    │
│  │   "tradeId": "TRADE-12345",                        │    │
│  │   "symbol": "AAPL",                                │    │
│  │   "quantity": 1000,                                │    │
│  │   "price": 150.25,  ← Changed from 150.00         │    │
│  │   "side": "BUY",                                   │    │
│  │   "timestamp": "2025-11-20T11:30:00Z",            │    │
│  │   "trader": "trader-001",                          │    │
│  │   "desk": "EQUITY"                                 │    │
│  │ }                                                   │    │
│  │                                                      │    │
│  │ [Copy JSON] [Download] [Create Correction]          │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Version History Tab**:
```
┌─────────────────────────────────────────────────────────────┐
│  Version History for TRADE-12345                             │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Version Timeline                                    │    │
│  │                                                      │    │
│  │  v1 ──────────→ v2 ──────────→ v3                  │    │
│  │  11:30:00       13:45:00       14:20:00            │    │
│  │  Original       Price fix      Quantity fix         │    │
│  │                                                      │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Ver│Valid Time│Tx Time  │Reason        │[View]     │    │
│  ├────┼──────────┼─────────┼──────────────┼───────────┤    │
│  │ 3  │11:30:00  │14:20:00 │Qty correction│ [View]    │    │
│  │ 2  │11:30:00  │13:45:00 │Price fix     │ [View]    │    │
│  │ 1  │11:30:00  │11:30:01 │Original      │ [View]    │    │
│  └────┴──────────┴─────────┴──────────────┴───────────┘    │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Diff: v1 → v2                                       │    │
│  │                                                      │    │
│  │ {                                                   │    │
│  │   "tradeId": "TRADE-12345",                        │    │
│  │   "symbol": "AAPL",                                │    │
│  │   "quantity": 1000,                                │    │
│  │ - "price": 150.00,                                 │    │
│  │ + "price": 150.25,                                 │    │
│  │   "side": "BUY",                                   │    │
│  │   ...                                              │    │
│  │ }                                                   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Tab 4: Analytics**

```
┌─────────────────────────────────────────────────────────────┐
│  Event Store Analytics                                       │
│                                                               │
│  Event Type Distribution                                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Pie chart: Event types breakdown]                  │    │
│  │ • TradeExecuted: 45%                                │    │
│  │ • TradeAmended: 30%                                 │    │
│  │ • TradeCancelled: 15%                               │    │
│  │ • Other: 10%                                        │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Event Volume Over Time                                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ [Line chart: Events per hour/day]                   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Correction Rate                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Total Events:      1,234,567                        │    │
│  │ Corrections:          12,345 (1.0%)                 │    │
│  │ Avg Correction Lag:   2.5 hours                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                               │
│  Top Aggregates by Event Count                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Aggregate ID    │ Event Count │ Last Event         │    │
│  ├─────────────────┼─────────────┼────────────────────┤    │
│  │ TRADE-12345     │     156     │ 2 minutes ago      │    │
│  │ TRADE-12344     │     142     │ 5 minutes ago      │    │
│  │ TRADE-12343     │     128     │ 10 minutes ago     │    │
│  └─────────────────┴─────────────┴────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

### Message Browser & Publish Tool

**Purpose**: Debug and test message operations across all queue types.

This functionality is integrated into the Queue Details page (Messages tab) as shown earlier, but key features include:

#### Get Messages (Debug Tool)

**Features**:
- Retrieve messages without permanent consumption
- Configurable acknowledgment mode:
  - Automatic ack (message removed)
  - Reject with requeue (message returned to queue)
  - Reject without requeue (message moved to DLQ)
- Adjustable message count (1-100)
- Encoding detection (JSON, XML, plain text, binary)
- Message inspection with formatted display

**Use Cases**:
- Debugging message format issues
- Inspecting message headers
- Verifying message routing
- Testing consumer behavior

#### Publish Message (Test Tool)

**Features**:
- Manual message publishing for testing
- Routing key specification
- Custom headers
- Message properties:
  - Delivery mode (persistent/non-persistent)
  - Priority (0-10)
  - Expiration (TTL)
  - Content type
- Payload editor with syntax highlighting
- Template support for common message types
- Bulk publish capability

**Use Cases**:
- Testing queue configuration
- Simulating producer behavior
- Load testing
- Integration testing

---

## Management API Specification

This section details the REST API endpoints required to support the management UI.

### Existing APIs (Analysis)

Based on the current PeeGeeQ REST server implementation, the following endpoints exist:

#### Database Setup APIs

```
POST   /api/setup
  Description: Initialize database schema for PeeGeeQ
  Request Body: SetupRequest (setupId, queueType, config)
  Response: SetupResponse (setupId, status)

GET    /api/setup/:setupId
  Description: Get setup details
  Response: SetupDetails (setupId, queueType, config, status)

DELETE /api/setup/:setupId
  Description: Remove setup and cleanup resources
  Response: 204 No Content
```

#### Queue Operations APIs

```
POST   /api/queues/:setupId/:queueName/send
  Description: Send message to queue
  Request Body: SendMessageRequest (payload, headers, routingKey)
  Response: SendMessageResponse (messageId, status)

GET    /api/queues/:setupId/:queueName/stats
  Description: Get queue statistics
  Response: QueueStats (messageCount, consumerCount, rates)

GET    /api/queues/:setupId/:queueName/next
  Description: Get next message from queue
  Query Params: count (default: 1), timeout (default: 30s)
  Response: Message[] (id, payload, headers, metadata)

POST   /api/queues/:setupId/:queueName/ack
  Description: Acknowledge message processing
  Request Body: AckRequest (messageId, ackMode)
  Response: 204 No Content
```

#### Consumer Group APIs

```
POST   /api/consumer-groups
  Description: Create consumer group
  Request Body: CreateGroupRequest (groupName, topic, config)
  Response: ConsumerGroup (groupName, topic, config)

GET    /api/consumer-groups
  Description: List all consumer groups
  Response: ConsumerGroup[] (array of groups)

GET    /api/consumer-groups/:groupName
  Description: Get consumer group details
  Response: ConsumerGroupDetails (group info, members, partitions)

DELETE /api/consumer-groups/:groupName
  Description: Delete consumer group
  Response: 204 No Content

POST   /api/consumer-groups/:groupName/join
  Description: Join consumer group as member
  Request Body: JoinRequest (memberId, clientId, config)
  Response: JoinResponse (memberId, assignedPartitions)

POST   /api/consumer-groups/:groupName/leave
  Description: Leave consumer group
  Request Body: LeaveRequest (memberId)
  Response: 204 No Content
```

#### Management APIs

```
GET    /api/management/health
  Description: System health check
  Response: HealthStatus (status, components, checks)

GET    /api/management/overview
  Description: System overview statistics
  Response: SystemOverview (queues, consumers, messages, connections)

GET    /api/management/queues
  Description: List all queues with statistics
  Response: QueueSummary[] (name, type, stats, status)

GET    /api/management/queues/:setupId/:queueName
  Description: Get detailed queue information
  Response: QueueDetails (properties, stats, consumers, bindings)

GET    /api/management/queues/:setupId/:queueName/consumers
  Description: Get queue consumers
  Response: Consumer[] (id, group, prefetch, unacked)

GET    /api/management/queues/:setupId/:queueName/bindings
  Description: Get queue bindings
  Response: Binding[] (exchange, routingKey, arguments)

POST   /api/management/queues/:setupId/:queueName/purge
  Description: Purge all messages from queue
  Response: PurgeResponse (messagesPurged)
```

### Required API Extensions

To fully support the management UI, the following new endpoints are needed:

#### Enhanced Queue Management

```
PATCH  /api/management/queues/:setupId/:queueName/config
  Description: Update queue configuration
  Request Body: QueueConfigUpdate (partial config changes)
  Response: QueueDetails (updated queue details)

GET    /api/management/queues/:setupId/:queueName/messages
  Description: Get messages for debugging (non-destructive peek)
  Query Params:
    - count: number of messages (1-100)
    - ackMode: AUTO | REJECT_REQUEUE | REJECT_NO_REQUEUE
    - offset: pagination offset
  Response: Message[] (messages with full metadata)

POST   /api/management/queues/:setupId/:queueName/publish
  Description: Publish test message
  Request Body: PublishRequest (payload, headers, properties)
  Response: PublishResponse (messageId, routingInfo)

POST   /api/management/queues/:setupId/:queueName/move
  Description: Move messages to another queue
  Request Body: MoveRequest (targetQueue, messageIds, filter)
  Response: MoveResponse (movedCount)

GET    /api/management/queues/:setupId/:queueName/charts
  Description: Get time-series data for charts
  Query Params:
    - metric: RATE | DEPTH | CONSUMERS | LATENCY
    - range: 5m | 1h | 8h | 24h | 7d
    - resolution: 1s | 10s | 1m | 5m | 1h
  Response: ChartData (timestamps, values, metadata)
```

#### Consumer Group Enhancements

```
GET    /api/consumer-groups/:groupName/partitions
  Description: Get detailed partition assignment
  Response: PartitionAssignment[] (partition, member, offset, lag)

GET    /api/consumer-groups/:groupName/lag
  Description: Get consumer lag metrics
  Response: LagMetrics (totalLag, perPartition, perMember)

POST   /api/consumer-groups/:groupName/rebalance
  Description: Trigger manual rebalance
  Response: RebalanceResponse (status, newAssignments)

GET    /api/consumer-groups/:groupName/members/:memberId
  Description: Get detailed member information
  Response: MemberDetails (id, host, partitions, stats, heartbeat)

DELETE /api/consumer-groups/:groupName/members/:memberId
  Description: Remove member from group (force)
  Response: 204 No Content

PATCH  /api/consumer-groups/:groupName/config
  Description: Update consumer group configuration
  Request Body: GroupConfigUpdate (strategy, timeouts, etc.)
  Response: ConsumerGroupDetails (updated group)
```

#### Event Store APIs

```
GET    /api/event-stores
  Description: List all event stores
  Response: EventStore[] (name, eventCount, size, partitions)

POST   /api/event-stores
  Description: Create new event store
  Request Body: CreateEventStoreRequest (name, config)
  Response: EventStore (store details)

GET    /api/event-stores/:storeName
  Description: Get event store details
  Response: EventStoreDetails (properties, stats, config)

DELETE /api/event-stores/:storeName
  Description: Delete event store
  Response: 204 No Content

POST   /api/event-stores/:storeName/query
  Description: Query events with temporal filters
  Request Body: EventQuery (filters, temporal ranges, sort, pagination)
  Response: EventQueryResult (events, totalCount, hasMore)

  EventQuery Schema:
  {
    "eventTypes": ["TradeExecuted", "TradeAmended"],
    "aggregateId": "TRADE-12345",
    "correlationId": "req-abc-123",
    "headers": {
      "region": "US",
      "desk": "EQUITY"
    },
    "validTimeRange": {
      "from": "2025-11-01T00:00:00Z",
      "to": "2025-11-20T23:59:59Z"
    },
    "transactionTimeRange": {
      "asOf": "now"
    },
    "includeCorrections": true,
    "onlyCorrections": false,
    "latestVersionOnly": false,
    "sortBy": "TRANSACTION_TIME_ASC",
    "limit": 1000,
    "offset": 0
  }

POST   /api/event-stores/:storeName/append
  Description: Append new event
  Request Body: AppendEventRequest (event data)
  Response: AppendEventResponse (eventId, version)

POST   /api/event-stores/:storeName/correct
  Description: Append correction event
  Request Body: CorrectEventRequest (originalEventId, correctedPayload, reason)
  Response: AppendEventResponse (eventId, version)

GET    /api/event-stores/:storeName/events/:eventId
  Description: Get specific event by ID
  Response: Event (full event details)

GET    /api/event-stores/:storeName/events/:eventId/versions
  Description: Get all versions of an event (correction history)
  Response: EventVersion[] (version chain)

GET    /api/event-stores/:storeName/aggregates/:aggregateId
  Description: Get all events for an aggregate
  Query Params: includeCorrections, validTimeAsOf, transactionTimeAsOf
  Response: Event[] (aggregate event stream)

GET    /api/event-stores/:storeName/analytics
  Description: Get event store analytics
  Response: EventStoreAnalytics (distribution, volume, corrections)

  EventStoreAnalytics Schema:
  {
    "totalEvents": 1234567,
    "totalCorrections": 12345,
    "correctionRate": 0.01,
    "avgCorrectionLag": "PT2H30M",
    "eventTypeDistribution": {
      "TradeExecuted": 45.2,
      "TradeAmended": 30.1,
      "TradeCancelled": 15.3,
      "Other": 9.4
    },
    "topAggregates": [
      {
        "aggregateId": "TRADE-12345",
        "eventCount": 156,
        "lastEventTime": "2025-11-20T14:30:00Z"
      }
    ],
    "volumeOverTime": [
      {
        "timestamp": "2025-11-20T00:00:00Z",
        "count": 12345
      }
    ]
  }
```

#### Metrics and Monitoring APIs

```
GET    /api/metrics
  Description: Get all system metrics (Prometheus format)
  Response: Prometheus metrics text format

GET    /api/metrics/summary
  Description: Get metrics summary in JSON
  Response: MetricsSummary (rates, counts, gauges)

  MetricsSummary Schema:
  {
    "messageRates": {
      "publishRate": 45.2,
      "deliveryRate": 43.8,
      "ackRate": 43.5,
      "errorRate": 0.02
    },
    "queueMetrics": {
      "totalQueues": 42,
      "totalMessages": 12345,
      "avgQueueDepth": 294.4
    },
    "consumerMetrics": {
      "totalConsumers": 156,
      "totalConsumerGroups": 18,
      "avgLag": 12.5
    },
    "connectionMetrics": {
      "activeConnections": 156,
      "idleConnections": 44,
      "poolUtilization": 0.78
    },
    "systemMetrics": {
      "cpuUsage": 45.2,
      "memoryUsage": 2048,
      "diskUsage": 8192
    }
  }

GET    /api/metrics/queues/:setupId/:queueName
  Description: Get queue-specific metrics
  Response: QueueMetrics (rates, depth, latency, errors)

GET    /api/metrics/consumer-groups/:groupName
  Description: Get consumer group metrics
  Response: ConsumerGroupMetrics (lag, throughput, members)
```

#### Activity Log APIs

```
GET    /api/activity
  Description: Get recent activity log
  Query Params:
    - type: QUEUE | CONSUMER | EVENT | SYSTEM
    - limit: number of entries (default: 100)
    - since: timestamp
  Response: Activity[] (timestamp, type, resource, action, user)

  Activity Schema:
  {
    "timestamp": "2025-11-20T14:30:00Z",
    "type": "QUEUE",
    "resource": "orders",
    "action": "CREATED",
    "user": "admin",
    "details": {
      "queueType": "NATIVE",
      "config": {...}
    }
  }
```

### WebSocket/SSE Real-time Updates

To provide real-time updates in the UI, implement WebSocket or Server-Sent Events (SSE) endpoints.

#### WebSocket Endpoint

```
WS     /api/ws
  Description: WebSocket connection for real-time updates

  Message Types (Client → Server):

  1. Subscribe to Overview
     {
       "type": "SUBSCRIBE",
       "channel": "overview"
     }

  2. Subscribe to Queue
     {
       "type": "SUBSCRIBE",
       "channel": "queue",
       "setupId": "setup-1",
       "queueName": "orders"
     }

  3. Subscribe to Consumer Group
     {
       "type": "SUBSCRIBE",
       "channel": "consumer-group",
       "groupName": "processors"
     }

  4. Subscribe to Event Store
     {
       "type": "SUBSCRIBE",
       "channel": "event-store",
       "storeName": "trade-events"
     }

  5. Unsubscribe
     {
       "type": "UNSUBSCRIBE",
       "channel": "queue",
       "setupId": "setup-1",
       "queueName": "orders"
     }

  Message Types (Server → Client):

  1. Overview Update
     {
       "type": "OVERVIEW_UPDATE",
       "data": {
         "totalQueues": 42,
         "messageRate": 45.2,
         "consumerGroups": 18,
         ...
       }
     }

  2. Queue Update
     {
       "type": "QUEUE_UPDATE",
       "setupId": "setup-1",
       "queueName": "orders",
       "data": {
         "messageCount": 1234,
         "consumerCount": 3,
         "rates": {...}
       }
     }

  3. Consumer Group Update
     {
       "type": "CONSUMER_GROUP_UPDATE",
       "groupName": "processors",
       "data": {
         "members": [...],
         "partitions": [...],
         "lag": 12
       }
     }

  4. Event Notification
     {
       "type": "EVENT_APPENDED",
       "storeName": "trade-events",
       "event": {
         "eventId": "evt-12345",
         "eventType": "TradeExecuted",
         "aggregateId": "TRADE-12345",
         ...
       }
     }

  5. Activity Notification
     {
       "type": "ACTIVITY",
       "activity": {
         "timestamp": "2025-11-20T14:30:00Z",
         "type": "QUEUE",
         "resource": "orders",
         "action": "CREATED"
       }
     }

  6. Error
     {
       "type": "ERROR",
       "message": "Subscription failed",
       "code": "SUBSCRIPTION_ERROR"
     }
```

#### SSE Endpoint (Alternative)

```
GET    /api/sse/overview
  Description: SSE stream for overview updates
  Response: text/event-stream

GET    /api/sse/queues/:setupId/:queueName
  Description: SSE stream for queue updates
  Response: text/event-stream

GET    /api/sse/consumer-groups/:groupName
  Description: SSE stream for consumer group updates
  Response: text/event-stream

GET    /api/sse/activity
  Description: SSE stream for activity log
  Response: text/event-stream
```

**SSE Message Format**:
```
event: queue-update
data: {"messageCount": 1234, "consumerCount": 3, ...}

event: activity
data: {"timestamp": "2025-11-20T14:30:00Z", "type": "QUEUE", ...}
```

#### Implementation Considerations

**WebSocket vs SSE**:
- **WebSocket**: Bidirectional, better for interactive features, requires more complex client handling
- **SSE**: Unidirectional (server → client), simpler, built-in reconnection, HTTP-based

**Recommendation**: Use **WebSocket** for the management UI due to:
- Need for subscription management
- Interactive features (subscribe/unsubscribe)
- Better performance for high-frequency updates
- Vert.x has excellent WebSocket support

**Update Frequency**:
- Overview: Every 5 seconds
- Queue stats: Every 2 seconds
- Consumer group: Every 3 seconds
- Activity log: Real-time (as events occur)
- Event store: Real-time (on new events)

**Scalability**:
- Implement connection pooling
- Use broadcast for common subscriptions (e.g., overview)
- Throttle updates to prevent overwhelming clients
- Implement backpressure handling

---

## Implementation Roadmap

This section outlines a phased approach to implementing the PeeGeeQ Management UI, prioritizing features based on value and dependencies.

### Phase 1: Queue Management Enhancement

**Duration**: 4-6 weeks
**Priority**: Critical
**Dependencies**: None

#### Objectives

Enhance existing queue management capabilities to match RabbitMQ's queue management features.

#### Features

1. **Enhanced Queue List Page**
   - Comprehensive queue listing with filtering
   - Real-time statistics display
   - Queue type badges and status indicators
   - Search and filter capabilities
   - Bulk operations support

2. **Queue Details Page**
   - Multi-tab interface (Overview, Consumers, Messages, Bindings, Charts)
   - Real-time metrics dashboard
   - Consumer visualization
   - Performance charts with time range selection

3. **Message Browser (Get Messages)**
   - Non-destructive message peeking
   - Configurable acknowledgment modes
   - Message formatting and syntax highlighting
   - Header inspection
   - Pagination support

4. **Message Publisher (Publish Message)**
   - Test message publishing
   - Custom headers and properties
   - Routing key specification
   - Message templates
   - Validation and error handling

5. **Queue Configuration Editor**
   - Visual configuration editor
   - Performance preset selection
   - Advanced options toggle
   - Configuration validation
   - Apply/revert controls

6. **Queue Actions**
   - Purge queue (with confirmation)
   - Delete queue (with if-empty option)
   - Move messages between queues
   - Export configuration

#### API Requirements

**New Endpoints**:
- `GET /api/management/queues/:setupId/:queueName/messages` - Get messages for debugging
- `POST /api/management/queues/:setupId/:queueName/publish` - Publish test message
- `PATCH /api/management/queues/:setupId/:queueName/config` - Update configuration
- `POST /api/management/queues/:setupId/:queueName/move` - Move messages
- `GET /api/management/queues/:setupId/:queueName/charts` - Get chart data

**Enhanced Endpoints**:
- Enhance `/api/management/queues` with filtering and sorting
- Enhance `/api/management/queues/:setupId/:queueName` with more detailed stats

#### UI Components

**React Components**:
- `QueueListPage` - Main queue listing
- `QueueDetailsPage` - Queue details with tabs
- `QueueOverviewTab` - Overview statistics
- `QueueConsumersTab` - Consumer list and details
- `QueueMessagesTab` - Message browser and publisher
- `QueueBindingsTab` - Binding management
- `QueueChartsTab` - Performance charts
- `MessageBrowser` - Get messages component
- `MessagePublisher` - Publish message component
- `QueueConfigEditor` - Configuration editor
- `QueueActionsMenu` - Action dropdown

**Shared Components**:
- `StatCard` - Metric display card
- `LineChart` - Time-series chart
- `AreaChart` - Area chart for queue depth
- `FilterBar` - Search and filter controls
- `ConfirmDialog` - Confirmation modal
- `CodeEditor` - JSON/text editor with syntax highlighting

#### Acceptance Criteria

- [ ] Queue list displays all queues with real-time statistics
- [ ] Queue details page shows comprehensive information across all tabs
- [ ] Message browser can retrieve and display messages without permanent consumption
- [ ] Message publisher can send test messages with custom properties
- [ ] Queue configuration can be edited and applied
- [ ] Queue purge operation works with confirmation
- [ ] Charts display accurate time-series data
- [ ] All operations handle errors gracefully
- [ ] UI is responsive and performs well with 100+ queues

#### Testing

- Unit tests for all React components
- Integration tests for API endpoints
- E2E tests for critical workflows:
  - Create queue → publish message → get message → purge queue
  - Edit queue configuration → verify changes
  - View queue charts → change time range

---

### Phase 2: Consumer Group Visualization

**Duration**: 3-4 weeks
**Priority**: High
**Dependencies**: Phase 1 (shared components)

#### Objectives

Implement comprehensive consumer group management with partition assignment visualization.

#### Features

1. **Consumer Group List Page**
   - List all consumer groups
   - Display member count, lag, and status
   - Filter by topic and status
   - Search functionality

2. **Consumer Group Details Page**
   - Multi-tab interface (Overview, Members, Partitions, Lag, Configuration)
   - Group properties display
   - Real-time metrics

3. **Member Management**
   - Active member list
   - Member details panel
   - Heartbeat status monitoring
   - Force member removal
   - Manual rebalance trigger

4. **Partition Assignment Visualizer**
   - Visual grid of partitions
   - Color-coded by assigned member
   - Partition details on hover/click
   - Lag indicators per partition
   - Rebalancing animation

5. **Lag Analysis**
   - Total lag metrics
   - Per-partition lag breakdown
   - Per-member lag breakdown
   - Lag over time charts
   - Lag alerts and warnings

6. **Configuration Management**
   - Load balancing strategy selection
   - Topic semantics configuration
   - Timeout settings
   - Member limits

#### API Requirements

**New Endpoints**:
- `GET /api/consumer-groups/:groupName/partitions` - Partition assignment details
- `GET /api/consumer-groups/:groupName/lag` - Lag metrics
- `POST /api/consumer-groups/:groupName/rebalance` - Trigger rebalance
- `GET /api/consumer-groups/:groupName/members/:memberId` - Member details
- `DELETE /api/consumer-groups/:groupName/members/:memberId` - Remove member
- `PATCH /api/consumer-groups/:groupName/config` - Update configuration
- `GET /api/metrics/consumer-groups/:groupName` - Group metrics

#### UI Components

**React Components**:
- `ConsumerGroupListPage` - Group listing
- `ConsumerGroupDetailsPage` - Group details with tabs
- `GroupOverviewTab` - Overview statistics
- `GroupMembersTab` - Member list and details
- `GroupPartitionsTab` - Partition visualizer
- `GroupLagTab` - Lag analysis
- `GroupConfigTab` - Configuration editor
- `PartitionGrid` - Visual partition assignment
- `MemberCard` - Member details card
- `LagChart` - Lag visualization
- `RebalanceButton` - Trigger rebalance with confirmation

#### Acceptance Criteria

- [ ] Consumer group list displays all groups with accurate statistics
- [ ] Group details page shows comprehensive information
- [ ] Partition visualizer accurately represents assignment
- [ ] Member management allows viewing and removing members
- [ ] Lag analysis shows accurate lag metrics
- [ ] Rebalancing can be triggered and visualized
- [ ] Configuration can be updated
- [ ] Real-time updates reflect member joins/leaves
- [ ] UI handles groups with 100+ partitions efficiently

#### Testing

- Unit tests for all components
- Integration tests for consumer group APIs
- E2E tests for workflows:
  - Create group → join members → view partitions → trigger rebalance
  - Monitor lag → identify slow consumer → remove member
  - Update configuration → verify changes

---

### Phase 3: Event Store Explorer

**Duration**: 4-5 weeks
**Priority**: High
**Dependencies**: Phase 1 (shared components)

#### Objectives

Implement the unique bitemporal event store browser with temporal query capabilities.

#### Features

1. **Event Store List Page**
   - List all event stores
   - Display event count, size, and status
   - Search and filter

2. **Event Store Browser**
   - Multi-tab interface (Query Builder, Timeline, Events, Analytics)
   - Comprehensive query capabilities

3. **Temporal Query Builder**
   - Visual query constructor
   - Event type filters
   - Aggregate ID filters
   - Correlation ID filters
   - Custom header filters
   - Valid time range selector
   - Transaction time range selector
   - Correction filters
   - Query templates
   - Save/load queries

4. **Bi-Temporal Timeline Visualization**
   - 2D timeline (valid time × transaction time)
   - Event markers
   - Correction chains
   - Interactive zoom and pan
   - Event selection
   - Time range selection

5. **Event List and Details**
   - Paginated event list
   - Event details modal
   - Metadata display
   - Payload viewer with formatting
   - Version history
   - Correction chain visualization
   - Diff viewer for corrections

6. **Event Analytics**
   - Event type distribution
   - Event volume over time
   - Correction rate analysis
   - Top aggregates by event count
   - Storage metrics

7. **Event Operations**
   - Append new event
   - Create correction
   - Export events (CSV, JSON)
   - Copy event payload

#### API Requirements

**New Endpoints**:
- `GET /api/event-stores` - List event stores
- `POST /api/event-stores` - Create event store
- `GET /api/event-stores/:storeName` - Get store details
- `DELETE /api/event-stores/:storeName` - Delete store
- `POST /api/event-stores/:storeName/query` - Query events
- `POST /api/event-stores/:storeName/append` - Append event
- `POST /api/event-stores/:storeName/correct` - Append correction
- `GET /api/event-stores/:storeName/events/:eventId` - Get event
- `GET /api/event-stores/:storeName/events/:eventId/versions` - Get versions
- `GET /api/event-stores/:storeName/aggregates/:aggregateId` - Get aggregate events
- `GET /api/event-stores/:storeName/analytics` - Get analytics

#### UI Components

**React Components**:
- `EventStoreListPage` - Store listing
- `EventStoreBrowserPage` - Browser with tabs
- `QueryBuilderTab` - Visual query builder
- `TimelineTab` - Bi-temporal timeline
- `EventsTab` - Event list
- `AnalyticsTab` - Analytics dashboard
- `TemporalQueryBuilder` - Query construction component
- `BiTemporalTimeline` - 2D timeline visualization
- `EventList` - Paginated event list
- `EventDetailsModal` - Event details popup
- `VersionHistoryViewer` - Version chain display
- `EventDiffViewer` - Diff between versions
- `EventAnalytics` - Analytics charts
- `AppendEventForm` - New event form
- `CorrectEventForm` - Correction form

**Advanced Components**:
- `TemporalRangePicker` - Date/time range selector with calendar
- `QueryTemplateSelector` - Template dropdown
- `FilterChips` - Active filter display
- `TimelineCanvas` - Canvas-based timeline rendering for performance

#### Acceptance Criteria

- [ ] Event store list displays all stores
- [ ] Query builder supports all filter types
- [ ] Temporal range selectors work correctly
- [ ] Timeline visualizes events on both temporal dimensions
- [ ] Event list displays query results with pagination
- [ ] Event details show complete metadata and payload
- [ ] Version history displays correction chain
- [ ] Diff viewer highlights changes between versions
- [ ] Analytics show accurate statistics
- [ ] New events can be appended
- [ ] Corrections can be created with reason
- [ ] Query templates can be saved and loaded
- [ ] UI handles 10,000+ events efficiently
- [ ] Timeline performs well with 1,000+ events

#### Testing

- Unit tests for all components
- Integration tests for event store APIs
- E2E tests for workflows:
  - Query events → view timeline → select event → view details
  - Create correction → view version history → compare versions
  - Build complex query → save template → load template
  - Append event → query for it → verify it appears

---

### Phase 4: Advanced Features

**Duration**: 3-4 weeks
**Priority**: Medium
**Dependencies**: Phases 1-3

#### Objectives

Add advanced features for production operations and monitoring.

#### Features

1. **Real-time Updates (WebSocket)**
   - WebSocket connection management
   - Subscribe/unsubscribe to channels
   - Real-time metric updates
   - Activity stream
   - Connection status indicator
   - Automatic reconnection

2. **Enhanced Overview Dashboard**
   - System-wide statistics
   - Message rate charts
   - Recent activity log
   - Health status indicators
   - Quick actions
   - Customizable widgets

3. **Advanced Monitoring**
   - Custom metric dashboards
   - Alert configuration
   - Threshold monitoring
   - Performance analytics
   - Trend analysis

4. **Configuration Management**
   - Global settings
   - Policy management
   - User preferences
   - Theme customization
   - Export/import configuration

5. **Operational Tools**
   - Bulk operations
   - Scheduled tasks
   - Backup/restore
   - Migration tools
   - Diagnostic tools

6. **User Management** (if multi-user)
   - User authentication
   - Role-based access control
   - Audit logging
   - Session management

#### API Requirements

**New Endpoints**:
- `WS /api/ws` - WebSocket endpoint for real-time updates
- `GET /api/activity` - Activity log
- `GET /api/metrics` - Prometheus metrics
- `GET /api/metrics/summary` - Metrics summary
- `POST /api/alerts` - Create alert
- `GET /api/alerts` - List alerts
- `PATCH /api/alerts/:alertId` - Update alert
- `DELETE /api/alerts/:alertId` - Delete alert
- `GET /api/config` - Get global configuration
- `PATCH /api/config` - Update configuration
- `POST /api/auth/login` - User login (if multi-user)
- `POST /api/auth/logout` - User logout
- `GET /api/users` - List users
- `POST /api/users` - Create user
- `PATCH /api/users/:userId` - Update user
- `DELETE /api/users/:userId` - Delete user

#### UI Components

**React Components**:
- `OverviewDashboard` - Enhanced dashboard
- `ActivityLog` - Real-time activity stream
- `MetricsDashboard` - Custom metrics dashboard
- `AlertManager` - Alert configuration
- `ConfigurationPanel` - Global settings
- `UserManagement` - User admin panel
- `WebSocketProvider` - WebSocket context provider
- `ConnectionStatus` - Connection indicator
- `ThemeSelector` - Theme customization
- `BulkOperations` - Bulk action interface

#### Acceptance Criteria

- [ ] WebSocket connection provides real-time updates
- [ ] Overview dashboard shows system-wide statistics
- [ ] Activity log displays recent actions in real-time
- [ ] Alerts can be configured and triggered
- [ ] Global configuration can be managed
- [ ] Bulk operations work across multiple resources
- [ ] User authentication and authorization work (if implemented)
- [ ] Connection status is clearly indicated
- [ ] Automatic reconnection works reliably
- [ ] UI remains responsive during high update frequency

#### Testing

- Unit tests for all components
- Integration tests for WebSocket functionality
- E2E tests for workflows:
  - Connect → subscribe to updates → verify real-time data
  - Configure alert → trigger condition → verify notification
  - Perform bulk operation → verify all affected resources
  - Login → perform actions → verify audit log

---

### Implementation Timeline Summary

| Phase | Duration | Start | End | Key Deliverables |
|-------|----------|-------|-----|------------------|
| Phase 1: Queue Management | 4-6 weeks | Week 1 | Week 6 | Enhanced queue list, details, message browser/publisher |
| Phase 2: Consumer Groups | 3-4 weeks | Week 7 | Week 10 | Consumer group visualization, partition assignment |
| Phase 3: Event Store | 4-5 weeks | Week 11 | Week 15 | Bitemporal query builder, timeline, event browser |
| Phase 4: Advanced Features | 3-4 weeks | Week 16 | Week 19 | Real-time updates, monitoring, operational tools |
| **Total** | **14-19 weeks** | | | **Complete Management UI** |

### Resource Requirements

**Backend Development**:
- 1 Senior Java/Vert.x Developer (full-time)
- Focus: REST API implementation, WebSocket server, database queries

**Frontend Development**:
- 1 Senior React Developer (full-time)
- 1 Mid-level React Developer (full-time, Phases 2-4)
- Focus: UI components, state management, real-time updates

**Design**:
- 1 UI/UX Designer (part-time, 50%)
- Focus: Wireframes, mockups, user flows, design system

**QA**:
- 1 QA Engineer (full-time, starting Phase 2)
- Focus: Test planning, automation, E2E testing

**DevOps**:
- 1 DevOps Engineer (part-time, 25%)
- Focus: Build pipeline, deployment, monitoring setup

### Risk Mitigation

**Technical Risks**:

1. **Performance with Large Datasets**
   - Risk: UI becomes slow with 1000+ queues or 10,000+ events
   - Mitigation:
     - Implement pagination and virtual scrolling
     - Use canvas rendering for timeline
     - Optimize queries with indexes
     - Implement caching

2. **WebSocket Scalability**
   - Risk: WebSocket connections don't scale to many concurrent users
   - Mitigation:
     - Implement connection pooling
     - Use broadcast for common subscriptions
     - Implement backpressure handling
     - Load test early

3. **Complex Temporal Queries**
   - Risk: Bitemporal queries are slow or complex to implement
   - Mitigation:
     - Design efficient database schema with proper indexes
     - Implement query optimization
     - Provide query templates for common patterns
     - Cache frequently accessed data

**Schedule Risks**:

1. **Scope Creep**
   - Risk: Additional features requested during development
   - Mitigation:
     - Strict phase boundaries
     - Change request process
     - Regular stakeholder reviews
     - MVP-first approach

2. **Integration Delays**
   - Risk: Backend API development delays frontend work
   - Mitigation:
     - API-first design with contracts
     - Mock API server for frontend development
     - Parallel development where possible
     - Regular integration checkpoints

---

## Technical Considerations

This section covers technical decisions, architecture, and implementation details.

### Technology Stack

#### Frontend

**Framework**: React 18+
- Mature ecosystem
- Excellent performance with hooks and concurrent features
- Large community and library support
- TypeScript support

**State Management**: Redux Toolkit + RTK Query
- Centralized state management
- Built-in data fetching and caching
- DevTools for debugging
- TypeScript support
- Optimistic updates

**UI Component Library**: Material-UI (MUI) v5
- Comprehensive component set
- Customizable theming
- Accessibility built-in
- Responsive design
- Well-documented

**Charting**: Recharts + D3.js
- Recharts for standard charts (line, area, bar, pie)
- D3.js for custom visualizations (bitemporal timeline)
- React-friendly
- Performant

**Code Editor**: Monaco Editor
- Same editor as VS Code
- Syntax highlighting
- JSON validation
- Autocomplete
- Diff viewer

**WebSocket**: Native WebSocket API + reconnecting-websocket
- Simple and reliable
- Automatic reconnection
- Exponential backoff
- Event-based API

**Build Tool**: Vite
- Fast development server
- Hot module replacement
- Optimized production builds
- TypeScript support

**Testing**:
- Jest - Unit testing
- React Testing Library - Component testing
- Playwright - E2E testing
- MSW (Mock Service Worker) - API mocking

#### Backend

**Framework**: Vert.x 5.x (existing)
- High-performance async I/O
- WebSocket support
- Reactive programming model
- PostgreSQL client

**API Design**: RESTful + WebSocket
- REST for CRUD operations
- WebSocket for real-time updates
- JSON for data exchange
- OpenAPI/Swagger documentation

**Database**: PostgreSQL (existing)
- LISTEN/NOTIFY for real-time events
- JSON/JSONB for flexible data
- Excellent indexing capabilities
- Bitemporal query support

**Metrics**: Micrometer (existing)
- Prometheus integration
- Custom metrics
- JVM metrics
- Database metrics

### Architecture

#### Frontend Architecture

```
src/
├── api/                    # API client and RTK Query
│   ├── client.ts          # Base API client
│   ├── websocket.ts       # WebSocket client
│   ├── queues.ts          # Queue API endpoints
│   ├── consumerGroups.ts  # Consumer group endpoints
│   └── eventStores.ts     # Event store endpoints
├── components/            # Reusable components
│   ├── common/           # Shared components
│   │   ├── StatCard.tsx
│   │   ├── LineChart.tsx
│   │   ├── FilterBar.tsx
│   │   └── CodeEditor.tsx
│   ├── queues/           # Queue-specific components
│   ├── consumerGroups/   # Consumer group components
│   └── eventStores/      # Event store components
├── pages/                # Page components
│   ├── OverviewPage.tsx
│   ├── QueueListPage.tsx
│   ├── QueueDetailsPage.tsx
│   ├── ConsumerGroupListPage.tsx
│   ├── ConsumerGroupDetailsPage.tsx
│   ├── EventStoreListPage.tsx
│   └── EventStoreBrowserPage.tsx
├── store/                # Redux store
│   ├── index.ts
│   ├── slices/
│   │   ├── queuesSlice.ts
│   │   ├── consumerGroupsSlice.ts
│   │   └── eventStoresSlice.ts
│   └── api/              # RTK Query API slices
│       ├── queuesApi.ts
│       ├── consumerGroupsApi.ts
│       └── eventStoresApi.ts
├── hooks/                # Custom hooks
│   ├── useWebSocket.ts
│   ├── useRealTimeUpdates.ts
│   └── usePolling.ts
├── utils/                # Utility functions
│   ├── formatters.ts
│   ├── validators.ts
│   └── dateUtils.ts
├── types/                # TypeScript types
│   ├── queue.ts
│   ├── consumerGroup.ts
│   └── eventStore.ts
├── theme/                # MUI theme configuration
│   └── index.ts
├── App.tsx               # Root component
└── main.tsx              # Entry point
```

#### Backend Architecture

```
peegeeq-rest/
├── src/main/java/dev/mars/peegeeq/rest/
│   ├── PeeGeeQRestServer.java           # Main server
│   ├── handlers/                         # Request handlers
│   │   ├── ManagementApiHandler.java    # Management endpoints
│   │   ├── QueueApiHandler.java         # Queue operations
│   │   ├── ConsumerGroupApiHandler.java # Consumer groups
│   │   ├── EventStoreApiHandler.java    # Event stores (new)
│   │   ├── MetricsApiHandler.java       # Metrics (new)
│   │   └── WebSocketHandler.java        # WebSocket (new)
│   ├── models/                           # Request/response models
│   │   ├── requests/
│   │   └── responses/
│   ├── services/                         # Business logic
│   │   ├── QueueService.java
│   │   ├── ConsumerGroupService.java
│   │   ├── EventStoreService.java       # (new)
│   │   └── MetricsService.java          # (new)
│   └── websocket/                        # WebSocket support (new)
│       ├── WebSocketManager.java
│       ├── SubscriptionManager.java
│       └── BroadcastService.java
```

### Data Flow

#### REST API Flow

```
User Action (UI)
    ↓
React Component
    ↓
RTK Query Hook
    ↓
API Client (fetch)
    ↓
Vert.x REST Handler
    ↓
Service Layer
    ↓
PeeGeeQ Core / PostgreSQL
    ↓
Response
    ↓
RTK Query Cache Update
    ↓
Component Re-render
```

#### WebSocket Flow

```
Component Mount
    ↓
useWebSocket Hook
    ↓
WebSocket Connection
    ↓
Subscribe Message
    ↓
Vert.x WebSocket Handler
    ↓
Subscription Manager
    ↓
[Background: PostgreSQL LISTEN/NOTIFY or Polling]
    ↓
Broadcast Service
    ↓
WebSocket Message to Client
    ↓
Redux Store Update
    ↓
Component Re-render
```

### Performance Optimization

#### Frontend Optimizations

1. **Code Splitting**
   - Lazy load routes
   - Dynamic imports for heavy components
   - Separate vendor bundles

2. **Memoization**
   - React.memo for expensive components
   - useMemo for expensive calculations
   - useCallback for event handlers

3. **Virtual Scrolling**
   - Use react-window for large lists
   - Render only visible items
   - Reduce DOM nodes

4. **Debouncing/Throttling**
   - Debounce search inputs
   - Throttle scroll events
   - Throttle WebSocket updates

5. **Caching**
   - RTK Query automatic caching
   - Cache invalidation strategies
   - Optimistic updates

6. **Bundle Optimization**
   - Tree shaking
   - Minification
   - Compression (gzip/brotli)
   - CDN for static assets

#### Backend Optimizations

1. **Database Queries**
   - Proper indexing on frequently queried columns
   - Query optimization with EXPLAIN ANALYZE
   - Connection pooling
   - Prepared statements

2. **Caching**
   - In-memory cache for frequently accessed data
   - Cache invalidation on updates
   - TTL-based expiration

3. **Async Processing**
   - Non-blocking I/O with Vert.x
   - Parallel query execution
   - Batch operations

4. **WebSocket Optimization**
   - Message batching
   - Compression
   - Throttling updates
   - Broadcast for common subscriptions

5. **Metrics Collection**
   - Async metric recording
   - Sampling for high-frequency metrics
   - Aggregation before export

### Security Considerations

#### Authentication and Authorization

**Options**:

1. **No Authentication** (Development/Internal)
   - Suitable for internal tools
   - Network-level security (VPN, firewall)
   - Simplest to implement

2. **Basic Authentication**
   - Username/password
   - HTTPS required
   - Simple but less secure

3. **JWT-based Authentication**
   - Stateless authentication
   - Token-based
   - Refresh token support
   - Recommended for production

4. **OAuth 2.0 / OIDC**
   - Enterprise SSO integration
   - Third-party identity providers
   - Most secure and flexible

**Recommendation**: Start with **no authentication** for MVP, add **JWT-based authentication** for production.

#### Authorization Model

**Role-Based Access Control (RBAC)**:

Roles:
- **Admin**: Full access to all operations
- **Operator**: Read/write access to queues and consumer groups, read-only for configuration
- **Developer**: Read/write for message operations, read-only for configuration
- **Viewer**: Read-only access to all resources

Permissions:
- `queues:read` - View queues
- `queues:write` - Create, update, delete queues
- `queues:publish` - Publish messages
- `queues:purge` - Purge queues
- `consumer-groups:read` - View consumer groups
- `consumer-groups:write` - Create, update, delete groups
- `consumer-groups:manage` - Manage members, trigger rebalance
- `event-stores:read` - Query events
- `event-stores:write` - Append events, corrections
- `config:read` - View configuration
- `config:write` - Update configuration
- `users:manage` - Manage users (admin only)

#### API Security

1. **HTTPS/TLS**
   - Enforce HTTPS in production
   - TLS 1.2+ only
   - Valid certificates

2. **CORS**
   - Configure allowed origins
   - Restrict to known domains
   - Credentials support if needed

3. **Rate Limiting**
   - Per-IP rate limits
   - Per-user rate limits
   - Prevent abuse and DoS

4. **Input Validation**
   - Validate all inputs
   - Sanitize user data
   - Prevent SQL injection (use parameterized queries)
   - Prevent XSS (escape output)

5. **CSRF Protection**
   - CSRF tokens for state-changing operations
   - SameSite cookies
   - Double-submit cookie pattern

6. **API Keys** (optional)
   - For programmatic access
   - Separate from user authentication
   - Revocable

#### WebSocket Security

1. **Authentication**
   - Authenticate on connection
   - Validate JWT token
   - Close connection on auth failure

2. **Authorization**
   - Check permissions for subscriptions
   - Validate channel access
   - Filter data based on permissions

3. **Message Validation**
   - Validate message format
   - Sanitize message content
   - Rate limit messages

4. **Connection Limits**
   - Max connections per user
   - Max connections per IP
   - Prevent resource exhaustion

### Deployment

#### Deployment Architecture

**Option 1: Embedded UI (Recommended for MVP)**

```
┌─────────────────────────────────────┐
│  Vert.x Server                      │
│  ┌───────────────────────────────┐  │
│  │  REST API Handlers            │  │
│  ├───────────────────────────────┤  │
│  │  WebSocket Handler            │  │
│  ├───────────────────────────────┤  │
│  │  Static File Handler          │  │
│  │  (serves React build)         │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
         ↓
    PostgreSQL
```

Pros:
- Single deployment unit
- Simpler configuration
- No CORS issues
- Easier to secure

Cons:
- Coupled deployment
- Harder to scale UI separately

**Option 2: Separate Frontend Server**

```
┌─────────────────┐      ┌─────────────────┐
│  Nginx          │      │  Vert.x Server  │
│  (React build)  │ ───→ │  (REST + WS)    │
└─────────────────┘      └─────────────────┘
                                ↓
                           PostgreSQL
```

Pros:
- Independent scaling
- CDN for static assets
- Separate deployment cycles

Cons:
- More complex setup
- CORS configuration needed
- Additional infrastructure

**Recommendation**: Use **Option 1 (Embedded)** for MVP, migrate to **Option 2** if scaling needs arise.

#### Build Process

**Frontend Build**:
```bash
# Install dependencies
npm install

# Build for production
npm run build

# Output: dist/ directory with optimized assets
```

**Backend Build**:
```bash
# Build with Maven
mvn clean package

# Output: peegeeq-rest-1.0.0-fat.jar (includes UI assets)
```

**Integrated Build**:
```bash
# Build frontend
cd peegeeq-management-ui
npm run build

# Copy build to backend resources
cp -r dist/* ../peegeeq-rest/src/main/resources/webroot/

# Build backend with embedded UI
cd ../peegeeq-rest
mvn clean package
```

#### Deployment Configurations

**Development**:
```yaml
server:
  port: 8080
  host: localhost

database:
  host: localhost
  port: 5432
  database: peegeeq_dev
  user: dev
  password: dev

cors:
  enabled: true
  origins: ["http://localhost:5173"]  # Vite dev server

websocket:
  enabled: true
  path: /api/ws

logging:
  level: DEBUG
```

**Production**:
```yaml
server:
  port: 8080
  host: 0.0.0.0
  ssl:
    enabled: true
    keystore: /etc/peegeeq/keystore.jks
    password: ${SSL_PASSWORD}

database:
  host: ${DB_HOST}
  port: 5432
  database: peegeeq
  user: ${DB_USER}
  password: ${DB_PASSWORD}
  pool:
    size: 20

cors:
  enabled: false  # UI served from same origin

websocket:
  enabled: true
  path: /api/ws
  maxConnections: 1000

auth:
  enabled: true
  jwtSecret: ${JWT_SECRET}
  tokenExpiry: PT1H

rateLimit:
  enabled: true
  requestsPerMinute: 100

logging:
  level: INFO
  format: JSON
```

#### Container Deployment

**Dockerfile**:
```dockerfile
# Multi-stage build
FROM node:18 AS frontend-build
WORKDIR /app/ui
COPY peegeeq-management-ui/package*.json ./
RUN npm ci
COPY peegeeq-management-ui/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app
COPY pom.xml ./
COPY peegeeq-*/pom.xml ./peegeeq-*/
RUN mvn dependency:go-offline
COPY . ./
COPY --from=frontend-build /app/ui/dist ./peegeeq-rest/src/main/resources/webroot/
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /app/peegeeq-rest/target/peegeeq-rest-*-fat.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Docker Compose**:
```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: peegeeq
      POSTGRES_USER: peegeeq
      POSTGRES_PASSWORD: peegeeq
    volumes:
      - postgres-data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  peegeeq:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_HOST: postgres
      DB_USER: peegeeq
      DB_PASSWORD: peegeeq
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      - postgres
    volumes:
      - ./config:/app/config

volumes:
  postgres-data:
```

#### Kubernetes Deployment

**Deployment**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: peegeeq-management
spec:
  replicas: 3
  selector:
    matchLabels:
      app: peegeeq-management
  template:
    metadata:
      labels:
        app: peegeeq-management
    spec:
      containers:
      - name: peegeeq
        image: peegeeq/management-ui:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: DB_HOST
          value: postgres-service
        - name: DB_USER
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: jwt-secret
              key: secret
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /api/management/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /api/management/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

**Service**:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: peegeeq-management
spec:
  selector:
    app: peegeeq-management
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
```

**Ingress**:
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: peegeeq-management
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/websocket-services: peegeeq-management
spec:
  tls:
  - hosts:
    - peegeeq.example.com
    secretName: peegeeq-tls
  rules:
  - host: peegeeq.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: peegeeq-management
            port:
              number: 80
```

### Monitoring and Observability

#### Metrics

**Application Metrics** (Micrometer/Prometheus):
- HTTP request rate, latency, errors
- WebSocket connection count, message rate
- Queue operation metrics (send, receive, ack)
- Consumer group metrics (lag, rebalance)
- Event store metrics (query time, append rate)
- JVM metrics (heap, GC, threads)
- Database connection pool metrics

**Custom Metrics**:
```java
// Queue metrics
Counter.builder("peegeeq.queue.messages.sent")
    .tag("queue", queueName)
    .register(registry);

Gauge.builder("peegeeq.queue.depth", () -> getQueueDepth(queueName))
    .tag("queue", queueName)
    .register(registry);

Timer.builder("peegeeq.queue.operation.duration")
    .tag("operation", "send")
    .tag("queue", queueName)
    .register(registry);

// Consumer group metrics
Gauge.builder("peegeeq.consumer.group.lag", () -> getGroupLag(groupName))
    .tag("group", groupName)
    .register(registry);

// Event store metrics
Timer.builder("peegeeq.event.store.query.duration")
    .tag("store", storeName)
    .register(registry);
```

**Prometheus Scrape Config**:
```yaml
scrape_configs:
  - job_name: 'peegeeq-management'
    static_configs:
      - targets: ['peegeeq:8080']
    metrics_path: '/api/metrics'
    scrape_interval: 15s
```

#### Logging

**Structured Logging** (JSON format):
```json
{
  "timestamp": "2025-11-20T14:30:00.123Z",
  "level": "INFO",
  "logger": "dev.mars.peegeeq.rest.handlers.QueueApiHandler",
  "message": "Message published to queue",
  "context": {
    "setupId": "setup-1",
    "queueName": "orders",
    "messageId": "msg-12345",
    "userId": "user-789",
    "duration": 15
  }
}
```

**Log Levels**:
- **ERROR**: Errors requiring immediate attention
- **WARN**: Warnings, degraded performance
- **INFO**: Important business events (queue created, message published)
- **DEBUG**: Detailed debugging information
- **TRACE**: Very detailed tracing

**Log Aggregation**:
- Use ELK Stack (Elasticsearch, Logstash, Kibana)
- Or Loki + Grafana
- Or cloud-native solutions (CloudWatch, Stackdriver)

#### Tracing

**Distributed Tracing** (OpenTelemetry):
- Trace HTTP requests end-to-end
- Trace WebSocket message flow
- Trace database queries
- Correlate logs with traces

**Example Trace**:
```
Publish Message Request
├─ HTTP POST /api/queues/setup-1/orders/send (150ms)
│  ├─ Validate request (5ms)
│  ├─ Database insert (120ms)
│  │  └─ SQL INSERT (115ms)
│  └─ Broadcast notification (25ms)
│     └─ WebSocket send (20ms)
```

#### Dashboards

**Grafana Dashboards**:

1. **System Overview**
   - Total queues, consumer groups, event stores
   - Message rate (publish, deliver, ack)
   - Active connections
   - System health

2. **Queue Metrics**
   - Queue depth over time
   - Message rates per queue
   - Consumer count per queue
   - Error rate per queue

3. **Consumer Group Metrics**
   - Consumer lag over time
   - Partition assignment
   - Rebalance frequency
   - Member health

4. **Event Store Metrics**
   - Event append rate
   - Query latency
   - Correction rate
   - Storage size

5. **System Metrics**
   - CPU, memory, disk usage
   - JVM heap, GC
   - Database connection pool
   - HTTP request latency

#### Alerting

**Alert Rules** (Prometheus Alertmanager):

```yaml
groups:
  - name: peegeeq_alerts
    rules:
      # High queue depth
      - alert: HighQueueDepth
        expr: peegeeq_queue_depth > 10000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High queue depth on {{ $labels.queue }}"
          description: "Queue {{ $labels.queue }} has {{ $value }} messages"

      # High consumer lag
      - alert: HighConsumerLag
        expr: peegeeq_consumer_group_lag > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High consumer lag on {{ $labels.group }}"
          description: "Consumer group {{ $labels.group }} lag is {{ $value }}"

      # High error rate
      - alert: HighErrorRate
        expr: rate(peegeeq_queue_errors_total[5m]) > 1
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High error rate on {{ $labels.queue }}"
          description: "Error rate is {{ $value }} errors/sec"

      # Service down
      - alert: ServiceDown
        expr: up{job="peegeeq-management"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "PeeGeeQ Management service is down"
          description: "Service has been down for more than 1 minute"

      # High memory usage
      - alert: HighMemoryUsage
        expr: jvm_memory_used_bytes / jvm_memory_max_bytes > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High memory usage"
          description: "Memory usage is {{ $value | humanizePercentage }}"
```

### Testing Strategy

#### Unit Testing

**Frontend**:
- Test all React components with React Testing Library
- Test Redux slices and reducers
- Test utility functions
- Test custom hooks
- Target: 80%+ code coverage

**Backend**:
- Test all handlers with JUnit 5
- Test services with mocked dependencies
- Test models and validation
- Target: 80%+ code coverage

#### Integration Testing

**API Integration Tests**:
- Test all REST endpoints
- Test WebSocket connections
- Test database interactions
- Use Testcontainers for PostgreSQL

**Example**:
```java
@ExtendWith(VertxExtension.class)
@Testcontainers
class QueueApiIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void testPublishAndGetMessage(Vertx vertx, VertxTestContext testContext) {
        // Test implementation
    }
}
```

#### E2E Testing

**Playwright Tests**:
- Test critical user workflows
- Test across browsers (Chrome, Firefox, Safari)
- Test responsive design
- Visual regression testing

**Example Workflows**:
1. Create queue → publish message → get message → purge queue
2. Create consumer group → join members → view partitions → trigger rebalance
3. Query events → view timeline → select event → view details → create correction

#### Performance Testing

**Load Testing** (k6, JMeter):
- Test API endpoints under load
- Test WebSocket scalability
- Test database query performance
- Identify bottlenecks

**Example k6 Test**:
```javascript
import http from 'k6/http';
import { check } from 'k6';

export let options = {
  stages: [
    { duration: '2m', target: 100 },  // Ramp up to 100 users
    { duration: '5m', target: 100 },  // Stay at 100 users
    { duration: '2m', target: 0 },    // Ramp down
  ],
};

export default function () {
  let res = http.get('http://localhost:8080/api/management/queues');
  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });
}
```

---

## Conclusion

This comprehensive design document provides a complete blueprint for implementing the PeeGeeQ Management UI. The design leverages proven patterns from RabbitMQ's management console while incorporating PeeGeeQ's unique features, particularly the bitemporal event store.

### Key Takeaways

1. **RabbitMQ-Inspired Patterns**: The UI adopts battle-tested patterns from RabbitMQ for queue and consumer management, ensuring familiarity for users.

2. **Unique Differentiators**: The bitemporal event store browser is a unique feature that sets PeeGeeQ apart, enabling powerful temporal queries and event corrections.

3. **Phased Implementation**: The 4-phase roadmap ensures incremental delivery of value, with critical features first.

4. **Modern Tech Stack**: React + Vert.x provides a performant, scalable foundation for the management UI.

5. **Production-Ready**: Comprehensive considerations for security, deployment, monitoring, and testing ensure the UI is production-ready.

### Next Steps

1. **Review and Approval**: Stakeholder review of this design document
2. **Technical Spike**: Proof-of-concept for bitemporal timeline visualization
3. **API Contract Definition**: Finalize OpenAPI specifications
4. **Design Mockups**: Create high-fidelity mockups for key screens
5. **Phase 1 Kickoff**: Begin implementation of Queue Management Enhancement

### Success Metrics

The success of the PeeGeeQ Management UI will be measured by:

- **Adoption**: Number of teams using the UI for queue management
- **Productivity**: Reduction in time to debug message issues
- **Reliability**: Reduction in production incidents due to better visibility
- **User Satisfaction**: Positive feedback from developers and operators
- **Performance**: UI remains responsive with 1000+ queues and 10,000+ events

---

**Document Version**: 1.0
**Last Updated**: November 20, 2025
**Status**: Ready for Review


