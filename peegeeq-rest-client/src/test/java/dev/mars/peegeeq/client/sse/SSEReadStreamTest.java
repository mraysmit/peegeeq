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

package dev.mars.peegeeq.client.sse;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class SSEReadStreamTest {

    @Test
    void parsesMultilineDataWithCrlfFrames(VertxTestContext testContext, io.vertx.core.Vertx vertx) throws Exception {
        HttpServer server = vertx.createHttpServer();
        AtomicInteger closeCalls = new AtomicInteger();
        List<JsonObject> received = new ArrayList<>();

        server.requestHandler(req -> req.response()
            .setChunked(true)
            .putHeader("content-type", "text/event-stream")
            .end("event: update\r\ndata: {\"id\":1,\r\ndata: \"value\":\"ok\"}\r\n\r\n"))
            .listen(0)
            .onSuccess(httpServer -> {
                HttpClient client = vertx.createHttpClient(new HttpClientOptions());
                SSEReadStream<JsonObject> stream = new SSEReadStream<>(
                    client.request(HttpMethod.GET, httpServer.actualPort(), "localhost", "/"),
                    json -> json,
                    () -> {
                        closeCalls.incrementAndGet();
                        client.close();
                    }
                );

                stream.handler(received::add)
                    .exceptionHandler(testContext::failNow)
                    .endHandler(v -> {
                        vertx.setTimer(25, timerId -> {
                            testContext.verify(() -> {
                                assertEquals(1, received.size());
                                assertEquals(1, received.get(0).getInteger("id"));
                                assertEquals("ok", received.get(0).getString("value"));
                                assertEquals(1, closeCalls.get(), "Close hook should be invoked once");
                            });
                            httpServer.close().onComplete(done -> testContext.completeNow());
                        });
                    });

                stream.start();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}
