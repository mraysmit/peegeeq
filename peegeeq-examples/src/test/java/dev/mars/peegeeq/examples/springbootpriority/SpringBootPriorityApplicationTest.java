package dev.mars.peegeeq.examples.springbootpriority;

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

import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springbootpriority.controller.TradeProducerController;
import dev.mars.peegeeq.examples.springbootpriority.service.AllTradesConsumerService;
import dev.mars.peegeeq.examples.springbootpriority.service.CriticalTradeConsumerService;
import dev.mars.peegeeq.examples.springbootpriority.service.HighPriorityConsumerService;
import dev.mars.peegeeq.examples.springbootpriority.service.TradeProducerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Spring Boot Priority Example.
 * 
 * Tests demonstrate:
 * - Priority-based message processing
 * - Multiple consumer patterns (all-trades, critical-only, high-priority)
 * - Message filtering by priority
 * - Consumer metrics tracking
 * - REST API endpoints
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=springboot-priority",
        "priority.consumers.all.enabled=true",
        "priority.consumers.critical.enabled=true",
        "priority.consumers.high.enabled=true"
    }
)
@Testcontainers
@ActiveProfiles("springboot-priority")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class SpringBootPriorityApplicationTest {
    
    private static final Logger log = LoggerFactory.getLogger(SpringBootPriorityApplicationTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        log.info("Configuring properties for SpringBootPriority test");
        SharedTestContainers.configureSharedProperties(registry);
    }
    
    @Autowired
    private TradeProducerService producerService;
    
    @Autowired(required = false)
    private AllTradesConsumerService allTradesConsumer;
    
    @Autowired(required = false)
    private CriticalTradeConsumerService criticalConsumer;
    
    @Autowired(required = false)
    private HighPriorityConsumerService highPriorityConsumer;
    
    @Autowired
    private TestRestTemplate restTemplate;

    @AfterAll
    static void tearDown() {
        log.info("🧹 Cleaning up Spring Boot Priority Test resources");
        log.info("✅ Spring Boot Priority Test cleanup complete");
    }

    @Test
    void testApplicationStarts() {
        log.info("=== Testing Application Starts ===");
        assertNotNull(producerService, "Producer service should be initialized");
        log.info("✅ Application starts test passed");
    }
    
    @Test
    void testSendCriticalTrade() throws Exception {
        log.info("=== Testing Send Critical Trade ===");
        
        TradeProducerController.TradeRequest request = new TradeProducerController.TradeRequest();
        request.tradeId = "TRADE-CRITICAL-001";
        request.counterparty = "BANK-A";
        request.amount = new BigDecimal("1000000.00");
        request.currency = "USD";
        request.settlementDate = LocalDate.now().plusDays(1);
        request.failureReason = "Settlement system unavailable";
        
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/trades/settlement-fail",
            request,
            Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CRITICAL", response.getBody().get("priority"));
        assertEquals("FAIL", response.getBody().get("status"));
        
        // Wait for processing
        Thread.sleep(2000);
        
        // Verify producer metrics
        assertTrue(producerService.getCriticalSent() > 0, "Critical messages should be sent");
        
        log.info("✅ Send Critical Trade test passed");
    }
    
    @Test
    void testSendHighPriorityTrade() throws Exception {
        log.info("=== Testing Send High Priority Trade ===");
        
        TradeProducerController.TradeRequest request = new TradeProducerController.TradeRequest();
        request.tradeId = "TRADE-HIGH-001";
        request.counterparty = "BANK-B";
        request.amount = new BigDecimal("500000.00");
        request.currency = "EUR";
        request.settlementDate = LocalDate.now().plusDays(2);
        
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/trades/amendment",
            request,
            Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("HIGH", response.getBody().get("priority"));
        assertEquals("AMEND", response.getBody().get("status"));
        
        // Wait for processing
        Thread.sleep(2000);
        
        // Verify producer metrics
        assertTrue(producerService.getHighSent() > 0, "High priority messages should be sent");
        
        log.info("✅ Send High Priority Trade test passed");
    }
    
    @Test
    void testSendNormalPriorityTrade() throws Exception {
        log.info("=== Testing Send Normal Priority Trade ===");
        
        TradeProducerController.TradeRequest request = new TradeProducerController.TradeRequest();
        request.tradeId = "TRADE-NORMAL-001";
        request.counterparty = "BANK-C";
        request.amount = new BigDecimal("100000.00");
        request.currency = "GBP";
        request.settlementDate = LocalDate.now().plusDays(3);
        
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/trades/confirmation",
            request,
            Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NORMAL", response.getBody().get("priority"));
        assertEquals("NEW", response.getBody().get("status"));
        
        // Wait for processing
        Thread.sleep(2000);
        
        // Verify producer metrics
        assertTrue(producerService.getNormalSent() > 0, "Normal priority messages should be sent");
        
        log.info("✅ Send Normal Priority Trade test passed");
    }
    
    @Test
    void testProducerMetrics() {
        log.info("=== Testing Producer Metrics ===");
        
        ResponseEntity<Map> response = restTemplate.getForEntity(
            "/api/trades/metrics",
            Map.class
        );
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("totalSent"));
        assertTrue(response.getBody().containsKey("criticalSent"));
        assertTrue(response.getBody().containsKey("highSent"));
        assertTrue(response.getBody().containsKey("normalSent"));
        
        log.info("✅ Producer Metrics test passed");
    }
    
    @Test
    void testMonitoringEndpoints() {
        log.info("=== Testing Monitoring Endpoints ===");
        
        // Test overall metrics
        ResponseEntity<Map> metricsResponse = restTemplate.getForEntity(
            "/api/monitoring/metrics",
            Map.class
        );
        assertEquals(HttpStatus.OK, metricsResponse.getStatusCode());
        assertNotNull(metricsResponse.getBody());
        assertTrue(metricsResponse.getBody().containsKey("producer"));
        assertTrue(metricsResponse.getBody().containsKey("consumers"));
        
        // Test health endpoint
        ResponseEntity<Map> healthResponse = restTemplate.getForEntity(
            "/api/monitoring/health",
            Map.class
        );
        assertEquals(HttpStatus.OK, healthResponse.getStatusCode());
        assertNotNull(healthResponse.getBody());
        assertEquals("UP", healthResponse.getBody().get("status"));
        
        log.info("✅ Monitoring Endpoints test passed");
    }
    
    @Test
    void testConsumerMetrics() throws Exception {
        log.info("=== Testing Consumer Metrics ===");

        // Send messages of different priorities
        sendTestTrade("TRADE-TEST-001", "settlement-fail");
        sendTestTrade("TRADE-TEST-002", "amendment");
        sendTestTrade("TRADE-TEST-003", "confirmation");

        // Wait for processing
        Thread.sleep(3000);

        // Verify total messages processed across all consumers
        // Note: In outbox pattern with competing consumers, each message is processed by ONE consumer
        // The consumers compete for messages, so we verify total processing, not individual consumer counts
        long totalProcessed = 0;
        long totalFiltered = 0;

        if (allTradesConsumer != null) {
            totalProcessed += allTradesConsumer.getMessagesProcessed();
            log.info("All-trades consumer processed: {}", allTradesConsumer.getMessagesProcessed());
        }

        if (criticalConsumer != null) {
            totalProcessed += criticalConsumer.getMessagesProcessed();
            totalFiltered += criticalConsumer.getMessagesFiltered();
            log.info("Critical consumer processed: {}, filtered: {}",
                criticalConsumer.getCriticalProcessed(), criticalConsumer.getMessagesFiltered());
        }

        if (highPriorityConsumer != null) {
            totalProcessed += highPriorityConsumer.getMessagesProcessed();
            totalFiltered += highPriorityConsumer.getMessagesFiltered();
            long totalHighPriority = highPriorityConsumer.getCriticalProcessed() +
                                    highPriorityConsumer.getHighProcessed();
            log.info("High-priority consumer processed: {}, filtered: {}",
                totalHighPriority, highPriorityConsumer.getMessagesFiltered());
        }

        // Verify that messages were processed (competing consumers pattern)
        assertTrue(totalProcessed >= 3,
            "At least 3 messages should be processed across all consumers (got: " + totalProcessed + ")");

        // Verify that filtering occurred (some consumers should filter out non-matching priorities)
        assertTrue(totalFiltered > 0,
            "Some messages should be filtered by priority-specific consumers (got: " + totalFiltered + ")");

        log.info("✅ Consumer Metrics test passed - Total processed: {}, Total filtered: {}",
            totalProcessed, totalFiltered);
    }
    
    private void sendTestTrade(String tradeId, String endpoint) {
        TradeProducerController.TradeRequest request = new TradeProducerController.TradeRequest();
        request.tradeId = tradeId;
        request.counterparty = "TEST-BANK";
        request.amount = new BigDecimal("10000.00");
        request.currency = "USD";
        request.settlementDate = LocalDate.now().plusDays(1);
        if ("settlement-fail".equals(endpoint)) {
            request.failureReason = "Test failure";
        }
        
        restTemplate.postForEntity("/api/trades/" + endpoint, request, Map.class);
    }
}

