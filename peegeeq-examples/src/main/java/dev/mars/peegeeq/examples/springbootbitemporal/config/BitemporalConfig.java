package dev.mars.peegeeq.examples.springbootbitemporal.config;

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

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.examples.springbootbitemporal.events.TransactionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration for bi-temporal event store.
 * 
 * <p>This configuration:
 * <ul>
 *   <li>Initializes PeeGeeQManager with configuration</li>
 *   <li>Creates BiTemporalEventStoreFactory</li>
 *   <li>Provides EventStore bean for dependency injection</li>
 *   <li>Manages lifecycle (startup and shutdown)</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
@Configuration
public class BitemporalConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(BitemporalConfig.class);
    
    private PeeGeeQManager manager;
    private EventStore<TransactionEvent> eventStore;
    
    /**
     * Creates and configures the PeeGeeQManager.
     * 
     * @param properties Application properties
     * @param meterRegistry Micrometer registry for metrics
     * @return Configured PeeGeeQManager
     * @throws Exception if initialization fails
     */
    @Bean
    public PeeGeeQManager peeGeeQManager(BitemporalProperties properties, MeterRegistry meterRegistry) throws Exception {
        logger.info("Initializing PeeGeeQManager with profile: {}", properties.getProfile());
        
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile());
        manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();
        
        logger.info("PeeGeeQManager started successfully");
        return manager;
    }
    
    /**
     * Creates the bi-temporal event store factory.
     * 
     * @param manager PeeGeeQManager instance
     * @return BiTemporalEventStoreFactory
     */
    @Bean
    public BiTemporalEventStoreFactory biTemporalEventStoreFactory(PeeGeeQManager manager) {
        logger.info("Creating BiTemporalEventStoreFactory");
        return new BiTemporalEventStoreFactory(manager);
    }
    
    /**
     * Creates the transaction event store.
     * 
     * @param factory BiTemporalEventStoreFactory
     * @return EventStore for TransactionEvent
     */
    @Bean
    public EventStore<TransactionEvent> transactionEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating TransactionEvent event store");
        eventStore = factory.createEventStore(TransactionEvent.class);
        logger.info("TransactionEvent event store created successfully");
        return eventStore;
    }
    
    /**
     * Cleanup resources on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down bi-temporal event store resources");
        
        try {
            if (eventStore != null) {
                eventStore.close();
                logger.info("Event store closed successfully");
            }
        } catch (Exception e) {
            logger.error("Error closing event store", e);
        }
        
        try {
            if (manager != null) {
                manager.close();
                logger.info("PeeGeeQManager closed successfully");
            }
        } catch (Exception e) {
            logger.error("Error closing PeeGeeQManager", e);
        }
    }
}

