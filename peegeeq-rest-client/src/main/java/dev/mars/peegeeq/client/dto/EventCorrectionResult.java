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

import java.time.Instant;

/**
 * Result of appending a correction to an event.
 *
 * <p>Maps the appendCorrection 201 payload {@code {message, eventStoreName,
 * setupId, originalEventId, correctionEventId, version, transactionTime,
 * correctionReason}} — the endpoint does not emit the correction event itself
 * (event-stores contract review, 2026-08-10).</p>
 *
 * @param eventStoreName the event store the correction was appended to
 * @param setupId the setup identifier
 * @param originalEventId the corrected event's identifier
 * @param correctionEventId the correction event's identifier
 * @param version the correction event's version
 * @param transactionTime the transaction time the store recorded
 * @param correctionReason the reason echoed from the request
 */
public record EventCorrectionResult(
    String eventStoreName,
    String setupId,
    String originalEventId,
    String correctionEventId,
    long version,
    Instant transactionTime,
    String correctionReason
) {
}
