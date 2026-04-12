package dev.mars.peegeeq.pgqueue;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE lifecycle tests for {@link PartitionedConsumerEngine}.
 *
 * <p>These tests verify the engine's state machine transitions that do NOT
 * require a database (stop/close when not running, start-after-close rejection,
 * initial state). They close the coverage gap identified in the code review:
 * the engine had 0% CORE coverage because all previous tests were integration-only.</p>
 *
 * <p>Tests that exercise the engine's fetch loop, partition assignment, and
 * offset commit require a real database and live in
 * {@code PartitionedConsumerSafetyIntegrationTest}.</p>
 */
@Tag(TestCategories.CORE)
@DisplayName("PartitionedConsumerEngine — lifecycle (no DB)")
class PartitionedConsumerEngineLifecycleTest {

    private Vertx vertx;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) {
            try { connectionManager.close(); } catch (Exception ignored) {}
        }
        if (vertx != null) {
            try { vertx.close(); } catch (Exception ignored) {}
        }
    }

    private PartitionedConsumerEngine<String> createEngine(String topic, String group, String instance) {
        return new PartitionedConsumerEngine<>(
                vertx, connectionManager, "test-svc",
                topic, group, instance,
                String.class, new ObjectMapper()
        );
    }

    @Test
    @DisplayName("new engine is not running")
    void newEngine_isNotRunning() {
        var engine = createEngine("t", "g", "i");
        assertFalse(engine.isRunning());
    }

    @Test
    @DisplayName("assigned partitions empty before start")
    void assignedPartitions_emptyBeforeStart() {
        var engine = createEngine("t", "g", "i");
        assertTrue(engine.getAssignedPartitions().isEmpty());
    }

    @Test
    @DisplayName("stop() when not running is a no-op")
    void stopWhenNotRunning_succeeds() {
        var engine = createEngine("t", "g", "i");
        Future<Void> f = engine.stop();
        assertTrue(f.succeeded(), "stop() should succeed immediately when not running");
        assertFalse(engine.isRunning());
    }

    @Test
    @DisplayName("close() when not running succeeds")
    void closeWhenNotRunning_succeeds() {
        var engine = createEngine("t", "g", "i");
        Future<Void> f = engine.close();
        assertTrue(f.succeeded(), "close() should succeed when not running");
        assertFalse(engine.isRunning());
    }

    @Test
    @DisplayName("start() after close() is rejected with IllegalStateException")
    void startAfterClose_rejected() {
        var engine = createEngine("t", "g", "i");
        Future<Void> closeResult = engine.close();
        assertTrue(closeResult.succeeded(), "close() should succeed");

        Future<Void> startResult = engine.start(msg -> Future.succeededFuture());
        assertTrue(startResult.failed(), "start() after close() should fail");
        assertInstanceOf(IllegalStateException.class, startResult.cause());
        assertTrue(startResult.cause().getMessage().contains("closed"),
                "Error should mention 'closed': " + startResult.cause().getMessage());
    }

    @Test
    @DisplayName("start() rejects null handler")
    void startRejectsNullHandler() {
        var engine = createEngine("t", "g", "i");
        assertThrows(NullPointerException.class, () -> engine.start(null));
    }

    @Test
    @DisplayName("stop() is idempotent when called multiple times")
    void stopIsIdempotent() {
        var engine = createEngine("t", "g", "i");
        Future<Void> f1 = engine.stop();
        Future<Void> f2 = engine.stop();
        assertTrue(f1.succeeded());
        assertTrue(f2.succeeded());
        assertFalse(engine.isRunning());
    }

    @Test
    @DisplayName("close() is idempotent when called multiple times")
    void closeIsIdempotent() {
        var engine = createEngine("t", "g", "i");
        Future<Void> f1 = engine.close();
        Future<Void> f2 = engine.close();
        assertTrue(f1.succeeded());
        assertTrue(f2.succeeded());
        assertFalse(engine.isRunning());
    }

    @Test
    @DisplayName("close() then stop() still succeeds")
    void closeThenStop_succeeds() {
        var engine = createEngine("t", "g", "i");
        Future<Void> closeResult = engine.close();
        assertTrue(closeResult.succeeded());

        Future<Void> stopResult = engine.stop();
        assertTrue(stopResult.succeeded(), "stop() after close() should be a no-op");
    }
}
