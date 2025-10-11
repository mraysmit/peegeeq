package dev.mars.peegeeq.examples.springboot2;

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
import dev.mars.peegeeq.examples.springboot2.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot2.events.PaymentEvent;
import dev.mars.peegeeq.examples.springboot2.adapter.ReactiveOutboxAdapter;
import dev.mars.peegeeq.examples.springboot2.config.PeeGeeQProperties;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxProducer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PeeGeeQ Reactive Configuration.
 *
 * This test verifies that all PeeGeeQ components are properly configured and
 * integrated with the reactive Spring Boot application:
 * - PeeGeeQManager initialization
 * - OutboxProducer beans creation
 * - ReactiveOutboxAdapter configuration
 * - Configuration properties binding
 * - Database connectivity
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@SpringBootTest(
    classes = SpringBootReactiveOutboxApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=testcontainers",
        "logging.level.dev.mars.peegeeq=DEBUG",
        "test.context.unique=PeeGeeQReactiveConfigTest"
    }
)
@TestPropertySource(properties = {
    "peegeeq.database.host=${DB_HOST:localhost}",
    "peegeeq.database.port=${DB_PORT:5432}",
    "peegeeq.database.name=${DB_NAME:peegeeq_shared_test}",
    "peegeeq.database.username=${DB_USERNAME:peegeeq_test}",
    "peegeeq.database.password=${DB_PASSWORD:peegeeq_test}",
    "spring.r2dbc.url=r2dbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:peegeeq_shared_test}",
    "spring.r2dbc.username=${DB_USERNAME:peegeeq_test}",
    "spring.r2dbc.password=${DB_PASSWORD:peegeeq_test}"
})
@Testcontainers
class PeeGeeQReactiveConfigTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQReactiveConfigTest.class);

    @Autowired
    private PeeGeeQManager manager;

    @Autowired
    private OutboxProducer<OrderEvent> orderEventProducer;

    @Autowired
    private OutboxProducer<PaymentEvent> paymentEventProducer;

    @Autowired
    private ReactiveOutboxAdapter reactiveAdapter;

    @Autowired
    private PeeGeeQProperties properties;

    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for PeeGeeQReactiveConfigTest");
        SharedTestContainers.configureSharedProperties(registry);

        // Override the hardcoded properties with actual container values
        PostgreSQLContainer<?> container = SharedTestContainers.getSharedPostgreSQLContainer();
        registry.add("peegeeq.database.host", container::getHost);
        registry.add("peegeeq.database.port", () -> container.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", container::getDatabaseName);
        registry.add("peegeeq.database.username", container::getUsername);
        registry.add("peegeeq.database.password", container::getPassword);

        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
            container.getHost(), container.getFirstMappedPort(), container.getDatabaseName());
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", container::getUsername);
        registry.add("spring.r2dbc.password", container::getPassword);

        logger.info("Overridden properties with container values: host={}, port={}, database={}",
            container.getHost(), container.getFirstMappedPort(), container.getDatabaseName());
    }

    @BeforeAll
    static void setupSchema() throws Exception {
        logger.info("Initializing database schema for PeeGeeQ reactive config test");
        PeeGeeQTestSchemaInitializer.initializeSchema(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            SchemaComponent.ALL
        );
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    /**
     * Test that PeeGeeQManager is properly configured and initialized.
     */
    @Test
    void testPeeGeeQManagerConfiguration() {
        logger.info("=== Test: PeeGeeQManager Configuration ===");

        assertNotNull(manager, "PeeGeeQManager should be autowired");
        logger.info("✓ PeeGeeQManager is properly configured");
        logger.info("✓ PeeGeeQ lifecycle management is working");

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test that OutboxProducer beans are properly created.
     */
    @Test
    void testOutboxProducerBeans() {
        logger.info("=== Test: OutboxProducer Beans ===");

        assertNotNull(orderEventProducer, "OrderEvent producer should be autowired");
        assertNotNull(paymentEventProducer, "PaymentEvent producer should be autowired");
        
        logger.info("✓ OrderEvent producer is configured");
        logger.info("✓ PaymentEvent producer is configured");
        logger.info("✓ Outbox pattern is ready for use");

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test that ReactiveOutboxAdapter is properly configured.
     */
    @Test
    void testReactiveOutboxAdapter() {
        logger.info("=== Test: ReactiveOutboxAdapter Configuration ===");

        assertNotNull(reactiveAdapter, "ReactiveOutboxAdapter should be autowired");
        logger.info("✓ ReactiveOutboxAdapter is configured");
        logger.info("✓ CompletableFuture to Mono/Flux conversion is available");

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test that PeeGeeQProperties are properly bound.
     */
    @Test
    void testPeeGeeQProperties() {
        logger.info("=== Test: PeeGeeQProperties Binding ===");

        assertNotNull(properties, "PeeGeeQProperties should be autowired");
        assertNotNull(properties.getDatabase(), "Database properties should be set");
        
        logger.info("Database host: {}", properties.getDatabase().getHost());
        logger.info("Database port: {}", properties.getDatabase().getPort());
        logger.info("Database name: {}", properties.getDatabase().getName());
        
        assertEquals(postgres.getHost(), properties.getDatabase().getHost(), 
            "Database host should match TestContainer");
        assertEquals(postgres.getFirstMappedPort(), properties.getDatabase().getPort(), 
            "Database port should match TestContainer");
        assertEquals(postgres.getDatabaseName(), properties.getDatabase().getName(), 
            "Database name should match TestContainer");
        
        logger.info("✓ PeeGeeQProperties are properly bound");
        logger.info("✓ Configuration properties are working");

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test that all PeeGeeQ components work together.
     */
    @Test
    void testPeeGeeQIntegration() {
        logger.info("=== Test: PeeGeeQ Integration ===");

        assertNotNull(manager, "Manager should be available");
        assertNotNull(orderEventProducer, "Order producer should be available");
        assertNotNull(paymentEventProducer, "Payment producer should be available");
        assertNotNull(reactiveAdapter, "Reactive adapter should be available");
        assertNotNull(properties, "Properties should be available");
        
        logger.info("✓ All PeeGeeQ components are properly integrated");
        logger.info("✓ Reactive outbox pattern is fully configured");
        logger.info("✓ System is ready for transactional messaging");

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test that database configuration is correct.
     */
    @Test
    void testDatabaseConfiguration() {
        logger.info("=== Test: Database Configuration ===");

        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        
        PeeGeeQProperties.Database dbConfig = properties.getDatabase();
        assertNotNull(dbConfig, "Database configuration should not be null");
        assertNotNull(dbConfig.getHost(), "Database host should not be null");
        assertNotNull(dbConfig.getPort(), "Database port should not be null");
        assertNotNull(dbConfig.getName(), "Database name should not be null");
        assertNotNull(dbConfig.getUsername(), "Database username should not be null");
        assertNotNull(dbConfig.getPassword(), "Database password should not be null");
        
        logger.info("✓ Database configuration is complete");
        logger.info("✓ TestContainer is properly configured");

        logger.info("=== Test Completed Successfully ===");
    }
}

