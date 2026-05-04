# Vert.x Instance Consolidation Refactoring Plan

```
    ____            ______            ____
   / __ \___  ___  / ____/__  ___    / __ \
  / /_/ / _ \/ _ \/ / __/ _ \/ _ \  / / / /
 / ____/  __/  __/ /_/ /  __/  __/ / /_/ /
/_/    \___/\___/\____/\___/\___/  \___\_\

PostgreSQL Event-Driven Queue System
```

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Date**: January 2025  
**Version**: Phase 2 Architectural Refactoring  
**Status**: Technical Debt - Planned Implementation

---

## Executive Summary

This document provides a systematic analysis and implementation plan for eliminating multiple Vert.x instances across the PeeGeeQ project. The current architecture creates separate Vert.x instances in multiple components, leading to resource waste, thread proliferation, and the warning: *"You're already on a Vert.x context, are you sure you want to create a new Vertx instance?"*

**Impact**: Moderate to High performance impact in production environments  
**Priority**: Medium - Address during next major refactoring cycle  
**Effort**: 3-5 days of focused development work  

---

## Problem Analysis

### Current Architecture Issues

#### **Multiple Vert.x Instances Identified**
1. **PeeGeeQManager** - Primary instance (✅ Correct)
2. **PgNativeQueueConsumer** - Creates shared static instance
3. **OutboxProducer** - Creates shared static instance  
4. **OutboxConsumer** - Creates shared static instance
5. **PgBiTemporalEventStore** - Creates shared static instance
6. **VertxPoolAdapter** (bitemporal) - Creates shared static instance
7. **VertxPoolAdapter** (native) - Creates instance per constructor
8. **REST Server applications** - Create standalone instances (✅ Correct for standalone apps)

#### **Resource Impact Per Instance**
- **Event loop threads**: 2 × CPU cores (default)
- **Worker pool threads**: 20 threads (default)  
- **Internal blocking pool**: 20 threads (default)
- **Memory overhead**: Event loops, thread pools, connection pools
- **Connection pool duplication**: HTTP clients, database connections

#### **Root Cause Analysis**
Following PGQ Coding Principle #1 ("Investigate First"), the root cause is:

**Architectural Pattern**: Components use static `getOrCreateSharedVertx()` methods instead of accepting Vert.x instances via dependency injection, because PeeGeeQManager didn't expose its Vert.x instance until recently.

---

## Solution Architecture

### **Phase 1: Foundation** ✅ **COMPLETED**
- [x] Added `getVertx()` method to PeeGeeQManager
- [x] Documented the architectural issue
- [x] Established refactoring plan

### **Phase 2: Component Refactoring** (This Plan)

#### **Dependency Injection Pattern**
Replace static shared instances with constructor injection:

```java
// BEFORE: Static shared instance
private static Vertx getOrCreateSharedVertx() {
    if (sharedVertx == null) {
        synchronized (ComponentClass.class) {
            if (sharedVertx == null) {
                sharedVertx = Vertx.vertx(); // Creates new instance!
            }
        }
    }
    return sharedVertx;
}

// AFTER: Constructor injection
public ComponentClass(Vertx vertx, /* other params */) {
    this.vertx = vertx; // Use provided instance
    // ... rest of constructor
}
```

---

## Implementation Plan

### **Step 1: Investigation and Preparation**

Following PGQ Coding Principle #2 ("Follow Patterns"), examine existing constructor patterns:

#### **1.1 Analyze Current Constructors**
```bash
# Find all component constructors that need Vert.x instances
grep -r "public.*Constructor.*Vertx" peegeeq-*/src/main/java/
grep -r "getOrCreateSharedVertx" peegeeq-*/src/main/java/
```

#### **1.2 Identify Factory Dependencies**
Map all factory classes that create components requiring Vert.x:
- `OutboxFactory`
- `PgNativeQueueFactory` 
- `BiTemporalEventStoreFactory`
- `VertxPoolAdapter` constructors

#### **1.3 Test Infrastructure Preparation**
Following PGQ Coding Principle #6 ("Validate Incrementally"):
```bash
# Ensure all modules are installed
mvn clean install -DskipTests
# Run baseline tests to establish current state
mvn test -pl peegeeq-examples -Dtest=SystemPropertiesConfigurationDemoTest
```

