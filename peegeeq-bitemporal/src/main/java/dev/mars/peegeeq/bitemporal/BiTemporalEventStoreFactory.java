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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventStoreFactory;
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
public class BiTemporalEventStoreFactory implements EventStoreFactory {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalEventStoreFactory.class);

    private final PeeGeeQManager peeGeeQManager;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new BiTemporalEventStoreFactory.
     *
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param objectMapper   The JSON object mapper
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
        this(peeGeeQManager, createDefaultObjectMapper());
    }

    /**
     * Creates a new bi-temporal event store for the specified payload type.
     *
     * @param <T>         The type of event payload
     * @param payloadType The class type of the event payload
     * @param tableName   The name of the database table to use for event storage
     * @return A new event store instance
     */
    @Override
    public <T> EventStore<T> createEventStore(Class<T> payloadType, String tableName) {
        Objects.requireNonNull(payloadType, "Payload type cannot be null");
        Objects.requireNonNull(tableName, "Table name cannot be null");

        // CRITICAL FIX: Remove manual schema qualification - rely on connection-level search_path
        // The connection pool is configured with search_path in PgConnectionManager.createReactivePool()
        // so all SQL statements will automatically use the correct schema
        logger.info("Creating bi-temporal event store for payload type: {} using table: {}",
                payloadType.getSimpleName(), tableName);

        return new PgBiTemporalEventStore<>(peeGeeQManager, payloadType, tableName, objectMapper);
    }

    @Override
    public String getFactoryName() {
        return "BiTemporalEventStoreFactory";
    }

    /**
     * Creates a new bi-temporal event store for the specified payload type using
     * default table name.
     *
     * @param <T>         The type of event payload
     * @param payloadType The class type of the event payload
     * @return A new event store instance
     * @deprecated Use {@link #createEventStore(Class, String)} to specify table
     *             name explicitly
     */
    @Deprecated
    public <T> EventStore<T> createEventStore(Class<T> payloadType) {
        return createEventStore(payloadType, "bitemporal_event_log");
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

    /**
     * Creates a default ObjectMapper with JSR310 support for Java 8 time types and
     * CloudEvents support.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Add CloudEvents Jackson module support if available on classpath
        try {
            Class<?> jsonFormatClass = Class.forName("io.cloudevents.jackson.JsonFormat");
            Object cloudEventModule = jsonFormatClass.getMethod("getCloudEventJacksonModule").invoke(null);
            if (cloudEventModule instanceof com.fasterxml.jackson.databind.Module) {
                mapper.registerModule((com.fasterxml.jackson.databind.Module) cloudEventModule);
                logger.debug("CloudEvents Jackson module registered successfully");
            }
        } catch (Exception e) {
            logger.debug("CloudEvents Jackson module not available on classpath, skipping registration: {}",
                    e.getMessage());
        }

        return mapper;
    }
}
