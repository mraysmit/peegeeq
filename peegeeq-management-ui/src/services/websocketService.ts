/**
 * WebSocket service for real-time updates in PeeGeeQ Management UI
 * 
 * This service manages WebSocket connections to the backend for real-time
 * message streaming, queue updates, and system monitoring.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */

export interface WebSocketMessage {
  type: string
  data: any
  timestamp: number
}

export interface WebSocketConfig {
  url: string
  reconnectInterval?: number
  maxReconnectAttempts?: number
  onMessage?: (message: WebSocketMessage) => void
  onConnect?: () => void
  onDisconnect?: () => void
  onError?: (error: Event) => void
}

export class WebSocketService {
  private ws: WebSocket | null = null
  private config: WebSocketConfig
  private reconnectAttempts = 0
  private reconnectTimer: number | null = null
  private isConnecting = false
  private isManuallyDisconnected = false

  constructor(config: WebSocketConfig) {
    this.config = {
      reconnectInterval: 5000,
      maxReconnectAttempts: 10,
      ...config
    }
  }

  connect(): void {
    if (this.isConnecting || (this.ws && this.ws.readyState === WebSocket.OPEN)) {
      return
    }

    this.isConnecting = true
    this.isManuallyDisconnected = false

    try {
      this.ws = new WebSocket(this.config.url)

      this.ws.onopen = () => {
        console.log('WebSocket connected to:', this.config.url)
        this.isConnecting = false
        this.reconnectAttempts = 0
        this.config.onConnect?.()
      }

      this.ws.onmessage = (event) => {
        try {
          const message: WebSocketMessage = JSON.parse(event.data)
          this.config.onMessage?.(message)
        } catch (error) {
          console.error('Failed to parse WebSocket message:', error)
        }
      }

      this.ws.onclose = () => {
        console.log('WebSocket disconnected from:', this.config.url)
        this.isConnecting = false
        this.ws = null
        this.config.onDisconnect?.()

        if (!this.isManuallyDisconnected) {
          this.scheduleReconnect()
        }
      }

      this.ws.onerror = (error) => {
        console.error('WebSocket error:', error)
        this.isConnecting = false
        this.config.onError?.(error)
      }
    } catch (error) {
      console.error('Failed to create WebSocket connection:', error)
      this.isConnecting = false
      this.scheduleReconnect()
    }
  }

  disconnect(): void {
    this.isManuallyDisconnected = true
    
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }

    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
  }

  send(message: any): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
    } else {
      console.warn('WebSocket is not connected. Message not sent:', message)
    }
  }

  isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN
  }

  private scheduleReconnect(): void {
    if (this.isManuallyDisconnected || 
        this.reconnectAttempts >= (this.config.maxReconnectAttempts || 10)) {
      console.log('Max reconnection attempts reached or manually disconnected')
      return
    }

    this.reconnectAttempts++
    const delay = this.config.reconnectInterval || 5000

    console.log(`Scheduling WebSocket reconnection attempt ${this.reconnectAttempts} in ${delay}ms`)
    
    this.reconnectTimer = setTimeout(() => {
      this.connect()
    }, delay)
  }
}

// WebSocket service instances for different endpoints
export const createMessageStreamService = (setupId: string, queueName?: string) => {
  const url = queueName 
    ? `ws://localhost:8080/ws/messages/${setupId}/${queueName}`
    : `ws://localhost:8080/ws/messages/${setupId}`

  return new WebSocketService({
    url,
    onMessage: (message) => {
      console.log('Message stream update:', message)
    },
    onConnect: () => {
      console.log('Message stream connected')
    },
    onDisconnect: () => {
      console.log('Message stream disconnected')
    }
  })
}

export const createSystemMonitoringService = () => {
  return new WebSocketService({
    url: 'ws://localhost:8080/ws/monitoring',
    onMessage: (message) => {
      console.log('System monitoring update:', message)
    },
    onConnect: () => {
      console.log('System monitoring connected')
    },
    onDisconnect: () => {
      console.log('System monitoring disconnected')
    }
  })
}

// Server-Sent Events service for HTTP-based real-time updates
export class SSEService {
  private eventSource: EventSource | null = null
  private url: string
  private onMessage?: (data: any) => void
  private onError?: (error: Event) => void

  constructor(url: string, onMessage?: (data: any) => void, onError?: (error: Event) => void) {
    this.url = url
    this.onMessage = onMessage
    this.onError = onError
  }

  connect(): void {
    if (this.eventSource) {
      this.disconnect()
    }

    try {
      this.eventSource = new EventSource(this.url)

      this.eventSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          this.onMessage?.(data)
        } catch (error) {
          console.error('Failed to parse SSE message:', error)
        }
      }

      this.eventSource.onerror = (error) => {
        console.error('SSE error:', error)
        this.onError?.(error)
      }

      this.eventSource.onopen = () => {
        console.log('SSE connected to:', this.url)
      }
    } catch (error) {
      console.error('Failed to create SSE connection:', error)
    }
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close()
      this.eventSource = null
    }
  }

  isConnected(): boolean {
    return this.eventSource !== null && this.eventSource.readyState === EventSource.OPEN
  }
}

// SSE service factory functions
export const createSystemMetricsSSE = (onUpdate: (metrics: any) => void) => {
  return new SSEService(
    '/sse/metrics',
    onUpdate,
    (error) => console.error('System metrics SSE error:', error)
  )
}

export const createQueueUpdatesSSE = (setupId: string, onUpdate: (queueData: any) => void) => {
  return new SSEService(
    `/sse/queues/${setupId}`,
    onUpdate,
    (error) => console.error('Queue updates SSE error:', error)
  )
}
