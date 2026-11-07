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
 * The outcome of one delivery of one message to one consumer group.
 *
 * <p>A dispatch returns one of these values in a succeeded {@code Future}. A failed
 * {@code Future} is the {@code FAILED} outcome: the consumer that owns the retry counter
 * retries the message and dead-letters it at the retry limit. No consumer selects a durable
 * effect by testing an exception class.</p>
 *
 * <p>The contract is in
 * {@code docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md},
 * section "Delivery Outcome Contract".</p>
 */
public enum DeliveryOutcome {

    /** The handler succeeded. Terminal. The only successful outcome. */
    COMPLETED,

    /**
     * The group predicate refused the message. Terminal for the group, and not successful
     * processing. On a queue the message stays available to every other consumer, and the
     * group does not receive it again until a queue replay.
     */
    REJECTED,

    /**
     * No active member accepted the message, or the selected member left before dispatch.
     * Not terminal. No retry count changes and the message is kept.
     */
    UNAVAILABLE
}
