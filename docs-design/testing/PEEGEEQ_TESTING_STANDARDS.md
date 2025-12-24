# PeeGeeQ Testing Standards

## Overview

This document establishes comprehensive testing standards for all PeeGeeQ development increments, ensuring thorough validation at every stage of implementation.

## Core Testing Principles

### 1. **Test at Every Stage**
- Compile after each code change
- Run unit tests after each logical unit of work
- Run integration tests after each increment completion
- Validate database state after each transaction-related change

### 2. **Comprehensive Coverage Requirements**

#### **Unit Tests**
- Parameter validation logic
- Method signature verification
- Edge case handling
- Error condition testing

#### **Integration Tests**
- End-to-end functionality with TestContainers
- Database state validation
- Transaction consistency verification
- Cross-table data integrity checks

#### **Database State Validation**
- **CRITICAL**: Always verify actual database state, not just API responses
- Query all relevant tables to confirm data persistence
- Validate referential integrity between related tables
- Check temporal data (valid_time vs transaction_time)

### 3. **Transaction Testing Standards**

For any increment involving database transactions:

#### **Required Test Scenarios**
1. **Happy Path Transaction Commit**
   - Business operation + event operation in same transaction
   - Verify both operations committed successfully
   - Database state validation for all affected tables

2. **Database Consistency Verification**
   - Cross-reference data between business and event tables
   - Use correlation IDs to link related records
   - Validate headers, metadata, and payload serialization

3. **Transaction Timing Validation**
   - Verify transaction_time is within expected bounds
   - Validate valid_time vs transaction_time relationships
   - Check temporal consistency

4. **Rollback Scenarios** (when applicable)
   - Simulate failures after partial operations
   - Verify no partial data exists after rollback
   - Confirm database state is clean after failed transactions

### 4. **Test Structure Standards**

#### **Test Class Naming**
- Unit Tests: `[Feature]Test.java`
- Integration Tests: `[Feature]IntegrationTest.java`
- Follow plan specifications for test class names

#### **Test Method Naming**
- Use descriptive names: `testSimpleTransactionParticipation()`
- Include validation type: `testBusinessTableEventLogConsistency()`
- Use `@DisplayName` for human-readable descriptions

#### **Test Organization**
```java
@Test
@DisplayName("Clear description of what is being tested")
void testMethodName() throws Exception {
    // 1. Setup test data
    // 2. Execute operation
    // 3. Verify API response
    // 4. CRITICAL: Verify database state
    // 5. Validate cross-table consistency
    // 6. Check timing/temporal aspects
}
```

## Increment-Specific Testing Requirements

### **Phase 1: Foundation and Core Infrastructure**

#### **Increment 1.1: Method Signatures**
- ✅ Unit tests for method signature existence
- ✅ Compilation verification
- ✅ Parameter validation logic

#### **Increment 1.2: Core Implementation**
- ✅ Unit tests for parameter validation
- ✅ Integration tests with TestContainers
- ✅ Database state validation

#### **Increment 1.3: Error Handling**
- ✅ Edge case testing
- ✅ Error condition validation
- ✅ Exception handling verification

### **Phase 2: Integration and Transaction Scenarios**

#### **Increment 2.1: Simple Transaction Integration Tests** ✅ COMPLETED
- ✅ TestContainers-based integration tests
- ✅ Simple business operation + bitemporal event in same transaction
- ✅ Transaction commit verification with database state validation
- ✅ Business table + bitemporal_event_log consistency verification
- ✅ Data integrity validation queries
- ✅ Transaction timing validation

**Test Coverage Achieved:**
- `testSimpleTransactionParticipation()` - Basic transaction participation with comprehensive database validation
- `testBusinessTableEventLogConsistency()` - Cross-table consistency verification with correlation IDs
- `testTransactionCommitVerification()` - Transaction timing and commit validation

#### **Increment 2.2: Transaction Rollback Scenarios** ✅ COMPLETED
**Required Tests:**
- ✅ Transaction rollback after business operation failure
- ✅ Transaction rollback after bitemporal append failure
- ✅ Database state verification after rollback (no orphaned data)
- ✅ Partial operation cleanup validation
- ✅ Transaction boundary integrity tests

**Test Coverage Achieved:**
- `testBusinessOperationFailureAfterBiTemporalAppend()` - Rollback after business operation failure with comprehensive database validation
- `testBiTemporalAppendFailureAfterBusinessOperation()` - Rollback after bitemporal append failure with clean state verification
- `testTransactionBoundaryIntegrity()` - Complex multi-operation transaction rollback with no partial commits

