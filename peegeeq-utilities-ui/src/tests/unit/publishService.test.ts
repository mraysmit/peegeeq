/**
 * Tests for publishService.ts (§12 of the feature design).
 *
 * axios is FULLY mocked (vi.mock('axios')) — no real HTTP happens here. What
 * these tests pin: the URL shape, the request body, the timeout option, and
 * the response-field mapping. What they do NOT verify: real wire behaviour or
 * real backend failure semantics — that lives in the e2e suite
 * (generator-run.spec.ts for success, generator-failure.spec.ts for failure).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import { publishBatch, PUBLISH_TIMEOUT_MS } from '../../services/publishService'
import type { BatchMessageRequest } from '../../types/queue'

vi.mock('axios')
const mockedAxios = vi.mocked(axios, true)

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

  describe('publishBatch', () => {
    it('POSTs to the batch endpoint and returns messagesSent', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: { messagesSent: 2 } })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(mockedAxios.post).toHaveBeenCalledWith(
        expect.stringContaining('/queues/setup-1/orders/messages/batch'),
        BATCH,
        // Every publish carries a timeout: a hung socket must not keep the
        // engine's in-flight fan-out (which Stop waits for) unsettled until
        // the OS timeout.
        expect.objectContaining({ timeout: PUBLISH_TIMEOUT_MS })
      )
      expect(res.messagesSent).toBe(2)
    })

    it('reports messagesSent from the count field when messagesSent is absent', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: { count: 2 } })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(res.messagesSent).toBe(2)
    })

    it('propagates an HTTP error to the caller — no fallback, no swallowing', async () => {
      const serverError = Object.assign(new Error('Server Error'), {
        isAxiosError: true,
        response: { status: 500 },
      })
      mockedAxios.post = vi.fn().mockRejectedValueOnce(serverError)

      await expect(publishBatch('setup-1', 'orders', BATCH)).rejects.toThrow('Server Error')
      expect(mockedAxios.post).toHaveBeenCalledTimes(1)
    })

    it('propagates a 404 like any other error — the per-message fallback is deleted', async () => {
      const notFound = Object.assign(new Error('Not Found'), {
        isAxiosError: true,
        response: { status: 404 },
      })
      mockedAxios.post = vi.fn().mockRejectedValueOnce(notFound)

      await expect(publishBatch('setup-1', 'orders', BATCH)).rejects.toThrow('Not Found')
      expect(mockedAxios.post).toHaveBeenCalledTimes(1)
    })
  })
})
