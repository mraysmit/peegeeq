package dev.mars.peegeeq.performance.config;

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

import java.time.Duration;
import java.util.Properties;

/**
 * Configuration for PeeGeeQ performance tests.
 * 
 * This class encapsulates all configuration parameters for performance testing,
 * including test duration, concurrency levels, database settings, and output options.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-13
 * @version 1.0
 */
public class PerformanceTestConfig {
    
    private final String testSuite;
    private final Duration testDuration;
    private final int concurrentThreads;
    private final int warmupIterations;
    private final int testIterations;
    private final String outputDirectory;
    private final String configFile;
    private final DatabaseConfig databaseConfig;
    private final VertxConfig vertxConfig;
    private final boolean enableDetailedLogging;
    private final boolean generateGraphs;
    
    private PerformanceTestConfig(Builder builder) {
        this.testSuite = builder.testSuite;
        this.testDuration = builder.testDuration;
        this.concurrentThreads = builder.concurrentThreads;
        this.warmupIterations = builder.warmupIterations;
        this.testIterations = builder.testIterations;
        this.outputDirectory = builder.outputDirectory;
        this.configFile = builder.configFile;
        this.databaseConfig = builder.databaseConfig;
        this.vertxConfig = builder.vertxConfig;
        this.enableDetailedLogging = builder.enableDetailedLogging;
        this.generateGraphs = builder.generateGraphs;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    // Getters
    public String getTestSuite() { return testSuite; }
    public Duration getTestDuration() { return testDuration; }
    public int getConcurrentThreads() { return concurrentThreads; }
    public int getWarmupIterations() { return warmupIterations; }
    public int getTestIterations() { return testIterations; }
    public String getOutputDirectory() { return outputDirectory; }
    public String getConfigFile() { return configFile; }
    public DatabaseConfig getDatabaseConfig() { return databaseConfig; }
    public VertxConfig getVertxConfig() { return vertxConfig; }
    public boolean isDetailedLoggingEnabled() { return enableDetailedLogging; }
    public boolean isGenerateGraphs() { return generateGraphs; }
    
    public static class Builder {
        private String testSuite = "all";
        private Duration testDuration = Duration.ofMinutes(5);
        private int concurrentThreads = 10;
        private int warmupIterations = 100;
        private int testIterations = 1000;
        private String outputDirectory = "target/performance-reports";
        private String configFile = null;
        private DatabaseConfig databaseConfig = DatabaseConfig.defaultConfig();
        private VertxConfig vertxConfig = VertxConfig.defaultConfig();
        private boolean enableDetailedLogging = false;
        private boolean generateGraphs = true;
        
        public Builder testSuite(String testSuite) {
            this.testSuite = testSuite;
            return this;
        }
        
        public Builder testDuration(Duration testDuration) {
            this.testDuration = testDuration;
            return this;
        }
        
        public Builder concurrentThreads(int concurrentThreads) {
            this.concurrentThreads = concurrentThreads;
            return this;
        }
        
        public Builder warmupIterations(int warmupIterations) {
            this.warmupIterations = warmupIterations;
            return this;
        }
        
        public Builder testIterations(int testIterations) {
            this.testIterations = testIterations;
            return this;
        }
        
        public Builder outputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }
        
        public Builder configFile(String configFile) {
            this.configFile = configFile;
            return this;
        }
        
        public Builder databaseConfig(DatabaseConfig databaseConfig) {
            this.databaseConfig = databaseConfig;
            return this;
        }
        
        public Builder vertxConfig(VertxConfig vertxConfig) {
            this.vertxConfig = vertxConfig;
            return this;
        }
        
        public Builder enableDetailedLogging(boolean enableDetailedLogging) {
            this.enableDetailedLogging = enableDetailedLogging;
            return this;
        }
        
        public Builder generateGraphs(boolean generateGraphs) {
            this.generateGraphs = generateGraphs;
            return this;
        }
        
        public PerformanceTestConfig build() {
            return new PerformanceTestConfig(this);
        }
    }
    
    public static class DatabaseConfig {
        private final int connectionPoolSize;
        private final int pipeliningLimit;
        private final boolean sharedPool;
        private final int waitQueueSize;
        private final Duration connectionTimeout;
        
