import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'

export interface ManagementNotification {
    id: string
    timestamp: string
    resource: string
    action: string
    /** Human-readable description shown in the bell drawer and notifications page. */
    description: string
    read: boolean
}

// Types for the management store
export interface DbPoolStats {
    active: number
    idle: number
    pending: number
    total: number
    perSetup: { setupId: string; active: number; idle: number; pending: number; total: number }[]
}

export interface SystemStats {
    totalQueues: number
    totalConsumerGroups: number
    totalEventStores: number
    // totalMessages was DELETED 2026-08-09: nothing rendered it, and its two sources carried
    // two different quantities (the overview endpoint's total vs the stream's pending-backlog
    // sum, since renamed totalPendingMessages). Derived state with no reader is not kept.
    messagesPerSecond: number
    // Phase 11: the old meaningless `activeConnections` composite is split into three dimensions.
    monitoringSessions: number
    activeSubscriptions: number
    dbPool: DbPoolStats
    uptime: string
}

/**
 * Maps a GET /management/overview response's systemStats onto the current store value.
 *
 * The overview emits totals and uptime only. The live quantities — messagesPerSecond,
 * monitoringSessions, activeSubscriptions, dbPool — are owned by the monitoring stream
 * and are carried over from the current state. The previous mapping replaced the whole
 * systemStats object, so every HTTP poll reset the stream-owned fields to 0 until the
 * next stream frame (fixed 2026-08-09).
 */
export function mergeOverviewSystemStats(
    current: SystemStats,
    overviewStats: { totalQueues?: number; totalConsumerGroups?: number; totalEventStores?: number; uptime?: string } | undefined
): SystemStats {
    return {
        // Emitted by the overview endpoint.
        totalQueues: overviewStats?.totalQueues || 0,
        totalConsumerGroups: overviewStats?.totalConsumerGroups || 0,
        totalEventStores: overviewStats?.totalEventStores || 0,
        uptime: overviewStats?.uptime || '0s',
        // Stream-owned: preserved from the last stream frame, never mapped from HTTP.
        messagesPerSecond: current.messagesPerSecond,
        monitoringSessions: current.monitoringSessions,
        activeSubscriptions: current.activeSubscriptions,
        dbPool: current.dbPool,
    }
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
    active: number
    idle: number
    pending: number
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
    wsReconnecting: boolean
    sseReconnecting: boolean

    // Notification bell
    notifications: ManagementNotification[]
    unreadCount: number

    // Selection state
    selectedSetupId: string | null
    selectedQueueName: string | null

    // Actions
    fetchSystemData: () => Promise<void>
    fetchQueues: () => Promise<void>
    fetchConsumerGroups: () => Promise<void>
    updateChartData: (stats: SystemStats) => void
    setLoading: (loading: boolean) => void
    setError: (error: string | null) => void
    setWebSocketStatus: (connected: boolean) => void
    setSSEStatus: (connected: boolean) => void
    setWsReconnecting: (reconnecting: boolean) => void
    setSseReconnecting: (reconnecting: boolean) => void
    addNotification: (n: Omit<ManagementNotification, 'id' | 'timestamp' | 'read' | 'description'> & { description?: string }) => void
    markAllNotificationsRead: () => void
    clearNotifications: () => void
    setSystemStats: (stats: SystemStats) => void
    refreshAll: () => Promise<void>
    setSelectedSetup: (setupId: string | null) => void
    setSelectedQueue: (queueName: string | null) => void
}

export const useManagementStore = create<ManagementState>()(
    devtools(
        (set, get) => ({
            // Initial state
            systemStats: {
                totalQueues: 0,
                totalConsumerGroups: 0,
                totalEventStores: 0,
                messagesPerSecond: 0,
                monitoringSessions: 0,
                activeSubscriptions: 0,
                dbPool: { active: 0, idle: 0, pending: 0, total: 0, perSetup: [] },
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
            wsReconnecting: false,
            sseReconnecting: false,
            notifications: [],
            unreadCount: 0,
            selectedSetupId: (() => { try { return localStorage.getItem('pgq-selected-setup') || null } catch { return null } })(),
            selectedQueueName: (() => { try { return localStorage.getItem('pgq-selected-queue') || null } catch { return null } })(),

            // Actions
            fetchSystemData: async () => {
                set({ loading: true, error: null })
                try {
                    const response = await axios.get(getVersionedApiUrl('management/overview'))
                    const data = response.data

                    set({
                        systemStats: mergeOverviewSystemStats(get().systemStats, data.systemStats),
                        lastUpdated: new Date().toISOString(),
                        loading: false
                    })

                    // No updateChartData here: chart points come from stream frames only
                    // (Overview.tsx). The old call here pushed a point built from HTTP-poll
                    // data, inserting fabricated zeros between frames (removed 2026-08-09).

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
                        {
                            time: timeLabel,
                            active: stats.dbPool?.active ?? 0,
                            idle: stats.dbPool?.idle ?? 0,
                            pending: stats.dbPool?.pending ?? 0,
                        }
                    ]
                }))
            },

            setLoading: (loading: boolean) => set({ loading }),
            setError: (error: string | null) => set({ error }),
            setWebSocketStatus: (connected: boolean) => set({ wsConnected: connected, wsReconnecting: false }),
            setSSEStatus: (connected: boolean) => set({ sseConnected: connected, sseReconnecting: false }),
            setWsReconnecting: (reconnecting: boolean) => set({ wsReconnecting: reconnecting }),
            setSseReconnecting: (reconnecting: boolean) => set({ sseReconnecting: reconnecting }),

            addNotification: (n) => set((state) => {
                const notification: ManagementNotification = {
                    ...n,
                    id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
                    timestamp: new Date().toISOString(),
                    description: n.description ?? `${n.action} — ${n.resource}`,
                    read: false
                }
                const updated = [notification, ...state.notifications].slice(0, 50)
                return { notifications: updated, unreadCount: updated.filter(x => !x.read).length }
            }),

            markAllNotificationsRead: () => set((state) => ({
                notifications: state.notifications.map(n => ({ ...n, read: true })),
                unreadCount: 0
            })),

            clearNotifications: () => set({ notifications: [], unreadCount: 0 }),

            setSystemStats: (stats: SystemStats) => set({
                systemStats: stats,
                lastUpdated: new Date().toISOString()
            }),

            setSelectedSetup: (setupId) => {
                set({ selectedSetupId: setupId, selectedQueueName: null })
                try {
                    if (setupId) localStorage.setItem('pgq-selected-setup', setupId)
                    else localStorage.removeItem('pgq-selected-setup')
                    localStorage.removeItem('pgq-selected-queue')
                } catch { /* ignore blocked storage */ }
            },

            setSelectedQueue: (queueName) => {
                set({ selectedQueueName: queueName })
                try {
                    if (queueName) localStorage.setItem('pgq-selected-queue', queueName)
                    else localStorage.removeItem('pgq-selected-queue')
                } catch { /* ignore blocked storage */ }
            },

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
