
# PostgreSQL LISTEN/NOTIFY for Outbox Pattern: Enterprise Implementation Guide

#### &copy; Mark Andrew Ray-Smith Cityline Ltd 2025

PostgreSQL's LISTEN/NOTIFY mechanism provides a foundation for implementing a message queue that can efficiently process outbox pattern messages. This guide details how to leverage these native PostgreSQL features to create a robust, enterprise-grade solution.

## Understanding PostgreSQL LISTEN/NOTIFY

PostgreSQL's LISTEN/NOTIFY is an asynchronous notification system that allows database clients to:
- Register interest in notifications (LISTEN)
- Send notifications to interested clients (NOTIFY)
- Receive notifications without polling (via asynchronous notification handlers)

This mechanism forms the backbone of our implementation, enabling real-time processing of outbox messages without constant polling.

## Database Schema Design

### Outbox Table Structure

```sql
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    version INT DEFAULT 0,
    headers JSONB
);

CREATE INDEX idx_outbox_status ON outbox(status, created_at);
CREATE INDEX idx_outbox_topic ON outbox(topic, status, created_at);
```

### Trigger Function for Notifications

```sql
CREATE OR REPLACE FUNCTION notify_outbox_change() RETURNS TRIGGER AS $$
BEGIN
    -- Notify about the new message
    PERFORM pg_notify(
        'outbox_channel',
        json_build_object(
            'operation', TG_OP,
            'record_id', NEW.id,
            'topic', NEW.topic
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER outbox_notify_trigger
AFTER INSERT ON outbox
FOR EACH ROW
EXECUTE FUNCTION notify_outbox_change();
```

## Core Components for Enterprise Implementation

### 1. Connection Management

```java
public class PgConnectionManager implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final Map<String, PgConnection> listenerConnections = new ConcurrentHashMap<>();
    
    public PgConnectionManager(HikariConfig config) {
        this.dataSource = new HikariDataSource(config);
    }
    
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    public PgConnection getListenerConnection(String listenerId) throws SQLException {
        return listenerConnections.computeIfAbsent(listenerId, id -> {
            try {
                // Create a dedicated connection for listening
                PgConnection conn = dataSource.getConnection().unwrap(PgConnection.class);
                // Enable automatic reconnection
                conn.addConnectionEventListener(new ReconnectionListener(this, listenerId));
                return conn;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create listener connection", e);
            }
        });
    }
    
    @Override
    public void close() {
        listenerConnections.values().forEach(this::safelyCloseConnection);
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
    
    private void safelyCloseConnection(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            // Log error
        }
    }
}
```

### 2. Notification Listener

```java
public class OutboxNotificationListener implements AutoCloseable {
    private final PgConnectionManager connectionManager;
    private final String listenerId;
    private final Map<String, Consumer<String>> topicHandlers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean running = false;
    
    public OutboxNotificationListener(PgConnectionManager connectionManager, String listenerId) {
        this.connectionManager = connectionManager;
        this.listenerId = listenerId;
    }
    
    public void registerTopicHandler(String topic, Consumer<String> handler) {
        topicHandlers.put(topic, handler);
    }
    
    public void start() throws SQLException {
        if (running) return;
        
        PgConnection conn = connectionManager.getListenerConnection(listenerId);
        
        // Set up the notification handler
        conn.addNotificationListener(notification -> {
            try {
                JsonNode payload = objectMapper.readTree(notification.getParameter());
                String topic = payload.get("topic").asText();
                String recordId = payload.get("record_id").asText();
                
                Consumer<String> handler = topicHandlers.get(topic);
                if (handler != null) {
                    handler.accept(recordId);
                }
            } catch (Exception e) {
                // Log error
            }
        });
        
        // Start listening
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("LISTEN outbox_channel");
        }
        
        running = true;
    }
    
    @Override
    public void close() {
        running = false;
        // Cleanup will be handled by connection manager
    }
}
```

### 3. Message Producer

