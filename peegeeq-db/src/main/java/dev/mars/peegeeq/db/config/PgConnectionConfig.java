package dev.mars.peegeeq.db.config;

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


import java.util.Objects;

/**
 * Configuration for PostgreSQL database connections.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgConnectionConfig {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String schema;
    private final boolean sslEnabled;
    
    private PgConnectionConfig(Builder builder) {
        this.host = Objects.requireNonNull(builder.host, "Host cannot be null");
        this.port = builder.port;
        this.database = Objects.requireNonNull(builder.database, "Database cannot be null");
        this.username = Objects.requireNonNull(builder.username, "Username cannot be null");
        this.password = builder.password;
        this.schema = builder.schema;
        this.sslEnabled = builder.sslEnabled;
    }
    
    public String getHost() {
        return host;
    }
    
    public int getPort() {
        return port;
    }
    
    public String getDatabase() {
        return database;
    }
    
    public String getUsername() {
        return username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public String getSchema() {
        return schema;
    }
    
    public boolean isSslEnabled() {
        return sslEnabled;
    }
    
    /**
     * Creates a JDBC URL for this configuration.
     *
     * @return The JDBC URL
     */
    public String getJdbcUrl() {
        StringBuilder url = new StringBuilder();
        url.append("jdbc:postgresql://")
           .append(host)
           .append(":")
           .append(port)
           .append("/")
           .append(database);
        
        // Add query parameters
        boolean hasParams = false;
        
        if (schema != null && !schema.isEmpty()) {
            url.append("?currentSchema=").append(schema);
            hasParams = true;
        }
        
        if (sslEnabled) {
            url.append(hasParams ? "&" : "?").append("ssl=true");
        }
        
        return url.toString();
    }
    
    /**
     * Builder for PgConnectionConfig.
     */
    public static class Builder {
        private String host = "localhost";
        private int port = 5432;
        private String database;
        private String username;
        private String password;
        private String schema;
        private boolean sslEnabled = false;
        
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder database(String database) {
            this.database = database;
            return this;
        }
        
        public Builder username(String username) {
            this.username = username;
            return this;
        }
        
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }
        
        public Builder sslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
            return this;
        }
        
        public PgConnectionConfig build() {
            return new PgConnectionConfig(this);
        }
    }
}