/**
 * Tests for publishService.ts (§12 of the feature design).
 *
 * axios is FULLY mocked (vi.mock('axios')) — no real HTTP happens here. What
 * these tests pin: the URL shape, the request body, the timeout option, and
 * the response-field mapping. What they do NOT verify: real wire behaviour or
 * real backend failure semantics — that lives in the e2e suite
 * (generator-run.spec.ts for success, generator-failure.spec.ts for failure).
 *
 * The response shapes below are the REAL ones, taken from
 * `QueueHandler.sendMessages` (peegeeq-rest): `totalMessages`,
 * `successfulMessages`, `failedMessages`. The previous version of this file
 * asserted `messagesSent` and `count` — fields the server has never returned.
 * Mocks written from an invented contract are why the production defect
 * (counting the request length instead of the acknowledged count) survived:
 * the tests agreed with the code and both disagreed with the server.
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
    it('POSTs to the batch endpoint and returns the server-reported counts', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({
        data: { totalMessages: 2, successfulMessages: 2, failedMessages: 0 },
      })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(mockedAxios.post).toHaveBeenCalledWith(
        expect.stringContaining('/queues/setup-1/orders/messages/batch'),
        BATCH,
        // Every publish carries a timeout: a hung socket must not keep the
        // engine's in-flight fan-out (which Stop waits for) unsettled until
        // the OS timeout.
        expect.objectContaining({ timeout: PUBLISH_TIMEOUT_MS })
      )
      expect(res).toEqual({ messagesSent: 2, messagesFailed: 0 })
    })

    it('reports a 207 partial success as the mixed result it is', async () => {
      // The handler returns 207 when failedMessages > 0. axios resolves 2xx, so
      // this arrives as a success — the counts are the only signal that some
      // messages were rejected, and they must reach the caller intact.
      mockedAxios.post = vi.fn().mockResolvedValueOnce({
        data: { totalMessages: 2, successfulMessages: 1, failedMessages: 1 },
      })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(res).toEqual({ messagesSent: 1, messagesFailed: 1 })
    })

    it('never reports more sent than the server acknowledged', async () => {
      // The regression guard for the original defect: a 2-message request whose
      // server response acknowledges 0 must report 0, not 2.
      mockedAxios.post = vi.fn().mockResolvedValueOnce({
        data: { totalMessages: 2, successfulMessages: 0, failedMessages: 2 },
      })

      const res = await publishBatch('setup-1', 'orders', BATCH)

      expect(res.messagesSent).toBe(0)
      expect(res.messagesSent).not.toBe(BATCH.messages.length)
    })

    it('throws when the response carries no counts — never substitutes the request length', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: { message: 'ok' } })

      await expect(publishBatch('setup-1', 'orders', BATCH)).rejects.toThrow(
        /missing successfulMessages\/failedMessages/
      )
    })

    it('throws when the response body is absent entirely', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: undefined })

      await expect(publishBatch('setup-1', 'orders', BATCH)).rejects.toThrow(
        /missing successfulMessages\/failedMessages/
      )
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
