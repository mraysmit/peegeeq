package dev.mars.peegeeq.api.setup;

public class EventStoreConfig {
    private final String eventStoreName;
    private final String tableName;
    private final String notificationPrefix;
    private final int queryLimit;
    private final boolean metricsEnabled;
    private final Class<?> eventType;
    private final boolean biTemporalEnabled;
    private final String partitionStrategy;
    
    public EventStoreConfig(String eventStoreName, String tableName, String notificationPrefix,
                           int queryLimit, boolean metricsEnabled, Class<?> eventType,
                           boolean biTemporalEnabled, String partitionStrategy) {
        this.eventStoreName = eventStoreName;
        this.tableName = tableName;
        this.notificationPrefix = notificationPrefix != null ? notificationPrefix : "peegeeq_events_";
        this.queryLimit = queryLimit > 0 ? queryLimit : 1000;
        this.metricsEnabled = metricsEnabled;
        this.eventType = eventType;
        this.biTemporalEnabled = biTemporalEnabled;
        this.partitionStrategy = partitionStrategy;
    }
    
    public String getEventStoreName() { return eventStoreName; }
    public String getTableName() { return tableName; }
    public String getNotificationPrefix() { return notificationPrefix; }
    public int getQueryLimit() { return queryLimit; }
    public boolean isMetricsEnabled() { return metricsEnabled; }
    public Class<?> getEventType() { return eventType; }
    public boolean isBiTemporalEnabled() { return biTemporalEnabled; }
    public String getPartitionStrategy() { return partitionStrategy; }
    
    public static class Builder {
        private String eventStoreName;
        private String tableName;
        private String notificationPrefix = "peegeeq_events_";
        private int queryLimit = 1000;
        private boolean metricsEnabled = true;
        private Class<?> eventType = Object.class;
        private boolean biTemporalEnabled = true;
        private String partitionStrategy = "monthly";
        
        public Builder eventStoreName(String eventStoreName) { this.eventStoreName = eventStoreName; return this; }
        public Builder tableName(String tableName) { this.tableName = tableName; return this; }
        public Builder notificationPrefix(String notificationPrefix) { this.notificationPrefix = notificationPrefix; return this; }
        public Builder queryLimit(int queryLimit) { this.queryLimit = queryLimit; return this; }
        public Builder metricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; return this; }
        public Builder eventType(Class<?> eventType) { this.eventType = eventType; return this; }
        public Builder biTemporalEnabled(boolean biTemporalEnabled) { this.biTemporalEnabled = biTemporalEnabled; return this; }
        public Builder partitionStrategy(String partitionStrategy) { this.partitionStrategy = partitionStrategy; return this; }

        public EventStoreConfig build() {
            return new EventStoreConfig(eventStoreName, tableName, notificationPrefix, queryLimit, metricsEnabled, eventType, biTemporalEnabled, partitionStrategy);
        }
    }
}
