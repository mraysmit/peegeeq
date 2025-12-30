# Real-Time Monitoring Endpoints Implementation Plan

**Status:** ✅ Ready for Implementation (Reviewed & Approved)  
**Created:** 2025-12-30  
**Reviewed:** 2025-12-30  
**Module:** peegeeq-rest  
**Target Version:** Phase 5 - Management UI Enhancement  
**Review Status:** APPROVED WITH RECOMMENDATIONS INCORPORATED

---

## 1. Executive Summary

### Problem Statement
The PeeGeeQ Management UI attempts to connect to two monitoring endpoints that do not exist:
- **WebSocket**: `ws://localhost:8080/ws/monitoring` → Returns 404
- **SSE**: `/sse/metrics` → Returns 404

These endpoints are documented but never implemented, causing silent connection failures and browser console errors. The UI currently falls back to 30-second polling of `/api/v1/management/overview`.

### Proposed Solution
Implement both real-time monitoring endpoints to provide live system metrics, queue statistics, and connection status updates to the Management UI. This will enable true real-time dashboards with sub-second latency instead of 30-second polling.

**Key Architecture Decision (Updated After Review):**
- ✅ Use **per-connection timers** with jitter (NOT global broadcast)
- ✅ Add **per-IP rate limiting** (max 10 connections per IP)
- ✅ Implement **connection idle timeout** (5 minutes)
- ✅ Cache **serialized JSON** to reduce GC pressure

### Success Criteria
1. ✅ WebSocket `/ws/monitoring` endpoint streaming system metrics every 5 seconds
2. ✅ SSE `/sse/metrics` endpoint providing real-time metrics updates
3. ✅ Management UI connects successfully without 404 errors
4. ✅ Dashboard updates in real-time (<5 second latency)
5. ✅ All 55 E2E tests continue to pass
6. ✅ Zero breaking changes to existing APIs
7. ✅ **NEW:** Support 100+ concurrent connections with <5% CPU overhead
8. ✅ **NEW:** Per-IP rate limiting prevents abuse
9. ✅ **NEW:** Graceful connection cleanup with no memory leaks

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

### 2.2 Data Sources Available (VERIFIED)

**⚠️ IMPORTANT:** After code review, `getCachedSystemMetrics()` and `getSystemStats()` serve different purposes:

**`getCachedSystemMetrics()` - JVM/System Metrics Only**  
Location: `ManagementApiHandler.java:294-324`  
Returns:
- `timestamp` - Current timestamp
- `uptime` - JVM uptime in milliseconds
- `memoryUsed` - Memory used in bytes
- `memoryTotal` - Total memory allocated
- `memoryMax` - Maximum memory available
- `cpuCores` - Available CPU cores
- `threadsActive` - Active thread count
- `messagesPerSecond` - ⚠️ **PLACEHOLDER 0.0** (not real data)
- `activeConnections` - ⚠️ **PLACEHOLDER 0** (not real data)
- `totalMessages` - ⚠️ **PLACEHOLDER 0** (not real data)

**`getSystemStats()` - Application/Business Metrics**  
Location: `ManagementApiHandler.java:332-351`  
Returns:
- `totalQueues` - Number of queues across all setups
- `totalConsumerGroups` - Number of consumer groups
- `totalEventStores` - Number of event stores
- `totalMessages` - Sum of messages in all queues (REAL data)
- `messagesPerSecond` - Calculated throughput (REAL data)
- `activeConnections` - Active consumer count (REAL data)
- `uptime` - Formatted uptime string

**✅ SOLUTION:** Monitoring endpoints must combine BOTH data sources:
```java
JsonObject metrics = new JsonObject()
    // From getCachedSystemMetrics()
    .put("timestamp", cached.getLong("timestamp"))
    .put("uptime", cached.getLong("uptime"))
    .put("memory", new JsonObject()
        .put("used", cached.getLong("memoryUsed"))
        .put("total", cached.getLong("memoryTotal"))
        .put("max", cached.getLong("memoryMax")))
    .put("system", new JsonObject()
        .put("cpuCores", cached.getInteger("cpuCores"))
        .put("threadsActive", cached.getInteger("threadsActive")))
    // From getSystemStats() - REAL business metrics
    .put("messaging", new JsonObject()
        .put("totalQueues", stats.getInteger("totalQueues"))
        .put("totalConsumerGroups", stats.getInteger("totalConsumerGroups"))
        .put("totalEventStores", stats.getInteger("totalEventStores"))
        .put("totalMessages", stats.getInteger("totalMessages"))
        .put("messagesPerSecond", stats.getDouble("messagesPerSecond"))
        .put("activeConnections", stats.getInteger("activeConnections")));
```

**⚠️ REQUIRED CHANGE:**  
`ManagementApiHandler.getSystemStats()` is currently **private** - must make it **public**:
```java
// Change from:
private JsonObject getSystemStats() { ... }

// To:
public JsonObject getSystemStats() { ... }
```

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

### 2.3 Existing Patterns (VERIFIED & CRITICAL LEARNING)

**🔴 CRITICAL: Per-Connection Pattern (NOT Global Broadcast)**

Review of existing `WebSocketHandler.handleQueueStream()` reveals the correct pattern:

```java
// ✅ CORRECT PATTERN - Each connection gets its own timer
private void startMessageStreaming(WebSocketConnection connection) {
    // Per-connection polling with individual timer
    long timerId = vertx.setPeriodic(
        pollInterval,  // Each connection can have different interval
        id -> pollAndSendMessages(connection)  // Sends ONLY to this connection
    );
    connection.setMessageTimerId(timerId);
}
```

**❌ WRONG APPROACH (Initially Planned):**
```java
// DON'T DO THIS - Creates broadcast storm
private void startBroadcasting() {
    broadcastTimerId = vertx.setPeriodic(5000, id -> {
        JsonObject metrics = collectMetrics();
        // Sends to ALL connections at once - BAD!
        for (MonitoringConnection conn : connections.values()) {
            conn.sendMetrics(metrics);
        }
    });
}
```

**Why Per-Connection Pattern is Superior:**
1. ✅ Clients can configure their own update intervals
2. ✅ No synchronized polling spikes (thundering herd)
3. ✅ Better load distribution over time
4. ✅ Graceful degradation when clients slow down
5. ✅ Individual connection cleanup (no orphaned timers)

**WebSocket Pattern** (from `WebSocketHandler.handleQueueStream()`):
```java
1. Connection establishment with path parsing
2. Welcome message with connectionId
3. Text/binary message handlers
4. Ping/pong for keep-alive
5. Subscribe/unsubscribe commands
6. ✅ PER-CONNECTION message streaming (each has own timer)
7. Graceful cleanup on close (cancel individual timer)
```

**SSE Pattern** (from `ServerSentEventsHandler.handleQueueStream()`):
```java
1. Set SSE headers (text/event-stream, no-cache, chunked)
2. Create connection wrapper
3. Handle Last-Event-ID for reconnection
4. Parse query parameters
5. Send connection event
6. ✅ PER-CONNECTION stream (each has own timer)
7. Heartbeat timer (30s default)
8. Cleanup on connection close (cancel individual timers)
```

**Key Takeaway:** 
Both existing handlers use **per-connection timers**, NOT global broadcasting. The monitoring endpoints MUST follow the same pattern.

