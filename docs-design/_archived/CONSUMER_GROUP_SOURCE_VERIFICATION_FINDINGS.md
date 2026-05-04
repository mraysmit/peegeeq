# Consumer Group Source Verification Findings

Date: 2026-04-01

## Purpose

This document records a source-code verification pass against the consumer-group fanout design documents. It corrects any stale documentation claims with evidence from the current implementation.

## Verified Implemented

### Backfill

Backfill is implemented in production code.

- `BackfillService` provides resumable, batched backfill with checkpointing and cancellation.
- `SubscriptionManager` can auto-trigger backfill for `FROM_BEGINNING` subscriptions.
- `PeeGeeQManager` wires `BackfillService` into the shared `SubscriptionService`.
- REST endpoints exist to start backfill and inspect progress.
- Integration tests exist for backfill behavior.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SubscriptionHandler.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillServiceIntegrationTest.java`

### Dead Consumer Cleanup

Dead-consumer cleanup is implemented, not just detection.

- `DeadConsumerGroupCleanup` decrements `required_consumer_groups`.
- It removes orphaned tracking rows.
- It auto-completes messages when `completed_consumer_groups >= required_consumer_groups`.
- `DeadConsumerDetectionJob` runs detection and then executes cleanup.
- `PeeGeeQManager` starts the scheduled job when enabled.
- Integration tests exist for cleanup behavior.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerGroupCleanup.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetectionJob.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetector.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/DeadConsumerGroupCleanupIntegrationTest.java`

### Flapping Protection

Flapping protection is implemented.

- V015 migration adds `consecutive_misses` (default 0) and `dead_after_misses` (default 3) columns to `outbox_topic_subscriptions`.
- `DeadConsumerDetector` uses two-phase SQL: increment `consecutive_misses` for expired subscriptions, then mark DEAD only when `consecutive_misses >= dead_after_misses`.
- `SubscriptionManager.updateHeartbeat()` resets `consecutive_misses = 0` on heartbeat (including DEAD→ACTIVE resurrection).
- `SubscriptionManager.subscribe()` resets `consecutive_misses = 0` on resubscription via ON CONFLICT.
- `SubscriptionOptions.deadAfterMisses(int)` allows per-subscription threshold configuration via the builder and REST API.
- 12 integration tests in `FlappingProtectionIntegrationTest` all passing.

Primary sources:

- `peegeeq-migrations/src/main/resources/db/migration/V015__Add_Flapping_Protection_Columns.sql`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetector.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/SubscriptionOptions.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/FlappingProtectionIntegrationTest.java`

## Verified Implemented (April 2026 Update)

### Graceful Shutdown Handling For Subscription-Backed Consumer Groups

Graceful shutdown is now implemented for subscription-backed consumer groups.

- `ConsumerGroup` interface: added `default Future<Void> stopGracefully()` method.
- `OutboxConsumerGroup` and `PgNativeConsumerGroup` track whether they were started with `SubscriptionOptions` via a `startedWithSubscription` flag.
- On `stopGracefully()`, if started with subscription: cancel subscription in database → stop internal consumers. Cancel failure is recovered (group still stops).
- Groups started without `SubscriptionOptions` fall back to regular `stop()` behavior.
- 7 unit tests in `OutboxConsumerGroupGracefulShutdownTest` all passing.
- 55 existing `OutboxConsumerGroupCoreTest` tests remain passing (backward compat verified).

Primary sources:

- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/ConsumerGroup.java`
- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeConsumerGroup.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupGracefulShutdownTest.java`

### Adaptive Rate Limiting for Backfill

Backfill rate limiting is now implemented.

- `BackfillService` accepts `batchDelayMs` parameter for configurable inter-batch throttling.
- New 3-arg constructor `(PgConnectionManager, String, Vertx)` enables non-blocking timer-based delays via `vertx.timer(batchDelayMs).mapEmpty()`.
- Legacy 2-arg constructor preserved for backward compatibility (timer support disabled, zero delay only).
- `PeeGeeQManager.createSubscriptionService()` passes Vertx to BackfillService.
- 13 unit tests + 4 integration tests all passing.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/subscription/BackfillRateLimitingUnitTest.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillRateLimitingIntegrationTest.java`

### Admin Force-Remove Endpoint

Admin force-remove is now implemented.

- `SubscriptionService` interface: added `forceRemoveConsumerGroup(topic, groupName)` default method.
- `SubscriptionManager`: validates → marks DEAD → runs `DeadConsumerGroupCleanup.cleanupDeadGroup()` → marks CANCELLED → returns `ForceRemoveResult`.
- REST: `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove`.
- `PeeGeeQManager.createSubscriptionService()` wires `DeadConsumerGroupCleanup` into `SubscriptionManager`.
- 5 unit tests + 5 integration tests all passing.

Primary sources:

- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/ForceRemoveResult.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/SubscriptionService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SubscriptionHandler.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/subscription/ForceRemoveUnitTest.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/subscription/ForceRemoveIntegrationTest.java`

## Verified Missing Or Incomplete

### Fanout Retry And DLQ Automation

Fanout-specific retry and DLQ automation remain incomplete.

- `CompletionTracker.markFailed(...)` exists and updates failed status plus `retry_count`.
- No production fanout workflow was found that automatically retries failed consumer-group rows.
- No production fanout workflow was found that moves failed fanout rows into the database dead letter queue.

Important scope note:

- DLQ and retry infrastructure does exist elsewhere in the repository.
- The missing piece is consumer-group fanout integration with that infrastructure.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/CompletionTracker.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/deadletter/DeadLetterQueueManager.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`
- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManager.java`

### Per-Consumer-Group Metrics

Per-consumer-group metrics are still missing.

- `PeeGeeQMetrics` records metrics by `instance` and by `topic`.
- No `consumer_group` tag was found in the current production metrics implementation.
- No implementation matching the documented `peegeeq_messages_received_total{consumer_group=...}` pattern was found.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/metrics/PeeGeeQMetrics.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/provider/PgMetricsProvider.java`

## Tracing Instrumentation Updated Status

The consumer-group fanout path now has comprehensive `TraceCtx` + `TraceContextUtil.mdcScope()` instrumentation:

- **`DeadConsumerDetectionJob`**: Trace created per detection run, propagated through entire compose chain (detection→blocked stats→cleanup→summary→healthy run). All 5 private logging methods accept `TraceCtx` and scope MDC. The `onFailure` handler was already scoped.
- **`ConsumerGroupFetcher`**: Trace created at `fetchMessages()` entry; MDC scoped at both the entry log and the result count log.
- **`CompletionTracker`**: Trace created at `markCompleted()` and `markFailed()` entry; MDC scoped at all internal log points (validation warnings, idempotent detection, completion status, error messages).
- **`BackfillService`**: Already had comprehensive tracing through the entire recursive batch chain (verified in earlier audit).

**Remaining gap**: `AsyncTraceUtils` wrappers (for cross-Vertx-context propagation) and fan-out trace branching design are still not implemented; current instrumentation covers MDC/log correlation within single Vert.x context.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerGroupCleanup.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetectionJob.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/ConsumerGroupFetcher.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/CompletionTracker.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`

## Recommended Corrected Summary

Backfill (with rate limiting), dead-consumer cleanup, flapping protection, graceful shutdown, and admin force-remove are all implemented. Fanout-specific retry/DLQ automation and per-consumer-group metrics are still missing. Tracing is now implemented across all consumer group operational code via `TraceCtx`/`mdcScope()` MDC correlation. The richer `AsyncTraceUtils`-based cross-context propagation and fan-out trace branching design remain as future work.