package dev.mars.peegeeq.examples.springbootconsumer.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order domain model.
 * Plain POJO with manual SQL mapping (no JPA/R2DBC annotations).
 */
public class Order {
    
    private String id;
    private String customerId;
    private BigDecimal amount;
    private String status;
    private Instant createdAt;
    private Instant processedAt;
    private String processedBy;
    
    public Order() {
    }
    
    public Order(String id, String customerId, BigDecimal amount, String status) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = Instant.now();
    }
    
    // Getters and setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getProcessedAt() {
        return processedAt;
    }
    
    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
    
    public String getProcessedBy() {
        return processedBy;
    }
    
    public void setProcessedBy(String processedBy) {
        this.processedBy = processedBy;
    }
}

