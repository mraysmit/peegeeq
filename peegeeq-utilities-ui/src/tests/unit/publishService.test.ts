/**
 * Tests for publishService.ts (§12 of the feature design).
 *
 * Uses real axios with vi.mock to intercept HTTP at the adapter level.
 * No mocking of business logic — only the network boundary.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import { publishBatch, publishSingle, PUBLISH_TIMEOUT_MS } from '../../services/publishService'
import type { BatchMessageRequest, MessageRequest } from '../../types/queue'

vi.mock('axios')
const mockedAxios = vi.mocked(axios, true)

const SINGLE: MessageRequest = { payload: { a: 1 } }
const BATCH: BatchMessageRequest = {
  messages: [{ payload: { a: 1 } }, { payload: { b: 2 } }],
}

describe('publishService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('publishSingle', () => {
    it('POSTs to /api/v1/queues/{setupId}/{queueName}/messages', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: { messageId: 'm1' } })

      const res = await publishSingle('setup-1', 'orders', SINGLE)

      expect(mockedAxios.post).toHaveBeenCalledWith(
        expect.stringContaining('/queues/setup-1/orders/messages'),
        SINGLE,
        // Every publish carries a timeout: a hung socket must not keep the
        // engine's in-flight fan-out (which Stop waits for) unsettled until
        // the OS timeout.
        expect.objectContaining({ timeout: PUBLISH_TIMEOUT_MS })
      )
      expect(res.messageId).toBe('m1')
    })
  })

  describe('publishBatch', () => {
    it('POSTs to the batch endpoint and returns messagesSent', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: { messagesSent: 2 } })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(mockedAxios.post).toHaveBeenCalledWith(
        expect.stringContaining('/queues/setup-1/orders/messages/batch'),
        BATCH,
        expect.objectContaining({ timeout: PUBLISH_TIMEOUT_MS })
      )
      expect(res.messagesSent).toBe(2)
    })

    it('reports messagesSent from the count field when messagesSent is absent', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: { count: 2 } })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(res.messagesSent).toBe(2)
    })

    it('falls back to single publishing when the batch endpoint returns 404', async () => {
      const notFound = Object.assign(new Error('Not Found'), {
        isAxiosError: true,
        response: { status: 404 },
      })
      mockedAxios.isAxiosError = vi.fn().mockReturnValue(true) as unknown as typeof axios.isAxiosError
      mockedAxios.post = vi
        .fn()
        .mockRejectedValueOnce(notFound) // batch 404
        .mockResolvedValueOnce({ data: { messageId: 'm1' } }) // single 1
        .mockResolvedValueOnce({ data: { messageId: 'm2' } }) // single 2

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(res.messagesSent).toBe(2)
      // 1 batch attempt + 2 single fallbacks
      expect(mockedAxios.post).toHaveBeenCalledTimes(3)
      expect(mockedAxios.post).toHaveBeenLastCalledWith(
        expect.stringContaining('/queues/setup-1/orders/messages'),
        BATCH.messages[1],
        expect.objectContaining({ timeout: PUBLISH_TIMEOUT_MS })
      )
    })

    it('rethrows non-404 errors without falling back', async () => {
      const serverError = Object.assign(new Error('Server Error'), {
        isAxiosError: true,
        response: { status: 500 },
      })
      mockedAxios.isAxiosError = vi.fn().mockReturnValue(true) as unknown as typeof axios.isAxiosError
      mockedAxios.post = vi.fn().mockRejectedValueOnce(serverError)

      await expect(publishBatch('setup-1', 'orders', BATCH)).rejects.toThrow('Server Error')
      expect(mockedAxios.post).toHaveBeenCalledTimes(1)
    })
  })
})
