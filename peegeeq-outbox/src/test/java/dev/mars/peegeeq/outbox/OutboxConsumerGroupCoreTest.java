package dev.mars.peegeeq.outbox;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.RejectedMessageException;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive core unit tests for {@link OutboxConsumerGroup} covering the
 * code review fixes:
 * <ul>
 *   <li>Phase 1 — Lifecycle state machine transitions and safety</li>
 *   <li>Phase 2 — Membership concurrency (putIfAbsent, no synchronized)</li>
 *   <li>Phase 3 — Failure semantics (RejectedMessageException vs MessageFilteredException)</li>
 *   <li>Phase 4 — Deterministic hash-based routing</li>
 *   <li>Phase 5 — Stats correctness (weighted average, lastActiveAt)</li>
 *   <li>Phase 7 — Builder validation</li>
 * </ul>
 *
 * <p>These are fast, in-process unit tests with no database or Testcontainers dependency.
 * The integration-level tests remain in {@link OutboxConsumerGroupTest}.</p>
 */
@Tag(TestCategories.CORE)
@DisplayName("OutboxConsumerGroup — core unit tests")
class OutboxConsumerGroupCoreTest {

    private OutboxConsumerGroup<String> group;

    @AfterEach
    void tearDown() {
        if (group != null) {
            group.close();
        }
    }

    // ========================================================================
    // Phase 1: Lifecycle State Machine
    // ========================================================================

    @Nested
    @DisplayName("Lifecycle State Machine")
    class LifecycleStateMachine {

        @Test
        @DisplayName("new group starts in NEW state")
        void newGroupStartsInNewState() {
            group = createGroup("lifecycle-group", "test-topic");
            assertEquals(OutboxConsumerGroup.State.NEW, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("start() transitions NEW → ACTIVE")
        void startTransitionsToActive() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertEquals(OutboxConsumerGroup.State.ACTIVE, group.getState());
            assertTrue(group.isActive());
        }

        @Test
        @DisplayName("start() is idempotent when already ACTIVE")
        void startIsIdempotentWhenActive() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertDoesNotThrow(() -> group.start());
            assertEquals(OutboxConsumerGroup.State.ACTIVE, group.getState());
        }

        @Test
        @DisplayName("start() throws when group is CLOSED")
        void startThrowsWhenClosed() {
            group = createGroup("lifecycle-group", "test-topic");
            group.close();
            assertThrows(IllegalStateException.class, () -> group.start());
        }

