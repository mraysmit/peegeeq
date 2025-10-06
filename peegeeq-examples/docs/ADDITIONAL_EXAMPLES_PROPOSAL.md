# Additional Spring Boot Examples Proposal

**Date**: 2025-10-02  
**Status**: Proposal for Review  
**Based on**: peegeeq-outbox and peegeeq-bitemporal test examples

---

## Overview

This proposal outlines additional Spring Boot examples that demonstrate advanced PeeGeeQ features beyond the basic producer patterns currently implemented. These examples are based on proven patterns from the existing test suites.

---

## Current State

### ✅ Already Implemented
1. **springboot** - Non-reactive producer with transactional outbox
2. **springboot2** - Reactive producer with transactional outbox

### 🎯 Proposed Additions

---

## Category 1: Outbox Consumer Examples

### Example 1: Consumer Group Pattern (springboot-consumer)
**Based on**: `ConsumerGroupExampleTest.java`

**Purpose**: Demonstrate message consumption with consumer groups

**Key Features**:
- Multiple consumers processing messages from same queue
- Message filtering based on headers
- Consumer group coordination
- Message acknowledgment patterns

**Spring Boot Integration**:
- `@Service` for consumer service
- `@PostConstruct` to start consumers
- `@PreDestroy` to stop consumers gracefully
- Configuration properties for consumer settings

**Use Case**: Order processing system where multiple workers process orders in parallel

**Estimated Effort**: 6-8 hours

---

### Example 2: Dead Letter Queue Handling (springboot-dlq)
**Based on**: `OutboxDeadLetterQueueTest.java`

**Purpose**: Demonstrate dead letter queue pattern for failed messages

**Key Features**:
- Automatic retry with configurable max retries
- Dead letter queue for permanently failed messages
- DLQ monitoring and alerting
- Manual DLQ message reprocessing

**Spring Boot Integration**:
- `@Scheduled` for DLQ monitoring
- REST endpoints for DLQ management
- Metrics for DLQ depth
- Admin UI for DLQ inspection

**Use Case**: Payment processing with retry logic and manual intervention for failures

**Estimated Effort**: 8-10 hours

---

### Example 3: Retry and Failure Handling (springboot-retry)
**Based on**: `RetryAndFailureHandlingExampleTest.java`

**Purpose**: Demonstrate configurable retry strategies

**Key Features**:
- Configurable max retries via properties
- Exponential backoff (if supported)
- Circuit breaker integration
- Failure metrics and monitoring

**Spring Boot Integration**:
- `@ConfigurationProperties` for retry settings
- Integration with Spring Retry (optional)
- Micrometer metrics for retry counts
- Health indicators for circuit breaker state

**Use Case**: External API integration with transient failure handling

**Estimated Effort**: 6-8 hours

---

## Category 2: Bi-Temporal Event Store Examples

### Example 4: Bi-Temporal Event Store (springboot-bitemporal)
**Based on**: `BiTemporalEventStoreExampleTest.java`

**Purpose**: Demonstrate bi-temporal event storage and querying

**Key Features**:
- Append-only event storage with valid time and transaction time
- Historical point-in-time queries
- Event corrections without losing audit trail
- Real-time event subscriptions via LISTEN/NOTIFY

**Spring Boot Integration**:
- `EventStore` bean configuration
- REST endpoints for event queries
- WebSocket for real-time event streaming
- Query service for historical views

**Use Case**: Financial audit system with complete history tracking

**Estimated Effort**: 10-12 hours

---

### Example 5: Transactional Bi-Temporal (springboot-bitemporal-tx)
**Based on**: `TransactionalBiTemporalExampleTest.java`

**Purpose**: Demonstrate coordinated transactions across multiple event stores

**Key Features**:
- Multiple event stores in single transaction
- Complex business workflows (Order + Payment)
- Transactional consistency guarantees
- Rollback scenarios

**Spring Boot Integration**:
- Service layer coordinating multiple event stores
- Transaction management with `ConnectionProvider`
- REST endpoints for complex workflows
- Saga pattern demonstration

**Use Case**: E-commerce order fulfillment with payment processing

**Estimated Effort**: 12-15 hours

---

## Category 3: Combined Outbox + Bi-Temporal Examples

### Example 6: Outbox + Bi-Temporal Integration (springboot-integrated)
**Based on**: User's preference for integrated examples

**Purpose**: Demonstrate how outbox and bi-temporal work together

**Key Features**:
- Database changes + outbox events + event store in single transaction
- Real-time processing via outbox consumers
- Historical queries via bi-temporal store
- Complete audit trail

**Spring Boot Integration**:
- Service layer using both `OutboxProducer` and `EventStore`
- Single transaction coordinating all operations
- REST endpoints for commands and queries
- WebSocket for real-time updates