#### **Increment 2.3: Multiple Operations in Single Transaction** (FUTURE)
**Required Tests:**
- Multiple `appendInTransaction` calls in same transaction
- Cross-operation consistency validation
- Performance impact assessment
- Resource cleanup verification

## Database Validation Patterns

### **Standard Validation Queries**

#### **Business Data Validation**
```sql
SELECT COUNT(*) FROM business_data WHERE [conditions]
```

#### **Event Data Validation**
```sql
SELECT COUNT(*) FROM bitemporal_event_log WHERE event_id = ? AND event_type = ?
```

#### **Cross-Table Consistency**
```sql
SELECT bd.*, bel.* 
FROM business_data bd 
CROSS JOIN bitemporal_event_log bel 
WHERE [correlation_conditions]
```

#### **Temporal Validation**
```sql
SELECT transaction_time, valid_time 
FROM bitemporal_event_log 
WHERE event_id = ?
```

### **Payload Integrity Validation**
```sql
SELECT payload FROM bitemporal_event_log WHERE event_id = ?
```
- Verify JSON serialization correctness
- Check for expected data fields
- Validate data types and values

## TestContainers Standards

### 🚨 **Critical Rules**

#### **1. ALWAYS Use PostgreSQLTestConstants**

```java
// ✅ CORRECT - Use the centralized constant
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

// OR with custom settings
@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createContainer(
    "custom_db_name",
    "custom_user",
    "custom_password");
```

```java
// ❌ WRONG - Never hardcode PostgreSQL versions
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
```

#### **2. Standard Container Patterns**

**Pattern A: Static Container (Recommended for Most Tests)**
```java
@Testcontainers
class MyTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        saveOriginalProperties();
        configureSystemPropertiesForContainer();
    }

    @AfterEach
    void tearDown() {
        restoreOriginalProperties();
    }
}
```

**Pattern B: Spring Boot Tests with @DynamicPropertySource**
```java
@SpringBootTest
@Testcontainers
class SpringBootTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");
    }
}
```

#### **3. System Property Configuration**

**Standard Property Names**
```java
private void configureSystemPropertiesForContainer() {
    // Standard PeeGeeQ properties
    System.setProperty("peegeeq.database.host", postgres.getHost());
    System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
    System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
    System.setProperty("peegeeq.database.username", postgres.getUsername());
    System.setProperty("peegeeq.database.password", postgres.getPassword());
    System.setProperty("peegeeq.database.schema", "public");
    System.setProperty("peegeeq.database.ssl.enabled", "false");
    System.setProperty("peegeeq.migration.enabled", "true");
    System.setProperty("peegeeq.migration.auto-migrate", "true");
}
```

**Property Cleanup Pattern**
```java
private final Map<String, String> originalProperties = new HashMap<>();

private void saveOriginalProperties() {
    String[] propertiesToSave = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.schema"
    };

    for (String property : propertiesToSave) {
        String value = System.getProperty(property);
        if (value != null) {
            originalProperties.put(property, value);
        }
    }
}

private void restoreOriginalProperties() {
    // Clear test properties
    System.clearProperty("peegeeq.database.host");
    System.clearProperty("peegeeq.database.port");
    System.clearProperty("peegeeq.database.name");
    System.clearProperty("peegeeq.database.username");
    System.clearProperty("peegeeq.database.password");
    System.clearProperty("peegeeq.database.schema");

    // Restore original properties
    originalProperties.forEach(System::setProperty);
}
```

#### **4. Container Configuration Options**

**Standard Container**
```java
PostgreSQLTestConstants.createStandardContainer()
// Uses: postgres:15.13-alpine3.20, 256MB shared memory, no reuse
```

**Custom Container**
```java
PostgreSQLTestConstants.createContainer("db_name", "username", "password")
// Uses: postgres:15.13-alpine3.20, 256MB shared memory, no reuse
```

**High Performance Container**
```java
PostgreSQLTestConstants.createHighPerformanceContainer("db_name", "username", "password")
// Uses: postgres:15.13-alpine3.20, 512MB shared memory, performance tuning
```

#### **5. Common Mistakes to Avoid**

❌ **Don't Do This:**
```java
// Hardcoded version
new PostgreSQLContainer<>("postgres:15")

// Missing @SuppressWarnings("resource")
@Container
static PostgreSQLContainer<?> postgres = ...

// No property cleanup
@BeforeEach
void setUp() {
    System.setProperty("db.host", postgres.getHost());
    // No cleanup in @AfterEach
}

// Inconsistent property names
System.setProperty("db.host", postgres.getHost());        // Wrong
System.setProperty("database.host", postgres.getHost());  // Wrong
```

