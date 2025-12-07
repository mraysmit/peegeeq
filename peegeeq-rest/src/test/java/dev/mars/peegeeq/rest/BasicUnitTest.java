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

package dev.mars.peegeeq.rest;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic unit tests that don't require TestContainers.
 * 
 * These tests verify the basic functionality of configuration classes
 * and API interfaces without requiring external dependencies.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class BasicUnitTest {
    
    private static final Logger logger = LoggerFactory.getLogger(BasicUnitTest.class);
    
    @Test
    void testDatabaseConfigBuilder() {
        logger.info("=== Testing DatabaseConfig Builder ===");
        
        DatabaseConfig config = new DatabaseConfig.Builder()
                .host("localhost")
                .port(5432)
                .databaseName("test_db")
                .username("test_user")
                .password("test_pass")
                .schema("public")
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();
        
        assertNotNull(config);
        assertEquals("localhost", config.getHost());
        assertEquals(5432, config.getPort());
        assertEquals("test_db", config.getDatabaseName());
        assertEquals("test_user", config.getUsername());
        assertEquals("test_pass", config.getPassword());
        assertEquals("public", config.getSchema());
        assertEquals("template0", config.getTemplateDatabase());
        assertEquals("UTF8", config.getEncoding());
        
        logger.info("DatabaseConfig builder working correctly");
        logger.info("=== DatabaseConfig Builder Test Passed ===");
    }
    
    @Test
    void testQueueConfigBuilder() {
        logger.info("=== Testing QueueConfig Builder ===");
        
        QueueConfig config = new QueueConfig.Builder()
                .queueName("test_queue")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .deadLetterEnabled(true)
                .batchSize(10)
                .pollingInterval(Duration.ofSeconds(5))
                .fifoEnabled(false)
                .deadLetterQueueName("test_queue_dlq")
                .build();
        
        assertNotNull(config);
        assertEquals("test_queue", config.getQueueName());
        assertEquals(3, config.getMaxRetries());
        assertEquals(Duration.ofSeconds(30), config.getVisibilityTimeout());
        assertTrue(config.isDeadLetterEnabled());
        assertEquals(10, config.getBatchSize());
        assertEquals(Duration.ofSeconds(5), config.getPollingInterval());
        assertFalse(config.isFifoEnabled());
        assertEquals("test_queue_dlq", config.getDeadLetterQueueName());
        
        logger.info("QueueConfig builder working correctly");
        logger.info("=== QueueConfig Builder Test Passed ===");
    }
    
    @Test
    void testEventStoreConfigBuilder() {
        logger.info("=== Testing EventStoreConfig Builder ===");
        
        EventStoreConfig config = new EventStoreConfig.Builder()
                .eventStoreName("test_events")
                .tableName("test_events_table")
                .biTemporalEnabled(true)
                .notificationPrefix("test_events_")
                .build();
        
        assertNotNull(config);
        assertEquals("test_events", config.getEventStoreName());
        assertEquals("test_events_table", config.getTableName());
        assertTrue(config.isBiTemporalEnabled());
        assertEquals("test_events_", config.getNotificationPrefix());
        
        logger.info("EventStoreConfig builder working correctly");
        logger.info("=== EventStoreConfig Builder Test Passed ===");
    }
    
    @Test
    void testDatabaseSetupRequest() {
        logger.info("=== Testing DatabaseSetupRequest ===");
        
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host("localhost")
                .port(5432)
                .databaseName("test_db")
                .username("test_user")
                .password("test_pass")
                .schema("public")
                .build();
        
        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName("test_queue")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();
        
        EventStoreConfig eventStoreConfig = new EventStoreConfig.Builder()
                .eventStoreName("test_events")
                .tableName("test_events_table")
                .biTemporalEnabled(true)
                .build();
        
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                "test_setup",
                dbConfig,
                List.of(queueConfig),
                List.of(eventStoreConfig),
                Map.of("test", "value")
        );
        
        assertNotNull(request);
        assertEquals("test_setup", request.getSetupId());
        assertEquals(dbConfig, request.getDatabaseConfig());
        assertEquals(1, request.getQueues().size());
        assertEquals(queueConfig, request.getQueues().get(0));
        assertEquals(1, request.getEventStores().size());
        assertEquals(eventStoreConfig, request.getEventStores().get(0));
        assertEquals(Map.of("test", "value"), request.getAdditionalProperties());
        
        logger.info("DatabaseSetupRequest working correctly");
        logger.info("=== DatabaseSetupRequest Test Passed ===");
    }
    
    @Test
    void testDatabaseSetupStatus() {
        logger.info("=== Testing DatabaseSetupStatus Enum ===");
        
        // Test all enum values
        DatabaseSetupStatus[] statuses = DatabaseSetupStatus.values();
        assertEquals(5, statuses.length);
        
        // Test specific values
        assertEquals(DatabaseSetupStatus.CREATING, DatabaseSetupStatus.valueOf("CREATING"));
        assertEquals(DatabaseSetupStatus.ACTIVE, DatabaseSetupStatus.valueOf("ACTIVE"));
        assertEquals(DatabaseSetupStatus.DESTROYING, DatabaseSetupStatus.valueOf("DESTROYING"));
        assertEquals(DatabaseSetupStatus.DESTROYED, DatabaseSetupStatus.valueOf("DESTROYED"));
        assertEquals(DatabaseSetupStatus.FAILED, DatabaseSetupStatus.valueOf("FAILED"));
        
        logger.info("DatabaseSetupStatus enum working correctly");
        logger.info("=== DatabaseSetupStatus Test Passed ===");
    }
    
    @Test
    void testRestServerInstantiation() {
        logger.info("=== Testing REST Server Instantiation ===");

        // Test that we can create the REST server with a stub DatabaseSetupService
        // The constructor now requires a DatabaseSetupService implementation
        DatabaseSetupService stubService = new StubDatabaseSetupService();

        PeeGeeQRestServer serverWithPort = new PeeGeeQRestServer(8080, stubService);
        assertNotNull(serverWithPort);

        logger.info("REST server instantiation working correctly");
        logger.info("=== REST Server Instantiation Test Passed ===");
    }

    /**
     * Stub implementation of DatabaseSetupService for unit testing.
     * This allows testing REST server instantiation without requiring
     * the full implementation modules.
     */
    private static class StubDatabaseSetupService implements DatabaseSetupService {
        @Override
        public java.util.concurrent.CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override
        public java.util.concurrent.CompletableFuture<Void> destroySetup(String setupId) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override
        public java.util.concurrent.CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) {
            return java.util.concurrent.CompletableFuture.completedFuture(DatabaseSetupStatus.ACTIVE);
        }
        @Override
        public java.util.concurrent.CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override
        public java.util.concurrent.CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override
        public java.util.concurrent.CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override
        public java.util.concurrent.CompletableFuture<java.util.Set<String>> getAllActiveSetupIds() {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Set.of());
        }
        @Override
        public SubscriptionService getSubscriptionServiceForSetup(String setupId) {
            return null;
        }
        @Override
        public DeadLetterService getDeadLetterServiceForSetup(String setupId) {
            return null;
        }
        @Override
        public HealthService getHealthServiceForSetup(String setupId) {
            return null;
        }
        @Override
        public QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) {
            return null;
        }
    }
    
    @Test
    void testHandlerInstantiation() {
        logger.info("=== Testing Handler Instantiation ===");
        
        // We can't easily test the handlers without mocking the dependencies,
        // but we can at least verify the classes exist and can be loaded
        try {
            Class.forName("dev.mars.peegeeq.rest.handlers.DatabaseSetupHandler");
            Class.forName("dev.mars.peegeeq.rest.handlers.QueueHandler");
            Class.forName("dev.mars.peegeeq.rest.handlers.EventStoreHandler");
            
            logger.info("All handler classes found and loadable");
        } catch (ClassNotFoundException e) {
            fail("Handler class not found: " + e.getMessage());
        }
        
        logger.info("=== Handler Instantiation Test Passed ===");
    }
    
    @Test
    void testConfigurationValidation() {
        logger.info("=== Testing Configuration Validation ===");

        // Test that configurations can be built with minimal required fields
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host("localhost")
                .port(5432)
                .databaseName("test")
                .username("user")
                .password("pass")
                .build();
        assertNotNull(dbConfig);

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName("test_queue")
                .build();
        assertNotNull(queueConfig);

        EventStoreConfig eventStoreConfig = new EventStoreConfig.Builder()
                .eventStoreName("test_events")
                .build();
        assertNotNull(eventStoreConfig);

        logger.info("Configuration objects can be created with minimal fields");
        logger.info("=== Configuration Validation Test Passed ===");
    }
}
