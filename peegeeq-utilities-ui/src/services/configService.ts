import { API_PREFIX } from './apiConstants'

const CONFIG_STORAGE_KEY = 'peegeeq_utilities_backend_config'

export interface BackendConfig {
    apiUrl: string
}

const DEFAULT_CONFIG: BackendConfig = {
    apiUrl: 'http://127.0.0.1:8088',
}

export const getBackendConfig = (): BackendConfig => {
    try {
        const stored = localStorage.getItem(CONFIG_STORAGE_KEY)
        if (stored) {
            return JSON.parse(stored)
        }
    } catch (error) {
        console.error('Failed to load backend config:', error)
    }
    return DEFAULT_CONFIG
}

export const saveBackendConfig = (config: BackendConfig): void => {
    try {
        localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(config))
    } catch (error) {
        console.error('Failed to save backend config:', error)
        throw error
    }
}

/**
 * Build a versioned API URL, optionally overriding the base.
 */
export const getVersionedApiUrl = (endpoint: string, baseUrl?: string): string => {
    const config = getBackendConfig()
    const base = baseUrl ?? config.apiUrl
    return `${base}${API_PREFIX}/${endpoint}`
}
