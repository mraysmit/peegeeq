# Consumer Group Fan-Out Implementation Plan

**Status**: Implementation Roadmap
**Author**: Mark Andrew Ray-Smith Cityline Ltd
**Date**: 2025-11-12
**Version**: 2.0
**Related Design**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) v2.0

---

## Executive Summary

This document provides a **phased implementation plan** for the Consumer Group Fan-Out feature, breaking down the work into manageable milestones with clear dependencies, testing requirements, and success criteria.

**Total Estimated Effort**: 8-12 weeks (2-3 sprints)
**Team Size**: 2-3 developers + 1 QA engineer
**Risk Level**: Medium-High (database schema changes, concurrency concerns)

---

## Implementation Strategy

### Guiding Principles

1. **Incremental delivery**: Each phase delivers working, testable functionality
2. **Backward compatibility**: Existing queue functionality continues to work unchanged
3. **Test-driven**: Write tests before implementation, validate at each phase
4. **Performance-aware**: Load test at each phase, not just at the end
5. **Fail-fast**: Validate assumptions early with prototypes and spikes

### Completion Tracking Mode Selection

**Decision Required**: Choose Reference Counting or Offset/Watermark mode based on expected fanout.

| Expected Fanout | Recommended Mode | Rationale |
|-----------------|------------------|-----------|
| ≤8 consumer groups | **Reference Counting** | Simple, proven, adequate performance |
| 9-16 consumer groups | **Reference Counting** (with monitoring) | Acceptable write amplification, plan migration path |
| >16 consumer groups | **Offset/Watermark** | Required for scalability |
| Unknown/Variable | **Start with Reference Counting** | Easier to implement, migrate later if needed |