---

## 3. Architecture & Design (UPDATED FOR HEXAGONAL ARCHITECTURE COMPLIANCE)

### 3.1 Component Overview - Hexagonal Architecture Alignment

**Following PeeGeeQ's Layered Architecture Rules (see PEEGEEQ_CALL_PROPAGATION_GUIDE.md):**

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         MANAGEMENT UI LAYER                              │
│                        peegeeq-management-ui                             │
│                    (React/TypeScript web application)                    │
│                  Uses: peegeeq-rest via HTTP REST client                 │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ HTTP/WebSocket/SSE
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                            REST LAYER (peegeeq-rest)                     │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │         SystemMonitoringHandler (NEW)                               │ │
│  │  - Handles /ws/monitoring WebSocket endpoint                        │ │
│  │  - Handles /sse/metrics SSE endpoint                                │ │
│  │  - ✅ Per-connection timers with jitter                             │ │
│  │  - ✅ Per-IP rate limiting (max 10/IP)                              │ │
│  │  - ✅ Connection idle timeout (5 min)                               │ │
│  │  - ✅ Cached JSON to reduce GC pressure                             │ │
│  │  - ✅ Only depends on: DatabaseSetupService (from peegeeq-api)      │ │
│  │  - ❌ DOES NOT depend on: ManagementApiHandler (sibling class)      │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                          ↓                                                │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │         ManagementApiHandler (EXISTING)                             │ │
│  │  - Handles /api/v1/management/* REST endpoints                      │ │
│  │  - Uses DatabaseSetupService for data access                        │ │
│  │  - Implements metrics caching (30s TTL)                             │ │
│  │  - ✅ Only depends on: DatabaseSetupService (from peegeeq-api)      │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ Calls DatabaseSetupService interface
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                 RUNTIME/COMPOSITION LAYER (peegeeq-runtime)              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │         DatabaseSetupService (FACADE)                               │ │
│  │  - Provides access to all PeeGeeQ services                          │ │
│  │  - getQueueFactories() → native/outbox queue implementations        │ │
│  │  - getEventStoreFactories() → bitemporal event stores               │ │
│  │  - getSubscriptionService() → subscription management                │ │
│  │  - getHealthService() → health monitoring                            │ │
│  │  - getDeadLetterService() → DLQ operations                          │ │
│  │  - getAllActiveSetupIds() → list of active database setups          │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                           │
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
      │                    DATABASE LAYER (peegeeq-db)           │
      │   (PostgreSQL connectivity + service implementations)    │
      │   - Implements: HealthService, DeadLetterService, etc.   │
      └──────────────────────────────────────────────────────────┘
                                   ▲
                                   │
      ┌──────────────────────────────────────────────────────────┐
      │              CONTRACTS LAYER (peegeeq-api)               │
      │  Interfaces: DatabaseSetupService, QueueFactory,         │
      │              HealthService, SubscriptionService, etc.    │
      │  DTOs: QueueStats, HealthStatusInfo, etc.                │
      │              NO implementations                          │
      └──────────────────────────────────────────────────────────┘
```

### 3.2 Hexagonal Architecture Compliance

**🔴 CRITICAL DESIGN PRINCIPLE:**

`SystemMonitoringHandler` **MUST NOT** call `ManagementApiHandler` directly. This would violate the hexagonal architecture because:

1. **Handlers are siblings** - Both are REST layer components
2. **No cross-handler dependencies** - REST handlers should only depend on `peegeeq-api` interfaces and `peegeeq-runtime` services
3. **Shared logic goes in services** - Common data collection belongs in the service layer, not REST handlers

**✅ CORRECT APPROACH:**

- `SystemMonitoringHandler` calls `DatabaseSetupService` directly (same as `ManagementApiHandler` does)
- Both handlers independently aggregate data from the same service interfaces
- No coupling between REST handlers
- Clean separation of concerns

**Data Flow for Monitoring Endpoints:**

```
WebSocket Client → SystemMonitoringHandler → DatabaseSetupService (facade)
                                                      ↓
                                   ┌─────────────────┼─────────────────┐
                                   │                 │                 │
                                   ▼                 ▼                 ▼
                            QueueFactory      HealthService    EventStoreFactory
                                   │                 │                 │
                                   └─────────────────┼─────────────────┘
                                                     ↓
                                              PostgreSQL Database
```

**🔴 CRITICAL ARCHITECTURE CHANGES:**

1. **Per-Connection Streaming** (NOT Global Broadcast)
   - Each connection gets its own `vertx.setPeriodic()` timer
   - Jitter added (random 0-1000ms) to prevent synchronized spikes
   - Individual timer cleanup on disconnect

2. **Connection Limiting**
   - Global max: 1000 connections
   - Per-IP max: 10 connections
   - Rate limiting: 1 connection attempt per 5 seconds per IP

3. **Performance Optimization**
   - Cache serialized JSON for 5 seconds
   - Reuse cached metrics across connections
   - Idle timeout: 5 minutes without ping

4. **Data Access Pattern**
   - SystemMonitoringHandler uses `DatabaseSetupService` directly
   - Calls same service methods as ManagementApiHandler
   - No handler-to-handler dependencies

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

## 4. Implementation Plan (UPDATED AFTER REVIEW)

### Phase 1: Core Handler Development (Week 1)

#### 4.1 Create SystemMonitoringHandler Class

**File**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SystemMonitoringHandler.java`

**✅ CORRECTED Architecture** (Hexagonal Architecture Compliance):

```java
package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles real-time system monitoring via WebSocket and SSE endpoints.
 * 
 * <p><strong>Hexagonal Architecture Compliance:</strong>
 * - Only depends on peegeeq-api interfaces (DatabaseSetupService, QueueFactory, HealthService)
 * - Does NOT depend on ManagementApiHandler (sibling REST handler)
 * - Follows same data access patterns as other REST handlers
 * - All business logic resides in service layer (peegeeq-runtime)
 * 
 * <p><strong>Design Pattern:</strong> Per-connection streaming with jitter
 * - Each WebSocket/SSE connection gets its own timer
 * - Random jitter (0-1000ms) prevents synchronized load spikes
 * - Individual cleanup on disconnect (no resource leaks)
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-30
 * @version 1.0
 */
public class SystemMonitoringHandler {
    private static final Logger log = LoggerFactory.getLogger(SystemMonitoringHandler.class);
    
    // Configuration
    private static final int MAX_CONNECTIONS = 1000;
    private static final int MAX_CONNECTIONS_PER_IP = 10;
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int MIN_INTERVAL_SECONDS = 1;
    private static final int MAX_INTERVAL_SECONDS = 60;
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    private static final long CACHE_TTL_MS = 5000; // 5 seconds
    private static final int JITTER_MS = 1000; // Random 0-1000ms
    
    // Dependencies (ONLY from peegeeq-api and peegeeq-runtime)
    private final DatabaseSetupService setupService;
    private final Vertx vertx;
    private final Random random;
    
    // Connection tracking
    private final Map<String, WebSocketConnection> wsConnections = new ConcurrentHashMap<>();
    private final Map<String, SSEConnection> sseConnections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionsByIp = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    
    // Metrics caching
    private volatile CachedMetrics cachedMetrics;
    
