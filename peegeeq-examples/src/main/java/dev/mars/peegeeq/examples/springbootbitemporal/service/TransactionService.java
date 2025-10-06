package dev.mars.peegeeq.examples.springbootbitemporal.service;

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
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent;
import dev.mars.peegeeq.examples.springbootbitemporal.model.AccountHistoryResponse;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionCorrectionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service for managing financial transactions using bi-temporal event store.
 * 
 * <p>This service provides:
 * <ul>
 *   <li>Transaction recording with bi-temporal dimensions</li>
 *   <li>Historical transaction queries</li>
 *   <li>Point-in-time balance calculations</li>
 *   <li>Transaction corrections with audit trail</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
@Service
public class TransactionService {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);
    
    private final EventStore<TransactionEvent> eventStore;
    
    public TransactionService(EventStore<TransactionEvent> eventStore) {
        this.eventStore = eventStore;
    }
    
    /**
     * Records a new transaction in the event store.
     * 
     * @param request Transaction request
     * @return CompletableFuture with the recorded event
     */
    public CompletableFuture<BiTemporalEvent<TransactionEvent>> recordTransaction(TransactionRequest request) {
        String transactionId = UUID.randomUUID().toString();
        
        TransactionEvent event = new TransactionEvent(
            transactionId,
            request.getAccountId(),
            request.getAmount(),
            request.getType(),
            request.getDescription(),
            request.getReference()
        );
        
        Instant validTime = request.getValidTime() != null ? request.getValidTime() : Instant.now();
        
        logger.info("Recording transaction: {} for account: {} amount: {} type: {}",
            transactionId, request.getAccountId(), request.getAmount(), request.getType());
        
        return eventStore.append("TransactionRecorded", event, validTime)
            .whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Failed to record transaction: {}", transactionId, error);
                } else {
                    logger.info("Transaction recorded successfully: {}", transactionId);
                }
            });
    }
    
    /**
     * Retrieves all transactions for an account.
     * 
     * @param accountId Account identifier
     * @return CompletableFuture with account history
     */
    public CompletableFuture<AccountHistoryResponse> getAccountHistory(String accountId) {
        logger.info("Retrieving transaction history for account: {}", accountId);
        
        return eventStore.query(EventQuery.all())
            .thenApply(events -> {
                List<BiTemporalEvent<TransactionEvent>> accountTransactions = events.stream()
                    .filter(event -> accountId.equals(event.getPayload().getAccountId()))
                    .collect(Collectors.toList());
                
                logger.info("Found {} transactions for account: {}", accountTransactions.size(), accountId);
                return new AccountHistoryResponse(accountId, accountTransactions);
            });
    }
    
    /**
     * Calculates account balance at a specific point in time.
     * 
     * @param accountId Account identifier
     * @param asOf Point in time for balance calculation
     * @return CompletableFuture with the balance
     */
    public CompletableFuture<BigDecimal> getAccountBalance(String accountId, Instant asOf) {
        logger.info("Calculating balance for account: {} as of: {}", accountId, asOf);
        
        return eventStore.query(EventQuery.all())
            .thenApply(events -> {
                BigDecimal balance = events.stream()
                    .filter(event -> accountId.equals(event.getPayload().getAccountId()))
                    .filter(event -> !event.getValidTime().isAfter(asOf))
                    .map(event -> {
                        TransactionEvent txn = event.getPayload();
                        return txn.getType() == TransactionEvent.TransactionType.CREDIT
                            ? txn.getAmount()
                            : txn.getAmount().negate();
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                logger.info("Balance for account: {} as of: {} is: {}", accountId, asOf, balance);
                return balance;
            });
    }
    
    /**
     * Corrects a transaction by recording a correction event.
     * 
     * <p>The correction preserves the original valid time but has a new transaction time,
     * maintaining complete audit trail.
     * 
     * @param transactionId Original transaction ID
     * @param request Correction request
     * @return CompletableFuture with the correction event
     */
    public CompletableFuture<BiTemporalEvent<TransactionEvent>> correctTransaction(
            String transactionId, TransactionCorrectionRequest request) {
        
        logger.info("Correcting transaction: {} with new amount: {} reason: {}",
            transactionId, request.getCorrectedAmount(), request.getReason());
        
        // Find the original transaction
        return eventStore.query(EventQuery.all())
            .thenCompose(events -> {
                BiTemporalEvent<TransactionEvent> original = events.stream()
                    .filter(event -> transactionId.equals(event.getPayload().getTransactionId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
                
                // Create correction event with same valid time as original
                TransactionEvent correctionEvent = new TransactionEvent(
                    transactionId,
                    original.getPayload().getAccountId(),
                    request.getCorrectedAmount(),
                    original.getPayload().getType(),
                    original.getPayload().getDescription() + " [CORRECTED: " + request.getReason() + "]",
                    original.getPayload().getReference()
                );
                
                // Append with original valid time (bi-temporal correction)
                return eventStore.append("TransactionCorrected", correctionEvent, original.getValidTime())
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            logger.error("Failed to correct transaction: {}", transactionId, error);
                        } else {
                            logger.info("Transaction corrected successfully: {}", transactionId);
                        }
                    });
            });
    }
    
    /**
     * Retrieves all versions of a transaction (original + corrections).
     * 
     * @param transactionId Transaction identifier
     * @return CompletableFuture with all versions
     */
    public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getTransactionVersions(String transactionId) {
        logger.info("Retrieving all versions of transaction: {}", transactionId);
        
        return eventStore.query(EventQuery.all())
            .thenApply(events -> {
                List<BiTemporalEvent<TransactionEvent>> versions = events.stream()
                    .filter(event -> transactionId.equals(event.getPayload().getTransactionId()))
                    .collect(Collectors.toList());
                
                logger.info("Found {} versions of transaction: {}", versions.size(), transactionId);
                return versions;
            });
    }
}

