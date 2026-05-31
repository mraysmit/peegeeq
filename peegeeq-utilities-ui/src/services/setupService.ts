import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type { CreateSetupRequest, SetupDetails } from '../types/setup'

/**
 * Create a new PeeGeeQ setup (database + schema).
 *
 * Posts to POST /api/v1/database-setup/create.
 * Timeout is 120 s — database creation is slow.
 * Throws AxiosError on non-2xx; caller is responsible for error display.
 */
export async function createSetup(req: CreateSetupRequest): Promise<void> {
  await axios.post(getVersionedApiUrl('database-setup/create'), req, {
    timeout: 120_000,
  })
}

/**
 * List all setup IDs registered in this PeeGeeQ instance.
 *
 * GET /api/v1/setups → { count: number, setupIds: string[] }
 */
export async function getSetups(): Promise<string[]> {
  const res = await axios.get<{ count: number; setupIds: string[] }>(getVersionedApiUrl('setups'))
  return res.data.setupIds
}

/**
 * List queue names for a given setup.
 *
 * GET /api/v1/setups/{setupId}/queues → { count: number, queues: string[] }
 */
export async function getQueues(setupId: string): Promise<string[]> {
  const res = await axios.get<{ count: number; queues: string[] }>(getVersionedApiUrl(`setups/${setupId}/queues`))
  return res.data.queues
}

/**
 * Fetch details for a single setup (queue factories, event stores, status).
 *
 * GET /api/v1/setups/{setupId}
 */
export async function getSetupDetails(setupId: string): Promise<SetupDetails> {
  const res = await axios.get<SetupDetails>(getVersionedApiUrl(`setups/${setupId}`))
  return res.data
}

/**
 * Delete a setup and its associated database.
 *
 * DELETE /api/v1/database-setup/{setupId}
 */
export async function deleteSetup(setupId: string): Promise<void> {
  await axios.delete(getVersionedApiUrl(`database-setup/${setupId}`))
}