```java
public class OutboxMessageProducer<T> {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String tableName;
    
    public OutboxMessageProducer(DataSource dataSource, ObjectMapper objectMapper, String tableName) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
    }
    
    @Transactional
    public void send(String topic, T message, Map<String, String> headers) throws Exception {
        String sql = String.format(
            "INSERT INTO %s (topic, payload, headers) VALUES (?, ?::jsonb, ?::jsonb)",
            tableName
        );
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, topic);
            stmt.setString(2, objectMapper.writeValueAsString(message));
            stmt.setString(3, objectMapper.writeValueAsString(headers));
            stmt.executeUpdate();
            
            // The notification will be triggered automatically by the database trigger
        }
    }
    
    /**
     * Sends a message as part of an existing transaction
     */
    public void sendWithinTransaction(Connection conn, String topic, T message, 
                                     Map<String, String> headers) throws Exception {
        String sql = String.format(
            "INSERT INTO %s (topic, payload, headers) VALUES (?, ?::jsonb, ?::jsonb)",
            tableName
        );
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, topic);
            stmt.setString(2, objectMapper.writeValueAsString(message));
            stmt.setString(3, objectMapper.writeValueAsString(headers));
            stmt.executeUpdate();
        }
    }
}
```

### 4. Message Consumer

```java
public class OutboxMessageConsumer<T> {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final Class<T> messageType;
    private final OutboxNotificationListener notificationListener;
    private final ExecutorService processingExecutor;
    
    public OutboxMessageConsumer(DataSource dataSource, ObjectMapper objectMapper,
                                String tableName, Class<T> messageType,
                                OutboxNotificationListener notificationListener,
                                int threadPoolSize) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
        this.messageType = messageType;
        this.notificationListener = notificationListener;
        this.processingExecutor = Executors.newFixedThreadPool(threadPoolSize);
    }
    
    public void subscribe(String topic, Consumer<Message<T>> messageHandler) throws SQLException {
        // Register handler for notifications about this topic
        notificationListener.registerTopicHandler(topic, recordId -> {
            processingExecutor.submit(() -> processMessage(topic, recordId, messageHandler));
        });
    }
    
    private void processMessage(String topic, String recordId, Consumer<Message<T>> handler) {
        try (Connection conn = dataSource.getConnection()) {
            // Use advisory lock to ensure exclusive processing
            if (!acquireAdvisoryLock(conn, recordId)) {
                return; // Another consumer is processing this message
            }
            
            try {
                // Fetch the message
                OutboxMessage<T> message = fetchMessage(conn, recordId);
                if (message != null) {
                    // Process the message
                    handler.accept(message);
                    // Mark as processed
                    markAsProcessed(conn, recordId);
                }
            } finally {
                releaseAdvisoryLock(conn, recordId);
            }
        } catch (Exception e) {
            // Log error and potentially retry or move to DLQ
        }
    }
    
    private boolean acquireAdvisoryLock(Connection conn, String recordId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtext(?))")) {
            stmt.setString(1, recordId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
    
    private void releaseAdvisoryLock(Connection conn, String recordId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT pg_advisory_unlock(hashtext(?))")) {
            stmt.setString(1, recordId);
            stmt.executeQuery();
        }
    }
    
    private OutboxMessage<T> fetchMessage(Connection conn, String recordId) throws Exception {
        String sql = String.format(
            "SELECT id, topic, payload, created_at, headers FROM %s WHERE id = ? AND status = 'PENDING'",
            tableName
        );
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, recordId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString("id");
                    T payload = objectMapper.readValue(rs.getString("payload"), messageType);
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    Map<String, String> headers = objectMapper.readValue(
                        rs.getString("headers"), 
                        new TypeReference<Map<String, String>>() {}
                    );
                    
                    return new OutboxMessage<>(id, payload, createdAt, headers);
                }
                return null;
            }
        }
    }
    
    private void markAsProcessed(Connection conn, String recordId) throws SQLException {
        String sql = String.format(
            "UPDATE %s SET status = 'PROCESSED', processed_at = NOW() WHERE id = ?",
            tableName
        );
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, recordId);
            stmt.executeUpdate();
        }
    }
    
    public void close() {
        processingExecutor.shutdown();
    }
}
```

### 5. Retry and Error Handling

