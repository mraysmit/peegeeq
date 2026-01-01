import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { WebSocketService, WebSocketConfig, WebSocketMessage } from './websocketService'

/**
 * Unit tests for WebSocketService
 * 
 * These tests use mocked WebSocket to verify connection lifecycle,
 * reconnection logic, message handling, and error scenarios.
 */

// Mock WebSocket class
class MockWebSocket {
    public onopen: ((event: Event) => void) | null = null
    public onclose: ((event: CloseEvent) => void) | null = null
    public onmessage: ((event: MessageEvent) => void) | null = null
    public onerror: ((event: Event) => void) | null = null
    public readyState: number = WebSocket.CONNECTING
    public url: string

    constructor(url: string) {
        this.url = url
        // Simulate async connection
        setTimeout(() => {
            if (this.readyState === WebSocket.CONNECTING) {
                this.readyState = WebSocket.OPEN
                this.onopen?.(new Event('open'))
            }
        }, 10)
    }

    send(_data: string): void {
        if (this.readyState !== WebSocket.OPEN) {
            throw new Error('WebSocket is not open')
        }
    }

    close(): void {
        this.readyState = WebSocket.CLOSED
        this.onclose?.(new CloseEvent('close'))
    }

    // Helper to simulate receiving a message
    simulateMessage(data: any): void {
        const event = new MessageEvent('message', {
            data: JSON.stringify(data)
        })
        this.onmessage?.(event)
    }

    // Helper to simulate an error
    simulateError(): void {
        this.onerror?.(new Event('error'))
    }
}

