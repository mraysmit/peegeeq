import axios from 'axios'
import { getVersionedApiUrl } from './configService'
import type { ConnectSetupRequest, SetupDetails } from '../types/setup'

/**
 * Connect to an EXISTING PeeGeeQ setup (non-destructive).
 *
 * Posts to POST /api/v1/database-setup/connect. This attaches to a setup whose
 * database + schema already exist — it does NOT create or modify anything. The
 * backend reconstitutes queues/event stores from the schema, so the queues/
 * eventStores in the body are ignored.
 *
 * Timeout is 120 s to match the setup lifecycle. Throws AxiosError on non-2xx;
 * caller is responsible for error display.
 */
export async function connectExisting(req: ConnectSetupRequest): Promise<void> {
  await axios.post(getVersionedApiUrl('database-setup/connect'), req, {
    timeout: 120_000,
  })
}

/**
 * List all setup IDs currently connected in this PeeGeeQ instance.
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