    /**
     * Cached metrics with TTL to reduce GC pressure
     */
    private static class CachedMetrics {
        final JsonObject json;
        final long timestamp;
        
        CachedMetrics(JsonObject json, long timestamp) {
            this.json = json;
            this.timestamp = timestamp;
        }
        
        boolean isExpired(long now) {
            return (now - timestamp) > CACHE_TTL_MS;
        }
    }
    
    public SystemMonitoringHandler(DatabaseSetupService setupService, Vertx vertx) {
        this.setupService = setupService;
        this.vertx = vertx;
        this.random = new Random();
    }
    
    /**
     * Handle WebSocket monitoring connection at /ws/monitoring
     */
    public void handleWebSocketMonitoring(ServerWebSocket ws) {
        String clientIp = getClientIp(ws.remoteAddress().toString());
        
        // Check global connection limit
        if (totalConnections.get() >= MAX_CONNECTIONS) {
            log.warn("Rejecting WebSocket connection from {}: max connections reached", clientIp);
            ws.reject(503);
            return;
        }
        
        // Check per-IP connection limit
        AtomicInteger ipConnections = connectionsByIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipConnections.get() >= MAX_CONNECTIONS_PER_IP) {
            log.warn("Rejecting WebSocket connection from {}: per-IP limit reached", clientIp);
            ws.reject(429);
            return;
        }
        
        // Accept connection
        String connectionId = generateConnectionId();
        WebSocketConnection connection = new WebSocketConnection(connectionId, ws, clientIp);
        
        ws.accept();
        log.info("WebSocket monitoring connected: {} from {}", connectionId, clientIp);
        
        // Track connection
        wsConnections.put(connectionId, connection);
        totalConnections.incrementAndGet();
        ipConnections.incrementAndGet();
        
        // Start per-connection streaming with jitter
        long jitter = random.nextInt(JITTER_MS);
        long intervalMs = connection.updateInterval * 1000L + jitter;
        
        long timerId = vertx.setPeriodic(intervalMs, id -> {
            try {
                JsonObject metrics = getOrUpdateCachedMetrics();
                connection.sendMetrics(metrics);
                connection.lastActivity = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Error sending metrics to {}", connectionId, e);
            }
        });
        
        connection.timerId = timerId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles real-time system monitoring via WebSocket and SSE endpoints.
 * Uses per-connection streaming with jitter to prevent synchronized load spikes.
 */
public class SystemMonitoringHandler {
    private static final Logger log = LoggerFactory.getLogger(SystemMonitoringHandler.class);
    
    // Configuration
    private static final int MAX_CONNECTIONS = 1000;
    private static final int MAX_CONNECTIONS_PER_IP = 10;
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int MIN_INTERVAL_SECONDS = 1;
    private static final int MAX_INTERVAL_SECONDS = 60;
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    private static final long CACHE_TTL_MS = 5000; // 5 seconds
    private static final int JITTER_MS = 1000; // Random 0-1000ms
    
    // Dependencies
    private final ManagementApiHandler managementHandler;
    private final Vertx vertx;
    private final Random random;
    
    // Connection tracking
    private final Map<String, WebSocketConnection> wsConnections = new ConcurrentHashMap<>();
    private final Map<String, SSEConnection> sseConnections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionsByIp = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    
    // Metrics caching
    private volatile CachedMetrics cachedMetrics;
    
    /**
     * Cached metrics with TTL to reduce GC pressure
     */
    private static class CachedMetrics {
        final JsonObject json;
        final long timestamp;
        
        CachedMetrics(JsonObject json, long timestamp) {
            this.json = json;
            this.timestamp = timestamp;
        }
        
        boolean isExpired(long now) {
            return (now - timestamp) > CACHE_TTL_MS;
        }
    }
    
    public SystemMonitoringHandler(ManagementApiHandler managementHandler, Vertx vertx) {
        this.managementHandler = managementHandler;
        this.vertx = vertx;
        this.random = new Random();
    }
    
    /**
     * Handle WebSocket monitoring connection at /ws/monitoring
     */
    public void handleWebSocketMonitoring(ServerWebSocket ws) {
        String clientIp = getClientIp(ws.remoteAddress().toString());
        
        // Check global connection limit
        if (totalConnections.get() >= MAX_CONNECTIONS) {
            log.warn("Rejecting WebSocket connection from {}: max connections reached", clientIp);
            ws.reject(503);
            return;
        }
        
        // Check per-IP connection limit
        AtomicInteger ipConnections = connectionsByIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipConnections.get() >= MAX_CONNECTIONS_PER_IP) {
            log.warn("Rejecting WebSocket connection from {}: per-IP limit reached", clientIp);
            ws.reject(429);
            return;
        }
        
        // Accept connection
        String connectionId = generateConnectionId();
        WebSocketConnection connection = new WebSocketConnection(connectionId, ws, clientIp);
        
        ws.accept();
        log.info("WebSocket monitoring connected: {} from {}", connectionId, clientIp);
        
        // Track connection
        wsConnections.put(connectionId, connection);
        totalConnections.incrementAndGet();
        ipConnections.incrementAndGet();
        
        // Start per-connection streaming with jitter
        long jitter = random.nextInt(JITTER_MS);
        long intervalMs = connection.updateInterval * 1000L + jitter;
        
        long timerId = vertx.setPeriodic(intervalMs, id -> {
            try {
                JsonObject metrics = getOrUpdateCachedMetrics();
                connection.sendMetrics(metrics);
                connection.lastActivity = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Error sending metrics to {}", connectionId, e);
            }
        });
        
        connection.timerId = timerId;
        
        // Handle incoming messages (ping, configure, refresh)
        ws.textMessageHandler(text -> {
            try {
                JsonObject command = new JsonObject(text);
                handleWebSocketCommand(connection, command);
                connection.lastActivity = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Error handling WebSocket command from {}", connectionId, e);
                JsonObject error = new JsonObject()
                    .put("type", "error")
                    .put("message", "Invalid command format");
                ws.writeTextMessage(error.encode());
            }
        });
        
        // Handle disconnect
        ws.closeHandler(v -> {
            log.info("WebSocket monitoring disconnected: {}", connectionId);
            cleanupWebSocketConnection(connectionId, clientIp);
        });
        
        ws.exceptionHandler(err -> {
            log.error("WebSocket error for {}", connectionId, err);
            cleanupWebSocketConnection(connectionId, clientIp);
        });
        
        // Idle timeout check
        long idleCheckerId = vertx.setPeriodic(60000, id -> {
            long now = System.currentTimeMillis();
            if ((now - connection.lastActivity) > IDLE_TIMEOUT_MS) {
                log.info("Closing idle WebSocket connection: {}", connectionId);
                ws.close();
            }
        });
        
        connection.idleCheckerId = idleCheckerId;
    }
    
    /**
     * Handle SSE monitoring connection at /sse/metrics
     */
    public void handleSSEMetrics(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        HttpServerResponse response = ctx.response();
        String clientIp = getClientIp(request.remoteAddress().toString());
        
        // Check global connection limit
        if (totalConnections.get() >= MAX_CONNECTIONS) {
            log.warn("Rejecting SSE connection from {}: max connections reached", clientIp);
            ctx.response().setStatusCode(503).end("Max connections reached");
            return;
        }
        
        // Check per-IP connection limit
        AtomicInteger ipConnections = connectionsByIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipConnections.get() >= MAX_CONNECTIONS_PER_IP) {
            log.warn("Rejecting SSE connection from {}: per-IP limit reached", clientIp);
            ctx.response().setStatusCode(429).end("Per-IP connection limit reached");
            return;
        }
        
