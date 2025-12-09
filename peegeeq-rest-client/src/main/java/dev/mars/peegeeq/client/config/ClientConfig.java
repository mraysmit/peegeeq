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

package dev.mars.peegeeq.client.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the PeeGeeQ REST client.
 * 
 * <p>Use the {@link Builder} to create instances with custom settings.
 * 
 * <p>Example usage:
 * <pre>{@code
 * ClientConfig config = ClientConfig.builder()
 *     .baseUrl("http://localhost:8080")
 *     .timeout(Duration.ofSeconds(30))
 *     .maxRetries(3)
 *     .build();
 * }</pre>
 */
public final class ClientConfig {

    /** Default base URL for the PeeGeeQ REST API */
    public static final String DEFAULT_BASE_URL = "http://localhost:8080";
    
    /** Default request timeout */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    /** Default maximum retry attempts */
    public static final int DEFAULT_MAX_RETRIES = 3;
    
    /** Default retry delay */
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);
    
    /** Default connection pool size */
    public static final int DEFAULT_POOL_SIZE = 10;

    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final int poolSize;
    private final boolean sslEnabled;
    private final boolean trustAllCertificates;

    private ClientConfig(Builder builder) {
        this.baseUrl = Objects.requireNonNull(builder.baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout must not be null");
        this.maxRetries = builder.maxRetries;
        this.retryDelay = Objects.requireNonNull(builder.retryDelay, "retryDelay must not be null");
        this.poolSize = builder.poolSize;
        this.sslEnabled = builder.sslEnabled;
        this.trustAllCertificates = builder.trustAllCertificates;
        
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
    }

    /** Returns the base URL for the PeeGeeQ REST API */
    public String getBaseUrl() { return baseUrl; }

    /** Returns the request timeout duration */
    public Duration getTimeout() { return timeout; }

    /** Returns the maximum number of retry attempts */
    public int getMaxRetries() { return maxRetries; }

    /** Returns the delay between retry attempts */
    public Duration getRetryDelay() { return retryDelay; }

    /** Returns the connection pool size */
    public int getPoolSize() { return poolSize; }

    /** Returns whether SSL is enabled */
    public boolean isSslEnabled() { return sslEnabled; }

    /** Returns whether to trust all certificates (for development only) */
    public boolean isTrustAllCertificates() { return trustAllCertificates; }

    /** Creates a new builder with default settings */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a default configuration */
    public static ClientConfig defaults() {
        return builder().build();
    }

    /** Builder for creating ClientConfig instances */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration retryDelay = DEFAULT_RETRY_DELAY;
        private int poolSize = DEFAULT_POOL_SIZE;
        private boolean sslEnabled = false;
        private boolean trustAllCertificates = false;

        private Builder() {}

        /** Sets the base URL for the PeeGeeQ REST API */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Sets the request timeout duration */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Sets the maximum number of retry attempts */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /** Sets the delay between retry attempts */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        /** Sets the connection pool size */
        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        /** Enables SSL for HTTPS connections */
        public Builder sslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
            return this;
        }

        /** Trusts all certificates (for development only - NOT for production) */
        public Builder trustAllCertificates(boolean trustAllCertificates) {
            this.trustAllCertificates = trustAllCertificates;
            return this;
        }

        /** Builds the ClientConfig instance */
        public ClientConfig build() {
            return new ClientConfig(this);
        }
    }
}

