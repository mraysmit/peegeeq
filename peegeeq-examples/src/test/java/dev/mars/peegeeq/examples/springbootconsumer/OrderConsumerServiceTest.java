package dev.mars.peegeeq.examples.springbootconsumer;

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
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.examples.springbootconsumer.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootconsumer.service.OrderConsumerService;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Spring Boot Consumer Example.
 * 
 * Tests demonstrate:
 * - Basic message consumption
 * - Message filtering
 * - Consumer metrics
 * - REST API monitoring
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "consumer.filtering.enabled=true",
        "consumer.filtering.allowed-statuses=PENDING,CONFIRMED"
    }
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrderConsumerServiceTest {
    
    private static final Logger log = LoggerFactory.getLogger(OrderConsumerServiceTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_consumer_test")
            .withUsername("postgres")
            .withPassword("password");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.ssl.enabled", () -> "false");
    }
    
    @Autowired
    private OrderConsumerService consumerService;
    
    @Autowired
    private QueueFactory queueFactory;
    
    @Autowired
    private DatabaseService databaseService;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    void testBasicMessageConsumption() throws Exception {
        log.info("=== Testing Basic Message Consumption ===");
        
        // Create producer
        MessageProducer<OrderEvent> producer = queueFactory.createProducer("order-events", OrderEvent.class);
        
        // Send test message
        OrderEvent event = new OrderEvent("ORDER-001", "customer-1", new BigDecimal("100.00"), "PENDING");
        producer.send(event).get(5, TimeUnit.SECONDS);
        
        // Wait for message to be processed
        Thread.sleep(2000);
        
        // Verify order was stored in database
        boolean orderExists = databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                return connection.preparedQuery("SELECT COUNT(*) FROM orders WHERE id = $1")
                    .execute(io.vertx.sqlclient.Tuple.of("ORDER-001"))
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return row.getLong(0) > 0;
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        
        assertTrue(orderExists, "Order should be stored in database");
        assertTrue(consumerService.getMessagesProcessed() > 0, "Consumer should have processed messages");
        
        producer.close();
        log.info("✅ Basic Message Consumption test passed");
    }
    
    @Test
    void testMessageFiltering() throws Exception {
        log.info("=== Testing Message Filtering ===");
        
        // Create producer
        MessageProducer<OrderEvent> producer = queueFactory.createProducer("order-events", OrderEvent.class);
        
        long initialProcessed = consumerService.getMessagesProcessed();
        long initialFiltered = consumerService.getMessagesFiltered();
        
        // Send message with allowed status (should be processed)
        OrderEvent allowedEvent = new OrderEvent("ORDER-002", "customer-2", new BigDecimal("150.00"), "PENDING");
        producer.send(allowedEvent).get(5, TimeUnit.SECONDS);
        
        // Send message with disallowed status (should be filtered)
        OrderEvent filteredEvent = new OrderEvent("ORDER-003", "customer-3", new BigDecimal("200.00"), "SHIPPED");
        producer.send(filteredEvent).get(5, TimeUnit.SECONDS);
        
        // Wait for messages to be processed
        Thread.sleep(2000);
        
        // Verify filtering worked
        long processedDelta = consumerService.getMessagesProcessed() - initialProcessed;
        long filteredDelta = consumerService.getMessagesFiltered() - initialFiltered;
        
        assertTrue(processedDelta >= 1, "At least one message should be processed");
        assertTrue(filteredDelta >= 1, "At least one message should be filtered");
        
        producer.close();
        log.info("✅ Message Filtering test passed");
    }
    
    @Test
    void testConsumerHealthEndpoint() {
        log.info("=== Testing Consumer Health Endpoint ===");
        
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/consumer/health", Map.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertNotNull(response.getBody().get("consumerInstanceId"));
        assertNotNull(response.getBody().get("messagesProcessed"));
        
        log.info("✅ Consumer Health Endpoint test passed");
    }
    
    @Test
    void testConsumerMetricsEndpoint() {
        log.info("=== Testing Consumer Metrics Endpoint ===");
        
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/consumer/metrics", Map.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("consumerInstanceId"));
        assertNotNull(response.getBody().get("messagesProcessed"));
        assertNotNull(response.getBody().get("messagesFiltered"));
        assertNotNull(response.getBody().get("messagesFailed"));
        assertNotNull(response.getBody().get("totalMessagesReceived"));
        
        log.info("✅ Consumer Metrics Endpoint test passed");
    }
    
    @Test
    void testConsumerStatusEndpoint() {
        log.info("=== Testing Consumer Status Endpoint ===");
        
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/consumer/status", Map.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("RUNNING", response.getBody().get("status"));
        assertNotNull(response.getBody().get("consumerInstanceId"));
        
        log.info("✅ Consumer Status Endpoint test passed");
    }
    
    @Test
    void testConsumerServiceBeanInjection() {
        log.info("=== Testing Consumer Service Bean Injection ===");
        
        assertNotNull(consumerService, "OrderConsumerService should be injected");
        assertNotNull(queueFactory, "QueueFactory should be injected");
        assertNotNull(databaseService, "DatabaseService should be injected");
        
        log.info("✅ Consumer Service Bean Injection test passed");
    }
}

