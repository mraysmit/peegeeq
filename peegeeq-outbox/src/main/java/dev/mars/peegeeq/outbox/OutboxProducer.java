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
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final PeeGeeQMetrics metrics;
    private volatile boolean closed = false;

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        logger.info("Created outbox producer for topic: {}", topic);
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
                String messageId = UUID.randomUUID().toString();
                String payloadJson = objectMapper.writeValueAsString(payload);
                String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : "{}";

                // Get DataSource through connection manager
                DataSource dataSource = clientFactory.getConnectionManager().getOrCreateDataSource(
                    "outbox-producer",
                    clientFactory.getConnectionConfig("peegeeq-main"),
                    clientFactory.getPoolConfig("peegeeq-main")
                );

                try (Connection conn = dataSource.getConnection()) {
                    String sql = """
                        INSERT INTO outbox (id, topic, payload, headers, correlation_id, message_group, created_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """;

                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, messageId);
                        stmt.setString(2, topic);
                        stmt.setString(3, payloadJson);
                        stmt.setString(4, headersJson);
                        stmt.setString(5, correlationId != null ? correlationId : messageId);
                        stmt.setString(6, messageGroup);
                        stmt.setTimestamp(7, Timestamp.from(OffsetDateTime.now().toInstant()));
                        
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

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            logger.info("Closed outbox producer for topic: {}", topic);
        }
    }
}
