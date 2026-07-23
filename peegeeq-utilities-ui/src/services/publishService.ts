/**
 * Message publishing against the PeeGeeQ REST API (§12 of the feature design).
 *
 * One endpoint: the batch publish. Any HTTP failure propagates to the caller
 * (the publication engine), which counts it against the consecutive-error
 * guard. (A 404→per-message fallback for "older backend versions" was deleted
 * 2026-07-23: no older backend has ever existed — the scenario was invented,
 * and the fallback was untestable end-to-end.)
 */
import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type { BatchMessageRequest } from '../types/queue'

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
 * Publish a batch of messages.
 *
 * POST /api/v1/queues/{setupId}/{queueName}/messages/batch
 */
export async function publishBatch(
  setupId: string,
  queueName: string,
  req: BatchMessageRequest
): Promise<BatchResponse> {
  const res = await axios.post<{ messagesSent?: number; count?: number }>(
    getVersionedApiUrl(`queues/${setupId}/${queueName}/messages/batch`),
    req,
    { timeout: PUBLISH_TIMEOUT_MS }
  )
  const data = res.data ?? {}
  const sent = data.messagesSent ?? data.count ?? req.messages.length
  return { messagesSent: sent }
}
