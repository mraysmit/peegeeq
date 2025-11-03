package dev.mars.peegeeq.examples.springbootdlq.config;

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
import org.springframework.stereotype.Component;

/**
 * Configuration properties for PeeGeeQ DLQ example.
 */
@Component
@ConfigurationProperties(prefix = "peegeeq.dlq")
public class PeeGeeQDlqProperties {
    
    private String queueName = "payment-events";
    private int maxRetries = 3;
    private long pollingIntervalMs = 500;
    private int dlqAlertThreshold = 10;
    private boolean autoReprocessEnabled = false;
    
    // Database configuration
    private Database database = new Database();
    
    public static class Database {
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
    
    public int getDlqAlertThreshold() { return dlqAlertThreshold; }
    public void setDlqAlertThreshold(int dlqAlertThreshold) { this.dlqAlertThreshold = dlqAlertThreshold; }
    
    public boolean isAutoReprocessEnabled() { return autoReprocessEnabled; }
    public void setAutoReprocessEnabled(boolean autoReprocessEnabled) { this.autoReprocessEnabled = autoReprocessEnabled; }
    
    public Database getDatabase() { return database; }
    public void setDatabase(Database database) { this.database = database; }
}

