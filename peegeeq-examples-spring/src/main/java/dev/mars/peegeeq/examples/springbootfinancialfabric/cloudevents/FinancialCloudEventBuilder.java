package dev.mars.peegeeq.examples.springbootfinancialfabric.cloudevents;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.examples.springbootfinancialfabric.config.FinancialFabricProperties;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Builder for creating CloudEvents with financial fabric extensions.
 * 
 * Creates CloudEvents v1.0 compliant events with custom extensions:
 * - correlationid: Links all events in a business workflow
 * - causationid: Identifies the parent event that caused this event
 * - validtime: Business time (when event occurred in real world)
 */
@Component
public class FinancialCloudEventBuilder {
    
    private final FinancialFabricProperties properties;
    private final ObjectMapper objectMapper;
    
    public FinancialCloudEventBuilder(
            FinancialFabricProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Build a CloudEvent with all financial fabric extensions.
     * 
     * @param eventType CloudEvents type (e.g., "com.fincorp.trading.equities.capture.completed.v1")
     * @param payload Event payload object
     * @param correlationId Workflow identifier (links all events in business transaction)
     * @param causationId Parent event identifier (event that caused this event)
     * @param validTime Business time (when event occurred in real world)
     * @param <T> Type of the payload
     * @return CloudEvent with all extensions
     * @throws JsonProcessingException if payload serialization fails
     */
    public <T> CloudEvent buildCloudEvent(
            String eventType,
            T payload,
            String correlationId,
            String causationId,
            Instant validTime) throws JsonProcessingException {
        
        String eventId = UUID.randomUUID().toString();
        URI source = URI.create(properties.getCloudevents().getSource());
        
        // Serialize payload to JSON
        byte[] data = objectMapper.writeValueAsBytes(payload);
        
        // Build CloudEvent with v1.0 spec
        return CloudEventBuilder.v1()
            .withId(eventId)
            .withType(eventType)
            .withSource(source)
            .withTime(OffsetDateTime.now())
            .withDataContentType(properties.getCloudevents().getDataContentType())
            .withData(data)
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId != null ? causationId : "")
            .withExtension("validtime", validTime.toString())
            .build();
    }
    
    /**
     * Build a CloudEvent without causation (for initial events in a workflow).
     * 
     * @param eventType CloudEvents type
     * @param payload Event payload object
     * @param correlationId Workflow identifier
     * @param validTime Business time
     * @param <T> Type of the payload
     * @return CloudEvent
     * @throws JsonProcessingException if payload serialization fails
     */
    public <T> CloudEvent buildCloudEvent(
            String eventType,
            T payload,
            String correlationId,
            Instant validTime) throws JsonProcessingException {
        return buildCloudEvent(eventType, payload, correlationId, null, validTime);
    }
}

