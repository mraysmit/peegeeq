package dev.mars.peegeeq.examples.springbootbitemporal.controller;

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
import dev.mars.peegeeq.examples.springbootbitemporal.model.AccountHistoryResponse;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionCorrectionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.model.TransactionRequest;
import dev.mars.peegeeq.examples.springbootbitemporal.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for transaction operations.
 * 
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Recording transactions</li>
 *   <li>Querying account history</li>
 *   <li>Calculating point-in-time balances</li>
 *   <li>Correcting transactions</li>
 *   <li>Viewing transaction versions</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class TransactionController {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);
    
    private final TransactionService transactionService;
    
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }
    
    /**
     * Records a new transaction.
     * 
     * <p>Example:
     * <pre>
     * POST /api/transactions
     * {
     *   "accountId": "ACC-001",
     *   "amount": "1000.00",
     *   "type": "CREDIT",
     *   "description": "Initial deposit",
     *   "reference": "REF-001"
     * }
     * </pre>
     * 
     * @param request Transaction request
     * @return CompletableFuture with the recorded event
     */
    @PostMapping("/transactions")
    public CompletableFuture<ResponseEntity<BiTemporalEvent<TransactionEvent>>> recordTransaction(
            @RequestBody TransactionRequest request) {
        
        logger.info("REST: Recording transaction for account: {}", request.getAccountId());
        
        return transactionService.recordTransaction(request)
            .thenApply(ResponseEntity::ok)
            .exceptionally(error -> {
                logger.error("REST: Failed to record transaction", error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Retrieves transaction history for an account.
     * 
     * <p>Example:
     * <pre>
     * GET /api/accounts/ACC-001/history
     * </pre>
     * 
     * @param accountId Account identifier
     * @return CompletableFuture with account history
     */
    @GetMapping("/accounts/{accountId}/history")
    public CompletableFuture<ResponseEntity<AccountHistoryResponse>> getAccountHistory(
            @PathVariable String accountId) {
        
        logger.info("REST: Retrieving history for account: {}", accountId);
        
        return transactionService.getAccountHistory(accountId)
            .thenApply(ResponseEntity::ok)
            .exceptionally(error -> {
                logger.error("REST: Failed to retrieve account history", error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Calculates account balance at a specific point in time.
     * 
     * <p>Example:
     * <pre>
     * GET /api/accounts/ACC-001/balance?asOf=2025-10-01T12:00:00Z
     * </pre>
     * 
     * @param accountId Account identifier
     * @param asOf Point in time (optional, defaults to now)
     * @return CompletableFuture with the balance
     */
    @GetMapping("/accounts/{accountId}/balance")
    public CompletableFuture<ResponseEntity<BigDecimal>> getAccountBalance(
            @PathVariable String accountId,
            @RequestParam(required = false) Instant asOf) {
        
        Instant pointInTime = asOf != null ? asOf : Instant.now();
        logger.info("REST: Calculating balance for account: {} as of: {}", accountId, pointInTime);
        
        return transactionService.getAccountBalance(accountId, pointInTime)
            .thenApply(ResponseEntity::ok)
            .exceptionally(error -> {
                logger.error("REST: Failed to calculate balance", error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Corrects a transaction.
     * 
     * <p>Example:
     * <pre>
     * POST /api/transactions/TXN-001/correct
     * {
     *   "correctedAmount": "1050.00",
     *   "reason": "Amount correction"
     * }
     * </pre>
     * 
     * @param transactionId Transaction identifier
     * @param request Correction request
     * @return CompletableFuture with the correction event
     */
    @PostMapping("/transactions/{transactionId}/correct")
    public CompletableFuture<ResponseEntity<BiTemporalEvent<TransactionEvent>>> correctTransaction(
            @PathVariable String transactionId,
            @RequestBody TransactionCorrectionRequest request) {
        
        logger.info("REST: Correcting transaction: {}", transactionId);
        
        return transactionService.correctTransaction(transactionId, request)
            .thenApply(ResponseEntity::ok)
            .exceptionally(error -> {
                logger.error("REST: Failed to correct transaction", error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Retrieves all versions of a transaction (original + corrections).
     * 
     * <p>Example:
     * <pre>
     * GET /api/transactions/TXN-001/versions
     * </pre>
     * 
     * @param transactionId Transaction identifier
     * @return CompletableFuture with all versions
     */
    @GetMapping("/transactions/{transactionId}/versions")
    public CompletableFuture<ResponseEntity<List<BiTemporalEvent<TransactionEvent>>>> getTransactionVersions(
            @PathVariable String transactionId) {
        
        logger.info("REST: Retrieving versions for transaction: {}", transactionId);
        
        return transactionService.getTransactionVersions(transactionId)
            .thenApply(ResponseEntity::ok)
            .exceptionally(error -> {
                logger.error("REST: Failed to retrieve transaction versions", error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Health check endpoint.
     * 
     * @return Health status
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Bi-Temporal Event Store is running");
    }
}

