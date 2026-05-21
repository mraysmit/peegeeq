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

import java.util.List;

/**
 * Request object for sending multiple messages to a queue in a batch.
 */
public class BatchMessageRequest {

    private List<MessageRequest> messages;
    private boolean failOnError = true; // If true, stop processing on first error
    private int maxBatchSize = 100; // Maximum number of messages in a batch

    public List<MessageRequest> getMessages() { return messages; }
    public void setMessages(List<MessageRequest> messages) { this.messages = messages; }

    public boolean isFailOnError() { return failOnError; }
    public void setFailOnError(boolean failOnError) { this.failOnError = failOnError; }

    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }

    /**
     * Validates the batch message request.
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Batch must contain at least one message");
        }

        if (messages.size() > maxBatchSize) {
            throw new IllegalArgumentException("Batch size exceeds maximum allowed: " + maxBatchSize);
        }

        // Validate each message in the batch
        for (int i = 0; i < messages.size(); i++) {
            try {
                messages.get(i).validate();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Message at index " + i + " is invalid: " + e.getMessage());
            }
        }
    }
}
