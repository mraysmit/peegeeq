package dev.mars.peegeeq.db.subscription;

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

import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubscriptionManager#forceRemoveConsumerGroup(String, String)}.
 *
 * <p>Validates parameter validation and configuration checks. Integration tests
 * with real database are in ForceRemoveIntegrationTest.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.CORE)
@DisplayName("SubscriptionManager — force-remove unit tests")
class ForceRemoveUnitTest {

    private Vertx vertx;
    private PgConnectionManager connectionManager;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
        subscriptionManager = new SubscriptionManager(connectionManager, "test-svc");
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) connectionManager.close();
        if (vertx != null) vertx.close();
    }

    // =========================================================================
    // Parameter validation
    // =========================================================================

    @Test
    @DisplayName("forceRemove with null topic throws NullPointerException")
    void forceRemove_nullTopic_throws() {
        subscriptionManager.setDeadConsumerGroupCleanup(
                new DeadConsumerGroupCleanup(connectionManager, "test-svc"));
        assertThrows(NullPointerException.class,
                () -> subscriptionManager.forceRemoveConsumerGroup(null, "group"));
    }

    @Test
    @DisplayName("forceRemove with null groupName throws NullPointerException")
    void forceRemove_nullGroupName_throws() {
        subscriptionManager.setDeadConsumerGroupCleanup(
                new DeadConsumerGroupCleanup(connectionManager, "test-svc"));
        assertThrows(NullPointerException.class,
                () -> subscriptionManager.forceRemoveConsumerGroup("topic", null));
    }

    // =========================================================================
    // Configuration checks
    // =========================================================================

    @Test
    @DisplayName("forceRemove without cleanup service returns failed future")
    void forceRemove_withoutCleanupService_failsFuture() {
        // No setDeadConsumerGroupCleanup called
        var future = subscriptionManager.forceRemoveConsumerGroup("topic", "group");
        assertTrue(future.failed(), "Should fail when cleanup service not configured");
        assertInstanceOf(IllegalStateException.class, future.cause());
        assertTrue(future.cause().getMessage().contains("DeadConsumerGroupCleanup"),
                "Error should mention the missing dependency");
    }

    @Test
    @DisplayName("forceRemove with cleanup service configured does not fail on config check")
    void forceRemove_withCleanupService_passesConfigCheck() {
        subscriptionManager.setDeadConsumerGroupCleanup(
                new DeadConsumerGroupCleanup(connectionManager, "test-svc"));
        var future = subscriptionManager.forceRemoveConsumerGroup("topic", "group");
        // Will fail at DB level (no pool), but must NOT fail on config check
        if (future.failed()) {
            assertFalse(future.cause() instanceof IllegalStateException
                    && future.cause().getMessage().contains("DeadConsumerGroupCleanup"),
                    "Should not fail on config check when cleanup is configured");
        }
    }

    @Test
    @DisplayName("setDeadConsumerGroupCleanup with null throws NullPointerException")
    void setCleanup_null_throws() {
        assertThrows(NullPointerException.class,
                () -> subscriptionManager.setDeadConsumerGroupCleanup(null));
    }
}
