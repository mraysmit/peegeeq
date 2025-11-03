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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;

import java.io.IOException;
import java.time.Instant;

/**
 * Utility class for working with CloudEvent extensions.
 * 
 * Provides helper methods to extract custom extensions:
 * - correlationid: Workflow identifier
 * - causationid: Parent event identifier
 * - validtime: Business time
 */
public class CloudEventExtensions {
    
    /**
     * Get the correlation ID from a CloudEvent.
     * 
     * @param event CloudEvent
     * @return Correlation ID or null if not present
     */
    public static String getCorrelationId(CloudEvent event) {
        return (String) event.getExtension("correlationid");
    }
    
    /**
     * Get the causation ID from a CloudEvent.
     * 
     * @param event CloudEvent
     * @return Causation ID or null if not present or empty
     */
    public static String getCausationId(CloudEvent event) {
        String causationId = (String) event.getExtension("causationid");
        return causationId != null && !causationId.isEmpty() ? causationId : null;
    }
    
    /**
     * Get the valid time from a CloudEvent.
     * 
     * @param event CloudEvent
     * @return Valid time as Instant or null if not present
     */
    public static Instant getValidTime(CloudEvent event) {
        String validTime = (String) event.getExtension("validtime");
        return validTime != null ? Instant.parse(validTime) : null;
    }
    
    /**
     * Extract and deserialize the payload from a CloudEvent.
     * 
     * @param event CloudEvent
     * @param payloadClass Class of the payload
     * @param objectMapper ObjectMapper for deserialization
     * @param <T> Type of the payload
     * @return Deserialized payload
     * @throws IOException if deserialization fails
     */
    public static <T> T getPayload(CloudEvent event, Class<T> payloadClass, ObjectMapper objectMapper) 
            throws IOException {
        byte[] data = event.getData() != null ? event.getData().toBytes() : new byte[0];
        return objectMapper.readValue(data, payloadClass);
    }
}

