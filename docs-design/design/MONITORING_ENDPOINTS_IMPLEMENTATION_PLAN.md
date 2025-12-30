# Real-Time Monitoring Endpoints Implementation Plan

**Status:** Planning Phase  
**Created:** 2025-12-30  
**Module:** peegeeq-rest  
**Target Version:** Phase 5 - Management UI Enhancement

---

## 1. Executive Summary

### Problem Statement
The PeeGeeQ Management UI attempts to connect to two monitoring endpoints that do not exist:
- **WebSocket**: `ws://localhost:8080/ws/monitoring` → Returns 404
- **SSE**: `/sse/metrics` → Returns 404

These endpoints are documented but never implemented, causing silent connection failures and browser console errors. The UI currently falls back to 30-second polling of `/api/v1/management/overview`.

### Proposed Solution
Implement both real-time monitoring endpoints to provide live system metrics, queue statistics, and connection status updates to the Management UI. This will enable true real-time dashboards with sub-second latency instead of 30-second polling.

### Success Criteria
1. ✅ WebSocket `/ws/monitoring` endpoint streaming system metrics every 5 seconds
2. ✅ SSE `/sse/metrics` endpoint providing real-time metrics updates
3. ✅ Management UI connects successfully without 404 errors
4. ✅ Dashboard updates in real-time (<5 second latency)
5. ✅ All 55 E2E tests continue to pass
6. ✅ Zero breaking changes to existing APIs

---

## 2. Current State Analysis

### 2.1 Existing Infrastructure

**Working Endpoints:**
```
✅ GET  /api/v1/health                    - REST health check
✅ GET  /api/v1/management/overview       - System overview (polled every 30s)
✅ GET  /api/v1/management/metrics        - Cached metrics (30s TTL)
✅ WS   /ws/health                        - WebSocket health check (one-shot)
✅ GET  /api/v1/sse/health                - SSE health check (one-shot)
✅ WS   /ws/queues/{setupId}/{queueName}  - Queue message streaming
✅ GET  /api/v1/queues/{setupId}/{queueName}/stream - Queue message SSE
```

**Missing Endpoints:**
```
❌ WS   /ws/monitoring                    - System monitoring stream
❌ GET  /sse/metrics                      - System metrics SSE stream
```

### 2.2 Data Sources Available

The `ManagementApiHandler` already collects comprehensive metrics:

**System Metrics** (via `getCachedSystemMetrics()`):
- `timestamp` - Current timestamp
- `uptime` - JVM uptime in milliseconds
- `memoryUsed` - Memory used in bytes
- `memoryTotal` - Total memory allocated
- `memoryMax` - Maximum memory available
- `cpuCores` - Available CPU cores
- `threadsActive` - Active thread count
- `messagesPerSecond` - Message throughput (calculated)
- `activeConnections` - Active consumer connections
- `totalMessages` - Total messages in all queues

**System Stats** (via `getSystemStats()`):
- `totalQueues` - Number of queues across all setups
- `totalConsumerGroups` - Number of consumer groups
- `totalEventStores` - Number of event stores
- `totalMessages` - Sum of messages in all queues
- `messagesPerSecond` - Calculated throughput
- `activeConnections` - Active consumer count
- `uptime` - Formatted uptime string

**Queue Summary** (via `getQueueSummary()`):
- Per-queue message counts
- Queue health status
- Queue types (NATIVE, OUTBOX)

**Consumer Group Summary** (via `getConsumerGroupSummary()`):
- Active consumer counts
- Consumer group states

**Event Store Summary** (via `getEventStoreSummary()`):
- Event store counts
- Event totals

**Recent Activity** (via `getRecentActivity()`):
- Last 20 system activities
- Action types, resources, timestamps

### 2.3 Existing Patterns

**WebSocket Pattern** (from `WebSocketHandler.handleQueueStream()`):
```java
1. Connection establishment with path parsing
2. Welcome message with connectionId
3. Text/binary message handlers
4. Ping/pong for keep-alive
5. Subscribe/unsubscribe commands
6. Real-time message streaming
7. Graceful cleanup on close
```

**SSE Pattern** (from `ServerSentEventsHandler.handleQueueStream()`):
```java
1. Set SSE headers (text/event-stream, no-cache, chunked)
2. Create connection wrapper
3. Handle Last-Event-ID for reconnection
4. Parse query parameters
5. Send connection event
6. Stream data events
7. Heartbeat timer (30s default)
8. Cleanup on connection close
```

