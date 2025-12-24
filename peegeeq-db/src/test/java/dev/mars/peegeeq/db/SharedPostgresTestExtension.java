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

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JUnit 5 Extension that provides a single shared PostgreSQL container for all tests.
 * 
 * <p>This extension ensures that:</p>
 * <ul>
 *   <li>Only ONE PostgreSQL container is started for all tests in the module</li>
 *   <li>Database schema is initialized exactly ONCE, even with parallel test execution</li>
 *   <li>Tests can run in parallel without schema conflicts</li>
 *   <li>Container is properly cleaned up after all tests complete</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * {@code
 * @ExtendWith(SharedPostgresExtension.class)
 * class MyTest {
 *     @Test
 *     void myTest() {
 *         PostgreSQLContainer<?> postgres = SharedPostgresTestExtension.getContainer();
 *         // Use postgres container
 *     }
 * }
 * }
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
public class SharedPostgresTestExtension implements BeforeAllCallback {

    private static final Logger logger = LoggerFactory.getLogger(SharedPostgresTestExtension.class);
    
    private static final Lock INIT_LOCK = new ReentrantLock();
    private static volatile PostgreSQLContainer<?> container;
    private static volatile boolean schemaInitialized = false;

    /**
     * Get the shared PostgreSQL container instance.
     *
     * @return the shared container
     * @throws IllegalStateException if container has not been initialized or started
     */
    public static PostgreSQLContainer<?> getContainer() {
        if (container == null) {
            throw new IllegalStateException("PostgreSQL container not initialized. Ensure @ExtendWith(SharedPostgresTestExtension.class) is present.");
        }
        if (!container.isRunning()) {
            throw new IllegalStateException("PostgreSQL container is not running. Container may have been stopped prematurely.");
        }
        return container;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // Use double-checked locking for thread-safe lazy initialization
        if (container == null) {
            INIT_LOCK.lock();
            try {
                if (container == null) {
                    logger.info("Initializing shared PostgreSQL container for all tests");
                    initializeContainer();
                    initializeSchema();
                    logger.info("✅ Shared PostgreSQL container initialized successfully");
                }
            } finally {
                INIT_LOCK.unlock();
            }
        }
    }

