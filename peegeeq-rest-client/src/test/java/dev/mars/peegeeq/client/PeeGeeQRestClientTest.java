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

package dev.mars.peegeeq.client;

import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.MessageRequest;
import dev.mars.peegeeq.client.exception.PeeGeeQApiException;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PeeGeeQRestClient.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class PeeGeeQRestClientTest {

    private PeeGeeQClient client;

    @BeforeEach
    void setUp(Vertx vertx) {
        ClientConfig config = ClientConfig.builder()
            .baseUrl("http://localhost:8080")
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .build();
        client = PeeGeeQRestClient.create(vertx, config);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void create_withVertxAndConfig_createsClient(Vertx vertx) {
        // Given
        ClientConfig config = ClientConfig.defaults();

        // When
        PeeGeeQClient newClient = PeeGeeQRestClient.create(vertx, config);

        // Then
        assertNotNull(newClient);
        newClient.close();
    }

    @Test
    void create_withVertxOnly_createsClientWithDefaults(Vertx vertx) {
        // When
        PeeGeeQClient newClient = PeeGeeQRestClient.create(vertx);

        // Then
        assertNotNull(newClient);
        newClient.close();
    }

    @Test
    void create_withNullVertx_throwsNullPointerException() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            PeeGeeQRestClient.create(null, ClientConfig.defaults())
        );
    }

    @Test
    void create_withNullConfig_throwsNullPointerException(Vertx vertx) {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            PeeGeeQRestClient.create(vertx, null)
        );
    }

    @Test
    void close_canBeCalledMultipleTimes() {
        // When/Then - should not throw
        client.close();
        client.close();
    }

    // ========================================================================
    // DTO Tests
    // ========================================================================

    @Test
    void messageRequest_fluentBuilder_setsAllFields() {
        // When
        MessageRequest request = new MessageRequest()
            .withPayload(Map.of("key", "value"))
            .withHeader("X-Custom", "header-value")
            .withPriority(5)
            .withDelaySeconds(60L)
            .withMessageType("OrderCreated")
            .withCorrelationId("corr-123")
            .withMessageGroup("group-1");

        // Then
        assertNotNull(request.getPayload());
        assertEquals("header-value", request.getHeaders().get("X-Custom"));
        assertEquals(5, request.getPriority());
        assertEquals(60L, request.getDelaySeconds());
        assertEquals("OrderCreated", request.getMessageType());
        assertEquals("corr-123", request.getCorrelationId());
        assertEquals("group-1", request.getMessageGroup());
    }

    @Test
    void messageRequest_constructor_initializesEmptyHeaders() {
        // When
        MessageRequest request = new MessageRequest();

        // Then
        assertNotNull(request.getHeaders());
        assertTrue(request.getHeaders().isEmpty());
    }

    @Test
    void messageRequest_payloadConstructor_setsPayload() {
        // Given
        Object payload = Map.of("orderId", "12345");

        // When
        MessageRequest request = new MessageRequest(payload);

        // Then
        assertEquals(payload, request.getPayload());
    }

    @Test
    void queryEvents_encodesQueryParameters(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicReference<String> requestUri = new AtomicReference<>();

        HttpServer server = vertx.createHttpServer();
        server.requestHandler(req -> {
            requestUri.set(req.uri());
            req.response()
                .putHeader("content-type", "application/json")
                .end("{\"events\":[],\"total\":0,\"hasMore\":false}");
        }).listen(0).onSuccess(httpServer -> {
            PeeGeeQClient localClient = PeeGeeQRestClient.create(vertx, ClientConfig.builder()
                .baseUrl("http://localhost:" + httpServer.actualPort())
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build());

            EventQuery query = EventQuery.builder()
                .eventType("order created")
                .aggregateId("agg/1")
                .limit(10)
                .offset(5)
                .build();

            localClient.queryEvents("setup", "store", query)
                .onComplete(ar -> {
                    testContext.verify(() -> {
                        assertTrue(ar.succeeded(), "queryEvents should succeed");
                        String uri = requestUri.get();
                        assertNotNull(uri);
                        assertTrue(uri.contains("eventType=order+created"), uri);
                        assertTrue(uri.contains("aggregateId=agg%2F1"), uri);
                        assertTrue(uri.contains("limit=10"), uri);
                        assertTrue(uri.contains("offset=5"), uri);
                        assertFalse(uri.contains("Optional"), uri);
                    });
                    localClient.close();
                    httpServer.close().onComplete(done -> testContext.completeNow());
                });
        }).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void getGlobalHealth_retriesOnServerErrors(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        HttpServer server = vertx.createHttpServer();
        server.requestHandler(req -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                req.response()
                    .setStatusCode(503)
                    .putHeader("content-type", "application/json")
                    .end("{\"error\":\"TEMP\",\"message\":\"temporary\"}");
            } else {
                req.response()
                    .putHeader("content-type", "application/json")
                    .end("{\"status\":\"UP\"}");
            }
        }).listen(0).onSuccess(httpServer -> {
            PeeGeeQClient localClient = PeeGeeQRestClient.create(vertx, ClientConfig.builder()
                .baseUrl("http://localhost:" + httpServer.actualPort())
                .timeout(Duration.ofSeconds(5))
                .maxRetries(2)
                .retryDelay(Duration.ofMillis(20))
                .build());

            localClient.getGlobalHealth().onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "Call should succeed after retries");
                    assertEquals("UP", ar.result().getString("status"));
                    assertEquals(3, attempts.get(), "Expected initial attempt + 2 retries");
                });
                localClient.close();
                httpServer.close().onComplete(done -> testContext.completeNow());
            });
        }).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void getGlobalHealth_doesNotRetryOnClientErrors(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        HttpServer server = vertx.createHttpServer();
        server.requestHandler(req -> {
            attempts.incrementAndGet();
            req.response()
                .setStatusCode(400)
                .putHeader("content-type", "application/json")
                .end("{\"error\":\"BAD_REQUEST\",\"message\":\"invalid\"}");
        }).listen(0).onSuccess(httpServer -> {
            PeeGeeQClient localClient = PeeGeeQRestClient.create(vertx, ClientConfig.builder()
                .baseUrl("http://localhost:" + httpServer.actualPort())
                .timeout(Duration.ofSeconds(5))
                .maxRetries(3)
                .retryDelay(Duration.ofMillis(20))
                .build());

            localClient.getGlobalHealth().onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.failed(), "Call should fail");
                    assertInstanceOf(PeeGeeQApiException.class, ar.cause());
                    assertEquals(1, attempts.get(), "4xx responses should not be retried");
                });
                localClient.close();
                httpServer.close().onComplete(done -> testContext.completeNow());
            });
        }).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}

