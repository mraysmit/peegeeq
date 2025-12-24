package dev.mars.peegeeq.db.connection;

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

import dev.mars.peegeeq.api.config.NoticeHandlerConfig;
import dev.mars.peegeeq.api.config.PgConnectionConfig;
import dev.mars.peegeeq.api.config.PgPoolConfig;
import dev.mars.peegeeq.api.metrics.NoticeMetrics;
import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.metrics.MicrometerNoticeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies authentic PostgreSQL warnings and errors
 * are properly handled and propagated through the notice handler.
 *
 * <p>This test creates real PostgreSQL scenarios that generate warnings:
 * <ul>
 *   <li>RAISE WARNING statements</li>
 *   <li>RAISE NOTICE statements</li>
 *   <li>RAISE INFO with PeeGeeQ codes</li>
 *   <li>Actual PostgreSQL warnings (e.g., implicit type conversions)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */
@ExtendWith(SharedPostgresExtension.class)
class PostgresNoticeHandlerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PostgresNoticeHandlerIntegrationTest.class);

    private Vertx vertx;
    private PgConnectionManager connectionManager;
    private SimpleMeterRegistry meterRegistry;
    private NoticeMetrics noticeMetrics;
    private Pool pool;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        meterRegistry = new SimpleMeterRegistry();
        noticeMetrics = new MicrometerNoticeMetrics(meterRegistry);

        NoticeHandlerConfig noticeConfig = new NoticeHandlerConfig.Builder()
            .enabled(true)
            .peeGeeQInfoLoggingEnabled(true)
            .postgresWarningLoggingEnabled(true)
            .otherNoticeLoggingEnabled(true)
            .metricsEnabled(true)
            .build();

        connectionManager = new PgConnectionManager(vertx, meterRegistry, noticeConfig, noticeMetrics);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pool != null) {
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (connectionManager != null) {
            connectionManager.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private Pool createPool() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        return connectionManager.getOrCreateReactivePool("test-notice-handler", connectionConfig, poolConfig);
    }

    @Test
    @DisplayName("Should handle RAISE INFO with PeeGeeQ code")
    void testRaiseInfoWithPeeGeeQCode() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        // Execute SQL that raises INFO with PeeGeeQ code
        pool.query("DO $$ BEGIN RAISE INFO 'Test PeeGeeQ info message' USING DETAIL = 'PGQINF0001'; END $$")
            .execute()
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute RAISE INFO", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Query should complete");
        assertTrue(success.get(), "Query should succeed");

        // Verify metrics were incremented
        double peeGeeQInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        assertTrue(peeGeeQInfoCount > 0, "PeeGeeQ info counter should be incremented");
    }