    /**
     * Initialize the shared PostgreSQL container.
     *
     * <p>Configuration notes:</p>
     * <ul>
     *   <li>max_connections=200: Supports parallel test execution (4 threads × 5 connections per pool × multiple test classes)</li>
     *   <li>fsync=off, synchronous_commit=off: Faster for tests (not for production!)</li>
     *   <li>shared_buffers=128MB: Adequate for test workloads</li>
     * </ul>
     */
    private void initializeContainer() {
        PostgreSQLContainer<?> tempContainer = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB
                .withReuse(true) // Reuse container across test runs
                .withCommand("postgres",
                    "-c", "max_connections=200",      // Support parallel test execution
                    "-c", "fsync=off",                // Faster for tests
                    "-c", "synchronous_commit=off",   // Faster for tests
                    "-c", "shared_buffers=128MB");    // Adequate for test workloads

        tempContainer.start();

        // Only assign to static field after container is fully started
        container = tempContainer;

        // Register shutdown hook to stop container when JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Stopping shared PostgreSQL container");
            if (container != null && container.isRunning()) {
                container.stop();
            }
        }));

        logger.info("PostgreSQL container started: {}:{}",
                container.getHost(), container.getFirstMappedPort());
    }

    /**
     * Initialize database schema ONCE for all tests.
     * This method is thread-safe and will only execute once even with parallel test execution.
     */
    private void initializeSchema() throws Exception {
        if (schemaInitialized) {
            return;
        }

        INIT_LOCK.lock();
        try {
            if (schemaInitialized) {
                return;
            }

            logger.info("Initializing shared database schema (ONE TIME ONLY)");

            // Create all tables using JDBC (not Vert.x) to ensure synchronous execution
            String jdbcUrl = container.getJdbcUrl();
            try (Connection conn = DriverManager.getConnection(jdbcUrl, container.getUsername(), container.getPassword());
                 Statement stmt = conn.createStatement()) {

                // Create peegeeq schema with LOG-level logging
                stmt.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'peegeeq') THEN
                            CREATE SCHEMA peegeeq;
                            RAISE LOG '[PGQINF0550] Schema created: peegeeq';
                        ELSE
                            RAISE LOG '[PGQINF0551] Schema already exists: peegeeq';
                        END IF;
                    END
                    $$
                    """);
                logger.info("[PGQINF0550] Ensured peegeeq schema exists");

                // Create outbox table (in public schema for backward compatibility)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        processed_at TIMESTAMP WITH TIME ZONE,
                        processing_started_at TIMESTAMP WITH TIME ZONE,
                        status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        next_retry_at TIMESTAMP WITH TIME ZONE,
                        version INT DEFAULT 0,
                        headers JSONB DEFAULT '{}',
                        error_message TEXT,
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
                        required_consumer_groups INT DEFAULT 1,
                        completed_consumer_groups INT DEFAULT 0,
                        completed_groups_bitmap BIGINT DEFAULT 0
                    )
                    """);

                // Create queue_messages table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS queue_messages (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        lock_id BIGINT,
                        lock_until TIMESTAMP WITH TIME ZONE,
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                        headers JSONB DEFAULT '{}',
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                    )
                    """);

                // Create dead_letter_queue table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dead_letter_queue (
                        id BIGSERIAL PRIMARY KEY,
                        original_table VARCHAR(50) NOT NULL,
                        original_id BIGINT NOT NULL,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        failure_reason TEXT NOT NULL,
                        retry_count INT NOT NULL,
                        headers JSONB DEFAULT '{}',
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255)
                    )
                    """);

                // Create queue_metrics table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS queue_metrics (
                        id BIGSERIAL PRIMARY KEY,
                        metric_name VARCHAR(100) NOT NULL,
                        metric_value DOUBLE PRECISION NOT NULL,
                        tags JSONB DEFAULT '{}',
                        timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )
                    """);

                // V010: Consumer Group Fanout Tables
                // Create outbox_topics table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox_topics (
                        topic VARCHAR(255) PRIMARY KEY,
                        semantics VARCHAR(20) NOT NULL DEFAULT 'QUEUE' CHECK (semantics IN ('QUEUE', 'PUB_SUB')),
                        message_retention_hours INT NOT NULL DEFAULT 24,
                        zero_subscription_retention_hours INT DEFAULT 24,
                        block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE,
                        completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING' CHECK (completion_tracking_mode IN ('REFERENCE_COUNTING', 'OFFSET_WATERMARK')),
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )
                    """);

                // Create outbox_topic_subscriptions table in public schema (for consistency with outbox_topics)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        group_name VARCHAR(255) NOT NULL,
                        subscription_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),
                        subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        start_from_message_id BIGINT,
                        start_from_timestamp TIMESTAMP WITH TIME ZONE,
                        heartbeat_interval_seconds INT NOT NULL DEFAULT 60,
                        heartbeat_timeout_seconds INT NOT NULL DEFAULT 300,
                        last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        backfill_status VARCHAR(20) DEFAULT 'NONE' CHECK (backfill_status IN ('NONE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED')),
                        backfill_checkpoint_id BIGINT,
                        backfill_processed_messages BIGINT DEFAULT 0,
                        backfill_total_messages BIGINT,
                        backfill_started_at TIMESTAMP WITH TIME ZONE,
                        backfill_completed_at TIMESTAMP WITH TIME ZONE,
                        UNIQUE (topic, group_name)
                    )
                    """);

                // Create outbox_consumer_groups table (tracking table for Reference Counting mode)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
                        id BIGSERIAL PRIMARY KEY,
                        message_id BIGINT NOT NULL,
                        group_name VARCHAR(255) NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
                        processed_at TIMESTAMP WITH TIME ZONE,
                        error_message TEXT,
                        retry_count INT DEFAULT 0,
                        UNIQUE (message_id, group_name)
                    )
                    """);

                // Create processed_ledger table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS processed_ledger (
                        message_id BIGINT NOT NULL,
                        group_name VARCHAR(255) NOT NULL,
                        processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        PRIMARY KEY (message_id, group_name)
                    )
                    """);

                // Create partition_drop_audit table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS partition_drop_audit (
                        partition_name VARCHAR(255) PRIMARY KEY,
                        dropped_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        min_message_id BIGINT,
                        max_message_id BIGINT,
                        message_count BIGINT
                    )
                    """);

                // Create consumer_group_index table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS consumer_group_index (
                        group_name VARCHAR(255) PRIMARY KEY,
                        last_processed_message_id BIGINT,
                        last_processed_at TIMESTAMP WITH TIME ZONE,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )
                    """);

                // Create trigger function for setting required_consumer_groups
                stmt.execute("""
                    CREATE OR REPLACE FUNCTION set_required_consumer_groups()
                    RETURNS TRIGGER AS $$
                    DECLARE
                        topic_semantics VARCHAR(20);
                        active_subscription_count INT;
                    BEGIN
                        -- Get topic semantics (default to QUEUE if not configured)
                        SELECT COALESCE(semantics, 'QUEUE') INTO topic_semantics
                        FROM outbox_topics
                        WHERE topic = NEW.topic;

                        -- If topic not configured, treat as QUEUE
                        IF topic_semantics IS NULL THEN
                            topic_semantics := 'QUEUE';
                        END IF;

                        -- For PUB_SUB topics, count ACTIVE subscriptions
                        IF topic_semantics = 'PUB_SUB' THEN
                            SELECT COUNT(*) INTO active_subscription_count
                            FROM outbox_topic_subscriptions
                            WHERE topic = NEW.topic
                              AND subscription_status = 'ACTIVE';

                            NEW.required_consumer_groups := active_subscription_count;
                        ELSE
                            -- For QUEUE topics, set to 1 (backward compatibility)
                            NEW.required_consumer_groups := 1;
                        END IF;

                        -- Initialize completed_consumer_groups to 0
                        NEW.completed_consumer_groups := 0;
                        NEW.completed_groups_bitmap := 0;

                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);

                // Create trigger on outbox table
                stmt.execute("""
                    DROP TRIGGER IF EXISTS trigger_set_required_consumer_groups ON outbox;
                    """);

                stmt.execute("""
                    CREATE TRIGGER trigger_set_required_consumer_groups
                        BEFORE INSERT ON outbox
                        FOR EACH ROW
                        EXECUTE FUNCTION set_required_consumer_groups();
                    """);

                logger.info("✅ Shared database schema initialized successfully (including V010 fanout tables)");
                schemaInitialized = true;
            }
        } finally {
            INIT_LOCK.unlock();
        }
    }

    /**
     * Reset the extension state (for testing purposes only).
     */
    static void reset() {
        INIT_LOCK.lock();
        try {
            if (container != null && container.isRunning()) {
                container.stop();
            }
            container = null;
            schemaInitialized = false;
        } finally {
            INIT_LOCK.unlock();
        }
    }
}

