package dev.mars.peegeeq.api.messaging;

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

/**
 * Signals that a message was permanently rejected and should be moved to the
 * dead letter queue rather than retried or reset to PENDING.
 *
 * <p>This differs from transient filtering (where a message is reset to PENDING
 * for reprocessing) — a rejected message will never be processable by this
 * consumer group and should not be retried.</p>
 *
 * <p>Typical causes:</p>
 * <ul>
 *   <li>Group-level filter permanently rejects the message</li>
 *   <li>Message is permanently unroutable (no consumer will ever accept it)</li>
 * </ul>
 */
public class RejectedMessageException extends RuntimeException {

    private final String messageId;
    private final String groupName;

    public RejectedMessageException(String messageId, String groupName, String reason) {
        super("Message %s permanently rejected by group '%s': %s".formatted(messageId, groupName, reason));
        this.messageId = messageId;
        this.groupName = groupName;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getGroupName() {
        return groupName;
    }
}
