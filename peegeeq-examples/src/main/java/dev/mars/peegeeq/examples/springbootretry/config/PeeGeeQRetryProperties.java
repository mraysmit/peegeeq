package dev.mars.peegeeq.examples.springbootretry.config;

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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for PeeGeeQ Retry example.
 */
@ConfigurationProperties(prefix = "peegeeq.retry")
public class PeeGeeQRetryProperties {
    
    private String queueName = "transaction-events";
    private int maxRetries = 5;
    private long pollingIntervalMs = 500;
    private long initialBackoffMs = 1000;
    private double backoffMultiplier = 2.0;
    private long maxBackoffMs = 60000;
    
    // Circuit breaker settings
    private int circuitBreakerThreshold = 10;
    private long circuitBreakerWindowMs = 60000;
    private long circuitBreakerResetMs = 30000;
    
    // Database settings
    private DatabaseProperties database = new DatabaseProperties();
    
    public static class DatabaseProperties {
        private String host = "localhost";
        private int port = 5432;
        private String name = "peegeeq";
        private String username = "postgres";
        private String password = "postgres";
        
        // Getters and setters
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
    
    // Getters and setters
    public String getQueueName() { return queueName; }
    public void setQueueName(String queueName) { this.queueName = queueName; }
    
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    
    public long getPollingIntervalMs() { return pollingIntervalMs; }
    public void setPollingIntervalMs(long pollingIntervalMs) { this.pollingIntervalMs = pollingIntervalMs; }
    
    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
    
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    
    public long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
    
    public int getCircuitBreakerThreshold() { return circuitBreakerThreshold; }
    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) { 
        this.circuitBreakerThreshold = circuitBreakerThreshold; 
    }
    
    public long getCircuitBreakerWindowMs() { return circuitBreakerWindowMs; }
    public void setCircuitBreakerWindowMs(long circuitBreakerWindowMs) { 
        this.circuitBreakerWindowMs = circuitBreakerWindowMs; 
    }
    
    public long getCircuitBreakerResetMs() { return circuitBreakerResetMs; }
    public void setCircuitBreakerResetMs(long circuitBreakerResetMs) { 
        this.circuitBreakerResetMs = circuitBreakerResetMs; 
    }
    
    public DatabaseProperties getDatabase() { return database; }
    public void setDatabase(DatabaseProperties database) { this.database = database; }
}

