import { useState, useEffect } from 'react'
import { Badge, Tooltip } from 'antd'

interface ConnectionStatusProps {
  className?: string
}

const ConnectionStatus = ({ className }: ConnectionStatusProps) => {
  const [isConnected, setIsConnected] = useState(false)
  const [lastPing, setLastPing] = useState<Date | null>(null)

  useEffect(() => {
    // Check connection status
    const checkConnection = async () => {
      try {
        const response = await fetch('/api/v1/health', {
          method: 'GET'
        })
        
        if (response.ok) {
          setIsConnected(true)
          setLastPing(new Date())
        } else {
          setIsConnected(false)
        }
      } catch (error) {
        setIsConnected(false)
      }
    }

    // Initial check
    checkConnection()

    // Check every 30 seconds
    const interval = setInterval(checkConnection, 30000)

    return () => clearInterval(interval)
  }, [])

  const getStatusText = () => {
    if (isConnected) {
      return lastPing 
        ? `Connected (last ping: ${lastPing.toLocaleTimeString()})`
        : 'Connected'
    }
    return 'Disconnected'
  }

  const getStatusColor = () => {
    return isConnected ? 'success' : 'error'
  }

  return (
    <Tooltip title={getStatusText()}>
      <div className={`status-indicator ${isConnected ? 'online' : 'offline'} ${className || ''}`}>
        <Badge status={getStatusColor()} />
        <span>{isConnected ? 'Online' : 'Offline'}</span>
      </div>
    </Tooltip>
  )
}

export default ConnectionStatus
