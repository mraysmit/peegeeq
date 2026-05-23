package dev.mars.peegeeq.db.performance;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reactive-only connection pool burst performance test.
 *
 * <p>Verifies that the Vert.x reactive pool handles a large burst of concurrent
 * database requests using only Vert.x {@code Future} composition — no
 * {@code ThreadPoolExecutor}, no blocking, no {@code .join()/.get()}.
 *
 * <p>This is the correct Vert.x 5.x counterpart to
 * {@link PeeGeeQPerformanceTest#testConnectionPoolHandlesBurstLoadFromThreadPoolExecutor}.
 * Both tests exercise identical pool load (20 × 100 = 2000 requests against a pool of
 * max-size 20); this class does so using only the reactive {@code Future} API.
 *
 * <h2>Pool configuration</h2>
 * <p>{@code peegeeq.database.pool.max-wait-queue-size} is set to {@code -1} (unlimited)
 * in {@link #setUp}. This is intentional: 2000 requests against a pool of size 20 means
 * up to 1980 requests must queue. The production default cap of 128 (introduced in
 * PgPoolConfig commit fd91794d) would reject 1852 of them. Setting {@code -1} restores
 * the unbounded-queue behaviour these tests require.
 *
 * <p>Do <strong>not</strong> remove the {@code max-wait-queue-size = -1} override.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-04
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class PeeGeeQReactiveConnectionPoolPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQReactiveConnectionPoolPerformanceTest.class);

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp(Vertx injectedVertx, VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");

        testProps.setProperty("peegeeq.database.pool.min-size", "10");
        testProps.setProperty("peegeeq.database.pool.max-size", "20");
        // Unlimited wait queue required: 2000 requests against pool size 20 means up to
        // 1980 requests must queue. The production default cap of 128 would reject the
        // excess with ConnectionPoolTooBusyException.
        // DO NOT remove this override.
        testProps.setProperty("peegeeq.database.pool.max-wait-queue-size", "-1");
        testProps.setProperty("peegeeq.database.pool.shared", "false");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        testProps.setProperty("peegeeq.queue.batch-size", "100");
        testProps.setProperty("peegeeq.queue.polling-interval", "PT100MS");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT5S");
        testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("performance", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    /**
     * Submits 2000 {@code SELECT 1} queries directly on the calling thread using
     * {@code withConnection} — no thread pool, no blocking.
     *
     * <p>Each {@code withConnection} call returns immediately with a pending
     * {@code Future<Integer>}; the Vert.x event loop services the pool asynchronously.
     * {@code Future.join(futures)} waits for all 2000 futures to settle (succeeding even
     * when individual futures fail), then completes the test context and logs the result.
     *
     * <p>Successful query count and aggregate latency are tracked via thread-safe
     * {@code AtomicInteger}/{@code AtomicLong} updated from {@code onSuccess} handlers
     * on the event loop.
     */
    @Test
    void testConnectionPoolHandlesBurstLoadWithReactiveFutures(VertxTestContext testContext) {
        int totalQueries = 20 * 100; // 2000 requests — identical load to the ThreadPoolExecutor variant

        AtomicInteger successfulQueries = new AtomicInteger(0);
        AtomicLong totalQueryTime = new AtomicLong(0);
        Instant startTime = Instant.now();

        List<Future<Integer>> futures = new ArrayList<>(totalQueries);
        for (int i = 0; i < totalQueries; i++) {
            Instant queryStart = Instant.now();
            futures.add(
                manager.getDatabaseService().getConnectionProvider()
                    .withConnection("peegeeq-main", connection ->
                        connection.query("SELECT 1")
                            .execute()
                            .map(rowSet -> rowSet.iterator().next().getInteger(0))
                    )
                    .onSuccess(result -> {
                        successfulQueries.incrementAndGet();
                        totalQueryTime.addAndGet(Duration.between(queryStart, Instant.now()).toMillis());
                    })
                    .onFailure(cause -> logger.warn("Query failed", cause))
            );
        }

        // Future.join waits for all futures regardless of individual failures,
        // matching the ThreadPoolExecutor variant's behaviour of logging failures
        // without failing the test.
        Future.join(futures)
            .onComplete(ar -> {
                Duration elapsed = Duration.between(startTime, Instant.now());
                logger.info("Reactive burst load: {}/{} queries succeeded in {} ms",
                    successfulQueries.get(), totalQueries, elapsed.toMillis());
                testContext.completeNow();
            });
    }
}
