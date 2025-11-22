package dev.mars.peegeeq.api;

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

/**
 * Factory interface for creating EventStore instances.
 * 
 * This abstraction allows different EventStore implementations to be plugged in
 * without the database setup service needing to know about specific implementations.
 * 
 * Implementations should be registered via ServiceLoader or dependency injection.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 1.0
 */
public interface EventStoreFactory {
    
    /**
     * Creates a new event store for the specified payload type and table name.
     *
     * @param <T> The type of event payload
     * @param payloadType The class type of the event payload
     * @param tableName The fully qualified database table name (schema.table)
     * @return A new event store instance
     * @throws IllegalArgumentException if payloadType or tableName is null
     */
    <T> EventStore<T> createEventStore(Class<T> payloadType, String tableName);
    
    /**
     * Returns the name of this factory implementation.
     * Used for logging and diagnostics.
     *
     * @return The factory name (e.g., "BiTemporalEventStoreFactory")
     */
    String getFactoryName();
}
