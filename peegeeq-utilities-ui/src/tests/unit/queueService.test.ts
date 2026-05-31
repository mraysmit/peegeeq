/**
 * Tests for queueService.ts
 *
 * Uses real axios with vi.mock to intercept HTTP at the adapter level.
 * No mocking of business logic — only network boundary.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import { listQueueDetails, createQueue, deleteQueue } from '../../services/queueService'
import type { CreateQueueRequest } from '../../types/queue'

vi.mock('axios')
const mockedAxios = vi.mocked(axios, true)

const VALID_CREATE_REQUEST: CreateQueueRequest = {
  queueName: 'orders',
  implementationType: 'native',
  maxRetries: 3,
  visibilityTimeoutSeconds: 300,
  batchSize: 10,
  deadLetterEnabled: true,
  fifoEnabled: false,
}

describe('queueService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // ── listQueueDetails ─────────────────────────────────────────────────────────

  describe('listQueueDetails', () => {
    it('GETs /api/v1/setups/{setupId}/queues and maps queueDetails', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          count: 2,
          queues: ['orders', 'payments'],
          queueDetails: [
            { name: 'orders', implementationType: 'native' },
            { name: 'payments', implementationType: 'outbox' },
          ],
        },
      })

      const result = await listQueueDetails('my-setup')

      expect(mockedAxios.get).toHaveBeenCalledWith(
        expect.stringContaining('/setups/my-setup/queues')
      )
      expect(result).toEqual([
        { name: 'orders', implementationType: 'native' },
        { name: 'payments', implementationType: 'outbox' },
      ])
    })

    it('normalizes an unknown implementationType to null', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: {
          count: 1,
          queues: ['orders'],
          queueDetails: [{ name: 'orders', implementationType: 'mystery' }],
        },
      })

      const result = await listQueueDetails('my-setup')

      expect(result).toEqual([{ name: 'orders', implementationType: null }])
    })

    it('falls back to names-only when queueDetails is absent', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({
        data: { count: 2, queues: ['orders', 'payments'] },
      })

      const result = await listQueueDetails('my-setup')

      expect(result).toEqual([
        { name: 'orders', implementationType: null },
        { name: 'payments', implementationType: null },
      ])
    })

    it('returns an empty array when no queues exist', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: { count: 0, queues: [] } })

      const result = await listQueueDetails('my-setup')

      expect(result).toEqual([])
    })
  })

  // ── createQueue ──────────────────────────────────────────────────────────────

  describe('createQueue', () => {
    it('POSTs to /api/v1/setups/{setupId}/queues with the request body', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: {}, status: 200 })

      await createQueue('my-setup', VALID_CREATE_REQUEST)

      expect(mockedAxios.post).toHaveBeenCalledWith(
        expect.stringContaining('/setups/my-setup/queues'),
        VALID_CREATE_REQUEST
      )
    })

    it('rejects with the server error message on 400', async () => {
      const serverError = { response: { data: { error: 'Unsupported implementation type' }, status: 400 } }
      mockedAxios.post = vi.fn().mockRejectedValueOnce(serverError)

      await expect(createQueue('my-setup', VALID_CREATE_REQUEST)).rejects.toMatchObject({
        response: { data: { error: 'Unsupported implementation type' } },
      })
    })
  })

  // ── deleteQueue ──────────────────────────────────────────────────────────────

  describe('deleteQueue', () => {
    it('DELETEs /api/v1/management/queues/{setupId}/{queueName}', async () => {
      mockedAxios.delete = vi.fn().mockResolvedValueOnce({ data: {}, status: 200 })

      await deleteQueue('my-setup', 'orders')

      expect(mockedAxios.delete).toHaveBeenCalledWith(
        expect.stringContaining('/management/queues/my-setup/orders')
      )
    })

    it('rejects on network error', async () => {
      mockedAxios.delete = vi.fn().mockRejectedValueOnce(new Error('Network Error'))

      await expect(deleteQueue('my-setup', 'orders')).rejects.toThrow('Network Error')
    })
  })
})
