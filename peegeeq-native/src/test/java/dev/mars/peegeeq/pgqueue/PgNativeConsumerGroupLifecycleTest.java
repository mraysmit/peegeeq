package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core lifecycle tests for {@link PgNativeConsumerGroup}.
 *
 * <p>These are fast, in-process unit tests (no database, no Testcontainers).
 * They verify state machine transitions, membership concurrency safety,
 * and stop-path robustness that the code review identified as needing alignment
 * with {@code OutboxConsumerGroup}.</p>
 *
 * <p>TDD: written RED first against the current dual-AtomicBoolean implementation,
 * then the production code is updated to pass.</p>
 *
 * @see PgNativeConsumerGroup
 */
@Tag(TestCategories.CORE)
@DisplayName("PgNativeConsumerGroup lifecycle & safety")
class PgNativeConsumerGroupLifecycleTest {

    private PgNativeConsumerGroup<String> group;
    private final List<Vertx> extraVertxInstances = new CopyOnWriteArrayList<>();
    private final List<PgConnectionManager> extraConnectionManagers = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (group != null) {
            group.stopGracefully().onFailure(e -> fail("tearDown group.close() failed: " + e.getMessage()));
        }
        for (PgConnectionManager cm : extraConnectionManagers) {
            try { cm.close(); } catch (Exception ignored) { }
        }
        extraConnectionManagers.clear();
        for (Vertx vtx : extraVertxInstances) {
            vtx.close().onFailure(e -> fail("tearDown vtx.close() failed: " + e.getMessage()));
        }
        extraVertxInstances.clear();
    }

    // ========================================================================
    // 5.1 State Machine Transitions
    // ========================================================================

    @Nested
    @DisplayName("State Machine Transitions")
    class StateMachineTransitions {

        @Test
        @DisplayName("new group starts in NEW state")
        void newGroupStartsInNewState() {
            group = createGroup("sm-group", "topic-a");
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("start() transitions NEW → ACTIVE for reference-counting mode")
        void startTransitionsToActive() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());
            assertTrue(group.isActive());
        }

        @Test
        @DisplayName("start() is idempotent when already ACTIVE")
        void startIsIdempotentWhenActive() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertDoesNotThrow(() -> group.start());
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());
        }

        @Test
        @DisplayName("start() throws when group is CLOSED")
        void startThrowsWhenClosed() {
            group = createGroup("sm-group", "topic-a");
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
            Future<Void> result = group.start();
            assertTrue(result.failed(), "start() must return a failed future when CLOSED");
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        @Test
        @DisplayName("stop() transitions ACTIVE → NEW (restartable)")
        void stopTransitionsToNew() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("stop() is a no-op when not ACTIVE")
        void stopIsNoOpWhenNew() {
            group = createGroup("sm-group", "topic-a");
            assertTrue(group.stop().succeeded(), "stop() on non-active group must return a succeeded future");
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("stop() then start() allows restart cycle")
        void stopThenStartAllowsRestart() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());
        }

        @Test
        @DisplayName("close() from ACTIVE stops and transitions to CLOSED")
        void closeFromActiveTransitionsToClosed() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("close() from NEW goes directly to CLOSED")
        void closeFromNewGoesToClosed() {
            group = createGroup("sm-group", "topic-a");
            group.close().onFailure(e -> fail("close() failed in closeFromNew: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            group = createGroup("sm-group", "topic-a");
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
            assertDoesNotThrow(() -> group.close().onFailure(e -> fail("second close() failed: " + e.getMessage())));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("close() clears all members")
        void closeClearsMembers() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());
            assertEquals(2, group.getConsumerIds().size());
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
            assertTrue(group.getConsumerIds().isEmpty());
        }

        @Test
        @DisplayName("isActive() returns false in NEW, STOPPING, and CLOSED states")
        void isActiveReturnsFalseOutsideActive() {
            group = createGroup("sm-group", "topic-a");
            assertFalse(group.isActive(), "NEW");

            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertTrue(group.isActive(), "ACTIVE");

            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertFalse(group.isActive(), "back to NEW after stop");

            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
            assertFalse(group.isActive(), "CLOSED");
        }
    }

    // ========================================================================
    // 5.1 start(SubscriptionOptions) state transitions
    // ========================================================================

    @Nested
    @DisplayName("start(SubscriptionOptions) state transitions")
    class StartWithSubscription {

        @Test
        @DisplayName("start(SubscriptionOptions) rejects null argument")
        void rejectsNullOptions() {
            group = createGroup("sub-group", "topic-a");
            assertThrows(IllegalArgumentException.class, () -> group.start((SubscriptionOptions) null));
        }

        @Test
        @DisplayName("start(SubscriptionOptions) fails with future when CLOSED")
        void failsWhenClosed() {
            group = createGroup("sub-group", "topic-a");
            group.close().onFailure(e -> fail("close() failed in failsWhenClosed: " + e.getMessage()));

            var options = SubscriptionOptions.builder().build();
            Future<Void> result = group.start(options);
            assertTrue(result.failed());
            assertInstanceOf(IllegalStateException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("closed"),
                    "Message should mention 'closed', got: " + result.cause().getMessage());
        }

        @Test
        @DisplayName("start(SubscriptionOptions) is idempotent when already ACTIVE")
        void succeedsWhenAlreadyActive() {
            group = createGroup("sub-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            var options = SubscriptionOptions.builder().build();
            Future<Void> result = group.start(options);
            assertTrue(result.succeeded(), "Should be idempotent when already active");
        }

        @Test
        @DisplayName("start(SubscriptionOptions) without databaseService calls startInternal directly")
        void withoutDatabaseService_startsNormally() {
            group = createGroup("sub-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());

            var options = SubscriptionOptions.builder().build();
            Future<Void> result = group.start(options);
            assertTrue(result.succeeded(), "start(options) with null databaseService should succeed");
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());
        }
    }

    // ========================================================================
    // 5.2 Membership Concurrency (putIfAbsent)
    // ========================================================================

    @Nested
    @DisplayName("Membership Concurrency")
    class MembershipConcurrency {

        @Test
        @DisplayName("addConsumer rejects duplicate consumer IDs")
        void rejectsDuplicateIds() {
            group = createGroup("mem-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            assertThrows(IllegalArgumentException.class,
                    () -> group.addConsumer("c1", msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("addConsumer throws when group is CLOSED")
        void throwsWhenClosed() {
            group = createGroup("mem-group", "topic-a");
            group.close().onFailure(e -> fail("close() failed in throwsWhenClosed: " + e.getMessage()));
            assertThrows(IllegalStateException.class,
                    () -> group.addConsumer("c1", msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("removeConsumer returns false for unknown ID")
        void removeUnknownReturnsFalse() {
            group = createGroup("mem-group", "topic-a");
            assertFalse(group.removeConsumer("nonexistent"));
        }

        @Test
        @DisplayName("removeConsumer returns true and removes known consumer")
        void removeKnownReturnsTrue() {
            group = createGroup("mem-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            assertTrue(group.removeConsumer("c1"));
            assertFalse(group.getConsumerIds().contains("c1"));
        }

        @Test
        @DisplayName("concurrent addConsumer with distinct IDs all succeed")
        void concurrentAddDistinctIds() throws Exception {
            group = createGroup("mem-group", "topic-a");
            int threadCount = 10;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Throwable> errors = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final String id = "consumer-" + i;
                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        group.addConsumer(id, msg -> Future.succeededFuture());
                    } catch (Exception e) {
                        errors.add(e);
                    }
                });
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(0, errors.size(), "No errors expected, got: " + errors);
            assertEquals(threadCount, group.getConsumerIds().size());
        }

        @Test
        @DisplayName("concurrent addConsumer with same ID: exactly one wins")
        void concurrentAddSameId() throws Exception {
            group = createGroup("mem-group", "topic-a");
            int threadCount = 10;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<ConsumerGroupMember<String>> winners = new CopyOnWriteArrayList<>();
            List<Throwable> rejections = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        ConsumerGroupMember<String> member =
                                group.addConsumer("same-id", msg -> Future.succeededFuture());
                        winners.add(member);
                    } catch (IllegalArgumentException e) {
                        rejections.add(e);
                    } catch (Exception e) {
                        // barrier/interrupt not relevant
                    }
                });
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, winners.size(), "Exactly one thread should win");
            assertEquals(threadCount - 1, rejections.size(),
                    "All others should be rejected with IllegalArgumentException");
            assertEquals(1, group.getConsumerIds().size());
        }

        @Test
        @DisplayName("setMessageHandler rejects second call")
        void setMessageHandlerRejectsSecondCall() {
            group = createGroup("mem-group", "topic-a");
            group.setMessageHandler(msg -> Future.succeededFuture());
            assertThrows(IllegalStateException.class,
                    () -> group.setMessageHandler(msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("setMessageHandler rejects null handler")
        void setMessageHandlerRejectsNull() {
            group = createGroup("mem-group", "topic-a");
            assertThrows(IllegalArgumentException.class,
                    () -> group.setMessageHandler(null));
        }
    }

    // ========================================================================
    // 5.10 Stop Path Robustness
    // ========================================================================

    @Nested
    @DisplayName("Stop Path Robustness")
    class StopPathRobustness {

        @Test
        @DisplayName("stop resets state to NEW even in reference-counting mode")
        void stopResetsStateToNew() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());

            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                    "State must be NEW after stop, not stuck in STOPPING or ACTIVE");
        }

        @Test
        @DisplayName("stopGracefully returns succeeded future when not active")
        void stopGracefullySucceedsWhenNotActive() {
            group = createGroup("stop-group", "topic-a");
            Future<Void> result = group.stopGracefully();
            assertTrue(result.succeeded(), "stopGracefully on non-active group should succeed");
        }

        @Test
        @DisplayName("stopGracefully returns a succeeded future after reference-counting stop")
        void stopGracefullyReturnsFutureRefCounting() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            Future<Void> result = group.stopGracefully();
            assertTrue(result.succeeded(),
                    "stopGracefully must return a succeeded future (not null or pending)");
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("stopGracefully transitions ACTIVE → NEW")
        void stopGracefullyTransitionsToNew() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully();
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("multiple stop calls are idempotent")
        void multipleStopsIdempotent() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            group.stop().onFailure(e -> fail("second stop() failed: " + e.getMessage()));
            group.stop().onFailure(e -> fail("third stop() failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("close after stop is safe")
        void closeAfterStopIsSafe() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertDoesNotThrow(() -> group.close());
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("stopCloseStartThrows stop then close then start throws IllegalStateException")
        void stopCloseStartThrows() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            group.close().onFailure(e -> fail("close() failed in stopCloseStart: " + e.getMessage()));
            Future<Void> result = group.start();
            assertTrue(result.failed(), "start() must return a failed future when CLOSED");
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        @Test
        @DisplayName("stopInternal() clears underlyingConsumer when in reference-counting mode")
        void stopClearsUnderlyingConsumer() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());

            // Stop and restart should not fail due to stale underlyingConsumer
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                    "restart should succeed underlyingConsumer was cleaned up on stop");
        }
    }

    // ========================================================================
    // CAS Safety close() vs async chains
    // ========================================================================

    @Nested
    @DisplayName("CAS Safety close during lifecycle transitions")
    class CASSafety {

        @Test
        @DisplayName("close during ACTIVE→STOPPING→NEW does not get overwritten back to NEW after CLOSED")
        void closeDuringStopKeepsClosed() {
            group = createGroup("cas-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());

            // Graceful stop starts the STOPPING transition
            group.stopGracefully();
            // Immediately close should set CLOSED
            group.close().onFailure(e -> fail("close() failed in closeDuringStop: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                    "close() must win over stop's NEW reset state should be CLOSED");
        }

        @Test
        @DisplayName("close during start+stop cycle preserves CLOSED")
        void closeOverridesStartStopCycle() {
            group = createGroup("cas-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            group.close().onFailure(e -> fail("close() failed in closeOverrides: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());

            // Verify no operation can resurrect from CLOSED
            Future<Void> result = group.start();
            assertTrue(result.failed(), "start() must return a failed future when CLOSED");
            assertInstanceOf(IllegalStateException.class, result.cause());
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                    "State must remain CLOSED after failed start attempt");
        }

        @Test
        @DisplayName("concurrent close and stop CLOSED always wins")
        void concurrentCloseAndStop() throws Exception {
            group = createGroup("cas-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            int threadCount = 10;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // Half the threads call stop(), half call close()
            for (int i = 0; i < threadCount; i++) {
                final boolean doClose = (i % 2 == 0);
                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        if (doClose) {
                            group.close().onFailure(e -> {});
                        } else {
                            group.stopGracefully().onFailure(e -> {});
                        }
                    } catch (Exception ignored) {}
                });
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            // At least one close() ran, so state must be CLOSED
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                    "After concurrent stop+close, state must be CLOSED");
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("getGroupName() and getTopic() return constructor values")
        void accessors() {
            group = createGroup("acc-group", "acc-topic");
            assertEquals("acc-group", group.getGroupName());
            assertEquals("acc-topic", group.getTopic());
        }

        @Test
        @DisplayName("6-arg constructor delegates correctly")
        void sixArgConstructor() {
            VertxPoolAdapter adapter = new VertxPoolAdapter(null, null, null);
            group = new PgNativeConsumerGroup<>(
                    "six-group", "six-topic", String.class,
                    adapter, null, null);
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            assertEquals("six-group", group.getGroupName());
        }

        @Test
        @DisplayName("7-arg constructor delegates correctly")
        void sevenArgConstructor() {
            VertxPoolAdapter adapter = new VertxPoolAdapter(null, null, null);
            group = new PgNativeConsumerGroup<>(
                    "seven-group", "seven-topic", String.class,
                    adapter, null, null, null);
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            assertEquals("seven-group", group.getGroupName());
        }

        @Test
        @DisplayName("start with non-null PeeGeeQConfiguration creates configuration-aware consumer")
        void startWithConfiguration() {
            VertxPoolAdapter adapter = new VertxPoolAdapter(null, null, null);
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test", new Properties());
            group = new PgNativeConsumerGroup<>(
                    "cfg-group", "cfg-topic", String.class,
                    adapter, null, null, config, null);
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                    "Group with configuration should start in ACTIVE");
        }

        @Test
        @DisplayName("start/stop/restart with configuration works")
        void startStopRestartWithConfiguration() {
            VertxPoolAdapter adapter = new VertxPoolAdapter(null, null, null);
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("test", new Properties());
            group = new PgNativeConsumerGroup<>(
                    "cfg-group", "cfg-topic", String.class,
                    adapter, null, null, config, null);
            group.addConsumer("c1", msg -> Future.succeededFuture());

            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());

            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());

            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                    "Restart with configuration should succeed");
        }

        @Test
        @DisplayName("addConsumer to active group auto-starts the member")
        void addConsumerToActiveGroupAutoStarts() {
            group = createGroup("edge-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            // Add a second consumer while active
            ConsumerGroupMember<String> member = group.addConsumer("c2", msg -> Future.succeededFuture());
            assertTrue(member.isActive(), "Consumer added to active group should be auto-started");
            assertEquals(2, group.getActiveConsumerCount());
        }

        @Test
        @DisplayName("group filter can be set and retrieved")
        void groupFilterSetAndGet() {
            group = createGroup("edge-group", "topic-a");
            assertNull(group.getGroupFilter());

            group.setGroupFilter(msg -> true);
            assertNotNull(group.getGroupFilter());
        }

        @Test
        @DisplayName("getStats returns valid stats with no members")
        void statsWithNoMembers() {
            group = createGroup("edge-group", "topic-a");
            var stats = group.getStats();
            assertEquals("edge-group", stats.getGroupName());
            assertEquals("topic-a", stats.getTopic());
            assertEquals(0, stats.getTotalConsumerCount());
            assertEquals(0, stats.getActiveConsumerCount());
            assertEquals(0, stats.getTotalMessagesProcessed());
        }

        @Test
        @DisplayName("getStats returns valid stats with active members")
        void statsWithActiveMembers() {
            group = createGroup("edge-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());
            group.start();

            var stats = group.getStats();
            assertEquals(2, stats.getTotalConsumerCount());
            assertEquals(2, stats.getActiveConsumerCount());
        }
    }

    // ========================================================================
    // Message Distribution (distributeMessage routing logic)
    // ========================================================================

    @Nested
    @DisplayName("Message Distribution")
    class MessageDistribution {

        private Message<String> createMessage(String id, String payload) {
            return new SimpleMessage<>(id, "topic-a", payload, Map.of(), null, null);
        }

        private Message<String> createMessageWithHeaders(String id, String payload, Map<String, String> headers) {
            return new SimpleMessage<>(id, "topic-a", payload, headers, null, null);
        }

        @Test
        @DisplayName("message dispatched to active consumer increments stats")
        void normalDispatch_incrementsProcessedCount() {
            group = createGroup("dm-group", "topic-a");
            AtomicInteger handlerCalls = new AtomicInteger(0);
            group.addConsumer("c1", msg -> {
                handlerCalls.incrementAndGet();
                return Future.succeededFuture();
            });
            group.start();

            Future<Void> result = group.distributeMessage(createMessage("msg-1", "hello"));
            assertTrue(result.succeeded(), "distributeMessage should succeed");
            assertEquals(1, handlerCalls.get(), "handler should be called once");

            var stats = group.getStats();
            assertEquals(1, stats.getTotalMessagesProcessed());
            assertEquals(0, stats.getTotalMessagesFailed());
        }

        @Test
        @DisplayName("multiple messages dispatched round-robin across consumers")
        void roundRobin_distributesAcrossConsumers() {
            group = createGroup("dm-group", "topic-a");
            AtomicInteger consumer1Calls = new AtomicInteger(0);
            AtomicInteger consumer2Calls = new AtomicInteger(0);
            group.addConsumer("c1", msg -> { consumer1Calls.incrementAndGet(); return Future.succeededFuture(); });
            group.addConsumer("c2", msg -> { consumer2Calls.incrementAndGet(); return Future.succeededFuture(); });
            group.start();

            // Send enough messages to exercise both consumers
            for (int i = 0; i < 20; i++) {
                group.distributeMessage(createMessage("msg-" + i, "data-" + i));
            }

            assertTrue(consumer1Calls.get() > 0, "consumer 1 should receive some messages");
            assertTrue(consumer2Calls.get() > 0, "consumer 2 should receive some messages");
            assertEquals(20, consumer1Calls.get() + consumer2Calls.get(),
                    "total handled should equal messages sent");
        }

        @Test
        @DisplayName("group filter rejects message filtered count increments")
        void groupFilterRejects_incrementsFilteredCount() {
            group = createGroup("dm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.setGroupFilter(msg -> false); // reject all

            Future<Void> result = group.distributeMessage(createMessage("msg-1", "hello"));
            assertTrue(result.succeeded(), "distributeMessage should succeed even when filtered");

            var stats = group.getStats();
            assertEquals(0, stats.getTotalMessagesProcessed(), "No messages should be processed");
            assertTrue(stats.getTotalMessagesFiltered() > 0, "Filtered count should increase");
        }

        @Test
        @DisplayName("no eligible consumers filtered count increments")
        void noEligibleConsumers_incrementsFilteredCount() {
            group = createGroup("dm-group", "topic-a");
            // Add consumer with a filter that rejects everything
            group.addConsumer("c1", msg -> Future.succeededFuture(), msg -> false);
            group.start();

            Future<Void> result = group.distributeMessage(createMessage("msg-1", "hello"));
            assertTrue(result.succeeded(), "distributeMessage should succeed when no consumers eligible");

            var stats = group.getStats();
            assertEquals(0, stats.getTotalMessagesProcessed());
            assertTrue(stats.getTotalMessagesFiltered() > 0, "Filtered count should increase");
        }

        @Test
        @DisplayName("handler failure increments failed count")
        void handlerFailure_incrementsFailedCount() {
            group = createGroup("dm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.failedFuture(new RuntimeException("handler error")));
            group.start();

            Future<Void> result = group.distributeMessage(createMessage("msg-1", "hello"));
            assertTrue(result.failed(), "distributeMessage should propagate handler failure");

            var stats = group.getStats();
            assertEquals(1, stats.getTotalMessagesFailed());
        }

        @Test
        @DisplayName("message with traceparent header is dispatched successfully")
        void withTraceparentHeader_dispatchesSuccessfully() {
            group = createGroup("dm-group", "topic-a");
            AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();
            group.addConsumer("c1", msg -> {
                receivedHeaders.set(msg.getHeaders());
                return Future.succeededFuture();
            });
            group.start();

            Map<String, String> headers = Map.of(
                    "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                    "custom-header", "value"
            );
            Future<Void> result = group.distributeMessage(
                    createMessageWithHeaders("msg-1", "hello", headers));
            assertTrue(result.succeeded());
            assertNotNull(receivedHeaders.get());
        }

        @Test
        @DisplayName("distributeMessage with no active members all stopped")
        void noActiveMembers_filteredAsNoEligible() {
            group = createGroup("dm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            // Not started members not active

            // Force state to ACTIVE for testing distributeMessage directly
            // We start and then stop members manually by removing them
            group.start();
            group.removeConsumer("c1");

            Future<Void> result = group.distributeMessage(createMessage("msg-1", "hello"));
            assertTrue(result.succeeded());

            var stats = group.getStats();
            assertTrue(stats.getTotalMessagesFiltered() > 0);
        }
    }

    // ========================================================================
    // Bad Consumer Behavior filters that throw, all consumers fail, etc.
    // ========================================================================

    @Nested
    @DisplayName("Bad Consumer Behavior")
    class BadConsumerBehavior {

        private Message<String> msg(String id) {
            return new SimpleMessage<>(id, "topic-a", "payload", Map.of(), null, null);
        }

        @Test
        @DisplayName("F5: consumer filter that throws exception message rejected, no crash")
        void filterThrowsException_messageRejected() {
            group = createGroup("bad-group", "topic-a");
            // Consumer whose filter always throws
            group.addConsumer("c1", msg -> Future.succeededFuture(),
                    msg -> { throw new RuntimeException("filter exploded"); });
            group.start();

            Future<Void> result = group.distributeMessage(msg("msg-1"));
            assertTrue(result.succeeded(),
                    "distributeMessage should succeed message is filtered/dropped, not error");

            var stats = group.getStats();
            assertEquals(0, stats.getTotalMessagesProcessed(),
                    "Message should not be processed");
            assertTrue(stats.getTotalMessagesFiltered() > 0,
                    "Message should count as filtered");
        }

        @Test
        @DisplayName("F5: two consumers, one filter throws message routed to healthy consumer")
        void filterThrowsOnOne_routedToOther() {
            group = createGroup("bad-group", "topic-a");
            AtomicInteger healthyCalls = new AtomicInteger(0);

            // c1's filter always throws
            group.addConsumer("c1", msg -> Future.succeededFuture(),
                    msg -> { throw new RuntimeException("c1 filter broken"); });
            // c2 is healthy
            group.addConsumer("c2", msg -> { healthyCalls.incrementAndGet(); return Future.succeededFuture(); });
            group.start();

            // Send enough messages: round-robin selects c2 for some IDs since c1 is filtered out
            for (int i = 0; i < 10; i++) {
                group.distributeMessage(msg("msg-" + i));
            }

            assertTrue(healthyCalls.get() > 0,
                    "Healthy consumer should receive messages when broken filter excludes c1");
        }

        @Test
        @DisplayName("F7: all consumers fail failed count increments, no failover retry")
        void allConsumersFail_noFailoverRetry() {
            group = createGroup("bad-group", "topic-a");
            AtomicInteger c1Calls = new AtomicInteger(0);
            AtomicInteger c2Calls = new AtomicInteger(0);

            group.addConsumer("c1", msg -> {
                c1Calls.incrementAndGet();
                return Future.failedFuture(new RuntimeException("c1 exploded"));
            });
            group.addConsumer("c2", msg -> {
                c2Calls.incrementAndGet();
                return Future.failedFuture(new RuntimeException("c2 exploded"));
            });
            group.start();

            // Send messages each is routed to exactly one consumer via round-robin
            for (int i = 0; i < 10; i++) {
                Future<Void> result = group.distributeMessage(msg("msg-" + i));
                assertTrue(result.failed(), "Each message should fail");
            }

            // Total calls should equal total messages no retries to alternative consumer
            assertEquals(10, c1Calls.get() + c2Calls.get(),
                    "Each message routed to exactly one consumer, no failover retry");
            assertTrue(c1Calls.get() > 0, "c1 should receive some messages");
            assertTrue(c2Calls.get() > 0, "c2 should receive some messages");

            var stats = group.getStats();
            assertEquals(10, stats.getTotalMessagesFailed(),
                    "All 10 messages should be counted as failed");
            assertEquals(0, stats.getTotalMessagesProcessed(),
                    "No messages should be processed successfully");
        }

        @Test
        @DisplayName("F6: removeConsumer during distributeMessage no NPE, fails gracefully")
        void removeConsumerDuringDispatch_failsGracefully() {
            group = createGroup("bad-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();

            // Remove the consumer simulates concurrent removal
            group.removeConsumer("c1");

            // Now distributeMessage with no active consumers
            Future<Void> result = group.distributeMessage(msg("msg-1"));

            // Should succeed (filtered as "no eligible consumers") not throw NPE
            assertTrue(result.succeeded(),
                    "Should succeed as filtered when no active consumers remain");
            var stats = group.getStats();
            assertTrue(stats.getTotalMessagesFiltered() > 0);
        }

        @Test
        @DisplayName("F6: consumer handler throws synchronously exception propagates (not caught)")
        void handlerThrowsSynchronously_exceptionPropagates() {
            group = createGroup("bad-group", "topic-a");
            group.addConsumer("c1", msg -> { throw new RuntimeException("sync boom"); });
            group.start();

            // A synchronous throw from the handler escapes the Future chain.
            // processMessage() calls handler.handle(message) without try-catch,
            // so synchronous throws propagate directly rather than becoming failed futures.
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> group.distributeMessage(msg("msg-1")),
                    "Synchronous throw from handler propagates to caller");
            assertEquals("sync boom", thrown.getMessage());
        }
    }

    // ========================================================================
    // Consumer Selection (round-robin index calculation)
    // ========================================================================

    @Nested
    @DisplayName("Consumer Selection")
    class ConsumerSelection {

        @Test
        @DisplayName("positive hash produces valid index")
        void positiveHash() {
            assertEquals(3, PgNativeConsumerGroup.selectConsumerIndex(13, 5));
            assertEquals(0, PgNativeConsumerGroup.selectConsumerIndex(10, 5));
        }

        @Test
        @DisplayName("zero hash produces index 0")
        void zeroHash() {
            assertEquals(0, PgNativeConsumerGroup.selectConsumerIndex(0, 3));
        }

        @Test
        @DisplayName("negative hash produces valid non-negative index (floorMod)")
        void negativeHash() {
            int result = PgNativeConsumerGroup.selectConsumerIndex(-7, 3);
            assertTrue(result >= 0 && result < 3,
                    "floorMod should return non-negative index, got: " + result);
            assertEquals(2, result); // floorMod(-7, 3) = 2
        }

        @Test
        @DisplayName("Integer.MIN_VALUE hash produces valid index (no overflow)")
        void minValueHash() {
            int result = PgNativeConsumerGroup.selectConsumerIndex(Integer.MIN_VALUE, 7);
            assertTrue(result >= 0 && result < 7,
                    "floorMod must handle MIN_VALUE safely, got: " + result);
        }

        @Test
        @DisplayName("single consumer always selected")
        void singleConsumer() {
            assertEquals(0, PgNativeConsumerGroup.selectConsumerIndex(42, 1));
            assertEquals(0, PgNativeConsumerGroup.selectConsumerIndex(-42, 1));
            assertEquals(0, PgNativeConsumerGroup.selectConsumerIndex(0, 1));
        }
    }

    // ========================================================================
    // 5.x Partitioned Path with connectionManager (no pool → fallback)
    //
    // When connectionManager is non-null, startInternal() calls
    // isOffsetWatermarkTopic() which fails (no pool) → .transform() catches it
    // → falls back to reference-counting mode. This exercises the branching
    // in startInternal() that the null-connectionManager tests never reach.
    // ========================================================================

    @Nested
    @DisplayName("Partitioned Fallback (connectionManager present, no pool)")
    class PartitionedFallback {

        @Test
        @DisplayName("start() with connectionManager but no pool falls back to reference counting")
        void startWithConnectionManager_fallsBackToReferenceCounting() {
            group = createGroupWithConnectionManager("pf-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            // The transform path in startInternal catches the "no pool" error
            // and falls back to reference counting, which succeeds
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                    "Should reach ACTIVE via reference-counting fallback");
            assertTrue(group.isActive());
        }

        @Test
        @DisplayName("start/stop/start cycle works with connectionManager fallback path")
        void startStopRestart_withConnectionManager() {
            group = createGroupWithConnectionManager("pf-group", "topic-b");
            group.addConsumer("c1", msg -> Future.succeededFuture());

            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState());

            group.stopGracefully().onFailure(e -> fail("stop failed: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());

            group.start();
            assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                    "Restart should succeed via fallback path");
        }

        @Test
        @DisplayName("close during fallback startup leaves state CLOSED")
        void closeDuringFallbackStartup() {
            group = createGroupWithConnectionManager("pf-group", "topic-c");
            group.addConsumer("c1", msg -> Future.succeededFuture());

            group.start();
            // Even though start is async, the transform → reference-counting path
            // completes synchronously on the fallback branch
            group.close().onFailure(e -> fail("close() failed in closeDuringFallback: " + e.getMessage()));
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }
    }

    // ========================================================================

    /**
     * Creates a PgNativeConsumerGroup with a minimal VertxPoolAdapter (null Vert.x/pool).
     * The consumer will be created but won't actually listen or poll sufficient for
     * lifecycle and membership tests that don't touch the database.
     * Uses the 8-arg constructor (no connectionManager → reference-counting mode).
     */
    private PgNativeConsumerGroup<String> createGroup(String groupName, String topic) {
        VertxPoolAdapter adapter = new VertxPoolAdapter(null, null, null);
        return new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, null, null, null, null);
    }

    /**
     * Creates a PgNativeConsumerGroup with a non-null connectionManager (but no pool registered).
     * This exercises the partitioned-detection path in startInternal():
     * isOffsetWatermarkTopic() fails (no pool) → .transform() → falls back to reference counting.
     */
    private PgNativeConsumerGroup<String> createGroupWithConnectionManager(String groupName, String topic) {
        Vertx vtx = Vertx.vertx();
        extraVertxInstances.add(vtx);
        PgConnectionManager connMgr = new PgConnectionManager(vtx);
        extraConnectionManagers.add(connMgr);
        VertxPoolAdapter adapter = new VertxPoolAdapter(vtx, null, null);
        return new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, null, null, null, null,
                connMgr, "test-svc");
    }
}
