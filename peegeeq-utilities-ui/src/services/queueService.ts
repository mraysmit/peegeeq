import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type { CreateQueueRequest, QueueSummary } from '../types/queue'

interface ListQueuesResponse {
  count: number
  queues: string[]
  queueDetails?: Array<{ name: string; implementationType: string | null }>
}

/**
 * List a setup's queues with their per-queue implementation type.
 *
 * GET /api/v1/setups/{setupId}/queues
 *
 * Uses the additive `queueDetails[]` field when present; falls back to the
 * legacy `queues` (names only) array for backward compatibility, in which case
 * the implementation type is reported as null.
 */
export async function listQueueDetails(setupId: string): Promise<QueueSummary[]> {
  const res = await axios.get<ListQueuesResponse>(getVersionedApiUrl(`setups/${setupId}/queues`))
  const data = res.data
  if (Array.isArray(data.queueDetails)) {
    return data.queueDetails.map((q) => ({
      name: q.name,
      implementationType:
        q.implementationType === 'native' || q.implementationType === 'outbox'
          ? q.implementationType
          : null,
    }))
  }
  return (data.queues ?? []).map((name) => ({ name, implementationType: null }))
}

/**
 * Create a queue under a setup, choosing its implementation type.
 *
 * POST /api/v1/setups/{setupId}/queues
 * Throws AxiosError on non-2xx; caller is responsible for error display.
 */
export async function createQueue(setupId: string, req: CreateQueueRequest): Promise<void> {
  await axios.post(getVersionedApiUrl(`setups/${setupId}/queues`), req)
}

/**
 * Delete a queue from a setup.
 *
 * DELETE /api/v1/management/queues/{setupId}/{queueName}
 */
export async function deleteQueue(setupId: string, queueName: string): Promise<void> {
  await axios.delete(getVersionedApiUrl(`management/queues/${setupId}/${queueName}`))
}
