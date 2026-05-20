package dev.mars.peegeeq.examples.fundscustody.events;

import java.util.Objects;

/**
 * Event payload for operational exception management.
 * Maps to event name: exception.detection.automated
 */
public record ExceptionManagedEvent(
    String exceptionId,
    String sourceTransactionId,
    String exceptionType,
    String severity,
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
