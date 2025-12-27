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

import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.MessageRequest;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Map;

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
}

