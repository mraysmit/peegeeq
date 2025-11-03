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

package dev.mars.peegeeq.examples.springbootintegrated.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the integrated example application.
 *
 * <p>This class follows Pattern 1 (Full Spring Boot Integration) where database
 * properties are included in the @ConfigurationProperties class and bridged to
 * PeeGeeQConfiguration via the configureSystemProperties() method.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Component
@ConfigurationProperties(prefix = "integrated")
public class IntegratedProperties {

    private String profile = "development";
    private Database database = new Database();

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
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
        private String name = "peegeeq_integrated";
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

