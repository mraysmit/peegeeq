package dev.mars.peegeeq.examples.springboot.config;

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

import java.time.Duration;

/**
 * Spring Boot Configuration Properties for PeeGeeQ.
 * 
 * This class maps Spring Boot application properties to PeeGeeQ configuration
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * Properties are mapped from the 'peegeeq' prefix in application.yml or application.properties.
 * 
 * Example configuration:
 * <pre>
 * peegeeq:
 *   profile: production
 *   database:
 *     host: localhost
 *     port: 5432
 *     name: myapp
 *     username: myapp_user
 *     password: secret
 *   pool:
 *     max-size: 20
 *     min-size: 5
 *   queue:
 *     max-retries: 3
 *     visibility-timeout: PT30S
 *     batch-size: 10
 *     polling-interval: PT1S
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@ConfigurationProperties(prefix = "peegeeq")
public class PeeGeeQProperties {

    private String profile = "production";
    private Database database = new Database();
    private Pool pool = new Pool();
    private Queue queue = new Queue();

    // Getters and setters
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public Database getDatabase() { return database; }
    public void setDatabase(Database database) { this.database = database; }

    public Pool getPool() { return pool; }
    public void setPool(Pool pool) { this.pool = pool; }

    public Queue getQueue() { return queue; }
    public void setQueue(Queue queue) { this.queue = queue; }

    /**
     * Database configuration properties.
     */
    public static class Database {
        private String host = "localhost";
        private int port = 5432;
        private String name = "peegeeq";
        private String username = "peegeeq";
        private String password = "";
        private String schema = "public";

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

        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
    }

    /**
     * Connection pool configuration properties.
     */
    public static class Pool {
        private int maxSize = 20;
        private int minSize = 5;

        // Getters and setters
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

        public int getMinSize() { return minSize; }
        public void setMinSize(int minSize) { this.minSize = minSize; }
    }

    /**
     * Queue configuration properties.
     */
    public static class Queue {
        private int maxRetries = 3;
        private Duration visibilityTimeout = Duration.ofSeconds(30);
        private int batchSize = 10;
        private Duration pollingInterval = Duration.ofSeconds(1);

        // Getters and setters
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public Duration getVisibilityTimeout() { return visibilityTimeout; }
        public void setVisibilityTimeout(Duration visibilityTimeout) { this.visibilityTimeout = visibilityTimeout; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public Duration getPollingInterval() { return pollingInterval; }
        public void setPollingInterval(Duration pollingInterval) { this.pollingInterval = pollingInterval; }
    }
}
