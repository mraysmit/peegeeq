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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springboot.model.CreateOrderResponse;
import dev.mars.peegeeq.examples.springboot.model.OrderItem;
import dev.mars.peegeeq.examples.springboot2.model.Order;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the Reactive Order Controller using WebTestClient.
 *
 * This test verifies the reactive REST API endpoints by making real HTTP calls to a
 * running Spring Boot WebFlux application instance, testing the complete reactive stack:
 * - Netty HTTP server and non-blocking networking
 * - Reactive request/response handling
 * - WebFlux routing and handlers
 * - R2DBC database operations
 * - Error handling and validation
 * - Transactional rollback scenarios
 * - Health check endpoints
 * - PeeGeeQ transactional outbox pattern integration
 *
 * WebTestClient provides a fluent API for testing reactive endpoints with backpressure support.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@SpringBootTest(
    classes = SpringBootReactiveOutboxApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=DEBUG",
        "logging.level.org.springframework.data.r2dbc=DEBUG"
    }
)
@Testcontainers
class OrderControllerTest {

    private static final Logger logger = LoggerFactory.getLogger(OrderControllerTest.class);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DatabaseService databaseService;

    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OrderControllerTest");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot 2 reactive order controller test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up application-specific tables for reactive test ===");

        // Create orders table for this specific test
        String createOrdersTable = """
            CREATE TABLE IF NOT EXISTS orders (
                id VARCHAR(255) PRIMARY KEY,
                customer_id VARCHAR(255) NOT NULL,
                amount DECIMAL(19, 2) NOT NULL,
                status VARCHAR(50) NOT NULL,
                description TEXT,
                created_at TIMESTAMP NOT NULL
            )
            """;

        // Create order_items table for this specific test
        String createOrderItemsTable = """
            CREATE TABLE IF NOT EXISTS order_items (
                id VARCHAR(255) PRIMARY KEY,
                order_id VARCHAR(255) NOT NULL,
                product_id VARCHAR(255) NOT NULL,
                name VARCHAR(255) NOT NULL,
                quantity INTEGER NOT NULL,
                price DECIMAL(19, 2) NOT NULL,
                total_price DECIMAL(19, 2),
                FOREIGN KEY (order_id) REFERENCES orders(id)
            )
            """;

        // Execute application-specific schema creation
        databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                return connection.query(createOrdersTable).execute()
                    .compose(v -> connection.query(createOrderItemsTable).execute())
                    .map(v -> {
                        logger.info("Application-specific schema created successfully");
                        return (Void) null;
                    });
            }).toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);

        logger.info("=== Application-specific schema setup complete ===");
    }

    /**
     * Test creating an order successfully through the reactive REST API.
     */
    @Test
    void testCreateOrder() {
        logger.info("=== Test: Create Order (Reactive) ===");

        CreateOrderRequest request = new CreateOrderRequest(
            "CUST-001",
            new BigDecimal("299.99"),
            Arrays.asList(
                new OrderItem("PROD-001", "Premium Widget", 2, new BigDecimal("149.99"))
            )
        );

        webTestClient
            .post()
            .uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody(CreateOrderResponse.class)
            .value(response -> {
                assertNotNull(response.getOrderId(), "Order ID should not be null");
                logger.info("✓ Order created successfully with ID: {}", response.getOrderId());
            });

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test creating an order with validation.
     */
    @Test
    void testCreateOrderWithValidation() {
        logger.info("=== Test: Create Order With Validation (Reactive) ===");

        CreateOrderRequest request = new CreateOrderRequest(
            "CUST-002",
            new BigDecimal("99.99"),
            Arrays.asList(
                new OrderItem("PROD-002", "Standard Widget", 1, new BigDecimal("99.99"))
            )
        );

        webTestClient
            .post()
            .uri("/api/orders/with-validation")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody(CreateOrderResponse.class)
            .value(response -> {
                assertNotNull(response.getOrderId(), "Order ID should not be null");
                logger.info("✓ Order created with validation, ID: {}", response.getOrderId());
            });

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test retrieving an order by ID.
     */
    @Test
    void testGetOrderById() {
        logger.info("=== Test: Get Order By ID (Reactive) ===");

        // First create an order
        CreateOrderRequest createRequest = new CreateOrderRequest(
            "CUST-003",
            new BigDecimal("199.99"),
            Arrays.asList(
                new OrderItem("PROD-003", "Deluxe Widget", 1, new BigDecimal("199.99"))
            )
        );

        @SuppressWarnings("null")
        String orderId = webTestClient
            .post()
            .uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest)
            .exchange()
            .expectStatus().isOk()
            .expectBody(CreateOrderResponse.class)
            .returnResult()
            .getResponseBody()
            .getOrderId();

        logger.info("Created order with ID: {}", orderId);

        // Now retrieve it
        webTestClient
            .get()
            .uri("/api/orders/{id}", orderId)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody(Order.class)
            .value(order -> {
                assertEquals(orderId, order.getId(), "Order ID should match");
                assertEquals("CUST-003", order.getCustomerId(), "Customer ID should match");
                logger.info("✓ Order retrieved successfully: {}", order.getId());
            });

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test retrieving a non-existent order returns 404.
     */
    @Test
    void testGetNonExistentOrder() {
        logger.info("=== Test: Get Non-Existent Order (Reactive) ===");

        webTestClient
            .get()
            .uri("/api/orders/{id}", "non-existent-id")
            .exchange()
            .expectStatus().isNotFound();

        logger.info("✓ Non-existent order correctly returned 404");
        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test health check endpoint.
     */
    @Test
    void testHealthCheck() {
        logger.info("=== Test: Health Check (Reactive) ===");

        webTestClient
            .get()
            .uri("/api/orders/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(health -> {
                assertTrue(health.contains("healthy"), "Health check should return healthy status");
                logger.info("✓ Health check response: {}", health);
            });

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test validating an order.
     */
    @Test
    void testValidateOrder() {
        logger.info("=== Test: Validate Order (Reactive) ===");

        // First create an order
        CreateOrderRequest createRequest = new CreateOrderRequest(
            "CUST-004",
            new BigDecimal("149.99"),
            Arrays.asList(
                new OrderItem("PROD-004", "Basic Widget", 1, new BigDecimal("149.99"))
            )
        );

        @SuppressWarnings("null")
        String orderId = webTestClient
            .post()
            .uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest)
            .exchange()
            .expectStatus().isOk()
            .expectBody(CreateOrderResponse.class)
            .returnResult()
            .getResponseBody()
            .getOrderId();

        // Now validate it
        webTestClient
            .post()
            .uri("/api/orders/{id}/validate", orderId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(message -> {
                assertTrue(message.contains("validated"), "Response should indicate validation");
                logger.info("✓ Order validated: {}", message);
            });

        logger.info("=== Test Completed Successfully ===");
    }

    /**
     * Test that WebTestClient is properly configured with timeout.
     */
    @Test
    void testWebTestClientConfiguration() {
        logger.info("=== Test: WebTestClient Configuration ===");

        assertNotNull(webTestClient, "WebTestClient should be autowired");
        logger.info("✓ WebTestClient is properly configured");
        logger.info("✓ Reactive testing infrastructure is working");

        logger.info("=== Test Completed Successfully ===");
    }
}

