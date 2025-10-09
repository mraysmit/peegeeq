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
 *         PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
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
public class SharedPostgresExtension implements BeforeAllCallback {

    private static final Logger logger = LoggerFactory.getLogger(SharedPostgresExtension.class);
    
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
            throw new IllegalStateException("PostgreSQL container not initialized. Ensure @ExtendWith(SharedPostgresExtension.class) is present.");
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
     */
    private void initializeContainer() {
        PostgreSQLContainer<?> tempContainer = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB
                .withReuse(true) // Reuse container across test runs
                .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off"); // Faster for tests

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

                // Create outbox table
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
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
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

                logger.info("✅ Shared database schema initialized successfully");
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

