package dev.mars.peegeeq.examples.fundscustody.events;

import java.util.Objects;

/**
 * Event payload for an automated operational exception in the post-trade lifecycle.
 * Maps to event name: {@code exception.detection.automated}
 *
 * <p>Captured when the system detects a processing failure that requires human intervention
 * before the transaction can settle. Examples include settlement failures, matching
 * discrepancies, and failed nostro reconciliations. The event drives the exception
 * management workflow and is paired with a {@link ManualRepairEvent} when resolved.
 */
public record ExceptionManagedEvent(
    String exceptionId,
    String sourceTransactionId,
    String exceptionType,     // e.g. SETTLEMENT_FAIL, MATCHING_FAIL, NOSTRO_BREAK
    String severity,          // CRITICAL, HIGH, MEDIUM, LOW
    String assignedTo
) {
    public ExceptionManagedEvent {
        Objects.requireNonNull(exceptionId, "exceptionId cannot be null");
        Objects.requireNonNull(sourceTransactionId, "sourceTransactionId cannot be null");
        Objects.requireNonNull(exceptionType, "exceptionType cannot be null");
        Objects.requireNonNull(severity, "severity cannot be null");
        Objects.requireNonNull(assignedTo, "assignedTo cannot be null");
    }
}
