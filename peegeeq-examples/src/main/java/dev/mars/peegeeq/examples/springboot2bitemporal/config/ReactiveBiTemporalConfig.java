package dev.mars.peegeeq.examples.springboot2bitemporal.config;

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
import dev.mars.peegeeq.examples.springboot2bitemporal.events.SettlementEvent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration for reactive bi-temporal event store.
 * 
 * <p>This configuration demonstrates how to integrate PeeGeeQ's bi-temporal event store
 * with Spring Boot WebFlux for reactive applications.
 * 
 * <p>Key components:
 * <ul>
 *   <li>PeeGeeQManager - Manages Vert.x lifecycle and database connections</li>
 *   <li>BiTemporalEventStoreFactory - Creates event store instances</li>
 *   <li>EventStore&lt;SettlementEvent&gt; - Bi-temporal event store for settlement events</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Configuration
public class ReactiveBiTemporalConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(ReactiveBiTemporalConfig.class);
    
    private PeeGeeQManager manager;
    private EventStore<SettlementEvent> eventStore;
    
    /**
     * Creates and configures the PeeGeeQManager.
     * 
     * <p>The manager handles all Vert.x setup internally and provides the foundation
     * for all PeeGeeQ operations.
     * 
     * @param properties Application properties
     * @param meterRegistry Micrometer registry for metrics
     * @return Configured PeeGeeQManager
     * @throws Exception if initialization fails
     */
    @Bean
    public PeeGeeQManager peeGeeQManager(ReactiveBiTemporalProperties properties, 
                                         MeterRegistry meterRegistry) throws Exception {
        logger.info("Initializing PeeGeeQManager for reactive bi-temporal with profile: {}", 
                   properties.getProfile());
        
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile());
        manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();
        
        logger.info("PeeGeeQManager started successfully");
        return manager;
    }
    
    /**
     * Creates the bi-temporal event store factory.
     * 
     * <p>The factory is used to create type-safe event store instances.
     * 
     * @param manager PeeGeeQManager instance
     * @return BiTemporalEventStoreFactory
     */
    @Bean
    public BiTemporalEventStoreFactory biTemporalEventStoreFactory(PeeGeeQManager manager) {
        logger.info("Creating BiTemporalEventStoreFactory for reactive application");
        return new BiTemporalEventStoreFactory(manager);
    }
    
    /**
     * Creates the settlement event store.
     * 
     * <p>This event store handles settlement instruction lifecycle events following
     * the {entity}.{action}.{state} naming pattern.
     * 
     * @param factory BiTemporalEventStoreFactory
     * @return EventStore for SettlementEvent
     */
    @Bean
    public EventStore<SettlementEvent> settlementEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating SettlementEvent event store");
        eventStore = factory.createEventStore(SettlementEvent.class);
        logger.info("SettlementEvent event store created successfully");
        return eventStore;
    }
    
    /**
     * Cleanup resources on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down reactive bi-temporal event store resources");
        
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

