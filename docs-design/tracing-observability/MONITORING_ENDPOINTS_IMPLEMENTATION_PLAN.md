# Real-Time Monitoring Endpoints Implementation Plan

**Status:** ✅ Implementation Complete (Critical Fixes Applied)
**Created:** 2025-12-30
**Reviewed:** 2025-12-30
**Module:** peegeeq-rest
**Target Version:** Phase 5 - Management UI Enhancement
**Review Status:** APPROVED WITH RECOMMENDATIONS INCORPORATED

---

## 1. Executive Summary

### Problem Statement
The PeeGeeQ Management UI currently attempts to connect to two monitoring endpoints that are not implemented:
- **WebSocket**: `ws://localhost:8080/ws/monitoring` → Returns 404
- **SSE**: `/sse/metrics` → Returns 404

These missing endpoints result in silent connection failures and browser console errors. Consequently, the UI relies on 30-second polling of `/api/v1/management/overview`, preventing real-time visibility.

### Proposed Solution
Implement both real-time monitoring endpoints to provide live system metrics, queue statistics, and connection status updates with sub-second latency.

**Key Architecture Decisions:**
- ✅ Use **per-connection timers** with jitter (avoids broadcast storms).
- ✅ Implement **per-IP rate limiting** (max 10 connections per IP).
- ✅ Enforce **connection idle timeouts** (5 minutes).
- ✅ Cache **serialized JSON** to reduce Garbage Collection (GC) pressure.

### Success Criteria
1. ✅ WebSocket `/ws/monitoring` endpoint streams system metrics (default 5s interval).
2. ✅ SSE `/sse/metrics` endpoint provides real-time metrics updates.
3. ✅ Management UI connects successfully without 404 errors.
4. ✅ Dashboard updates in real-time (<5 second latency).
5. ✅ Support for 100+ concurrent connections with <5% CPU overhead.
6. ✅ Per-IP rate limiting prevents abuse.

---

## 2. Current State Analysis

### 2.1 Existing Infrastructure

**Working Endpoints:**
- `GET /api/v1/health` - REST health check
- `GET /api/v1/management/overview` - System overview (polled 30s)
- `WS /ws/queues/{setupId}/{queueName}` - Queue message streaming (Per-connection)

**Missing Endpoints:**
- `WS /ws/monitoring` - System monitoring stream
- `GET /sse/metrics` - System metrics SSE stream

### 2.2 Data Sources

The monitoring endpoints will aggregate data from two primary sources within the `ManagementApiHandler` logic (to be moved/shared via Service Layer):

1.  **JVM/System Metrics** (`getCachedSystemMetrics`):
    - Timestamp, Uptime, Memory (Used/Total/Max), CPU Cores, Active Threads.
2.  **Application/Business Metrics** (`getSystemStats`):
    - Total Queues, Consumer Groups, Event Stores.
    - Total Messages, Messages Per Second, Active Connections.

**Integration Strategy:**
The `SystemMonitoringHandler` will directly access the `DatabaseSetupService` to collect these metrics, ensuring strict adherence to the Hexagonal Architecture (see Architecture section).

---

## 3. Architecture & Design

### 3.1 Component Overview - Hexagonal Architecture

The implementation adheres to the PeeGeeQ Layered Architecture.

**Rules:**
- `SystemMonitoringHandler` is a sibling to `ManagementApiHandler`.
- **Constraint:** Handlers MUST NOT call each other.
- **Pattern:** Both handlers depend on `peegeeq-api` interfaces (e.g., `DatabaseSetupService`) and `peegeeq-runtime` implementations.

**Data Flow:**
```
[Management UI] <-> [SystemMonitoringHandler] -> [DatabaseSetupService] -> [QueueFactory / HealthService] -> [DB]
```

### 3.2 Design Patterns & Security

**1. Per-Connection Streaming (Critical)**
   - Instead of a global broadcast timer, each connection has its own `vertx.setPeriodic()` timer.
   - **Jitter**: A random delay (0-1000ms) is added to the interval to desynchronize updates and prevent "thundering herd" CPU spikes.

**2. Connection Limits**
   - **Global Limit**: 1000 concurrent connections.
   - **Per-IP Limit**: 10 connections per IP.
   - **Rate Limiting**: New connections limited to 1 per 5 seconds per IP.

