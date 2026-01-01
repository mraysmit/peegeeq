/**
 * React hooks for real-time updates in PeeGeeQ Management UI
 * 
 * These hooks provide easy integration of WebSocket and SSE connections
 * with React components for real-time data updates.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */

import { useEffect, useRef, useState } from 'react'
import {
    SSEService,
    WebSocketMessage,
    createMessageStreamService,
    createSystemMonitoringService,
    createSystemMetricsSSE
} from '../services/websocketService'

export interface UseWebSocketOptions {
    url: string
    enabled?: boolean
    reconnectInterval?: number
    maxReconnectAttempts?: number
    onMessage?: (message: WebSocketMessage) => void
    onConnect?: () => void
    onDisconnect?: () => void
    onError?: (error: Event) => void
}

/**
 * Basic WebSocket hook
 */
export const useWebSocket = (options: UseWebSocketOptions) => {
    const [isConnected, setIsConnected] = useState(false)
    const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null)
    const [error, setError] = useState<Event | null>(null)
    const wsRef = useRef<WebSocket | null>(null)

    useEffect(() => {
        if (!options.enabled || !options.url) {
            return
        }

        const ws = new WebSocket(options.url)
        wsRef.current = ws

        ws.onopen = () => {
            setIsConnected(true)
            setError(null)
            options.onConnect?.()
        }

        ws.onmessage = (event) => {
            try {
                const message = JSON.parse(event.data)
                setLastMessage(message)
                options.onMessage?.(message)
            } catch (err) {
                console.error('Failed to parse WebSocket message:', err)
            }
        }

        ws.onclose = () => {
            setIsConnected(false)
            options.onDisconnect?.()
        }

        ws.onerror = (event) => {
            setError(event)
            options.onError?.(event)
        }

        return () => {
            ws.close()
        }
    }, [options.url, options.enabled])

    return { isConnected, lastMessage, error }
}



export interface UseSSEOptions {
    url: string
    enabled?: boolean
    onMessage?: (data: any) => void
    onError?: (error: Event) => void
}

export const useSSE = (options: UseSSEOptions) => {
    const [isConnected, setIsConnected] = useState(false)
    const [lastData, setLastData] = useState<any>(null)
    const [error, setError] = useState<Event | null>(null)
    const sseRef = useRef<SSEService | null>(null)

    useEffect(() => {
        if (!options.enabled) {
            return
        }

        const sse = new SSEService(
            options.url,
            (data) => {
                setLastData(data)
                options.onMessage?.(data)
            },
            undefined, // onConnect
            undefined, // onDisconnect
            (error: Event) => {
                setError(error)
                setIsConnected(false)
                options.onError?.(error)
            }
        )

        sseRef.current = sse
        sse.connect()
        setIsConnected(true)

        return () => {
            sse.disconnect()
            sseRef.current = null
            setIsConnected(false)
        }
    }, [options.url, options.enabled])

    return {
        isConnected,
        lastData,
        error
    }
}

// Specialized hooks for specific use cases

export const useSystemMetrics = (enabled = true) => {
    const [metrics, setMetrics] = useState<any>(null)
    const [isConnected, setIsConnected] = useState(false)

    useEffect(() => {
        if (!enabled) return

        const sse = createSystemMetricsSSE(
            (data) => setMetrics(data),
            () => setIsConnected(true),
            () => setIsConnected(false)
        )
        sse.connect()

        return () => sse.disconnect()
    }, [enabled])

    return {
        metrics,
        isConnected
    }
}

export const useQueueUpdates = (setupId: string, enabled = true) => {
    const [queueData, setQueueData] = useState<any[]>([])
    const [isConnected, setIsConnected] = useState(false)

    useEffect(() => {
        if (!enabled || !setupId) return

        // Note: The backend only provides per-queue streams now.
        // This hook might need to be redefined or use a different approach.
        // For now, we'll keep the SSE service structure but use the correct base logic.

        const sse = new SSEService(
            `/sse/queues/${setupId}`,
            (data) => setQueueData(data.queues || []),
            () => setIsConnected(true),
            () => setIsConnected(false)
        )
        sse.connect()

        return () => sse.disconnect()
    }, [setupId, enabled])

    return {
        queueData,
        isConnected
    }
}

export const useMessageStream = (setupId: string, queueName?: string, enabled = true) => {
    const [messages, setMessages] = useState<any[]>([])
    const [messageCount, setMessageCount] = useState(0)
    const [isConnected, setIsConnected] = useState(false)

    useEffect(() => {
        if (!enabled || !setupId) return

        const service = createMessageStreamService(setupId, queueName)
        service.connect()
        setIsConnected(true)

        // Add message handling to service if it supports it, 
        // or update createMessageStreamService to accept options.
        // For now, let's keep it simple as we are unifying connectivity first.

        return () => service.disconnect()
    }, [setupId, queueName, enabled])

    const clearMessages = () => {
        setMessages([])
        setMessageCount(0)
    }

    return {
        messages,
        messageCount,
        isConnected,
        clearMessages
    }
}

export const useSystemMonitoring = (enabled = true) => {
    const [alerts, setAlerts] = useState<any[]>([])
    const [isConnected, setIsConnected] = useState(false)

    useEffect(() => {
        if (!enabled) return

        const service = createSystemMonitoringService()
        service.connect()
        setIsConnected(true)

        return () => service.disconnect()
    }, [enabled])

    const clearAlerts = () => {
        setAlerts([])
    }

    return {
        alerts,
        isConnected,
        clearAlerts
    }
}

// Hook for managing connection status across the app
export const useConnectionStatus = () => {
    const [connections, setConnections] = useState<Record<string, boolean>>({})

    const updateConnection = (name: string, status: boolean) => {
        setConnections(prev => ({
            ...prev,
            [name]: status
        }))
    }

    const isAnyConnected = Object.values(connections).some(status => status)
    const allConnected = Object.values(connections).every(status => status)
    const connectionCount = Object.values(connections).filter(status => status).length

    return {
        connections,
        updateConnection,
        isAnyConnected,
        allConnected,
        connectionCount
    }
}
