package dev.mars.peegeeq.examples.springbootfinancialfabric.config;

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
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for Financial Fabric event stores.
 *
 * Follows the established DLQ/Retry pattern:
 * - Single properties class with database configuration
 * - System properties configured from Spring properties
 * - Creates 5 domain-specific bi-temporal event stores:
 *   1. Trading Event Store - Trade lifecycle events
 *   2. Settlement Event Store - Settlement instruction events
 *   3. Cash Event Store - Cash movement events
 *   4. Position Event Store - Position update events
 *   5. Regulatory Event Store - Regulatory reporting events
 */
@Configuration
@EnableConfigurationProperties(FinancialFabricProperties.class)
public class FinancialFabricConfig {

    private static final Logger log = LoggerFactory.getLogger(FinancialFabricConfig.class);

    private final FinancialFabricProperties properties;

    public FinancialFabricConfig(FinancialFabricProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates and configures the PeeGeeQ Manager as a Spring bean.
     */
    @Bean
    @Primary
    public PeeGeeQManager peeGeeQManager(
            @Value("${spring.profiles.active:development}") String profile,
            MeterRegistry meterRegistry) {
        log.info("Creating PeeGeeQ Manager for Financial Fabric with profile: {}", profile);

        // Configure system properties from Spring configuration
        configureSystemProperties(properties);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(profile);
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);

        // Start the manager
        manager.start();
        log.info("PeeGeeQ Manager started successfully");

        return manager;
    }

    /**
     * ObjectMapper configured for CloudEvents serialization.
     */
    @Bean
    public ObjectMapper cloudEventObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
    
    /**
     * Trading Event Store - Handles all trading domain events.
     * Event types: trading.{asset-class}.{action}.{state}
     */
    @Bean
    @Qualifier("tradingEventStore")
    public PgBiTemporalEventStore<TradeEvent> tradingEventStore(
            PeeGeeQManager manager,
            ObjectMapper cloudEventObjectMapper) {

        log.info("Creating Trading Event Store");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager, cloudEventObjectMapper);
        return (PgBiTemporalEventStore<TradeEvent>) factory.createEventStore(TradeEvent.class);
    }

    /**
     * Settlement Event Store - Handles settlement instruction events.
     * Event types: instruction.settlement.{state}
     */
    @Bean
    @Qualifier("settlementEventStore")
    public PgBiTemporalEventStore<SettlementInstructionEvent> settlementEventStore(
            PeeGeeQManager manager,
            ObjectMapper cloudEventObjectMapper) {

        log.info("Creating Settlement Event Store");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager, cloudEventObjectMapper);
        return (PgBiTemporalEventStore<SettlementInstructionEvent>) factory.createEventStore(SettlementInstructionEvent.class);
    }

    /**
     * Cash Event Store - Handles cash movement events.
     * Event types: cash.{action}.{state}
     */
    @Bean
    @Qualifier("cashEventStore")
    public PgBiTemporalEventStore<CashMovementEvent> cashEventStore(
            PeeGeeQManager manager,
            ObjectMapper cloudEventObjectMapper) {

        log.info("Creating Cash Event Store");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager, cloudEventObjectMapper);
        return (PgBiTemporalEventStore<CashMovementEvent>) factory.createEventStore(CashMovementEvent.class);
    }

    /**
     * Position Event Store - Handles position update events.
     * Event types: position.{action}.{state}
     */
    @Bean
    @Qualifier("positionEventStore")
    public PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore(
            PeeGeeQManager manager,
            ObjectMapper cloudEventObjectMapper) {

        log.info("Creating Position Event Store");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager, cloudEventObjectMapper);
        return (PgBiTemporalEventStore<PositionUpdateEvent>) factory.createEventStore(PositionUpdateEvent.class);
    }

    /**
     * Regulatory Event Store - Handles regulatory reporting events.
     * Event types: regulatory.{action}.{state}
     */
    @Bean
    @Qualifier("regulatoryEventStore")
    public PgBiTemporalEventStore<RegulatoryReportEvent> regulatoryEventStore(
            PeeGeeQManager manager,
            ObjectMapper cloudEventObjectMapper) {

        log.info("Creating Regulatory Event Store");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager, cloudEventObjectMapper);
        return (PgBiTemporalEventStore<RegulatoryReportEvent>) factory.createEventStore(RegulatoryReportEvent.class);
    }

    /**
     * Configures system properties from Spring Boot configuration.
     * Follows the DLQ/Retry pattern.
     */
    private void configureSystemProperties(FinancialFabricProperties properties) {
        log.debug("Configuring system properties from Spring Boot configuration");

        System.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        System.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        System.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        System.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());

        log.info("System properties configured from Spring configuration");
    }
}

