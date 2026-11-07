package dev.mars.peegeeq.rest.handlers;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A queue stream connection stays healthy while its socket is open (finding 9).
 *
 * <p>Before the correction a connection that had sent no data frame for 300 seconds reported
 * itself unhealthy. Every later message was then dropped with a WARN line, and the socket stayed
 * open, so the client could not see that it no longer received anything. Only the server's own
 * data sends reset the clock, so a stream on a queue that was quiet for 5 minutes never delivered
 * again. The connection's health now depends on the socket and on its writes, not on time.</p>
 *
 * <p>Real Vert.x WebSocket server and client on a local port; no database. The connection's clock
 * is injected so the test does not wait 5 minutes.</p>
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 30, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class WebSocketConnectionIdleTest {

    private static final long ONE_HOUR_MS = 3_600_000L;

    private HttpServer server;
    private WebSocketClient client;

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> clientClose = client != null ? client.close() : Future.succeededFuture();
        clientClose
                .compose(v -> server != null ? server.close() : Future.<Void>succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void aConnectionThatSentNothingForAnHourStillDeliversTheNextMessage(Vertx vertx, VertxTestContext testContext) {
        AtomicLong now = new AtomicLong(1_000_000L);
        Promise<WebSocketConnection> serverSide = Promise.promise();
        Promise<JsonObject> received = Promise.promise();

        startServer(vertx, now, serverSide)
                .compose(port -> connectClient(vertx, port, received))
                .compose(clientSocket -> serverSide.future())
                .compose(connection -> {
                    now.addAndGet(ONE_HOUR_MS);
                    testContext.verify(() -> assertTrue(connection.isHealthy(),
                            "an open socket must stay healthy however long the queue was quiet"));
                    connection.sendDataMessage("after-an-hour", "m-1", null, "text");
                    return received.future();
                })
                .onSuccess(message -> testContext.verify(() -> {
                    assertEquals("data", message.getString("type"));
                    assertEquals("after-an-hour", message.getString("payload"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void aConnectionWhoseSocketClosedIsNotHealthy(Vertx vertx, VertxTestContext testContext) {
        AtomicLong now = new AtomicLong(1_000_000L);
        Promise<WebSocketConnection> serverSide = Promise.promise();
        Promise<Void> serverSaw = Promise.promise();

        startServer(vertx, now, serverSide)
                .compose(port -> connectClient(vertx, port, Promise.promise()))
                .compose(clientSocket -> serverSide.future().compose(connection -> {
                    connection.getWebSocket().closeHandler(v -> serverSaw.tryComplete());
                    return clientSocket.close().compose(v -> serverSaw.future()).map(connection);
                }))
                .onSuccess(connection -> testContext.verify(() -> {
                    assertFalse(connection.isHealthy(), "a closed socket is not healthy");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Integer> startServer(Vertx vertx, AtomicLong now, Promise<WebSocketConnection> serverSide) {
        return vertx.createHttpServer()
                .webSocketHandler(ws -> serverSide.tryComplete(
                        new WebSocketConnection("conn-1", ws, "setup-1", "queue-1", now::get)))
                .listen(0)
                .map(listening -> {
                    server = listening;
                    return listening.actualPort();
                });
    }

    private Future<WebSocket> connectClient(Vertx vertx, int port, Promise<JsonObject> received) {
        client = vertx.createWebSocketClient();
        return client.connect(port, "localhost", "/").map(socket -> {
            socket.textMessageHandler(text -> received.tryComplete(new JsonObject(text)));
            return socket;
        });
    }
}
