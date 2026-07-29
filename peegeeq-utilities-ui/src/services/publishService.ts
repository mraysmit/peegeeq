/**
 * Message publishing against the PeeGeeQ REST API (§12 of the feature design).
 *
 * One endpoint: the batch publish. Any HTTP failure propagates to the caller
 * (the publication engine), which counts it against the consecutive-error
 * guard. A PARTIAL failure is not an HTTP failure — the server answers 207 and
 * axios resolves — so it is reported through the returned counts instead, and
 * the engine applies the same guard to it. This service reports what the server
 * said; the engine decides what it means. (A 404→per-message fallback for
 * "older backend versions" was deleted 2026-07-23: no older backend has ever
 * existed — the scenario was invented, and the fallback was untestable
 * end-to-end.)
 */
import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type { BatchMessageRequest } from '../types/queue'

/**
 * What the server actually reported for the batch. Both counts come from the
 * response; neither is inferred from the request.
 */
export interface BatchResponse {
  /** Messages the server acknowledged (`successfulMessages`). */
  messagesSent: number
  /** Messages the server rejected (`failedMessages`) — non-zero only on 207. */
  messagesFailed: number
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
 *
 * The server responds `{ totalMessages, successfulMessages, failedMessages, ... }`
 * with status 200 when every message was accepted and **207 when some were
 * rejected** (`QueueHandler.sendMessages`). axios resolves both — 207 is a 2xx —
 * so a partial failure arrives here as a success and must be reported as the
 * mixed result it is.
 *
 * Corrected 2026-07-29: this read `messagesSent ?? count ?? req.messages.length`.
 * The server returns neither `messagesSent` nor `count`, so EVERY call fell
 * through to the request length — the UI counted what it asked for, never what
 * the server accepted, and a 207 was indistinguishable from a clean 200. That
 * is client-side TYPED-ERASURE: a fabricated count the caller cannot tell from
 * a real one. The server half of the same defect is catalogued as entry #90 in
 * the `.recover()` audit (`QueueHandler.java` "FAILED:" markers).
 */
export async function publishBatch(
  setupId: string,
  queueName: string,
  req: BatchMessageRequest
): Promise<BatchResponse> {
  const res = await axios.post<{ successfulMessages?: number; failedMessages?: number }>(
    getVersionedApiUrl(`queues/${setupId}/${queueName}/messages/batch`),
    req,
    { timeout: PUBLISH_TIMEOUT_MS }
  )
  const data = res.data ?? {}
  const sent = data.successfulMessages
  const failed = data.failedMessages
  if (typeof sent !== 'number' || typeof failed !== 'number') {
    // No count to report. Substituting the request length here is what hid the
    // original defect, so this fails loudly instead: the engine records it as a
    // batch error and the run summary stays truthful.
    throw new Error(
      `Batch publish response is missing successfulMessages/failedMessages: ${JSON.stringify(data)}`
    )
  }
  return { messagesSent: sent, messagesFailed: failed }
}