```java
public class OutboxRetryManager {
    private final DataSource dataSource;
    private final String tableName;
    private final ScheduledExecutorService scheduler;
    private final int maxRetries;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    
    public OutboxRetryManager(DataSource dataSource, String tableName, 
                             int maxRetries, Duration initialRetryDelay, 
                             Duration maxRetryDelay) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.maxRetries = maxRetries;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    public void start() {
        // Schedule retry job
        scheduler.scheduleWithFixedDelay(
            this::processFailedMessages,
            initialRetryDelay.toMillis(),
            initialRetryDelay.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }
    
    private void processFailedMessages() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 String.format("SELECT id FROM %s WHERE status = 'FAILED' AND " +
                              "retry_count < ? AND next_retry_at <= NOW()", 
                              tableName))) {
            
            stmt.setInt(1, maxRetries);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String messageId = rs.getString("id");
                    retryMessage(messageId);
                }
            }
        } catch (Exception e) {
            // Log error
        }
    }
    
    public void markAsFailed(String messageId, Throwable error) {
        try (Connection conn = dataSource.getConnection()) {
            // Get current retry count
            int retryCount = getCurrentRetryCount(conn, messageId);
            
            // Calculate next retry time with exponential backoff
            Duration delay = calculateBackoffDelay(retryCount);
            Instant nextRetry = Instant.now().plus(delay);
            
            // Update the message
            try (PreparedStatement stmt = conn.prepareStatement(
                 String.format("UPDATE %s SET status = ?, retry_count = ?, " +
                              "next_retry_at = ? WHERE id = ?", 
                              tableName))) {
                
                String status = retryCount >= maxRetries ? "DEAD_LETTER" : "FAILED";
                stmt.setString(1, status);
                stmt.setInt(2, retryCount + 1);
                stmt.setTimestamp(3, Timestamp.from(nextRetry));
                stmt.setString(4, messageId);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            // Log error
        }
    }
    
    private int getCurrentRetryCount(Connection conn, String messageId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
             String.format("SELECT retry_count FROM %s WHERE id = ?", tableName))) {
            
            stmt.setString(1, messageId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("retry_count");
                }
                return 0;
            }
        }
    }
    
    private Duration calculateBackoffDelay(int retryCount) {
        // Exponential backoff with jitter
        long delayMillis = (long) (initialRetryDelay.toMillis() * Math.pow(2, retryCount));
        delayMillis = Math.min(delayMillis, maxRetryDelay.toMillis());
        
        // Add jitter (±20%)
        double jitter = 0.8 + Math.random() * 0.4;
        return Duration.ofMillis((long) (delayMillis * jitter));
    }
    
    private void retryMessage(String messageId) {
        try (Connection conn = dataSource.getConnection()) {
            // Reset the message status to PENDING
            try (PreparedStatement stmt = conn.prepareStatement(
                 String.format("UPDATE %s SET status = 'PENDING', " +
                              "next_retry_at = NULL WHERE id = ?", 
                              tableName))) {
                
                stmt.setString(1, messageId);
                stmt.executeUpdate();
            }
            
            // The notification trigger will fire automatically
        } catch (Exception e) {
            // Log error
        }
    }
    
    public void close() {
        scheduler.shutdown();
    }
}
```

### 6. Cleanup Service

```java
public class OutboxCleanupService {
    private final DataSource dataSource;
    private final String tableName;
    private final Duration retentionPeriod;
    private final ScheduledExecutorService scheduler;
    private final int batchSize;
    
    public OutboxCleanupService(DataSource dataSource, String tableName, 
                               Duration retentionPeriod, int batchSize) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.retentionPeriod = retentionPeriod;
        this.batchSize = batchSize;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    public void start() {
        // Schedule cleanup job
        scheduler.scheduleWithFixedDelay(
            this::cleanupProcessedMessages,
            1,
            24,
            TimeUnit.HOURS
        );
    }
    
    private void cleanupProcessedMessages() {
        try (Connection conn = dataSource.getConnection()) {
            Instant cutoffTime = Instant.now().minus(retentionPeriod);
            
            // Use batch deletion to avoid long-running transactions
            int totalDeleted = 0;
            int deleted;
            
            do {
                try (PreparedStatement stmt = conn.prepareStatement(
                     String.format("DELETE FROM %s WHERE status = 'PROCESSED' " +
                                  "AND processed_at < ? LIMIT ?", 
                                  tableName))) {
                    
                    stmt.setTimestamp(1, Timestamp.from(cutoffTime));
                    stmt.setInt(2, batchSize);
                    deleted = stmt.executeUpdate();
                    totalDeleted += deleted;
                    
                    // Small pause between batches to reduce database pressure
                    if (deleted > 0) {
                        Thread.sleep(100);
                    }
                }
            } while (deleted > 0);
            
            // Log cleanup results
            System.out.println("Cleaned up " + totalDeleted + " processed messages");
        } catch (Exception e) {
            // Log error
        }
    }
    
    public void close() {
        scheduler.shutdown();
    }
}
```

