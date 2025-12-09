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

package dev.mars.peegeeq.client.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Request object for sending a message to a queue.
 */
public class MessageRequest {

    private Object payload;
    private Map<String, String> headers;
    private Integer priority;
    private Long delaySeconds;
    private String messageType;
    private String correlationId;
    private String messageGroup;

    public MessageRequest() {
        this.headers = new HashMap<>();
    }

    public MessageRequest(Object payload) {
        this();
        this.payload = payload;
    }

    // Getters and setters
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

    // Fluent builder methods
    public MessageRequest withPayload(Object payload) {
        this.payload = payload;
        return this;
    }

    public MessageRequest withHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    public MessageRequest withHeaders(Map<String, String> headers) {
        this.headers.putAll(headers);
        return this;
    }

    public MessageRequest withPriority(Integer priority) {
        this.priority = priority;
        return this;
    }

    public MessageRequest withDelaySeconds(Long delaySeconds) {
        this.delaySeconds = delaySeconds;
        return this;
    }

    public MessageRequest withMessageType(String messageType) {
        this.messageType = messageType;
        return this;
    }

    public MessageRequest withCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public MessageRequest withMessageGroup(String messageGroup) {
        this.messageGroup = messageGroup;
        return this;
    }
}

