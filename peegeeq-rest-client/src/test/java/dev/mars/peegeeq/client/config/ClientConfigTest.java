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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientConfig.
 */
@Tag(TestCategories.CORE)
class ClientConfigTest {

    @Test
    void defaults_createsConfigWithDefaultValues() {
        // When
        ClientConfig config = ClientConfig.defaults();

        // Then
        assertEquals(ClientConfig.DEFAULT_BASE_URL, config.getBaseUrl());
        assertEquals(ClientConfig.DEFAULT_TIMEOUT, config.getTimeout());
        assertEquals(ClientConfig.DEFAULT_MAX_RETRIES, config.getMaxRetries());
        assertEquals(ClientConfig.DEFAULT_RETRY_DELAY, config.getRetryDelay());
        assertEquals(ClientConfig.DEFAULT_POOL_SIZE, config.getPoolSize());
        assertFalse(config.isSslEnabled());
        assertFalse(config.isTrustAllCertificates());
    }

    @Test
    void builder_withCustomValues_createsConfigWithCustomValues() {
        // Given
        String baseUrl = "https://api.example.com:9443";
        Duration timeout = Duration.ofSeconds(60);
        int maxRetries = 5;
        Duration retryDelay = Duration.ofSeconds(1);
        int poolSize = 20;

        // When
        ClientConfig config = ClientConfig.builder()
            .baseUrl(baseUrl)
            .timeout(timeout)
            .maxRetries(maxRetries)
            .retryDelay(retryDelay)
            .poolSize(poolSize)
            .sslEnabled(true)
            .trustAllCertificates(true)
            .build();

        // Then
        assertEquals(baseUrl, config.getBaseUrl());
        assertEquals(timeout, config.getTimeout());
        assertEquals(maxRetries, config.getMaxRetries());
        assertEquals(retryDelay, config.getRetryDelay());
        assertEquals(poolSize, config.getPoolSize());
        assertTrue(config.isSslEnabled());
        assertTrue(config.isTrustAllCertificates());
    }

    @Test
    void builder_withNullBaseUrl_throwsNullPointerException() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            ClientConfig.builder()
                .baseUrl(null)
                .build()
        );
    }

    @Test
    void builder_withNullTimeout_throwsNullPointerException() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            ClientConfig.builder()
                .timeout(null)
                .build()
        );
    }

    @Test
    void builder_withNegativeMaxRetries_throwsIllegalArgumentException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder()
                .maxRetries(-1)
                .build()
        );
    }

    @Test
    void builder_withZeroPoolSize_throwsIllegalArgumentException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            ClientConfig.builder()
                .poolSize(0)
                .build()
        );
    }

    @Test
    void builder_withZeroMaxRetries_succeeds() {
        // When
        ClientConfig config = ClientConfig.builder()
            .maxRetries(0)
            .build();

        // Then
        assertEquals(0, config.getMaxRetries());
    }

    @Test
    void builder_withMinimalPoolSize_succeeds() {
        // When
        ClientConfig config = ClientConfig.builder()
            .poolSize(1)
            .build();

        // Then
        assertEquals(1, config.getPoolSize());
    }
}

