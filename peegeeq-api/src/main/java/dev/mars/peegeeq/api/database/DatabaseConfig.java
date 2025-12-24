package dev.mars.peegeeq.api.database;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DatabaseConfig {
    private final String host;
    private final int port;
    private final String databaseName;
    private final String username;
    private final String password;
    private final String schema;
    private final boolean sslEnabled;
    private final String templateDatabase;
    private final String encoding;
    private final ConnectionPoolConfig poolConfig;

    @JsonCreator
    public DatabaseConfig(@JsonProperty("host") String host,
                         @JsonProperty("port") int port,
                         @JsonProperty("databaseName") String databaseName,
                         @JsonProperty("username") String username,
                         @JsonProperty("password") String password,
                         @JsonProperty("schema") String schema,
                         @JsonProperty("sslEnabled") boolean sslEnabled,
                         @JsonProperty("templateDatabase") String templateDatabase,
                         @JsonProperty("encoding") String encoding,
                         @JsonProperty("poolConfig") ConnectionPoolConfig poolConfig) {
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.schema = schema;  // No default - schema must be explicitly configured
        this.sslEnabled = sslEnabled;
        this.templateDatabase = templateDatabase;
        this.encoding = encoding != null ? encoding : "UTF8";
        this.poolConfig = poolConfig;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabaseName() { return databaseName; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getSchema() { return schema; }
    public boolean isSslEnabled() { return sslEnabled; }
    public String getTemplateDatabase() { return templateDatabase; }
    public String getEncoding() { return encoding; }
    public ConnectionPoolConfig getPoolConfig() { return poolConfig; }

    public static class Builder {
        private String host = "localhost";
        private int port = 5432;
        private String databaseName;
        private String username;
        private String password;
        private String schema;  // No default - schema must be explicitly configured
        private boolean sslEnabled = false;
        private String templateDatabase;
        private String encoding = "UTF8";
        private ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder databaseName(String databaseName) { this.databaseName = databaseName; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder schema(String schema) { this.schema = schema; return this; }
        public Builder sslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; return this; }
        public Builder templateDatabase(String templateDatabase) { this.templateDatabase = templateDatabase; return this; }
        public Builder encoding(String encoding) { this.encoding = encoding; return this; }
        public Builder poolConfig(ConnectionPoolConfig poolConfig) { this.poolConfig = poolConfig; return this; }

        public DatabaseConfig build() {
            return new DatabaseConfig(host, port, databaseName, username, password,
                                    schema, sslEnabled, templateDatabase, encoding, poolConfig);
        }
    }
}