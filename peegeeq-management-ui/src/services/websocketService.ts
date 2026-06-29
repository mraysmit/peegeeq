import { getApiUrl, getVersionedApiUrl } from './configService'

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
    onReconnecting?: () => void
    onError?: (error: Event) => void
}

export class WebSocketService {
    private ws: WebSocket | null = null
    private config: WebSocketConfig
    private reconnectAttempts = 0
    private reconnectTimer: ReturnType<typeof setTimeout> | null = null
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
                // Warn rather than error — WebSocket connection failures during reconnection
                // are expected transient events; the service handles them by reconnecting.
                console.warn('WebSocket error:', error)
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
        this.config.onReconnecting?.()

        this.reconnectTimer = setTimeout(() => {
            this.connect()
        }, delay)
    }
}

// WebSocket service instances for different endpoints
export const createMessageStreamService = (setupId: string, queueName?: string) => {
    const baseUrl = getApiUrl('').replace('http://', 'ws://').replace('https://', 'wss://')
    // WS endpoints usually don't have /api/v1 prefix in this backend, 
    // but they follow the /ws root.
    const url = queueName
        ? `${baseUrl}/ws/queues/${setupId}/${queueName}`
        : `${baseUrl}/ws/queues/${setupId}`

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

export const createSystemMonitoringService = (
    onMessage?: (message: any) => void,
    onConnect?: () => void,
    onDisconnect?: () => void,
    onReconnecting?: () => void
) => {
    const baseUrl = getApiUrl('').replace('http://', 'ws://').replace('https://', 'wss://')
    const service = new WebSocketService({
        url: `${baseUrl}/ws/monitoring`,
        onMessage,
        onConnect,
        onDisconnect,
        onReconnecting
    })
    service.connect()
    return service
}

// Server-Sent Events service for HTTP-based real-time updates
export class SSEService {
    private eventSource: EventSource | null = null
    private url: string
    private onMessage?: (data: any) => void
    private onConnect?: () => void
    private onDisconnect?: () => void
    private onError?: (error: Event) => void
    private onReconnecting?: () => void
    private namedEvents: string[]

    constructor(
        url: string,
        onMessage?: (data: any) => void,
        onConnect?: () => void,
        onDisconnect?: () => void,
        onError?: (error: Event) => void,
        onReconnecting?: () => void,
        // Named SSE events (e.g. `queue-changed`) to route to onMessage in addition to the
        // default unnamed `message` event. A native EventSource.onmessage NEVER fires for a
        // frame that carries an `event:` field, so any named event must be registered here.
        namedEvents: string[] = []
    ) {
        this.url = url
        this.onMessage = onMessage
        this.onConnect = onConnect
        this.onDisconnect = onDisconnect
        this.onError = onError
        this.onReconnecting = onReconnecting
        this.namedEvents = namedEvents
    }

    private dispatchMessage(raw: string): void {
        try {
            const data = JSON.parse(raw)
            this.onMessage?.(data)
        } catch (error) {
            console.error('Failed to parse SSE message:', error)
        }
    }

    connect(): void {
        if (this.eventSource) {
            this.disconnect()
        }

        try {
            this.eventSource = new EventSource(this.url)

            // Default (unnamed) SSE events — e.g. /sse/metrics sends `data:` frames with no
            // `event:` field, which the native onmessage handler receives.
            this.eventSource.onmessage = (event) => this.dispatchMessage(event.data)

            // Named SSE events — e.g. /sse/queues/{setupId} emits `event: queue-changed`, which
            // onmessage never receives. Register each requested named event and route it through
            // the same parse + onMessage path.
            for (const eventName of this.namedEvents) {
                this.eventSource.addEventListener(eventName, (event) =>
                    this.dispatchMessage((event as MessageEvent).data))
            }

            this.eventSource.onerror = (error) => {
                // Warn rather than error — SSE connection failures on page navigation /
                // reload are expected transient events; the browser reconnects automatically.
                console.warn('SSE error:', error)
                this.onError?.(error)
                // Discriminate transient reconnect from terminal close, mirroring the
                // WebSocketService reconnecting state. The native EventSource auto-reconnects:
                // while it is doing so readyState is CONNECTING; it only reaches CLOSED when
                // the browser gives up (or close() was called).
                if (this.eventSource?.readyState === EventSource.CONNECTING) {
                    this.onReconnecting?.()
                } else if (this.eventSource?.readyState === EventSource.CLOSED) {
                    this.onDisconnect?.()
                }
            }

            this.eventSource.onopen = () => {
                console.log('SSE connected to:', this.url)
                this.onConnect?.()
            }
        } catch (error) {
            console.error('Failed to create SSE connection:', error)
        }
    }

    disconnect(): void {
        if (this.eventSource) {
            this.eventSource.close()
            this.eventSource = null
            this.onDisconnect?.()
        }
    }

    isConnected(): boolean {
        return this.eventSource !== null && this.eventSource.readyState === EventSource.OPEN
    }
}

// SSE service factory functions
export const createSystemMetricsSSE = (
    onUpdate: (metrics: any) => void,
    onConnect?: () => void,
    onDisconnect?: () => void,
    onReconnecting?: () => void
) => {
    const service = new SSEService(
        getVersionedApiUrl('sse/metrics'),
        onUpdate,
        onConnect,
        onDisconnect,
        // Warn, not error — transient SSE drops are expected and recovered by auto-reconnect.
        (error) => console.warn('System metrics SSE error:', error),
        onReconnecting
    )
    service.connect()
    return service
}

export const createQueueUpdatesSSE = (
    setupId: string,
    onUpdate: (queueData: any) => void,
    onConnect?: () => void,
    onDisconnect?: () => void
) => {
    const service = new SSEService(
        getVersionedApiUrl(`sse/queues/${setupId}`),
        onUpdate,
        onConnect,
        onDisconnect,
        (error) => console.error('Queue updates SSE error:', error),
        undefined,            // onReconnecting — not used for queue updates
        ['queue-changed']     // backend pushes named `queue-changed` events; route them to onUpdate
    )
    service.connect()
    return service
}
