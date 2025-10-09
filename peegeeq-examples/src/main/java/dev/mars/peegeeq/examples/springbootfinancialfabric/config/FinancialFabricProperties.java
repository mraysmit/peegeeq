package dev.mars.peegeeq.examples.springbootfinancialfabric.config;

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
 * Configuration properties for Financial Fabric example.
 *
 * Follows the established DLQ/Retry pattern:
 * - Uses prefix "peegeeq.financial-fabric"
 * - Includes its own database configuration
 * - Contains domain-specific settings for event stores, CloudEvents, and routing
 */
@ConfigurationProperties(prefix = "peegeeq.financial-fabric")
public class FinancialFabricProperties {

    private Database database = new Database();
    private final EventStores eventStores = new EventStores();
    private final CloudEvents cloudevents = new CloudEvents();
    private final Routing routing = new Routing();

    public Database getDatabase() {
        return database;
    }

    public void setDatabase(Database database) {
        this.database = database;
    }

    public EventStores getEventStores() {
        return eventStores;
    }

    public CloudEvents getCloudevents() {
        return cloudevents;
    }

    public Routing getRouting() {
        return routing;
    }

    /**
     * Database configuration properties.
     */
    public static class Database {
        private String host = "localhost";
        private int port = 5432;
        private String name = "peegeeq";
        private String username = "peegeeq";
        private String password = "peegeeq";

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
    
    /**
     * Event store configuration for each domain.
     */
    public static class EventStores {
        private final EventStoreConfig trading = new EventStoreConfig("trading_events", true);
        private final EventStoreConfig settlement = new EventStoreConfig("settlement_events", true);
        private final EventStoreConfig cash = new EventStoreConfig("cash_events", true);
        private final EventStoreConfig position = new EventStoreConfig("position_events", true);
        private final EventStoreConfig regulatory = new EventStoreConfig("regulatory_events", true);
        
        public EventStoreConfig getTrading() {
            return trading;
        }
        
        public EventStoreConfig getSettlement() {
            return settlement;
        }
        
        public EventStoreConfig getCash() {
            return cash;
        }
        
        public EventStoreConfig getPosition() {
            return position;
        }
        
        public EventStoreConfig getRegulatory() {
            return regulatory;
        }
    }
    
    /**
     * Individual event store configuration.
     */
    public static class EventStoreConfig {
        private String tableName;
        private boolean enabled;
        
        public EventStoreConfig() {
        }
        
        public EventStoreConfig(String tableName, boolean enabled) {
            this.tableName = tableName;
            this.enabled = enabled;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
    
    /**
     * CloudEvents configuration.
     */
    public static class CloudEvents {
        private String source = "trading-system";
        private String specVersion = "1.0";
        private String dataContentType = "application/json";
        
        public String getSource() {
            return source;
        }
        
        public void setSource(String source) {
            this.source = source;
        }
        
        public String getSpecVersion() {
            return specVersion;
        }
        
        public void setSpecVersion(String specVersion) {
            this.specVersion = specVersion;
        }
        
        public String getDataContentType() {
            return dataContentType;
        }
        
        public void setDataContentType(String dataContentType) {
            this.dataContentType = dataContentType;
        }
    }
    
    /**
     * Event routing patterns configuration.
     */
    public static class Routing {
        private String highPriorityPattern = "*.*.*.*.high";
        private String failurePattern = "*.*.*.failed";
        private String regulatoryPattern = "regulatory.*.*";
        
        public String getHighPriorityPattern() {
            return highPriorityPattern;
        }
        
        public void setHighPriorityPattern(String highPriorityPattern) {
            this.highPriorityPattern = highPriorityPattern;
        }
        
        public String getFailurePattern() {
            return failurePattern;
        }
        
        public void setFailurePattern(String failurePattern) {
            this.failurePattern = failurePattern;
        }
        
        public String getRegulatoryPattern() {
            return regulatoryPattern;
        }
        
        public void setRegulatoryPattern(String regulatoryPattern) {
            this.regulatoryPattern = regulatoryPattern;
        }
    }
}

