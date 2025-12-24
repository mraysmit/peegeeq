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
package dev.mars.peegeeq.integration.smoke;

import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for System Overview and Management Dashboard API endpoints.
 *
 * Tests cover:
 * - GET /api/v1/management/overview - System overview dashboard
 * - GET /api/v1/management/queues - Queue list for management
 * - GET /api/v1/management/consumer-groups - Consumer group list
 * - GET /api/v1/management/event-stores - Event store list
 * - GET /api/v1/management/messages - Recent messages
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("System Overview Smoke Tests")
class SystemOverviewSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("GET /api/v1/management/overview returns system overview")
    void testSystemOverview(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/overview")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertTrue(response.bodyAsString().contains("systemStats"));
                assertTrue(response.bodyAsString().contains("timestamp"));
                logger.info("GET /api/v1/management/overview returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/queues returns queue list")
    void testManagementQueues(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/queues")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertTrue(response.bodyAsString().contains("queues"));
                logger.info("GET /api/v1/management/queues returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/consumer-groups returns consumer group list")
    void testManagementConsumerGroups(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/consumer-groups")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/consumer-groups returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/event-stores returns event store list")
    void testManagementEventStores(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/event-stores")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/event-stores returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/messages returns recent messages")
    void testManagementMessages(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/messages")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/messages returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }
}

