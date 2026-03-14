/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
 */

package dev.mars.peegeeq.bitemporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
@Testcontainers
@Isolated
class ReactiveNotificationHandlerFailurePathTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("reactive_failure_path_test")
            .withUsername("test_user")
            .withPassword("test_password")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PgConnectOptions connectOptions;

    @BeforeEach
    void setUp() {
        connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
    }

    @Test
    void startShouldReplayListenChannelsForExistingSubscriptions() {
        Vertx vertx = Vertx.vertx();
        try {
            Promise<PgConnection> connectPromise = Promise.promise();
            AtomicInteger connectCalls = new AtomicInteger(0);
            CopyOnWriteArrayList<String> listenChannels = new CopyOnWriteArrayList<>();

            TestableReactiveNotificationHandler handler = new TestableReactiveNotificationHandler(
                    vertx,
                    connectOptions,
                    eventId -> Future.succeededFuture(null),
                    connectPromise::future,
                    connectCalls,
                    listenChannels,
                    channel -> Future.succeededFuture(),
                    channel -> Future.succeededFuture(),
                    new AtomicBoolean(false));

            handler.subscriptionsView().put(SubscriptionKey.of("order.created", null), new CopyOnWriteArrayList<>());
            handler.listeningChannelsView().add("stale_channel_state_should_be_cleared");

            Future<Void> startFuture = handler.start();
            connectPromise.complete(connect(vertx));
            startFuture.toCompletionStage().toCompletableFuture().join();

            assertEquals(1, connectCalls.get(), "Expected a single connect attempt");
            assertTrue(listenChannels.contains("public_bitemporal_events_bitemporal_event_log_order_created"),
                    "Existing subscriptions should re-issue LISTEN commands after connect");
            assertFalse(handler.listeningChannelsView().contains("stale_channel_state_should_be_cleared"),
                    "Reconnect replay should discard stale in-memory LISTEN state");

            handler.stop().toCompletionStage().toCompletableFuture().join();
        } finally {
            vertx.close();
        }
    }

    @Test
    void startShouldDeduplicateConcurrentCalls() {
        Vertx vertx = Vertx.vertx();
        try {
            Promise<PgConnection> connectPromise = Promise.promise();
            AtomicInteger connectCalls = new AtomicInteger(0);

            TestableReactiveNotificationHandler handler = new TestableReactiveNotificationHandler(
                    vertx,
                    connectOptions,
                    eventId -> Future.succeededFuture(null),
                    connectPromise::future,
                    connectCalls,
                    new CopyOnWriteArrayList<>(),
                    channel -> Future.succeededFuture(),
                    channel -> Future.succeededFuture(),
                    new AtomicBoolean(false));

            Future<Void> first = handler.start();
            Future<Void> second = handler.start();

            assertSame(first, second, "Concurrent start calls should return the same in-progress future");
            assertEquals(1, connectCalls.get(), "Only one physical connect attempt should be started");

            connectPromise.complete(connect(vertx));
            first.toCompletionStage().toCompletableFuture().join();
            assertTrue(handler.isActive(), "Handler should become active after connection succeeds");

            handler.stop().toCompletionStage().toCompletableFuture().join();
        } finally {
            vertx.close();
        }
    }

    @Test
    void stopShouldFailWhenUnlistenFailsAndStillCleanupState() {
        Vertx vertx = Vertx.vertx();
        try {
            AtomicBoolean closeCalled = new AtomicBoolean(false);

            TestableReactiveNotificationHandler handler = new TestableReactiveNotificationHandler(
                    vertx,
                    connectOptions,
                    eventId -> Future.succeededFuture(null),
                    () -> Future.succeededFuture(connect(vertx)),
                    new AtomicInteger(0),
                    new CopyOnWriteArrayList<>(),
                    channel -> Future.succeededFuture(),
                    channel -> "public_bitemporal_events_bitemporal_event_log_test_event".equals(channel)
                            ? Future.failedFuture(new RuntimeException("forced unlisten failure"))
                            : Future.succeededFuture(),
                    closeCalled);

            handler.start().toCompletionStage().toCompletableFuture().join();
            handler.subscribe("test.event", null, message -> CompletableFuture.completedFuture(null))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> handler.stop().toCompletionStage().toCompletableFuture().join());
            assertNotNull(thrown.getCause());
            assertTrue(thrown.getCause().getMessage().contains("forced unlisten failure"));

            assertFalse(handler.isActive());
            assertFalse(handler.hasListenConnection());
            assertTrue(handler.listeningChannelsView().isEmpty(), "Listening channels should be cleaned up on failed stop");
            assertTrue(handler.subscriptionsView().isEmpty(), "Subscriptions should be cleaned up on failed stop");
            assertTrue(closeCalled.get(), "stop() should attempt to close connection even after UNLISTEN failure");
        } finally {
            vertx.close();
        }
    }

    @Test
    void subscribeShouldRejectNullHandlerBeforeActiveCheck() {
        Vertx vertx = Vertx.vertx();
        try {
            ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
                    vertx,
                    connectOptions,
                    new ObjectMapper(),
                    String.class,
                    eventId -> Future.succeededFuture(null));

            Throwable thrown = assertThrows(CompletionException.class,
                    () -> handler.subscribe("order.created", null, (MessageHandler<BiTemporalEvent<String>>) null)
                            .toCompletionStage()
                            .toCompletableFuture()
                            .join());
            assertTrue(thrown.getCause() instanceof IllegalArgumentException);
            assertTrue(thrown.getCause().getMessage().contains("Handler cannot be null"));
        } finally {
            vertx.close();
        }
    }

    @Test
    void subscribeShouldRollbackStateWhenListenSetupFails() {
        Vertx vertx = Vertx.vertx();
        try {
            String failingChannel = "public_bitemporal_events_bitemporal_event_log_test_event";
            TestableReactiveNotificationHandler handler = new TestableReactiveNotificationHandler(
                    vertx,
                    connectOptions,
                    eventId -> Future.succeededFuture(null),
                    () -> Future.succeededFuture(connect(vertx)),
                    new AtomicInteger(0),
                    new CopyOnWriteArrayList<>(),
                    channel -> failingChannel.equals(channel)
                            ? Future.failedFuture(new RuntimeException("forced listen failure"))
                            : Future.succeededFuture(),
                    channel -> Future.succeededFuture(),
                    new AtomicBoolean(false));

            handler.start().toCompletionStage().toCompletableFuture().join();

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> handler.subscribe("test.event", null, message -> CompletableFuture.completedFuture(null))
                            .toCompletionStage()
                            .toCompletableFuture()
                            .join());
            assertNotNull(thrown.getCause());
            assertTrue(thrown.getCause().getMessage().contains("forced listen failure"));
            assertTrue(handler.subscriptionsView().isEmpty(), "Failed subscribe should not retain handler state");
            assertTrue(handler.listeningChannelsView().isEmpty(), "Failed subscribe should not retain channel state");

            handler.stop().toCompletionStage().toCompletableFuture().join();
        } finally {
            vertx.close();
        }
    }

    @Test
    void subscribeShouldRejectPartialWildcardSegments() {
        Vertx vertx = Vertx.vertx();
        try {
            ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
                    vertx,
                    connectOptions,
                    new ObjectMapper(),
                    String.class,
                    eventId -> Future.succeededFuture(null));

            Throwable thrown = assertThrows(CompletionException.class,
                    () -> handler.subscribe("order*created", null, message -> CompletableFuture.completedFuture(null))
                            .toCompletionStage()
                            .toCompletableFuture()
                            .join());
            assertTrue(thrown.getCause() instanceof IllegalArgumentException);
            assertTrue(thrown.getCause().getMessage().contains("Invalid eventType"));
        } finally {
            vertx.close();
        }
    }

    private PgConnection connect(Vertx vertx) {
        return PgConnection.connect(vertx, connectOptions)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private static final class TestableReactiveNotificationHandler extends ReactiveNotificationHandler<String> {
        private final Supplier<Future<PgConnection>> connectBehavior;
        private final AtomicInteger connectCalls;
        private final List<String> listenedChannels;
        private final Function<String, Future<Void>> listenBehavior;
        private final Function<String, Future<Void>> unlistenBehavior;
        private final AtomicBoolean closeCalled;

        private TestableReactiveNotificationHandler(Vertx vertx,
                PgConnectOptions connectOptions,
                Function<String, Future<BiTemporalEvent<String>>> eventRetriever,
                Supplier<Future<PgConnection>> connectBehavior,
                AtomicInteger connectCalls,
                List<String> listenedChannels,
                Function<String, Future<Void>> listenBehavior,
                Function<String, Future<Void>> unlistenBehavior,
                AtomicBoolean closeCalled) {
            super(vertx, connectOptions, new ObjectMapper(), String.class, eventRetriever);
            this.connectBehavior = connectBehavior;
            this.connectCalls = connectCalls;
            this.listenedChannels = listenedChannels;
            this.listenBehavior = listenBehavior;
            this.unlistenBehavior = unlistenBehavior;
            this.closeCalled = closeCalled;
        }

        @Override
        Future<PgConnection> connectReactive() {
            connectCalls.incrementAndGet();
            return connectBehavior.get();
        }

        @Override
        Future<Void> listenOnChannel(PgConnection connection, String channel) {
            listenedChannels.add(channel);
            return listenBehavior.apply(channel);
        }

        @Override
        Future<Void> unlistenChannel(PgConnection connection, String channel) {
            return unlistenBehavior.apply(channel);
        }

        @Override
        Future<Void> closeListenConnection(PgConnection connection) {
            closeCalled.set(true);
            return super.closeListenConnection(connection);
        }
    }
}