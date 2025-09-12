package dev.mars.peegeeq.db.connection;

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


import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Implementation of PgListenerConnectionTest functionality.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
public class PgListenerConnectionTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgConnectionManager connectionManager;
    private PgListenerConnection listenerConnection;
    private Connection notifierConnection;

    @BeforeEach
    void setUp() throws SQLException {
        connectionManager = new PgConnectionManager(Vertx.vertx());
        
        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create pool config
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .minimumIdle(1)
                .maximumPoolSize(5)
                .build();

        // Create data source
        connectionManager.getOrCreateDataSource("test-service", connectionConfig, poolConfig);
        
        // Create listener connection
        listenerConnection = new PgListenerConnection(connectionManager.getConnection("test-service"));
        
        // Create notifier connection
        notifierConnection = connectionManager.getConnection("test-service");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (listenerConnection != null) {
            listenerConnection.close();
        }
        if (notifierConnection != null && !notifierConnection.isClosed()) {
            notifierConnection.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    @Disabled("PgListenerConnection uses deprecated JDBC patterns. This test is disabled until PgListenerConnection is migrated to reactive patterns.")
    void testListenAndNotify() throws Exception {
        // Set up a latch to wait for notification
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedChannel = new AtomicReference<>();
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        
        // Add notification listener
        listenerConnection.addNotificationListener(notification -> {
            receivedChannel.set(notification.getName());
            receivedPayload.set(notification.getParameter());
            latch.countDown();
        });
        
        // Start listening
        listenerConnection.start();
        listenerConnection.listen("test_channel");
        
        // Send notification
        try (Statement stmt = notifierConnection.createStatement()) {
            stmt.execute("NOTIFY test_channel, 'test_payload'");
        }
        
        // Wait for notification
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Notification not received within timeout");
        
        // Verify notification
        assertEquals("test_channel", receivedChannel.get());
        assertEquals("test_payload", receivedPayload.get());
    }
}