**3. Optimization**
   - **Caching**: Serialized JSON metrics cached for 1-5 seconds to serve multiple clients efficiently.
   - **Idle Timeout**: Connections close automatically after 5 minutes of inactivity (absence of keep-alive/pong).

### 3.3 WebSocket Endpoint Specification

**Endpoint**: `WS /ws/monitoring`

**Message Format (Server -> Client)**:
```json
{
  "type": "system_stats",
  "data": {
    "timestamp": 1704000000000,
    "uptime": 3600000,
    "memoryUsed": 536870912,
    "messagesPerSecond": 125.5,
    "activeConnections": 15,
    "queues": { "total": 5, "byType": { "NATIVE": 3 } }
  }
}
```

**Client Commands**:
- `{"type": "ping", "id": 123}` -> `{"type": "pong", ...}`
- `{"type": "configure", "interval": 5}` -> Update push frequency.
- `{"type": "refresh"}` -> Request immediate update.

### 3.4 SSE Endpoint Specification

**Endpoint**: `GET /sse/metrics`

**Query Params**:
- `interval`: Update interval in seconds (default: 5).
- `heartbeat`: Keep-alive heartbeat in seconds (default: 30).

**Event Format**:
```text
event: metrics
id: <timestamp>
data: <json_metrics_payload>

event: heartbeat
data: {"timestamp": ...}
```

---

## 4. Implementation Plan

### Phase 1: Core Handler Development

#### 1. SystemMonitoringHandler Implementation
- **Class Structure**:
  - Dependencies: `DatabaseSetupService`, `Vertx`, `RestServerConfig`.
  - Inner Classes: `WebSocketConnection`, `SSEConnection`, `CachedMetrics`.
- **Responsibilities**:
  - Connection lifecycle management (Open, Close, Timeout).
  - Metrics collection (orchestrating calls to services).
  - Command parsing and dispatch.

#### 2. RestServer Integration
- Register `SystemMonitoringHandler` in `PeeGeeQRestServer`.
- Route `/ws/monitoring` to `handleWebSocketMonitoring`.
- Route `/sse/metrics` to `handleSSEMetrics`.

### Phase 2: Testing & Validation

#### Unit Tests (Target: 90% Coverage)
- **Suite**: `SystemMonitoringHandlerTest`
- **Scenarios**:
  - Connection acceptance/rejection (Limits).
  - Timer creation with jitter.
  - JSON serialization correctness.
  - Command handling (Ping/Configure).
  - Graceful shutdown and resource cleanup.

#### Integration Tests
- **Suite**: `SystemMonitoringIntegrationTest`
- **Scenarios**:
  - Load Test: 1000 concurrent connections.
  - Memory Leak Check: Repeated connect/disconnect cycles.
  - Backpressure: Slow client handling.

### Phase 3: Frontend Integration ✅ COMPLETED

- ✅ Updated `websocketService.ts` to connect to `/ws/monitoring` and `/sse/metrics` using `getApiUrl()` for proper URL resolution
- ✅ Updated `Overview.tsx` to subscribe to real-time streams with message unwrapping and `setSystemStats()` integration
- ✅ Implemented fallback to polling (30-second interval) if connection failure occurs
- ✅ Added `setSystemStats()` action to `managementStore.ts` for real-time stat updates
- ✅ Updated E2E tests (`overview-system-status.spec.ts`) to expect "Connected" status and green indicators
- ✅ Backend sends initial metrics immediately on connection (no wait for first interval)




---

## 5. Technical Specifications & Requirements

### Performance Targets
| Metric | Target | Rationale |
|--------|--------|-----------|
| **Update Interval** | 5 seconds | Good balance of freshness vs load. |
| **Max Connections** | 1000 | Support large deployments. |
| **CPU Overhead** | < 5% | @ 100 concurrent connections. |
| **Latency** | < 100ms | Real-time feel. |

### Security
- **Input Validation**: Validate `interval` (1-60s).
- **DoS Protection**: Enforce IP limits and connection caps.
- **Resource Management**: Strict cleanup of timers on socket close.

---

## 6. Appendix: Testing Gates