### **Step 2: Component Constructor Refactoring**

#### **2.1 PgNativeQueueConsumer Refactoring**

**File**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`

**Current Issue**:
```java
// Line 460: Creates separate Vert.x instance
Vertx vertx = getOrCreateSharedVertx();
```

**Refactoring Steps**:
1. Add constructor parameter: `Vertx vertx`
2. Remove static `sharedVertx` field
3. Remove `getOrCreateSharedVertx()` method
4. Update all instantiation points

**Implementation**:
```java
// BEFORE
public PgNativeQueueConsumer(String topic, Class<T> payloadType, /* other params */) {
    // ... existing params
    this.vertx = getOrCreateSharedVertx(); // REMOVE THIS
}

// AFTER  
public PgNativeQueueConsumer(Vertx vertx, String topic, Class<T> payloadType, /* other params */) {
    this.vertx = vertx; // Use provided instance
    // ... rest of constructor unchanged
}
```

#### **2.2 OutboxProducer Refactoring**

**File**: `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxProducer.java`

**Current Issue**:
```java
// Line 396: Creates separate Vert.x instance  
Vertx vertx = getOrCreateSharedVertx();
```

**Refactoring Steps**:
1. Add constructor parameter: `Vertx vertx`
2. Remove static `sharedVertx` field (line 708)
3. Remove `getOrCreateSharedVertx()` method (lines 699-709)
4. Update pool creation to use provided instance

#### **2.3 OutboxConsumer Refactoring**

**File**: `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java`

Similar pattern to OutboxProducer - add constructor injection.

#### **2.4 PgBiTemporalEventStore Refactoring**

**File**: `peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStore.java`

**Current Issue**:
```java
// Line 1413: Creates separate Vert.x instance
.using(getOrCreateSharedVertx())
```

**Refactoring**: Add Vert.x parameter to constructor and factory methods.

#### **2.5 VertxPoolAdapter Refactoring**

**Files**: 
- `peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/VertxPoolAdapter.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/VertxPoolAdapter.java`

**Current Issue**: Multiple constructors, some create new Vert.x instances

**Refactoring**: Standardize on constructor injection pattern.

### **Step 3: Factory Class Updates**

#### **3.1 OutboxFactory Updates**

**File**: `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java`

**Current**: Factory creates components that internally create Vert.x instances
**Target**: Factory passes PeeGeeQManager's Vert.x instance to components

```java
// BEFORE
@Override
public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
    return new OutboxProducer<>(topic, payloadType, /* params */);
    // OutboxProducer internally calls getOrCreateSharedVertx()
}

// AFTER
@Override  
public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
    Vertx vertx = extractVertxFromDatabaseService(); // Get from PeeGeeQManager
    return new OutboxProducer<>(vertx, topic, payloadType, /* params */);
}
```

#### **3.2 PgNativeQueueFactory Updates**

**File**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java`

Similar pattern - extract Vert.x from DatabaseService and pass to components.

#### **3.3 BiTemporalEventStoreFactory Updates**

Update factory to pass Vert.x instance to event store constructors.

### **Step 4: DatabaseService Integration**

#### **4.1 Extract Vert.x from DatabaseService**

Add utility method to extract Vert.x instance from DatabaseService:

```java
// In factory classes
private Vertx extractVertxFromDatabaseService() {
    if (databaseService instanceof PgDatabaseService) {
        PgDatabaseService pgService = (PgDatabaseService) databaseService;
        return pgService.getPeeGeeQManager().getVertx();
    }
    throw new IllegalStateException("Cannot extract Vert.x from DatabaseService");
}
```

### **Step 5: Testing and Validation**

Following PGQ Coding Principle #6 ("Validate Incrementally"):

#### **5.1 Unit Testing Strategy**
```bash
# Test each component refactoring individually
mvn test -pl peegeeq-native -Dtest=PgNativeQueueConsumerTest
mvn test -pl peegeeq-outbox -Dtest=OutboxProducerTest  
mvn test -pl peegeeq-bitemporal -Dtest=PgBiTemporalEventStoreTest
```

