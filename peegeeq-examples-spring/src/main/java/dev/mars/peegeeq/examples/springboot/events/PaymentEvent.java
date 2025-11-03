package dev.mars.peegeeq.examples.springboot.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for payment-related events in the PeeGeeQ Transactional Outbox Pattern.
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
        private final String paymentId;
        private final String transactionId;

        public PaymentCompletedEvent(String paymentId, String transactionId) {
            this.paymentId = paymentId;
            this.transactionId = transactionId;
        }

        public String getPaymentId() { return paymentId; }
        public String getTransactionId() { return transactionId; }

        @Override
        public String toString() {
            return String.format("PaymentCompletedEvent{eventId='%s', paymentId='%s', transactionId='%s', timestamp=%s}", 
                getEventId(), paymentId, transactionId, getTimestamp());
        }
    }

    /**
     * Event published when a payment processing fails.
     */
    public static class PaymentFailedEvent extends PaymentEvent {
        private final String paymentId;
        private final String errorCode;
        private final String errorMessage;

        public PaymentFailedEvent(String paymentId, String errorCode, String errorMessage) {
            this.paymentId = paymentId;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public String getPaymentId() { return paymentId; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }

        @Override
        public String toString() {
            return String.format("PaymentFailedEvent{eventId='%s', paymentId='%s', errorCode='%s', errorMessage='%s', timestamp=%s}", 
                getEventId(), paymentId, errorCode, errorMessage, getTimestamp());
        }
    }
}
