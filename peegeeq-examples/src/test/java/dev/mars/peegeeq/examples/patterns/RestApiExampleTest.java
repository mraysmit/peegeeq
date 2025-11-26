package dev.mars.peegeeq.examples.patterns;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * INFRASTRUCTURE TEST: PeeGeeQ REST API Server Integration (CURRENTLY DISABLED)
 *
 * ⚠️  NOTE: All tests are currently SKIPPED due to Vert.x 4.x → 5.x migration issues.
 * ⚠️  NOTE: This test does NOT create or test any message queues directly.
 *
 * WHAT THIS WOULD TEST (when enabled):
 * - REST API server startup and configuration
 * - HTTP endpoints for queue management operations
 * - REST API integration with PeeGeeQ core functionality
 * - API health checks and metrics endpoints
 *
 * BUSINESS VALUE:
 * - Validates REST API integration works correctly
 * - Ensures HTTP interface to PeeGeeQ functionality
 * - Provides confidence in web service integration patterns
 *
 * CURRENT STATUS: Requires Vert.x 5.x migration before tests can be enabled
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class RestApiExampleTest {

    @Test
    void testDatabaseSetupManagement() {
        Assumptions.assumeTrue(false,
            "REST API tests are currently skipped due to Vert.x 4.x → 5.x migration issues. " +
            "The PeeGeeQRestServer needs to be updated to use Vert.x 5.x patterns. " +
            "This includes updating HttpServer.listen() method calls and other API changes. " +
            "Once the REST server is migrated to Vert.x 5.x, these tests can be re-enabled.");
    }

    @Test
    void testQueueOperations() {
        Assumptions.assumeTrue(false,
            "REST API tests are currently skipped due to Vert.x 4.x → 5.x migration issues. " +
            "The PeeGeeQRestServer needs to be updated to use Vert.x 5.x patterns. " +
            "This includes updating HttpServer.listen() method calls and other API changes. " +
            "Once the REST server is migrated to Vert.x 5.x, these tests can be re-enabled.");
    }

    @Test
    void testEventStoreOperations() {
        Assumptions.assumeTrue(false,
            "REST API tests are currently skipped due to Vert.x 4.x → 5.x migration issues. " +
            "The PeeGeeQRestServer needs to be updated to use Vert.x 5.x patterns. " +
            "This includes updating HttpServer.listen() method calls and other API changes. " +
            "Once the REST server is migrated to Vert.x 5.x, these tests can be re-enabled.");
    }

    @Test
    void testHealthAndMetrics() {
        Assumptions.assumeTrue(false,
            "REST API tests are currently skipped due to Vert.x 4.x → 5.x migration issues. " +
            "The PeeGeeQRestServer needs to be updated to use Vert.x 5.x patterns. " +
            "This includes updating HttpServer.listen() method calls and other API changes. " +
            "Once the REST server is migrated to Vert.x 5.x, these tests can be re-enabled.");
    }

    @Test
    void testConsumerGroupManagement() {
        Assumptions.assumeTrue(false,
            "REST API tests are currently skipped due to Vert.x 4.x → 5.x migration issues. " +
            "The PeeGeeQRestServer needs to be updated to use Vert.x 5.x patterns. " +
            "This includes updating HttpServer.listen() method calls and other API changes. " +
            "Once the REST server is migrated to Vert.x 5.x, these tests can be re-enabled.");
    }
}
