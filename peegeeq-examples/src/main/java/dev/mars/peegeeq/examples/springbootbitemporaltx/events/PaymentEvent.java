package dev.mars.peegeeq.examples.springbootbitemporaltx.events;

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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Payment Event for Bi-Temporal Event Store Transaction Coordination.
 * 
 * This event represents payment processing events in an enterprise payment system
 * that coordinates transactions with order and inventory management.
 * 
 * <h2>Payment Processing States</h2>
 * <ul>
 *   <li><b>AUTHORIZED</b> - Payment authorized but not captured</li>
 *   <li><b>CAPTURED</b> - Payment captured from customer account</li>
 *   <li><b>REFUNDED</b> - Payment refunded to customer</li>
 *   <li><b>PARTIALLY_REFUNDED</b> - Partial refund processed</li>
 *   <li><b>FAILED</b> - Payment processing failed</li>
 *   <li><b>CANCELLED</b> - Payment authorization cancelled</li>
 *   <li><b>EXPIRED</b> - Payment authorization expired</li>
 *   <li><b>DISPUTED</b> - Payment disputed by customer</li>
 * </ul>
 * 
 * <h2>Transaction Coordination</h2>
 * 
 * Payment events participate in coordinated transactions with:
 * <ul>
 *   <li><b>Order Events</b> - Authorize payments when orders are created</li>
 *   <li><b>Inventory Events</b> - Capture payments when inventory is allocated</li>
 *   <li><b>Audit Events</b> - Record payment activities for compliance</li>
 * </ul>
 * 
 * <h2>Bi-Temporal Characteristics</h2>
 * <ul>
 *   <li><b>Valid Time</b> - When the payment transaction actually occurred</li>
 *   <li><b>Transaction Time</b> - When the event was recorded in the system</li>
 *   <li><b>Corrections</b> - Support for correcting historical payment data</li>
 *   <li><b>Audit Trail</b> - Complete history of all payment activities</li>
 * </ul>
 * 
 * <h2>Payment Security and Compliance</h2>
 * <ul>
 *   <li><b>PCI DSS Compliance</b> - Secure handling of payment data</li>
 *   <li><b>Tokenization</b> - Payment method tokens instead of raw data</li>
 *   <li><b>Fraud Detection</b> - Risk scoring and fraud prevention</li>
 *   <li><b>Regulatory Reporting</b> - Anti-money laundering and compliance</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentEvent {
    
    private final String paymentId;
    private final String orderId;
    private final String customerId;
    private final String paymentStatus;
    private final BigDecimal amount;
    private final String currency;
    private final String paymentMethod;
    private final String paymentToken;
    private final String gatewayTransactionId;
    private final String authorizationCode;
    private final BigDecimal authorizedAmount;
    private final BigDecimal capturedAmount;
    private final BigDecimal refundedAmount;
    private final String merchantId;
    private final String processorName;
    private final LocalDateTime processedDate;
    private final String riskScore;
    private final String failureReason;
    private final Map<String, String> metadata;
    
    /**
     * Creates a new PaymentEvent.
     *
     * @param paymentId Unique payment identifier
     * @param orderId Related order identifier
     * @param customerId Customer identifier
     * @param paymentStatus Current payment status
     * @param amount Payment amount
     * @param currency Payment currency
     * @param paymentMethod Payment method (CARD, BANK_TRANSFER, etc.)
     * @param paymentToken Tokenized payment method
     * @param gatewayTransactionId Gateway transaction identifier
     * @param authorizationCode Payment authorization code
     * @param authorizedAmount Authorized amount
     * @param capturedAmount Captured amount
     * @param refundedAmount Refunded amount
     * @param merchantId Merchant identifier
     * @param processorName Payment processor name
     * @param processedDate Payment processing date
     * @param riskScore Risk assessment score
     * @param failureReason Failure reason (if applicable)
     * @param metadata Additional payment metadata
     */
    @JsonCreator
    public PaymentEvent(
            @JsonProperty("paymentId") String paymentId,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("paymentStatus") String paymentStatus,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("paymentMethod") String paymentMethod,
            @JsonProperty("paymentToken") String paymentToken,
            @JsonProperty("gatewayTransactionId") String gatewayTransactionId,
            @JsonProperty("authorizationCode") String authorizationCode,
            @JsonProperty("authorizedAmount") BigDecimal authorizedAmount,
            @JsonProperty("capturedAmount") BigDecimal capturedAmount,
            @JsonProperty("refundedAmount") BigDecimal refundedAmount,
            @JsonProperty("merchantId") String merchantId,
            @JsonProperty("processorName") String processorName,
            @JsonProperty("processedDate") LocalDateTime processedDate,
            @JsonProperty("riskScore") String riskScore,
            @JsonProperty("failureReason") String failureReason,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.paymentStatus = paymentStatus;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.paymentToken = paymentToken;
        this.gatewayTransactionId = gatewayTransactionId;
        this.authorizationCode = authorizationCode;
        this.authorizedAmount = authorizedAmount;
        this.capturedAmount = capturedAmount;
        this.refundedAmount = refundedAmount;
        this.merchantId = merchantId;
        this.processorName = processorName;
        this.processedDate = processedDate;
        this.riskScore = riskScore;
        this.failureReason = failureReason;
        this.metadata = metadata;
    }
    
    // Getters
    
    public String getPaymentId() {
        return paymentId;
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public String getPaymentStatus() {
        return paymentStatus;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public String getPaymentMethod() {
        return paymentMethod;
    }
    
    public String getPaymentToken() {
        return paymentToken;
    }
    
    public String getGatewayTransactionId() {
        return gatewayTransactionId;
    }
    
    public String getAuthorizationCode() {
        return authorizationCode;
    }
    
    public BigDecimal getAuthorizedAmount() {
        return authorizedAmount;
    }
    
    public BigDecimal getCapturedAmount() {
        return capturedAmount;
    }
    
    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }
    
    public String getMerchantId() {
        return merchantId;
    }
    
    public String getProcessorName() {
        return processorName;
    }
    
    public LocalDateTime getProcessedDate() {
        return processedDate;
    }
    
    public String getRiskScore() {
        return riskScore;
    }
    
    public String getFailureReason() {
        return failureReason;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentEvent that = (PaymentEvent) o;
        return Objects.equals(paymentId, that.paymentId) &&
               Objects.equals(orderId, that.orderId) &&
               Objects.equals(customerId, that.customerId) &&
               Objects.equals(paymentStatus, that.paymentStatus) &&
               Objects.equals(amount, that.amount) &&
               Objects.equals(currency, that.currency) &&
               Objects.equals(paymentMethod, that.paymentMethod) &&
               Objects.equals(paymentToken, that.paymentToken) &&
               Objects.equals(gatewayTransactionId, that.gatewayTransactionId) &&
               Objects.equals(authorizationCode, that.authorizationCode) &&
               Objects.equals(authorizedAmount, that.authorizedAmount) &&
               Objects.equals(capturedAmount, that.capturedAmount) &&
               Objects.equals(refundedAmount, that.refundedAmount) &&
               Objects.equals(merchantId, that.merchantId) &&
               Objects.equals(processorName, that.processorName) &&
               Objects.equals(processedDate, that.processedDate) &&
               Objects.equals(riskScore, that.riskScore) &&
               Objects.equals(failureReason, that.failureReason) &&
               Objects.equals(metadata, that.metadata);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(paymentId, orderId, customerId, paymentStatus, amount, currency,
                          paymentMethod, paymentToken, gatewayTransactionId, authorizationCode,
                          authorizedAmount, capturedAmount, refundedAmount, merchantId,
                          processorName, processedDate, riskScore, failureReason, metadata);
    }
    
    @Override
    public String toString() {
        return "PaymentEvent{" +
                "paymentId='" + paymentId + '\'' +
                ", orderId='" + orderId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", paymentStatus='" + paymentStatus + '\'' +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", paymentMethod='" + paymentMethod + '\'' +
                ", paymentToken='" + paymentToken + '\'' +
                ", gatewayTransactionId='" + gatewayTransactionId + '\'' +
                ", authorizationCode='" + authorizationCode + '\'' +
                ", authorizedAmount=" + authorizedAmount +
                ", capturedAmount=" + capturedAmount +
                ", refundedAmount=" + refundedAmount +
                ", merchantId='" + merchantId + '\'' +
                ", processorName='" + processorName + '\'' +
                ", processedDate=" + processedDate +
                ", riskScore='" + riskScore + '\'' +
                ", failureReason='" + failureReason + '\'' +
                ", metadata=" + metadata +
                '}';
    }
}