---

## 3. Architecture & Design

### 3.1 Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    PeeGeeQ REST Server                      │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │         SystemMonitoringHandler (NEW)                  │  │
│  │  - Manages /ws/monitoring WebSocket endpoint          │  │
│  │  - Manages /sse/metrics SSE endpoint                  │  │
│  │  - Collects metrics from ManagementApiHandler         │  │
│  │  - Broadcasts to all connected clients                │  │
│  │  - Configurable update intervals                      │  │
│  └───────────────────────────────────────────────────────┘  │
│                          ↓                                    │
│  ┌───────────────────────────────────────────────────────┐  │
│  │         ManagementApiHandler (EXISTING)                │  │
│  │  - getCachedSystemMetrics() → metrics cache           │  │
│  │  - getSystemStats() → comprehensive stats             │  │
│  │  - getQueueSummary() → queue data                     │  │
│  │  - getConsumerGroupSummary() → consumer data          │  │
│  │  - getEventStoreSummary() → event store data          │  │
│  │  - getRecentActivity() → activity log                 │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                               │
└─────────────────────────────────────────────────────────────┘
                          ↓
        ┌─────────────────────────────────────┐
        │    Management UI (Frontend)         │
        │  - Overview.tsx connects via        │
        │    createSystemMonitoringService()  │
        │  - Updates charts in real-time      │
        │  - Graceful fallback to polling     │
        └─────────────────────────────────────┘
```

### 3.2 WebSocket Endpoint Design

**Endpoint**: `WS /ws/monitoring`

**Message Format**:
```json
{
  "type": "system_stats",
  "data": {
    "timestamp": 1704000000000,
    "uptime": 3600000,
    "memoryUsed": 536870912,
    "memoryTotal": 1073741824,
    "memoryMax": 4294967296,
    "cpuCores": 8,
    "threadsActive": 42,
    "messagesPerSecond": 125.5,
    "activeConnections": 15,
    "totalMessages": 10000,
    "queues": {
      "total": 5,
      "byType": {
        "NATIVE": 3,
        "OUTBOX": 2
      }
    },
    "consumerGroups": {
      "total": 3,
      "active": 3
    },
    "eventStores": {
      "total": 2
    }
  }
}
```

**Client Commands**:
```json
// Ping for keep-alive
{"type": "ping", "id": 123}

// Configure update interval (seconds)
{"type": "configure", "interval": 5}

// Request immediate update
{"type": "refresh"}
```

**Server Responses**:
```json
// Pong response
{"type": "pong", "id": 123, "timestamp": 1704000000000}

// Configuration acknowledged
{"type": "configured", "interval": 5}

// Error message
{"type": "error", "message": "Invalid configuration"}
```

### 3.3 SSE Endpoint Design

**Endpoint**: `GET /sse/metrics`

**Event Format**:
```
event: metrics
id: 1704000000000
data: {"timestamp":1704000000000,"uptime":3600000,"memoryUsed":536870912,...}

event: heartbeat
data: {"timestamp":1704000000000}
```

**Query Parameters**:
```
?interval=5          - Update interval in seconds (default: 5, min: 1, max: 60)
?heartbeat=30        - Heartbeat interval in seconds (default: 30)
```

---

## 4. Implementation Plan

### Phase 1: Core Handler Development (Week 1)

#### 4.1 Create SystemMonitoringHandler Class

**File**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SystemMonitoringHandler.java`

**Key Components**:
```java
public class SystemMonitoringHandler {
    // Dependencies
    private final ManagementApiHandler managementHandler;
    private final Vertx vertx;
    
    // Connection tracking
    private final Map<String, MonitoringConnection> wsConnections;
    private final Map<String, SSEMonitoringConnection> sseConnections;
    
    // Metrics broadcasting
    private long broadcastTimerId;
    private int broadcastInterval = 5000; // 5 seconds default
    
    // Public methods
    public void handleWebSocketMonitoring(ServerWebSocket ws);
    public void handleSSEMetrics(RoutingContext ctx);
    
    // Private methods
    private void startBroadcasting();
    private void broadcastMetrics();
    private JsonObject collectMetrics();
    private void sendToWebSocketClients(JsonObject metrics);
    private void sendToSSEClients(JsonObject metrics);
}
```

#### 4.2 Create Connection Wrapper Classes

