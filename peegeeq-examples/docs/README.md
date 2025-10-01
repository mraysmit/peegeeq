# PeeGeeQ Examples Documentation

**Last Updated**: 2025-10-02

This directory contains documentation for implementing PeeGeeQ examples correctly.

---

## 📚 Documentation Index

### 1. **CORRECT_PEEGEEQ_API_USAGE.md** ⭐ START HERE
**Purpose**: Comprehensive guide on how to use PeeGeeQ APIs correctly

**Key Topics**:
- What PeeGeeQ provides (PeeGeeQManager, DatabaseService, ConnectionProvider)
- Correct usage patterns for Spring Boot integration
- What NOT to do (separate pools, R2DBC, etc.)
- Allowed vs not-allowed imports

**Read this first** before implementing any PeeGeeQ integration.

### 2. **WRONG_VS_CORRECT_PATTERNS.md**
**Purpose**: Side-by-side comparison of incorrect and correct patterns

**Key Topics**:
- Spring configuration (wrong vs correct)
- Service layer transactions (wrong vs correct)
- R2DBC usage (why it's wrong)
- Transaction propagation patterns
- Summary table of all patterns

**Use this** as a quick reference when implementing.

### 3. **REVISED_IMPLEMENTATION_PLAN.md** ⭐ CURRENT PLAN
**Purpose**: Step-by-step implementation plan with correct API usage

**Key Topics**:
- Current status (what's done, what needs revision)
- Phase 1: Revise springboot example (4-6 hours)
- Phase 2: Fix springboot2 example (17-23 hours)
- Phase 3: Update documentation (6-8 hours)
- Detailed tasks with code examples
- Success criteria

**Use this** for implementation work.

### 4. **SPRINGBOOT2_TRANSACTION_IMPLEMENTATION_PLAN.md** (SUPERSEDED)
**Purpose**: Original implementation plan (now superseded by REVISED_IMPLEMENTATION_PLAN.md)

**Note**: This document contains the original investigation findings but uses incorrect API patterns.
Refer to REVISED_IMPLEMENTATION_PLAN.md for the correct approach.

**Use this** for historical context only.

### 5. **IMPLEMENTATION_STATUS.md**
**Purpose**: Track progress of implementation work

**Key Topics**:
- Current status of each task
- Completed tasks with checkmarks
- Remaining tasks
- Critical discoveries and course corrections

**Note**: This document tracks the original implementation attempt. See REVISED_IMPLEMENTATION_PLAN.md for current work.

**Use this** for historical tracking.

### 6. **Transaction Rollback in PeeGeeQ_INVESTIGATION_FINDINGS.md**
**Purpose**: Original investigation findings that led to this work

**Key Topics**:
- Evidence of issues in current examples
- Analysis of TransactionPropagation.CONTEXT behavior
- Comparison with working test examples
- Impact assessment

**Use this** to understand the history and context.

---

## 🎯 Quick Start Guide

### For Developers New to PeeGeeQ

1. **Read**: `CORRECT_PEEGEEQ_API_USAGE.md` (15-20 minutes)
2. **Review**: `WRONG_VS_CORRECT_PATTERNS.md` (10 minutes)
3. **Implement**: Follow the patterns from the correct examples

### For Developers Implementing the Fixes

1. **Read**: `CORRECT_PEEGEEQ_API_USAGE.md` to understand the API
2. **Review**: `WRONG_VS_CORRECT_PATTERNS.md` to identify issues
3. **Follow**: `REVISED_IMPLEMENTATION_PLAN.md` step-by-step

### For Project Managers

1. **Review**: `REVISED_IMPLEMENTATION_PLAN.md` for scope and effort (27-37 hours)
2. **Track**: Progress using the task lists in the plan
3. **Understand**: `Transaction Rollback in PeeGeeQ_INVESTIGATION_FINDINGS.md` for context

---

## 🚨 Critical Principles

### 1. PeeGeeQ Provides ALL Infrastructure

**Don't create**:
- ❌ Separate Vert.x Pools
- ❌ R2DBC connections
- ❌ Your own connection management

**Do use**:
- ✅ PeeGeeQManager
- ✅ DatabaseService
- ✅ ConnectionProvider

### 2. Use the Public API

**Import from**:
- ✅ `dev.mars.peegeeq.api.*`
- ✅ `dev.mars.peegeeq.db.PeeGeeQManager` (for setup)
- ✅ `dev.mars.peegeeq.db.provider.*` (for setup)
- ✅ `dev.mars.peegeeq.outbox.*` (for outbox pattern)
- ✅ `io.vertx.*` (PeeGeeQ API uses Vert.x types)

**Don't import from**:
- ❌ `dev.mars.peegeeq.db.client.*` (internal)
- ❌ `dev.mars.peegeeq.db.connection.*` (internal)
- ❌ `io.r2dbc.*` (incompatible)

### 3. Transaction Pattern

**Correct pattern**:
```java
ConnectionProvider cp = databaseService.getConnectionProvider();

cp.withTransaction("peegeeq-main", connection -> {
    // All operations use this connection
    return repository.save(data, connection)
        .compose(v -> Future.fromCompletionStage(
            producer.sendInTransaction(event, connection)
        ));
}).toCompletionStage().toCompletableFuture();
```

**Wrong pattern**:
```java
// ❌ Creates separate transactions
producer.sendWithTransaction(event, TransactionPropagation.CONTEXT)
    .thenCompose(v -> repository.save(data));
```

### 4. No R2DBC

**Remove these dependencies**:
- ❌ `spring-boot-starter-data-r2dbc`
- ❌ `r2dbc-postgresql`
- ❌ `r2dbc-pool`

**Why**: R2DBC creates a separate connection pool that cannot share transactions with PeeGeeQ.

---

## 📋 Implementation Checklist

### Spring Boot Configuration

- [ ] Create `PeeGeeQManager` bean
- [ ] Create `DatabaseService` bean from manager
- [ ] Create `QueueFactory` bean from DatabaseService
- [ ] Create `OutboxProducer` beans from QueueFactory
- [ ] **Do NOT** create separate Pool beans
- [ ] **Do NOT** configure R2DBC

### Repository Layer

- [ ] Repositories accept `SqlConnection` parameter
- [ ] Use `io.vertx.sqlclient.*` for queries
- [ ] Return `Future<T>` for async operations
- [ ] **Do NOT** extend R2DBC repositories
- [ ] **Do NOT** use Spring Data R2DBC

### Service Layer

- [ ] Inject `DatabaseService`
- [ ] Get `ConnectionProvider` from DatabaseService
- [ ] Use `ConnectionProvider.withTransaction()` for transactions
- [ ] Pass `SqlConnection` to repositories
- [ ] Use `OutboxProducer.sendInTransaction(event, connection)`
- [ ] **Do NOT** use `sendWithTransaction()`
- [ ] **Do NOT** inject Pool directly

### Dependencies (pom.xml)

- [ ] Include PeeGeeQ modules (api, db, outbox, etc.)
- [ ] Include Spring Boot starter (web or webflux)
- [ ] Include Vert.x dependencies (provided by PeeGeeQ)
- [ ] **Remove** R2DBC dependencies
- [ ] **Remove** separate PostgreSQL driver (PeeGeeQ provides it)

### Tests

- [ ] Tests verify database state after operations
- [ ] Tests verify rollback actually happens
- [ ] Tests check both business tables AND outbox table
- [ ] Tests use PeeGeeQ's infrastructure (not separate pools)

---

## 🔍 Common Mistakes

### Mistake 1: Creating Separate Pool
```java
// ❌ WRONG
@Bean
public Pool vertxPool(PeeGeeQManager manager) {
    return manager.getClientFactory()
        .getConnectionManager()
        .getOrCreateReactivePool(...);
}
```

**Fix**: Use DatabaseService instead
```java
// ✅ CORRECT
@Bean
public DatabaseService databaseService(PeeGeeQManager manager) {
    return new PgDatabaseService(manager);
}
```

### Mistake 2: Using R2DBC
```java
// ❌ WRONG
@Repository
public interface OrderRepository extends R2dbcRepository<Order, String> {
}
```

**Fix**: Create Vert.x-based repository
```java
// ✅ CORRECT
@Repository
public class OrderRepository {
    public Future<Order> save(Order order, SqlConnection connection) {
        // Use Vert.x SQL client
    }
}
```

### Mistake 3: Using sendWithTransaction()
```java
// ❌ WRONG
producer.sendWithTransaction(event, TransactionPropagation.CONTEXT)
    .thenCompose(v -> repository.save(data));
```

**Fix**: Use sendInTransaction() with shared connection
```java
// ✅ CORRECT
connectionProvider.withTransaction("peegeeq-main", connection -> {
    return repository.save(data, connection)
        .compose(v -> Future.fromCompletionStage(
            producer.sendInTransaction(event, connection)
        ));
});
```

---

## 📞 Getting Help

If you're unsure about a pattern:

1. Check `WRONG_VS_CORRECT_PATTERNS.md` for examples
2. Review `CORRECT_PEEGEEQ_API_USAGE.md` for detailed explanations
3. Look at working test examples in `peegeeq-outbox/src/test/java/.../examples/`

---

## 📝 Document Maintenance

When updating these documents:

1. Update the "Last Updated" date at the top
2. Add entries to this README if new documents are created
3. Keep examples consistent across all documents
4. Update the implementation status as work progresses

---

**Remember**: The purpose of these examples is to show how to **HOST PeeGeeQ in Spring Boot**, not to create separate database infrastructure. Use PeeGeeQ's provided APIs for everything.

