package dev.mars.peegeeq.api.setup;

import java.time.Duration;

public class QueueConfig {
    private final String queueName;
    private final Duration visibilityTimeout;
    private final int maxRetries;
    private final boolean deadLetterEnabled;
    private final int batchSize;
    private final Duration pollingInterval;
    private final boolean fifoEnabled;
    private final String deadLetterQueueName;

    public QueueConfig(String queueName, Duration visibilityTimeout, int maxRetries,
                      boolean deadLetterEnabled, int batchSize, Duration pollingInterval,
                      boolean fifoEnabled, String deadLetterQueueName) {
        this.queueName = queueName;
        this.visibilityTimeout = visibilityTimeout != null ? visibilityTimeout : Duration.ofMinutes(5);
        this.maxRetries = maxRetries;
        this.deadLetterEnabled = deadLetterEnabled;
        this.batchSize = batchSize > 0 ? batchSize : 10;
        this.pollingInterval = pollingInterval != null ? pollingInterval : Duration.ofSeconds(1);
        this.fifoEnabled = fifoEnabled;
        this.deadLetterQueueName = deadLetterQueueName;
    }

    public String getQueueName() { return queueName; }
    public Duration getVisibilityTimeout() { return visibilityTimeout; }
    public int getMaxRetries() { return maxRetries; }
    public boolean isDeadLetterEnabled() { return deadLetterEnabled; }
    public int getBatchSize() { return batchSize; }
    public Duration getPollingInterval() { return pollingInterval; }
    public boolean isFifoEnabled() { return fifoEnabled; }
    public String getDeadLetterQueueName() { return deadLetterQueueName; }

    public static class Builder {
        private String queueName;
        private Duration visibilityTimeout = Duration.ofMinutes(5);
        private int maxRetries = 3;
        private boolean deadLetterEnabled = true;
        private int batchSize = 10;
        private Duration pollingInterval = Duration.ofSeconds(1);
        private boolean fifoEnabled = false;
        private String deadLetterQueueName;

        public Builder queueName(String queueName) { this.queueName = queueName; return this; }
        public Builder visibilityTimeout(Duration visibilityTimeout) { this.visibilityTimeout = visibilityTimeout; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder deadLetterEnabled(boolean deadLetterEnabled) { this.deadLetterEnabled = deadLetterEnabled; return this; }
        public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }
        public Builder pollingInterval(Duration pollingInterval) { this.pollingInterval = pollingInterval; return this; }
        public Builder fifoEnabled(boolean fifoEnabled) { this.fifoEnabled = fifoEnabled; return this; }
        public Builder deadLetterQueueName(String deadLetterQueueName) { this.deadLetterQueueName = deadLetterQueueName; return this; }

        public QueueConfig build() {
            return new QueueConfig(queueName, visibilityTimeout, maxRetries, deadLetterEnabled,
                                 batchSize, pollingInterval, fifoEnabled, deadLetterQueueName);
        }
    }
}
