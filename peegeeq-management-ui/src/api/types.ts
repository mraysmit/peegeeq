/**
 * TypeScript types for PeeGeeQ REST API
 * These types match the peegeeq-api Java DTOs
 */

// ============================================================================
// Database Setup Types
// ============================================================================

export type DatabaseSetupStatus = 'CREATING' | 'ACTIVE' | 'DESTROYING' | 'DESTROYED' | 'FAILED';

export interface ConnectionPoolConfig {
  minSize: number;
  maxSize: number;
  maxLifetimeMs: number;
  connectionTimeoutMs: number;
  idleTimeoutMs: number;
}

export interface DatabaseConfig {
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password: string;
  schema: string;
  sslEnabled: boolean;
  templateDatabase?: string;
  encoding: string;
  poolConfig?: ConnectionPoolConfig;
}

export interface QueueConfigDto {
  queueName: string;
  visibilityTimeoutMs?: number;
  maxRetries: number;
  deadLetterEnabled: boolean;
  batchSize: number;
  pollingIntervalMs?: number;
  fifoEnabled: boolean;
  deadLetterQueueName?: string;
}

export interface EventStoreConfigDto {
  eventStoreName: string;
  tableName?: string;
  notificationPrefix: string;
  queryLimit: number;
  metricsEnabled: boolean;
  biTemporalEnabled: boolean;
  partitionStrategy: string;
}

export interface DatabaseSetupRequest {
  setupId: string;
  databaseConfig: DatabaseConfig;
  queues?: QueueConfigDto[];
  eventStores?: EventStoreConfigDto[];
  additionalProperties?: Record<string, unknown>;
}

export interface DatabaseSetupResult {
  setupId: string;
  status: DatabaseSetupStatus;
  connectionUrl?: string;
  createdAt: number;
  queueNames?: string[];
  eventStoreNames?: string[];
}

// ============================================================================
// Dead Letter Types
// ============================================================================

export interface DeadLetterMessageInfo {
  id: number;
  originalTable: string;
  originalId: number;
  topic: string;
  payload: string;
  originalCreatedAt: string; // ISO-8601 timestamp
  failedAt: string; // ISO-8601 timestamp
  failureReason: string;
  retryCount: number;
  headers?: Record<string, string>;
  correlationId?: string;
  messageGroup?: string;
}

export interface DeadLetterStatsInfo {
  totalMessages: number;
  uniqueTopics: number;
  uniqueTables: number;
  oldestFailure?: string; // ISO-8601 timestamp
  newestFailure?: string; // ISO-8601 timestamp
  averageRetryCount: number;
}

export interface DeadLetterListResponse {
  messages: DeadLetterMessageInfo[];
  total: number;
  page: number;
  pageSize: number;
}

// ============================================================================
// Subscription Types
// ============================================================================

export type SubscriptionState = 'ACTIVE' | 'PAUSED' | 'CANCELLED' | 'DEAD';

export interface SubscriptionInfo {
  id?: number;
  topic: string;
  groupName: string;
  state: SubscriptionState;
  subscribedAt: string; // ISO-8601 timestamp
  lastActiveAt: string; // ISO-8601 timestamp
  startFromMessageId?: number;
  startFromTimestamp?: string; // ISO-8601 timestamp
  heartbeatIntervalSeconds: number;
  heartbeatTimeoutSeconds: number;
  lastHeartbeatAt?: string; // ISO-8601 timestamp
  backfillStatus: string;
  backfillCheckpointId?: number;
  backfillProcessedMessages?: number;
  backfillTotalMessages?: number;
  backfillStartedAt?: string; // ISO-8601 timestamp
  backfillCompletedAt?: string; // ISO-8601 timestamp
}

export interface SubscriptionListResponse {
  subscriptions: SubscriptionInfo[];
  total: number;
}

// ============================================================================
// Health Types
// ============================================================================

export type ComponentHealthState = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY';

export interface HealthStatusInfo {
  component: string;
  state: ComponentHealthState;
  message?: string;
  details?: Record<string, unknown>;
  timestamp: string; // ISO-8601 timestamp
}

export interface OverallHealthInfo {
  status: 'UP' | 'DOWN';
  components: Record<string, HealthStatusInfo>;
  timestamp: string; // ISO-8601 timestamp
}

// ============================================================================
// Bi-Temporal Event Types
// ============================================================================

export type EventQuerySortOrder =
  | 'VALID_TIME_ASC'
  | 'VALID_TIME_DESC'
  | 'TRANSACTION_TIME_ASC'
  | 'TRANSACTION_TIME_DESC'
  | 'VERSION_ASC'
  | 'VERSION_DESC';

export interface TemporalRange {
  start?: string; // ISO-8601 timestamp
  end?: string; // ISO-8601 timestamp
  startInclusive: boolean;
  endInclusive: boolean;
}

export interface EventQuery {
  eventType?: string;
  aggregateId?: string;
  correlationId?: string;
  validTimeRange?: TemporalRange;
  transactionTimeRange?: TemporalRange;
  headerFilters?: Record<string, string>;
  limit: number;
  offset: number;
  sortOrder: EventQuerySortOrder;
  includeCorrections: boolean;
  minVersion?: number;
  maxVersion?: number;
}

