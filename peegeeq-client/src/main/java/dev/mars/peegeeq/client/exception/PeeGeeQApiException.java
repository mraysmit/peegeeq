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

/**
 * Exception thrown when the PeeGeeQ REST API returns an error response.
 * 
 * <p>This exception contains details about the API error including:
 * <ul>
 *   <li>HTTP status code</li>
 *   <li>Error code from the API</li>
 *   <li>Error message</li>
 *   <li>Request path that caused the error</li>
 * </ul>
 */
public class PeeGeeQApiException extends PeeGeeQClientException {

    private final int statusCode;
    private final String errorCode;
    private final String path;

    /**
     * Creates a new API exception.
     *
     * @param message the error message
     * @param statusCode the HTTP status code
     * @param errorCode the API error code (may be null)
     * @param path the request path (may be null)
     */
    public PeeGeeQApiException(String message, int statusCode, String errorCode, String path) {
        super(formatMessage(message, statusCode, errorCode, path));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.path = path;
    }

    /**
     * Creates a new API exception with a cause.
     *
     * @param message the error message
     * @param statusCode the HTTP status code
     * @param errorCode the API error code (may be null)
     * @param path the request path (may be null)
     * @param cause the underlying cause
     */
    public PeeGeeQApiException(String message, int statusCode, String errorCode, String path, Throwable cause) {
        super(formatMessage(message, statusCode, errorCode, path), cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.path = path;
    }

    /** Returns the HTTP status code */
    public int getStatusCode() { return statusCode; }

    /** Returns the API error code, or null if not provided */
    public String getErrorCode() { return errorCode; }

    /** Returns the request path, or null if not provided */
    public String getPath() { return path; }

    /** Returns true if this is a client error (4xx status code) */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /** Returns true if this is a server error (5xx status code) */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /** Returns true if this is a not found error (404) */
    public boolean isNotFound() {
        return statusCode == 404;
    }

    /** Returns true if this is an unauthorized error (401) */
    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    /** Returns true if this is a forbidden error (403) */
    public boolean isForbidden() {
        return statusCode == 403;
    }

    /** Returns true if this is a conflict error (409) */
    public boolean isConflict() {
        return statusCode == 409;
    }

    private static String formatMessage(String message, int statusCode, String errorCode, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("API Error [").append(statusCode).append("]");
        if (errorCode != null) {
            sb.append(" (").append(errorCode).append(")");
        }
        if (path != null) {
            sb.append(" at ").append(path);
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}

