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


import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the PgNotificationStream class using TestContainers.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Integration tests for the PgNotificationStream class using TestContainers.
 */
@Testcontainers
public class PgNotificationStreamTestContainers {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private Vertx vertx;
    private ObjectMapper objectMapper;
    private PgNotificationStream<JsonObject> stream;
    private PgConnection pgConnection;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        objectMapper = new ObjectMapper();
        stream = new PgNotificationStream<>(vertx, JsonObject.class, objectMapper);

        // Create connection options from TestContainer
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        // Connect to PostgreSQL
        CountDownLatch connectionLatch = new CountDownLatch(1);
        AtomicReference<PgConnection> connectionRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        PgConnection.connect(vertx, connectOptions)
                .onSuccess(conn -> {
                    connectionRef.set(conn);
                    connectionLatch.countDown();
                })
                .onFailure(err -> {
                    errorRef.set(err);
                    connectionLatch.countDown();
                });

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "Connection timeout");
        if (errorRef.get() != null) {
            throw new RuntimeException("Failed to connect to PostgreSQL", errorRef.get());
        }

        pgConnection = connectionRef.get();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close connection first, then Vert.x, with independent latches to avoid races where
        // executor termination prevents completion handlers from running.
        boolean connClosed = true;
        boolean vertxClosed = true;

        if (pgConnection != null) {
            CountDownLatch connLatch = new CountDownLatch(1);
            pgConnection.close().onComplete(ar -> connLatch.countDown());
            connClosed = connLatch.await(5, TimeUnit.SECONDS);
        }

        if (vertx != null) {
            CountDownLatch vertxLatch = new CountDownLatch(1);
            vertx.close().onComplete(ar -> vertxLatch.countDown());
            vertxClosed = vertxLatch.await(5, TimeUnit.SECONDS);
        }

        assertTrue(connClosed && vertxClosed, "Failed to close resources");
    }

    @Test
    void testHandleNotification() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject expectedPayload = new JsonObject().put("test", "value");
        AtomicReference<JsonObject> receivedPayload = new AtomicReference<>();

        // Set up handler
        stream.handler(payload -> {
            receivedPayload.set(payload);
            latch.countDown();
        });

        // Act - Simulate a notification
        stream.handleNotification(expectedPayload);

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Notification timeout");
        assertEquals(expectedPayload, receivedPayload.get());
    }

    @Test
    void testPauseAndResume() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject payload = new JsonObject().put("test", "value");
        AtomicReference<JsonObject> receivedPayload = new AtomicReference<>();

        // Set up handler
        stream.handler(p -> {
            receivedPayload.set(p);
            latch.countDown();
        });

        // Act - Pause the stream and send a notification
        stream.pause();
        stream.handleNotification(payload);

        // Assert - The notification should not be received
        assertFalse(latch.await(1, TimeUnit.SECONDS), "Notification was received while paused");
        assertNull(receivedPayload.get());

        // Act - Resume the stream and send a notification
        stream.resume();
        stream.handleNotification(payload);

        // Assert - The notification should be received
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Notification timeout after resume");
        assertEquals(payload, receivedPayload.get());
    }

    @Test
    void testHandleError() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        Exception expectedException = new RuntimeException("Test error");
        AtomicReference<Throwable> receivedException = new AtomicReference<>();

        // Set up handler
        stream.exceptionHandler(error -> {
            receivedException.set(error);
            latch.countDown();
        });

        // Act
        stream.handleError(expectedException);

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Error timeout");
        assertSame(expectedException, receivedException.get());
    }

    @Test
    void testHandleEnd() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);

        // Set up handler
        stream.endHandler(v -> latch.countDown());

        // Act
        stream.handleEnd();

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "End timeout");
    }

    @Test
    void testRealPostgresNotification() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        String channelName = "test_channel";
        JsonObject expectedPayload = new JsonObject().put("test", "value");
        String notificationPayload = expectedPayload.encode();
        AtomicReference<JsonObject> receivedPayload = new AtomicReference<>();

        // Set up handler
        stream.handler(payload -> {
            receivedPayload.set(payload);
            latch.countDown();
        });

        // Set up notification handler on the connection
        pgConnection.notificationHandler(notification -> {
            if (channelName.equals(notification.getChannel())) {
                try {
                    JsonObject payload = new JsonObject(notification.getPayload());
                    stream.handleNotification(payload);
                } catch (Exception e) {
                    stream.handleError(e);
                }
            }
        });

        // Listen on the channel
        CountDownLatch listenLatch = new CountDownLatch(1);
        pgConnection.query("LISTEN " + channelName)
                .execute()
                .onSuccess(v -> listenLatch.countDown())
                .onFailure(err -> {
                    System.err.println("Failed to LISTEN: " + err.getMessage());
                    listenLatch.countDown();
                });

        assertTrue(listenLatch.await(5, TimeUnit.SECONDS), "LISTEN timeout");

        // Act - Send a notification
        CountDownLatch notifyLatch = new CountDownLatch(1);
        pgConnection.query("NOTIFY " + channelName + ", '" + notificationPayload + "'")
                .execute()
                .onSuccess(v -> notifyLatch.countDown())
                .onFailure(err -> {
                    System.err.println("Failed to NOTIFY: " + err.getMessage());
                    notifyLatch.countDown();
                });

        assertTrue(notifyLatch.await(5, TimeUnit.SECONDS), "NOTIFY timeout");

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Notification timeout");
        assertNotNull(receivedPayload.get());
        assertEquals(expectedPayload.getString("test"), receivedPayload.get().getString("test"));
    }
}