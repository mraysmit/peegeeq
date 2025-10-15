package dev.mars.peegeeq.examples.fundscustody.model;

import java.util.Objects;

/**
 * Request DTO for cancelling a trade.
 */
public record CancellationRequest(
    String reason,
    String cancelledBy
) {
    public CancellationRequest {
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(cancelledBy, "cancelledBy cannot be null");
        
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
        if (cancelledBy.isBlank()) {
            throw new IllegalArgumentException("cancelledBy cannot be blank");
        }
    }
}

