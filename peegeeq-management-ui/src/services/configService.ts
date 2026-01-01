import { API_PREFIX } from './apiConstants'

/**
 * Configuration service for managing backend connection settings
 */

const CONFIG_STORAGE_KEY = 'peegeeq_backend_config'

export interface BackendConfig {
    apiUrl: string
    wsUrl?: string
}

const DEFAULT_CONFIG: BackendConfig = {
    apiUrl: 'http://127.0.0.1:8080',
    wsUrl: 'ws://127.0.0.1:8080'
}

/**
 * Get the current backend configuration
 */
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

/**
 * Save backend configuration
 */
export const saveBackendConfig = (config: BackendConfig): void => {
    try {
        localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(config))
    } catch (error) {
        console.error('Failed to save backend config:', error)
        throw error
    }
}

/**
 * Test REST API connection
 */
export const testRestConnection = async (apiUrl: string): Promise<{ success: boolean; message: string }> => {
    try {
        const response = await fetch(getVersionedApiUrl('/health', apiUrl), {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            },
            signal: AbortSignal.timeout(5000)
        })

        if (response.ok) {
            const data = await response.json()
            return {
                success: true,
                message: `REST API connected (${data.status || 'UP'})`
            }
        } else {
            return {
                success: false,
                message: `Server returned status ${response.status}`
            }
        }
    } catch (error) {
        return {
            success: false,
            message: error instanceof Error ? error.message : 'Connection failed'
        }
    }
}

/**
 * Test WebSocket connection
 */
export const testWebSocketConnection = async (wsUrl: string): Promise<{ success: boolean; message: string }> => {
    return new Promise((resolve) => {
        try {
            const ws = new WebSocket(wsUrl)
            const timeout = setTimeout(() => {
                ws.close()
                resolve({
                    success: false,
                    message: 'WebSocket connection timeout'
                })
            }, 5000)

            ws.onopen = () => {
                clearTimeout(timeout)
                ws.close()
                resolve({
                    success: true,
                    message: 'WebSocket connected'
                })
            }

            ws.onerror = () => {
                clearTimeout(timeout)
                resolve({
                    success: false,
                    message: 'WebSocket connection failed'
                })
            }
        } catch (error) {
            resolve({
                success: false,
                message: error instanceof Error ? error.message : 'WebSocket error'
            })
        }
    })
}

/**
 * Test SSE connection
 */
export const testSSEConnection = async (apiUrl: string): Promise<{ success: boolean; message: string }> => {
    return new Promise((resolve) => {
        try {
            const eventSource = new EventSource(getVersionedApiUrl('/sse/health', apiUrl))
            const timeout = setTimeout(() => {
                eventSource.close()
                resolve({
                    success: false,
                    message: 'SSE connection timeout'
                })
            }, 5000)

            eventSource.onmessage = (event) => {
                clearTimeout(timeout)
                eventSource.close()
                try {
                    const data = JSON.parse(event.data)
                    resolve({
                        success: true,
                        message: `SSE connected (${data.status || 'UP'})`
                    })
                } catch {
                    resolve({
                        success: true,
                        message: 'SSE connected'
                    })
                }
            }

            eventSource.onerror = () => {
                clearTimeout(timeout)
                eventSource.close()
                resolve({
                    success: false,
                    message: 'SSE connection failed'
                })
            }
        } catch (error) {
            resolve({
                success: false,
                message: error instanceof Error ? error.message : 'SSE error'
            })
        }
    })
}

/**
 * Test connection to backend (legacy - tests REST only)
 */
export const testBackendConnection = async (apiUrl: string): Promise<{ success: boolean; message: string }> => {
    return testRestConnection(apiUrl)
}

/**
 * Get the full API URL for an endpoint
 */
export const getApiUrl = (endpoint: string, baseUrl?: string): string => {
    const effectiveBaseUrl = (baseUrl || getBackendConfig().apiUrl).replace(/\/$/, '') // Remove trailing slash
    if (!endpoint || endpoint === '/') {
        return effectiveBaseUrl
    }
    const path = endpoint.startsWith('/') ? endpoint : `/${endpoint}`
    return `${effectiveBaseUrl}${path}`
}

/**
 * Get a versioned API URL (enforces /api/v1 prefix)
 */
export const getVersionedApiUrl = (endpoint: string, baseUrl?: string): string => {
    const cleanEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`

    // If the endpoint already has the prefix, don't duplicate it
    if (cleanEndpoint.startsWith(API_PREFIX)) {
        return getApiUrl(cleanEndpoint, baseUrl)
    }

    return getApiUrl(`${API_PREFIX}${cleanEndpoint}`, baseUrl)
}

/**
 * Reset configuration to defaults
 */
export const resetBackendConfig = (): void => {
    try {
        localStorage.removeItem(CONFIG_STORAGE_KEY)
    } catch (error) {
        console.error('Failed to reset backend config:', error)
    }
}

