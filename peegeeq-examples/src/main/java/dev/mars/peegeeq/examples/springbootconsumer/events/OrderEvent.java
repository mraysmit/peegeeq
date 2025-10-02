package dev.mars.peegeeq.examples.springbootconsumer.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Order event for consumer example.
 * Demonstrates message consumption with strongly-typed events.
 */
public class OrderEvent {
    
    private final String orderId;
    private final String customerId;
    private final BigDecimal amount;
    private final String status;
    
    @JsonCreator
    public OrderEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("status") String status) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public String getStatus() {
        return status;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderEvent that = (OrderEvent) o;
        return Objects.equals(orderId, that.orderId) &&
               Objects.equals(customerId, that.customerId) &&
               Objects.equals(amount, that.amount) &&
               Objects.equals(status, that.status);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orderId, customerId, amount, status);
    }
    
    @Override
    public String toString() {
        return "OrderEvent{" +
               "orderId='" + orderId + '\'' +
               ", customerId='" + customerId + '\'' +
               ", amount=" + amount +
               ", status='" + status + '\'' +
               '}';
    }
}

