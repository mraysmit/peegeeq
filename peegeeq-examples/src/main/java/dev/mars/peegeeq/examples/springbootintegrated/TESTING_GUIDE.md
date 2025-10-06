# Spring Boot Integrated Example - Testing Guide

## Overview

This document describes the functionality implemented in the `springboot-integrated` example and the tests required to validate it.

## Implemented Functionality

### 1. Core Integration Pattern

**OrderService.createOrder()**
- **Purpose**: Creates an order with integrated outbox + bi-temporal pattern
- **Operations** (all in SINGLE transaction):
  1. Save order to `orders` table
  2. Send event to `outbox` table (for immediate consumer processing)
  3. Append event to bi-temporal event store (for historical queries)
- **Transaction Semantics**: All three operations commit together or rollback together
- **Key Classes**:
  - `OrderService.java` - Service layer with transaction coordination
  - `OrderRepository.java` - Database operations
  - `IntegratedConfig.java` - Spring Boot configuration

### 2. Query Operations

**OrderService.getOrderHistory()**
- **Purpose**: Retrieves complete history of an order from bi-temporal store
- **Returns**: `OrderResponse` with list of `BiTemporalEvent<OrderEvent>`
- **Use Case**: Audit trail, compliance, historical analysis

**OrderService.getCustomerOrders()**
- **Purpose**: Retrieves all orders for a specific customer
- **Returns**: List of `BiTemporalEvent<OrderEvent>`
- **Use Case**: Customer order history, analytics

**OrderService.getOrdersAsOfTime()**
- **Purpose**: Point-in-time query - what orders existed at a specific timestamp
- **Returns**: List of `BiTemporalEvent<OrderEvent>` valid at that time
- **Use Case**: Regulatory reporting, time-travel queries

### 3. REST API

**OrderController**
- `POST /api/orders` - Create new order
- `GET /api/orders/{orderId}/history` - Get order history
- `GET /api/orders/customer/{customerId}` - Get customer orders
- `GET /api/orders/as-of` - Point-in-time query

## Test Requirements

### Test 1: Integrated Transaction Success ✅

**Test**: `testIntegratedTransactionSuccess()`

**Purpose**: Verify that all three operations (database save, outbox send, event store append) complete successfully in a single transaction.

**Steps**:
1. Create order request with customer ID, amount, description
2. Call `orderService.createOrder(request)`
3. Verify order exists in `orders` table
4. Verify event exists in `outbox` table
5. Verify event exists in bi-temporal event store
6. Verify event details match request (order ID, customer ID, amount, status)

**Expected Result**: All three operations committed together

**Validation Queries**:
```sql
-- Verify order in database
SELECT COUNT(*) FROM orders WHERE id = ?

-- Verify event in outbox
SELECT COUNT(*) FROM outbox WHERE payload::text LIKE '%orderId%'

-- Verify event in event store (via OrderService.getOrderHistory)
```

### Test 2: Query Order History ✅

**Test**: `testQueryOrderHistory()`

**Purpose**: Verify that order history can be retrieved from bi-temporal store.

**Steps**:
1. Create an order
2. Call `orderService.getOrderHistory(orderId)`
3. Verify response contains order ID
4. Verify response contains at least 1 event
5. Verify event type is "OrderCreated"

**Expected Result**: Complete order history retrieved

### Test 3: Query Customer Orders ✅

**Test**: `testQueryCustomerOrders()`

**Purpose**: Verify that all orders for a customer can be retrieved.

**Steps**:
1. Create multiple orders for same customer (e.g., 2 orders)
2. Call `orderService.getCustomerOrders(customerId)`
3. Verify at least 2 orders returned
4. Verify all orders belong to the customer

**Expected Result**: All customer orders retrieved

### Test 4: Point-in-Time Query ✅

**Test**: `testPointInTimeQuery()`

**Purpose**: Verify bi-temporal queries work correctly.

**Steps**:
1. Record timestamp BEFORE creating orders
2. Create an order
3. Record timestamp AFTER creating orders
4. Query orders as of BEFORE timestamp
5. Query orders as of AFTER timestamp
6. Verify AFTER results >= BEFORE results

**Expected Result**: Point-in-time queries return correct data

### Test 5: Transaction Rollback (TODO)

**Test**: `testTransactionRollback()`

**Purpose**: Verify that if any operation fails, ALL operations rollback.

**Steps**:
1. Create order request
2. Simulate failure in one of the operations (e.g., invalid data)
3. Verify order does NOT exist in database
4. Verify event does NOT exist in outbox
5. Verify event does NOT exist in event store