**For this plan, we assume Reference Counting mode** (simpler, lower risk). Offset/Watermark mode is documented in Phase 7 (Optional).

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Implementation Strategy](#implementation-strategy)
3. [Phase 1: Foundation & Schema Changes](#phase-1-foundation--schema-changes-week-1-2)
4. [Phase 2: Consumer Group Subscription API](#phase-2-consumer-group-subscription-api-week-3-4)
5. [Phase 3: Message Production & Fan-Out](#phase-3-message-production--fan-out-week-5-6)
6. [Phase 4: Message Consumption & Completion](#phase-4-message-consumption--completion-week-7-8)
7. [Phase 5: Cleanup Jobs & Monitoring](#phase-5-cleanup-jobs--monitoring-week-9-10)
8. [Phase 6: Load Testing & Performance Validation](#phase-6-load-testing--performance-validation-week-11-12)
9. [Phase 7 (Optional): Offset/Watermark Mode](#phase-7-optional-offsetwatermark-mode-week-13-16)
10. [Phase 8 (Optional): Resumable Backfill](#phase-8-optional-resumable-backfill)
11. [Risk Management](#risk-management)
12. [Success Metrics](#success-metrics)
13. [Deployment & Rollback](#deployment--rollback)

---

## Phase 1: Foundation & Schema Changes (Week 1-2)

### Objectives

- Create database schema for consumer group fan-out
- Implement database migrations with rollback capability
- Validate schema changes on test environment
- Ensure backward compatibility with existing queue functionality

### Tasks

#### 1.1 Database Schema Migration
**Owner**: Backend Developer
**Effort**: 3 days

**Deliverables**:
- Migration file: `V010__Create_Consumer_Group_Fanout_Tables.sql` (already created)
- Tables: `outbox_topics`, `outbox_topic_subscriptions`, `outbox_consumer_groups`, `processed_ledger`, `partition_drop_audit`, `consumer_group_index`
- Enhancements to `outbox` table: Add `required_consumer_groups`, `completed_consumer_groups`, `completed_groups_bitmap` columns
- Indexes for performance
- Trigger for auto-creating consumer group tracking rows

**See**: Migration file for complete schema

#### 1.2 Schema Validation & Testing
**Owner**: QA Engineer
**Effort**: 2 days

**Test Cases**:
- Migration runs successfully on empty database
- Migration runs successfully on database with existing outbox data
- Rollback script works correctly
- All indexes created with correct definitions
- Foreign key constraints enforce referential integrity
- Trigger creates tracking rows correctly

**Exit Criteria**:
- All schema tests pass
- Migration validated on staging environment
- Rollback procedure documented and tested

---

## Phase 2: Consumer Group Subscription API (Week 3-4)

### Objectives

- Implement subscription management API
- Support ACTIVE, PAUSED, CANCELLED, DEAD subscription states
- Implement heartbeat mechanism for dead consumer detection
- Support late-joining consumers with configurable start position

### Tasks

#### 2.1 Subscription Management Service
**Owner**: Backend Developer
**Effort**: 4 days

**Components**:
- `SubscriptionManager` class - CRUD operations for subscriptions
- `SubscriptionOptions` - Configuration for start position (FROM_NOW, FROM_BEGINNING, FROM_TIMESTAMP)
- Database operations for subscription lifecycle

**API Methods**:
- `subscribe(topic, groupName, options)` - Create or resume subscription
- `pause(topic, groupName)` - Pause subscription
- `resume(topic, groupName)` - Resume subscription
- `cancel(topic, groupName)` - Cancel subscription
- `updateHeartbeat(topic, groupName)` - Update last heartbeat timestamp

**See**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) Section 6 "Subscription Lifecycle"

#### 2.2 Topic Configuration Service
**Owner**: Backend Developer
**Effort**: 2 days

**Components**:
- `TopicConfigService` class - Manage topic semantics and retention
- Support for QUEUE vs PUB_SUB semantics
- Retention policy configuration
- Zero-subscription protection policy

**API Methods**:
- `createTopic(topic, semantics, retentionHours)` - Create topic configuration
- `updateTopic(topic, config)` - Update topic configuration
- `getTopic(topic)` - Get topic configuration
- `listTopics()` - List all topics

#### 2.3 Integration Tests
**Owner**: QA Engineer
**Effort**: 2 days

**Test Scenarios**:
- F5: Snapshot of Required Groups (immutable semantics)
- F6: Zero-Subscription Policy (retention and write blocking)
- Subscription state transitions (ACTIVE → PAUSED → ACTIVE)
- Late-joining consumer with FROM_BEGINNING option
- Heartbeat updates and dead consumer detection

**Exit Criteria**:
- All F5, F6 tests pass
- Subscription API fully functional
- Topic configuration API working

---

## Phase 3: Message Production & Fan-Out (Week 5-6)

### Objectives

- Implement fan-out logic for PUB_SUB topics
- Create consumer group tracking rows on message insertion
- Maintain backward compatibility with QUEUE topics
- Implement zero-subscription protection

### Tasks

#### 3.1 Fan-Out Producer Implementation
**Owner**: Backend Developer
**Effort**: 4 days

**Components**:
- Enhance `OutboxProducer` to support PUB_SUB semantics
- Implement trigger-based consumer group tracking row creation
- Set `required_consumer_groups` based on active subscriptions at insertion time
- Implement zero-subscription protection (block writes or set retention)

**Key Behaviors**:
- QUEUE topics: Existing behavior unchanged
- PUB_SUB topics: Create tracking rows for all ACTIVE subscriptions
- Snapshot semantics: `required_consumer_groups` is immutable after insertion

**See**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) Section 7 "Message Production"

#### 3.2 Producer Integration Tests
**Owner**: QA Engineer
**Effort**: 2 days

**Test Scenarios**:
- Messages published to QUEUE topics work unchanged
- Messages published to PUB_SUB topics create tracking rows for all active subscriptions
- Zero-subscription protection blocks writes or sets retention correctly
- Snapshot semantics: Adding subscription after message insertion doesn't affect existing messages

**Exit Criteria**:
- All producer tests pass
- Backward compatibility verified
- Zero-subscription protection working

---

## Phase 4: Message Consumption & Completion (Week 7-8)

### Objectives

- Implement consumer group message fetching
- Implement completion tracking (Reference Counting mode)
- Support concurrent consumers within a group
- Implement retry and dead-letter handling

### Tasks

#### 4.1 Consumer Group Fetcher
**Owner**: Backend Developer
**Effort**: 5 days

**Components**:
- `ConsumerGroupFetcher` class - Fetch messages for a specific consumer group
- Query messages where tracking row status = PENDING for this group
- Use `FOR UPDATE SKIP LOCKED` for concurrent consumer safety
- Support batch fetching with configurable batch size

**Key Behaviors**:
- Fetch only messages where this group has PENDING status
- Skip messages already being processed by other workers in the same group
- Return messages in `created_at ASC` order (FIFO)

**See**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) Section 8 "Message Consumption"

#### 4.2 Completion Tracking (Reference Counting)
**Owner**: Backend Developer
**Effort**: 3 days

**Components**:
- `CompletionTracker` class - Update tracking rows and completion counters
- Update `outbox_consumer_groups` status to COMPLETED
- Increment `outbox.completed_consumer_groups` counter
- Mark message as eligible for cleanup when `completed_consumer_groups == required_consumer_groups`

**Key Behaviors**:
- Atomic update of tracking row and counter
- Handle concurrent updates correctly
- Support retry on failure

#### 4.3 Functional Tests
**Owner**: QA Engineer
**Effort**: 4 days

**Test Scenarios**:
- F1: At-Least-Once Delivery (100k messages, 3 groups, restarts)
- F2: Ordering Guarantees (per-key ordering validation)
- F3: Consumer Group Semantics (8 workers, shared cursor, CAS conflicts)
- F4: Bitmap Correctness (32 groups, concurrent acks, audit validation)
- R1: Crash Before Ack Commit (redelivery proof)
- R2: Crash After Ack Commit (no duplicates)

**Exit Criteria**:
- All F1-F4, R1-R2 tests pass
- Consumer group fetching working correctly
- Completion tracking accurate

---

## Phase 5: Cleanup Jobs & Monitoring (Week 9-10)

### Objectives

- Implement cleanup job for completed messages
- Implement dead consumer detection and cleanup
- Implement watermark calculation and partition drop
- Add observability metrics and monitoring




### Tasks

#### 5.1 Cleanup Job Implementation
**Owner**: Backend Developer
**Effort**: 4 days

**Components**:
- `CleanupJob` class - Periodic job to delete completed messages
- Watermark calculation: `min(last_processed_id)` across all ACTIVE subscriptions
- Partition drop for time-based partitions older than watermark
- Dead consumer detection based on heartbeat timeout

**Key Behaviors**:
- Run periodically (e.g., every 5 minutes)
- Calculate watermark per topic
- Drop partitions where `max_id <= watermark`
- Mark subscriptions as DEAD if heartbeat timeout exceeded
- Delete messages where `completed_consumer_groups == required_consumer_groups` and `id <= watermark`

**See**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) Section 11 "Cleanup Job Operations"

#### 5.2 Observability & Monitoring
**Owner**: Backend Developer
**Effort**: 2 days

**Metrics**:
- Consumer group lag (pending messages per group)
- Cleanup rate (messages deleted per second)
- Watermark position per topic
- Dead consumer count
- Table bloat percentage
- WAL generation rate

**Dashboards**:
- Consumer group health dashboard
- Cleanup job performance dashboard
- Database health dashboard

#### 5.3 Cleanup Tests
**Owner**: QA Engineer
**Effort**: 2 days

**Test Scenarios**:
- C1: Watermark Correctness (monotonic advancement)
- C2: Bitmap-Gated Cleanup (no premature deletion)
- C3: Zombie Subscription Protection (DEAD subscriptions don't block cleanup)
- C4: Partition Drop Safety (no data loss)
- R3: Dead Detection & Resurrection (heartbeat timeout and recovery)

**Exit Criteria**:
- All C1-C4, R3 tests pass
- Cleanup job working correctly
- Monitoring dashboards functional

---

## Phase 6: Load Testing & Performance Validation (Week 11-12)

### Objectives

- Validate performance under production-like load
- Identify and fix performance bottlenecks
- Validate scalability with increasing consumer groups
- Run soak tests for stability

### Tasks

#### 6.1 Performance Benchmarking
**Owner**: QA Engineer + Backend Developer
**Effort**: 5 days

**Test Harness**: Use `peegeeq-performance-test-harness` (already created)

**Benchmark Scenarios**:
- P1: Steady-State Throughput Curve (batch sizes × payload sizes × groups)
- P2: Fanout Scaling (1-64 consumer groups, measure CPU scaling)
- P3: Mixed Topics (QUEUE + PUB_SUB concurrently)
- P4: Backfill vs OLTP (backfill doesn't degrade OLTP performance)
- P5: Soak Test (24-72 hours, stability validation)

**Performance Targets**:
- Throughput ≥ 30,000 msg/sec (2KB payload, 4 groups)
- p95 latency < 300ms
- DB CPU < 70% under normal load
- Bitmap conflicts < 10% at N=16 groups

**See**: Test harness implementation in `peegeeq-performance-test-harness/`

#### 6.2 Performance Tuning
**Owner**: Backend Developer
**Effort**: 3 days

**Tuning Areas**:
- Database connection pool sizing
- Batch size optimization
- Index tuning based on query plans
- Vacuum and autovacuum tuning
- WAL configuration

**Exit Criteria**:
- All P1-P5 tests pass
- Performance targets met
- No critical bottlenecks identified

---

## Phase 7 (Optional): Offset/Watermark Mode (Week 13-16)

### Objectives

- Implement Offset/Watermark mode for high fanout (>16 groups)
- Reduce write amplification from O(N×M) to O(N)
- Support CAS-based offset updates

### Tasks

#### 7.1 Schema Migration
**Owner**: Backend Developer
**Effort**: 2 days

**Deliverables**:
- Migration file: `V011__Add_Offset_Watermark_Mode.sql` (to be created)
- Tables: `outbox_subscription_offsets`, `outbox_topic_watermarks`
- Add `completion_tracking_mode` column to `outbox_topics`

**See**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) Section 5 "Offset/Watermark Mode"

#### 7.2 Offset-Based Consumer Implementation
**Owner**: Backend Developer
**Effort**: 4 days

**Components**:
- Offset-based message fetching (fetch messages where `id > last_processed_id`)
- Offset commit logic with CAS (compare-and-swap using version column)
- Watermark calculation job
- Cleanup based on watermark

#### 7.3 Performance Validation
**Owner**: QA Engineer
**Effort**: 2 days

**Test Scenarios**:
- P2: Fanout Scaling (compare Bitmap vs Offset mode at N=32, 64, 128)
- K2: CAS Efficiency (measure CAS conflict rate)
- Validate O(1) CPU scaling with increasing consumer groups

**Exit Criteria**:
- Offset mode working correctly
- CAS conflicts < 5%
- CPU scaling O(1) vs O(N) for Bitmap mode

---

## Phase 8 (Optional): Resumable Backfill

### Objectives

- Support resumable backfill for late-joining consumers
- Prevent backfill from degrading OLTP performance
- Support cancellation and progress tracking



### Tasks

#### 8.1 Schema Migration
**Owner**: Backend Developer
**Effort**: 1 day

**Deliverables**:
- Migration file: `V012__Add_Resumable_Backfill.sql` (to be created)
- Add backfill tracking columns to `outbox_topic_subscriptions`

**See**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) Section 10 "Resumable Backfill"

#### 8.2 Resumable Backfill Implementation
**Owner**: Backend Developer
**Effort**: 4 days

**Components**:
- `ResumableBackfillJob` class
- Checkpoint save/load logic
- Cancellation API
- Rate limiting to prevent OLTP degradation

#### 8.3 Backfill Tests
**Owner**: QA Engineer
**Effort**: 2 days

**Test Scenarios**:
- R4: Bounded Backfill (1M messages, completes within 30 minutes)
- P4: Backfill vs OLTP (backfill doesn't degrade OLTP performance)
- Backfill cancellation and resumption

**Exit Criteria**:
- All R4, P4 tests pass
- Backfill working correctly
- OLTP performance unaffected

---

## Risk Management

### High Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Write amplification at scale** | Performance degradation with >16 groups | Start with Reference Counting, plan migration to Offset mode |
| **Hot row contention on bitmap updates** | High conflict rate, reduced throughput | Implement Offset mode for high fanout scenarios |
| **Schema migration on large tables** | Downtime during migration | Use `ADD COLUMN IF NOT EXISTS`, test on production-sized dataset |
| **Backward compatibility breakage** | Existing queue consumers fail | Extensive testing, feature flags, gradual rollout |
| **Dead consumer detection false positives** | Premature cleanup, data loss | Conservative heartbeat timeout (5 minutes), monitoring |

### Medium Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Partition drop during active consumption** | Data loss | Watermark calculation includes safety margin |
| **Zero-subscription edge cases** | Unexpected behavior | Comprehensive testing of F6 scenarios |
| **Cleanup job performance** | Slow cleanup, table bloat | Partition-based cleanup, batch deletes |

---

## Success Metrics

### Functional Correctness
- ✅ All acceptance tests pass (see test harness for complete list)
- ✅ No data loss (missing messages = 0)
- ✅ Duplicates < 0.5% during crash tests
- ✅ Backward compatibility: Existing queue consumers work unchanged

### Performance
- ✅ Throughput ≥ 30,000 msg/sec (2KB payload, 4 groups)
- ✅ p95 latency < 300ms
- ✅ DB CPU < 70% under normal load
- ✅ Fanout scaling: 64 groups with acceptable performance

### Operational
- ✅ Cleanup rate > production rate (no unbounded growth)
- ✅ Table bloat < 20%
- ✅ Dead consumer detection < 5 minutes
- ✅ Monitoring dashboards functional
- ✅ Rollback procedures tested and documented

---

## Deployment & Rollback

### Deployment Strategy

1. **Phase 1**: Deploy schema changes to staging, validate migrations
2. **Phase 2-3**: Deploy subscription and producer changes with feature flag OFF
3. **Phase 4**: Enable feature flag for internal testing topics
4. **Phase 5**: Deploy cleanup jobs, monitor for 48 hours
5. **Phase 6**: Gradual rollout to production topics (10% → 50% → 100%)

### Rollback Procedures

**Phase 1 Rollback**:
- Run rollback migration to drop tables and columns
- No application changes needed

**Phase 2-5 Rollback**:
- Disable feature flag
- Stop consumer groups
- Revert application code to previous version
- Run schema rollback if needed

**Emergency Rollback**:
- Feature flag: Disable fan-out globally
- Fallback to QUEUE semantics for all topics
- Monitor for 24 hours before re-enabling

**See**: Rollback migration files for complete SQL

---

## References

- **Design Document**: [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) - Complete design specification
- **Migration Files**: `peegeeq-migrations/src/main/resources/db/migration/V010__*.sql` - Database schema
- **Test Harness**: `peegeeq-performance-test-harness/` - Performance testing infrastructure
- **Benchmark Scripts**: `scripts/run-fanout-benchmarks.sh` - Automated benchmarking
