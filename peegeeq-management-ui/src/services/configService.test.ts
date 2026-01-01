import { describe, it, expect, beforeEach } from 'vitest'
import {
    getBackendConfig,
    saveBackendConfig,
    getApiUrl,
    getVersionedApiUrl,
    resetBackendConfig,
    BackendConfig
} from './configService'

describe('configService', () => {
    beforeEach(() => {
        localStorage.clear()
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

    describe('getVersionedApiUrl', () => {
        it('should construct URL with default version (v1)', () => {
            const url = getVersionedApiUrl('health')
            expect(url).toBe('http://localhost:8080/api/v1/health')
        })

        it('should construct URL with custom baseUrl', () => {
            const url = getVersionedApiUrl('health', 'https://api.test.com')
            expect(url).toBe('https://api.test.com/api/v1/health')
        })

        it('should handle endpoint with leading slash', () => {
            const url = getVersionedApiUrl('/health')
            expect(url).toBe('http://localhost:8080/api/v1/health')
        })

        it('should handle management endpoints', () => {
            const url = getVersionedApiUrl('management/overview')
            expect(url).toBe('http://localhost:8080/api/v1/management/overview')
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

    // NOTE: testBackendConnection tests removed - these require network calls
    // and are better tested in E2E tests with real backend server.
    // See src/tests/e2e/system-integration.spec.ts for backend connectivity tests.
})