export interface BiTemporalEvent<T = unknown> {
  eventId: string;
  eventType: string;
  payload: T;
  validTime: string; // ISO-8601 timestamp
  transactionTime: string; // ISO-8601 timestamp
  version: number;
  previousVersionId?: string;
  headers?: Record<string, string>;
  correlationId?: string;
  aggregateId?: string;
  isCorrection: boolean;
  correctionReason?: string;
}

export interface EventQueryResult<T = unknown> {
  events: BiTemporalEvent<T>[];
  total: number;
  hasMore: boolean;
}

export interface AppendEventRequest<T = unknown> {
  eventType: string;
  payload: T;
  validTime?: string; // ISO-8601 timestamp, defaults to now
  headers?: Record<string, string>;
  correlationId?: string;
  aggregateId?: string;
}

export interface CorrectionRequest<T = unknown> {
  originalEventId: string;
  correctedPayload: T;
  correctionReason: string;
  validTime?: string; // ISO-8601 timestamp
}

// ============================================================================
// API Response Types
// ============================================================================

export interface ApiError {
  error: string;
  message: string;
  statusCode: number;
  timestamp: string;
  path?: string;
}

export interface ApiResponse<T> {
  data?: T;
  error?: ApiError;
  success: boolean;
}

// ============================================================================
// Consumer Group Types
// ============================================================================

export interface ConsumerGroupInfo {
  groupName: string;
  queueName: string;
  memberCount: number;
  pendingMessages: number;
  lastActivity: string; // ISO-8601 timestamp
}

export interface ConsumerGroupMemberInfo {
  memberId: string;
  groupName: string;
  joinedAt: string; // ISO-8601 timestamp
  lastHeartbeat: string; // ISO-8601 timestamp
  messagesProcessed: number;
  status: 'ACTIVE' | 'IDLE' | 'DISCONNECTED';
}

export interface ConsumerGroupStats {
  groupName: string;
  totalMembers: number;
  activeMembers: number;
  totalMessagesProcessed: number;
  messagesPerSecond: number;
  avgProcessingTimeMs: number;
}

// ============================================================================
// Webhook Types
// ============================================================================

export interface WebhookSubscriptionRequest {
  webhookUrl: string;
  secret?: string;
  retryPolicy?: {
    maxRetries: number;
    retryDelayMs: number;
  };
  filterExpression?: string;
}

export interface WebhookSubscriptionInfo {
  subscriptionId: string;
  setupId: string;
  queueName: string;
  webhookUrl: string;
  status: 'ACTIVE' | 'PAUSED' | 'FAILED';
  createdAt: string;
  lastDeliveryAt?: string;
  deliveryCount: number;
  failureCount: number;
}

// ============================================================================
// Queue Message Types
// ============================================================================

export interface QueueMessage<T = unknown> {
  messageId: string;
  topic: string;
  payload: T;
  headers?: Record<string, string>;
  timestamp: string;
  deliveryCount: number;
}

export interface SendMessageRequest<T = unknown> {
  payload: T;
  headers?: Record<string, string>;
  delayMs?: number;
}

export interface SendMessageResult {
  messageId: string;
  topic: string;
  timestamp: string;
}

// ============================================================================
// Subscription Options Types
// ============================================================================

export interface SubscriptionOptionsRequest {
  maxRetries?: number;
  retryDelayMs?: number;
  visibilityTimeoutMs?: number;
  batchSize?: number;
  pollIntervalMs?: number;
}

export interface SubscriptionOptionsInfo {
  topic: string;
  groupName: string;
  maxRetries: number;
  retryDelayMs: number;
  visibilityTimeoutMs: number;
  batchSize: number;
  pollIntervalMs: number;
}

// ============================================================================
// Queue Statistics Types
// ============================================================================

export interface QueueStats {
  queueName: string;
  setupId: string;
  messageCount: number;
  consumerCount: number;
  messagesPerSecond: number;
  avgProcessingTimeMs: number;
  pendingMessages: number;
  deadLetterCount: number;
}

export interface QueueConsumerInfo {
  consumerId: string;
  queueName: string;
  groupName?: string;
  connectedAt: string; // ISO-8601 timestamp
  messagesProcessed: number;
  status: 'ACTIVE' | 'IDLE' | 'DISCONNECTED';
}

export interface QueueBindingInfo {
  bindingId: string;
  queueName: string;
  exchangeName?: string;
  routingKey?: string;
  createdAt: string; // ISO-8601 timestamp
}

export interface PurgeQueueResult {
  queueName: string;
  messagesDeleted: number;
}

export interface SetupStatusInfo {
  setupId: string;
  status: DatabaseSetupStatus;
  databaseConnected: boolean;
  queueCount: number;
  eventStoreCount: number;
  lastHealthCheck?: string; // ISO-8601 timestamp
}

export interface QueueListResponse {
  queues: string[];
  count: number;
}

export interface EventStoreListResponse {
  eventStores: string[];
  count: number;
}