        // Parse query parameters
        int interval = parseInterval(request.getParam("interval"));
        int heartbeat = parseHeartbeat(request.getParam("heartbeat"));
        
        // Setup SSE response headers
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .setChunked(true);
        
        // Create connection
        String connectionId = generateConnectionId();
        SSEConnection connection = new SSEConnection(connectionId, response, clientIp, interval);
        
        log.info("SSE monitoring connected: {} from {} (interval={}s, heartbeat={}s)", 
                 connectionId, clientIp, interval, heartbeat);
        
        // Track connection
        sseConnections.put(connectionId, connection);
        totalConnections.incrementAndGet();
        ipConnections.incrementAndGet();
        
        // Start per-connection metrics streaming with jitter
        long jitter = random.nextInt(JITTER_MS);
        long intervalMs = interval * 1000L + jitter;
        
        long metricsTimerId = vertx.setPeriodic(intervalMs, id -> {
            try {
                JsonObject metrics = getOrUpdateCachedMetrics();
                connection.sendMetricsEvent(metrics);
                connection.lastActivity = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Error sending SSE metrics to {}", connectionId, e);
                cleanupSSEConnection(connectionId, clientIp);
            }
        });
        
        connection.metricsTimerId = metricsTimerId;
        
        // Start heartbeat timer
        long heartbeatTimerId = vertx.setPeriodic(heartbeat * 1000L, id -> {
            try {
                connection.sendHeartbeat();
            } catch (Exception e) {
                log.error("Error sending SSE heartbeat to {}", connectionId, e);
                cleanupSSEConnection(connectionId, clientIp);
            }
        });
        
        connection.heartbeatTimerId = heartbeatTimerId;
        
        // Handle client disconnect
        response.closeHandler(v -> {
            log.info("SSE monitoring disconnected: {}", connectionId);
            cleanupSSEConnection(connectionId, clientIp);
        });
        
        response.exceptionHandler(err -> {
            log.error("SSE error for {}", connectionId, err);
            cleanupSSEConnection(connectionId, clientIp);
        });
        
        // Idle timeout check
        long idleCheckerId = vertx.setPeriodic(60000, id -> {
            long now = System.currentTimeMillis();
            if ((now - connection.lastActivity) > IDLE_TIMEOUT_MS) {
                log.info("Closing idle SSE connection: {}", connectionId);
                response.close();
            }
        });
        
