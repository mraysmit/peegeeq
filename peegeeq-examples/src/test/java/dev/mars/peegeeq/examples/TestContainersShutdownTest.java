package dev.mars.peegeeq.examples;

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


import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test to verify TestContainers shutdown behavior with manual vs automatic management.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class TestContainersShutdownTest {
    private static final Logger logger = LoggerFactory.getLogger(TestContainersShutdownTest.class);

    @Test
    void testManualContainerManagement() {
        logger.info("Testing manual container management");
        
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
                .withDatabaseName("test_db")
                .withUsername("test_user")
                .withPassword("test_pass")
                .withReuse(false);
        
        try {
            postgres.start();
            logger.info("Container started: {}", postgres.getJdbcUrl());
            
            // Simulate some work
            Thread.sleep(1000);
            
        } catch (Exception e) {
            logger.error("Error in test", e);
        } finally {
            logger.info("Stopping container manually");
            postgres.stop();
            logger.info("Container stopped");
        }
    }
}
