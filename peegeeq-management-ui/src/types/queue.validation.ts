/**
 * Runtime validation schemas for Queue types using Zod
 * These schemas validate API responses at runtime to ensure data integrity
 */
import { z } from 'zod';

// Enum schemas - BACKEND RETURNS LOWERCASE VALUES
// Backend returns: "native", "outbox", "bitemporal" (not uppercase)
export const QueueTypeSchema = z.enum(['native', 'outbox', 'bitemporal']);
// Backend returns: "active", "paused", "idle", "error" (not uppercase)
export const QueueStatusSchema = z.enum(['active', 'paused', 'idle', 'error']);
export const ConsumerModeSchema = z.enum(['LISTEN_NOTIFY_ONLY', 'POLLING_ONLY', 'HYBRID']);
export const MessageStateSchema = z.enum(['PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD_LETTER']);
export const AckModeSchema = z.enum(['AUTO_ACK', 'MANUAL_ACK', 'NO_ACK']);

// Message header schema
export const MessageHeaderSchema = z.object({
  key: z.string(),
  value: z.string(),
});

// Queue message schema
export const QueueMessageSchema = z.object({
  id: z.string(),
  payload: z.string(),
  headers: z.array(MessageHeaderSchema),
  state: MessageStateSchema.optional(),
  createdAt: z.string(),
  updatedAt: z.string().optional(),
  processedAt: z.string().optional(),
  retryCount: z.number().optional(),
  errorMessage: z.string().optional(),
  routingKey: z.string().optional(),
  priority: z.number().optional(),
});

// Processing time metrics schema
export const ProcessingTimeSchema = z.object({
  avg: z.number().default(0),
  p50: z.number().default(0),
  p95: z.number().default(0),
  p99: z.number().default(0),
});

// Queue statistics schema
export const QueueStatisticsSchema = z.object({
  messageCount: z.number().default(0),
  messagesPerSecond: z.number().default(0),
  consumerCount: z.number().default(0),
  activeConsumers: z.number().default(0),
  processingTime: ProcessingTimeSchema.default({
    avg: 0,
    p50: 0,
    p95: 0,
    p99: 0,
  }),
  errorRate: z.number().default(0),
  queueDepth: z.number().default(0),
  oldestMessageAge: z.number().optional(),
  newestMessageAge: z.number().optional(),
});

// Queue consumer schema
export const QueueConsumerSchema = z.object({
  id: z.string(),
  name: z.string().optional(),
  status: z.enum(['ACTIVE', 'IDLE', 'DISCONNECTED']),
  connectedAt: z.string(),
  lastHeartbeat: z.string(),
  messagesProcessed: z.number().default(0),
  messagesPerSecond: z.number().default(0),
  errorCount: z.number().default(0),
  avgProcessingTime: z.number().default(0),
  partition: z.number().optional(),
});

// Queue list item schema (for list view)
// Backend returns createdAt as number (timestamp), not string
// Use .optional().default() to handle missing fields properly
export const QueueSchema = z.object({
  setupId: z.string(),
  queueName: z.string(),
  type: QueueTypeSchema,
  status: QueueStatusSchema,
  messageCount: z.number().optional().default(0),
  consumerCount: z.number().optional().default(0),
  messagesPerSecond: z.number().optional().default(0),
  errorRate: z.number().optional().default(0),
  createdAt: z.union([z.number(), z.string()]).transform(val =>
    typeof val === 'number' ? new Date(val).toISOString() : val
  ),
  updatedAt: z.string(),
});

// Queue list response schema
// Backend returns: { queues: [...], queueCount: X, timestamp: Y }
// NOT { queues: [...], total: X, page: X, pageSize: X }
export const QueueListResponseSchema = z.object({
  queues: z.array(QueueSchema),
  queueCount: z.number().optional(),
  timestamp: z.number().optional(),
  // Add optional pagination fields for future compatibility
  total: z.number().optional(),
  page: z.number().optional(),
  pageSize: z.number().optional(),
}).transform(data => ({
  queues: data.queues,
  total: data.total ?? data.queueCount ?? data.queues.length,
  page: data.page ?? 1,
  pageSize: data.pageSize ?? (data.queues.length || 10),
}));

// Get messages response schema
export const GetMessagesResponseSchema = z.object({
  messages: z.array(QueueMessageSchema),
  total: z.number(),
  hasMore: z.boolean(),
});

/**
 * Validates and sanitizes queue list response data
 * Returns validated data with defaults for missing fields
 */
export function validateQueueListResponse(data: unknown) {
  try {
    return QueueListResponseSchema.parse(data);
  } catch (error) {
    console.error('Queue list response validation failed:', error);
    // Return safe default instead of crashing
    return {
      queues: [],
      total: 0,
      page: 1,
      pageSize: 10,
    };
  }
}

/**
 * Validates and sanitizes a single queue object
 * Returns validated data with defaults for missing fields
 */
export function validateQueue(data: unknown) {
  try {
    return QueueSchema.parse(data);
  } catch (error) {
    console.error('Queue validation failed:', error);
    throw error; // Re-throw to let caller handle
  }
}

/**
 * Validates queue statistics
 * Returns validated data with defaults for missing fields
 */
export function validateQueueStatistics(data: unknown) {
  try {
    return QueueStatisticsSchema.parse(data);
  } catch (error) {
    console.error('Queue statistics validation failed:', error);
    // Return safe defaults
    return {
      messageCount: 0,
      messagesPerSecond: 0,
      consumerCount: 0,
      activeConsumers: 0,
      processingTime: { avg: 0, p50: 0, p95: 0, p99: 0 },
      errorRate: 0,
      queueDepth: 0,
    };
  }
}

