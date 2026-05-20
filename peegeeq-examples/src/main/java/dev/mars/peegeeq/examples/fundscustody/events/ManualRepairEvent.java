package dev.mars.peegeeq.examples.fundscustody.events;

import java.util.Objects;

/**
 * Event payload for a manual repair carried out by an operations team member.
 * Maps to event name: {@code repair.manual.executed}
 *
 * <p>Recorded after a human operator resolves an exception raised by the automated
 * processing pipeline (see {@link ExceptionManagedEvent}). Storing the repair as
 * an event — rather than updating a status column — preserves the full resolution
 * history: who approved it, what action was taken, and when it was effective.
 */
public record ManualRepairEvent(
    String repairId,
    String originalTransactionId,
    String repairType,        // e.g. CANCEL_AND_REBOOK, NOVATION, PARTIAL_SETTLEMENT
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
