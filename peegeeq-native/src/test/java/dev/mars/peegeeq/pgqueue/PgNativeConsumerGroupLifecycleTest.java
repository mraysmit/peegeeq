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
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
@DisplayName("PgNativeConsumerGroup — lifecycle & safety")
class PgNativeConsumerGroupLifecycleTest {

    private PgNativeConsumerGroup<String> group;

    @AfterEach
    void tearDown() {
        if (group != null) {
            try {
                group.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    // ========================================================================
    // 5.1 — State Machine Transitions
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
            group.close();
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
            assertThrows(IllegalStateException.class, () -> group.start());
        }

        @Test
        @DisplayName("stop() transitions ACTIVE → NEW (restartable)")
        void stopTransitionsToNew() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("stop() is a no-op when not ACTIVE")
        void stopIsNoOpWhenNew() {
            group = createGroup("sm-group", "topic-a");
            assertDoesNotThrow(() -> group.stop());
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("stop() then start() allows restart cycle")
        void stopThenStartAllowsRestart() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
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
            group.close();
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("close() from NEW goes directly to CLOSED")
        void closeFromNewGoesToClosed() {
            group = createGroup("sm-group", "topic-a");
            group.close();
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            group = createGroup("sm-group", "topic-a");
            group.close();
            assertDoesNotThrow(() -> group.close());
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("close() clears all members")
        void closeClearsMembers() {
            group = createGroup("sm-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.addConsumer("c2", msg -> Future.succeededFuture());
            assertEquals(2, group.getConsumerIds().size());
            group.close();
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

            group.stop();
            assertFalse(group.isActive(), "back to NEW after stop");

            group.close();
            assertFalse(group.isActive(), "CLOSED");
        }
    }

    // ========================================================================
    // 5.1 — start(SubscriptionOptions) state transitions
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
            group.close();

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
    }

    // ========================================================================
    // 5.2 — Membership Concurrency (putIfAbsent)
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
            group.close();
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
                        // barrier/interrupt — not relevant
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
    // 5.10 — Stop Path Robustness
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

            group.stop();
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
            group.stop();
            assertDoesNotThrow(() -> group.stop());
            assertDoesNotThrow(() -> group.stop());
            assertEquals(PgNativeConsumerGroup.State.NEW, group.getState());
        }

        @Test
        @DisplayName("close after stop is safe")
        void closeAfterStopIsSafe() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
            assertDoesNotThrow(() -> group.close());
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
        }

        @Test
        @DisplayName("stop then close then start throws IllegalStateException")
        void stopCloseStartThrows() {
            group = createGroup("stop-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
            group.close();
            assertThrows(IllegalStateException.class, () -> group.start());
        }
    }

    // ========================================================================
    // CAS Safety — close() vs async chains
    // ========================================================================

    @Nested
    @DisplayName("CAS Safety — close during lifecycle transitions")
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
            // Immediately close — should set CLOSED
            group.close();
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                    "close() must win over stop's NEW reset — state should be CLOSED");
        }

        @Test
        @DisplayName("close during start+stop cycle preserves CLOSED")
        void closeOverridesStartStopCycle() {
            group = createGroup("cas-group", "topic-a");
            group.addConsumer("c1", msg -> Future.succeededFuture());
            group.start();
            group.stop();
            group.close();
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());

            // Verify no operation can resurrect from CLOSED
            assertThrows(IllegalStateException.class, () -> group.start());
            assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                    "State must remain CLOSED after failed start attempt");
        }

        @Test
        @DisplayName("concurrent close and stop — CLOSED always wins")
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
                            group.close();
                        } else {
                            group.stop();
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
    // Helpers
    // ========================================================================

    /**
     * Creates a PgNativeConsumerGroup with a minimal VertxPoolAdapter (null Vert.x/pool).
     * The consumer will be created but won't actually listen or poll — sufficient for
     * lifecycle and membership tests that don't touch the database.
     * Uses the 8-arg constructor (no connectionManager → reference-counting mode).
     */
    private PgNativeConsumerGroup<String> createGroup(String groupName, String topic) {
        VertxPoolAdapter adapter = new VertxPoolAdapter(null, null, null);
        return new PgNativeConsumerGroup<>(
                groupName, topic, String.class,
                adapter, null, null, null, null);
    }
}
