package dev.mars.peegeeq.examples.springbootfinancialfabric.events;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Trade Confirmation Event - Represents trade matching and confirmation.
 */
public class TradeConfirmationEvent {
    private final String tradeId;
    private final String status;
    private final Instant confirmationTime;

    @JsonCreator
    public TradeConfirmationEvent(
            @JsonProperty("tradeId") String tradeId,
            @JsonProperty("status") String status,
            @JsonProperty("confirmationTime") Instant confirmationTime) {
        this.tradeId = tradeId;
        this.status = status;
        this.confirmationTime = confirmationTime;
    }

    public String getTradeId() {
        return tradeId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getConfirmationTime() {
        return confirmationTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TradeConfirmationEvent that = (TradeConfirmationEvent) o;
        return Objects.equals(tradeId, that.tradeId) &&
                Objects.equals(status, that.status) &&
                Objects.equals(confirmationTime, that.confirmationTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tradeId, status, confirmationTime);
    }

    @Override
    public String toString() {
        return "TradeConfirmationEvent{" +
                "tradeId='" + tradeId + '\'' +
                ", status='" + status + '\'' +
                ", confirmationTime=" + confirmationTime +
                '}';
    }
}

