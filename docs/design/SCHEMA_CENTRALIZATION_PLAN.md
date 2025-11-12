# PeeGeeQ Schema Centralization Plan

**Author**: Mark Andrew Ray-Smith Cityline Ltd  
**Date**: 2025-11-11  
**Version**: 1.0  
**Status**: Phase 1 Complete  | Phase 2 In Progress 

**Problem**: Schema initialization is scattered across 8+ different files with massive duplication and inconsistencies.

**Solution**: Centralize all schema initialization in `peegeeq-test-support` with a single source of truth.

**Impact**: Eliminate duplication, ensure consistency, simplify maintenance, and recover lost migration knowledge.

## 📊 Current State Analysis

### ❌ Problems Identified

#### 1. **Massive Code Duplication**
- `peegeeq-outbox/TestSchemaInitializer.java` (166 lines)
- `peegeeq-outbox/examples/TestSchemaInitializer.java` (166 lines) **← EXACT DUPLICATE**
- `peegeeq-bitemporal/BiTemporalTestSchemaInitializer.java` (209 lines)
- `peegeeq-db/SharedPostgresExtension.java` (inline schema, 100+ lines)
- Multiple examples with inline schema creation

**Total Duplicated Code**: ~800+ lines across 8+ files

#### 2. **Inconsistent Schema Coverage**
| Module | Outbox | Native Queue | Dead Letter | Bitemporal | Triggers | Metrics |
|--------|--------|--------------|-------------|------------|----------|---------|
| outbox | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| bitemporal | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| native | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| db | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |

#### 3. **Lost Migration Knowledge**
- Complete schema exists in `peegeeq-migrations/V001__Create_Base_Tables.sql` (575 lines)
- Test initializers only implement fragments
- No connection between migration scripts and test setup

#### 4. **No Centralization**
- `peegeeq-test-support` exists but has NO centralized schema utilities
- Each module reinvents schema initialization
- No single source of truth

## ✅ Proposed Solution

### **Phase 1: Create Centralized Schema Utilities** ✅ COMPLETED

**Created**: `peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/schema/PeeGeeQTestSchemaInitializer.java`

**Features**:
- **Modular Design**: Initialize only needed components
- **Complete Schema**: Based on migration scripts
- **Consistent API**: Same interface across all modules
- **Proper Triggers**: Includes PostgreSQL NOTIFY/LISTEN functions

**Usage Examples**:
```java
// Initialize complete schema
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);

// Initialize only specific components
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, 
    SchemaComponent.OUTBOX, SchemaComponent.BITEMPORAL);

// Cleanup test data
PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, SchemaComponent.ALL);
```

### **Phase 2: Migration Plan**

#### **Step 1: Update Bitemporal Module** (PRIORITY 1)
**File**: `peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStoreIntegrationTest.java`

**Change**:
```java
// OLD
BiTemporalTestSchemaInitializer.initializeSchema(postgres);

// NEW
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);
```

**Benefits**:
- Remove 209 lines of duplicated code
- Ensure schema consistency with migration scripts
- Maintain existing functionality

#### **Step 2: Update Outbox Module** (PRIORITY 2)
**Files**:
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/AutomaticTransactionManagementExampleTest.java`
- All other outbox tests

**Change**:
```java
// OLD
TestSchemaInitializer.initializeSchema(postgres);

// NEW
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.OUTBOX);
```

**Benefits**:
- Remove 332 lines of duplicated code (2 identical files)
- Standardize outbox test setup

#### **Step 3: Update Native Module** (PRIORITY 3)
**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/NativeQueueIntegrationTest.java`

**Change**:
```java
// OLD - inline schema creation in test
private void initializeSchema() { /* 50+ lines */ }

// NEW
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, 
    SchemaComponent.NATIVE_QUEUE, SchemaComponent.DEAD_LETTER_QUEUE);
```

#### **Step 4: Update DB Module** (PRIORITY 4)
**File**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresExtension.java`

**Change**:
```java
// OLD - inline schema in initializeSchema()
private void initializeSchema() { /* 100+ lines */ }

// NEW
PeeGeeQTestSchemaInitializer.initializeSchema(
    container.getJdbcUrl(), container.getUsername(), container.getPassword(),
    SchemaComponent.OUTBOX, SchemaComponent.NATIVE_QUEUE, 
    SchemaComponent.DEAD_LETTER_QUEUE, SchemaComponent.METRICS);
```

#### **Step 5: Update Examples Module** (PRIORITY 5)
**Files**: Multiple example tests with inline schema

**Change**: Replace inline schema creation with centralized calls

### **Phase 3: Cleanup and Validation**

#### **Step 1: Remove Deprecated Files**
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/TestSchemaInitializer.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/TestSchemaInitializer.java`
- `peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/BiTemporalTestSchemaInitializer.java`

#### **Step 2: Run Full Test Suite**
```bash
mvn clean test
```

#### **Step 3: Validate Schema Consistency**
- Compare generated schema with migration scripts
- Ensure all tests pass
- Verify no functionality regression

## 📈 Expected Benefits

### **Immediate Benefits**
- **Remove 800+ lines of duplicated code**
- **Single source of truth for schema**
- **Consistent schema across all modules**
- **Recovered migration knowledge**

### **Long-term Benefits**
- **Easier maintenance**: One place to update schema
- **Better testing**: Consistent test environments
- **Faster development**: No need to recreate schema logic
- **Reduced bugs**: Eliminate schema inconsistencies

### **Quantified Impact**
| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Schema Files | 8+ files | 1 file | -87.5% |
| Lines of Code | 800+ lines | 487 lines | -39% |
| Maintenance Points | 8 locations | 1 location | -87.5% |
| Schema Coverage | Inconsistent | Complete | +100% |

## 🚀 Implementation Timeline

| Phase | Duration | Effort | Risk |
|-------|----------|--------|------|
| Phase 1: Create Centralized Utils | ✅ DONE | 4 hours | Low |
| Phase 2: Module Migration | 2-3 days | 8 hours | Medium |
| Phase 3: Cleanup & Validation | 1 day | 4 hours | Low |
| **Total** | **3-4 days** | **16 hours** | **Low-Medium** |

## ⚠️ Risk Assessment

### **Low Risk**
- Centralized schema initializer is based on proven migration scripts
- Modular design allows gradual migration
- Existing tests validate functionality

### **Medium Risk**
- Need to update multiple test files
- Potential for temporary test failures during migration
- Requires coordination across modules

### **Mitigation Strategies**
1. **Incremental Migration**: Update one module at a time
2. **Parallel Testing**: Keep old and new systems running during transition
3. **Rollback Plan**: Keep deprecated files until full validation
4. **Comprehensive Testing**: Run full test suite after each module update

## 🎯 Success Criteria

### **Technical Success**
- [ ] All tests pass with centralized schema
- [ ] Schema matches migration scripts exactly
- [ ] No functionality regression
- [ ] All deprecated files removed

### **Quality Success**
- [ ] 800+ lines of duplicated code eliminated
- [ ] Single source of truth established
- [ ] Documentation updated
- [ ] Code review completed

### **Business Success**
- [ ] Faster development cycles
- [ ] Reduced maintenance overhead
- [ ] Improved developer experience
- [ ] Better test reliability

## 📝 Next Steps

1. **Review and Approve Plan** - Get stakeholder buy-in
2. **Start Phase 2** - Begin module migration with bitemporal
3. **Incremental Updates** - Update one module at a time
4. **Continuous Validation** - Run tests after each update
5. **Final Cleanup** - Remove deprecated files and update documentation

---

**Status**: Phase 1 Complete ✅ | Ready for Phase 2 Implementation 🚀
