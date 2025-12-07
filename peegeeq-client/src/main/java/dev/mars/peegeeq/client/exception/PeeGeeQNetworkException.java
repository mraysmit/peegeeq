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
 * Exception thrown when a network error occurs while communicating with the PeeGeeQ REST API.
 * 
 * <p>This exception is thrown for:
 * <ul>
 *   <li>Connection failures</li>
 *   <li>Timeouts</li>
 *   <li>DNS resolution failures</li>
 *   <li>SSL/TLS errors</li>
 *   <li>Other network-level errors</li>
 * </ul>
 */
public class PeeGeeQNetworkException extends PeeGeeQClientException {

    private final String host;
    private final int port;
    private final boolean isTimeout;

    /**
     * Creates a new network exception.
     *
     * @param message the error message
     * @param host the target host (may be null)
     * @param port the target port (0 if unknown)
     * @param isTimeout true if this was a timeout error
     */
    public PeeGeeQNetworkException(String message, String host, int port, boolean isTimeout) {
        super(formatMessage(message, host, port, isTimeout));
        this.host = host;
        this.port = port;
        this.isTimeout = isTimeout;
    }

    /**
     * Creates a new network exception with a cause.
     *
     * @param message the error message
     * @param host the target host (may be null)
     * @param port the target port (0 if unknown)
     * @param isTimeout true if this was a timeout error
     * @param cause the underlying cause
     */
    public PeeGeeQNetworkException(String message, String host, int port, boolean isTimeout, Throwable cause) {
        super(formatMessage(message, host, port, isTimeout), cause);
        this.host = host;
        this.port = port;
        this.isTimeout = isTimeout;
    }

    /** Returns the target host, or null if unknown */
    public String getHost() { return host; }

    /** Returns the target port, or 0 if unknown */
    public int getPort() { return port; }

    /** Returns true if this was a timeout error */
    public boolean isTimeout() { return isTimeout; }

    private static String formatMessage(String message, String host, int port, boolean isTimeout) {
        StringBuilder sb = new StringBuilder();
        sb.append("Network Error");
        if (isTimeout) {
            sb.append(" (Timeout)");
        }
        if (host != null) {
            sb.append(" connecting to ").append(host);
            if (port > 0) {
                sb.append(":").append(port);
            }
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}

