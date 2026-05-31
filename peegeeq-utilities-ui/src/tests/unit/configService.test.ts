/**
 * Tests for configService.ts
 *
 * Covers: default config, localStorage round-trip, error handling,
 * and URL construction via getVersionedApiUrl.
 *
 * jsdom provides a real localStorage implementation — no mocking needed.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { getBackendConfig, saveBackendConfig, getVersionedApiUrl } from '../../services/configService'

const STORAGE_KEY = 'peegeeq_utilities_backend_config'

describe('configService', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  // ── getBackendConfig ─────────────────────────────────────────────────────────

  describe('getBackendConfig', () => {
    it('returns default config when localStorage is empty', () => {
      const config = getBackendConfig()
      expect(config.apiUrl).toBe('http://127.0.0.1:8088')
    })

    it('returns the parsed config previously stored in localStorage', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ apiUrl: 'http://custom-host:9090' }))

      const config = getBackendConfig()

      expect(config.apiUrl).toBe('http://custom-host:9090')
    })

    it('returns default config when the localStorage value is malformed JSON', () => {
      localStorage.setItem(STORAGE_KEY, '{not-valid-json')
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

      const config = getBackendConfig()

      expect(config.apiUrl).toBe('http://127.0.0.1:8088')
      expect(spy).toHaveBeenCalledWith('Failed to load backend config:', expect.any(SyntaxError))
      spy.mockRestore()
    })
  })

  // ── saveBackendConfig ────────────────────────────────────────────────────────

  describe('saveBackendConfig', () => {
    it('persists the config to localStorage under the expected key', () => {
      saveBackendConfig({ apiUrl: 'http://saved-host:1234' })

      const raw = localStorage.getItem(STORAGE_KEY)
      expect(raw).not.toBeNull()
      expect(JSON.parse(raw!).apiUrl).toBe('http://saved-host:1234')
    })

    it('round-trips: save then getBackendConfig returns the saved value', () => {
      saveBackendConfig({ apiUrl: 'http://round-trip:5678' })

      expect(getBackendConfig().apiUrl).toBe('http://round-trip:5678')
    })

    it('overwrites a previously saved config', () => {
      saveBackendConfig({ apiUrl: 'http://first:1111' })
      saveBackendConfig({ apiUrl: 'http://second:2222' })

      expect(getBackendConfig().apiUrl).toBe('http://second:2222')
    })
  })

  // ── getVersionedApiUrl ───────────────────────────────────────────────────────

  describe('getVersionedApiUrl', () => {
    it('builds URL with default config when localStorage is empty', () => {
      const url = getVersionedApiUrl('management/queues')
      expect(url).toBe('http://127.0.0.1:8088/api/v1/management/queues')
    })

    it('builds URL using the saved config', () => {
      saveBackendConfig({ apiUrl: 'http://custom:9000' })

      const url = getVersionedApiUrl('setups')
      expect(url).toBe('http://custom:9000/api/v1/setups')
    })

    it('uses the provided baseUrl argument when given, ignoring stored config', () => {
      saveBackendConfig({ apiUrl: 'http://stored:1111' })

      const url = getVersionedApiUrl('health', 'http://override:4444')
      expect(url).toBe('http://override:4444/api/v1/health')
    })

    it('handles multi-segment endpoints correctly', () => {
      const url = getVersionedApiUrl('setups/my-setup/queues')
      expect(url).toContain('/api/v1/setups/my-setup/queues')
    })
  })
})
