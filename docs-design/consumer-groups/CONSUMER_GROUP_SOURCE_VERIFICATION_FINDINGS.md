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
- 12 integration tests in `FlappingProtectionIntegrationTest` — all passing.

Primary sources:

- `peegeeq-migrations/src/main/resources/db/migration/V015__Add_Flapping_Protection_Columns.sql`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetector.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/SubscriptionOptions.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/FlappingProtectionIntegrationTest.java`

## Verified Missing Or Incomplete

### Graceful Shutdown Handling For Subscription-Backed Consumer Groups

Graceful shutdown is still incomplete for subscription-backed consumer groups.

- `OutboxConsumerGroup` and `PgNativeConsumerGroup` create subscriptions when started with `SubscriptionOptions`.
- Their `stop()` and `close()` paths stop local consumers and unsubscribe the underlying consumer.
- They do not cancel subscriptions or implement a subscription-state drain/transition workflow during shutdown.

Primary sources:

- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeConsumerGroup.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`

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

## Partially Correct Documentation Claim

The earlier statement that tracing instrumentation was missing needs refinement.

Accurate source-based position:

- The consumer-group fanout path does use `TraceCtx` and `TraceContextUtil` for MDC/log correlation.
- The consumer-group fanout path does not use `AsyncTraceUtils` in the way described by the tracing design documents.

This means tracing is partially implemented, but the richer async/distributed tracing instrumentation is still absent from the fanout path.

Primary sources:

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerGroupCleanup.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetectionJob.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`

## Recommended Corrected Summary

Backfill, dead-consumer cleanup, and flapping protection are implemented. Graceful shutdown of subscription-backed consumer groups, fanout-specific retry/DLQ automation, and per-consumer-group metrics are still missing. Tracing is partially implemented through `TraceCtx` and MDC correlation, but the consumer-group fanout path still lacks `AsyncTraceUtils`-based async/distributed tracing.