| Gate | Description | Criteria |
|------|-------------|----------|
| **1.1** | Compilation | Code compiles, Server starts. |
| **1.2** | Unit Tests | all `SystemMonitoringHandlerTest` pass. |
| **1.3** | Coverage | > 90% Line Coverage on Handler. |
| **2.1** | Integration | 1000 connections stable. |
| **3.1** | E2E | UI shows live updates; no 404s. |

---

## 7. Post-Implementation Code Review Findings

**Review Date:** 2025-12-31  
**Reviewer:** Code Quality Analysis  
**Status:** Implementation Complete - Bugs Identified

### 7.1 Bug #1: Resource Leak - SystemMonitoringHandler Not Closed on Shutdown ⚠️ HIGH

**Severity:** HIGH  
**Location:** `PeeGeeQRestServer.java` lines 79-80, 156-174

**Issue:**
The `SystemMonitoringHandler` manages WebSocket and SSE connections with active timers but is **not stored as an instance field** and **not closed during shutdown**.

**Current Code:**
```java
// Handlers that manage consumers and need explicit cleanup on shutdown
private WebhookSubscriptionHandler webhookHandler;
private ServerSentEventsHandler sseHandler;
// ❌ SystemMonitoringHandler is missing!

@Override
public void stop(Promise<Void> stopPromise) {
    if (webhookHandler != null) {
        webhookHandler.close();
    }
    if (sseHandler != null) {
        sseHandler.close();
    }
    // ❌ SystemMonitoringHandler is NOT closed!
}
```

**Impact:**
- Active monitoring connections (WebSocket/SSE) won't be closed gracefully
- Per-connection timers won't be cancelled (resource leak)
- Idle timeout timers won't be cancelled (resource leak)
- Connection tracking maps (`wsConnections`, `sseConnections`) won't be cleared
- Memory leaks on server restart/redeploy

**Evidence:**
`SystemMonitoringHandler` manages stateful resources:
```java
private final Map<String, WebSocketConnection> wsConnections = new ConcurrentHashMap<>();
private final Map<String, SSEConnection> sseConnections = new ConcurrentHashMap<>();
// Each connection has: timerId, heartbeatTimerId, idleCheckerId
```

**Required Fix:**
1. Add `SystemMonitoringHandler` as instance field in `PeeGeeQRestServer`
2. Implement `close()` method in `SystemMonitoringHandler`:
   ```java
   public void close() {
       // Cancel all WebSocket connection timers
       wsConnections.values().forEach(conn -> {
           if (conn.timerId > 0) vertx.cancelTimer(conn.timerId);
           if (conn.idleCheckerId > 0) vertx.cancelTimer(conn.idleCheckerId);
           conn.webSocket.close();
       });
       wsConnections.clear();
       
       // Cancel all SSE connection timers
       sseConnections.values().forEach(conn -> {
           if (conn.metricsTimerId > 0) vertx.cancelTimer(conn.metricsTimerId);
           if (conn.heartbeatTimerId > 0) vertx.cancelTimer(conn.heartbeatTimerId);
           if (conn.idleCheckerId > 0) vertx.cancelTimer(conn.idleCheckerId);
           conn.response.end();
       });
       sseConnections.clear();
       
       connectionsByIp.clear();
       totalConnections.set(0);
   }
   ```
3. Call `monitoringHandler.close()` in `PeeGeeQRestServer.stop()`

**Estimated Effort:** 30 minutes

---

### 7.2 Bug #2: WebSocketHandler Not Closed on Shutdown ✅ FIXED

**Severity:** MEDIUM  
**Status:** ✅ **FIXED** (2025-12-31)  
**Location:** `PeeGeeQRestServer.java` and `WebSocketHandler.java`

**Issue:**
`WebSocketHandler` was created inline for each WebSocket connection but never stored or closed during server shutdown.

**Original Problem:**
```java
if (path.startsWith("/ws/queues/")) {
    WebSocketHandler webSocketHandler = new WebSocketHandler(setupService, objectMapper);
    webSocketHandler.handleQueueStream(webSocket);
    // ❌ Created inline, never stored, can't be closed on shutdown
}
```

**Impact:**
- Active queue stream WebSocket connections won't be closed gracefully
- Consumers started by WebSocketHandler may not be stopped properly
- Connection tracking and resources may leak

**Resolution Implemented:**