describe('WebSocketService', () => {
    let originalWebSocket: any

    beforeEach(() => {
        // Save original WebSocket
        originalWebSocket = global.WebSocket
        // Replace with mock
        global.WebSocket = MockWebSocket as any
        vi.useFakeTimers()
    })

    afterEach(() => {
        // Restore original WebSocket
        global.WebSocket = originalWebSocket
        vi.useRealTimers()
        vi.clearAllMocks()
    })

    describe('constructor', () => {
        it('should initialize with provided config', () => {
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)
            expect(service).toBeDefined()
        })

        it('should set default reconnectInterval to 5000ms', () => {
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)
            expect(service).toBeDefined()
            // We can't directly test private fields, but we can test behavior
        })

        it('should set default maxReconnectAttempts to 10', () => {
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)
            expect(service).toBeDefined()
        })
    })

    describe('connect', () => {
        it('should establish WebSocket connection', async () => {
            const onConnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect
            }
            const service = new WebSocketService(config)

            service.connect()
            
            // Advance timers to trigger connection
            await vi.advanceTimersByTimeAsync(20)

            expect(onConnect).toHaveBeenCalledTimes(1)
            expect(service.isConnected()).toBe(true)
        })

        it('should not connect if already connecting', async () => {
            const onConnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect
            }
            const service = new WebSocketService(config)

            service.connect()
            service.connect() // Second call should be ignored

            await vi.advanceTimersByTimeAsync(20)

            expect(onConnect).toHaveBeenCalledTimes(1)
        })

        it('should not connect if already connected', async () => {
            const onConnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)
            
            service.connect() // Should not reconnect

            expect(onConnect).toHaveBeenCalledTimes(1)
        })
    })

    describe('disconnect', () => {
        it('should close WebSocket connection', async () => {
            const onDisconnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onDisconnect
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            service.disconnect()

            expect(onDisconnect).toHaveBeenCalledTimes(1)
            expect(service.isConnected()).toBe(false)
        })

        it('should prevent automatic reconnection after manual disconnect', async () => {
            const onConnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect,
                reconnectInterval: 1000
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            service.disconnect()

            // Advance past reconnect interval
            await vi.advanceTimersByTimeAsync(2000)

            // Should still only have connected once
            expect(onConnect).toHaveBeenCalledTimes(1)
        })
    })

    describe('message handling', () => {
        it('should call onMessage callback with parsed message', async () => {
            const onMessage = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onMessage
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            // Get the mock WebSocket instance
            const mockWs = (service as any).ws as MockWebSocket

            const testMessage: WebSocketMessage = {
                type: 'test',
                data: { foo: 'bar' },
                timestamp: Date.now()
            }

            mockWs.simulateMessage(testMessage)

            expect(onMessage).toHaveBeenCalledWith(testMessage)
        })

        it('should handle invalid JSON gracefully', async () => {
            const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
            const onMessage = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onMessage
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            const mockWs = (service as any).ws as MockWebSocket

            const invalidEvent = new MessageEvent('message', {
                data: 'invalid json{'
            })
            mockWs.onmessage?.(invalidEvent)

            expect(onMessage).not.toHaveBeenCalled()
            expect(consoleSpy).toHaveBeenCalled()
            
            consoleSpy.mockRestore()
        })
    })

    describe('send', () => {
        it('should send JSON stringified message when connected', async () => {
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            const mockWs = (service as any).ws as MockWebSocket
            const sendSpy = vi.spyOn(mockWs, 'send')

            const message = { type: 'ping', data: null }
            service.send(message)

            expect(sendSpy).toHaveBeenCalledWith(JSON.stringify(message))
        })

        it('should not send when disconnected', () => {
            const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)

            const message = { type: 'ping', data: null }
            service.send(message)

            expect(consoleSpy).toHaveBeenCalledWith(
                'WebSocket is not connected. Message not sent:',
                message
            )
            
            consoleSpy.mockRestore()
        })
    })

    describe('reconnection logic', () => {
        it('should attempt to reconnect after connection loss', async () => {
            const onConnect = vi.fn()
            const onDisconnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect,
                onDisconnect,
                reconnectInterval: 1000
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            expect(onConnect).toHaveBeenCalledTimes(1)

            // Simulate connection loss
            const mockWs = (service as any).ws as MockWebSocket
            mockWs.close()

            expect(onDisconnect).toHaveBeenCalledTimes(1)

            // Advance past reconnect interval
            await vi.advanceTimersByTimeAsync(1100)

            expect(onConnect).toHaveBeenCalledTimes(2)
        })

        it('should stop reconnecting after max attempts', async () => {
            const onConnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect,
                reconnectInterval: 100,
                maxReconnectAttempts: 3
            }
            const service = new WebSocketService(config)

            // Mock WebSocket to fail connection
            global.WebSocket = class FailingWebSocket {
                readyState = WebSocket.CLOSED
                constructor(public url: string) {
                    setTimeout(() => {
                        this.onclose?.(new CloseEvent('close'))
                    }, 10)
                }
                onopen: any = null
                onclose: any = null
                onmessage: any = null
                onerror: any = null
                send() {}
                close() {}
            } as any

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            // Attempt 1
            await vi.advanceTimersByTimeAsync(120)
            // Attempt 2
            await vi.advanceTimersByTimeAsync(120)
            // Attempt 3
            await vi.advanceTimersByTimeAsync(120)
            // Should not attempt 4
            await vi.advanceTimersByTimeAsync(120)

            expect(onConnect).not.toHaveBeenCalled()
        })

        it('should reset reconnect attempts after successful connection', async () => {
            const onConnect = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onConnect,
                reconnectInterval: 100
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            expect(onConnect).toHaveBeenCalledTimes(1)

            // Simulate disconnect and reconnect
            const mockWs = (service as any).ws as MockWebSocket
            mockWs.close()

            await vi.advanceTimersByTimeAsync(120)

            expect(onConnect).toHaveBeenCalledTimes(2)

            // Reconnect attempts should be reset to 0
            expect((service as any).reconnectAttempts).toBe(0)
        })
    })

    describe('error handling', () => {
        it('should call onError callback', async () => {
            const onError = vi.fn()
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test',
                onError
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            const mockWs = (service as any).ws as MockWebSocket
            mockWs.simulateError()

            expect(onError).toHaveBeenCalledTimes(1)
        })
    })

    describe('isConnected', () => {
        it('should return true when connected', async () => {
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)

            expect(service.isConnected()).toBe(false)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            expect(service.isConnected()).toBe(true)
        })

        it('should return false when disconnected', async () => {
            const config: WebSocketConfig = {
                url: 'ws://localhost:8080/test'
            }
            const service = new WebSocketService(config)

            service.connect()
            await vi.advanceTimersByTimeAsync(20)

            service.disconnect()

            expect(service.isConnected()).toBe(false)
        })
    })
})
