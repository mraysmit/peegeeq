# BackfillService Performance & Concurrency Validation Guide

## Overview
This document describes how to validate the BackfillService refactoring changes under heavy load and high concurrency conditions.

## What Was Changed
1. **Status constants** - Centralized status strings
2. **Row-level locking** - Added `FOR UPDATE` to prevent race conditions
3. **Input validation** - Validates parameters before processing
4. **Logging optimization** - Reduced log volume during batch processing
5. **Async tail-recursion** - Uses Future composition for batch iteration

## Test Coverage Created

### BackfillServiceConcurrencyTest.java
Located: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillServiceConcurrencyTest.java`

#### Test 1: Concurrent Same Subscription (Race Condition Test)
- **Purpose**: Validates FOR UPDATE row locking prevents duplicate processing
- **Scenario**: 5 concurrent threads attempt to backfill the same subscription
- **Expected**: Only one completes work, others see ALREADY_COMPLETED
- **Validates**: No duplicate tracking rows, correct final state

#### Test 2: Concurrent Different Subscriptions
- **Purpose**: Validates independent backfills don't interfere
- **Scenario**: 10 subscriptions backfilling simultaneously
- **Expected**: All complete independently, no interference
- **Validates**: Parallel execution efficiency

#### Test 3: Heavy Load (100k Messages)
- **Purpose**: Validates performance at scale
- **Scenario**: Single backfill of 100,000 messages in 10k batches
- **Expected**: >= 1,000 msgs/sec throughput
- **Validates**: Batch processing efficiency, memory stability

#### Test 4: Row-Level Locking Stress Test
- **Purpose**: Stress test the FOR UPDATE lock with many batches
- **Scenario**: 3 concurrent workers, 2000 messages, 100 msg batches (20 batches)
- **Expected**: Exactly 2000 tracking rows (no duplicates)
- **Validates**: Lock effectiveness under batch contention

#### Test 5: Resumability Under Load
- **Purpose**: Validates checkpoint-based recovery
- **Scenario**: Start backfill, cancel mid-flight, resume
- **Expected**: Completes from checkpoint, no duplicates
- **Validates**: Crash recovery, checkpoint integrity

## Running the Tests

### Run All Concurrency Tests
```bash
cd peegeeq-db
mvn test -Dtest=BackfillServiceConcurrencyTest
```

### Run Specific Test
```bash
# Heavy load test
mvn test -Dtest=BackfillServiceConcurrencyTest#testBackfillHeavyLoad_100kMessages

# Concurrency race condition test
mvn test -Dtest=BackfillServiceConcurrencyTest#testConcurrentBackfillSameSubscription_PreventsDuplicateProcessing

# Row locking test
mvn test -Dtest=BackfillServiceConcurrencyTest#testRowLevelLocking_PreventsConcurrentBatches
```

### Run with Performance Category
```bash
mvn test -Dgroups="performance"
```

### Run Integration Tests (includes existing functional tests)
```bash
mvn test -Dtest=BackfillServiceIntegrationTest
```

## Expected Results

### Performance Benchmarks
- **Throughput**: >= 1,000 msgs/sec for 100k message backfill
- **Latency**: < 10ms per batch operation
- **Memory**: Stable heap usage (no accumulation with many batches)

### Concurrency Requirements
- **No duplicate processing**: Track row count == message count
- **Serialization**: FOR UPDATE prevents concurrent batch processing
- **No deadlocks**: All concurrent operations complete successfully
- **Checkpoint integrity**: Resume from correct position after cancellation

## Validation Checklist

### Before Production Deployment

- [ ] **Functional Tests Pass**
  ```bash
  mvn test -Dtest=BackfillServiceIntegrationTest
  ```
  All 8 existing integration tests should pass

- [ ] **Concurrency Tests Pass**
  ```bash
  mvn test -Dtest=BackfillServiceConcurrencyTest
  ```
  All 5 concurrency tests should pass

- [ ] **Heavy Load Test**
  - 100k messages backfill completes in < 120 seconds
  - Throughput >= 1,000 msgs/sec
  - No OutOfMemoryError

- [ ] **Race Condition Test**
  - 5 concurrent attempts on same subscription
  - Zero duplicate tracking rows
  - Final state is COMPLETED with correct count

- [ ] **Database Verification**
  - Check for orphaned tracking rows
  - Verify checkpoint updates are atomic
  - Confirm no deadlock_detected errors in PostgreSQL logs

- [ ] **Logging Verification**
  - Log volume reduced by ~90% during batch processing
  - Info logs appear every ~100k messages
  - Last batch always logs at info level

### Performance Regression Checks

Compare against baseline (if available):
- Throughput should not degrade > 10%
- Memory usage should not increase > 20%
- CPU usage should remain similar

### Known Limitations

1. **Async Tail-Recursion**
   - Safe for up to ~1000 batches (10M messages with 10k batch size)
   - Very large datasets (1000+ batches) may accumulate Future objects
   - Mitigated by DEFAULT_MAX_MESSAGES = 1M limit

2. **Connection Pool Size**
   - Tests use pool size 50 for concurrency
   - Production should configure based on expected concurrency
   - Monitor pool exhaustion metrics

3. **TestContainers Performance**
   - Test throughput may be lower than production PostgreSQL
   - Run against production-like database for accurate benchmarks

## Troubleshooting

### Test Timeouts
- Increase @Timeout annotation if running on slow hardware
- Check PostgreSQL resource limits in container

### Compilation Errors
- Ensure all changes to BackfillService.java compiled successfully
- Check that SubscriptionManager errors (lines 418, 442) are resolved

### Test Failures
1. **Duplicate tracking rows**: Row locking not working
2. **Timeout in concurrency tests**: Database contention or pool exhaustion
3. **Performance < 1000 msgs/sec**: Check database config, indexing

## Next Steps

1. **Run the tests**:
   ```bash
   mvn clean test -Dtest=BackfillServiceConcurrencyTest
   ```

2. **Review results**: Check test output for timing and throughput metrics

3. **Benchmark comparison**: Compare against original implementation if possible

4. **Production validation**: Run against production-sized database with realistic data


