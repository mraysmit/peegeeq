/**
 * Tests for setupService.ts
 *
 * Uses real axios with vi.mock to intercept HTTP at the adapter level.
 * No mocking of business logic — only network boundary.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios from 'axios'
import { createSetup, getSetups, getQueues } from '../../services/setupService'
import type { CreateSetupRequest } from '../../types/setup'

vi.mock('axios')
const mockedAxios = vi.mocked(axios, true)

const VALID_SETUP_REQUEST: CreateSetupRequest = {
  setupId: 'test-setup',
  databaseConfig: {
    host: 'localhost',
    port: 5432,
    databaseName: 'peegeeq_test',
    username: 'peegeeq',
    password: 's3cr3t',
    schema: 'public',
    sslEnabled: false,
    templateDatabase: 'template0',
    encoding: 'UTF8',
  },
  queues: [],
  eventStores: [],
}

describe('setupService', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // ── createSetup ──────────────────────────────────────────────────────────────

  describe('createSetup', () => {
    it('posts to /api/v1/database-setup/create with the request body', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: {}, status: 200 })

      await createSetup(VALID_SETUP_REQUEST)

      expect(mockedAxios.post).toHaveBeenCalledWith(
        expect.stringContaining('/database-setup/create'),
        VALID_SETUP_REQUEST,
        expect.objectContaining({ timeout: 120_000 })
      )
    })

    it('resolves without a value on 200', async () => {
      mockedAxios.post = vi.fn().mockResolvedValueOnce({ data: {}, status: 200 })

      await expect(createSetup(VALID_SETUP_REQUEST)).resolves.toBeUndefined()
    })

    it('rejects with the server error message on 400', async () => {
      const serverError = { response: { data: { error: 'Setup ID already exists' }, status: 400 } }
      mockedAxios.post = vi.fn().mockRejectedValueOnce(serverError)

      await expect(createSetup(VALID_SETUP_REQUEST)).rejects.toMatchObject({
        response: { data: { error: 'Setup ID already exists' } },
      })
    })

    it('rejects with network error when backend is unreachable', async () => {
      mockedAxios.post = vi.fn().mockRejectedValueOnce(new Error('Network Error'))

      await expect(createSetup(VALID_SETUP_REQUEST)).rejects.toThrow('Network Error')
    })
  })

  // ── getSetups ────────────────────────────────────────────────────────────────

  describe('getSetups', () => {
    it('GETs /api/v1/setups and returns the string array', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: { count: 2, setupIds: ['setup-a', 'setup-b'] } })

      const result = await getSetups()

      expect(mockedAxios.get).toHaveBeenCalledWith(expect.stringContaining('/setups'))
      expect(result).toEqual(['setup-a', 'setup-b'])
    })

    it('returns an empty array when no setups exist', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: { count: 0, setupIds: [] } })

      const result = await getSetups()

      expect(result).toEqual([])
    })

    it('rejects on network error', async () => {
      mockedAxios.get = vi.fn().mockRejectedValueOnce(new Error('Network Error'))

      await expect(getSetups()).rejects.toThrow('Network Error')
    })
  })

  // ── getQueues ────────────────────────────────────────────────────────────────

  describe('getQueues', () => {
    it('GETs /api/v1/setups/{setupId}/queues and returns the string array', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: { count: 2, queues: ['orders', 'payments'] } })

      const result = await getQueues('my-setup')

      expect(mockedAxios.get).toHaveBeenCalledWith(
        expect.stringContaining('/setups/my-setup/queues')
      )
      expect(result).toEqual(['orders', 'payments'])
    })

    it('interpolates setupId into the URL path', async () => {
      mockedAxios.get = vi.fn().mockResolvedValueOnce({ data: { count: 0, queues: [] } })

      await getQueues('special-setup')

      expect(mockedAxios.get).toHaveBeenCalledWith(
        expect.stringContaining('special-setup')
      )
    })

    it('rejects with 404 when the setup does not exist', async () => {
      const error = { response: { status: 404, data: { error: 'Setup not found' } } }
      mockedAxios.get = vi.fn().mockRejectedValueOnce(error)

      await expect(getQueues('unknown')).rejects.toMatchObject({
        response: { status: 404 },
      })
    })
  })
})
