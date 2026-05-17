package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.peegeeq.test.util.FutureTestHelper.awaitFuture;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo test showcasing Distributed System Resilience Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Circuit Breaker Pattern - Preventing cascade failures
 * 2. Bulkhead Pattern - Isolating critical resources
 * 3. Timeout Pattern - Preventing resource exhaustion
 * 4. Retry Pattern - Handling transient failures
 * 5. Fallback Pattern - Graceful degradation
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class DistributedSystemResilienceDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(DistributedSystemResilienceDemoTest.class);


    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Resilience patterns
    enum ResiliencePattern {
        CIRCUIT_BREAKER("circuit-breaker", "Prevent cascade failures"),
        BULKHEAD("bulkhead", "Isolate critical resources"),
        TIMEOUT("timeout", "Prevent resource exhaustion"),
        RETRY("retry", "Handle transient failures"),
        FALLBACK("fallback", "Graceful degradation");

        final String patternName;
        final String description;

        ResiliencePattern(String patternName, String description) {
            this.patternName = patternName;
            this.description = description;
        }
    }

    // Service request for resilience testing
    static class ServiceRequest {
        public String requestId;
        public String serviceId;
        public String operation;
        public Map<String, Object> parameters;
        public String timestamp;
        public int timeoutMs;
        public int maxRetries;

        // Default constructor for Jackson
        public ServiceRequest() {
        }

        public ServiceRequest(String requestId, String serviceId, String operation,
                             Map<String, Object> parameters, int timeoutMs, int maxRetries) {
            this.requestId = requestId;
            this.serviceId = serviceId;
            this.operation = operation;
            this.parameters = parameters;
            this.timestamp = Instant.now().toString();
            this.timeoutMs = timeoutMs;
            this.maxRetries = maxRetries;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("requestId", requestId)
                    .put("serviceId", serviceId)
                    .put("operation", operation)
                    .put("parameters", parameters)
                    .put("timestamp", timestamp)
                    .put("timeoutMs", timeoutMs)
                    .put("maxRetries", maxRetries);
        }
    }

    // Service response for resilience testing
    static class ServiceResponse {
        public String requestId;
        public String serviceId;
        public boolean success;
        public Map<String, Object> result;
        public String errorMessage;
        public long processingTimeMs;
        public String timestamp;

        // Default constructor for Jackson
        public ServiceResponse() {
        }

        public ServiceResponse(String requestId, String serviceId, boolean success,
                              Map<String, Object> result, String errorMessage, long processingTimeMs) {
            this.requestId = requestId;
            this.serviceId = serviceId;
            this.success = success;
            this.result = result;
            this.errorMessage = errorMessage;
            this.processingTimeMs = processingTimeMs;
            this.timestamp = Instant.now().toString();
            this.timestamp = Instant.now().toString();
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("requestId", requestId)
                    .put("serviceId", serviceId)
                    .put("success", success)
                    .put("result", result != null ? new JsonObject(result) : null)
                    .put("errorMessage", errorMessage)
                    .put("processingTimeMs", processingTimeMs)
                    .put("timestamp", timestamp);
        }
    }

    // Circuit breaker implementation
    static class CircuitBreaker {
        public enum State { CLOSED, OPEN, HALF_OPEN }
        
        public final String name;
        public volatile State state = State.CLOSED;
        public final AtomicInteger failureCount = new AtomicInteger(0);
        public final AtomicInteger successCount = new AtomicInteger(0);
        public final AtomicLong lastFailureTime = new AtomicLong(0);
        public final int failureThreshold;
        public final long timeoutMs;

        public CircuitBreaker(String name, int failureThreshold, long timeoutMs) {
            this.name = name;
            this.failureThreshold = failureThreshold;
            this.timeoutMs = timeoutMs;
        }

        public boolean canExecute() {
            if (state == State.CLOSED) {
                return true;
            } else if (state == State.OPEN) {
                if (System.currentTimeMillis() - lastFailureTime.get() > timeoutMs) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            } else { // HALF_OPEN
                return true;
            }
        }

        public void recordSuccess() {
            successCount.incrementAndGet();
            if (state == State.HALF_OPEN) {
                state = State.CLOSED;
                failureCount.set(0);
            }
        }

        public void recordFailure() {
            failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            
            if (failureCount.get() >= failureThreshold) {
                state = State.OPEN;
            }
        }

        public JsonObject getStatus() {
            return new JsonObject()
                    .put("name", name)
                    .put("state", state.toString())
                    .put("failureCount", failureCount.get())
                    .put("successCount", successCount.get())
                    .put("failureThreshold", failureThreshold);
        }
    }

    // Resilient service simulator
    static class ResilientService {
        public final String serviceId;
        public final CircuitBreaker circuitBreaker;
        public final AtomicInteger requestCount = new AtomicInteger(0);
        public final AtomicInteger successCount = new AtomicInteger(0);
        public final AtomicInteger failureCount = new AtomicInteger(0);
        public final AtomicInteger timeoutCount = new AtomicInteger(0);
        public volatile double failureRate = 0.0; // Configurable failure rate for testing
        public volatile long processingDelayMs = 100; // Configurable processing delay

        public ResilientService(String serviceId, int failureThreshold, long circuitTimeoutMs) {
            this.serviceId = serviceId;
            this.circuitBreaker = new CircuitBreaker(serviceId + "-circuit", failureThreshold, circuitTimeoutMs);
        }

        public ServiceResponse processRequest(ServiceRequest request) {
            requestCount.incrementAndGet();
            long startTime = System.currentTimeMillis();
            
            // Check circuit breaker
            if (!circuitBreaker.canExecute()) {
                failureCount.incrementAndGet();
                return new ServiceResponse(
                    request.requestId, serviceId, false, null,
                    "Circuit breaker is OPEN", 0
                );
            }
            
            try {
                // Simulate processing delay
                new CountDownLatch(1).await(processingDelayMs, TimeUnit.MILLISECONDS);
                
                // Simulate failures based on failure rate
                boolean shouldFail = Math.random() < failureRate;
                
                if (shouldFail) {
                    failureCount.incrementAndGet();
                    circuitBreaker.recordFailure();
                    return new ServiceResponse(
                        request.requestId, serviceId, false, null,
                        "Simulated service failure", System.currentTimeMillis() - startTime
                    );
                } else {
                    successCount.incrementAndGet();
                    circuitBreaker.recordSuccess();
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("processed", true);
                    result.put("operation", request.operation);
                    result.put("serviceId", serviceId);
                    result.put("processingTime", System.currentTimeMillis() - startTime);

                    return new ServiceResponse(
                        request.requestId, serviceId, true, result, null,
                        System.currentTimeMillis() - startTime
                    );
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                timeoutCount.incrementAndGet();
                circuitBreaker.recordFailure();
                return new ServiceResponse(
                    request.requestId, serviceId, false, null,
                    "Request interrupted", System.currentTimeMillis() - startTime
                );
            }
        }

        public JsonObject getMetrics() {
            return new JsonObject()
                    .put("serviceId", serviceId)
                    .put("requestCount", requestCount.get())
                    .put("successCount", successCount.get())
                    .put("failureCount", failureCount.get())
                    .put("timeoutCount", timeoutCount.get())
                    .put("successRate", requestCount.get() > 0 ? (double) successCount.get() / requestCount.get() : 0.0)
                    .put("circuitBreaker", circuitBreaker.getStatus());
        }
    }

    // Retry handler with exponential backoff
    static class RetryHandler {
        public final int maxRetries;
        public final long baseDelayMs;
        public final double backoffMultiplier;

        public RetryHandler(int maxRetries, long baseDelayMs, double backoffMultiplier) {
            this.maxRetries = maxRetries;
            this.baseDelayMs = baseDelayMs;
            this.backoffMultiplier = backoffMultiplier;
        }

        public ServiceResponse executeWithRetry(ServiceRequest request, ResilientService service) {
            ServiceResponse lastResponse = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                if (attempt > 0) {
                    // Calculate exponential backoff delay
                    long delay = (long) (baseDelayMs * Math.pow(backoffMultiplier, attempt - 1));

                    try {
                        new CountDownLatch(1).await(delay, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                lastResponse = service.processRequest(request);

                if (lastResponse.success) {
                    return lastResponse; // Success, no need to retry
                }

                // Don't retry if circuit breaker is open
                if (lastResponse.errorMessage != null && lastResponse.errorMessage.contains("Circuit breaker")) {
                    break;
                }
            }

            return lastResponse;
        }
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up Distributed System Resilience Demo Test");
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                var databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                queueFactory = provider.createFactory("native", databaseService);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Cleaning up Distributed System Resilience Demo Test");
        if (manager == null) { testContext.completeNow(); return; }
        manager.closeReactive()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.warn("Error during manager cleanup: {}", err.getMessage());
                testContext.completeNow();
            });

        // Clean up system properties
        logger.info("Cleanup complete");
    }

    @Test
    @DisplayName("Circuit Breaker Pattern - Preventing Cascade Failures")
    void testCircuitBreakerPattern(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: circuit breaker pattern");
        logger.info("Testing Circuit Breaker Pattern");

        String requestQueue = "resilience-request-queue";
        String responseQueue = "resilience-response-queue";

        Map<String, ServiceResponse> responses = new HashMap<>();
        ResilientService service = new ResilientService("payment-service", 3, 5000); // 3 failures, 5s timeout
        AtomicInteger requestsProcessed = new AtomicInteger(0);
        AtomicInteger responsesReceived = new AtomicInteger(0);
        var requestCheckpoint = testContext.checkpoint(10);
        var responseCheckpoint = testContext.checkpoint(10);

        // Set high failure rate to trigger circuit breaker
        service.failureRate = 0.7; // 70% failure rate
        service.processingDelayMs = 50; // Fast processing

        // Create producers and consumers
        MessageProducer<ServiceRequest> requestProducer = queueFactory.createProducer(requestQueue, ServiceRequest.class);
        MessageConsumer<ServiceRequest> requestConsumer = queueFactory.createConsumer(requestQueue, ServiceRequest.class);
        MessageProducer<ServiceResponse> responseProducer = queueFactory.createProducer(responseQueue, ServiceResponse.class);
        MessageConsumer<ServiceResponse> responseConsumer = queueFactory.createConsumer(responseQueue, ServiceResponse.class);

        // Request processor with circuit breaker
        requestConsumer.subscribe(message -> {
            ServiceRequest request = message.getPayload();

            logger.info("Processing request: {} (Circuit state: {})", request.requestId, service.circuitBreaker.state);

            ServiceResponse response = service.processRequest(request);
            responseProducer.send(response);

            requestsProcessed.incrementAndGet();
            requestCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Response collector
        responseConsumer.subscribe(message -> {
            ServiceResponse response = message.getPayload();

            logger.info("Received response: {} (Success: {}, Error: {})", response.requestId, response.success, response.errorMessage);

            responses.put(response.requestId, response);
            responsesReceived.incrementAndGet();
            responseCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Send requests to trigger circuit breaker
        logger.info("Sending requests to trigger circuit breaker...");

        List<Future<Void>> sendTasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final int requestId = i;
            Map<String, Object> params = new HashMap<>();
            params.put("amount", 100.0 * requestId);
            params.put("customerId", "CUST-" + requestId);

            ServiceRequest request = new ServiceRequest(
                "req-" + String.format("%03d", requestId),
                "payment-service",
                "processPayment",
                params,
                1000, // 1 second timeout
                2     // 2 retries
            );
            sendTasks.add(requestProducer.send(request));
        }

        // Wait for all sends to complete
        awaitFuture(Future.all(sendTasks), 30, TimeUnit.SECONDS);

        // Wait for all processing
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should process all requests and responses");

        // Verify circuit breaker behavior
        assertEquals(10, requestsProcessed.get(), "Should have processed 10 requests");
        assertEquals(10, responsesReceived.get(), "Should have received 10 responses");

        // Count successes and failures
        long successCount = responses.values().stream().mapToLong(r -> r.success ? 1 : 0).sum();
        long failureCount = responses.values().stream().mapToLong(r -> r.success ? 0 : 1).sum();
        long circuitBreakerFailures = responses.values().stream()
                .mapToLong(r -> r.errorMessage != null && r.errorMessage.contains("Circuit breaker") ? 1 : 0)
                .sum();

        logger.info("Circuit Breaker Results:");
        logger.info("  Total requests: {}", requestsProcessed.get());
        logger.info("  Successful responses: {}", successCount);
        logger.info("  Failed responses: {}", failureCount);
        logger.info("  Circuit breaker rejections: {}", circuitBreakerFailures);
        logger.info("  Final circuit state: {}", service.circuitBreaker.state);
        logger.info("  Service metrics: {}", service.getMetrics().encodePrettily());

        // Verify circuit breaker opened
        assertTrue(circuitBreakerFailures > 0, "Circuit breaker should have rejected some requests");
        assertTrue(service.circuitBreaker.failureCount.get() >= 3, "Should have recorded failures");

        // Cleanup
        requestConsumer.close();
        responseConsumer.close();

        logger.info("Circuit Breaker Pattern test completed successfully");
    }


}


