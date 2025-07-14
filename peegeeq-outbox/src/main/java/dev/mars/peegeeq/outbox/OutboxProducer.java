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


import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Outbox pattern message producer implementation.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OutboxProducer<T> implements MessageProducer<T> {
    private static final Logger logger = LoggerFactory.getLogger(OutboxProducer.class);

    private final PgClientFactory clientFactory;
    private final dev.mars.peegeeq.api.DatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final PeeGeeQMetrics metrics;
    private volatile boolean closed = false;

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        logger.info("Created outbox producer for topic: {}", topic);
    }

    public OutboxProducer(dev.mars.peegeeq.api.DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        logger.info("Created outbox producer for topic: {} (using DatabaseService)", topic);
    }

    @Override
    public CompletableFuture<Void> send(T payload) {
        return send(payload, null, null, null);
    }

    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers) {
        return send(payload, headers, null, null);
    }

    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId) {
        return send(payload, headers, correlationId, null);
    }

    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                DataSource dataSource = getDataSource();
                if (dataSource == null) {
                    throw new RuntimeException("No data source available for topic " + topic);
                }

                String messageId = UUID.randomUUID().toString();
                String payloadJson = objectMapper.writeValueAsString(payload);
                String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : "{}";

                try (Connection conn = dataSource.getConnection()) {
                    String sql = """
                        INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status)
                        VALUES (?, ?::jsonb, ?::jsonb, ?, ?, ?, 'PENDING')
                        """;

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, topic);
                        stmt.setString(2, payloadJson);
                        stmt.setString(3, headersJson);
                        stmt.setString(4, correlationId != null ? correlationId : messageId);
                        stmt.setString(5, messageGroup);
                        stmt.setTimestamp(6, Timestamp.from(OffsetDateTime.now().toInstant()));
                        
                        int rowsAffected = stmt.executeUpdate();
                        if (rowsAffected == 0) {
                            throw new RuntimeException("Failed to insert message into outbox");
                        }
                        
                        logger.debug("Message sent to outbox for topic {}: {}", topic, messageId);
                        
                        // Record metrics
                        if (metrics != null) {
                            metrics.recordMessageSent(topic);
                        }
                        
                        return null;
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to send message to topic {}: {}", topic, e.getMessage());
                throw new RuntimeException("Failed to send message", e);
            }
        });
    }

    private DataSource getDataSource() {
        try {
            if (clientFactory != null) {
                // Use the client factory approach
                var connectionConfig = clientFactory.getConnectionConfig("peegeeq-main");
                var poolConfig = clientFactory.getPoolConfig("peegeeq-main");

                if (connectionConfig == null) {
                    throw new RuntimeException("Connection configuration 'peegeeq-main' not found in PgClientFactory for topic " + topic);
                }

                if (poolConfig == null) {
                    logger.warn("Pool configuration 'peegeeq-main' not found in PgClientFactory for topic {}, using default", topic);
                    poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                }

                return clientFactory.getConnectionManager().getOrCreateDataSource(
                    "outbox-producer",
                    connectionConfig,
                    poolConfig
                );
            } else if (databaseService != null) {
                // Use the database service approach
                var connectionProvider = databaseService.getConnectionProvider();
                if (connectionProvider.hasClient("peegeeq-main")) {
                    return connectionProvider.getDataSource("peegeeq-main");
                } else {
                    throw new RuntimeException("Client 'peegeeq-main' not found in connection provider for topic " + topic);
                }
            } else {
                throw new RuntimeException("Both clientFactory and databaseService are null for topic " + topic);
            }
        } catch (Exception e) {
            logger.error("Failed to get data source for topic {}: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to get data source for topic " + topic, e);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            logger.info("Closed outbox producer for topic: {}", topic);
        }
    }
}
