import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  getBackendConfig,
  saveBackendConfig,
  testBackendConnection,
  getApiUrl,
  resetBackendConfig,
  BackendConfig
} from './configService'

describe('configService', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  describe('getBackendConfig', () => {
    it('should return default config when nothing is stored', () => {
      const config = getBackendConfig()
      expect(config).toEqual({
        apiUrl: 'http://localhost:8080',
        wsUrl: 'ws://localhost:8080/ws'
      })
    })

    it('should return stored config when available', () => {
      const customConfig: BackendConfig = {
        apiUrl: 'https://api.example.com',
        wsUrl: 'wss://api.example.com/ws'
      }
      localStorage.setItem('peegeeq_backend_config', JSON.stringify(customConfig))

      const config = getBackendConfig()
      expect(config).toEqual(customConfig)
    })

    it('should return default config when stored data is invalid', () => {
      localStorage.setItem('peegeeq_backend_config', 'invalid json')

      const config = getBackendConfig()
      expect(config).toEqual({
        apiUrl: 'http://localhost:8080',
        wsUrl: 'ws://localhost:8080/ws'
      })
    })
  })

  describe('saveBackendConfig', () => {
    it('should save config to localStorage', () => {
      const config: BackendConfig = {
        apiUrl: 'https://api.example.com',
        wsUrl: 'wss://api.example.com/ws'
      }

      saveBackendConfig(config)

      const stored = localStorage.getItem('peegeeq_backend_config')
      expect(stored).toBe(JSON.stringify(config))
    })
  })

  describe('getApiUrl', () => {
    it('should construct URL with default config', () => {
      const url = getApiUrl('/api/v1/health')
      expect(url).toBe('http://localhost:8080/api/v1/health')
    })

    it('should construct URL with custom config', () => {
      saveBackendConfig({
        apiUrl: 'https://api.example.com'
      })

      const url = getApiUrl('/api/v1/health')
      expect(url).toBe('https://api.example.com/api/v1/health')
    })

    it('should handle endpoint without leading slash', () => {
      const url = getApiUrl('api/v1/health')
      expect(url).toBe('http://localhost:8080/api/v1/health')
    })

    it('should handle apiUrl with trailing slash', () => {
      saveBackendConfig({
        apiUrl: 'https://api.example.com/'
      })

      const url = getApiUrl('/api/v1/health')
      expect(url).toBe('https://api.example.com/api/v1/health')
    })
  })

  describe('resetBackendConfig', () => {
    it('should remove config from localStorage', () => {
      saveBackendConfig({
        apiUrl: 'https://api.example.com'
      })

      resetBackendConfig()

      const stored = localStorage.getItem('peegeeq_backend_config')
      expect(stored).toBeNull()
    })
  })

  describe('testBackendConnection', () => {
    it('should return success when connection works', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200
      })

      const result = await testBackendConnection('http://localhost:8080')
      expect(result).toEqual({
        success: true,
        message: 'Connection successful'
      })
    })

    it('should return failure when server returns error status', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 500
      })

      const result = await testBackendConnection('http://localhost:8080')
      expect(result).toEqual({
        success: false,
        message: 'Server returned status 500'
      })
    })

    it('should return failure when network error occurs', async () => {
      global.fetch = vi.fn().mockRejectedValue(new Error('Network error'))

      const result = await testBackendConnection('http://localhost:8080')
      expect(result).toEqual({
        success: false,
        message: 'Network error'
      })
    })
  })
})

