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
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent.TransactionType;
import dev.mars.peegeeq.examples.springbootbitemporal.model.AccountHistoryResponse;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionCorrectionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.service.TransactionService;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
@SpringBootTest(properties = {"test.context.unique=SpringBootBitemporalApplicationTest"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SpringBootBitemporalApplicationTest {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootBitemporalApplicationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring Spring Boot Bi-Temporal properties for TestContainer");
        SharedTestContainers.configureSharedProperties(registry);

        // Pattern 1 (Full Spring Boot Integration): No need to set system properties manually
        // The BitemporalConfig.configureSystemProperties() method automatically bridges
        // Spring properties to system properties when creating the PeeGeeQManager bean

        logger.info("Spring properties configured for TestContainer: host={}, port={}, database={}",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }



    @Autowired
    private TransactionService transactionService;

    @BeforeAll
    static void initializeSchema() throws Exception {
        logger.info("Initializing database schema for Spring Boot Bitemporal test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }
    
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
        assertEquals(0, new BigDecimal("700.00").compareTo(balance));
        
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

    @Test
    void testDomainSpecificQueryMethods() throws Exception {
        logger.info("=== Testing Domain-Specific Query Methods ===");

        String accountId = "ACC-TEST-QUERY-001";

        // Record multiple transactions of different types
        TransactionRequest request1 = new TransactionRequest();
        request1.setAccountId(accountId);
        request1.setAmount(new BigDecimal("500.00"));
        request1.setType(TransactionType.CREDIT);
        request1.setDescription("First deposit");
        BiTemporalEvent<TransactionEvent> txn1 = transactionService.recordTransaction(request1).get();

        TransactionRequest request2 = new TransactionRequest();
        request2.setAccountId(accountId);
        request2.setAmount(new BigDecimal("200.00"));
        request2.setType(TransactionType.DEBIT);
        request2.setDescription("Withdrawal");
        transactionService.recordTransaction(request2).get();

        TransactionRequest request3 = new TransactionRequest();
        request3.setAccountId(accountId);
        request3.setAmount(new BigDecimal("300.00"));
        request3.setType(TransactionType.CREDIT);
        request3.setDescription("Second deposit");
        transactionService.recordTransaction(request3).get();

        // Correct the first transaction
        TransactionCorrectionRequest correction = new TransactionCorrectionRequest();
        correction.setCorrectedAmount(new BigDecimal("550.00"));
        correction.setReason("Amount adjustment");
        transactionService.correctTransaction(txn1.getPayload().getTransactionId(), correction).get();

        logger.info("Created 3 recorded transactions and 1 correction for account: {}", accountId);

        // Test 1: Query all transactions by account (using EventQuery.forAggregate)
        List<BiTemporalEvent<TransactionEvent>> allTransactions =
            transactionService.queryTransactionsByAccount(accountId).get();
        assertEquals(4, allTransactions.size(), "Should have 3 recorded + 1 corrected = 4 total");
        logger.info("✓ queryTransactionsByAccount: Found {} transactions", allTransactions.size());

        // Test 2: Query only recorded transactions (using EventQuery.forAggregateAndType)
        List<BiTemporalEvent<TransactionEvent>> recordedOnly =
            transactionService.queryRecordedTransactions(accountId).get();
        assertEquals(3, recordedOnly.size(), "Should have 3 recorded transactions");
        assertTrue(recordedOnly.stream().allMatch(e -> "TransactionRecorded".equals(e.getEventType())));
        logger.info("✓ queryRecordedTransactions: Found {} recorded transactions", recordedOnly.size());

        // Test 3: Query only corrected transactions (using EventQuery.forAggregateAndType)
        List<BiTemporalEvent<TransactionEvent>> correctedOnly =
            transactionService.queryCorrectedTransactions(accountId).get();
        assertEquals(1, correctedOnly.size(), "Should have 1 corrected transaction");
        assertTrue(correctedOnly.stream().allMatch(e -> "TransactionCorrected".equals(e.getEventType())));
        logger.info("✓ queryCorrectedTransactions: Found {} corrected transactions", correctedOnly.size());

        // Test 4: Query by account and type directly
        List<BiTemporalEvent<TransactionEvent>> recordedDirect =
            transactionService.queryTransactionsByAccountAndType(accountId, "TransactionRecorded").get();
        assertEquals(3, recordedDirect.size(), "Direct query should match convenience method");
        logger.info("✓ queryTransactionsByAccountAndType: Found {} transactions", recordedDirect.size());

        logger.info("=== Domain-Specific Query Methods Test Passed ===");
        logger.info("Demonstrated patterns:");
        logger.info("  1. queryTransactionsByAccount(accountId) - wraps EventQuery.forAggregate() [async]");
        logger.info("  2. queryTransactionsByAccountAndType(accountId, type) - wraps EventQuery.forAggregateAndType() [async]");
        logger.info("  3. queryRecordedTransactions(accountId) - domain-specific convenience method [async]");
        logger.info("  4. queryCorrectedTransactions(accountId) - domain-specific convenience method [async]");
        logger.info("  NOTE: All methods MUST return CompletableFuture to avoid blocking the event loop");
    }
}

