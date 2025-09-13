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

## Testing Checklist for Each Increment

### **Before Implementation**
- [ ] Review plan requirements for testing specifications
- [ ] Identify all database tables that will be affected
- [ ] Plan test scenarios covering happy path and edge cases
- [ ] Design correlation mechanisms for cross-table validation

### **During Implementation**
- [ ] Compile after each code change
- [ ] Run unit tests after each logical unit
- [ ] Test parameter validation thoroughly
- [ ] Verify error handling paths

### **After Implementation**
- [ ] Run comprehensive integration tests
- [ ] Validate database state for all affected tables
- [ ] Check cross-table data consistency
- [ ] Verify temporal aspects (timing, valid_time, transaction_time)
- [ ] Test rollback scenarios (if applicable)
- [ ] Performance validation (if specified in plan)

### **Increment Completion Criteria**
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Database state validation confirms expected behavior
- [ ] Cross-table consistency verified
- [ ] Temporal data correctly stored and retrievable
- [ ] No resource leaks or cleanup issues
- [ ] Performance meets requirements (if specified)

## Commitment to Quality

**Every increment must meet these testing standards before being considered complete.** This ensures:
- Robust, production-ready code
- Comprehensive validation of all functionality
- Early detection of integration issues
- Confidence in database consistency and transaction behavior
- Maintainable test suite for regression testing

**Testing is not optional - it is integral to the development process.**
