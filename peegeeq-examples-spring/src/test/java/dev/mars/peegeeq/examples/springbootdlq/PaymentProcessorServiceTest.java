package dev.mars.peegeeq.examples.springbootdlq;

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
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.springbootdlq.events.PaymentEvent;
import dev.mars.peegeeq.examples.springbootdlq.service.DlqManagementService;
import dev.mars.peegeeq.examples.springbootdlq.service.PaymentProcessorService;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Payment Processor Service with DLQ.
 */
@Tag(TestCategories.INTEGRATION)
@SpringBootTest(
    classes = SpringBootDlqApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "peegeeq.dlq.max-retries=3",
        "peegeeq.dlq.polling-interval-ms=500",
        "peegeeq.dlq.dlq-alert-threshold=5"
    }
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class PaymentProcessorServiceTest {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessorServiceTest.class);
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @org.junit.jupiter.api.AfterAll
    static void tearDown() {
        log.info("🧹 Cleaning up Payment Processor Service Test resources");
        // Container cleanup is handled by SharedTestContainers
        log.info("✅ Payment Processor Service Test cleanup complete");
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        log.info("Configuring properties for PaymentProcessor test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        log.info("Initializing database schema for Spring Boot DLQ payment processor test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        log.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @BeforeEach
    void setUp() throws Exception {
        log.info("=== Setting up application-specific tables ===");

        // Create payments table for this specific test
        String createPaymentsTable = """
            CREATE TABLE IF NOT EXISTS payments (
                id VARCHAR(255) PRIMARY KEY,
                order_id VARCHAR(255) NOT NULL,
                amount DECIMAL(19, 4) NOT NULL,
                currency VARCHAR(3) NOT NULL,
                payment_method VARCHAR(50) NOT NULL,
                status VARCHAR(50) NOT NULL,
                processed_at TIMESTAMP,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                retry_count INTEGER NOT NULL DEFAULT 0
            )
            """;

        // Execute application-specific schema creation
        databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                return connection.query(createPaymentsTable).execute()
                    .map(v -> {
                        log.info("Application-specific schema created successfully");
                        return (Void) null;
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

        log.info("=== Application-specific schema setup complete ===");
    }

    @Autowired
    private MessageProducer<PaymentEvent> producer;
    
    @Autowired
    private PaymentProcessorService processorService;
    
    @Autowired
    private DlqManagementService dlqService;
    
    @Autowired
    private DatabaseService databaseService;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    public void testSuccessfulPaymentProcessing() throws Exception {
        log.info("=== Testing Successful Payment Processing ===");
        
        // Send a payment event that should succeed
        PaymentEvent event = new PaymentEvent(
            "PAY-001", "ORDER-001", new BigDecimal("100.00"), "USD", "CREDIT_CARD", false
        );
        producer.send(event).get(5, TimeUnit.SECONDS);
        
        // Wait for processing
        Thread.sleep(2000);
        
        // Verify payment was stored in database
        boolean paymentExists = databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                return connection.preparedQuery("SELECT COUNT(*) FROM payments WHERE id = $1")
                    .execute(io.vertx.sqlclient.Tuple.of("PAY-001"))
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return row.getLong(0) > 0;
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        
        assertTrue(paymentExists, "Payment should be stored in database");
        assertTrue(processorService.getPaymentsProcessed() > 0, "Payments processed count should increase");
        
        log.info("✅ Successful Payment Processing test passed");
    }
    
    @Test
    public void testPaymentProcessorServiceBeanInjection() {
        log.info("=== Testing Payment Processor Service Bean Injection ===");
        
        assertNotNull(processorService, "PaymentProcessorService should be injected");
        assertNotNull(dlqService, "DlqManagementService should be injected");
        assertNotNull(producer, "MessageProducer should be injected");
        
        log.info("✅ Payment Processor Service Bean Injection test passed");
    }
    
    @SuppressWarnings("null")
    @Test
    public void testDlqDepthEndpoint() {
        log.info("=== Testing DLQ Depth Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/dlq/depth", Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("depth"));
        
        log.info("✅ DLQ Depth Endpoint test passed");
    }
    
    @SuppressWarnings("null")
    @Test
    public void testDlqStatsEndpoint() {
        log.info("=== Testing DLQ Stats Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/dlq/stats", Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("depth"));
        assertTrue(response.getBody().containsKey("threshold"));
        assertTrue(response.getBody().containsKey("alerting"));
        
        log.info("✅ DLQ Stats Endpoint test passed");
    }
    
    @SuppressWarnings("null")
    @Test
    public void testDlqMetricsEndpoint() {
        log.info("=== Testing DLQ Metrics Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/dlq/metrics", Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("paymentsProcessed"));
        assertTrue(response.getBody().containsKey("paymentsFailed"));
        assertTrue(response.getBody().containsKey("paymentsRetried"));
        
        log.info("✅ DLQ Metrics Endpoint test passed");
    }
    
    @Test
    public void testDlqMessagesEndpoint() {
        log.info("=== Testing DLQ Messages Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/dlq/messages", List.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        
        log.info("✅ DLQ Messages Endpoint test passed");
    }
}

