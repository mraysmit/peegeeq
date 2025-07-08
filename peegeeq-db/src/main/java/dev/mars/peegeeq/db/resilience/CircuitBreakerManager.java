package dev.mars.peegeeq.db.resilience;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Manages circuit breakers for PeeGeeQ operations.
 * Provides resilience patterns to prevent cascading failures.
 */
public class CircuitBreakerManager {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerManager.class);
    
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers;
    private final PeeGeeQConfiguration.CircuitBreakerConfig config;
    private final boolean enabled;
    
    public CircuitBreakerManager(PeeGeeQConfiguration.CircuitBreakerConfig config, MeterRegistry meterRegistry) {
        this.config = config;
        this.enabled = config.isEnabled();
        this.circuitBreakers = new ConcurrentHashMap<>();
        
        if (enabled) {
            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) config.getFailureRateThreshold())
                .waitDurationInOpenState(config.getWaitDuration())
                .slidingWindowSize(config.getRingBufferSize())
                .minimumNumberOfCalls(config.getFailureThreshold())
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(Exception.class)
                .build();
            
            this.circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);
            
            // Register metrics
            if (meterRegistry != null) {
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry)
                    .bindTo(meterRegistry);
            }
            
            logger.info("Circuit breaker manager initialized with config: {}", config);
        } else {
            this.circuitBreakerRegistry = null;
            logger.info("Circuit breaker is disabled");
        }
    }
    
    /**
     * Gets or creates a circuit breaker for the specified operation.
     */
    public CircuitBreaker getCircuitBreaker(String name) {
        if (!enabled) {
            return null;
        }
        
        return circuitBreakers.computeIfAbsent(name, key -> {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(key);
            
            // Add event listeners
            cb.getEventPublisher()
                .onStateTransition(event -> 
                    logger.info("Circuit breaker '{}' state transition: {} -> {}", 
                        key, event.getStateTransition().getFromState(), 
                        event.getStateTransition().getToState()))
                .onFailureRateExceeded(event ->
                    logger.warn("Circuit breaker '{}' failure rate exceeded: {}%", 
                        key, event.getFailureRate()))
                .onCallNotPermitted(event ->
                    logger.debug("Circuit breaker '{}' call not permitted", key))
                .onError(event ->
                    logger.debug("Circuit breaker '{}' recorded error: {}", 
                        key, event.getThrowable().getMessage()));
            
            logger.info("Created circuit breaker: {}", key);
            return cb;
        });
    }
    
    /**
     * Executes a supplier with circuit breaker protection.
     */
    public <T> T executeSupplier(String circuitBreakerName, Supplier<T> supplier) {
        if (!enabled) {
            return supplier.get();
        }
        
        CircuitBreaker circuitBreaker = getCircuitBreaker(circuitBreakerName);
        Supplier<T> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        
        return decoratedSupplier.get();
    }
    
    /**
     * Executes a runnable with circuit breaker protection.
     */
    public void executeRunnable(String circuitBreakerName, Runnable runnable) {
        if (!enabled) {
            runnable.run();
            return;
        }
        
        CircuitBreaker circuitBreaker = getCircuitBreaker(circuitBreakerName);
        Runnable decoratedRunnable = CircuitBreaker.decorateRunnable(circuitBreaker, runnable);
        
        decoratedRunnable.run();
    }
    
    /**
     * Database operation circuit breaker.
     */
    public <T> T executeDatabaseOperation(String operation, Supplier<T> supplier) {
        return executeSupplier("database-" + operation, supplier);
    }
    
    /**
     * Queue operation circuit breaker.
     */
    public <T> T executeQueueOperation(String queueType, String operation, Supplier<T> supplier) {
        return executeSupplier(queueType + "-" + operation, supplier);
    }
    
    /**
     * Gets circuit breaker metrics for monitoring.
     */
    public CircuitBreakerMetrics getMetrics(String name) {
        if (!enabled) {
            return CircuitBreakerMetrics.disabled();
        }
        
        CircuitBreaker cb = circuitBreakers.get(name);
        if (cb == null) {
            return CircuitBreakerMetrics.notFound();
        }
        
        CircuitBreaker.Metrics metrics = cb.getMetrics();
        return new CircuitBreakerMetrics(
            cb.getState().toString(),
            metrics.getNumberOfSuccessfulCalls(),
            metrics.getNumberOfFailedCalls(),
            metrics.getFailureRate(),
            metrics.getNumberOfNotPermittedCalls()
        );
    }
    
    /**
     * Gets all circuit breaker names.
     */
    public java.util.Set<String> getCircuitBreakerNames() {
        return circuitBreakers.keySet();
    }
    
    /**
     * Resets a circuit breaker to closed state.
     */
    public void reset(String name) {
        if (!enabled) {
            return;
        }
        
        CircuitBreaker cb = circuitBreakers.get(name);
        if (cb != null) {
            cb.reset();
            logger.info("Reset circuit breaker: {}", name);
        }
    }
    
    /**
     * Forces a circuit breaker to open state.
     */
    public void forceOpen(String name) {
        if (!enabled) {
            return;
        }
        
        CircuitBreaker cb = circuitBreakers.get(name);
        if (cb != null) {
            cb.transitionToOpenState();
            logger.info("Forced circuit breaker to open: {}", name);
        }
    }
    
    /**
     * Circuit breaker metrics data class.
     */
    public static class CircuitBreakerMetrics {
        private final String state;
        private final long successfulCalls;
        private final long failedCalls;
        private final float failureRate;
        private final long notPermittedCalls;
        private final boolean enabled;
        
        public CircuitBreakerMetrics(String state, long successfulCalls, long failedCalls, 
                                   float failureRate, long notPermittedCalls) {
            this.state = state;
            this.successfulCalls = successfulCalls;
            this.failedCalls = failedCalls;
            this.failureRate = failureRate;
            this.notPermittedCalls = notPermittedCalls;
            this.enabled = true;
        }
        
        private CircuitBreakerMetrics(boolean enabled) {
            this.enabled = enabled;
            this.state = enabled ? "UNKNOWN" : "DISABLED";
            this.successfulCalls = 0;
            this.failedCalls = 0;
            this.failureRate = 0;
            this.notPermittedCalls = 0;
        }
        
        public static CircuitBreakerMetrics disabled() {
            return new CircuitBreakerMetrics(false);
        }
        
        public static CircuitBreakerMetrics notFound() {
            return new CircuitBreakerMetrics(true);
        }
        
        // Getters
        public String getState() { return state; }
        public long getSuccessfulCalls() { return successfulCalls; }
        public long getFailedCalls() { return failedCalls; }
        public float getFailureRate() { return failureRate; }
        public long getNotPermittedCalls() { return notPermittedCalls; }
        public boolean isEnabled() { return enabled; }
        public long getTotalCalls() { return successfulCalls + failedCalls; }
        
        @Override
        public String toString() {
            return "CircuitBreakerMetrics{" +
                    "state='" + state + '\'' +
                    ", successfulCalls=" + successfulCalls +
                    ", failedCalls=" + failedCalls +
                    ", failureRate=" + failureRate +
                    ", notPermittedCalls=" + notPermittedCalls +
                    ", enabled=" + enabled +
                    '}';
        }
    }
}
