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

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseService;
import java.util.Map;

/**
 * Interface for registering queue factory implementations.
 * 
 * This interface allows queue implementation modules to register themselves
 * with the queue factory provider without creating circular dependencies.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public interface QueueFactoryRegistrar {
    
    /**
     * Registers a queue factory creator with the provider.
     * 
     * @param implementationType The type of implementation (e.g., "native", "outbox")
     * @param creator The factory creator function
     */
    void registerFactory(String implementationType, QueueFactoryCreator creator);
    
    /**
     * Unregisters a queue factory creator.
     * 
     * @param implementationType The type of implementation to unregister
     */
    void unregisterFactory(String implementationType);
    
    /**
     * Functional interface for creating queue factories.
     * This allows implementations to provide factory creation logic
     * without exposing their concrete classes.
     */
    @FunctionalInterface
    interface QueueFactoryCreator {
        /**
         * Creates a queue factory instance.
         * 
         * @param databaseService The database service to use
         * @param configuration Additional configuration parameters
         * @return A queue factory instance
         * @throws Exception if factory creation fails
         */
        QueueFactory create(DatabaseService databaseService, Map<String, Object> configuration) throws Exception;
    }
}
