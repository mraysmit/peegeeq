package dev.mars.peegeeq.examples.springbootbitemporal.config;

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
 * Configuration properties for the bi-temporal example application.
 *
 * <p>This class follows Pattern 1 (Full Spring Boot Integration) where database
 * properties are included in the @ConfigurationProperties class and bridged to
 * PeeGeeQConfiguration via the configureSystemProperties() method.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
@Component
@ConfigurationProperties(prefix = "bitemporal")
public class BitemporalProperties {

    private String profile = "development";
    private boolean enableRealTimeSubscriptions = true;
    private int queryPageSize = 100;
    private Database database = new Database();

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public boolean isEnableRealTimeSubscriptions() {
        return enableRealTimeSubscriptions;
    }

    public void setEnableRealTimeSubscriptions(boolean enableRealTimeSubscriptions) {
        this.enableRealTimeSubscriptions = enableRealTimeSubscriptions;
    }

    public int getQueryPageSize() {
        return queryPageSize;
    }

    public void setQueryPageSize(int queryPageSize) {
        this.queryPageSize = queryPageSize;
    }

    public Database getDatabase() {
        return database;
    }

    public void setDatabase(Database database) {
        this.database = database;
    }

    /**
     * Database configuration properties.
     */
    public static class Database {
        private String host = "localhost";
        private int port = 5432;
        private String name = "peegeeq_bitemporal";
        private String username = "postgres";
        private String password = "password";
        private String schema = "public";

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
}