### 7. Monitoring and Metrics

```java
public class OutboxMetrics {
    private final DataSource dataSource;
    private final String tableName;
    private final MeterRegistry registry;
    
    public OutboxMetrics(DataSource dataSource, String tableName, MeterRegistry registry) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.registry = registry;
    }
    
    public void registerMetrics() {
        // Queue depth gauge
        registry.gauge("outbox.queue.depth", Tags.of("status", "pending"), this, m -> m.getQueueDepth("PENDING"));
        registry.gauge("outbox.queue.depth", Tags.of("status", "failed"), this, m -> m.getQueueDepth("FAILED"));
        registry.gauge("outbox.queue.depth", Tags.of("status", "dead_letter"), this, m -> m.getQueueDepth("DEAD_LETTER"));
        
        // Message age histogram
        registry.more().timeGauge("outbox.message.age", Tags.of("status", "pending"), this, TimeUnit.SECONDS, m -> m.getOldestMessageAge("PENDING"));
    }
    
    public double getQueueDepth(String status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 String.format("SELECT COUNT(*) FROM %s WHERE status = ?", tableName))) {
            
            stmt.setString(1, status);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (Exception e) {
            // Log error
        }
        return 0;
    }
    
    public double getOldestMessageAge(String status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 String.format("SELECT EXTRACT(EPOCH FROM (NOW() - MIN(created_at))) " +
                              "FROM %s WHERE status = ?", tableName))) {
            
            stmt.setString(1, status);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (Exception e) {
            // Log error
        }
        return 0;
    }
}
```

## Advanced PostgreSQL Features for Enterprise Implementations

### 1. Partitioning for High-Volume Queues

```sql
-- Create partitioned outbox table
CREATE TABLE outbox (
    id BIGSERIAL,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    version INT DEFAULT 0,
    headers JSONB
) PARTITION BY RANGE (created_at);

-- Create partitions
CREATE TABLE outbox_current PARTITION OF outbox
    FOR VALUES FROM (NOW() - INTERVAL '1 day') TO (NOW() + INTERVAL '30 days');
    
CREATE TABLE outbox_archive PARTITION OF outbox
    FOR VALUES FROM (NOW() - INTERVAL '90 days') TO (NOW() - INTERVAL '1 day');

-- Create indexes on partitions
CREATE INDEX idx_outbox_current_status ON outbox_current(status, created_at);
CREATE INDEX idx_outbox_archive_status ON outbox_archive(status, created_at);
```

### 2. Using JSON Operators for Message Filtering

```sql
-- Create a function to filter messages by payload content
CREATE OR REPLACE FUNCTION get_messages_by_content(content_filter JSONB)
RETURNS TABLE (
    id BIGINT,
    topic VARCHAR,
    payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE
) AS $$
BEGIN
    RETURN QUERY
    SELECT o.id, o.topic, o.payload, o.created_at
    FROM outbox o
    WHERE o.status = 'PENDING'
    AND o.payload @> content_filter
    ORDER BY o.created_at
    LIMIT 100;
END;
$$ LANGUAGE plpgsql;
```

### 3. Implementing Delayed Messages

```sql
-- Create a function to schedule delayed messages
CREATE OR REPLACE FUNCTION schedule_delayed_message(
    p_topic VARCHAR,
    p_payload JSONB,
    p_headers JSONB,
    p_delay_seconds INT
) RETURNS BIGINT AS $$
DECLARE
    v_id BIGINT;
BEGIN
    INSERT INTO outbox (
        topic, 
        payload, 
        headers, 
        status, 
        next_retry_at
    ) VALUES (
        p_topic,
        p_payload,
        p_headers,
        'SCHEDULED',
        NOW() + (p_delay_seconds * INTERVAL '1 second')
    ) RETURNING id INTO v_id;
    
    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

-- Create a function to activate scheduled messages
CREATE OR REPLACE FUNCTION activate_scheduled_messages() RETURNS INT AS $$
DECLARE
    v_count INT;
BEGIN
    UPDATE outbox
    SET status = 'PENDING', next_retry_at = NULL
    WHERE status = 'SCHEDULED'
    AND next_retry_at <= NOW();
    
    GET DIAGNOSTICS v_count = ROW_COUNT;
    
    IF v_count > 0 THEN
        PERFORM pg_notify('outbox_channel', '{"operation":"scheduled_activation"}');
    END IF;
    
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;
```

