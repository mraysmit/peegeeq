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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@ExtendWith(VertxExtension.class)
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
    void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        objectMapper = new ObjectMapper();
        stream = new PgNotificationStream<>(vertx, JsonObject.class, objectMapper);

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        PgConnection.connect(vertx, connectOptions)
                .onSuccess(conn -> {
                    pgConnection = conn;
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        // Vertx lifecycle is managed by VertxExtension
        if (pgConnection != null) {
            pgConnection.close().onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testHandleNotification(VertxTestContext testContext) {
        JsonObject expectedPayload = new JsonObject().put("test", "value");

        stream.handler(payload -> testContext.verify(() -> {
            assertEquals(expectedPayload, payload);
            testContext.completeNow();
        }));

        stream.handleNotification(expectedPayload);
    }

    @Test
    void testPauseAndResume(VertxTestContext testContext) {
        JsonObject payload = new JsonObject().put("test", "value");
        AtomicReference<JsonObject> receivedPayload = new AtomicReference<>();

        stream.handler(p -> {
            receivedPayload.set(p);
            testContext.verify(() -> assertEquals(payload, p));
            testContext.completeNow();
        });

        // Pause the stream and send a notification
        stream.pause();
        stream.handleNotification(payload);

        // After 1 second, verify nothing was received while paused, then resume
        vertx.setTimer(1000, id -> {
            testContext.verify(() -> assertNull(receivedPayload.get()));
            stream.resume();
            stream.handleNotification(payload);
        });
    }

    @Test
    void testHandleError(VertxTestContext testContext) {
        Exception expectedException = new RuntimeException("Test error");

        stream.exceptionHandler(error -> testContext.verify(() -> {
            assertSame(expectedException, error);
            testContext.completeNow();
        }));

        stream.handleError(expectedException);
    }

    @Test
    void testHandleEnd(VertxTestContext testContext) {
        stream.endHandler(v -> testContext.completeNow());
        stream.handleEnd();
    }

    @Test
    void testRealPostgresNotification(VertxTestContext testContext) {
        String channelName = "test_channel";
        JsonObject expectedPayload = new JsonObject().put("test", "value");
        String notificationPayload = expectedPayload.encode();

        stream.handler(payload -> testContext.verify(() -> {
            assertNotNull(payload);
            assertEquals(expectedPayload.getString("test"), payload.getString("test"));
            testContext.completeNow();
        }));

        pgConnection.notificationHandler(notification -> {
            if (channelName.equals(notification.getChannel())) {
                try {
                    JsonObject payload = new JsonObject(notification.getPayload());
                    stream.handleNotification(payload);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            }
        });

        pgConnection.query("LISTEN " + channelName)
                .execute()
                .compose(v -> pgConnection.query("NOTIFY " + channelName + ", '" + notificationPayload + "'").execute())
                .onFailure(testContext::failNow);
    }
}