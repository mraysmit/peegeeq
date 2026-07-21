/**
 * Message publishing against the PeeGeeQ REST API (§12 of the feature design).
 *
 * Prefers the batch endpoint and transparently falls back to single-message
 * publishing when the batch endpoint is unavailable (HTTP 404 on older
 * backend versions).
 */
import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type { BatchMessageRequest, MessageRequest } from '../types/queue'

export interface MessageResponse {
  messageId?: string
}

export interface BatchResponse {
  messagesSent: number
}

/**
 * Every publish request times out. Without one, a hung socket keeps the
 * engine's in-flight fan-out unsettled until the OS timeout — and Stop waits
 * for that settle, so the run would show RUNNING long after Stop was pressed.
 */
export const PUBLISH_TIMEOUT_MS = 30_000

/**
 * Publish a single message.
 *
 * POST /api/v1/queues/{setupId}/{queueName}/messages
 */
export async function publishSingle(
  setupId: string,
  queueName: string,
  req: MessageRequest
): Promise<MessageResponse> {
  const res = await axios.post<MessageResponse>(
    getVersionedApiUrl(`queues/${setupId}/${queueName}/messages`),
    req,
    { timeout: PUBLISH_TIMEOUT_MS }
  )
  return res.data ?? {}
}

/**
 * Publish a batch of messages.
 *
 * POST /api/v1/queues/{setupId}/{queueName}/messages/batch
 *
 * If the batch endpoint returns 404 (older backend), falls back to publishing
 * each message individually via {@link publishSingle}.
 */
export async function publishBatch(
  setupId: string,
  queueName: string,
  req: BatchMessageRequest
): Promise<BatchResponse> {
  try {
    const res = await axios.post<{ messagesSent?: number; count?: number }>(
      getVersionedApiUrl(`queues/${setupId}/${queueName}/messages/batch`),
      req,
      { timeout: PUBLISH_TIMEOUT_MS }
    )
    const data = res.data ?? {}
    const sent = data.messagesSent ?? data.count ?? req.messages.length
    return { messagesSent: sent }
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 404) {
      let sent = 0
      for (const message of req.messages) {
        await publishSingle(setupId, queueName, message)
        sent++
      }
      return { messagesSent: sent }
    }
    throw error
  }
}