        private DatabaseConfig(int connectionPoolSize, int pipeliningLimit, boolean sharedPool, 
                              int waitQueueSize, Duration connectionTimeout) {
            this.connectionPoolSize = connectionPoolSize;
            this.pipeliningLimit = pipeliningLimit;
            this.sharedPool = sharedPool;
            this.waitQueueSize = waitQueueSize;
            this.connectionTimeout = connectionTimeout;
        }
        
        public static DatabaseConfig defaultConfig() {
            return new DatabaseConfig(50, 256, true, 1000, Duration.ofSeconds(30));
        }
        
        public static DatabaseConfig highThroughputConfig() {
            return new DatabaseConfig(100, 512, true, 2000, Duration.ofSeconds(60));
        }
        
        // Getters
        public int getConnectionPoolSize() { return connectionPoolSize; }
        public int getPipeliningLimit() { return pipeliningLimit; }
        public boolean isSharedPool() { return sharedPool; }
        public int getWaitQueueSize() { return waitQueueSize; }
        public Duration getConnectionTimeout() { return connectionTimeout; }
    }
    
    public static class VertxConfig {
        private final int eventLoopThreads;
        private final int workerThreads;
        private final boolean preferNativeTransport;
        private final int maxEventLoopExecuteTime;
        
        private VertxConfig(int eventLoopThreads, int workerThreads, boolean preferNativeTransport, 
                           int maxEventLoopExecuteTime) {
            this.eventLoopThreads = eventLoopThreads;
            this.workerThreads = workerThreads;
            this.preferNativeTransport = preferNativeTransport;
            this.maxEventLoopExecuteTime = maxEventLoopExecuteTime;
        }
        
        public static VertxConfig defaultConfig() {
            return new VertxConfig(8, 16, true, 2000);
        }
        
        public static VertxConfig highPerformanceConfig() {
            return new VertxConfig(Runtime.getRuntime().availableProcessors(), 
                                 Runtime.getRuntime().availableProcessors() * 2, 
                                 true, 5000);
        }
        
        // Getters
        public int getEventLoopThreads() { return eventLoopThreads; }
        public int getWorkerThreads() { return workerThreads; }
        public boolean isPreferNativeTransport() { return preferNativeTransport; }
        public int getMaxEventLoopExecuteTime() { return maxEventLoopExecuteTime; }
    }
    
    /**
     * Load configuration from system properties and environment variables.
     */
    public static PerformanceTestConfig fromSystemProperties() {
        Builder builder = builder();
        
        // Load from system properties
        String suite = System.getProperty("peegeeq.performance.suite", "all");
        builder.testSuite(suite);
        
        String duration = System.getProperty("peegeeq.performance.duration", "300");
        builder.testDuration(Duration.ofSeconds(Long.parseLong(duration)));
        
        String threads = System.getProperty("peegeeq.performance.threads", "10");
        builder.concurrentThreads(Integer.parseInt(threads));
        
        String outputDir = System.getProperty("peegeeq.performance.output", "target/performance-reports");
        builder.outputDirectory(outputDir);
        
        String detailedLogging = System.getProperty("peegeeq.performance.detailed.logging", "false");
        builder.enableDetailedLogging(Boolean.parseBoolean(detailedLogging));
        
        String generateGraphs = System.getProperty("peegeeq.performance.generate.graphs", "true");
        builder.generateGraphs(Boolean.parseBoolean(generateGraphs));
        
        return builder.build();
    }
    
    /**
     * Load configuration from properties file.
     */
    public static PerformanceTestConfig fromProperties(Properties properties) {
        Builder builder = builder();
        
        builder.testSuite(properties.getProperty("test.suite", "all"));
        builder.testDuration(Duration.ofSeconds(Long.parseLong(properties.getProperty("test.duration", "300"))));
        builder.concurrentThreads(Integer.parseInt(properties.getProperty("test.threads", "10")));
        builder.warmupIterations(Integer.parseInt(properties.getProperty("test.warmup.iterations", "100")));
        builder.testIterations(Integer.parseInt(properties.getProperty("test.iterations", "1000")));
        builder.outputDirectory(properties.getProperty("output.directory", "target/performance-reports"));
        builder.enableDetailedLogging(Boolean.parseBoolean(properties.getProperty("logging.detailed", "false")));
        builder.generateGraphs(Boolean.parseBoolean(properties.getProperty("output.graphs", "true")));
        
        return builder.build();
    }
}
