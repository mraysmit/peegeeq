package dev.mars.peegeeq.bitemporal;

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
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.db.PeeGeeQManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Factory for creating bi-temporal event stores.
 * 
 * This factory provides a convenient way to create properly configured
 * bi-temporal event stores with the PeeGeeQ infrastructure.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class BiTemporalEventStoreFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalEventStoreFactory.class);
    
    private final PeeGeeQManager peeGeeQManager;
    private final ObjectMapper objectMapper;
    
    /**
     * Creates a new BiTemporalEventStoreFactory.
     *
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param objectMapper The JSON object mapper
     */
    public BiTemporalEventStoreFactory(PeeGeeQManager peeGeeQManager, ObjectMapper objectMapper) {
        this.peeGeeQManager = Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
    }
    
    /**
     * Creates a new BiTemporalEventStoreFactory with default ObjectMapper.
     *
     * @param peeGeeQManager The PeeGeeQ manager for database access
     */
    public BiTemporalEventStoreFactory(PeeGeeQManager peeGeeQManager) {
        this(peeGeeQManager, new ObjectMapper());
    }
    
    /**
     * Creates a new bi-temporal event store for the specified payload type.
     *
     * @param <T> The type of event payload
     * @param payloadType The class type of the event payload
     * @return A new event store instance
     */
    public <T> EventStore<T> createEventStore(Class<T> payloadType) {
        Objects.requireNonNull(payloadType, "Payload type cannot be null");
        
        logger.info("Creating bi-temporal event store for payload type: {}", payloadType.getSimpleName());
        
        return new PgBiTemporalEventStore<>(peeGeeQManager, payloadType, objectMapper);
    }
    
    /**
     * Creates a new bi-temporal event store for String payloads.
     * This is a convenience method for the common case of string-based events.
     *
     * @return A new event store instance for String payloads
     */
    public EventStore<String> createStringEventStore() {
        return createEventStore(String.class);
    }
    
    /**
     * Creates a new bi-temporal event store for Object payloads.
     * This allows for flexible JSON-based event payloads.
     *
     * @return A new event store instance for Object payloads
     */
    public EventStore<Object> createObjectEventStore() {
        return createEventStore(Object.class);
    }
    
    /**
     * Gets the PeeGeeQ manager used by this factory.
     *
     * @return The PeeGeeQ manager
     */
    public PeeGeeQManager getPeeGeeQManager() {
        return peeGeeQManager;
    }
    
    /**
     * Gets the object mapper used by this factory.
     *
     * @return The object mapper
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