        connection.idleCheckerId = idleCheckerId;
    }
    
    /**
     * Helper methods
     */
    
    private JsonObject getOrUpdateCachedMetrics() {
        long now = System.currentTimeMillis();
        
        if (cachedMetrics == null || cachedMetrics.isExpired(now)) {
            // ✅ HEXAGONAL ARCHITECTURE: Use DatabaseSetupService directly
            // Do NOT call ManagementApiHandler methods
            JsonObject metrics = collectMetricsFromServices();
            cachedMetrics = new CachedMetrics(metrics, now);
        }
        
        return cachedMetrics.json;
    }
    
    /**
     * Collect metrics from service layer (peegeeq-runtime services).
     * 
     * <p><strong>Architecture Note:</strong>
     * This method replicates data collection logic from ManagementApiHandler
     * but does NOT call ManagementApiHandler directly. Both handlers independently
     * access the same DatabaseSetupService facade, maintaining clean separation.
     */
    private JsonObject collectMetricsFromServices() {
        try {
            // JVM metrics
            Runtime runtime = Runtime.getRuntime();
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            
            // Get active setups from service layer
            Set<String> activeSetupIds = setupService.getAllActiveSetupIds().join();
            
            // Aggregate queue statistics
            int totalQueues = 0;
            long totalMessages = 0;
            int activeConnections = 0;
            
            for (String setupId : activeSetupIds) {
                try {
                    DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();
                    
                    if (setupResult.getStatus() == DatabaseSetupStatus.ACTIVE) {
                        Map<String, QueueFactory> queueFactories = setupResult.getQueueFactories();
                        totalQueues += queueFactories.size();
                        
                        // Aggregate queue stats using service interfaces
                        for (QueueFactory factory : queueFactories.values()) {
                            try {
                                var stats = factory.getStats().toCompletionStage().toCompletableFuture().join();
                                totalMessages += stats.getPendingCount();
                                activeConnections += stats.getActiveConsumers();
                            } catch (Exception e) {
                                log.debug("Could not get stats for queue", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not process setup {}", setupId, e);
                }
            }
            
            // Calculate messages per second (simplified - could use time-series data)
            double messagesPerSecond = totalMessages > 0 ? totalMessages / (uptime / 1000.0) : 0.0;
            
            return new JsonObject()
                .put("timestamp", now)
                .put("uptime", uptime)
                .put("memoryUsed", runtime.totalMemory() - runtime.freeMemory())
                .put("memoryTotal", runtime.totalMemory())
                .put("memoryMax", runtime.maxMemory())
                .put("cpuCores", runtime.availableProcessors())
                .put("threadsActive", Thread.activeCount())
                .put("messagesPerSecond", messagesPerSecond)
                .put("activeConnections", activeConnections)
                .put("totalMessages", totalMessages)
                .put("totalQueues", totalQueues)
                .put("totalSetups", activeSetupIds.size());
                
        } catch (Exception e) {
            log.error("Error collecting metrics from services", e);
            // Return minimal metrics on error
            Runtime runtime = Runtime.getRuntime();
            return new JsonObject()
                .put("timestamp", System.currentTimeMillis())
                .put("uptime", ManagementFactory.getRuntimeMXBean().getUptime())
                .put("memoryUsed", runtime.totalMemory() - runtime.freeMemory())
                .put("error", "Could not collect full metrics: " + e.getMessage());
        }
    }
    
    private void handleWebSocketCommand(WebSocketConnection connection, JsonObject command) {
        String type = command.getString("type");
        
        switch (type) {
            case "ping":
                handlePing(connection, command);
                break;
            case "configure":
                handleConfigure(connection, command);
                break;
            case "refresh":
                JsonObject metrics = getOrUpdateCachedMetrics();
                connection.sendMetrics(metrics);
                break;
            default:
                JsonObject error = new JsonObject()
                    .put("type", "error")
                    .put("message", "Unknown command type: " + type);
                connection.webSocket.writeTextMessage(error.encode());
        }
    }
    
    private void handlePing(WebSocketConnection connection, JsonObject command) {
        JsonObject pong = new JsonObject()
            .put("type", "pong")
            .put("timestamp", System.currentTimeMillis());
        
        if (command.containsKey("id")) {
            pong.put("id", command.getValue("id"));
        }
        
        connection.webSocket.writeTextMessage(pong.encode());
    }
    
    private void handleConfigure(WebSocketConnection connection, JsonObject command) {
        int interval = command.getInteger("interval", DEFAULT_INTERVAL_SECONDS);
        
        if (interval < MIN_INTERVAL_SECONDS || interval > MAX_INTERVAL_SECONDS) {
            JsonObject error = new JsonObject()
                .put("type", "error")
                .put("message", "Invalid interval: must be between " + MIN_INTERVAL_SECONDS + " and " + MAX_INTERVAL_SECONDS);
            connection.webSocket.writeTextMessage(error.encode());
            return;
        }
        
        // Cancel old timer
        if (connection.timerId > 0) {
            vertx.cancelTimer(connection.timerId);
        }
        
        // Start new timer with jitter
        long jitter = random.nextInt(JITTER_MS);
        long intervalMs = interval * 1000L + jitter;
        
        long timerId = vertx.setPeriodic(intervalMs, id -> {
            try {
                JsonObject metrics = getOrUpdateCachedMetrics();
                connection.sendMetrics(metrics);
            } catch (Exception e) {
                log.error("Error sending metrics", e);
            }
        });
        
        connection.timerId = timerId;
        connection.updateInterval = interval;
        
        // Send confirmation
        JsonObject confirmation = new JsonObject()
            .put("type", "configured")
            .put("interval", interval)
            .put("timestamp", System.currentTimeMillis());
        connection.webSocket.writeTextMessage(confirmation.encode());
    }
    
    private void cleanupWebSocketConnection(String connectionId, String clientIp) {
        WebSocketConnection connection = wsConnections.remove(connectionId);
        if (connection != null) {
            if (connection.timerId > 0) vertx.cancelTimer(connection.timerId);
            if (connection.idleCheckerId > 0) vertx.cancelTimer(connection.idleCheckerId);
        }
        
        totalConnections.decrementAndGet();
        
        AtomicInteger ipCount = connectionsByIp.get(clientIp);
        if (ipCount != null) {
            ipCount.decrementAndGet();
            if (ipCount.get() <= 0) {
                connectionsByIp.remove(clientIp);
            }
        }
    }
    
    private void cleanupSSEConnection(String connectionId, String clientIp) {
        SSEConnection connection = sseConnections.remove(connectionId);
        if (connection != null) {
            if (connection.metricsTimerId > 0) vertx.cancelTimer(connection.metricsTimerId);
            if (connection.heartbeatTimerId > 0) vertx.cancelTimer(connection.heartbeatTimerId);
            if (connection.idleCheckerId > 0) vertx.cancelTimer(connection.idleCheckerId);
        }
        
        totalConnections.decrementAndGet();
        
        AtomicInteger ipCount = connectionsByIp.get(clientIp);
        if (ipCount != null) {
            ipCount.decrementAndGet();
            if (ipCount.get() <= 0) {
                connectionsByIp.remove(clientIp);
            }
        }
    }
    
    private int parseInterval(String param) {
        if (param == null) return DEFAULT_INTERVAL_SECONDS;
        try {
            int interval = Integer.parseInt(param);
            return Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, interval));
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_SECONDS;
        }
    }
    
    private int parseHeartbeat(String param) {
        if (param == null) return 30;
        try {
            return Math.max(10, Integer.parseInt(param));
        } catch (NumberFormatException e) {
            return 30;
        }
    }
    
    private String generateConnectionId() {
        return "monitoring-" + System.currentTimeMillis() + "-" + random.nextInt(10000);
    }
    
    private String getClientIp(String remoteAddress) {
        // Extract IP from "host:port" format
        int colonIndex = remoteAddress.lastIndexOf(':');
        return colonIndex > 0 ? remoteAddress.substring(0, colonIndex) : remoteAddress;
    }
    
    /**
     * Connection wrapper classes
     */
    
    private static class WebSocketConnection {
        final String connectionId;
        final ServerWebSocket webSocket;
        final String clientIp;
        int updateInterval = DEFAULT_INTERVAL_SECONDS;
        long timerId = -1;
        long idleCheckerId = -1;
        long lastActivity = System.currentTimeMillis();
        
        WebSocketConnection(String connectionId, ServerWebSocket webSocket, String clientIp) {
            this.connectionId = connectionId;
            this.webSocket = webSocket;
            this.clientIp = clientIp;
        }
        
        void sendMetrics(JsonObject metrics) {
            JsonObject message = new JsonObject()
                .put("type", "system_stats")
                .put("data", metrics)
                .put("timestamp", System.currentTimeMillis());
            webSocket.writeTextMessage(message.encode());
        }
    }
    
    private static class SSEConnection {
        final String connectionId;
        final HttpServerResponse response;
        final String clientIp;
        int updateInterval;
        long metricsTimerId = -1;
        long heartbeatTimerId = -1;
        long idleCheckerId = -1;
        long lastActivity = System.currentTimeMillis();
        long eventId = 0;
        
        SSEConnection(String connectionId, HttpServerResponse response, String clientIp, int interval) {
            this.connectionId = connectionId;
            this.response = response;
            this.clientIp = clientIp;
            this.updateInterval = interval;
        }
        
        void sendMetricsEvent(JsonObject metrics) {
            eventId++;
            StringBuilder sse = new StringBuilder();
            sse.append("event: metrics\n");
            sse.append("id: ").append(eventId).append("\n");
            sse.append("data: ").append(metrics.encode()).append("\n\n");
            response.write(sse.toString());
        }
        
        void sendHeartbeat() {
            StringBuilder sse = new StringBuilder();
            sse.append("event: heartbeat\n");
            sse.append("data: {\"timestamp\":").append(System.currentTimeMillis()).append("}\n\n");
            response.write(sse.toString());
        }
    }
}
```

#### 4.2 PeeGeeQRestServer Integration (HEXAGONAL ARCHITECTURE COMPLIANT)

**File**: `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`

**Changes** (following existing handler patterns):

```java
public class PeeGeeQRestServer extends AbstractVerticle {
    
    // Existing handlers
    private QueueHandler queueHandler;
    private ManagementApiHandler managementHandler;
    private HealthHandler healthHandler;
    
    // NEW: Add monitoring handler
    private SystemMonitoringHandler monitoringHandler;
    
    @Override
    public void start(Promise<Void> startPromise) {
        // ... existing initialization ...
        
        // ✅ HEXAGONAL ARCHITECTURE: Pass DatabaseSetupService (not ManagementApiHandler)
        monitoringHandler = new SystemMonitoringHandler(setupService, vertx);
        
        // In webSocketHandler lambda, add new endpoint:
        httpServer.webSocketHandler(webSocket -> {
            String path = webSocket.path();
            
            if (path.equals("/ws/health")) {
                new HealthWebSocketHandler().handle(webSocket);
            } else if (path.equals("/ws/monitoring")) {
                // NEW: System monitoring WebSocket
                monitoringHandler.handleWebSocketMonitoring(webSocket);
            } else if (path.startsWith("/ws/queues/")) {
                webSocketHandler.handleQueueStream(webSocket);
            } else {
                webSocket.reject(404);
            }
        });
        
        // ... existing router setup ...
    }
    
    private Router createRouter() {
        Router router = Router.router(vertx);
        
        // ... existing routes ...
        
        // NEW: Add SSE metrics endpoint
        router.get("/sse/metrics").handler(monitoringHandler::handleSSEMetrics);
        
        return router;
    }
}
```

**Hexagonal Architecture Compliance:**

✅ `SystemMonitoringHandler` receives `DatabaseSetupService` (peegeeq-api interface)  
✅ Same dependency pattern as `QueueHandler`, `HealthHandler`, `ManagementApiHandler`  
✅ No cross-handler dependencies (handlers are siblings, not parent-child)  
✅ All business logic in service layer (peegeeq-runtime)  
✅ REST layer is a thin HTTP adapter

### Phase 2: Testing & Validation (Week 1-2)

#### 4.4 Unit Tests (UPDATED WITH REVIEW RECOMMENDATIONS)

**File**: `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/SystemMonitoringHandlerTest.java`

**Test Cases**:
- ✅ WebSocket connection establishment
- ✅ WebSocket per-connection timer creation with jitter
- ✅ WebSocket ping/pong handling
- ✅ WebSocket interval configuration (1-60s validation)
- ✅ WebSocket connection limit enforcement (1000 global, 10 per-IP)
- ✅ SSE connection establishment
- ✅ SSE event streaming with proper event format
- ✅ SSE heartbeat timing
- ✅ SSE reconnection with Last-Event-ID
- ✅ Metrics data accuracy (validates getCachedSystemMetrics + getSystemStats combination)
- ✅ Metrics caching (5-second TTL verification)
- ✅ Connection cleanup on close (timer cancellation)
- ✅ Idle timeout (5-minute auto-disconnect)
- ✅ Multiple concurrent connections
- ✅ Error handling for invalid commands
- ✅ Per-IP rate limiting
- ✅ Input validation for configure messages

#### 4.4a Test Coverage Requirements

**Coverage Tool**: JaCoCo (Java Code Coverage)

**Minimum Coverage Thresholds**:

| Coverage Type | Minimum | Target | Critical Classes |
|---------------|---------|--------|------------------|
| **Line Coverage** | 85% | 90% | `SystemMonitoringHandler` |
| **Branch Coverage** | 75% | 85% | All conditional logic |
| **Method Coverage** | 90% | 95% | All public methods |
| **Complexity Coverage** | 70% | 80% | Connection handling methods |

**JaCoCo Configuration** (add to `peegeeq-rest/pom.xml`):

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.11</version>
            <executions>
                <execution>
                    <id>prepare-agent</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                </execution>
                <execution>
                    <id>report</id>
                    <phase>test</phase>
                    <goals>
                        <goal>report</goal>
                    </goals>
                </execution>
                <execution>
                    <id>check</id>
                    <goals>
                        <goal>check</goal>
                    </goals>
                    <configuration>
                        <rules>
                            <rule>
                                <element>CLASS</element>
                                <limits>
                                    <limit>
                                        <counter>LINE</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.85</minimum>
                                    </limit>
                                    <limit>
                                        <counter>BRANCH</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.75</minimum>
                                    </limit>
                                </limits>
                                <includes>
                                    <include>dev.mars.peegeeq.rest.handlers.SystemMonitoringHandler</include>
                                </includes>
                            </rule>
                        </rules>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**Running Coverage Reports**:

```bash
# Run tests with coverage
mvn clean test

# Generate coverage report
mvn jacoco:report

# View HTML report
open target/site/jacoco/index.html
# Windows: start target/site/jacoco/index.html
# Linux: xdg-open target/site/jacoco/index.html

# Check coverage thresholds (fails build if below minimum)
mvn jacoco:check

# Generate coverage report and fail on threshold violation
mvn clean verify
```

**Coverage Report Location**:
- **HTML Report**: `peegeeq-rest/target/site/jacoco/index.html`
- **XML Report**: `peegeeq-rest/target/site/jacoco/jacoco.xml` (for CI/CD integration)
- **CSV Report**: `peegeeq-rest/target/site/jacoco/jacoco.csv` (for spreadsheet analysis)

**Interpreting Coverage Metrics**:

1. **Line Coverage**: Percentage of executable lines tested
   - **Green** (>90%): Excellent
   - **Yellow** (75-90%): Acceptable
   - **Red** (<75%): Needs improvement

2. **Branch Coverage**: Percentage of decision branches tested (if/else, switch, etc.)
   - **Critical** for connection handling logic
   - Must test both success and failure paths

3. **Method Coverage**: Percentage of methods invoked
   - All public methods should be at 100%
   - Private helper methods should be covered via public method tests

4. **Cyclomatic Complexity**: Measure of code complexity
   - **Target**: Average complexity < 10 per method
   - High complexity methods need more test cases

**Classes to Cover**:

| Class | Minimum Line Coverage | Notes |
|-------|----------------------|-------|
| `SystemMonitoringHandler` | 90% | Primary implementation class |
| `WebSocketConnection` | 85% | Inner class - connection wrapper |
| `SSEConnection` | 85% | Inner class - SSE wrapper |
| `CachedMetrics` | 95% | Simple data class |

**Exclusions** (if needed):

```xml
<configuration>
    <excludes>
        <!-- Exclude generated code if any -->
        <exclude>**/*Builder.class</exclude>
        <!-- Exclude trivial getters/setters if using Lombok -->
        <exclude>**/dto/*.class</exclude>
    </excludes>
</configuration>
```

**CI/CD Integration**:

```yaml
# GitHub Actions / Jenkins
- name: Run tests with coverage
  run: mvn clean verify

- name: Upload coverage to Codecov
  uses: codecov/codecov-action@v3
  with:
    file: ./peegeeq-rest/target/site/jacoco/jacoco.xml
    fail_ci_if_error: true
```

**Coverage Gate Enforcement**:

**Gate 1.4 Pass Criteria**:
```bash
# Must pass all these checks:
mvn test -Dtest=SystemMonitoringHandlerTest
# ✅ All 17 unit tests pass

mvn jacoco:check
# ✅ Line coverage ≥ 85%
# ✅ Branch coverage ≥ 75%
# ✅ Method coverage ≥ 90%

# Review HTML report for gaps
open target/site/jacoco/index.html
# ✅ SystemMonitoringHandler class shows 90%+ line coverage
# ✅ All public methods have green coverage bars
# ✅ Critical error handling paths are covered
```

**What to Cover**:

✅ **Must Cover**:
- All public methods (`handleWebSocketMonitoring`, `handleSSEMetrics`)
- Connection establishment (success + failure paths)
- Message handling (ping, pong, configure, refresh)
- Connection limits (global, per-IP, rate limiting)
- Error handling (invalid commands, connection failures)
- Cleanup logic (timer cancellation, connection removal)
- Metrics caching (TTL expiration, cache hit/miss)

⚠️ **Nice to Cover** (if time permits):
- Edge cases (null parameters, empty messages)
- Concurrent access scenarios
- Performance degradation under load
- Resource leak detection

❌ **Don't Worry About**:
- Third-party library internals (Vert.x, JsonObject)
- Trivial getters/setters
- Logging statements
- Static utility methods from other classes

**Daily Coverage Review**:

```bash
# Day 5 morning: Check current coverage
mvn clean test jacoco:report
open target/site/jacoco/index.html

# Identify gaps:
# 1. Red lines = not executed
# 2. Yellow branches = partially covered (one path missing)
# 3. Look at "Cov." column in report

# Write tests to cover gaps, then re-run:
mvn test jacoco:report

# Repeat until 90%+ achieved
```

#### 4.5 Integration Tests (UPDATED WITH REVIEW RECOMMENDATIONS)

**File**: `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/SystemMonitoringIntegrationTest.java`

**Test Cases**:

**Load Testing**:
```java
@Test
void testConcurrent1000Connections() {
    // Simulate 1000 concurrent SSE connections
    CountDownLatch latch = new CountDownLatch(1000);
    
    for (int i = 0; i < 1000; i++) {
        CompletableFuture.runAsync(() -> {
            SSEClient client = new SSEClient("http://localhost:8080/sse/metrics");
            client.connect();
            latch.countDown();
        });
    }
    
    latch.await(30, TimeUnit.SECONDS);
    
    // Verify server is still responsive
    HttpResponse health = httpClient.get("/api/v1/health");
    assertEquals(200, health.status());
}
```

**Backpressure Testing**:
```java
@Test
void testSlowClientBackpressure() {
    // Connect a client that doesn't read messages fast enough
    SSEClient slowClient = new SSEClient() {
        @Override
        public void onMessage(String msg) {
            Thread.sleep(10000); // Simulate slow processing
        }
    };
    
    slowClient.connect();
    
    // Verify server doesn't block other clients
    SSEClient fastClient = new SSEClient();
    fastClient.connect();
    
    // Fast client should still receive messages
    assertThat(fastClient.receivedMessages()).isNotEmpty();
}
```

**Memory Leak Testing**:
```java
@Test
void testNoMemoryLeakAfterDisconnects() {
    long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Connect and disconnect 10,000 clients
    for (int i = 0; i < 10000; i++) {
        SSEClient client = new SSEClient();
        client.connect();
        client.disconnect();
    }
    
    System.gc();
    Thread.sleep(1000);
    
    long finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Memory should not grow more than 10MB
    assertThat(finalMemory - initialMemory).isLessThan(10_000_000);
}
```

**Connection Limit Testing**:
```java
@Test
void testGlobalConnectionLimit() {
    // Connect 1000 clients (max limit)
    List<SSEClient> clients = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
        SSEClient client = new SSEClient();
        client.connect();
        clients.add(client);
    }
    
    // 1001st connection should be rejected with 503
    SSEClient rejectedClient = new SSEClient();
    HttpResponse response = rejectedClient.connect();
    assertEquals(503, response.status());
}

@Test
void testPerIpConnectionLimit() {
    // Connect 10 clients from same IP (max per-IP limit)
    List<SSEClient> clients = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
        SSEClient client = new SSEClient("127.0.0.1");
        client.connect();
        clients.add(client);
    }
    
    // 11th connection from same IP should be rejected with 429
    SSEClient rejectedClient = new SSEClient("127.0.0.1");
    HttpResponse response = rejectedClient.connect();
    assertEquals(429, response.status());
}
```

**Graceful Shutdown**:
```java
@Test
void testGracefulShutdownWithActiveConnections() {
    // Establish 100 active connections
    List<SSEClient> clients = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
        SSEClient client = new SSEClient();
        client.connect();
        clients.add(client);
    }
    
    // Shutdown server
    server.stop();
    
    // Verify all connections received close event
    for (SSEClient client : clients) {
        assertTrue(client.wasClosedGracefully());
    }
}
```

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

### 5.1 Performance Analysis & Requirements (UPDATED WITH REVIEW)

#### Projected Load at 100 Connections

**Assumptions:**
- 100 concurrent connections (50 WS, 50 SSE)
- 5-second update interval
- 1KB metrics payload per message

**Bandwidth:**
- **Messages per second:** 100 connections ÷ 5 seconds = 20 msg/sec
- **Bandwidth:** 20 msg/sec × 1KB = 20 KB/sec = **0.16 Mbps** ✅ (negligible)

**CPU:**
- **JSON serialization:** 20 msg/sec × 1ms = 20ms CPU per second = **2% CPU** ✅
- **Network I/O:** Handled by Vert.x event loop (non-blocking) ✅

**Memory:**
- **Connection overhead:** 100 connections × 50KB = **5MB** ✅
- **Metrics cache:** 1KB × TTL(5s) = **1KB** ✅

**Verdict:** Performance targets are **achievable** ✅

#### Performance Requirements

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

### 5.3 Security Considerations (UPDATED WITH REVIEW RECOMMENDATIONS)

#### 5.3.1 Authentication & Authorization

**Current Plan**: "Same as existing REST endpoints (Phase 6 requirement)"

**Review Recommendation**: Plan authentication NOW to avoid refactoring later.

```java
public void handleWebSocketMonitoring(ServerWebSocket ws) {
    // Extract auth token from query parameter or header
    String authToken = ws.headers().get("Authorization");
    
    if (!isValidToken(authToken)) {
        ws.close((short) 1008, "Unauthorized");
        return;
    }
    
    // Proceed with connection setup
    ...
}
```

#### 5.3.2 Input Validation

```java
private void handleConfigureMessage(MonitoringConnection conn, JsonObject msg) {
    int interval = msg.getInteger("interval", 5);
    
    // Validate interval range
    if (interval < 1 || interval > 60) {
        sendErrorMessage(conn, "Invalid interval: must be between 1 and 60 seconds");
        return;
    }
    
    conn.setUpdateInterval(interval);
}
```

#### 5.3.3 DoS Protection

**Connection Limiting**:
1. **Global Limit**: Max 1000 concurrent monitoring connections
2. **Per-IP Limit**: Max 10 connections per IP address
3. **Rate Limiting**: Max 1 connection attempt per 5 seconds per IP

```java
private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

private boolean isRateLimited(String clientIp) {
    RateLimiter limiter = rateLimiters.computeIfAbsent(
        clientIp, 
        k -> RateLimiter.create(1.0 / 5.0) // 1 connection per 5 seconds
    );
    
    return !limiter.tryAcquire();
}
```

#### 5.3.4 Additional Security Measures

1. **Data Sanitization**: JSON encoding prevents injection attacks ✅
2. **CORS**: Configurable via existing CORS handler ✅
3. **Idle Timeout**: 5-minute auto-disconnect prevents zombie connections ✅
4. **Heartbeat**: 30-second heartbeat detects stale connections ✅
5. **Error Messages**: Generic errors prevent information disclosure ✅

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

## 6. Implementation Timeline with Testing Gates

### Phase 1: Core Handler Development (Days 1-5)

#### Day 1-2: Handler Skeleton
**Deliverable**: `SystemMonitoringHandler.java` with method signatures
**Testing Gate 1.1 - Compilation Check**:
- ✅ Code compiles without errors
- ✅ Integration with `PeeGeeQRestServer` successful
- ✅ Server starts without exceptions
**Exit Criteria**: `mvn clean compile` passes

#### Day 3: WebSocket Implementation
**Deliverable**: WebSocket `/ws/monitoring` endpoint functional
**Testing Gate 1.2 - WebSocket Unit Tests**:
- ✅ Connection establishment test passes
- ✅ Per-connection timer creation verified
- ✅ Ping/pong handling works
- ✅ Metrics streaming validated
- ✅ Connection cleanup on disconnect
**Exit Criteria**: `SystemMonitoringHandlerTest.testWebSocket*()` all pass

#### Day 4: SSE Implementation  
**Deliverable**: SSE `/sse/metrics` endpoint functional
**Testing Gate 1.3 - SSE Unit Tests**:
- ✅ SSE connection establishment test passes
- ✅ Event format validation
- ✅ Heartbeat timing verified
- ✅ Query parameter parsing works
- ✅ Connection cleanup on disconnect
**Exit Criteria**: `SystemMonitoringHandlerTest.testSSE*()` all pass

#### Day 5: Unit Test Completion
**Deliverable**: 90%+ unit test coverage
**Testing Gate 1.4 - Unit Test Suite**:
- ✅ All 17 unit tests pass (see section 4.4)
- ✅ Code coverage ≥ 90% for `SystemMonitoringHandler`
- ✅ Connection limit enforcement tests pass
- ✅ Input validation tests pass
- ✅ Metrics caching tests pass
**Exit Criteria**: 
```bash
mvn test -Dtest=SystemMonitoringHandlerTest
# All tests pass, coverage report shows 90%+
```

---

### Phase 2: Integration & Load Testing (Days 6-7)

#### Day 6: Integration Testing
**Deliverable**: End-to-end backend integration verified
**Testing Gate 2.1 - Integration Test Suite**:
- ✅ Load test: 1000 concurrent connections (see section 4.5)
- ✅ Backpressure test: slow client doesn't block others
- ✅ Memory leak test: 10,000 connect/disconnect cycles
- ✅ Connection limit tests: global (1000) and per-IP (10)
- ✅ Graceful shutdown test: active connections closed properly
**Exit Criteria**: 
```bash
mvn test -Dtest=SystemMonitoringIntegrationTest
# All integration tests pass
# Memory usage stable after 10,000 cycles
```

#### Day 7: Performance Testing & Bug Fixes
**Deliverable**: Performance validated, bugs fixed
**Testing Gate 2.2 - Performance Benchmarks**:
- ✅ 100 connections: <5% CPU, <10MB RAM
- ✅ Message latency: <100ms p99
- ✅ Throughput: 20+ msg/sec sustainable
- ✅ No memory leaks detected after 1-hour run
- ✅ All existing REST API tests still pass
**Exit Criteria**: 
```bash
# Run performance test harness
cd peegeeq-performance-test-harness
./run-monitoring-load-test.sh
# CPU < 5%, RAM < 10MB, latency p99 < 100ms
```

---

### Phase 3: Frontend Integration (Day 8-9)

#### Day 8: UI Integration & E2E Tests
**Deliverable**: Management UI connects to new endpoints
**Testing Gate 3.1 - E2E Test Suite**:
- ✅ WebSocket connection successful (no 404 errors)
- ✅ SSE connection successful (no 404 errors)
- ✅ Real-time data updates verified (DOM changes)
- ✅ Fallback to polling if connections fail
- ✅ Reconnection after network interruption
- ✅ All 55 existing E2E tests still pass
**Exit Criteria**: 
```bash
cd peegeeq-management-ui
npm run test:e2e
# All 55+ tests pass, including new monitoring tests
```

#### Day 9: Cross-Browser & Performance Testing
**Deliverable**: UI performance validated across browsers
**Testing Gate 3.2 - UI Performance**:
- ✅ Chrome: WebSocket/SSE connection successful
- ✅ Firefox: WebSocket/SSE connection successful
- ✅ Edge: WebSocket/SSE connection successful
- ✅ Safari: WebSocket/SSE connection successful (if available)
- ✅ No console errors or warnings
- ✅ Dashboard updates <500ms after backend sends
- ✅ Lighthouse performance score ≥ 90
**Exit Criteria**: 
```bash
npx playwright test --project=chromium --project=firefox --project=webkit
# All browsers pass
npm run lighthouse
# Performance score ≥ 90
```

---

### Phase 4: Documentation & Code Review (Days 10-11)

#### Day 10: Documentation Updates
**Deliverable**: Updated API docs, guides, README
**Testing Gate 4.1 - Documentation Validation**:
- ✅ API reference includes `/ws/monitoring` and `/sse/metrics`
- ✅ Code examples compile and run
- ✅ Integration guide accurate
- ✅ Architecture diagrams updated
**Exit Criteria**: Manual review + peer sign-off

#### Day 11: Code Review & Refactoring
**Deliverable**: Code review feedback addressed
**Testing Gate 4.2 - Code Quality**:
- ✅ SonarQube: 0 critical/major issues
- ✅ Checkstyle: passes project standards
- ✅ PMD: no high-priority violations
- ✅ All tests still pass after refactoring
**Exit Criteria**: 
```bash
mvn verify sonar:sonar
# Quality gate: PASSED
```

---

### Phase 5: UAT & Deployment Prep (Days 12-14)

#### Day 12-13: User Acceptance Testing
**Deliverable**: UAT feedback collected and issues resolved
**Testing Gate 5.1 - UAT Checklist**:
- ✅ Product owner verifies real-time updates work
- ✅ QA team validates all scenarios in test plan
- ✅ No regressions in existing functionality
- ✅ Performance acceptable in staging environment
- ✅ Security review passed (connection limits, auth planning)
**Exit Criteria**: UAT sign-off document approved

#### Day 14: Production Deployment Preparation
**Deliverable**: Deployment artifacts ready
**Testing Gate 5.2 - Pre-Production Validation**:
- ✅ Staging environment mirrors production
- ✅ All tests pass in staging
- ✅ Rollback procedure documented and tested
- ✅ Monitoring/alerting configured
- ✅ Performance baseline established
**Exit Criteria**: 
```bash
# Staging deployment
mvn clean package
docker build -t peegeeq-rest:latest .
# Deploy to staging, run smoke tests
curl http://staging:8080/api/v1/health
# Health check passes
```

---

## 6a. Testing Summary Matrix

| Phase | Day | Test Type | Test Gate | Pass Criteria | Tools |
|-------|-----|-----------|-----------|---------------|-------|
| 1 | 1-2 | Compilation | 1.1 | Code compiles, server starts | `mvn compile` |
| 1 | 3 | Unit (WS) | 1.2 | WebSocket tests pass | JUnit |
| 1 | 4 | Unit (SSE) | 1.3 | SSE tests pass | JUnit |
| 1 | 5 | Unit (Full) | 1.4 | 90%+ coverage, all tests pass | JUnit + JaCoCo |
| 2 | 6 | Integration | 2.1 | Load/memory/limits pass | Integration tests |
| 2 | 7 | Performance | 2.2 | <5% CPU, <100ms latency | JMeter/harness |
| 3 | 8 | E2E | 3.1 | All 55+ UI tests pass | Playwright |
| 3 | 9 | Cross-browser | 3.2 | All browsers + Lighthouse ≥90 | Playwright |
| 4 | 10 | Documentation | 4.1 | Manual review sign-off | Peer review |
| 4 | 11 | Code Quality | 4.2 | SonarQube passes | SonarQube |
| 5 | 12-13 | UAT | 5.1 | Product owner sign-off | Manual |
| 5 | 14 | Pre-Prod | 5.2 | Staging tests pass | Smoke tests |

**Critical Testing Gates (Cannot Proceed Without Passing):**
- 🔴 **Gate 1.4**: Unit tests must achieve 90%+ coverage
- 🔴 **Gate 2.1**: Integration tests must handle 1000 connections without failure
- 🔴 **Gate 3.1**: All 55 existing E2E tests must still pass (no regressions)
- 🔴 **Gate 5.1**: UAT sign-off required before deployment

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