**MonitoringConnection** (WebSocket):
```java
class MonitoringConnection {
    private final String connectionId;
    private final ServerWebSocket webSocket;
    private int updateInterval = 5; // seconds
    private long lastUpdate = 0;
    
    public boolean shouldSendUpdate();
    public void sendMetrics(JsonObject metrics);
    public void handleCommand(JsonObject command);
}
```

**SSEMonitoringConnection** (SSE):
```java
class SSEMonitoringConnection {
    private final String connectionId;
    private final HttpServerResponse response;
    private int updateInterval = 5; // seconds
    private long lastEventId = 0;
    private long heartbeatTimer;
    
    public void sendMetricsEvent(JsonObject metrics);
    public void sendHeartbeat();
    public void cleanup();
}
```

#### 4.3 Integrate with PeeGeeQRestServer

**File**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`

**Changes**:
```java
public class PeeGeeQRestServer extends AbstractVerticle {
    // Add new handler field
    private SystemMonitoringHandler monitoringHandler;
    
    @Override
    public void start(Promise<Void> startPromise) {
        // In webSocketHandler lambda, add:
        } else if (path.equals("/ws/monitoring")) {
            SystemMonitoringHandler handler = new SystemMonitoringHandler(
                managementHandler, vertx
            );
            handler.handleWebSocketMonitoring(webSocket);
        } else if (path.equals("/ws/health")) {
            // ... existing health check
        }
    }
    
    private Router createRouter() {
        // Add SSE metrics endpoint
        router.get("/sse/metrics").handler(ctx -> {
            SystemMonitoringHandler handler = new SystemMonitoringHandler(
                managementHandler, vertx
            );
            handler.handleSSEMetrics(ctx);
        });
    }
}
```

### Phase 2: Testing & Validation (Week 1-2)

#### 4.4 Unit Tests

**File**: `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/SystemMonitoringHandlerTest.java`

**Test Cases**:
- ✅ WebSocket connection establishment
- ✅ WebSocket message broadcasting
- ✅ WebSocket ping/pong handling
- ✅ WebSocket interval configuration
- ✅ SSE connection establishment
- ✅ SSE event streaming
- ✅ SSE heartbeat timing
- ✅ SSE reconnection with Last-Event-ID
- ✅ Metrics data accuracy
- ✅ Connection cleanup on close
- ✅ Multiple concurrent connections
- ✅ Error handling for invalid commands

#### 4.5 Integration Tests

**File**: `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/SystemMonitoringIntegrationTest.java`

**Test Cases**:
- ✅ End-to-end WebSocket streaming
- ✅ End-to-end SSE streaming
- ✅ Load test with 100+ concurrent connections
- ✅ Memory leak detection
- ✅ Graceful shutdown with active connections

#### 4.6 E2E UI Tests

**Files**:
- `peegeeq-management-ui/src/tests/e2e/specs/overview-system-monitoring.spec.ts`

**Test Cases**:
- ✅ WebSocket connection successful
- ✅ SSE connection successful
- ✅ Real-time data updates (verify DOM changes)
- ✅ Fallback to polling if connections fail
- ✅ Reconnection after network interruption
- ✅ No console errors or 404s

### Phase 3: Frontend Integration (Week 2)

#### 4.7 Update websocketService.ts

**File**: `peegeeq-management-ui/src/services/websocketService.ts`

**Changes**:
```typescript
// Already exists, just verify endpoint is correct
export const createSystemMonitoringService = () => {
  return new WebSocketService({
    url: 'ws://localhost:8080/ws/monitoring', // ✅ Now implemented
    onMessage: (message) => {
      console.log('System monitoring update:', message)
    },
    onConnect: () => {
      console.log('System monitoring connected')
    },
    onDisconnect: () => {
      console.log('System monitoring disconnected')
    }
  })
}

