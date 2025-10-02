package dev.mars.peegeeq.examples.springbootconsumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for PeeGeeQ Consumer.
 */
@ConfigurationProperties(prefix = "peegeeq")
public class PeeGeeQConsumerProperties {
    
    private String profile = "development";
    private DatabaseProperties database = new DatabaseProperties();
    private QueueProperties queue = new QueueProperties();
    
    public String getProfile() {
        return profile;
    }
    
    public void setProfile(String profile) {
        this.profile = profile;
    }
    
    public DatabaseProperties getDatabase() {
        return database;
    }
    
    public void setDatabase(DatabaseProperties database) {
        this.database = database;
    }
    
    public QueueProperties getQueue() {
        return queue;
    }
    
    public void setQueue(QueueProperties queue) {
        this.queue = queue;
    }
    
    public static class DatabaseProperties {
        private String host = "localhost";
        private int port = 5432;
        private String name = "peegeeq_consumer_example";
        private String username = "postgres";
        private String password = "password";
        private String schema = "public";
        private SslProperties ssl = new SslProperties();
        private PoolProperties pool = new PoolProperties();
        
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
        
        public SslProperties getSsl() { return ssl; }
        public void setSsl(SslProperties ssl) { this.ssl = ssl; }
        
        public PoolProperties getPool() { return pool; }
        public void setPool(PoolProperties pool) { this.pool = pool; }
    }
    
    public static class SslProperties {
        private boolean enabled = false;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
    
    public static class PoolProperties {
        private int maxSize = 10;
        private int minSize = 2;
        
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        
        public int getMinSize() { return minSize; }
        public void setMinSize(int minSize) { this.minSize = minSize; }
    }
    
    public static class QueueProperties {
        private String pollingInterval = "PT0.5S";
        private int maxRetries = 3;
        private int batchSize = 10;
        
        public String getPollingInterval() { return pollingInterval; }
        public void setPollingInterval(String pollingInterval) { this.pollingInterval = pollingInterval; }
        
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }
}

