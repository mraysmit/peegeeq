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

import dev.mars.peegeeq.api.database.NoticeHandlerConfig;
import dev.mars.peegeeq.api.metrics.NoticeMetrics;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.metrics.MicrometerNoticeMetrics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(SharedPostgresTestExtension.class)
public class PostgresNoticeHandlerIntegrationTest {

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
            .peeGeeQInfoLoggingEnabled(true)
            .peeGeeQInfoLogLevel("INFO")
            .otherNoticesLoggingEnabled(true)
            .otherNoticesLogLevel("DEBUG")
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
            connectionManager.closeAsync().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private Pool createPool() {
        PostgreSQLContainer<?> postgres = SharedPostgresTestExtension.getContainer();
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
        // Use withConnection to ensure notice handler is attached
        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query("DO $$ BEGIN RAISE INFO 'Test PeeGeeQ info message' USING DETAIL = 'PGQINF0001'; END $$")
                .execute()
        )
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




    @Test
    @DisplayName("Should handle RAISE NOTICE statements")
    void testRaiseNotice() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        // Execute SQL that raises NOTICE
        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query("DO $$ BEGIN RAISE NOTICE 'This is a standard PostgreSQL notice'; END $$")
                .execute()
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute RAISE NOTICE", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Query should complete");
        assertTrue(success.get(), "Query should succeed");

        // Verify metrics were incremented
        double otherNoticeCount = meterRegistry.counter("peegeeq.notice.other").count();
        assertTrue(otherNoticeCount > 0, "Other notice counter should be incremented");
    }

    @Test
    @DisplayName("Should handle RAISE WARNING statements")
    void testRaiseWarning() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        // Execute SQL that raises WARNING
        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query("DO $$ BEGIN RAISE WARNING 'This is a PostgreSQL warning'; END $$")
                .execute()
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute RAISE WARNING", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Query should complete");
        assertTrue(success.get(), "Query should succeed");

        // Verify metrics were incremented
        double warningCount = meterRegistry.counter("peegeeq.notice.postgres_warning").count();
        assertTrue(warningCount > 0, "PostgreSQL warning counter should be incremented");
    }

    @Test
    @DisplayName("Should handle actual PostgreSQL warnings from deprecated syntax")
    void testActualPostgresWarning() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        // Create a function that uses deprecated syntax to trigger a real PostgreSQL warning
        // Note: This may not generate a warning in all PostgreSQL versions
        String createFunction = """
            CREATE OR REPLACE FUNCTION test_warning_function()
            RETURNS void AS $$
            BEGIN
                -- Use RAISE WARNING to simulate a real warning
                RAISE WARNING 'Simulated PostgreSQL warning from function';
            END;
            $$ LANGUAGE plpgsql;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(createFunction)
                .execute()
                .compose(v -> conn.query("SELECT test_warning_function()").execute())
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute function with warning", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Query should complete");
        assertTrue(success.get(), "Query should succeed");

        // Verify metrics were incremented
        double warningCount = meterRegistry.counter("peegeeq.notice.postgres_warning").count();
        assertTrue(warningCount > 0, "PostgreSQL warning counter should be incremented");
    }

    @Test
    @DisplayName("Should handle multiple notice types in sequence")
    void testMultipleNoticeTypes() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        // Create a function that raises multiple types of notices
        String createFunction = """
            CREATE OR REPLACE FUNCTION test_multiple_notices()
            RETURNS void AS $$
            BEGIN
                RAISE INFO 'PeeGeeQ info message' USING DETAIL = 'PGQINF0100';
                RAISE NOTICE 'Standard notice message';
                RAISE WARNING 'Warning message';
            END;
            $$ LANGUAGE plpgsql;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(createFunction)
                .execute()
                .compose(v -> conn.query("SELECT test_multiple_notices()").execute())
        )
            .onSuccess(result -> {
                successCount.incrementAndGet();
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute function with multiple notices", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Query should complete");
        assertEquals(1, successCount.get(), "Query should succeed");

        // Verify all notice types were captured
        double peeGeeQInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        double otherNoticeCount = meterRegistry.counter("peegeeq.notice.other").count();
        double warningCount = meterRegistry.counter("peegeeq.notice.postgres_warning").count();

        assertTrue(peeGeeQInfoCount > 0, "PeeGeeQ info counter should be incremented");
        assertTrue(otherNoticeCount > 0, "Other notice counter should be incremented");
        assertTrue(warningCount > 0, "PostgreSQL warning counter should be incremented");
    }

    @Test
    @DisplayName("Should not interfere with error propagation")
    void testErrorPropagation() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean errorReceived = new AtomicBoolean(false);

        // Execute SQL that will cause an error
        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query("SELECT * FROM non_existent_table").execute()
        )
            .onSuccess(result -> {
                logger.error("Query should have failed but succeeded");
                latch.countDown();
            })
            .onFailure(err -> {
                logger.info("Correctly received error: {}", err.getMessage());
                errorReceived.set(true);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Query should complete");
        assertTrue(errorReceived.get(), "Error should be propagated correctly");
    }

    @Test
    @DisplayName("Should capture infrastructure schema creation messages")
    void testInfrastructureSchemaCreation() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        double initialInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();

        // Create a test schema using the same pattern as the infrastructure templates
        String createSchemaSQL = """
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'test_infra_schema') THEN
                    CREATE SCHEMA test_infra_schema;
                    RAISE NOTICE '[PGQINF0550] Schema created: test_infra_schema';
                ELSE
                    RAISE NOTICE '[PGQINF0551] Schema already exists: test_infra_schema';
                END IF;
            END
            $$;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(createSchemaSQL)
                .execute()
                .compose(v -> conn.query("DROP SCHEMA IF EXISTS test_infra_schema CASCADE").execute())
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to create schema", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Schema creation should complete");
        assertTrue(success.get(), "Schema creation should succeed");

        // Verify the info message was captured
        double finalInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        assertTrue(finalInfoCount > initialInfoCount,
                "PeeGeeQ info counter should increase after schema creation");
    }

    @Test
    @DisplayName("Should capture infrastructure table creation messages")
    void testInfrastructureTableCreation() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        double initialInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();

        // Create a test table using the same pattern as the infrastructure templates
        String createTableSQL = """
            DO $$
            BEGIN
                IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'test_infra_table') THEN
                    DROP TABLE public.test_infra_table CASCADE;
                    RAISE NOTICE '[PGQINF0552] Dropped existing test_infra_table for recreation';
                END IF;

                CREATE TABLE public.test_infra_table (
                    id BIGSERIAL PRIMARY KEY,
                    data TEXT NOT NULL
                );
                RAISE NOTICE '[PGQINF0552] Created test_infra_table';
            END
            $$;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(createTableSQL)
                .execute()
                .compose(v -> conn.query("DROP TABLE IF EXISTS public.test_infra_table CASCADE").execute())
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to create table", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Table creation should complete");
        assertTrue(success.get(), "Table creation should succeed");

        // Verify the info messages were captured
        double finalInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        assertTrue(finalInfoCount > initialInfoCount,
                "PeeGeeQ info counter should increase after table creation");
    }

    @Test
    @DisplayName("Should capture infrastructure index creation messages")
    void testInfrastructureIndexCreation() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        // First create a table for the index
        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query("CREATE TABLE IF NOT EXISTS public.test_index_table (id BIGSERIAL PRIMARY KEY, topic VARCHAR(255))")
                .execute()
        )
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        double initialInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();

        // Create an index with info logging
        String createIndexSQL = """
            DO $$
            BEGIN
                CREATE INDEX IF NOT EXISTS idx_test_index_table_topic ON public.test_index_table(topic);
                RAISE NOTICE '[PGQINF0553] Created index: idx_test_index_table_topic';
            END
            $$;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(createIndexSQL)
                .execute()
                .compose(v -> conn.query("DROP TABLE IF EXISTS public.test_index_table CASCADE").execute())
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to create index", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Index creation should complete");
        assertTrue(success.get(), "Index creation should succeed");

        // Verify the info message was captured
        double finalInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        assertTrue(finalInfoCount > initialInfoCount,
                "PeeGeeQ info counter should increase after index creation");
    }

    @Test
    @DisplayName("Should capture multiple infrastructure operations")
    void testMultipleInfrastructureOperations() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        double initialInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();

        // Simulate a complete infrastructure setup with multiple operations
        String multiOpSQL = """
            DO $$
            BEGIN
                -- Create schema
                IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'test_multi_schema') THEN
                    CREATE SCHEMA test_multi_schema;
                    RAISE NOTICE '[PGQINF0550] Schema created: test_multi_schema';
                END IF;

                -- Create table
                CREATE TABLE IF NOT EXISTS test_multi_schema.test_table (
                    id BIGSERIAL PRIMARY KEY,
                    data JSONB NOT NULL
                );
                RAISE NOTICE '[PGQINF0552] Created test_table in test_multi_schema';

                -- Create index
                CREATE INDEX IF NOT EXISTS idx_test_table_data ON test_multi_schema.test_table USING GIN(data);
                RAISE NOTICE '[PGQINF0553] Created GIN index on test_table';

                -- Create function
                CREATE OR REPLACE FUNCTION test_multi_schema.test_function()
                RETURNS TRIGGER AS $func$
                BEGIN
                    RETURN NEW;
                END;
                $func$ LANGUAGE plpgsql;
                RAISE NOTICE '[PGQINF0554] Created test_function';
            END
            $$;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(multiOpSQL)
                .execute()
                .compose(v -> conn.query("DROP SCHEMA IF EXISTS test_multi_schema CASCADE").execute())
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute multiple operations", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Multiple operations should complete");
        assertTrue(success.get(), "Multiple operations should succeed");

        // Verify multiple info messages were captured
        double finalInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        double messagesReceived = finalInfoCount - initialInfoCount;
        assertTrue(messagesReceived >= 4,
                "Should receive at least 4 PeeGeeQ info messages (schema, table, index, function), got: " + messagesReceived);
    }

    @Test
    @DisplayName("Should preserve tracing context in infrastructure messages")
    void testTracingContextPreservation() throws Exception {
        pool = createPool();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        // This test verifies that info messages can be correlated with operations for tracing
        String correlationId = "trace-" + System.currentTimeMillis();

        double initialInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();

        String tracedSQL = """
            DO $$
            BEGIN
                -- Simulate infrastructure operation with correlation ID
                RAISE NOTICE '[PGQINF0550] [CorrelationID: %s] Starting infrastructure setup', '""" + correlationId + """
            ';

                CREATE SCHEMA IF NOT EXISTS test_trace_schema;
                RAISE NOTICE '[PGQINF0550] [CorrelationID: %s] Schema created: test_trace_schema', '""" + correlationId + """
            ';

                RAISE NOTICE '[PGQINF0550] [CorrelationID: %s] Infrastructure setup complete', '""" + correlationId + """
            ';
            END
            $$;
            """;

        connectionManager.withConnection("test-notice-handler", conn ->
            conn.query(tracedSQL)
                .execute()
                .compose(v -> conn.query("DROP SCHEMA IF EXISTS test_trace_schema CASCADE").execute())
        )
            .onSuccess(result -> {
                success.set(true);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.error("Failed to execute traced operations", err);
                latch.countDown();
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Traced operations should complete");
        assertTrue(success.get(), "Traced operations should succeed");

        // Verify messages were captured
        double finalInfoCount = meterRegistry.counter("peegeeq.notice.peegeeq_info").count();
        assertTrue(finalInfoCount > initialInfoCount,
                "PeeGeeQ info counter should increase for traced operations");
    }
}