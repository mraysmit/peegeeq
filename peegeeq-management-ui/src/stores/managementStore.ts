import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'

// Types for the management store
export interface SystemStats {
    totalQueues: number
    totalConsumerGroups: number
    totalEventStores: number
    totalMessages: number
    messagesPerSecond: number
    activeConnections: number
    uptime: string
}

export interface QueueInfo {
    key: string
    name: string
    setup: string
    messages: number
    consumers: number
    status: 'active' | 'idle' | 'error'
    messageRate: number
}

export interface ConsumerGroupInfo {
    key: string
    groupName: string
    queueName: string
    members: number
    status: string
}

export interface ThroughputDataPoint {
    time: string
    messages: number
}

export interface ConnectionDataPoint {
    time: string
    connections: number
}

export interface ManagementState {
    // System data
    systemStats: SystemStats
    queues: QueueInfo[]
    consumerGroups: ConsumerGroupInfo[]

    // Chart data
    throughputData: ThroughputDataPoint[]
    connectionData: ConnectionDataPoint[]

    // UI state
    loading: boolean
    error: string | null
    lastUpdated: string | null

    // Real-time connection status
    wsConnected: boolean
    sseConnected: boolean

    // Actions
    fetchSystemData: () => Promise<void>
    fetchQueues: () => Promise<void>
    fetchConsumerGroups: () => Promise<void>
    updateChartData: (stats: SystemStats) => void
    setLoading: (loading: boolean) => void
    setError: (error: string | null) => void
    setWebSocketStatus: (connected: boolean) => void
    setSSEStatus: (connected: boolean) => void
    setSystemStats: (stats: SystemStats) => void
    refreshAll: () => Promise<void>
}

export const useManagementStore = create<ManagementState>()(
    devtools(
        (set, get) => ({
            // Initial state
            systemStats: {
                totalQueues: 0,
                totalConsumerGroups: 0,
                totalEventStores: 0,
                totalMessages: 0,
                messagesPerSecond: 0,
                activeConnections: 0,
                uptime: '0s'
            },
            queues: [],
            consumerGroups: [],
            throughputData: [],
            connectionData: [],
            loading: false,
            error: null,
            lastUpdated: null,
            wsConnected: false,
            sseConnected: false,

            // Actions
            fetchSystemData: async () => {
                set({ loading: true, error: null })
                try {
                    const response = await axios.get(getVersionedApiUrl('management/overview'))
                    const data = response.data

                    set({
                        systemStats: {
                            totalQueues: data.systemStats?.totalQueues || 0,
                            totalConsumerGroups: data.systemStats?.totalConsumerGroups || 0,
                            totalEventStores: data.systemStats?.totalEventStores || 0,
                            totalMessages: data.systemStats?.totalMessages || 0,
                            messagesPerSecond: data.systemStats?.messagesPerSecond || 0,
                            activeConnections: data.systemStats?.activeConnections || 0,
                            uptime: data.systemStats?.uptime || '0s'
                        },
                        lastUpdated: new Date().toISOString(),
                        loading: false
                    })

                    // Update chart data
                    get().updateChartData(get().systemStats)

                } catch (error) {
                    console.error('Failed to fetch system data:', error)
                    set({
                        error: 'Failed to load system data. Please check if the backend service is running.',
                        loading: false
                    })
                }
            },

            fetchQueues: async () => {
                try {
                    const response = await axios.get(getVersionedApiUrl('management/queues'))
                    if (response.data.queues && Array.isArray(response.data.queues)) {
                        const queues = response.data.queues.map((queue: any, index: number) => ({
                            key: index.toString(),
                            name: queue.name,
                            setup: queue.setup,
                            messages: queue.messages,
                            consumers: queue.consumers,
                            status: queue.status,
                            messageRate: queue.messageRate || 0
                        }))
                        set({ queues })
                    }
                } catch (error) {
                    console.error('Failed to fetch queues:', error)
                    set({ error: 'Failed to load queue data' })
                }
            },

            fetchConsumerGroups: async () => {
                try {
                    const response = await axios.get(getVersionedApiUrl('management/consumer-groups'))
                    if (response.data.consumerGroups && Array.isArray(response.data.consumerGroups)) {
                        const consumerGroups = response.data.consumerGroups.map((group: any, index: number) => ({
                            key: index.toString(),
                            groupName: group.groupName || group.name,
                            queueName: group.queueName,
                            members: group.members || 0,
                            status: group.status || 'ACTIVE'
                        }))
                        set({ consumerGroups })
                    }
                } catch (error) {
                    console.error('Failed to fetch consumer groups:', error)
                    set({ error: 'Failed to load consumer group data' })
                }
            },

            updateChartData: (stats: SystemStats) => {
                const now = new Date()
                const timeLabel = now.toLocaleTimeString()

                set((state) => ({
                    throughputData: [
                        ...state.throughputData.slice(-19), // Keep last 19 points
                        { time: timeLabel, messages: stats.messagesPerSecond }
                    ],
                    connectionData: [
                        ...state.connectionData.slice(-19), // Keep last 19 points
                        { time: timeLabel, connections: stats.activeConnections }
                    ]
                }))
            },

            setLoading: (loading: boolean) => set({ loading }),
            setError: (error: string | null) => set({ error }),
            setWebSocketStatus: (connected: boolean) => set({ wsConnected: connected }),
            setSSEStatus: (connected: boolean) => set({ sseConnected: connected }),

            setSystemStats: (stats: SystemStats) => set({
                systemStats: stats,
                lastUpdated: new Date().toISOString()
            }),

            refreshAll: async () => {
                const { fetchSystemData, fetchQueues, fetchConsumerGroups } = get()
                await Promise.all([
                    fetchSystemData(),
                    fetchQueues(),
                    fetchConsumerGroups()
                ])
            }
        }),
        {
            name: 'peegeeq-management-store'
        }
    )
)
