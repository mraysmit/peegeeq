package dev.mars.peegeeq.examples.springboot2.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for payment-related events in the PeeGeeQ Reactive Outbox Pattern.
 * This is the springboot2 package version for reactive examples.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PaymentEvent.PaymentInitiatedEvent.class, name = "PAYMENT_INITIATED"),
    @JsonSubTypes.Type(value = PaymentEvent.PaymentCompletedEvent.class, name = "PAYMENT_COMPLETED"),
    @JsonSubTypes.Type(value = PaymentEvent.PaymentFailedEvent.class, name = "PAYMENT_FAILED")
})
public abstract class PaymentEvent {
    private final String eventId = UUID.randomUUID().toString();
    private final Instant timestamp = Instant.now();

    public String getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("%s{eventId='%s', timestamp=%s}", 
            getClass().getSimpleName(), eventId, timestamp);
    }

    /**
     * Event published when a payment process is initiated.
     */
    public static class PaymentInitiatedEvent extends PaymentEvent {
        private final String orderId;
        private final String paymentId;
        private final BigDecimal amount;

        public PaymentInitiatedEvent(String orderId, BigDecimal amount) {
            this.orderId = orderId;
            this.paymentId = "PAY-" + orderId + "-" + System.currentTimeMillis();
            this.amount = amount;
        }

        public String getOrderId() { return orderId; }
        public String getPaymentId() { return paymentId; }
        public BigDecimal getAmount() { return amount; }

        @Override
        public String toString() {
            return String.format("PaymentInitiatedEvent{eventId='%s', orderId='%s', paymentId='%s', amount=%s, timestamp=%s}",
                getEventId(), orderId, paymentId, amount, getTimestamp());
        }
    }

    /**
     * Event published when a payment is successfully completed.
     */
    public static class PaymentCompletedEvent extends PaymentEvent {
        private final String orderId;
        private final String paymentId;
        private final BigDecimal amount;

        public PaymentCompletedEvent(String orderId, String paymentId, BigDecimal amount) {
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.amount = amount;
        }

        public String getOrderId() { return orderId; }
        public String getPaymentId() { return paymentId; }
        public BigDecimal getAmount() { return amount; }

        @Override
        public String toString() {
            return String.format("PaymentCompletedEvent{eventId='%s', orderId='%s', paymentId='%s', amount=%s, timestamp=%s}",
                getEventId(), orderId, paymentId, amount, getTimestamp());
        }
    }

    /**
     * Event published when a payment fails.
     */
    public static class PaymentFailedEvent extends PaymentEvent {
        private final String orderId;
        private final String paymentId;
        private final String reason;
        private final BigDecimal amount;

        public PaymentFailedEvent(String orderId, String paymentId, BigDecimal amount, String reason) {
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.amount = amount;
            this.reason = reason;
        }

        public String getOrderId() { return orderId; }
        public String getPaymentId() { return paymentId; }
        public String getReason() { return reason; }
        public BigDecimal getAmount() { return amount; }

        @Override
        public String toString() {
            return String.format("PaymentFailedEvent{eventId='%s', orderId='%s', paymentId='%s', amount=%s, reason='%s', timestamp=%s}",
                getEventId(), orderId, paymentId, amount, reason, getTimestamp());
        }
    }
}
