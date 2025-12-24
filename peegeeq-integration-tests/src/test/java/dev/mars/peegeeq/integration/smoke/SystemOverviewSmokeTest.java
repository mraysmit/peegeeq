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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

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
@DisplayName("System Overview Smoke Tests")
class SystemOverviewSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("GET /api/v1/management/overview returns system overview")
    void testSystemOverview() throws Exception {
        HttpResponse<String> response = get("/api/v1/management/overview");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("systemStats");
        assertThat(response.body()).contains("timestamp");
        logSuccess("GET /api/v1/management/overview", response);
    }

    @Test
    @DisplayName("GET /api/v1/management/queues returns queue list")
    void testManagementQueues() throws Exception {
        HttpResponse<String> response = get("/api/v1/management/queues");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("queues");
        logSuccess("GET /api/v1/management/queues", response);
    }

    @Test
    @DisplayName("GET /api/v1/management/consumer-groups returns consumer group list")
    void testManagementConsumerGroups() throws Exception {
        HttpResponse<String> response = get("/api/v1/management/consumer-groups");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        logSuccess("GET /api/v1/management/consumer-groups", response);
    }

    @Test
    @DisplayName("GET /api/v1/management/event-stores returns event store list")
    void testManagementEventStores() throws Exception {
        HttpResponse<String> response = get("/api/v1/management/event-stores");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        logSuccess("GET /api/v1/management/event-stores", response);
    }

    @Test
    @DisplayName("GET /api/v1/management/messages returns recent messages")
    void testManagementMessages() throws Exception {
        HttpResponse<String> response = get("/api/v1/management/messages");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        logSuccess("GET /api/v1/management/messages", response);
    }
}

