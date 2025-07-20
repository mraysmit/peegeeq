/**
 * React hooks for real-time updates in PeeGeeQ Management UI
 * 
 * These hooks provide easy integration of WebSocket and SSE connections
 * with React components for real-time data updates.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */

import { useEffect, useRef, useState } from 'react'
import { SSEService, WebSocketMessage } from '../services/websocketService'

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
      (error) => {
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

  const { isConnected, error } = useSSE({
    url: '/sse/metrics',
    enabled,
    onMessage: (data) => {
      setMetrics(data)
    }
  })

  return {
    metrics,
    isConnected,
    error
  }
}

export const useQueueUpdates = (setupId: string, enabled = true) => {
  const [queueData, setQueueData] = useState<any[]>([])

  const { isConnected, error } = useSSE({
    url: `/sse/queues/${setupId}`,
    enabled: enabled && !!setupId,
    onMessage: (data) => {
      setQueueData(data.queues || [])
    }
  })

  return {
    queueData,
    isConnected,
    error
  }
}

export const useMessageStream = (setupId: string, queueName?: string, enabled = true) => {
  const [messages, setMessages] = useState<any[]>([])
  const [messageCount, setMessageCount] = useState(0)

  const url = queueName 
    ? `ws://localhost:8080/ws/messages/${setupId}/${queueName}`
    : `ws://localhost:8080/ws/messages/${setupId}`

  const { isConnected, error } = useWebSocket({
    url,
    enabled: enabled && !!setupId,
    onMessage: (message) => {
      if (message.type === 'message') {
        setMessages(prev => [message.data, ...prev.slice(0, 99)]) // Keep last 100 messages
        setMessageCount(prev => prev + 1)
      }
    }
  })

  const clearMessages = () => {
    setMessages([])
    setMessageCount(0)
  }

  return {
    messages,
    messageCount,
    isConnected,
    error,
    clearMessages
  }
}

export const useSystemMonitoring = (enabled = true) => {
  const [systemStats, setSystemStats] = useState<any>(null)
  const [alerts, setAlerts] = useState<any[]>([])

  const { isConnected, error } = useWebSocket({
    url: 'ws://localhost:8080/ws/monitoring',
    enabled,
    onMessage: (message) => {
      switch (message.type) {
        case 'system_stats':
          setSystemStats(message.data)
          break
        case 'alert':
          setAlerts(prev => [message.data, ...prev.slice(0, 49)]) // Keep last 50 alerts
          break
        case 'queue_update':
          // Handle queue updates
          break
        default:
          console.log('Unknown monitoring message type:', message.type)
      }
    }
  })

  const clearAlerts = () => {
    setAlerts([])
  }

  return {
    systemStats,
    alerts,
    isConnected,
    error,
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
