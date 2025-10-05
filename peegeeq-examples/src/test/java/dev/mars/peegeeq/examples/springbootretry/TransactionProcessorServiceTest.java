package dev.mars.peegeeq.examples.springbootretry;

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
import dev.mars.peegeeq.examples.springbootretry.events.TransactionEvent;
import dev.mars.peegeeq.examples.springbootretry.service.CircuitBreakerService;
import dev.mars.peegeeq.examples.springbootretry.service.TransactionProcessorService;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import io.vertx.sqlclient.Row;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;

/**
 * Integration tests for Transaction Processor Service with Retry.
 */
@SpringBootTest(
    classes = SpringBootRetryApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "peegeeq.retry.max-retries=5",
        "peegeeq.retry.polling-interval-ms=500",
        "peegeeq.retry.circuit-breaker-threshold=10"
    }
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class TransactionProcessorServiceTest {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionProcessorServiceTest.class);
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        log.info("Configuring properties for TransactionProcessor test");
        SharedTestContainers.configureSharedProperties(registry);
    }
    
    @Autowired
    private MessageProducer<TransactionEvent> producer;
    
    @Autowired
    private TransactionProcessorService processorService;
    
    @Autowired
    private CircuitBreakerService circuitBreaker;
    
    @Autowired
    private DatabaseService databaseService;
    
    @Autowired
    private TestRestTemplate restTemplate;

    @AfterAll
    static void tearDown() {
        log.info("🧹 Cleaning up Transaction Processor Service Test resources");
        // Container cleanup is handled by SharedTestContainers
        log.info("✅ Transaction Processor Service Test cleanup complete");
    }

    @Test
    public void testSuccessfulTransactionProcessing() throws Exception {
        log.info("=== Testing Successful Transaction Processing ===");
        
        // Send a transaction event that should succeed
        TransactionEvent event = new TransactionEvent(
            "TXN-001", "ACC-001", new BigDecimal("500.00"), "DEBIT", null
        );
        producer.send(event).get(5, TimeUnit.SECONDS);
        
        // Wait for processing
        Thread.sleep(2000);
        
        // Verify transaction was stored in database
        boolean transactionExists = databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                return connection.preparedQuery("SELECT COUNT(*) FROM transactions WHERE id = $1")
                    .execute(io.vertx.sqlclient.Tuple.of("TXN-001"))
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return row.getLong(0) > 0;
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        
        assertTrue(transactionExists, "Transaction should be stored in database");
        assertTrue(processorService.getTransactionsProcessed() > 0, "Transactions processed count should increase");
        
        log.info("✅ Successful Transaction Processing test passed");
    }
    
    @Test
    public void testTransactionProcessorServiceBeanInjection() {
        log.info("=== Testing Transaction Processor Service Bean Injection ===");
        
        assertNotNull(processorService, "TransactionProcessorService should be injected");
        assertNotNull(circuitBreaker, "CircuitBreakerService should be injected");
        assertNotNull(producer, "MessageProducer should be injected");
        
        log.info("✅ Transaction Processor Service Bean Injection test passed");
    }
    
    @Test
    public void testRetryMetricsEndpoint() {
        log.info("=== Testing Retry Metrics Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/retry/metrics", Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("transactionsProcessed"));
        assertTrue(response.getBody().containsKey("transactionsFailed"));
        assertTrue(response.getBody().containsKey("transactionsRetried"));
        assertTrue(response.getBody().containsKey("permanentFailures"));
        
        log.info("✅ Retry Metrics Endpoint test passed");
    }
    
    @Test
    public void testCircuitBreakerEndpoint() {
        log.info("=== Testing Circuit Breaker Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/retry/circuit-breaker", Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("state"));
        assertTrue(response.getBody().containsKey("failureCount"));
        assertTrue(response.getBody().containsKey("successCount"));
        assertTrue(response.getBody().containsKey("allowingRequests"));
        
        log.info("✅ Circuit Breaker Endpoint test passed");
    }
    
    @Test
    public void testCircuitBreakerResetEndpoint() {
        log.info("=== Testing Circuit Breaker Reset Endpoint ===");
        
        var response = restTemplate.postForEntity("/api/retry/circuit-breaker/reset", null, Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        
        log.info("✅ Circuit Breaker Reset Endpoint test passed");
    }
    
    @Test
    public void testRetryHealthEndpoint() {
        log.info("=== Testing Retry Health Endpoint ===");
        
        var response = restTemplate.getForEntity("/api/retry/health", Map.class);
        
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertTrue(response.getBody().containsKey("circuitBreakerState"));
        
        log.info("✅ Retry Health Endpoint test passed");
    }
}