**Use Case**: Order management system with:
- Immediate order processing (outbox)
- Complete order history (bi-temporal)
- Real-time notifications (outbox consumers)
- Historical reporting (bi-temporal queries)

**Estimated Effort**: 15-18 hours

---

## Category 4: Advanced Patterns

### Example 7: Message Priority and Filtering (springboot-priority)
**Based on**: `MessagePriorityExampleTest.java`

**Purpose**: Demonstrate message prioritization and filtering

**Key Features**:
- Priority-based message processing
- Header-based message filtering
- Selective message consumption
- Priority queue patterns

**Spring Boot Integration**:
- Multiple consumers with different filters
- Priority configuration via properties
- Metrics for priority queue depths

**Use Case**: Support ticket system with priority levels

**Estimated Effort**: 6-8 hours

---

### Example 8: Reactive Bi-Temporal (springboot2-bitemporal)
**Based on**: Combination of reactive patterns + bi-temporal

**Purpose**: Demonstrate reactive bi-temporal event store usage

**Key Features**:
- Reactive event store operations returning `Mono`/`Flux`
- Non-blocking historical queries
- Reactive event subscriptions
- WebFlux integration

**Spring Boot Integration**:
- Reactive service layer with `ReactiveEventStoreAdapter`
- WebFlux REST endpoints
- Server-Sent Events for real-time updates
- Reactive query patterns

**Use Case**: Real-time analytics dashboard with historical data

**Estimated Effort**: 10-12 hours

---

## Recommended Implementation Priority

### Phase 1: Core Consumer Patterns (20-26 hours)
1. **springboot-consumer** - Consumer groups (6-8 hours)
2. **springboot-dlq** - Dead letter queue (8-10 hours)
3. **springboot-retry** - Retry handling (6-8 hours)

**Rationale**: Completes the outbox pattern story (producer + consumer)

### Phase 2: Bi-Temporal Basics (10-12 hours)
4. **springboot-bitemporal** - Basic bi-temporal (10-12 hours)

**Rationale**: Introduces event store concepts

### Phase 3: Advanced Integration (27-33 hours)
5. **springboot-bitemporal-tx** - Transactional bi-temporal (12-15 hours)
6. **springboot-integrated** - Outbox + Bi-temporal (15-18 hours)

**Rationale**: Shows how everything works together

### Phase 4: Optional Enhancements (16-20 hours)
7. **springboot-priority** - Priority and filtering (6-8 hours)
8. **springboot2-bitemporal** - Reactive bi-temporal (10-12 hours)

**Rationale**: Advanced patterns for specific use cases

---

## Total Estimated Effort

- **Phase 1**: 20-26 hours
- **Phase 2**: 10-12 hours
- **Phase 3**: 27-33 hours
- **Phase 4**: 16-20 hours

**Total**: 73-91 hours (approximately 2-3 weeks of focused development)

---

## Success Criteria

For each example:
1. ✅ Uses correct PeeGeeQ API patterns (DatabaseService, ConnectionProvider)
2. ✅ All tests passing
3. ✅ Demonstrates real-world use case
4. ✅ Includes comprehensive documentation
5. ✅ Follows Spring Boot best practices
6. ✅ No separate connection pools or R2DBC
7. ✅ Proper error handling and logging

---

## Documentation Updates

Each example should include:
1. **README.md** - Overview and quick start
2. **Code comments** - Inline documentation
3. **Integration guide section** - Added to SPRING_BOOT_INTEGRATION_GUIDE.md
4. **Test documentation** - Expected behaviors

---

## Questions for Review

1. **Priority**: Which phase should we start with?
2. **Scope**: Should we implement all examples or focus on specific ones?
3. **Use Cases**: Are the proposed use cases realistic for your needs?
4. **Reactive**: Should we prioritize reactive examples (springboot2-*)?
5. **Integration**: Is the combined outbox + bi-temporal example important?

---

## Next Steps

Once approved:
1. Create detailed implementation plan for selected examples
2. Set up project structure for new examples
3. Implement examples in priority order
4. Update documentation
5. Run comprehensive tests

---

## Appendix: Implementation Status

**Last Updated**: 2025-10-07

### ✅ Implemented Examples (8/10 - 80% Complete)

