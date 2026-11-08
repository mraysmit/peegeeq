package dev.mars.peegeeq.servicemanager.config;

import io.vertx.core.json.JsonObject;

/**
 * Configuration class for the PeeGeeQ Service Manager.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class ServiceManagerConfig {
    
    // Default values
    private static final String DEFAULT_CONSUL_HOST = "localhost";
    private static final int DEFAULT_CONSUL_PORT = 8500;
    private static final int DEFAULT_SERVICE_PORT = 9090;
    private static final int DEFAULT_REQUEST_TIMEOUT = 10000;
    private static final int DEFAULT_CACHE_REFRESH_INTERVAL = 30000;
    
    private final String consulHost;
    private final int consulPort;
    private final int servicePort;
    private final int requestTimeout;
    private final int cacheRefreshInterval;
    private final String serviceName;
    private final String environment;
    private final String region;
    
    public ServiceManagerConfig() {
        this(new JsonObject());
    }
    
    /**
     * Reads the given configuration; a key it does not hold takes its default. JVM system
     * properties are not read: they are process-wide state, not service manager configuration.
     */
    public ServiceManagerConfig(JsonObject config) {
        this.consulHost = config.getString("consul.host", DEFAULT_CONSUL_HOST);
        this.consulPort = config.getInteger("consul.port", DEFAULT_CONSUL_PORT);
        this.servicePort = config.getInteger("service.port", DEFAULT_SERVICE_PORT);
        this.requestTimeout = config.getInteger("request.timeout", DEFAULT_REQUEST_TIMEOUT);
        this.cacheRefreshInterval = config.getInteger("cache.refresh.interval", DEFAULT_CACHE_REFRESH_INTERVAL);
        this.serviceName = config.getString("service.name", "peegeeq-service-manager");
        this.environment = config.getString("environment", "development");
        this.region = config.getString("region", "default");
    }
    
    // Getters
    
    public String getConsulHost() {
        return consulHost;
    }
    
    public int getConsulPort() {
        return consulPort;
    }
    
    public int getServicePort() {
        return servicePort;
    }
    
    public int getRequestTimeout() {
        return requestTimeout;
    }
    
    public int getCacheRefreshInterval() {
        return cacheRefreshInterval;
    }
    
    public String getServiceName() {
        return serviceName;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public String getRegion() {
        return region;
    }
    
    public String getConsulAddress() {
        return consulHost + ":" + consulPort;
    }
    
    public String getServiceAddress() {
        return "localhost:" + servicePort;
    }
    
    @Override
    public String toString() {
        return "ServiceManagerConfig{" +
                "consulHost='" + consulHost + '\'' +
                ", consulPort=" + consulPort +
                ", servicePort=" + servicePort +
                ", requestTimeout=" + requestTimeout +
                ", cacheRefreshInterval=" + cacheRefreshInterval +
                ", serviceName='" + serviceName + '\'' +
                ", environment='" + environment + '\'' +
                ", region='" + region + '\'' +
                '}';
    }
    
    /**
     * Creates a builder for constructing ServiceManagerConfig objects.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final JsonObject config = new JsonObject();
        
        public Builder consulHost(String consulHost) {
            config.put("consul.host", consulHost);
            return this;
        }
        
        public Builder consulPort(int consulPort) {
            config.put("consul.port", consulPort);
            return this;
        }
        
        public Builder servicePort(int servicePort) {
            config.put("service.port", servicePort);
            return this;
        }
        
        public Builder requestTimeout(int requestTimeout) {
            config.put("request.timeout", requestTimeout);
            return this;
        }
        
        public Builder cacheRefreshInterval(int cacheRefreshInterval) {
            config.put("cache.refresh.interval", cacheRefreshInterval);
            return this;
        }
        
        public Builder serviceName(String serviceName) {
            config.put("service.name", serviceName);
            return this;
        }
        
        public Builder environment(String environment) {
            config.put("environment", environment);
            return this;
        }
        
        public Builder region(String region) {
            config.put("region", region);
            return this;
        }
        
        public ServiceManagerConfig build() {
            return new ServiceManagerConfig(config);
        }
    }
}
