package dev.mars.peegeeq.examples.patterns.architecture;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

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
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DistributedSystemResilienceDemoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

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
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("requestId", requestId)
                    .put("serviceId", serviceId)
                    .put("success", success)
                    .put("result", result)
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
            
            // Simulate processing delay (minimal for testing)
            if (processingDelayMs > 0) {
                try {
                    Thread.sleep(processingDelayMs);
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

                    // Use CompletableFuture for delay
                    try {
                        CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).get();
                    } catch (Exception e) {
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
    void setUp() {
        System.out.println("\n🛡️ Setting up Distributed System Resilience Demo Test");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer(postgres);

        // Initialize PeeGeeQ with resilience configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for distributed system resilience pattern testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Distributed System Resilience Demo Test");
        
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                System.err.println("⚠️ Error during manager cleanup: " + e.getMessage());
            }
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        System.out.println("✅ Cleanup complete");
    }

    @Test
    @Order(1)
    @DisplayName("Circuit Breaker Pattern - Preventing Cascade Failures")
    void testCircuitBreakerPattern() throws Exception {
        System.out.println("\n🛡️ Testing Circuit Breaker Pattern");

        String requestQueue = "resilience-request-queue";
        String responseQueue = "resilience-response-queue";
        
        Map<String, ServiceResponse> responses = new HashMap<>();
        ResilientService service = new ResilientService("payment-service", 3, 5000); // 3 failures, 5s timeout
        AtomicInteger requestsProcessed = new AtomicInteger(0);
        AtomicInteger responsesReceived = new AtomicInteger(0);
        CountDownLatch requestLatch = new CountDownLatch(10);
        CountDownLatch responseLatch = new CountDownLatch(10);

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
            
            System.out.println("🛡️ Processing request: " + request.requestId + 
                             " (Circuit state: " + service.circuitBreaker.state + ")");
            
            ServiceResponse response = service.processRequest(request);
            responseProducer.send(response);
            
            requestsProcessed.incrementAndGet();
            requestLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Response collector
        responseConsumer.subscribe(message -> {
            ServiceResponse response = message.getPayload();
            
            System.out.println("🛡️ Received response: " + response.requestId + 
                             " (Success: " + response.success + 
                             ", Error: " + response.errorMessage + ")");
            
            responses.put(response.requestId, response);
            responsesReceived.incrementAndGet();
            responseLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send requests to trigger circuit breaker
        System.out.println("📤 Sending requests to trigger circuit breaker...");

        List<CompletableFuture<Void>> sendTasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final int requestId = i;
            CompletableFuture<Void> sendTask = CompletableFuture.runAsync(() -> {
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
                requestProducer.send(request);

                // Small delay to see circuit breaker behavior progression
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            sendTasks.add(sendTask);
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(sendTasks.toArray(new CompletableFuture[0])).join();

        // Wait for all processing
        assertTrue(requestLatch.await(30, TimeUnit.SECONDS), "Should process all requests");
        assertTrue(responseLatch.await(30, TimeUnit.SECONDS), "Should receive all responses");

        // Verify circuit breaker behavior
        assertEquals(10, requestsProcessed.get(), "Should have processed 10 requests");
        assertEquals(10, responsesReceived.get(), "Should have received 10 responses");

        // Count successes and failures
        long successCount = responses.values().stream().mapToLong(r -> r.success ? 1 : 0).sum();
        long failureCount = responses.values().stream().mapToLong(r -> r.success ? 0 : 1).sum();
        long circuitBreakerFailures = responses.values().stream()
                .mapToLong(r -> r.errorMessage != null && r.errorMessage.contains("Circuit breaker") ? 1 : 0)
                .sum();

        System.out.println("📊 Circuit Breaker Results:");
        System.out.println("  Total requests: " + requestsProcessed.get());
        System.out.println("  Successful responses: " + successCount);
        System.out.println("  Failed responses: " + failureCount);
        System.out.println("  Circuit breaker rejections: " + circuitBreakerFailures);
        System.out.println("  Final circuit state: " + service.circuitBreaker.state);
        System.out.println("  Service metrics: " + service.getMetrics().encodePrettily());

        // Verify circuit breaker opened
        assertTrue(circuitBreakerFailures > 0, "Circuit breaker should have rejected some requests");
        assertTrue(service.circuitBreaker.failureCount.get() >= 3, "Should have recorded failures");

        // Cleanup
        requestConsumer.close();
        responseConsumer.close();

        System.out.println("✅ Circuit Breaker Pattern test completed successfully");
    }

    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
    }
}
