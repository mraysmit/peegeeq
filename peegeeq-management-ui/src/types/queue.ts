/**
 * TypeScript types for PeeGeeQ Queue Management
 * These types match the backend REST API models
 */

export type QueueType = 'NATIVE' | 'OUTBOX' | 'BITEMPORAL';

export type QueueStatus = 'ACTIVE' | 'PAUSED' | 'IDLE' | 'ERROR';

export type ConsumerMode = 'LISTEN_NOTIFY_ONLY' | 'POLLING_ONLY' | 'HYBRID';

export type MessageState = 'PENDING' | 'PROCESSING' | 'PROCESSED' | 'FAILED' | 'DEAD_LETTER';

export type AckMode = 'AUTO_ACK' | 'MANUAL_ACK' | 'NO_ACK';

/**
 * Base queue configuration (common to all queue types)
 */
export interface QueueConfigBase {
  type: QueueType;
  retentionPeriod?: string; // ISO-8601 duration, e.g., "P7D"
  maxSize?: number;
  deadLetterEnabled?: boolean;
}

/**
 * Native queue-specific configuration
 */
export interface NativeQueueConfig extends QueueConfigBase {
  type: 'NATIVE';
  consumerMode: ConsumerMode;
  pollingInterval?: number; // milliseconds
  batchSize?: number;
  connectionPoolSize?: number;
  enableNotifications?: boolean;
  consumerThreads?: number;
  prefetchCount?: number;
}

/**
 * Outbox queue-specific configuration
 */
export interface OutboxQueueConfig extends QueueConfigBase {
  type: 'OUTBOX';
  visibilityTimeout?: number; // seconds
  maxRetries?: number;
  retryBackoffMultiplier?: number;
  stuckMessageThreshold?: number; // seconds
  cleanupInterval?: number; // seconds
}

/**
 * Bitemporal event store configuration
 */
export interface BitemporalQueueConfig extends QueueConfigBase {
  type: 'BITEMPORAL';
  enableCorrections?: boolean;
  maxVersions?: number;
  compressionEnabled?: boolean;
}

/**
 * Union type for all queue configurations
 */
export type QueueConfig = NativeQueueConfig | OutboxQueueConfig | BitemporalQueueConfig;

/**
 * Queue statistics and metrics
 */
export interface QueueStatistics {
  messageCount: number;
  messagesPerSecond: number;
  consumerCount: number;
  activeConsumers: number;
  processingTime: {
    avg: number;
    p50: number;
    p95: number;
    p99: number;
  };
  errorRate: number;
  queueDepth: number;
  oldestMessageAge?: number; // milliseconds
  newestMessageAge?: number; // milliseconds
}

/**
 * Queue consumer information
 */
export interface QueueConsumer {
  id: string;
  name?: string;
  status: 'ACTIVE' | 'IDLE' | 'DISCONNECTED';
  connectedAt: string; // ISO-8601 timestamp
  lastHeartbeat: string;
  messagesProcessed: number;
  messagesPerSecond: number;
  errorCount: number;
  avgProcessingTime: number;
  partition?: number;
}

/**
 * Message header
 */
export interface MessageHeader {
  key: string;
  value: string;
}

/**
 * Queue message
 */
export interface QueueMessage {
  id: string;
  payload: string; // JSON string or text
  headers: MessageHeader[];
  state?: MessageState;
  createdAt: string;
  updatedAt?: string;
  processedAt?: string;
  retryCount?: number;
  errorMessage?: string;
  routingKey?: string;
  priority?: number;
}

/**
 * Time-series data point for charts
 */
export interface TimeSeriesDataPoint {
  timestamp: string;
  value: number;
}

/**
 * Queue chart data
 */
export interface QueueChartData {
  messageRates: TimeSeriesDataPoint[];
  queueDepth: TimeSeriesDataPoint[];
  errorRates: TimeSeriesDataPoint[];
  processingTimes: TimeSeriesDataPoint[];
}

/**
 * Queue list item (summary view)
 */
export interface Queue {
  setupId: string;
  queueName: string;
  type: QueueType;
  status: QueueStatus;
  messageCount: number;
  consumerCount: number;
  messagesPerSecond: number;
  errorRate: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Detailed queue information
 */
export interface QueueDetails extends Queue {
  config: QueueConfig;
  statistics: QueueStatistics;
  consumers: QueueConsumer[];
  description?: string;
  tags?: string[];
}

/**
 * Queue filters for list view
 */
export interface QueueFilters {
  type?: QueueType[];
  status?: QueueStatus[];
  setupId?: string;
  search?: string;
  sortBy?: 'name' | 'messageCount' | 'createdAt' | 'messagesPerSecond';
  sortOrder?: 'asc' | 'desc';
  page?: number;
  pageSize?: number;
}

/**
 * Paginated queue list response
 */
export interface QueueListResponse {
  queues: Queue[];
  total: number;
  page: number;
  pageSize: number;
}

/**
 * Message browser options
 */
export interface GetMessagesOptions {
  count?: number;
  ackMode?: AckMode;
  offset?: number;
  filter?: string;
}

/**
 * Message browser response
 */
export interface GetMessagesResponse {
  messages: QueueMessage[];
  total: number;
  hasMore: boolean;
}

/**
 * Publish message request
 */
export interface PublishMessageRequest {
  payload: string;
  headers?: MessageHeader[];
  routingKey?: string;
  priority?: number;
}

/**
 * Queue operation request
 */
export interface QueueOperationRequest {
  operation: 'PURGE' | 'DELETE' | 'PAUSE' | 'RESUME';
  options?: {
    ifEmpty?: boolean;
    ifUnused?: boolean;
  };
}

/**
 * Move messages request
 */
export interface MoveMessagesRequest {
  targetSetupId: string;
  targetQueueName: string;
  messageCount?: number;
  filter?: string;
}

/**
 * Performance preset for native queues
 */
export type PerformancePreset = 'HIGH_THROUGHPUT' | 'LOW_LATENCY' | 'RELIABLE';

/**
 * Performance preset configuration
 */
export interface PerformancePresetConfig {
  preset: PerformancePreset;
  description: string;
  config: Partial<NativeQueueConfig>;
}
