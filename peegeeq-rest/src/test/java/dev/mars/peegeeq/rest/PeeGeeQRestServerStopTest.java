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

package dev.mars.peegeeq.rest;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for PeeGeeQRestServer verticle lifecycle.
 *
 * These are pure unit tests — no database, no Testcontainers.
 * They verify the shutdown contract: stop() must call setupService.close().
 */
@Tag("core")
@ExtendWith(VertxExtension.class)
class PeeGeeQRestServerStopTest {

    // Port outside the range used by other test classes
    private static final int TEST_PORT = 19095;

    /**
     * Builds a spy DatabaseSetupService that records whether close() was invoked.
     */
    private DatabaseSetupService spySetupService(AtomicBoolean closeCalled) {
        return new DatabaseSetupService() {
            @Override public Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest r) { return Future.succeededFuture(null); }
            @Override public Future<Void> destroySetup(String id) { return Future.succeededFuture(); }
            @Override public Future<DatabaseSetupStatus> getSetupStatus(String id) { return Future.succeededFuture(DatabaseSetupStatus.ACTIVE); }
            @Override public Future<DatabaseSetupResult> getSetupResult(String id) { return Future.succeededFuture(null); }
            @Override public Future<Void> addQueue(String id, QueueConfig q) { return Future.succeededFuture(); }
            @Override public Future<Void> addEventStore(String id, EventStoreConfig e) { return Future.succeededFuture(); }
            @Override public Future<Set<String>> getAllActiveSetupIds() { return Future.succeededFuture(Collections.emptySet()); }
            @Override public SubscriptionService getSubscriptionServiceForSetup(String id) { return null; }
            @Override public DeadLetterService getDeadLetterServiceForSetup(String id) { return null; }
            @Override public HealthService getHealthServiceForSetup(String id) { return null; }
            @Override public QueueFactoryProvider getQueueFactoryProviderForSetup(String id) { return null; }
            @Override public Future<Void> close() {
                closeCalled.set(true);
                return Future.succeededFuture();
            }
        };
    }

    @Test
    @DisplayName("stop() must call setupService.close() when verticle is undeployed")
    void stop_callsSetupServiceClose(Vertx vertx, VertxTestContext testContext) {
        AtomicBoolean closeCalled = new AtomicBoolean(false);
        DatabaseSetupService spy = spySetupService(closeCalled);

        RestServerConfig config = new RestServerConfig(
                TEST_PORT,
                RestServerConfig.MonitoringConfig.defaults(),
                List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, spy))
                .compose(deploymentId -> vertx.undeploy(deploymentId))
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(closeCalled.get(),
                            "PeeGeeQRestServer.stop() must call setupService.close() " +
                            "to cancel background timers (e.g. depth cache) on all active PeeGeeQManager instances");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
