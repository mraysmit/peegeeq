package dev.mars.peegeeq.examples.springbootbitemporal;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent.TransactionType;
import dev.mars.peegeeq.examples.springbootbitemporal.model.AccountHistoryResponse;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionCorrectionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Spring Boot Bi-Temporal Event Store Example.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
@SpringBootTest
@Testcontainers
class SpringBootBitemporalApplicationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringBootBitemporalApplicationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("peegeeq_bitemporal_test")
        .withUsername("postgres")
        .withPassword("password");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
    }
    
    @Autowired
    private TransactionService transactionService;
    
    @Test
    void testRecordAndQueryTransaction() throws Exception {
        logger.info("=== Testing Record and Query Transaction ===");
        
        // Record a transaction
        TransactionRequest request = new TransactionRequest();
        request.setAccountId("ACC-TEST-001");
        request.setAmount(new BigDecimal("1000.00"));
        request.setType(TransactionType.CREDIT);
        request.setDescription("Test deposit");
        request.setReference("REF-001");
        
        BiTemporalEvent<TransactionEvent> recorded = transactionService.recordTransaction(request).get();
        assertNotNull(recorded);
        assertEquals("TransactionRecorded", recorded.getEventType());
        
        logger.info("Transaction recorded: {}", recorded.getPayload().getTransactionId());
        
        // Query account history
        AccountHistoryResponse history = transactionService.getAccountHistory("ACC-TEST-001").get();
        assertNotNull(history);
        assertTrue(history.getTotalCount() >= 1);
        
        logger.info("Found {} transactions for account", history.getTotalCount());
        logger.info("=== Test Passed ===");
    }
    
    @Test
    void testCalculateBalance() throws Exception {
        logger.info("=== Testing Balance Calculation ===");
        
        String accountId = "ACC-TEST-002";
        
        // Record multiple transactions
        TransactionRequest credit = new TransactionRequest();
        credit.setAccountId(accountId);
        credit.setAmount(new BigDecimal("1000.00"));
        credit.setType(TransactionType.CREDIT);
        credit.setDescription("Deposit");
        transactionService.recordTransaction(credit).get();
        
        TransactionRequest debit = new TransactionRequest();
        debit.setAccountId(accountId);
        debit.setAmount(new BigDecimal("300.00"));
        debit.setType(TransactionType.DEBIT);
        debit.setDescription("Withdrawal");
        transactionService.recordTransaction(debit).get();
        
        // Calculate balance
        BigDecimal balance = transactionService.getAccountBalance(accountId, Instant.now()).get();
        assertEquals(new BigDecimal("700.00"), balance);
        
        logger.info("Balance for account {}: {}", accountId, balance);
        logger.info("=== Test Passed ===");
    }
    
    @Test
    void testTransactionCorrection() throws Exception {
        logger.info("=== Testing Transaction Correction ===");
        
        // Record original transaction
        TransactionRequest request = new TransactionRequest();
        request.setAccountId("ACC-TEST-003");
        request.setAmount(new BigDecimal("1000.00"));
        request.setType(TransactionType.CREDIT);
        request.setDescription("Original amount");
        
        BiTemporalEvent<TransactionEvent> original = transactionService.recordTransaction(request).get();
        String transactionId = original.getPayload().getTransactionId();
        
        logger.info("Original transaction: {} amount: {}", transactionId, original.getPayload().getAmount());
        
        // Correct the transaction
        TransactionCorrectionRequest correction = new TransactionCorrectionRequest();
        correction.setCorrectedAmount(new BigDecimal("1050.00"));
        correction.setReason("Amount correction");
        
        BiTemporalEvent<TransactionEvent> corrected = transactionService.correctTransaction(transactionId, correction).get();
        assertNotNull(corrected);
        assertEquals("TransactionCorrected", corrected.getEventType());
        
        logger.info("Corrected transaction: {} new amount: {}", transactionId, corrected.getPayload().getAmount());
        
        // Verify both versions exist
        List<BiTemporalEvent<TransactionEvent>> versions = transactionService.getTransactionVersions(transactionId).get();
        assertEquals(2, versions.size());
        
        // Verify same valid time, different transaction time
        assertEquals(versions.get(0).getValidTime(), versions.get(1).getValidTime());
        assertNotEquals(versions.get(0).getTransactionTime(), versions.get(1).getTransactionTime());
        
        logger.info("Found {} versions of transaction", versions.size());
        logger.info("=== Test Passed ===");
    }
}