// Update SSE endpoint
export const createSystemMetricsSSE = (onUpdate: (metrics: any) => void) => {
  return new SSEService(
    '/sse/metrics', // ✅ Now implemented
    onUpdate,
    (error) => console.error('System metrics SSE error:', error)
  )
}
```

#### 4.8 Update Overview.tsx

**File**: `peegeeq-management-ui/src/pages/Overview.tsx`

**Changes**:
```typescript
// Already implemented, verify error handling
const initializeRealTimeServices = () => {
  // Initialize system monitoring WebSocket
  wsServiceRef.current = createSystemMonitoringService()
  wsServiceRef.current.config.onMessage = (message: any) => {
    if (message.type === 'system_stats') {
      updateChartData(message.data) // ✅ Real-time updates
    }
  }
  wsServiceRef.current.config.onConnect = () => setWebSocketStatus(true)
  wsServiceRef.current.config.onDisconnect = () => setWebSocketStatus(false)

  // Initialize metrics SSE
  sseServiceRef.current = createSystemMetricsSSE((metrics: any) => {
    updateChartData(metrics) // ✅ Real-time updates
    setSSEStatus(true)
  })

  // Connect with graceful fallback
  try {
    wsServiceRef.current.connect()
    sseServiceRef.current.connect()
  } catch (error) {
    console.log('Real-time services not available, using polling fallback')
    setWebSocketStatus(false)
    setSSEStatus(false)
  }
}
```

### Phase 4: Documentation & Deployment (Week 2)

#### 4.9 Update API Documentation

**Files**:
- `docs/PEEGEEQ_REST_API_REFERENCE.md`
- `peegeeq-rest/docs/PEEGEEQ_REST_MODULE_GUIDE.md`

**Sections to Add**:
```markdown
### WebSocket Monitoring Endpoint

**Endpoint**: `WS /ws/monitoring`  
**Purpose**: Real-time system metrics streaming  
**Handler**: `SystemMonitoringHandler.handleWebSocketMonitoring()`

**Message Types**:
- `system_stats` - System metrics broadcast (every 5s)
- `ping/pong` - Keep-alive messages
- `configure` - Change update interval
- `refresh` - Request immediate update

### SSE Metrics Endpoint

**Endpoint**: `GET /sse/metrics`  
**Purpose**: Server-Sent Events metrics stream  
**Handler**: `SystemMonitoringHandler.handleSSEMetrics()`

**Query Parameters**:
- `interval` - Update interval in seconds (1-60, default: 5)
- `heartbeat` - Heartbeat interval in seconds (default: 30)

**Event Types**:
- `metrics` - System metrics data
- `heartbeat` - Connection keep-alive
```

#### 4.10 Update QUICKSTART_GUIDE.md

**File**: `peegeeq-management-ui/docs/QUICKSTART_GUIDE.md`

**Add Section**:
```markdown
## Real-Time Monitoring Features

The Management UI now supports real-time monitoring via:
- **WebSocket** (`/ws/monitoring`) - Bi-directional monitoring
- **SSE** (`/sse/metrics`) - Server-to-client metrics stream