1. **Promoted WebSocketHandler to singleton instance field** (`PeeGeeQRestServer.java` line 77):
   ```java
   private WebSocketHandler webSocketHandler;
   ```

2. **Initialize once in createRouter()** (line 248):
   ```java
   this.webSocketHandler = new WebSocketHandler(setupService, objectMapper);
   ```

3. **Use singleton instance in start()** (lines 117-123):
   ```java
   if (path.startsWith("/ws/queues/")) {
       if (webSocketHandler != null) {
           webSocketHandler.handleQueueStream(webSocket);
       } else {
           logger.error("WebSocketHandler not initialized");
           webSocket.close((short) 1011, "Internal Server Error");
       }
   }
   ```

4. **Implemented close() method in WebSocketHandler** (lines 321-336):
   ```java
   public void close() {
       logger.info("Closing WebSocketHandler and {} active connections", activeConnections.size());
       activeConnections.values().forEach(connection -> {
           try {
               connection.cleanup();
               if (!connection.getWebSocket().isClosed()) {
                   connection.getWebSocket().close((short) 1001, "Server shutting down");
               }
           } catch (Exception e) {
               logger.warn("Error closing WebSocket connection {}: {}", 
                   connection.getConnectionId(), e.getMessage());
           }
       });
       activeConnections.clear();
       logger.info("WebSocketHandler closed");
   }
   ```

5. **Call close() in stop() method** (`PeeGeeQRestServer.java` lines 192-196):
   ```java
   if (webSocketHandler != null) {
       logger.debug("Closing WebSocketHandler...");
       webSocketHandler.close();
       logger.debug("WebSocketHandler closed");
   }
   ```

**Verification:**
- ✅ Single instance created (not per-connection)
- ✅ Proper cleanup on shutdown
- ✅ All active connections closed gracefully
- ✅ Resources properly released
- ✅ Tests passing (94 tests, 0 failures)

**Effort Spent:** 1 hour (investigation + implementation + testing)

---

### 7.3 Bug #3: CORS Handler Allows All Origins 🔒 MEDIUM-HIGH Security

**Severity:** MEDIUM-HIGH (Security Risk)  
**Location:** `PeeGeeQRestServer.java` lines 398-406

**Issue:**
CORS handler does not specify allowed origins, defaulting to wildcard `*` which allows requests from ANY origin.

**Current Code:**
```java
private CorsHandler createCorsHandler() {
    return CorsHandler.create()
            .allowedMethod(io.vertx.core.http.HttpMethod.GET)
            .allowedMethod(io.vertx.core.http.HttpMethod.POST)
            .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
            .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
            .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization");
    // ❌ NO .addOrigin() or .allowedOrigin() specified!
}
```

**Impact:**
- **Allows requests from ANY origin** (wildcard `*`)
- Any website can make AJAX requests to the API
- Opens up to Cross-Site Request Forgery (CSRF) attacks
- Not production-safe
- Violates security best practices

**Required Fix:**
```java
private CorsHandler createCorsHandler() {
    CorsHandler handler = CorsHandler.create();
    
    // Add allowed origins from configuration
    config.allowedOrigins().forEach(handler::addOrigin);
    
    // Alternative: Environment-based
    // String origins = System.getenv("ALLOWED_ORIGINS");
    // if (origins != null) {
    //     Arrays.stream(origins.split(","))
    //         .forEach(handler::addOrigin);
    // }
    
    return handler
            .allowedMethod(io.vertx.core.http.HttpMethod.GET)
            .allowedMethod(io.vertx.core.http.HttpMethod.POST)
            .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
            .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
            .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization");
}
```

**Configuration Addition Required:**
```java
// In RestServerConfig.java
public record RestServerConfig(
    int port,
    MonitoringConfig monitoring,
    List<String> allowedOrigins  // Add this field
) {
    public static RestServerConfig from(JsonObject json) {
        List<String> origins = json.getJsonArray("allowedOrigins", 
            new JsonArray()
                .add("http://localhost:3000")
                .add("http://localhost:8080"))
            .stream()
            .map(Object::toString)
            .toList();
        
        return new RestServerConfig(port, monitoring, origins);
    }
}
```

**Estimated Effort:** 1 hour

---

### 7.4 Minor Issue: Metrics Endpoint Returns Hardcoded Zeros ℹ️ LOW

