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

package dev.mars.peegeeq.client.exception;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for exception classes.
 */
@Tag(TestCategories.CORE)
class ExceptionTest {

    // ========================================================================
    // PeeGeeQClientException Tests
    // ========================================================================

    @Test
    void clientException_withMessage_containsMessage() {
        // When
        PeeGeeQClientException ex = new PeeGeeQClientException("Test error");

        // Then
        assertEquals("Test error", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void clientException_withMessageAndCause_containsBoth() {
        // Given
        RuntimeException cause = new RuntimeException("Root cause");

        // When
        PeeGeeQClientException ex = new PeeGeeQClientException("Test error", cause);

        // Then
        assertEquals("Test error", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    // ========================================================================
    // PeeGeeQApiException Tests
    // ========================================================================

    @Test
    void apiException_formatsMessageCorrectly() {
        // When
        PeeGeeQApiException ex = new PeeGeeQApiException(
            "Not found", 404, "RESOURCE_NOT_FOUND", "/api/v1/setups/test");

        // Then
        assertTrue(ex.getMessage().contains("404"));
        assertTrue(ex.getMessage().contains("RESOURCE_NOT_FOUND"));
        assertTrue(ex.getMessage().contains("/api/v1/setups/test"));
        assertTrue(ex.getMessage().contains("Not found"));
    }

    @Test
    void apiException_returnsCorrectStatusCode() {
        // When
        PeeGeeQApiException ex = new PeeGeeQApiException("Error", 500, null, null);

        // Then
        assertEquals(500, ex.getStatusCode());
    }

    @Test
    void apiException_isClientError_returnsTrueFor4xx() {
        // When
        PeeGeeQApiException ex400 = new PeeGeeQApiException("Bad request", 400, null, null);
        PeeGeeQApiException ex404 = new PeeGeeQApiException("Not found", 404, null, null);
        PeeGeeQApiException ex499 = new PeeGeeQApiException("Client error", 499, null, null);

        // Then
        assertTrue(ex400.isClientError());
        assertTrue(ex404.isClientError());
        assertTrue(ex499.isClientError());
    }

    @Test
    void apiException_isServerError_returnsTrueFor5xx() {
        // When
        PeeGeeQApiException ex500 = new PeeGeeQApiException("Internal error", 500, null, null);
        PeeGeeQApiException ex503 = new PeeGeeQApiException("Service unavailable", 503, null, null);

        // Then
        assertTrue(ex500.isServerError());
        assertTrue(ex503.isServerError());
    }

    @Test
    void apiException_isNotFound_returnsTrueFor404() {
        // When
        PeeGeeQApiException ex404 = new PeeGeeQApiException("Not found", 404, null, null);
        PeeGeeQApiException ex500 = new PeeGeeQApiException("Error", 500, null, null);

        // Then
        assertTrue(ex404.isNotFound());
        assertFalse(ex500.isNotFound());
    }

    @Test
    void apiException_isUnauthorized_returnsTrueFor401() {
        // When
        PeeGeeQApiException ex = new PeeGeeQApiException("Unauthorized", 401, null, null);

        // Then
        assertTrue(ex.isUnauthorized());
    }

    @Test
    void apiException_isForbidden_returnsTrueFor403() {
        // When
        PeeGeeQApiException ex = new PeeGeeQApiException("Forbidden", 403, null, null);

        // Then
        assertTrue(ex.isForbidden());
    }

    @Test
    void apiException_isConflict_returnsTrueFor409() {
        // When
        PeeGeeQApiException ex = new PeeGeeQApiException("Conflict", 409, null, null);

        // Then
        assertTrue(ex.isConflict());
    }

    // ========================================================================
    // PeeGeeQNetworkException Tests
    // ========================================================================

    @Test
    void networkException_formatsMessageCorrectly() {
        // When
        PeeGeeQNetworkException ex = new PeeGeeQNetworkException(
            "Connection refused", "localhost", 8080, false);

        // Then
        assertTrue(ex.getMessage().contains("localhost"));
        assertTrue(ex.getMessage().contains("8080"));
        assertTrue(ex.getMessage().contains("Connection refused"));
    }

    @Test
    void networkException_indicatesTimeout() {
        // When
        PeeGeeQNetworkException ex = new PeeGeeQNetworkException(
            "Request timed out", "api.example.com", 443, true);

        // Then
        assertTrue(ex.isTimeout());
        assertTrue(ex.getMessage().contains("Timeout"));
    }

    @Test
    void networkException_returnsHostAndPort() {
        // When
        PeeGeeQNetworkException ex = new PeeGeeQNetworkException(
            "Error", "myhost", 9090, false);

        // Then
        assertEquals("myhost", ex.getHost());
        assertEquals(9090, ex.getPort());
    }
}

