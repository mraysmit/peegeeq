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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Request to append a correction to an existing event.
 *
 * <p>The server parses this with a strict mapper that rejects unknown keys, so
 * the wire keys must match its CorrectionRequest exactly: correctedPayload
 * serializes as 'eventData' and validTime as 'validFrom' — the old field names
 * were refused with 400 on every call (event-stores contract review,
 * 2026-08-10).</p>
 */
public class CorrectionRequest {

    @JsonProperty("eventData")
    private Object correctedPayload;
    private String correctionReason;
    @JsonProperty("validFrom")
    private Instant validTime;

    public CorrectionRequest() {}

    public CorrectionRequest(Object correctedPayload, String correctionReason) {
        this.correctedPayload = correctedPayload;
        this.correctionReason = correctionReason;
    }

    // Getters and setters
    public Object getCorrectedPayload() { return correctedPayload; }
    public void setCorrectedPayload(Object correctedPayload) { this.correctedPayload = correctedPayload; }

    public String getCorrectionReason() { return correctionReason; }
    public void setCorrectionReason(String correctionReason) { this.correctionReason = correctionReason; }

    public Instant getValidTime() { return validTime; }
    public void setValidTime(Instant validTime) { this.validTime = validTime; }

    // Fluent builder methods
    public CorrectionRequest withCorrectedPayload(Object correctedPayload) {
        this.correctedPayload = correctedPayload;
        return this;
    }

    public CorrectionRequest withCorrectionReason(String correctionReason) {
        this.correctionReason = correctionReason;
        return this;
    }

    public CorrectionRequest withValidTime(Instant validTime) {
        this.validTime = validTime;
        return this;
    }
}

