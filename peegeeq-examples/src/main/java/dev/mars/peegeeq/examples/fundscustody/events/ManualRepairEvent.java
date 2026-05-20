package dev.mars.peegeeq.examples.fundscustody.events;

import java.util.Objects;

/**
 * Event payload for manual repair execution.
 * Maps to event name: repair.manual.executed
 */
public record ManualRepairEvent(
    String repairId,
    String originalTransactionId,
    String repairType,
    String executedBy,
    String approvedBy
) {
    public ManualRepairEvent {
        Objects.requireNonNull(repairId, "repairId cannot be null");
        Objects.requireNonNull(originalTransactionId, "originalTransactionId cannot be null");
        Objects.requireNonNull(repairType, "repairType cannot be null");
        Objects.requireNonNull(executedBy, "executedBy cannot be null");
        Objects.requireNonNull(approvedBy, "approvedBy cannot be null");
    }
}
