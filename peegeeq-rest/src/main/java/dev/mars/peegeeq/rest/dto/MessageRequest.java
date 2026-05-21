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

package dev.mars.peegeeq.rest.dto;

import java.util.Map;

/**
 * Request object for sending a message to a queue.
 *
 * TODO: detectMessageType() is a diagnostic convenience for the REST layer only.
 *       It must not be used for routing, projection dispatch, or any domain logic.
 *       The message envelope should carry explicit mandatory metadata (messageType,
 *       eventType, aggregateType, aggregateId) rather than inferring type from payload
 *       structure. Consider replacing MessageRequest with a typed envelope record and
 *       making messageType a required field validated in validate().
 */
public class MessageRequest {

    private Object payload;
    private Map<String, String> headers;
    private Integer priority;
    private Long delaySeconds;
    private String messageType; // Optional: specify expected type
    private String correlationId; // Optional: for distributed tracing
    private String messageGroup; // Optional: for ordered processing within a partition

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Long getDelaySeconds() { return delaySeconds; }
    public void setDelaySeconds(Long delaySeconds) { this.delaySeconds = delaySeconds; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getMessageGroup() { return messageGroup; }
    public void setMessageGroup(String messageGroup) { this.messageGroup = messageGroup; }

    /**
     * Validates the message request.
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (payload == null) {
            throw new IllegalArgumentException("Message payload is required");
        }
        if (priority != null && (priority < 1 || priority > 10)) {
            throw new IllegalArgumentException("Priority must be between 1 and 10");
        }
        if (delaySeconds != null && delaySeconds < 0) {
            throw new IllegalArgumentException("Delay seconds cannot be negative");
        }

        // Validate headers if present
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    throw new IllegalArgumentException("Header keys cannot be null or empty");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("Header values cannot be null");
                }
            }
        }
    }

    /**
     * Detects the message type based on payload content.
     * @return detected message type or "Unknown" if cannot be determined
     */
    public String detectMessageType() {
        if (messageType != null && !messageType.trim().isEmpty()) {
            return messageType;
        }

        if (payload == null) {
            return "Unknown";
        }

        if (payload instanceof Map<?, ?> payloadMap) {
            Object mt = payloadMap.get("messageType");
            if (mt != null) return mt.toString();

            if (payloadMap.containsKey("eventType")) return "Event";
            if (payloadMap.containsKey("commandType")) return "Command";
            if (payloadMap.containsKey("orderId")) return "Order";

            return "Object";
        }

        if (payload instanceof String) return "Text";
        if (payload instanceof Number) return "Numeric";
        if (payload instanceof java.util.List) return "Array";

        return "Unknown";
    }
}