| Example | Status | Phase | Directory | Application Class | Tests |
|---------|--------|-------|-----------|-------------------|-------|
| ➡️ **springboot** | ✅ Complete | Current | `springboot/` | `SpringBootOutboxApplication.java` | ✅ Passing |
| ➡️ **springboot2** | ✅ Complete | Current | `springboot2/` | `SpringBootReactiveOutboxApplication.java` | ✅ Passing |
| ➡️ **springboot-consumer** | ✅ Complete | Phase 1 | `springbootconsumer/` | `SpringBootConsumerApplication.java` | ✅ Passing |
| ➡️ **springboot-dlq** | ✅ Complete | Phase 1 | `springbootdlq/` | `SpringBootDlqApplication.java` | ✅ Passing |
| ➡️ **springboot-retry** | ✅ Complete | Phase 1 | `springbootretry/` | `SpringBootRetryApplication.java` | ✅ Passing |
| ➡️ **springboot-bitemporal** | ✅ Complete | Phase 2 | `springbootbitemporal/` | `SpringBootBitemporalApplication.java` | ✅ Passing |
| ➡️ **springboot-bitemporal-tx** | ✅ Complete | Phase 3 | `springbootbitemporaltx/` | `SpringBootBitemporalTxApplication.java` | ✅ Passing |
| ➡️ **springboot-integrated** | ✅ Complete | Phase 3 | `springbootintegrated/` | `SpringBootIntegratedApplication.java` | ✅ **4/4 Passing** |

### ❌ Not Yet Implemented (2/10 - 20% Remaining)

| Example | Status | Phase | Estimated Effort |
|---------|--------|-------|------------------|
| ⏸️ **springboot-priority** | ❌ Pending | Phase 4 | 6-8 hours |
| ⏸️ **springboot2-bitemporal** | ❌ Pending | Phase 4 | 10-12 hours |

### 📊 Progress Summary

**Phase Completion:**
- ✅ **Phase 1: Core Consumer Patterns** - **COMPLETE** (3/3 examples)
  - ➡️ springboot-consumer ✅
  - ➡️ springboot-dlq ✅
  - ➡️ springboot-retry ✅

- ✅ **Phase 2: Bi-Temporal Basics** - **COMPLETE** (1/1 example)
  - ➡️ springboot-bitemporal ✅

- ✅ **Phase 3: Advanced Integration** - **COMPLETE** (2/2 examples)
  - ➡️ springboot-bitemporal-tx ✅
  - ➡️ springboot-integrated ✅

- ❌ **Phase 4: Optional Enhancements** - **NOT STARTED** (0/2 examples)
  - ⏸️ springboot-priority ❌ (pending)
  - ⏸️ springboot2-bitemporal ❌ (pending)

**Overall Progress**: 8/10 examples complete (80%)

### 📚 Documentation Status

| Document | Status | Location |
|----------|--------|----------|
| **Spring Boot Integration Guide** | ✅ Complete | `peegeeq-examples/docs/SPRING_BOOT_INTEGRATION_GUIDE.md` |
| **Additional Examples Proposal** | ✅ Complete | `peegeeq-examples/docs/ADDITIONAL_EXAMPLES_PROPOSAL.md` |
| **Individual Example READMEs** | ✅ Complete | Each example directory has README.md |

### 🎯 Remaining Work

To complete the proposal:

1. **springboot-priority** (Phase 4 - Medium Priority)
   - Message prioritization and filtering
   - Header-based routing
   - Estimated: 6-8 hours

3. **springboot2-bitemporal** (Phase 4 - Low Priority)
   - Reactive bi-temporal patterns
   - WebFlux integration
   - Estimated: 10-12 hours

**Total Remaining Effort**: 16-20 hours (approximately 2-3 days of focused development)

### 📝 Notes

- All implemented examples follow the patterns documented in `SPRING_BOOT_INTEGRATION_GUIDE.md`
- All examples use correct PeeGeeQ API patterns (DatabaseService, ConnectionProvider)
- All examples include comprehensive README.md documentation
- All examples have passing integration tests with TestContainers
- No examples use separate connection pools or R2DBC (as per design principles)

### 🎉 Recent Completion: springboot-integrated

**Completed**: 2025-10-07

The `springboot-integrated` example demonstrates the complete integration of:
- **Transactional Outbox Pattern** - For reliable message delivery
- **Bi-Temporal Event Store** - For complete audit trail and historical queries
- **Single Transaction Coordination** - All operations commit or rollback together

**Test Results**: All 4 tests passing
1. ✅ `testIntegratedTransactionSuccess` - Verifies all three operations commit together
2. ✅ `testQueryOrderHistory` - Tests retrieving order history from event store
3. ✅ `testQueryCustomerOrders` - Tests querying orders by customer
4. ✅ `testPointInTimeQuery` - Tests bi-temporal point-in-time queries

**Key Implementation Details**:
- Uses `ConnectionProvider.withTransaction()` to coordinate all operations
- Single `SqlConnection` passed to database, outbox, and event store operations
- Proper timestamp handling with `TIMESTAMPTZ` and `OffsetDateTime`
- Complete database schema matching PeeGeeQ requirements
- Comprehensive verification of all three storage layers

