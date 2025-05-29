package dev.mars.peegeeq.db.config;

import java.util.Objects;

/**
 * Configuration for PostgreSQL database connections.
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