#### **5.2 Integration Testing Strategy**
```bash
# Test factory integration
mvn test -pl peegeeq-examples -Dtest=SystemPropertiesConfigurationDemoTest
mvn test -pl peegeeq-examples -Dtest=SpringBootIntegratedApplicationTest
```

#### **5.3 Resource Leak Testing**
```bash
# Verify no resource leaks with single Vert.x instance
mvn test -pl peegeeq-outbox -Dtest=OutboxResourceLeakDetectionTest
```

### **Step 6: Cleanup and Documentation**

#### **6.1 Remove Static Shared Instances**
- Remove all `static volatile Vertx sharedVertx` fields
- Remove all `getOrCreateSharedVertx()` methods
- Update PeeGeeQManager cleanup to remove reflection-based cleanup

#### **6.2 Update Documentation**
- Update architecture diagrams
- Update component initialization guides
- Document new constructor patterns

---

## Risk Assessment

### **Low Risk Areas**
- ✅ PeeGeeQManager already exposes `getVertx()`
- ✅ Factory pattern already established
- ✅ Test infrastructure in place

### **Medium Risk Areas**
- ⚠️ Constructor signature changes affect multiple modules
- ⚠️ Spring Boot integration may need configuration updates
- ⚠️ Existing applications using factories directly

### **Mitigation Strategies**
1. **Backward Compatibility**: Keep old constructors temporarily with `@Deprecated`
2. **Incremental Migration**: Update one component at a time
3. **Comprehensive Testing**: Test each change before proceeding
4. **Documentation**: Clear migration guide for external users

---

## Success Criteria

### **Functional Requirements**
- [ ] All components use single shared Vert.x instance
- [ ] No "multiple Vert.x instance" warnings in logs
- [ ] All existing tests pass
- [ ] No resource leaks detected

### **Performance Requirements**  
- [ ] Reduced thread count (eliminate duplicate thread pools)
- [ ] Reduced memory footprint (eliminate duplicate event loops)
- [ ] Improved connection pool efficiency

### **Code Quality Requirements**
- [ ] No static shared Vert.x instances remain
- [ ] Clean constructor injection pattern
- [ ] Updated documentation and examples

---

## Implementation Timeline

### **Week 1: Preparation and Analysis**
- Day 1-2: Complete investigation and constructor analysis
- Day 3: Update build and test infrastructure
- Day 4-5: Create component refactoring templates

### **Week 2: Component Refactoring**
- Day 1: PgNativeQueueConsumer refactoring
- Day 2: OutboxProducer/Consumer refactoring  
- Day 3: PgBiTemporalEventStore refactoring
- Day 4: VertxPoolAdapter refactoring
- Day 5: Factory class updates

### **Week 3: Integration and Testing**
- Day 1-2: Integration testing and bug fixes
- Day 3: Performance validation
- Day 4: Documentation updates
- Day 5: Final cleanup and code review

---

## Next Steps

1. **Immediate**: Review and approve this refactoring plan
2. **Preparation**: Set up development branch and testing environment
3. **Investigation**: Complete detailed constructor analysis per Step 1
4. **Implementation**: Begin component refactoring following incremental approach

Following PGQ Coding Principle #10: "Work incrementally and test after each small incremental change"

---

## Detailed Implementation Steps

### **Component-Specific Refactoring Details**

#### **PgNativeQueueConsumer Implementation**

**Current Constructor Analysis**:
```java
// Current problematic pattern (lines 460-470)
public PgNativeQueueConsumer(String topic, Class<T> payloadType,
                           PgClientFactory clientFactory, ObjectMapper objectMapper,
                           PeeGeeQMetrics metrics, PeeGeeQConfiguration configuration) {
    // ... parameter assignments
    this.vertx = getOrCreateSharedVertx(); // PROBLEM: Creates separate instance
}
```

