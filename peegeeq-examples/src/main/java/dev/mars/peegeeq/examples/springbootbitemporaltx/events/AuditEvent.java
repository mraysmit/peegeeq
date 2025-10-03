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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Audit Event for Bi-Temporal Event Store Transaction Coordination.
 * 
 * This event represents audit and compliance events in an enterprise system
 * that coordinates with order, inventory, and payment processing for
 * regulatory compliance and investigation purposes.
 * 
 * <h2>Audit Event Types</h2>
 * <ul>
 *   <li><b>TRANSACTION_STARTED</b> - Multi-store transaction initiated</li>
 *   <li><b>TRANSACTION_COMMITTED</b> - Multi-store transaction committed successfully</li>
 *   <li><b>TRANSACTION_ROLLED_BACK</b> - Multi-store transaction rolled back</li>
 *   <li><b>COMPLIANCE_CHECK</b> - Regulatory compliance validation</li>
 *   <li><b>SECURITY_EVENT</b> - Security-related event (authentication, authorization)</li>
 *   <li><b>DATA_ACCESS</b> - Sensitive data access event</li>
 *   <li><b>CONFIGURATION_CHANGE</b> - System configuration modification</li>
 *   <li><b>INVESTIGATION_STARTED</b> - Audit investigation initiated</li>
 * </ul>
 * 
 * <h2>Transaction Coordination</h2>
 * 
 * Audit events participate in coordinated transactions to ensure:
 * <ul>
 *   <li><b>Complete Audit Trail</b> - Every business transaction has audit records</li>
 *   <li><b>Regulatory Compliance</b> - All required compliance events are recorded</li>
 *   <li><b>Investigation Support</b> - Detailed forensic information is available</li>
 *   <li><b>Security Monitoring</b> - Security events are correlated with business events</li>
 * </ul>
 * 
 * <h2>Bi-Temporal Characteristics</h2>
 * <ul>
 *   <li><b>Valid Time</b> - When the audited activity actually occurred</li>
 *   <li><b>Transaction Time</b> - When the audit event was recorded</li>
 *   <li><b>Immutable Records</b> - Audit events cannot be modified, only corrected</li>
 *   <li><b>Retention Policies</b> - Long-term retention for regulatory requirements</li>
 * </ul>
 * 
 * <h2>Regulatory Compliance</h2>
 * <ul>
 *   <li><b>SOX Compliance</b> - Financial transaction audit trails</li>
 *   <li><b>GDPR Compliance</b> - Data processing and privacy events</li>
 *   <li><b>PCI DSS</b> - Payment card industry security events</li>
 *   <li><b>HIPAA</b> - Healthcare information access events</li>
 *   <li><b>Industry Standards</b> - Sector-specific compliance requirements</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditEvent {
    
    private final String auditId;
    private final String eventType;
    private final String entityType;
    private final String entityId;
    private final String userId;
    private final String sessionId;
    private final String action;
    private final String outcome;
    private final String riskLevel;
    private final String complianceFramework;
    private final String sourceSystem;
    private final String sourceIpAddress;
    private final String userAgent;
    private final LocalDateTime eventDate;
    private final String correlationId;
    private final String transactionId;
    private final String details;
    private final Map<String, String> metadata;
    
    /**
     * Creates a new AuditEvent.
     *
     * @param auditId Unique audit event identifier
     * @param eventType Type of audit event
     * @param entityType Type of entity being audited
     * @param entityId Identifier of entity being audited
     * @param userId User performing the action
     * @param sessionId User session identifier
     * @param action Action being performed
     * @param outcome Outcome of the action
     * @param riskLevel Risk level assessment
     * @param complianceFramework Applicable compliance framework
     * @param sourceSystem System generating the audit event
     * @param sourceIpAddress Source IP address
     * @param userAgent User agent string
     * @param eventDate Date and time of the audited event
     * @param correlationId Correlation identifier for related events
     * @param transactionId Transaction identifier for coordinated operations
     * @param details Detailed description of the event
     * @param metadata Additional audit metadata
     */
    @JsonCreator
    public AuditEvent(
            @JsonProperty("auditId") String auditId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("entityType") String entityType,
            @JsonProperty("entityId") String entityId,
            @JsonProperty("userId") String userId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("action") String action,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("riskLevel") String riskLevel,
            @JsonProperty("complianceFramework") String complianceFramework,
            @JsonProperty("sourceSystem") String sourceSystem,
            @JsonProperty("sourceIpAddress") String sourceIpAddress,
            @JsonProperty("userAgent") String userAgent,
            @JsonProperty("eventDate") LocalDateTime eventDate,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("transactionId") String transactionId,
            @JsonProperty("details") String details,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.auditId = auditId;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.action = action;
        this.outcome = outcome;
        this.riskLevel = riskLevel;
        this.complianceFramework = complianceFramework;
        this.sourceSystem = sourceSystem;
        this.sourceIpAddress = sourceIpAddress;
        this.userAgent = userAgent;
        this.eventDate = eventDate;
        this.correlationId = correlationId;
        this.transactionId = transactionId;
        this.details = details;
        this.metadata = metadata;
    }
    
    // Getters
    
    public String getAuditId() {
        return auditId;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public String getEntityType() {
        return entityType;
    }
    
    public String getEntityId() {
        return entityId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public String getAction() {
        return action;
    }
    
    public String getOutcome() {
        return outcome;
    }
    
    public String getRiskLevel() {
        return riskLevel;
    }
    
    public String getComplianceFramework() {
        return complianceFramework;
    }
    
    public String getSourceSystem() {
        return sourceSystem;
    }
    
    public String getSourceIpAddress() {
        return sourceIpAddress;
    }
    
    public String getUserAgent() {
        return userAgent;
    }
    
    public LocalDateTime getEventDate() {
        return eventDate;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public String getTransactionId() {
        return transactionId;
    }
    
    public String getDetails() {
        return details;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuditEvent that = (AuditEvent) o;
        return Objects.equals(auditId, that.auditId) &&
               Objects.equals(eventType, that.eventType) &&
               Objects.equals(entityType, that.entityType) &&
               Objects.equals(entityId, that.entityId) &&
               Objects.equals(userId, that.userId) &&
               Objects.equals(sessionId, that.sessionId) &&
               Objects.equals(action, that.action) &&
               Objects.equals(outcome, that.outcome) &&
               Objects.equals(riskLevel, that.riskLevel) &&
               Objects.equals(complianceFramework, that.complianceFramework) &&
               Objects.equals(sourceSystem, that.sourceSystem) &&
               Objects.equals(sourceIpAddress, that.sourceIpAddress) &&
               Objects.equals(userAgent, that.userAgent) &&
               Objects.equals(eventDate, that.eventDate) &&
               Objects.equals(correlationId, that.correlationId) &&
               Objects.equals(transactionId, that.transactionId) &&
               Objects.equals(details, that.details) &&
               Objects.equals(metadata, that.metadata);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(auditId, eventType, entityType, entityId, userId, sessionId,
                          action, outcome, riskLevel, complianceFramework, sourceSystem,
                          sourceIpAddress, userAgent, eventDate, correlationId,
                          transactionId, details, metadata);
    }
    
    @Override
    public String toString() {
        return "AuditEvent{" +
                "auditId='" + auditId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", entityType='" + entityType + '\'' +
                ", entityId='" + entityId + '\'' +
                ", userId='" + userId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", action='" + action + '\'' +
                ", outcome='" + outcome + '\'' +
                ", riskLevel='" + riskLevel + '\'' +
                ", complianceFramework='" + complianceFramework + '\'' +
                ", sourceSystem='" + sourceSystem + '\'' +
                ", sourceIpAddress='" + sourceIpAddress + '\'' +
                ", userAgent='" + userAgent + '\'' +
                ", eventDate=" + eventDate +
                ", correlationId='" + correlationId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", details='" + details + '\'' +
                ", metadata=" + metadata +
                '}';
    }
}