## Enterprise Integration Patterns

### 1. Dead Letter Channel

```java
public class DeadLetterHandler<T> {
    private final DataSource dataSource;
    private final String tableName;
    private final ObjectMapper objectMapper;
    private final Consumer<OutboxMessage<T>> deadLetterConsumer;
    
    public DeadLetterHandler(DataSource dataSource, String tableName, 
                            ObjectMapper objectMapper,
                            Consumer<OutboxMessage<T>> deadLetterConsumer) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.objectMapper = objectMapper;
        this.deadLetterConsumer = deadLetterConsumer;
    }
    
    public void processDeadLetters() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 String.format("SELECT id, topic, payload, created_at, headers, retry_count " +
                              "FROM %s WHERE status = 'DEAD_LETTER' " +
                              "ORDER BY created_at LIMIT 100", 
                              tableName))) {
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Process each dead letter message
                    String id = rs.getString("id");
                    String topic = rs.getString("topic");
                    String payloadJson = rs.getString("payload");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    String headersJson = rs.getString("headers");
                    int retryCount = rs.getInt("retry_count");
                    
                    // Parse the message
                    T payload = objectMapper.readValue(payloadJson, (Class<T>)Object.class);
                    Map<String, String> headers = objectMapper.readValue(
                        headersJson, new TypeReference<Map<String, String>>() {}
                    );
                    
                    // Add metadata about failures
                    headers.put("x-retry-count", String.valueOf(retryCount));
                    headers.put("x-original-topic", topic);
                    
                    OutboxMessage<T> deadLetter = new OutboxMessage<>(id, payload, createdAt, headers);
                    
                    // Process the dead letter
                    deadLetterConsumer.accept(deadLetter);
                    
                    // Mark as processed
                    markDeadLetterAsProcessed(conn, id);
                }
            }
        } catch (Exception e) {
            // Log error
        }
    }
    
    private void markDeadLetterAsProcessed(Connection conn, String messageId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
             String.format("UPDATE %s SET status = 'DEAD_LETTER_PROCESSED', " +
                          "processed_at = NOW() WHERE id = ?", 
                          tableName))) {
            
            stmt.setString(1, messageId);
            stmt.executeUpdate();
        }
    }
}
```

### 2. Message Routing

```java
public class TopicRouter<T> {
    private final Map<String, List<Consumer<OutboxMessage<T>>>> topicHandlers = new ConcurrentHashMap<>();
    
    public void registerHandler(String topic, Consumer<OutboxMessage<T>> handler) {
        topicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
    
    public void routeMessage(OutboxMessage<T> message, String topic) {
        List<Consumer<OutboxMessage<T>>> handlers = topicHandlers.get(topic);
        if (handlers != null && !handlers.isEmpty()) {
            for (Consumer<OutboxMessage<T>> handler : handlers) {
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    // Log error but continue with other handlers
                }
            }
        }
    }
}
```

## Performance Optimization Techniques

### 1. Batch Processing

```java
public class BatchOutboxProcessor<T> {
    private final DataSource dataSource;
    private final String tableName;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final Class<T> messageType;
    
    public BatchOutboxProcessor(DataSource dataSource, String tableName,
                               ObjectMapper objectMapper, int batchSize,
                               Class<T> messageType) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.messageType = messageType;
    }
    
    public List<OutboxMessage<T>> fetchBatch(String topic) throws Exception {
        List<OutboxMessage<T>> messages = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection()) {
            // Begin transaction
            conn.setAutoCommit(false);
            
            try {
                // Lock and fetch messages
                String sql = String.format(
                    "UPDATE %s SET status = 'PROCESSING' " +
                    "WHERE id IN (" +
                    "  SELECT id FROM %s " +
                    "  WHERE status = 'PENDING' AND topic = ? " +
                    "  ORDER BY created_at " +
                    "  FOR UPDATE SKIP LOCKED " +
                    "  LIMIT ?" +
                    ") RETURNING id, payload, created_at, headers",
                    tableName, tableName
                );
                
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, topic);
                    stmt.setInt(2, batchSize);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String id = rs.getString("id");
                            T payload = objectMapper.readValue(rs.getString("payload"), messageType);
                            Instant createdAt = rs.getTimestamp("created_at").toInstant();
                            Map<String, String> headers = objectMapper.readValue(
                                rs.getString("headers"),
                                new TypeReference<Map<String, String>>() {}
                            );
                            
                            messages.add(new OutboxMessage<>(id, payload, createdAt, headers));
                        }
                    }
                }
                
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        
        return messages;
    }
    
    public void markBatchAsProcessed(List<String> messageIds) throws SQLException {
        if (messageIds.isEmpty()) return;
        
        try (Connection conn = dataSource.getConnection()) {
            String placeholders = String.join(",", Collections.nCopies(messageIds.size(), "?"));
            String sql = String.format(
                "UPDATE %s SET status = 'PROCESSED', processed_at = NOW() " +
                "WHERE id IN (%s)",
                tableName, placeholders
            );
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < messageIds.size(); i++) {
                    stmt.setString(i + 1, messageIds.get(i));
                }
                stmt.executeUpdate();
            }
        }
    }
}
```

