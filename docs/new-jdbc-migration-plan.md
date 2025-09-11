# **Simple Class-by-Class JDBC to Vert.x 5.x Upgrade Plan**

## **Overview**

This is a **simple refactoring plan** based on analysis of the existing codebase. The reactive infrastructure already exists - we just need to clean up the legacy API surface and remove deprecated JDBC methods.

**Key Finding:** Most Vert.x 5.x reactive patterns are already implemented. The main issue is that public APIs still return `CompletableFuture` instead of `Future`, and some deprecated JDBC methods need removal.

**Estimated Timeline:** 4-5 days total

---

## **Phase 1: Core Interface Modernization (1-2 days)**

### **1. ConnectionProvider Interface** 
**File:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/ConnectionProvider.java`

**Current State:**
- Uses `DataSource getDataSource(String clientId)`
- Uses `Connection getConnection(String clientId)`
- Uses `CompletableFuture<Connection> getConnectionAsync(String clientId)`

**Required Changes:**
- Replace `DataSource getDataSource()` → `Future<Pool> getReactivePool(String clientId)`
- Replace `Connection getConnection()` → `Future<SqlConnection> getConnection(String clientId)`
- Remove `CompletableFuture<Connection> getConnectionAsync()` (redundant with reactive getConnection)
- Keep health check methods but make them reactive: `Future<Boolean> isHealthy()`

### **2. PgConnectionProvider Implementation**
**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/provider/PgConnectionProvider.java`

**Current State:**
- Implements old ConnectionProvider interface with JDBC methods
- Uses `connectionManager.getDataSource(clientId)` internally
- Has `CompletableFuture.supplyAsync()` patterns

**Required Changes:**
- Update to implement new reactive ConnectionProvider interface
- Replace JDBC calls with reactive pool calls from PgConnectionManager
- Remove all `CompletableFuture` usage
- **Note:** The reactive pool creation already exists in PgConnectionManager - just wire it up

---

## **Phase 2: Database Service Layer (1 day)**

### **3. PgDatabaseService**
**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/provider/PgDatabaseService.java`

**Current State:**
- All methods return `CompletableFuture<T>`
- Uses `CompletableFuture.runAsync()` and `CompletableFuture.supplyAsync()`

**Required Changes:**
- `CompletableFuture<Void> initialize()` → `Future<Void> initialize()`
- `CompletableFuture<Void> start()` → `Future<Void> start()`
- `CompletableFuture<Void> runMigrations()` → `Future<Void> runMigrations()`
- `CompletableFuture<Boolean> performHealthCheck()` → `Future<Boolean> performHealthCheck()`
- Replace `CompletableFuture.runAsync()` with Vert.x `executeBlocking()` or direct reactive calls

### **4. PeeGeeQDatabaseSetupService**
**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java`

**Current State:**
- `CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request)`
- Uses `CompletableFuture.supplyAsync()` internally

**Required Changes:**
- Replace with `Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request)`
- Convert internal `CompletableFuture.supplyAsync()` to Vert.x reactive patterns

---

## **Phase 3: Outbox Module Cleanup (1 day)**

### **5. OutboxProducer**
**File:** `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxProducer.java`

**Current State:**
- Public API returns `CompletableFuture<Void>` 
- Internally uses reactive patterns correctly (already has `sendReactive()` methods)
- Has proper Vert.x pool management

**Required Changes:**
- Change public API methods to return `Future<Void>` instead of `CompletableFuture<Void>`
- Remove `CompletableFuture<Void> future = new CompletableFuture<>()` wrapper patterns
- **Note:** Internal reactive implementation already exists and works correctly

### **6. OutboxConsumer**
**File:** `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java`

**Current State:**
- Mixed patterns - mostly reactive, some CompletableFuture, deprecated `getDataSource()`
- Has JDBC code in dead letter queue handling
- Uses `CompletableFuture.runAsync()` for message processing

**Required Changes:**
- Remove deprecated `getDataSource()` method entirely
- Replace remaining `CompletableFuture` usage with `Future` patterns
- Convert dead letter queue JDBC code to reactive patterns
- Clean up JDBC imports (`PreparedStatement`, `SQLException`, etc.)

---

## **Phase 4: Legacy Cleanup (1 day)**

### **7. PgConnectionManager**
**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java`

**Current State:**
- Has deprecated `getOrCreateDataSource()` method that throws `UnsupportedOperationException`
- Has `legacyDataSources` Map that's unused
- Imports JDBC classes (`DataSource`, `Connection`, `SQLException`)

**Required Changes:**
- Remove deprecated `getOrCreateDataSource()` method entirely
- Remove `legacyDataSources` Map and all JDBC imports
- Keep only reactive pool methods (`getOrCreateReactivePool()`, `getReactiveConnection()`)
- Clean up unused imports

### **8. PeeGeeQManager**
**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`

**Current State:**
- Creates temporary DataSource using HikariCP reflection for migration
- Has complex `createTemporaryDataSourceForMigration()` method

**Required Changes:**
- Remove `createTemporaryDataSourceForMigration()` method entirely
- Remove all HikariCP reflection code
- Remove temporary DataSource creation logic
- **Note:** All components should use reactive pools by this point

### **9. SqlTemplateProcessor**
**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/SqlTemplateProcessor.java`

**Current State:**
- Uses JDBC `Connection` and `Statement`
- Has `SQLException` handling

**Required Changes:**
- Replace JDBC `Connection` parameter with Vert.x `SqlConnection`
- Replace `Statement` usage with reactive `preparedQuery()` or `query()`
- Replace `SQLException` handling with Vert.x error handling patterns

---

## **Phase 5: Dependency Cleanup (30 minutes)**

### **10. Remove HikariCP Dependencies**

**Files to Update:**
- All `pom.xml` files that reference HikariCP
- Any remaining classes with unused JDBC imports

**Required Changes:**
- Remove HikariCP dependencies from Maven configurations
- Clean up unused JDBC imports across all classes:
  - `javax.sql.DataSource`
  - `java.sql.Connection`
  - `java.sql.SQLException`
  - `java.sql.PreparedStatement`
  - `java.sql.Statement`
- Update any remaining `javax.sql.*` imports to `io.vertx.sqlclient.*`

---

## **Key Observations**

1. **Most reactive infrastructure already exists** - the codebase has extensive Vert.x 5.x pool management
2. **Main issue is API surface** - public methods still return `CompletableFuture` instead of `Future`
3. **Legacy methods are already deprecated** - just need to remove them entirely
4. **No new test frameworks needed** - existing reactive patterns work fine
5. **This is refactoring, not migration** - the hard work of implementing reactive patterns is already done

## **Success Criteria**

- [ ] No `CompletableFuture` usage in public APIs
- [ ] No JDBC imports (`javax.sql.*`, `java.sql.*`)
- [ ] No HikariCP dependencies
- [ ] All database operations use Vert.x 5.x `Future<T>` patterns
- [ ] All connection management uses reactive `Pool` instead of `DataSource`
- [ ] Existing tests continue to pass with reactive patterns

## **Risk Assessment**

**Low Risk** - This is primarily an API surface cleanup rather than a fundamental architecture change. The reactive infrastructure is already proven and working in production.