Dashboard updates automatically with <5 second latency.
Falls back to 30-second polling if real-time connections fail.
```

---

## 5. Technical Specifications

### 5.1 Performance Requirements

| Metric | Target | Rationale |
|--------|--------|-----------|
| Update Interval | 5 seconds | Balance between freshness and load |
| Max Connections | 1000 concurrent | Support large deployment monitoring |
| Memory per Connection | <50KB | Prevent memory exhaustion |
| CPU Overhead | <5% at 100 connections | Minimize backend impact |
| Message Latency | <100ms | Real-time user experience |
| Heartbeat Interval | 30 seconds | Detect stale connections |

### 5.2 Data Format Specification

**System Metrics Payload**:
```typescript
interface SystemMetrics {
  timestamp: number;           // Unix timestamp (ms)
  uptime: number;              // JVM uptime (ms)
  memoryUsed: number;          // Bytes
  memoryTotal: number;         // Bytes
  memoryMax: number;           // Bytes
  cpuCores: number;            // Available cores
  threadsActive: number;       // Active thread count
  messagesPerSecond: number;   // Throughput rate
  activeConnections: number;   // Consumer connections
  totalMessages: number;       // Messages in all queues
  queues: {
    total: number;
    byType: {
      [key: string]: number;   // NATIVE, OUTBOX, etc.
    };
  };
  consumerGroups: {
    total: number;
    active: number;
  };
  eventStores: {
    total: number;
  };
}
```

### 5.3 Security Considerations

1. **Authentication**: Same as existing REST endpoints (Phase 6 requirement)
2. **Rate Limiting**: Max 1 connection per client IP for monitoring endpoints
3. **Resource Limits**: Max 1000 concurrent monitoring connections
4. **Data Sanitization**: JSON encoding prevents injection attacks
5. **CORS**: Configurable via existing CORS handler

### 5.4 Error Handling

**WebSocket Errors**:
```json
{
  "type": "error",
  "code": "INVALID_INTERVAL",
  "message": "Interval must be between 1 and 60 seconds"
}
```

**SSE Errors**:
```
event: error
data: {"code":"CONNECTION_LIMIT","message":"Maximum connections exceeded"}
```

**Graceful Degradation**:
- If backend fails, frontend falls back to polling
- If metrics collection fails, send last known good data
- Connection drops trigger automatic reconnection (exponential backoff)

---

## 6. Implementation Timeline

### Week 1 (Days 1-7)
- **Day 1-2**: Create `SystemMonitoringHandler.java` skeleton
- **Day 3**: Implement WebSocket `/ws/monitoring` endpoint
- **Day 4**: Implement SSE `/sse/metrics` endpoint
- **Day 5**: Write unit tests (target 90% coverage)
- **Day 6-7**: Integration testing and bug fixes

### Week 2 (Days 8-14)
- **Day 8**: Frontend integration and E2E tests
- **Day 9**: Performance testing and optimization
- **Day 10**: Documentation updates
- **Day 11**: Code review and refactoring
- **Day 12-13**: User acceptance testing
- **Day 14**: Production deployment preparation

---

## 7. Success Metrics

### Technical Metrics
- ✅ Zero 404 errors in browser console
- ✅ WebSocket connection success rate > 99%
- ✅ SSE connection success rate > 99%
- ✅ Average update latency < 100ms
- ✅ 100% of existing E2E tests pass
- ✅ Unit test coverage > 90%
- ✅ Integration test coverage > 80%

### User Experience Metrics
- ✅ Dashboard updates in real-time (verified by DOM updates)
- ✅ No visible lag in chart animations
- ✅ Connection status indicators accurate
- ✅ Graceful fallback to polling (transparent to user)

---

## 8. Risks & Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Memory leak with long-lived connections | High | Medium | Implement connection limits, heartbeat timeouts, automated cleanup |
| Broadcasting delay with many connections | Medium | Medium | Use Vertx event bus for async broadcasting, configurable intervals |
| Frontend reconnection storms | Medium | Low | Exponential backoff, jitter, max reconnect attempts |
| Breaking changes to existing tests | High | Low | Run full test suite before merge, maintain backward compatibility |
| Performance degradation | Medium | Low | Load testing, connection limits, resource monitoring |

---

## 9. Future Enhancements

### Phase 5.1 (Optional)
- **Selective Metrics**: Allow clients to subscribe to specific metric types
- **Historical Data**: Buffer last 1000 data points for reconnection recovery
- **Compression**: Enable message compression for bandwidth efficiency
- **Filtering**: Support metric filtering by setup ID or queue name

### Phase 5.2 (Optional)
- **Alerting**: Push alerts when thresholds exceeded
- **Aggregation**: Multi-level aggregation (per-setup, per-queue, global)
- **Custom Metrics**: Allow plugins to contribute custom metrics
- **Export**: Support Prometheus exposition format

---

## 10. Appendix

### A. Related Files

**Backend**:
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/WebSocketHandler.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ServerSentEventsHandler.java`

**Frontend**:
- `peegeeq-management-ui/src/services/websocketService.ts`
- `peegeeq-management-ui/src/pages/Overview.tsx`
- `peegeeq-management-ui/src/stores/managementStore.ts`
- `peegeeq-management-ui/src/hooks/useRealTimeUpdates.ts`

**Tests**:
- `peegeeq-management-ui/src/tests/e2e/specs/overview-system-status.spec.ts`
- `peegeeq-management-ui/src/tests/e2e/specs/settings-ping-utilities.spec.ts`

### B. References

- **Existing Patterns**: `WebSocketHandler.handleQueueStream()`
- **Existing Patterns**: `ServerSentEventsHandler.handleQueueStream()`
- **Data Sources**: `ManagementApiHandler.getCachedSystemMetrics()`
- **WebSocket Spec**: RFC 6455
- **SSE Spec**: HTML5 Server-Sent Events
- **Vert.x Documentation**: https://vertx.io/docs/

---

## 11. Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2025-12-30 | Use 5-second default interval | Balance between real-time feel and server load |
| 2025-12-30 | Support both WebSocket and SSE | WebSocket for bi-directional, SSE for simplicity |
| 2025-12-30 | Reuse ManagementApiHandler data | Avoid duplicate metrics collection logic |
| 2025-12-30 | Implement connection limits | Prevent resource exhaustion |
| 2025-12-30 | Graceful fallback to polling | Ensure UI works even if real-time fails |

---

**Document Version:** 1.0  
**Last Updated:** 2025-12-30  
**Status:** Ready for Implementation
