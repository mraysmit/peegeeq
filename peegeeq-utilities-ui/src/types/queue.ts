/**
 * Per-queue implementation type. This is an attribute of a queue (chosen at
 * queue-creation time), not of a setup. The backend reports/accepts the
 * lowercase values "native" and "outbox".
 */
export type QueueImplementationType = 'native' | 'outbox'

/**
 * Lightweight per-queue summary returned by
 * GET /api/v1/setups/{setupId}/queues in the additive `queueDetails[]` field.
 */
export interface QueueSummary {
  name: string
  implementationType: QueueImplementationType | null
}

/**
 * Body for POST /api/v1/setups/{setupId}/queues.
 * Only `queueName` and `implementationType` are required; the rest are optional
 * advanced settings that fall back to backend defaults when omitted.
 */
export interface CreateQueueRequest {
  queueName: string
  implementationType: QueueImplementationType
  maxRetries?: number
  visibilityTimeoutSeconds?: number
  batchSize?: number
  deadLetterEnabled?: boolean
  fifoEnabled?: boolean
}

/** Default advanced settings surfaced in the Create Queue form. */
export const DEFAULT_QUEUE_CONFIG = {
  maxRetries: 3,
  visibilityTimeoutSeconds: 300,
  batchSize: 10,
  deadLetterEnabled: true,
  fifoEnabled: false,
} as const

/** A setup as listed by GET /api/v1/setups. */
export interface SetupInfo {
  setupId: string
  status: string
  queueCount: number
  createdAt: number
}

/** Full per-queue configuration (chosen at queue-creation time). */
export interface QueueInfo {
  name: string
  implementationType: QueueImplementationType // per-queue, chosen at creation
  maxRetries: number
  visibilityTimeoutSeconds: number
  deadLetterEnabled: boolean
  batchSize: number
  fifoEnabled: boolean
}

/**
 * A single message published to a queue.
 * Mirrors the backend `MessageRequest` contract (§4).
 */
export interface MessageRequest {
  payload: object
  headers?: Record<string, string>
  messageType?: string
  correlationId?: string
  priority?: number
  delaySeconds?: number
  messageGroup?: string
}

/**
 * A batch of messages published in a single request.
 * Mirrors the backend `BatchMessageRequest` contract (§4).
 */
export interface BatchMessageRequest {
  messages: MessageRequest[]
  failOnError?: boolean
  maxBatchSize?: number
}
