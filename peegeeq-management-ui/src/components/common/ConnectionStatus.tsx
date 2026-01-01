import { useState, useEffect } from 'react'
import { Badge, Tooltip } from 'antd'
import { getBackendConfig, getVersionedApiUrl } from '../../services/configService'

interface ConnectionStatusProps {
    className?: string
}

const ConnectionStatus = ({ className }: ConnectionStatusProps) => {
    const [isRestConnected, setIsRestConnected] = useState(false)
    const [isWsConnected, setIsWsConnected] = useState(false)
    const [isSseConnected, setIsSseConnected] = useState(false)
    const [lastPing, setLastPing] = useState<Date | null>(null)
    const [currentUrl, setCurrentUrl] = useState<string>('')
    const [wsInstance, setWsInstance] = useState<WebSocket | null>(null)

    // Check REST API connection status
    const checkRestConnection = async () => {
        try {
            // Get fresh config every time
            const config = getBackendConfig()
            const url = getVersionedApiUrl('health', config.apiUrl)

            console.log(`[ConnectionStatus] Checking REST connection to: ${url}`)
            setCurrentUrl(url)

            const response = await fetch(url, {
                method: 'GET',
                signal: AbortSignal.timeout(5000) // 5 second timeout
            })

            if (response.ok) {
                console.log(`[ConnectionStatus] REST Connected to: ${url}`)
                setIsRestConnected(true)
                setLastPing(new Date())
            } else {
                console.log(`[ConnectionStatus] REST Failed to connect to: ${url} - Status: ${response.status}`)
                setIsRestConnected(false)
            }
        } catch (error) {
            console.log(`[ConnectionStatus] REST Error connecting to: ${currentUrl}`, error)
            setIsRestConnected(false)
        }
    }

    // Check WebSocket connection status
    const checkWsConnection = () => {
        try {
            const config = getBackendConfig()
            const wsUrl = `${config.apiUrl.replace('http', 'ws')}/ws/health`

            console.log(`[ConnectionStatus] Checking WebSocket connection to: ${wsUrl}`)

            // Close existing connection if any
            if (wsInstance) {
                wsInstance.close()
            }

            const ws = new WebSocket(wsUrl)

            ws.onopen = () => {
                console.log(`[ConnectionStatus] WebSocket Connected to: ${wsUrl}`)
                setIsWsConnected(true)
                // Close immediately after confirming connection
                ws.close()
            }

            ws.onerror = (error) => {
                console.log(`[ConnectionStatus] WebSocket Error:`, error)
                setIsWsConnected(false)
            }

            ws.onclose = () => {
                console.log(`[ConnectionStatus] WebSocket Closed`)
                // Don't set to false here - we already confirmed connection in onopen
            }

            setWsInstance(ws)
        } catch (error) {
            console.log(`[ConnectionStatus] WebSocket Error:`, error)
            setIsWsConnected(false)
        }
    }

    // Check SSE connection status
    const checkSseConnection = () => {
        try {
            const config = getBackendConfig()
            const sseUrl = getVersionedApiUrl('sse/health', config.apiUrl)

            console.log(`[ConnectionStatus] Checking SSE connection to: ${sseUrl}`)

            const eventSource = new EventSource(sseUrl)
            const timeout: ReturnType<typeof setTimeout> = setTimeout(() => {
                eventSource.close()
                console.log(`[ConnectionStatus] SSE connection timeout`)
                setIsSseConnected(false)
            }, 5000)

            eventSource.onmessage = () => {
                clearTimeout(timeout)
                console.log(`[ConnectionStatus] SSE Connected to: ${sseUrl}`)
                setIsSseConnected(true)
                eventSource.close()
            }

            eventSource.onerror = (error) => {
                clearTimeout(timeout)
                console.log(`[ConnectionStatus] SSE Error:`, error)
                setIsSseConnected(false)
                eventSource.close()
            }
        } catch (error) {
            console.log(`[ConnectionStatus] SSE Error:`, error)
            setIsSseConnected(false)
        }
    }

    useEffect(() => {
        // Initial check
        checkRestConnection()
        checkWsConnection()
        checkSseConnection()

        // Check every 10 seconds (more frequent for better UX)
        const interval = setInterval(() => {
            checkRestConnection()
            // Don't recheck WebSocket/SSE automatically - they maintain their own connections
        }, 10000)

        // Listen for config changes (when config is saved in Settings)
        const handleConfigChange = () => {
            console.log('[ConnectionStatus] Config changed event received, rechecking...')
            checkRestConnection()
            checkWsConnection()
            checkSseConnection()
        }
        window.addEventListener('peegeeq-config-changed', handleConfigChange)

        return () => {
            clearInterval(interval)
            window.removeEventListener('peegeeq-config-changed', handleConfigChange)
            // Clean up WebSocket
            if (wsInstance) {
                wsInstance.close()
            }
        }
    }, []) // Empty deps is OK now - functions always get fresh config

    const getStatusText = () => {
        const restStatus = isRestConnected ? 'REST ✓' : 'REST ✗'
        const wsStatus = isWsConnected ? 'WS ✓' : 'WS ✗'
        const sseStatus = isSseConnected ? 'SSE ✓' : 'SSE ✗'
        const pingInfo = lastPing ? `\nLast ping: ${lastPing.toLocaleTimeString()}` : ''
        return `${restStatus} ${wsStatus} ${sseStatus}${pingInfo}`
    }

    const getOverallStatus = () => {
        if (isRestConnected && isWsConnected && isSseConnected) return 'success'
        if (isRestConnected) return 'warning' // REST works but WS/SSE don't
        return 'error'
    }

    const getOverallText = () => {
        const restIcon = isRestConnected ? '✓' : '✗'
        const wsIcon = isWsConnected ? '✓' : '✗'
        const sseIcon = isSseConnected ? '✓' : '✗'

        if (isRestConnected && isWsConnected && isSseConnected) {
            return `Online (REST ${restIcon} WS ${wsIcon} SSE ${sseIcon})`
        }
        if (isRestConnected) {
            return `Partial (REST ${restIcon} WS ${wsIcon} SSE ${sseIcon})`
        }
        return `Offline (REST ${restIcon} WS ${wsIcon} SSE ${sseIcon})`
    }

    return (
        <Tooltip title={<div style={{ whiteSpace: 'pre-line' }}>{getStatusText()}</div>}>
            <div
                data-testid="connection-status"
                className={className}
                style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    fontSize: '13px',
                    color: '#595959'
                }}
            >
                <Badge status={getOverallStatus()} />
                <span>{getOverallText()}</span>
            </div>
        </Tooltip>
    )
}

export default ConnectionStatus