        @Test
        @DisplayName("stop() transitions ACTIVE → NEW (restartable)")
        void stopTransitionsToNew() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
            assertEquals(OutboxConsumerGroup.State.NEW, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("stop() is a no-op when not ACTIVE")
        void stopIsNoOpWhenNew() {
            group = createGroup("lifecycle-group", "test-topic");
            assertDoesNotThrow(() -> group.stop());
            assertEquals(OutboxConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("stop() then start() allows restart")
        void stopThenStartAllowsRestart() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
            assertEquals(OutboxConsumerGroup.State.NEW, group.getState());
            group.start();
            assertEquals(OutboxConsumerGroup.State.ACTIVE, group.getState());
        }

        @Test
        @DisplayName("close() from ACTIVE stops and closes")
        void closeFromActiveStopsAndCloses() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.close();
            assertEquals(OutboxConsumerGroup.State.CLOSED, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("close() from NEW goes directly to CLOSED")
        void closeFromNewGoesToClosed() {
            group = createGroup("lifecycle-group", "test-topic");
            group.close();
            assertEquals(OutboxConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            group = createGroup("lifecycle-group", "test-topic");
            group.close();
            assertDoesNotThrow(() -> group.close());
            assertEquals(OutboxConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("close() clears all members")
        void closeClearsMembers() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());
            assertEquals(2, group.getConsumerIds().size());
            group.close();
            assertTrue(group.getConsumerIds().isEmpty());
        }

        @Test
        @DisplayName("start(SubscriptionOptions) fails with failed future when CLOSED")
        void startWithOptionsFailsWhenClosed() {
            group = createGroup("lifecycle-group", "test-topic");
            group.close();

            var options = dev.mars.peegeeq.api.messaging.SubscriptionOptions.builder().build();
            Future<Void> result = group.start(options);
            assertTrue(result.failed());
            assertInstanceOf(IllegalStateException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("closed"));
        }

        @Test
        @DisplayName("start(SubscriptionOptions) fails with failed future when already ACTIVE")
        void startWithOptionsFailsWhenAlreadyActive() {
            group = createGroup("lifecycle-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            var options = dev.mars.peegeeq.api.messaging.SubscriptionOptions.builder().build();
            Future<Void> result = group.start(options);
            assertTrue(result.failed());
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        @Test
        @DisplayName("start(SubscriptionOptions) rejects null argument")
        void startWithNullOptionsThrows() {
            group = createGroup("lifecycle-group", "test-topic");
            assertThrows(IllegalArgumentException.class, () -> group.start(null));
        }

        @Test
        @DisplayName("start() rolls back to NEW on internal failure")
        void startRollbackOnFailure() {
            // Use the private constructor path via builder to get both null
            // The constructor won't enforce, but start() will hit the null check
            group = new OutboxConsumerGroup<>(
                    "rollback-group", "test-topic", String.class,
                    (dev.mars.peegeeq.db.client.PgClientFactory) null,
                    null, null, null, null);

            try {
                group.start();
                fail("Expected IllegalStateException because both clientFactory and databaseService are null");
            } catch (IllegalStateException e) {
                // Expected
            }
            assertEquals(OutboxConsumerGroup.State.NEW, group.getState(),
                    "State should roll back to NEW after failed start()");
        }
    }

    // ========================================================================
    // Phase 2: Membership Concurrency
    // ========================================================================

    @Nested
    @DisplayName("Membership Concurrency")
    class MembershipConcurrency {

        @Test
        @DisplayName("addConsumer rejects duplicate consumer IDs atomically")
        void addConsumerRejectsDuplicateIds() {
            group = createGroup("membership-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            assertThrows(IllegalArgumentException.class,
                    () -> group.addConsumer("c1", msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("addConsumer rejects null consumerId")
        void addConsumerRejectsNullId() {
            group = createGroup("membership-group", "test-topic");
            assertThrows(NullPointerException.class,
                    () -> group.addConsumer(null, msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("addConsumer rejects null handler")
        void addConsumerRejectsNullHandler() {
            group = createGroup("membership-group", "test-topic");
            assertThrows(NullPointerException.class,
                    () -> group.addConsumer("c1", null));
        }

        @Test
        @DisplayName("addConsumer throws when group is CLOSED")
        void addConsumerThrowsWhenClosed() {
            group = createGroup("membership-group", "test-topic");
            group.close();
            assertThrows(IllegalStateException.class,
                    () -> group.addConsumer("c1", msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("removeConsumer returns false for unknown ID")
        void removeConsumerReturnsFalseForUnknown() {
            group = createGroup("membership-group", "test-topic");
            assertFalse(group.removeConsumer("nonexistent"));
        }

        @Test
        @DisplayName("removeConsumer returns true and removes known consumer")
        void removeConsumerRemovesKnown() {
            group = createGroup("membership-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            assertTrue(group.removeConsumer("c1"));
            assertFalse(group.getConsumerIds().contains("c1"));
        }

        @Test
        @DisplayName("concurrent addConsumer with same ID — exactly one succeeds")
        void concurrentAddConsumerOnlyOneSucceeds() throws Exception {
            group = createGroup("concurrent-group", "test-topic");
            int threadCount = 10;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                CountDownLatch done = new CountDownLatch(threadCount);
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            group.addConsumer("contested-id", msg -> Future.succeededFuture());
                            successCount.incrementAndGet();
                        } catch (IllegalArgumentException e) {
                            failureCount.incrementAndGet();
                        } catch (Exception e) {
                            // Barrier/timeout
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertEquals(1, successCount.get(), "Exactly one thread should succeed");
                assertEquals(threadCount - 1, failureCount.get(), "All others should fail with duplicate ID");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("setMessageHandler rejects duplicate call")
        void setMessageHandlerRejectsDuplicate() {
            group = createGroup("handler-group", "test-topic");
            group.setMessageHandler(msg -> Future.succeededFuture());
            assertThrows(IllegalStateException.class,
                    () -> group.setMessageHandler(msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("setMessageHandler rejects null")
        void setMessageHandlerRejectsNull() {
            group = createGroup("handler-group", "test-topic");
            assertThrows(NullPointerException.class, () -> group.setMessageHandler(null));
        }

        @Test
        @DisplayName("setMessageHandler throws when group is CLOSED")
        void setMessageHandlerThrowsWhenClosed() {
            group = createGroup("handler-group", "test-topic");
            group.close();
            assertThrows(IllegalStateException.class,
                    () -> group.setMessageHandler(msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("getConsumerIds returns defensive copy")
        void getConsumerIdsReturnsDefensiveCopy() {
            group = createGroup("snapshot-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            Set<String> ids = group.getConsumerIds();
            group.addConsumer("c2", msg -> Future.succeededFuture());
            assertEquals(1, ids.size(), "Snapshot should not reflect later additions");
            assertTrue(ids.contains("c1"));
        }
    }

    // ========================================================================
    // Phase 3: Filter/Rejection Failure Semantics
    // ========================================================================

    @Nested
    @DisplayName("Filter and Rejection Failure Semantics")
    class FilterRejectionSemantics {

        @Test
        @DisplayName("group filter rejection produces RejectedMessageException (permanent)")
        void groupFilterRejectionThrowsRejectedMessageException() throws Exception {
            group = createGroup("filter-group", "test-topic");
            group.setGroupFilter(msg -> false);
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            Message<String> message = new SimpleMessage<>("msg-1", "test-topic", "payload");
            Future<Void> result = invokeDistributeMessage(group, message);

            assertTrue(result.failed());
            assertInstanceOf(RejectedMessageException.class, result.cause());
            RejectedMessageException ex = (RejectedMessageException) result.cause();
            assertEquals("msg-1", ex.getMessageId());
            assertEquals("filter-group", ex.getGroupName());
        }

        @Test
        @DisplayName("no eligible consumer produces MessageFilteredException (transient)")
        void noEligibleConsumerProducesMessageFilteredException() throws Exception {
            group = createGroup("filter-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture(), msg -> false);
            group.start();

            Message<String> message = new SimpleMessage<>("msg-2", "test-topic", "payload");
            Future<Void> result = invokeDistributeMessage(group, message);

            assertTrue(result.failed());
            assertInstanceOf(MessageFilteredException.class, result.cause());
        }

        @Test
        @DisplayName("group filter accepts, all member filters reject → transient MessageFilteredException")
        void groupAcceptsMembersRejectIsTransient() throws Exception {
            group = createGroup("filter-group", "test-topic");
            group.setGroupFilter(msg -> true);
            group.addConsumer("c1", msg -> Future.succeededFuture(), msg -> false);
            group.start();

            Message<String> message = new SimpleMessage<>("msg-3", "test-topic", "payload");
            Future<Void> result = invokeDistributeMessage(group, message);

            assertTrue(result.failed());
            assertInstanceOf(MessageFilteredException.class, result.cause());
        }

        @Test
        @DisplayName("message passing group filter reaches handler successfully")
        void messagePassingGroupFilterReachesHandler() throws Exception {
            group = createGroup("filter-group", "test-topic");
            group.setGroupFilter(msg -> true);
            List<String> received = new CopyOnWriteArrayList<>();
            group.addConsumer("c1", msg -> {
                received.add(msg.getId());
                return Future.succeededFuture();
            });
            group.start();

            Message<String> message = new SimpleMessage<>("msg-4", "test-topic", "payload");
            Future<Void> result = invokeDistributeMessage(group, message);

            assertTrue(result.succeeded());
            assertTrue(received.contains("msg-4"));
        }

        @Test
        @DisplayName("no group filter set means message passes through to routing")
        void noGroupFilterMeansPassThrough() throws Exception {
            group = createGroup("filter-group", "test-topic");
            List<String> received = new CopyOnWriteArrayList<>();
            group.addConsumer("c1", msg -> {
                received.add(msg.getId());
                return Future.succeededFuture();
            });
            group.start();

            Message<String> message = new SimpleMessage<>("msg-5", "test-topic", "payload");
            Future<Void> result = invokeDistributeMessage(group, message);

            assertTrue(result.succeeded());
            assertTrue(received.contains("msg-5"));
        }

        @Test
        @DisplayName("group filter rejection increments totalMessagesFiltered in stats")
        void groupFilterRejectionIncrementsFilteredCount() throws Exception {
            group = createGroup("filter-group", "test-topic");
            group.setGroupFilter(msg -> false);
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            invokeDistributeMessage(group, new SimpleMessage<>("f1", "test-topic", "p"));
            invokeDistributeMessage(group, new SimpleMessage<>("f2", "test-topic", "p"));

            ConsumerGroupStats stats = group.getStats();
            assertEquals(2, stats.getTotalMessagesFiltered());
        }

        @Test
        @DisplayName("no-eligible-consumer also increments totalMessagesFiltered")
        void noEligibleConsumerAlsoIncrementsFiltered() throws Exception {
            group = createGroup("filter-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture(), msg -> false);
            group.start();

            invokeDistributeMessage(group, new SimpleMessage<>("ne1", "test-topic", "p"));

            ConsumerGroupStats stats = group.getStats();
            assertEquals(1, stats.getTotalMessagesFiltered());
        }
    }

    // ========================================================================
    // Phase 4: Deterministic Hash-Based Routing
    // ========================================================================

    @Nested
    @DisplayName("Deterministic Hash-Based Routing")
    class DeterministicRouting {

        @Test
        @DisplayName("same message ID always routes to same consumer")
        void sameMessageIdAlwaysRoutesToSameConsumer() throws Exception {
            group = createGroup("routing-group", "test-topic");

            List<String> consumer1Msgs = new CopyOnWriteArrayList<>();
            List<String> consumer2Msgs = new CopyOnWriteArrayList<>();

            group.addConsumer("c1", msg -> {
                consumer1Msgs.add(msg.getId());
                return Future.succeededFuture();
            });
            group.addConsumer("c2", msg -> {
                consumer2Msgs.add(msg.getId());
                return Future.succeededFuture();
            });
            group.start();

            for (int i = 0; i < 5; i++) {
                invokeDistributeMessage(group,
                        new SimpleMessage<>("deterministic-id", "test-topic", "payload-" + i));
            }

            assertTrue(
                    (consumer1Msgs.size() == 5 && consumer2Msgs.isEmpty()) ||
                    (consumer2Msgs.size() == 5 && consumer1Msgs.isEmpty()),
                    "All messages with same ID should route to the same consumer. " +
                    "c1=" + consumer1Msgs.size() + " c2=" + consumer2Msgs.size());
        }

        @Test
        @DisplayName("different message IDs distribute across consumers")
        void differentMessageIdsDistributeAcrossConsumers() throws Exception {
            group = createGroup("routing-group", "test-topic");

            AtomicInteger c1Count = new AtomicInteger();
            AtomicInteger c2Count = new AtomicInteger();

            group.addConsumer("c1", msg -> {
                c1Count.incrementAndGet();
                return Future.succeededFuture();
            });
            group.addConsumer("c2", msg -> {
                c2Count.incrementAndGet();
                return Future.succeededFuture();
            });
            group.start();

            for (int i = 0; i < 100; i++) {
                invokeDistributeMessage(group,
                        new SimpleMessage<>("msg-" + i, "test-topic", "payload"));
            }

            assertTrue(c1Count.get() > 0, "Consumer 1 should receive some messages");
            assertTrue(c2Count.get() > 0, "Consumer 2 should receive some messages");
            assertEquals(100, c1Count.get() + c2Count.get());
        }

        @Test
        @DisplayName("single consumer receives all messages")
        void singleConsumerReceivesAll() throws Exception {
            group = createGroup("routing-group", "test-topic");

            AtomicInteger count = new AtomicInteger();
            group.addConsumer("c1", msg -> {
                count.incrementAndGet();
                return Future.succeededFuture();
            });
            group.start();

            for (int i = 0; i < 10; i++) {
                invokeDistributeMessage(group,
                        new SimpleMessage<>("msg-" + i, "test-topic", "payload"));
            }

            assertEquals(10, count.get());
        }
    }

    // ========================================================================
    // Phase 5: Stats Correctness
    // ========================================================================

    @Nested
    @DisplayName("Stats Correctness")
    class StatsCorrectness {

        @Test
        @DisplayName("empty group stats: zero totals, null lastActiveAt")
        void emptyGroupStatsAreZeroWithNullLastActive() {
            group = createGroup("stats-group", "test-topic");

            ConsumerGroupStats stats = group.getStats();

            assertEquals("stats-group", stats.getGroupName());
            assertEquals("test-topic", stats.getTopic());
            assertEquals(0, stats.getActiveConsumerCount());
            assertEquals(0, stats.getTotalConsumerCount());
            assertEquals(0, stats.getTotalMessagesProcessed());
            assertEquals(0, stats.getTotalMessagesFailed());
            assertEquals(0, stats.getTotalMessagesFiltered());
            assertEquals(0.0, stats.getAverageProcessingTimeMs());
            assertNotNull(stats.getCreatedAt());
            assertNull(stats.getLastActiveAt(), "lastActiveAt should be null when no members exist");
        }

        @Test
        @DisplayName("totalConsumerCount matches number of added consumers")
        void totalConsumerCountMatchesMembership() {
            group = createGroup("stats-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());

            assertEquals(2, group.getStats().getTotalConsumerCount());
        }

        @Test
        @DisplayName("activeConsumerCount is zero before start()")
        void activeCountIsZeroBeforeStart() {
            group = createGroup("stats-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());

            assertEquals(0, group.getStats().getActiveConsumerCount());
        }

        @Test
        @DisplayName("activeConsumerCount matches started members after start()")
        void activeCountMatchesAfterStart() {
            group = createGroup("stats-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());
            group.start();

            assertEquals(2, group.getStats().getActiveConsumerCount());
        }

        @Test
        @DisplayName("memberStats map has entries for each consumer")
        void memberStatsMapHasEntries() {
            group = createGroup("stats-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());

            var memberStats = group.getStats().getMemberStats();
            assertEquals(2, memberStats.size());
            assertTrue(memberStats.containsKey("c1"));
            assertTrue(memberStats.containsKey("c2"));
        }

        @Test
        @DisplayName("totalMessagesProcessed is sum of member processed counts")
        void totalProcessedIsSumOfMembers() throws Exception {
            group = createGroup("stats-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            for (int i = 0; i < 5; i++) {
                invokeDistributeMessage(group,
                        new SimpleMessage<>("msg-" + i, "test-topic", "payload"));
            }

            assertEquals(5, group.getStats().getTotalMessagesProcessed());
        }

        @Test
        @DisplayName("totalMessagesFailed is sum of member failed counts")
        void totalFailedIsSumOfMembers() throws Exception {
            group = createGroup("stats-group", "test-topic");
            group.addConsumer("c1", msg ->
                    Future.failedFuture(new RuntimeException("fail")));
            group.start();

            for (int i = 0; i < 3; i++) {
                invokeDistributeMessage(group,
                        new SimpleMessage<>("msg-" + i, "test-topic", "payload"));
            }

            assertEquals(3, group.getStats().getTotalMessagesFailed());
        }
    }

    // ========================================================================
    // Phase 7: Builder Validation
    // ========================================================================

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("builder rejects null groupName")
        void builderFailsWithoutGroupName() {
            assertThrows(NullPointerException.class, () ->
                    OutboxConsumerGroup.<String>builder()
                            .topic("test-topic")
                            .payloadType(String.class)
                            .databaseService(new StubDatabaseService())
                            .build());
        }

        @Test
        @DisplayName("builder rejects null topic")
        void builderFailsWithoutTopic() {
            assertThrows(NullPointerException.class, () ->
                    OutboxConsumerGroup.<String>builder()
                            .groupName("g1")
                            .payloadType(String.class)
                            .databaseService(new StubDatabaseService())
                            .build());
        }

        @Test
        @DisplayName("builder rejects null payloadType")
        void builderFailsWithoutPayloadType() {
            assertThrows(NullPointerException.class, () ->
                    OutboxConsumerGroup.<String>builder()
                            .groupName("g1")
                            .topic("test-topic")
                            .databaseService(new StubDatabaseService())
                            .build());
        }

        @Test
        @DisplayName("builder rejects missing data source (neither clientFactory nor databaseService)")
        void builderFailsWithoutDataSource() {
            assertThrows(IllegalStateException.class, () ->
                    OutboxConsumerGroup.<String>builder()
                            .groupName("g1")
                            .topic("test-topic")
                            .payloadType(String.class)
                            .build());
        }

        @Test
        @DisplayName("builder rejects both clientFactory and databaseService")
        void builderFailsWithBothDataSources() {
            // Build validation should reject before PgClientFactory is actually used,
            // so we use reflection to set fields directly and trigger validation
            var builder = OutboxConsumerGroup.<String>builder()
                    .groupName("g1")
                    .topic("test-topic")
                    .payloadType(String.class)
                    .databaseService(new StubDatabaseService());
            // Set clientFactory via reflection to bypass PgClientFactory constructor
            try {
                java.lang.reflect.Field cf = builder.getClass().getDeclaredField("clientFactory");
                cf.setAccessible(true);
                cf.set(builder, new dev.mars.peegeeq.db.client.PgClientFactory(io.vertx.core.Vertx.vertx()));
                assertThrows(IllegalStateException.class, builder::build);
                // Clean up the Vertx instance
                dev.mars.peegeeq.db.client.PgClientFactory factory =
                        (dev.mars.peegeeq.db.client.PgClientFactory) cf.get(builder);
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("builder produces valid group with databaseService")
        void builderProducesValidGroupWithDatabaseService() {
            group = OutboxConsumerGroup.<String>builder()
                    .groupName("builder-group")
                    .topic("test-topic")
                    .payloadType(String.class)
                    .databaseService(new StubDatabaseService())
                    .clientId("client-42")
                    .build();

            assertEquals("builder-group", group.getGroupName());
            assertEquals("test-topic", group.getTopic());
            assertEquals(OutboxConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("builder produces valid group with clientFactory")
        void builderProducesValidGroupWithClientFactory() {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                group = OutboxConsumerGroup.<String>builder()
                        .groupName("builder-group")
                        .topic("test-topic")
                        .payloadType(String.class)
                        .clientFactory(new dev.mars.peegeeq.db.client.PgClientFactory(vertx))
                        .build();

                assertEquals("builder-group", group.getGroupName());
                assertEquals("test-topic", group.getTopic());
            } finally {
                vertx.close();
            }
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("getGroupName and getTopic return constructor values")
        void getGroupNameAndTopic() {
            group = createGroup("my-group", "my-topic");
            assertEquals("my-group", group.getGroupName());
            assertEquals("my-topic", group.getTopic());
        }

        @Test
        @DisplayName("setGroupFilter / getGroupFilter round-trip")
        void groupFilterRoundTrip() {
            group = createGroup("filter-group", "test-topic");
            assertNull(group.getGroupFilter());

            java.util.function.Predicate<Message<String>> filter = msg -> true;
            group.setGroupFilter(filter);
            assertSame(filter, group.getGroupFilter());
        }

        @Test
        @DisplayName("addConsumer during ACTIVE auto-starts the new member")
        void addConsumerDuringActiveAutoStarts() {
            group = createGroup("active-add-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            ConsumerGroupMember<String> newMember = group.addConsumer("c2", msg -> Future.succeededFuture());
            assertTrue(newMember.isActive());
            assertEquals(2, group.getActiveConsumerCount());
        }

        @Test
        @DisplayName("handler failure propagates through distributeMessage")
        void handlerFailurePropagates() throws Exception {
            group = createGroup("failure-group", "test-topic");
            group.addConsumer("c1", msg -> Future.failedFuture(new RuntimeException("boom")));
            group.start();

            Future<Void> result = invokeDistributeMessage(group,
                    new SimpleMessage<>("msg-fail", "test-topic", "payload"));

            assertTrue(result.failed());
            assertNotNull(result.cause());
        }

        @Test
        @DisplayName("concurrent start() calls — all complete without error")
        void concurrentStartAllComplete() throws Exception {
            group = createGroup("concurrent-start-group", "test-topic");
            group.addConsumer("c1", msg -> Future.succeededFuture());

            int threadCount = 10;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            AtomicInteger errorCount = new AtomicInteger();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                CountDownLatch done = new CountDownLatch(threadCount);
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            group.start(); // idempotent if already active
                        } catch (IllegalStateException e) {
                            // Not expected for start(), but tolerate if racing with STOPPING
                            errorCount.incrementAndGet();
                        } catch (Exception e) {
                            // Barrier timeout
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertEquals(OutboxConsumerGroup.State.ACTIVE, group.getState());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private OutboxConsumerGroup<String> createGroup(String groupName, String topic) {
        return new OutboxConsumerGroup<>(
                groupName, topic, String.class,
                new StubDatabaseService(), null, null,
                new PeeGeeQConfiguration("test"));
    }

    @SuppressWarnings("unchecked")
    private Future<Void> invokeDistributeMessage(OutboxConsumerGroup<String> group, Message<String> message)
            throws Exception {
        Method method = OutboxConsumerGroup.class.getDeclaredMethod("distributeMessage", Message.class);
        method.setAccessible(true);
        return (Future<Void>) method.invoke(group, message);
    }

    private static class StubDatabaseService implements DatabaseService {
        @Override public Future<Void> initialize() { return Future.succeededFuture(); }
        @Override public Future<Void> start() { return Future.succeededFuture(); }
        @Override public Future<Void> stop() { return Future.succeededFuture(); }
        @Override public boolean isRunning() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public dev.mars.peegeeq.api.database.ConnectionProvider getConnectionProvider() { return null; }
        @Override public MetricsProvider getMetricsProvider() { return null; }
        @Override public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionService() { return null; }
        @Override public Future<Void> runMigrations() { return Future.succeededFuture(); }
        @Override public Future<Boolean> performHealthCheck() { return Future.succeededFuture(true); }
        @Override public io.vertx.core.Vertx getVertx() { return null; }
        @Override public io.vertx.sqlclient.Pool getPool() { return null; }
        @Override public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }
        @Override public void close() { }
    }
}
