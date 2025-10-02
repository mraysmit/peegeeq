package dev.mars.peegeeq.examples.springbootdlq.service;

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
import dev.mars.peegeeq.examples.springbootdlq.config.PeeGeeQDlqProperties;
import dev.mars.peegeeq.examples.springbootdlq.events.PaymentEvent;
import dev.mars.peegeeq.examples.springbootdlq.model.Payment;
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
 * Payment Processor Service with DLQ support.
 * 
 * Demonstrates the CORRECT way to process messages with retry and DLQ:
 * - Subscribes to payment events queue
 * - Processes payments with simulated failures
 * - Automatic retry on failure (up to max retries)
 * - Failed messages automatically move to DLQ after max retries
 * - Tracks metrics for successful/failed/retried messages
 * 
 * Key Principles:
 * 1. Let PeeGeeQ handle retry logic automatically
 * 2. Throw exceptions for transient failures (will be retried)
 * 3. After max retries, message moves to DLQ automatically
 * 4. Track retry count in database for monitoring
 * 5. Log all failures for debugging
 */
@Service
public class PaymentProcessorService {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessorService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final MessageConsumer<PaymentEvent> consumer;
    private final DatabaseService databaseService;
    private final PeeGeeQDlqProperties properties;
    
    // Metrics
    private final AtomicLong paymentsProcessed = new AtomicLong(0);
    private final AtomicLong paymentsFailed = new AtomicLong(0);
    private final AtomicLong paymentsRetried = new AtomicLong(0);
    
    public PaymentProcessorService(
            MessageConsumer<PaymentEvent> consumer,
            DatabaseService databaseService,
            PeeGeeQDlqProperties properties) {
        this.consumer = consumer;
        this.databaseService = databaseService;
        this.properties = properties;
    }
    
    /**
     * Start processing payments when the application is ready.
     * Uses ApplicationReadyEvent to ensure schema is initialized first.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startProcessing() {
        log.info("Starting payment processing with max retries: {}", properties.getMaxRetries());
        consumer.subscribe(this::processPayment);
        log.info("Payment processor started successfully");
    }
    
    /**
     * Stop processing payments when the application is shutting down.
     * Properly closes the consumer to shut down background threads.
     */
    @PreDestroy
    public void stopProcessing() {
        log.info("Stopping payment processor");
        try {
            // Close consumer to shut down scheduler and background threads
            consumer.close();
            log.info("Payment processor stopped successfully");
        } catch (Exception e) {
            log.error("Error stopping payment processor", e);
        }
    }
    
    /**
     * Process payment message.
     * 
     * This method demonstrates the CORRECT pattern:
     * 1. Extract event from message
     * 2. Process in transaction
     * 3. Throw exception on failure (PeeGeeQ will retry)
     * 4. Return CompletableFuture for acknowledgment
     */
    private CompletableFuture<Void> processPayment(Message<PaymentEvent> message) {
        PaymentEvent event = message.getPayload();
        
        log.debug("Processing payment: paymentId={}, amount={}", 
            event.getPaymentId(), event.getAmount());
        
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                // Create payment from event
                Payment payment = new Payment(
                    event.getPaymentId(),
                    event.getOrderId(),
                    event.getAmount(),
                    event.getCurrency(),
                    event.getPaymentMethod()
                );
                
                // Simulate payment processing failure for testing
                if (event.isShouldFail()) {
                    paymentsRetried.incrementAndGet();
                    log.warn("⚠️ Payment processing failed (simulated): paymentId={}", event.getPaymentId());
                    throw new RuntimeException("Payment gateway timeout (simulated failure)");
                }
                
                // Process payment successfully
                payment.setStatus("COMPLETED");
                payment.setProcessedAt(Instant.now());
                
                // Convert Instant to LocalDateTime for PostgreSQL TIMESTAMP
                LocalDateTime createdAt = LocalDateTime.ofInstant(payment.getCreatedAt(), ZoneOffset.UTC);
                LocalDateTime processedAt = LocalDateTime.ofInstant(payment.getProcessedAt(), ZoneOffset.UTC);
                
                // Insert payment into database
                String sql = "INSERT INTO payments (id, order_id, amount, currency, payment_method, status, created_at, processed_at, retry_count) " +
                            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) " +
                            "ON CONFLICT (id) DO UPDATE SET " +
                            "status = EXCLUDED.status, " +
                            "processed_at = EXCLUDED.processed_at, " +
                            "retry_count = payments.retry_count + 1";
                
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getPaymentMethod(),
                        payment.getStatus(),
                        createdAt,
                        processedAt,
                        payment.getRetryCount()
                    ))
                    .compose(result -> {
                        log.info("✅ Payment processed successfully: paymentId={}, amount={}", 
                            event.getPaymentId(), event.getAmount());
                        
                        paymentsProcessed.incrementAndGet();
                        return Future.succeededFuture();
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(v -> (Void) null)
            .exceptionally(ex -> {
                log.error("❌ Failed to process payment: paymentId={}", event.getPaymentId(), ex);
                paymentsFailed.incrementAndGet();
                
                // Re-throw to trigger retry
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException("Payment processing failed", ex);
            });
    }
    
    // Metrics getters
    public long getPaymentsProcessed() {
        return paymentsProcessed.get();
    }
    
    public long getPaymentsFailed() {
        return paymentsFailed.get();
    }
    
    public long getPaymentsRetried() {
        return paymentsRetried.get();
    }
}

