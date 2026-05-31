import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'

export interface SystemStats {
    totalSetups: number
    totalQueues: number
    totalConsumerGroups: number
    totalEventStores: number
    totalMessages: number
    messagesPerSecond: number
    activeConnections: number
    uptime: string
}

export interface SetupSummary {
    setupId: string
    status: string
    totalQueues: number
    totalConsumerGroups: number
    totalEventStores: number
    totalMessages: number
    messagesPerSecond: number
    queues: QueueInfo[]
    consumerGroups: ConsumerGroupInfo[]
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

export interface UtilitiesState {
    // System data
    systemStats: SystemStats
    setups: SetupSummary[]
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

const DEFAULT_STATS: SystemStats = {
    totalSetups: 0,
    totalQueues: 0,
    totalConsumerGroups: 0,
    totalEventStores: 0,
    totalMessages: 0,
    messagesPerSecond: 0,
    activeConnections: 0,
    uptime: '0s',
}

export const useUtilitiesStore = create<UtilitiesState>()(
    devtools(
        (set, get) => ({
            // Initial state
            systemStats: DEFAULT_STATS,
            setups: [],
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
                    const totals = data.systemTotals || {}

                    const setups: SetupSummary[] = (data.setups || []).map((s: any) => ({
                        setupId: s.setupId,
                        status: s.status,
                        totalQueues: s.totalQueues || 0,
                        totalConsumerGroups: s.totalConsumerGroups || 0,
                        totalEventStores: s.totalEventStores || 0,
                        totalMessages: s.totalMessages || 0,
                        messagesPerSecond: s.messagesPerSecond || 0,
                        queues: (s.queues || []).map((q: any, idx: number) => ({
                            key: `${s.setupId}-${idx}`,
                            name: q.name,
                            setup: s.setupId,
                            messages: q.messages || 0,
                            consumers: q.consumers || 0,
                            status: q.status,
                            messageRate: q.messageRate || 0,
                        })),
                        consumerGroups: (s.consumerGroups || []).map((g: any, idx: number) => ({
                            key: `${s.setupId}-cg-${idx}`,
                            groupName: g.name || g.groupName,
                            queueName: g.queueName,
                            members: g.members || 0,
                            status: g.status || 'ACTIVE',
                        })),
                    }))

                    // Flat queues list derived from all setups (used by queue table)
                    const queues: QueueInfo[] = setups.flatMap(s => s.queues)

                    set({
                        systemStats: {
                            totalSetups: totals.totalSetups || 0,
                            totalQueues: totals.totalQueues || 0,
                            totalConsumerGroups: totals.totalConsumerGroups || 0,
                            totalEventStores: totals.totalEventStores || 0,
                            totalMessages: totals.totalMessages || 0,
                            messagesPerSecond: totals.messagesPerSecond || 0,
                            activeConnections: 0,
                            uptime: totals.uptime || '0s',
                        },
                        setups,
                        queues,
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
                        ...state.throughputData.slice(-19),
                        { time: timeLabel, messages: stats.messagesPerSecond }
                    ],
                    connectionData: [
                        ...state.connectionData.slice(-19),
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
                await Promise.all([fetchSystemData(), fetchQueues(), fetchConsumerGroups()])
            },
        }),
        { name: 'utilities-store' }
    )
)
