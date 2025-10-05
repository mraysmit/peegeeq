package dev.mars.peegeeq.outbox;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Utility class for initializing test database schema.
 * This replaces the migration system for test environments.
 */
public class TestSchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(TestSchemaInitializer.class);

    /**
     * Initialize the database schema for outbox tests.
     * Creates all necessary tables using JDBC.
     *
     * @param postgres the PostgreSQL container
     */
    public static void initializeSchema(PostgreSQLContainer<?> postgres) {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            logger.debug("Initializing test schema for outbox tests");

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

            // Create outbox_consumer_groups table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
                    id BIGSERIAL PRIMARY KEY,
                    outbox_message_id BIGINT NOT NULL,
                    consumer_group_name VARCHAR(255) NOT NULL,
                    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
                    processed_at TIMESTAMP WITH TIME ZONE,
                    processing_started_at TIMESTAMP WITH TIME ZONE,
                    retry_count INT DEFAULT 0,
                    error_message TEXT,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    UNIQUE(outbox_message_id, consumer_group_name)
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

            // Create queue_messages table (for tests that might need it)
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

            logger.debug("Test schema initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize test schema", e);
            throw new RuntimeException("Schema initialization failed", e);
        }
    }

    /**
     * Clean up test data from all tables.
     * Useful for test isolation.
     *
     * @param postgres the PostgreSQL container
     */
    public static void cleanupTestData(PostgreSQLContainer<?> postgres) {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE outbox CASCADE");
            stmt.execute("TRUNCATE TABLE outbox_consumer_groups CASCADE");
            stmt.execute("TRUNCATE TABLE dead_letter_queue CASCADE");
            stmt.execute("TRUNCATE TABLE queue_messages CASCADE");
            stmt.execute("TRUNCATE TABLE queue_metrics CASCADE");

        } catch (Exception e) {
            logger.warn("Failed to cleanup test data (tables may not exist yet)", e);
        }
    }
}

