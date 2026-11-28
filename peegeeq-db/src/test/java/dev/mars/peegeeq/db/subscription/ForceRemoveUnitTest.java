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

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link SubscriptionManager#forceRemoveConsumerGroup(String, String)}.
 *
 * <p>Validates the argument checks, which run before any database access. Integration tests
 * with a real database are in ForceRemoveIntegrationTest.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.CORE)
@DisplayName("SubscriptionManager force-remove unit tests")
@ExtendWith(VertxExtension.class)
class ForceRemoveUnitTest {

    private PgConnectionManager connectionManager;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp(Vertx vertx) {
        connectionManager = new PgConnectionManager(vertx);
        subscriptionManager = new SubscriptionManager(connectionManager, "test-svc");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        // VertxExtension owns the Vert.x instance and closes it.
        (connectionManager != null ? connectionManager.close() : Future.<Void>succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("forceRemove with null topic throws NullPointerException")
    void forceRemove_nullTopic_throws() {
        assertThrows(NullPointerException.class,
                () -> subscriptionManager.forceRemoveConsumerGroup(null, "group"));
    }

    @Test
    @DisplayName("forceRemove with null groupName throws NullPointerException")
    void forceRemove_nullGroupName_throws() {
        assertThrows(NullPointerException.class,
                () -> subscriptionManager.forceRemoveConsumerGroup("topic", null));
    }
}
