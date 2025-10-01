package dev.mars.peegeeq.examples.springboot2.config;

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

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;

/**
 * R2DBC Configuration for Spring Boot Reactive Application.
 * 
 * This configuration class sets up R2DBC for reactive database access with PostgreSQL.
 * 
 * Key Features:
 * - R2DBC PostgreSQL connection factory with connection pooling
 * - Database schema initialization
 * - Repository scanning for reactive repositories
 * - Production-ready connection configuration
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Configuration
@EnableR2dbcRepositories(basePackages = "dev.mars.peegeeq.examples.springboot2.repository")
public class R2dbcConfig extends AbstractR2dbcConfiguration {
    private static final Logger log = LoggerFactory.getLogger(R2dbcConfig.class);

    private final PeeGeeQProperties properties;

    public R2dbcConfig(PeeGeeQProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates the R2DBC connection factory for PostgreSQL.
     * 
     * @return Configured PostgreSQL connection factory
     */
    @Override
    @Bean
    public ConnectionFactory connectionFactory() {
        log.info("Creating R2DBC PostgreSQL connection factory");
        log.info("Database: {}:{}/{}", 
            properties.getDatabase().getHost(),
            properties.getDatabase().getPort(),
            properties.getDatabase().getName());

        PostgresqlConnectionConfiguration config = PostgresqlConnectionConfiguration.builder()
            .host(properties.getDatabase().getHost())
            .port(properties.getDatabase().getPort())
            .database(properties.getDatabase().getName())
            .username(properties.getDatabase().getUsername())
            .password(properties.getDatabase().getPassword())
            .schema(properties.getDatabase().getSchema())
            .build();

        ConnectionFactory factory = new PostgresqlConnectionFactory(config);
        log.info("R2DBC connection factory created successfully");
        
        return factory;
    }

    /**
     * Initializes the database schema on startup.
     * This will create the orders and order_items tables if they don't exist.
     * 
     * @param connectionFactory The R2DBC connection factory
     * @return Database initializer
     */
    @Bean
    public ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        log.info("Setting up database schema initializer");
        
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);
        
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema-springboot2.sql"));
        
        initializer.setDatabasePopulator(populator);
        
        log.info("Database schema initializer configured");
        return initializer;
    }
}