✅ **Do This Instead:**
```java
// Use centralized constants
PostgreSQLTestConstants.createStandardContainer()

// Always suppress resource warnings for static containers
@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = ...

// Always clean up properties
@BeforeEach void setUp() { saveOriginalProperties(); configureSystemPropertiesForContainer(); }
@AfterEach void tearDown() { restoreOriginalProperties(); }

// Use standard property names
System.setProperty("peegeeq.database.host", postgres.getHost());
```

### **TestContainers Migration Tools**

**Check for Violations**
```bash
# Run the pre-commit check
./scripts/pre-commit-postgresql-check

# Search for hardcoded versions
findstr /R /S /C:"new PostgreSQLContainer.*postgres:" *.java
```

**Fix Violations**
```bash
# Run migration script (Linux/Mac)
./scripts/migrate-postgresql-versions.sh

# Run migration script (Windows)
powershell -ExecutionPolicy Bypass -File ./scripts/migrate-postgresql-versions.ps1
```

## Testing Checklist for Each Increment

### **Before Implementation**
- [ ] Review plan requirements for testing specifications
- [ ] Identify all database tables that will be affected
- [ ] Plan test scenarios covering happy path and edge cases
- [ ] Design correlation mechanisms for cross-table validation
- [ ] Import `dev.mars.peegeeq.test.PostgreSQLTestConstants`
- [ ] Plan TestContainers setup using standard patterns

### **During Implementation**
- [ ] Compile after each code change
- [ ] Run unit tests after each logical unit
- [ ] Test parameter validation thoroughly
- [ ] Verify error handling paths
- [ ] Use `PostgreSQLTestConstants.createStandardContainer()` or variants
- [ ] Add `@SuppressWarnings("resource")` to static containers

### **After Implementation**
- [ ] Run comprehensive integration tests
- [ ] Validate database state for all affected tables
- [ ] Check cross-table data consistency
- [ ] Verify temporal aspects (timing, valid_time, transaction_time)
- [ ] Test rollback scenarios (if applicable)
- [ ] Performance validation (if specified in plan)
- [ ] Implement property save/restore pattern in `@BeforeEach`/`@AfterEach`
- [ ] Use standard `peegeeq.database.*` property names
- [ ] No hardcoded PostgreSQL versions in code

### **Increment Completion Criteria**
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Database state validation confirms expected behavior
- [ ] Cross-table consistency verified
- [ ] Temporal data correctly stored and retrievable
- [ ] No resource leaks or cleanup issues
- [ ] Performance meets requirements (if specified)
- [ ] TestContainers standards compliance verified

## TestContainers Benefits

1. **Single PostgreSQL Version**: Only `postgres:15.13-alpine3.20` across entire project
2. **Consistent Configuration**: Standard property names and patterns
3. **Clean Test Isolation**: Proper property cleanup between tests
4. **Maintainable**: Easy to update PostgreSQL version project-wide
5. **No Docker Pollution**: Prevents multiple PostgreSQL images
6. **Standardized Setup**: All tests use the same container configuration
7. **Performance Optimized**: High-performance containers available for performance tests

## New Test Checklist (Complete)

### **TestContainers Standards Compliance**
- [ ] Import `dev.mars.peegeeq.test.PostgreSQLTestConstants`
- [ ] Use `PostgreSQLTestConstants.createStandardContainer()` or variants
- [ ] Add `@SuppressWarnings("resource")` to static containers
- [ ] Implement property save/restore pattern in `@BeforeEach`/`@AfterEach`
- [ ] Use standard `peegeeq.database.*` property names
- [ ] Test compiles and runs successfully
- [ ] No hardcoded PostgreSQL versions in code

### **Core Testing Requirements**
- [ ] Unit tests for all public methods
- [ ] Integration tests with TestContainers
- [ ] Database state validation for all affected tables
- [ ] Cross-table consistency verification
- [ ] Temporal data validation (valid_time, transaction_time)
- [ ] Transaction rollback scenarios (if applicable)
- [ ] Error handling and edge cases
- [ ] Performance validation (if specified)

## Commitment to Quality

**Every increment must meet these comprehensive testing standards before being considered complete.** This ensures:
- Robust, production-ready code
- Comprehensive validation of all functionality
- Early detection of integration issues
- Confidence in database consistency and transaction behavior
- Maintainable test suite for regression testing
- Consistent TestContainers usage across all modules
- Clean Docker environment without image pollution
- Standardized test patterns for all developers

**Testing is not optional - it is integral to the development process. TestContainers standards are mandatory for all database-related tests.**