**Severity:** LOW (Functionality)  
**Location:** `PeeGeeQRestServer.java` lines 374-391

**Issue:**
The `/metrics` endpoint returns hardcoded zero values instead of real metrics.

**Current Code:**
```java
router.get("/metrics").handler(ctx -> {
    metrics.append("peegeeq_http_requests_total 0\n");  // ❌ Always 0
    metrics.append("peegeeq_active_connections 0\n");   // ❌ Always 0
    metrics.append("peegeeq_messages_sent_total 0\n");  // ❌ Always 0
});
```

**Impact:**
- Metrics endpoint is non-functional
- Returns fake data
- Can't be used for monitoring/alerting
- Misleading for operations teams

**Note:** Comment in code already acknowledges this is placeholder.

**Required Fix:**
Integrate with the already-injected `MeterRegistry`:
```java
// Use the existing meterRegistry field
private final MeterRegistry meterRegistry;

router.get("/metrics").handler(ctx -> {
    // Use Micrometer's PrometheusMeterRegistry for proper Prometheus format
    String metrics = meterRegistry.scrape(); // If using PrometheusMeterRegistry
    ctx.response()
        .putHeader("content-type", "text/plain; version=0.0.4; charset=utf-8")
        .end(metrics);
});
```

**Estimated Effort:** 2-3 hours (requires Micrometer Prometheus integration)

---

### 7.5 Implementation Quality Summary

**Overall Code Quality:** ⭐⭐⭐⭐ (9/10) - Excellent

**Strengths:**
- ✅ Clean hexagonal architecture
- ✅ Proper thread safety (ConcurrentHashMap, AtomicInteger)
- ✅ Comprehensive error handling
- ✅ Excellent documentation (Javadoc)
- ✅ Production-ready configuration management
- ✅ Comprehensive test coverage (10 tests, 634 lines)
- ✅ Smart caching and performance optimizations
- ✅ Per-connection timers with jitter (prevents thundering herd)

**Weaknesses:**
- ⚠️ Resource leak in SystemMonitoringHandler shutdown
- ⚠️ CORS security vulnerability (allows all origins)
- ⚠️ Incomplete WebSocketHandler lifecycle management
- ℹ️ Non-functional metrics endpoint

**Production Readiness:**
- **Current State:** Not production-ready due to Bug #1 (resource leak) and Bug #3 (security)
- **After Fixes:** Production-ready (estimated 2-3 hours total effort)

---

### 7.6 Remediation Plan

| Priority | Bug | Estimated Effort | Blocking Production? | Status |
|----------|-----|------------------|---------------------|--------|
| **P0** | **Bug #5: Singleton Instance** | 15 minutes | ✅ YES - Critical Logic Bug | **FIXED** |
| **P0** | **Bug #1: SystemMonitoringHandler Leak** | 30 minutes | ✅ YES - Resource Leak | **FIXED** |
| **P0** | **Bug #3: CORS Security** | 1 hour | ✅ YES - Security Risk | **FIXED** |
| **P1** | **Imp: AtomicReference Cache** | 15 minutes | ❌ NO - Stability | **FIXED** |
| **P1** | **Bug #4: Metrics Integration** | 1 hour | ❌ NO - Observability | **FIXED** |
| **P1** | **Bug #2: WebSocketHandler lifecycle** | 1 hour | ✅ YES - Resource Leak | **FIXED** |

**Total Critical Path:** ✅ All issues resolved - Production Ready

---

### 7.7 Recommendations

1. ✅ ~~**Immediate:** Fix Bug #1 and Bug #3 before any production deployment~~ - COMPLETED
2. ✅ ~~**Short-term:** Investigate and fix Bug #2 (WebSocketHandler lifecycle)~~ - COMPLETED
3. ✅ ~~**Next:** Add shutdown resource leak detection to integration tests~~ - COMPLETED (ShutdownResourceLeakDetectionTest.java)
4. ✅ ~~**Next:** Add CORS configuration validation to startup checks~~ - COMPLETED (validateCorsConfiguration() method)
5. **Long-term:** Implement enhanced metrics integration with Micrometer (Prometheus scrape endpoint functional)
6. **Security:** Regular security audits of CORS configuration
7. **Monitoring:** Monitor real-time connection metrics in production environment