**Target Constructor Pattern**:
```java
// New constructor with Vert.x injection
public PgNativeQueueConsumer(Vertx vertx, String topic, Class<T> payloadType,
                           PgClientFactory clientFactory, ObjectMapper objectMapper,
                           PeeGeeQMetrics metrics, PeeGeeQConfiguration configuration) {
    this.vertx = vertx; // Use provided instance
    // ... rest of constructor unchanged
}

// Deprecated backward compatibility constructor
@Deprecated
public PgNativeQueueConsumer(String topic, Class<T> payloadType,
                           PgClientFactory clientFactory, ObjectMapper objectMapper,
                           PeeGeeQMetrics metrics, PeeGeeQConfiguration configuration) {
    this(getOrCreateSharedVertx(), topic, payloadType, clientFactory,
         objectMapper, metrics, configuration);
    logger.warn("Using deprecated constructor - please migrate to Vert.x injection pattern");
}
```

#### **OutboxProducer Implementation**

**Current Issue Location**:
```java
// Line 396: Problematic Vert.x creation
private void ensureVertxContext(Supplier<Future<Void>> operation, Promise<Void> promise) {
    Vertx vertx = getOrCreateSharedVertx(); // PROBLEM
    Context context = vertx.getOrCreateContext();
    // ...
}
```

**Target Implementation**:
```java
// Constructor injection
public OutboxProducer(Vertx vertx, String topic, Class<T> payloadType,
                     PgClientFactory clientFactory, ObjectMapper objectMapper,
                     PeeGeeQMetrics metrics, PeeGeeQConfiguration configuration) {
    this.vertx = vertx; // Store provided instance
    // ... rest of constructor
}

// Updated context management
private void ensureVertxContext(Supplier<Future<Void>> operation, Promise<Void> promise) {
    Context context = vertx.getOrCreateContext(); // Use injected instance
    // ... rest unchanged
}
```

#### **Factory Integration Pattern**

**OutboxFactory Update**:
```java
// Current factory method
@Override
public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
    checkNotClosed();
    logger.info("Creating outbox producer for topic: {}", topic);

    // BEFORE: Component creates its own Vert.x
    return new OutboxProducer<>(topic, payloadType, clientFactory,
                               objectMapper, getMetrics(), configuration);
}

// AFTER: Factory provides Vert.x instance
@Override
public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
    checkNotClosed();
    logger.info("Creating outbox producer for topic: {}", topic);

    Vertx vertx = extractVertxFromDatabaseService();
    return new OutboxProducer<>(vertx, topic, payloadType, clientFactory,
                               objectMapper, getMetrics(), configuration);
}

// Utility method to extract Vert.x from DatabaseService
private Vertx extractVertxFromDatabaseService() {
    if (databaseService instanceof PgDatabaseService) {
        PgDatabaseService pgService = (PgDatabaseService) databaseService;
        return pgService.getPeeGeeQManager().getVertx();
    }
    throw new IllegalStateException("Cannot extract Vert.x instance from DatabaseService: "
                                  + databaseService.getClass().getSimpleName());
}
```

### **Testing Strategy Details**

#### **Unit Test Updates**

**Test Constructor Changes**:
```java
// BEFORE: Test creates component that internally creates Vert.x
@Test
void testProducerCreation() {
    OutboxProducer<String> producer = new OutboxProducer<>("test-topic", String.class,
                                                          clientFactory, objectMapper,
                                                          metrics, configuration);
    // Test logic...
}

// AFTER: Test provides Vert.x instance
@Test
void testProducerCreation() {
    Vertx testVertx = Vertx.vertx(); // Test-specific instance
    try {
        OutboxProducer<String> producer = new OutboxProducer<>(testVertx, "test-topic",
                                                              String.class, clientFactory,
                                                              objectMapper, metrics, configuration);
        // Test logic...
    } finally {
        testVertx.close(); // Clean up test instance
    }
}
```

#### **Integration Test Validation**

**Resource Leak Detection**:
```java
@Test
void testSingleVertxInstanceUsage() {
    // Verify only one Vert.x instance is created
    Set<Vertx> vertxInstances = new HashSet<>();

    // Create multiple components
    MessageProducer<String> producer1 = factory.createProducer("topic1", String.class);
    MessageProducer<String> producer2 = factory.createProducer("topic2", String.class);
    MessageConsumer<String> consumer1 = factory.createConsumer("topic1", String.class);

    // Verify all use same Vert.x instance (implementation-specific verification)
    // This would require exposing Vert.x instance for testing or using reflection

    assertThat("All components should use the same Vert.x instance",
               vertxInstances.size(), equalTo(1));
}
```

