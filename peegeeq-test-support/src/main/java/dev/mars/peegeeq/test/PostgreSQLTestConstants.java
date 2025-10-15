package dev.mars.peegeeq.test;

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

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Centralized PostgreSQL version constants for all PeeGeeQ tests and examples.
 * 
 * This class ensures that only ONE PostgreSQL version is used across the entire project,
 * preventing Docker Desktop pollution with multiple PostgreSQL images.
 * 
 * Usage:
 * ```java
 * // ✅ CORRECT - Use the centralized constant
 * PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
 *     .withDatabaseName("test_db")
 *     .withUsername("test_user")
 *     .withPassword("test_password");
 * 
 * // ❌ WRONG - Don't hardcode versions
 * PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
public final class PostgreSQLTestConstants {
    
    /**
     * The ONLY PostgreSQL Docker image version used across the entire PeeGeeQ project.
     * 
     * This version is carefully chosen for:
     * - Stability: PostgreSQL 15.13 is a stable LTS release
     * - Size: Alpine Linux base keeps image size minimal
     * - Security: Specific version pinning prevents unexpected updates
     * - Performance: Optimized Alpine build with good performance characteristics
     * 
     * DO NOT change this without updating ALL references across the project.
     * Use the provided migration script if version updates are needed.
     */
    public static final String POSTGRES_IMAGE = PostgreSQLTestConstants.POSTGRES_IMAGE;
    
    /**
     * Standard database name for PeeGeeQ tests.
     */
    public static final String DEFAULT_DATABASE_NAME = "peegeeq_test";
    
    /**
     * Standard username for PeeGeeQ tests.
     */
    public static final String DEFAULT_USERNAME = "peegeeq_test";
    
    /**
     * Standard password for PeeGeeQ tests.
     */
    public static final String DEFAULT_PASSWORD = "peegeeq_test";
    
    /**
     * Standard shared memory size for PostgreSQL containers (256MB).
     * This provides good performance for most test scenarios.
     */
    public static final long DEFAULT_SHARED_MEMORY_SIZE = 256 * 1024 * 1024L;
    
    /**
     * High-performance shared memory size for performance tests (512MB).
     * Use this for performance-critical tests that need more memory.
     */
    public static final long HIGH_PERFORMANCE_SHARED_MEMORY_SIZE = 512 * 1024 * 1024L;
    
    /**
     * Creates a standard PostgreSQL container with default settings.
     * 
     * @return configured PostgreSQL container
     */
    public static PostgreSQLContainer<?> createStandardContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(DEFAULT_DATABASE_NAME)
                .withUsername(DEFAULT_USERNAME)
                .withPassword(DEFAULT_PASSWORD)
                .withSharedMemorySize(DEFAULT_SHARED_MEMORY_SIZE)
                .withReuse(false);
    }
    
    /**
     * Creates a PostgreSQL container with custom database settings.
     * 
     * @param databaseName the database name
     * @param username the username
     * @param password the password
     * @return configured PostgreSQL container
     */
    public static PostgreSQLContainer<?> createContainer(String databaseName, String username, String password) {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(databaseName)
                .withUsername(username)
                .withPassword(password)
                .withSharedMemorySize(DEFAULT_SHARED_MEMORY_SIZE)
                .withReuse(false);
    }
    
    /**
     * Creates a high-performance PostgreSQL container for performance tests.
     * 
     * @param databaseName the database name
     * @param username the username
     * @param password the password
     * @return configured high-performance PostgreSQL container
     */
    public static PostgreSQLContainer<?> createHighPerformanceContainer(String databaseName, String username, String password) {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(databaseName)
                .withUsername(username)
                .withPassword(password)
                .withSharedMemorySize(HIGH_PERFORMANCE_SHARED_MEMORY_SIZE)
                .withReuse(false)
                .withCommand("postgres",
                    // Performance optimizations
                    "-c", "shared_buffers=256MB",
                    "-c", "effective_cache_size=1GB",
                    "-c", "work_mem=16MB",
                    "-c", "maintenance_work_mem=64MB",
                    "-c", "max_connections=200",
                    "-c", "checkpoint_completion_target=0.9",
                    "-c", "wal_buffers=16MB",
                    "-c", "random_page_cost=1.1",
                    "-c", "effective_io_concurrency=200"
                );
    }
    
    /**
     * Private constructor to prevent instantiation.
     */
    private PostgreSQLTestConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
