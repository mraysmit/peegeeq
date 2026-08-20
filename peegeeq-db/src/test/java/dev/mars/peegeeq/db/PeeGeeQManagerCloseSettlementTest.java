package dev.mars.peegeeq.db;

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

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression contracts for settlement of the manager-owned Vert.x close boundary.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
class PeeGeeQManagerCloseSettlementTest {

    @Test
    void closeReactiveSettlesWhenComposedFromManagerContext(VertxTestContext testContext) {
        PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("test", new Properties()),
                new SimpleMeterRegistry());

        manager.getVertx().executeBlocking(() -> null)
                .compose(ignored -> manager.closeReactive())
                .onSuccess(ignored -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void repeatedCloseCallsShareOneSettlementFuture(VertxTestContext testContext) {
        PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("test", new Properties()),
                new SimpleMeterRegistry());

        Future<Void> first = manager.closeReactive();
        Future<Void> second = manager.closeReactive();

        assertSame(first, second, "Concurrent callers must observe one shutdown operation");
        first
                .onSuccess(ignored -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
}