**Expected Result**: Complete rollback - no partial data

**Implementation Note**: This test requires injecting a failure scenario, such as:
- Invalid SQL constraint violation
- Simulated database error
- Connection failure

### Test 6: REST API Integration (TODO)

**Test**: `testRestApiCreateOrder()`

**Purpose**: Verify REST endpoints work correctly.

**Steps**:
1. Use `MockMvc` or `TestRestTemplate` to POST to `/api/orders`
2. Verify HTTP 200 response
3. Verify response contains order ID
4. Verify order exists in database

**Expected Result**: REST API creates order successfully

### Test 7: Concurrent Order Creation (TODO)

**Test**: `testConcurrentOrderCreation()`

**Purpose**: Verify system handles concurrent requests correctly.

**Steps**:
1. Create multiple threads
2. Each thread creates an order simultaneously
3. Verify all orders created successfully
4. Verify no data corruption
5. Verify all events in outbox and event store

**Expected Result**: All concurrent operations succeed

## Test Infrastructure

### TestContainers Setup

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
    .withDatabaseName("peegeeq_integrated_test")
    .withUsername("postgres")
    .withPassword("password");

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("peegeeq.database.host", postgres::getHost);
    registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
    registry.add("peegeeq.database.name", postgres::getDatabaseName);
    registry.add("peegeeq.database.username", postgres::getUsername);
    registry.add("peegeeq.database.password", postgres::getPassword);
}
```

### Helper Methods

**verifyOrderInDatabase(String orderId)**
- Queries `orders` table to verify order exists
- Returns `boolean`

**verifyEventInOutbox(String orderId)**
- Queries `outbox` table to verify event exists
- Returns `boolean`

## Running Tests

### Run All Tests
```bash
mvn test -Dtest=SpringBootIntegratedApplicationTest -pl peegeeq-examples
```

### Run Single Test
```bash
mvn test -Dtest=SpringBootIntegratedApplicationTest#testIntegratedTransactionSuccess -pl peegeeq-examples
```

### Run with Debug Logging
```bash
mvn test -Dtest=SpringBootIntegratedApplicationTest -pl peegeeq-examples -X
```

## Test Status

| Test | Status | Notes |
|------|--------|-------|
| testIntegratedTransactionSuccess | ✅ Implemented | Verifies complete integration |
| testQueryOrderHistory | ✅ Implemented | Verifies bi-temporal queries |
| testQueryCustomerOrders | ✅ Implemented | Verifies customer queries |
| testPointInTimeQuery | ✅ Implemented | Verifies time-travel queries |
| testTransactionRollback | ⏳ TODO | Requires failure injection |
| testRestApiCreateOrder | ⏳ TODO | Requires MockMvc setup |
| testConcurrentOrderCreation | ⏳ TODO | Requires threading |

## Known Issues

1. **Pre-existing Test Compilation Errors**: There are compilation errors in other test files (`SimpleNativeQueueTest.java`, `EnhancedErrorHandlingDemoTest.java`) that prevent running tests. These need to be fixed separately.

2. **Async Operations**: Tests include `Thread.sleep(500)` to wait for async operations. This should be replaced with proper synchronization mechanisms (e.g., `CountDownLatch`, `CompletableFuture.allOf()`).

3. **Test Isolation**: Tests should clean up data between runs to ensure isolation. Consider adding `@BeforeEach` and `@AfterEach` methods to truncate tables.

## Next Steps

1. **Fix Pre-existing Test Errors**: Fix compilation errors in `SimpleNativeQueueTest.java` and `EnhancedErrorHandlingDemoTest.java`
2. **Run Tests**: Execute `SpringBootIntegratedApplicationTest` to verify functionality
3. **Implement TODO Tests**: Add transaction rollback, REST API, and concurrency tests
4. **Add Performance Tests**: Measure throughput and latency under load
5. **Add Chaos Tests**: Test behavior under failure scenarios (database down, network issues, etc.)

## Validation Checklist

Before marking the example as complete, verify:

- [ ] All implemented tests pass
- [ ] Code compiles without errors
- [ ] Application starts successfully
- [ ] REST API endpoints respond correctly
- [ ] Database schema is created automatically
- [ ] Outbox polling works correctly
- [ ] Event store queries return correct data
- [ ] Transaction rollback works as expected
- [ ] Documentation is complete and accurate
- [ ] README includes usage examples

