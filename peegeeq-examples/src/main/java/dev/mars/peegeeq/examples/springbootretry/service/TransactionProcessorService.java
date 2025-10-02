package dev.mars.peegeeq.examples.springbootretry.service;

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
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.examples.springbootretry.config.PeeGeeQRetryProperties;
import dev.mars.peegeeq.examples.springbootretry.events.TransactionEvent;
import dev.mars.peegeeq.examples.springbootretry.exception.PermanentFailureException;
import dev.mars.peegeeq.examples.springbootretry.exception.TransientFailureException;
import dev.mars.peegeeq.examples.springbootretry.model.Transaction;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transaction Processor Service with advanced retry strategies.
 * 
 * Demonstrates the CORRECT way to implement retry patterns:
 * - Classify failures as transient or permanent
 * - Use circuit breaker to prevent cascading failures
 * - Track retry metrics for monitoring
 * - Let PeeGeeQ handle automatic retry logic
 * - Implement exponential backoff via next_retry_at
 * 
 * Key Principles:
 * 1. Throw TransientFailureException for retryable errors
 * 2. Throw PermanentFailureException for non-retryable errors
 * 3. Check circuit breaker before processing
 * 4. Record success/failure for circuit breaker
 * 5. PeeGeeQ automatically retries up to max retries
 */
@Service
public class TransactionProcessorService {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionProcessorService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final MessageConsumer<TransactionEvent> consumer;
    private final DatabaseService databaseService;
    private final CircuitBreakerService circuitBreaker;
    private final PeeGeeQRetryProperties properties;
    
    // Metrics
    private final AtomicLong transactionsProcessed = new AtomicLong(0);
    private final AtomicLong transactionsFailed = new AtomicLong(0);
    private final AtomicLong transactionsRetried = new AtomicLong(0);
    private final AtomicLong permanentFailures = new AtomicLong(0);
    
    public TransactionProcessorService(
            MessageConsumer<TransactionEvent> consumer,
            DatabaseService databaseService,
            CircuitBreakerService circuitBreaker,
            PeeGeeQRetryProperties properties) {
        this.consumer = consumer;
        this.databaseService = databaseService;
        this.circuitBreaker = circuitBreaker;
        this.properties = properties;
    }
    
    /**
     * Start processing transactions when the application is ready.
     * Uses ApplicationReadyEvent to ensure schema is initialized first.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startProcessing() {
        log.info("Starting transaction processing with max retries: {}", properties.getMaxRetries());
        consumer.subscribe(this::processTransaction);
        log.info("Transaction processor started successfully");
    }
    
    /**
     * Stop processing transactions when the application is shutting down.
     * Properly closes the consumer to shut down background threads.
     */
    @PreDestroy
    public void stopProcessing() {
        log.info("Stopping transaction processor");
        try {
            // Close consumer to shut down scheduler and background threads
            consumer.close();
            log.info("Transaction processor stopped successfully");
        } catch (Exception e) {
            log.error("Error stopping transaction processor", e);
        }
    }
    
    /**
     * Process transaction message with retry logic.
     */
    private CompletableFuture<Void> processTransaction(Message<TransactionEvent> message) {
        TransactionEvent event = message.getPayload();
        
        log.debug("Processing transaction: transactionId={}, amount={}", 
            event.getTransactionId(), event.getAmount());
        
        // Check circuit breaker
        if (!circuitBreaker.allowRequest()) {
            log.warn("Circuit breaker is OPEN - rejecting transaction: {}", event.getTransactionId());
            transactionsFailed.incrementAndGet();
            throw new TransientFailureException("Circuit breaker is open");
        }
        
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                // Create transaction from event
                Transaction transaction = new Transaction(
                    event.getTransactionId(),
                    event.getAccountId(),
                    event.getAmount(),
                    event.getType()
                );
                
                // Simulate different failure types for testing
                if ("TRANSIENT".equals(event.getFailureType())) {
                    transactionsRetried.incrementAndGet();
                    circuitBreaker.recordFailure();
                    log.warn("⚠️ Transient failure (simulated): transactionId={}", event.getTransactionId());
                    throw new TransientFailureException("Network timeout (simulated)");
                }
                
                if ("PERMANENT".equals(event.getFailureType())) {
                    permanentFailures.incrementAndGet();
                    circuitBreaker.recordFailure();
                    log.error("❌ Permanent failure (simulated): transactionId={}", event.getTransactionId());
                    throw new PermanentFailureException("Invalid account (simulated)");
                }
                
                // Process transaction successfully
                transaction.setStatus("COMPLETED");
                transaction.setProcessedAt(Instant.now());
                
                // Convert Instant to LocalDateTime for PostgreSQL TIMESTAMP
                LocalDateTime createdAt = LocalDateTime.ofInstant(transaction.getCreatedAt(), ZoneOffset.UTC);
                LocalDateTime processedAt = LocalDateTime.ofInstant(transaction.getProcessedAt(), ZoneOffset.UTC);
                
                // Insert transaction into database
                String sql = "INSERT INTO transactions (id, account_id, amount, type, status, created_at, processed_at, retry_count) " +
                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) " +
                            "ON CONFLICT (id) DO UPDATE SET " +
                            "status = EXCLUDED.status, " +
                            "processed_at = EXCLUDED.processed_at, " +
                            "retry_count = transactions.retry_count + 1";
                
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(
                        transaction.getId(),
                        transaction.getAccountId(),
                        transaction.getAmount(),
                        transaction.getType(),
                        transaction.getStatus(),
                        createdAt,
                        processedAt,
                        transaction.getRetryCount()
                    ))
                    .compose(result -> {
                        log.info("✅ Transaction processed successfully: transactionId={}, amount={}", 
                            event.getTransactionId(), event.getAmount());
                        
                        transactionsProcessed.incrementAndGet();
                        circuitBreaker.recordSuccess();
                        return Future.succeededFuture();
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> (Void) null)
            .exceptionally(ex -> {
                log.error("❌ Failed to process transaction: transactionId={}", event.getTransactionId(), ex);
                transactionsFailed.incrementAndGet();
                
                // Re-throw to trigger retry
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException("Transaction processing failed", ex);
            });
    }
    
    // Metrics getters
    public long getTransactionsProcessed() {
        return transactionsProcessed.get();
    }
    
    public long getTransactionsFailed() {
        return transactionsFailed.get();
    }
    
    public long getTransactionsRetried() {
        return transactionsRetried.get();
    }
    
    public long getPermanentFailures() {
        return permanentFailures.get();
    }
}

