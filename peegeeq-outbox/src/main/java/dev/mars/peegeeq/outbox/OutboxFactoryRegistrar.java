package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Registrar for the outbox queue factory implementation.
 * 
 * This class provides a clean way to register the outbox factory
 * without using reflection or creating circular dependencies.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class OutboxFactoryRegistrar {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxFactoryRegistrar.class);
    
    /**
     * Registers the outbox factory with the provided registrar.
     * 
     * @param registrar The factory registrar to register with
     */
    public static void registerWith(QueueFactoryRegistrar registrar) {
        registrar.registerFactory("outbox", new OutboxFactoryCreator());
        logger.info("Registered outbox queue factory");
    }
    
    /**
     * Unregisters the outbox factory from the provided registrar.
     * 
     * @param registrar The factory registrar to unregister from
     */
    public static void unregisterFrom(QueueFactoryRegistrar registrar) {
        registrar.unregisterFactory("outbox");
        logger.info("Unregistered outbox queue factory");
    }
    
    /**
     * Factory creator for outbox queue factories.
     */
    private static class OutboxFactoryCreator implements QueueFactoryRegistrar.QueueFactoryCreator {
        
        @Override
        public QueueFactory create(DatabaseService databaseService, Map<String, Object> configuration) throws Exception {
            // Extract PeeGeeQConfiguration if available
            PeeGeeQConfiguration peeGeeQConfig = null;
            if (configuration.containsKey("peeGeeQConfiguration")) {
                Object configObj = configuration.get("peeGeeQConfiguration");
                if (configObj instanceof PeeGeeQConfiguration) {
                    peeGeeQConfig = (PeeGeeQConfiguration) configObj;
                }
            }
            
            // Create the factory with proper constructor
            if (peeGeeQConfig != null) {
                return new OutboxFactory(databaseService, peeGeeQConfig);
            } else {
                return new OutboxFactory(databaseService);
            }
        }
    }
}