## Putting It All Together: Enterprise Implementation

```java
public class PostgresOutboxSystem<T> implements AutoCloseable {
    private final PgConnectionManager connectionManager;
    private final OutboxMessageProducer<T> producer;
    private final OutboxNotificationListener notificationListener;
    private final OutboxMessageConsumer<T> consumer;
    private final OutboxRetryManager retryManager;
    private final OutboxCleanupService cleanupService;
    private final OutboxMetrics metrics;
    private final TopicRouter<T> router;
    private final DeadLetterHandler<T> deadLetterHandler;
    
    // Constructor and initialization methods
    
    public void start() throws SQLException {
        notificationListener.start();
        retryManager.start();
        cleanupService.start();
        metrics.registerMetrics();
    }
    
    public void send(String topic, T message) throws Exception {
        producer.send(topic, message, Collections.emptyMap());
    }
    
    public void send(String topic, T message, Map<String, String> headers) throws Exception {
        producer.send(topic, message, headers);
    }
    
    public void subscribe(String topic, Consumer<OutboxMessage<T>> handler) throws SQLException {
        router.registerHandler(topic, handler);
        consumer.subscribe(topic, message -> router.routeMessage(message, topic));
    }
    
    public void registerDeadLetterConsumer(Consumer<OutboxMessage<T>> deadLetterConsumer) {
        // Set up dead letter handling
    }
    
    @Override
    public void close() {
        // Close all components in reverse order of creation
        deadLetterHandler = null;
        if (router != null) router = null;
        if (metrics != null) metrics = null;
        if (cleanupService != null) cleanupService.close();
        if (retryManager != null) retryManager.close();
        if (consumer != null) consumer.close();
        if (notificationListener != null) notificationListener.close();
        if (producer != null) producer = null;
        if (connectionManager != null) connectionManager.close();
    }
}
```

## Operational Considerations

### 1. High Availability Setup

For enterprise deployments, consider:
- Multiple consumer instances with leader election
- Connection pooling with proper sizing
- Database replication for read scaling
- Monitoring and alerting on queue metrics

### 2. Security Considerations

- Encrypt sensitive payloads before storing
- Use column-level encryption for sensitive data
- Implement proper access controls on the outbox table
- Audit logging for message processing

### 3. Performance Tuning

- Optimize PostgreSQL for write-heavy workloads
- Configure appropriate WAL settings
- Use UNLOGGED tables for temporary message storage
- Consider table partitioning for historical messages
- Implement proper indexing strategies

## Conclusion

PostgreSQL's LISTEN/NOTIFY mechanism, combined with triggers and advisory locks, provides a powerful foundation for implementing the outbox pattern in enterprise applications. This approach offers several advantages:

1. **Transactional integrity**: Messages are stored and processed with ACID guarantees
2. **Real-time processing**: LISTEN/NOTIFY enables immediate message processing without polling
3. **Scalability**: Multiple consumers can process messages concurrently with proper locking
4. **Reliability**: Built-in retry mechanisms and dead letter handling ensure no messages are lost
5. **Simplicity**: Leveraging the existing database reduces infrastructure complexity

By implementing the components described in this guide, organizations can build a robust, enterprise-grade message queue using PostgreSQL's native features, perfectly suited for implementing the outbox pattern in distributed systems.