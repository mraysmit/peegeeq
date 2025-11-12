package dev.mars.peegeeq.db.consumer;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * Represents a message fetched from the outbox table for consumer group processing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class OutboxMessage {
    private final Long id;
    private final String topic;
    private final JsonObject payload;
    private final JsonObject headers;
    private final String correlationId;
    private final String messageGroup;
    private final Instant createdAt;
    private final Integer requiredConsumerGroups;
    private final Integer completedConsumerGroups;

    private OutboxMessage(Builder builder) {
        this.id = builder.id;
        this.topic = builder.topic;
        this.payload = builder.payload;
        this.headers = builder.headers;
        this.correlationId = builder.correlationId;
        this.messageGroup = builder.messageGroup;
        this.createdAt = builder.createdAt;
        this.requiredConsumerGroups = builder.requiredConsumerGroups;
        this.completedConsumerGroups = builder.completedConsumerGroups;
    }

    public Long getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public JsonObject getPayload() {
        return payload;
    }

    public JsonObject getHeaders() {
        return headers;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getMessageGroup() {
        return messageGroup;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getRequiredConsumerGroups() {
        return requiredConsumerGroups;
    }

    public Integer getCompletedConsumerGroups() {
        return completedConsumerGroups;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String topic;
        private JsonObject payload;
        private JsonObject headers;
        private String correlationId;
        private String messageGroup;
        private Instant createdAt;
        private Integer requiredConsumerGroups;
        private Integer completedConsumerGroups;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder payload(JsonObject payload) {
            this.payload = payload;
            return this;
        }

        public Builder headers(JsonObject headers) {
            this.headers = headers;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder messageGroup(String messageGroup) {
            this.messageGroup = messageGroup;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder requiredConsumerGroups(Integer requiredConsumerGroups) {
            this.requiredConsumerGroups = requiredConsumerGroups;
            return this;
        }

        public Builder completedConsumerGroups(Integer completedConsumerGroups) {
            this.completedConsumerGroups = completedConsumerGroups;
            return this;
        }

        public OutboxMessage build() {
            return new OutboxMessage(this);
        }
    }

    @Override
    public String toString() {
        return "OutboxMessage{" +
                "id=" + id +
                ", topic='" + topic + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", messageGroup='" + messageGroup + '\'' +
                ", createdAt=" + createdAt +
                ", requiredConsumerGroups=" + requiredConsumerGroups +
                ", completedConsumerGroups=" + completedConsumerGroups +
                '}';
    }
}