### **Migration Checklist**

#### **Pre-Refactoring Validation**
- [ ] All modules compile successfully: `mvn clean compile`
- [ ] All tests pass: `mvn test`
- [ ] No existing resource leaks: Run with `-XX:+PrintGCDetails`
- [ ] Baseline performance metrics captured

#### **Component Refactoring Checklist**
- [ ] **PgNativeQueueConsumer**
  - [ ] Add Vert.x constructor parameter
  - [ ] Remove static `sharedVertx` field
  - [ ] Remove `getOrCreateSharedVertx()` method
  - [ ] Update all instantiation points
  - [ ] Add deprecated backward compatibility constructor
  - [ ] Unit tests pass

- [ ] **OutboxProducer**
  - [ ] Add Vert.x constructor parameter
  - [ ] Remove static `sharedVertx` field
  - [ ] Remove `getOrCreateSharedVertx()` method
  - [ ] Update context management methods
  - [ ] Unit tests pass

- [ ] **OutboxConsumer**
  - [ ] Same pattern as OutboxProducer
  - [ ] Unit tests pass

- [ ] **PgBiTemporalEventStore**
  - [ ] Add Vert.x constructor parameter
  - [ ] Update factory integration
  - [ ] Unit tests pass

- [ ] **VertxPoolAdapter** (both versions)
  - [ ] Standardize constructor patterns
  - [ ] Remove instance creation logic
  - [ ] Unit tests pass

#### **Factory Integration Checklist**
- [ ] **OutboxFactory**
  - [ ] Add `extractVertxFromDatabaseService()` method
  - [ ] Update `createProducer()` method
  - [ ] Update `createConsumer()` method
  - [ ] Integration tests pass

- [ ] **PgNativeQueueFactory**
  - [ ] Add Vert.x extraction logic
  - [ ] Update component creation methods
  - [ ] Integration tests pass

- [ ] **BiTemporalEventStoreFactory**
  - [ ] Update event store creation
  - [ ] Integration tests pass

#### **System Integration Validation**
- [ ] Spring Boot examples work correctly
- [ ] Standalone examples work correctly
- [ ] No Vert.x warnings in logs
- [ ] Performance metrics show improvement
- [ ] Resource usage reduced (threads, memory)

### **Rollback Strategy**

#### **If Issues Arise**
1. **Immediate Rollback**: Revert to previous commit
2. **Partial Rollback**: Keep deprecated constructors active
3. **Gradual Migration**: Enable new pattern via feature flag

#### **Rollback Indicators**
- Test failures that cannot be quickly resolved
- Performance degradation
- Resource leaks detected
- Integration issues with external systems

---

## Code Quality Standards

### **Constructor Pattern Standards**
```java
// Standard pattern for all components
public ComponentClass(Vertx vertx, /* required params */, /* optional params */) {
    this.vertx = Objects.requireNonNull(vertx, "Vert.x instance cannot be null");
    // ... parameter validation and assignment
}

// Deprecated backward compatibility (temporary)
@Deprecated
public ComponentClass(/* old signature */) {
    this(getOrCreateSharedVertx(), /* forward to new constructor */);
    logger.warn("Using deprecated constructor for {} - migrate to Vert.x injection",
                getClass().getSimpleName());
}
```

### **Factory Pattern Standards**
```java
// Standard Vert.x extraction pattern
private Vertx extractVertxFromDatabaseService() {
    if (databaseService instanceof PgDatabaseService) {
        PgDatabaseService pgService = (PgDatabaseService) databaseService;
        return pgService.getPeeGeeQManager().getVertx();
    }
    throw new IllegalStateException("Cannot extract Vert.x from DatabaseService: "
                                  + databaseService.getClass().getSimpleName());
}
```

### **Logging Standards**
```java
// Log Vert.x instance usage for debugging
logger.debug("Using Vert.x instance: {} for component: {}",
             System.identityHashCode(vertx), getClass().getSimpleName());
```

---

*This comprehensive refactoring plan follows PGQ coding principles and provides a systematic approach to eliminating multiple Vert.x instances while maintaining backward compatibility and system stability.*
