import { describe, it, expect, beforeEach } from 'vitest'
import { useManagementStore } from './managementStore'

const baseState = {
    systemStats: {
        totalQueues: 0,
        totalConsumerGroups: 0,
        totalEventStores: 0,
        totalMessages: 0,
        messagesPerSecond: 0,
        monitoringSessions: 0,
        activeSubscriptions: 0,
        dbPool: { active: 0, idle: 0, pending: 0, total: 0, perSetup: [] },
        uptime: '0s',
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
    selectedSetupId: null,
    selectedQueueName: null,
}

describe('managementStore', () => {
    beforeEach(() => {
        localStorage.clear()
        useManagementStore.setState(baseState)
    })

    // ── connection status ──────────────────────────────────────────────────────

    describe('setWebSocketStatus', () => {
        it('sets wsConnected true and clears wsReconnecting', () => {
            useManagementStore.setState({ wsReconnecting: true })
            useManagementStore.getState().setWebSocketStatus(true)
            const { wsConnected, wsReconnecting } = useManagementStore.getState()
            expect(wsConnected).toBe(true)
            expect(wsReconnecting).toBe(false)
        })

        it('sets wsConnected false', () => {
            useManagementStore.setState({ wsConnected: true })
            useManagementStore.getState().setWebSocketStatus(false)
            expect(useManagementStore.getState().wsConnected).toBe(false)
        })
    })

    describe('setSSEStatus', () => {
        it('sets sseConnected true and clears sseReconnecting', () => {
            useManagementStore.setState({ sseReconnecting: true })
            useManagementStore.getState().setSSEStatus(true)
            const { sseConnected, sseReconnecting } = useManagementStore.getState()
            expect(sseConnected).toBe(true)
            expect(sseReconnecting).toBe(false)
        })
    })

    describe('setWsReconnecting / setSseReconnecting', () => {
        it('sets wsReconnecting without touching wsConnected', () => {
            useManagementStore.setState({ wsConnected: true })
            useManagementStore.getState().setWsReconnecting(true)
            const { wsConnected, wsReconnecting } = useManagementStore.getState()
            expect(wsConnected).toBe(true)
            expect(wsReconnecting).toBe(true)
        })

        it('sets sseReconnecting', () => {
            useManagementStore.getState().setSseReconnecting(true)
            expect(useManagementStore.getState().sseReconnecting).toBe(true)
        })
    })

    // ── notifications ──────────────────────────────────────────────────────────

    describe('addNotification', () => {
        it('creates a notification with generated id, ISO timestamp and read=false', () => {
            useManagementStore.getState().addNotification({ resource: 'queue', action: 'created' })
            const { notifications, unreadCount } = useManagementStore.getState()
            expect(notifications).toHaveLength(1)
            const n = notifications[0]
            expect(n.resource).toBe('queue')
            expect(n.action).toBe('created')
            expect(n.read).toBe(false)
            expect(n.id).toBeTruthy()
            expect(n.timestamp).toBeTruthy()
            expect(unreadCount).toBe(1)
        })

        it('defaults description to "{action} — {resource}" when none is provided', () => {
            useManagementStore.getState().addNotification({ resource: 'orders', action: 'queue created' })
            expect(useManagementStore.getState().notifications[0].description).toBe('queue created — orders')
        })

        it('uses an explicit description when one is provided', () => {
            useManagementStore.getState().addNotification({
                resource: 'orders',
                action: 'queue created',
                description: "Queue 'orders' created in setup 'default'",
            })
            expect(useManagementStore.getState().notifications[0].description)
                .toBe("Queue 'orders' created in setup 'default'")
        })

        it('prepends so newest notification is first', () => {
            useManagementStore.getState().addNotification({ resource: 'queue', action: 'created' })
            useManagementStore.getState().addNotification({ resource: 'setup', action: 'deleted' })
            const { notifications } = useManagementStore.getState()
            expect(notifications[0].resource).toBe('setup')
            expect(notifications[1].resource).toBe('queue')
        })

        it('caps the list at 50 entries', () => {
            for (let i = 0; i < 55; i++) {
                useManagementStore.getState().addNotification({ resource: 'queue', action: `action-${i}` })
            }
            const { notifications, unreadCount } = useManagementStore.getState()
            expect(notifications).toHaveLength(50)
            expect(unreadCount).toBe(50)
        })
    })

    describe('markAllNotificationsRead', () => {
        it('marks every notification read and resets unreadCount to 0', () => {
            useManagementStore.getState().addNotification({ resource: 'queue', action: 'created' })
            useManagementStore.getState().addNotification({ resource: 'setup', action: 'deleted' })
            useManagementStore.getState().markAllNotificationsRead()
            const { notifications, unreadCount } = useManagementStore.getState()
            expect(unreadCount).toBe(0)
            expect(notifications.every(n => n.read)).toBe(true)
        })
    })

    describe('clearNotifications', () => {
        it('empties the list and resets unreadCount', () => {
            useManagementStore.getState().addNotification({ resource: 'queue', action: 'created' })
            useManagementStore.getState().clearNotifications()
            const { notifications, unreadCount } = useManagementStore.getState()
            expect(notifications).toHaveLength(0)
            expect(unreadCount).toBe(0)
        })
    })

    // ── chart data ─────────────────────────────────────────────────────────────

    describe('updateChartData', () => {
        const stats = {
            totalQueues: 3, totalConsumerGroups: 2, totalEventStores: 1,
            totalMessages: 100, messagesPerSecond: 5.5,
            monitoringSessions: 2, activeSubscriptions: 1,
            dbPool: { active: 4, idle: 3, pending: 1, total: 8, perSetup: [] },
            uptime: '1m 30s',
        }

        it('appends one point to throughputData and connectionData', () => {
            useManagementStore.getState().updateChartData(stats)
            const { throughputData, connectionData } = useManagementStore.getState()
            expect(throughputData).toHaveLength(1)
            expect(throughputData[0].messages).toBe(5.5)
            expect(connectionData).toHaveLength(1)
            expect(connectionData[0].active).toBe(4)
            expect(connectionData[0].idle).toBe(3)
            expect(connectionData[0].pending).toBe(1)
        })

        it('caps both series at 20 points, keeping the most recent', () => {
            for (let i = 0; i < 25; i++) {
                useManagementStore.getState().updateChartData({
                    ...stats, messagesPerSecond: i,
                    dbPool: { active: i, idle: 0, pending: 0, total: i, perSetup: [] },
                })
            }
            const { throughputData, connectionData } = useManagementStore.getState()
            expect(throughputData).toHaveLength(20)
            expect(connectionData).toHaveLength(20)
            // index 24 is the last appended value
            expect(throughputData[19].messages).toBe(24)
            expect(connectionData[19].active).toBe(24)
        })
    })

    // ── system stats ───────────────────────────────────────────────────────────

    describe('setSystemStats', () => {
        it('replaces systemStats and updates lastUpdated', () => {
            const before = useManagementStore.getState().lastUpdated
            const stats = {
                totalQueues: 5, totalConsumerGroups: 3, totalEventStores: 2,
                totalMessages: 200, messagesPerSecond: 10,
                monitoringSessions: 3, activeSubscriptions: 2,
                dbPool: { active: 8, idle: 4, pending: 0, total: 12, perSetup: [] },
                uptime: '5m',
            }
            useManagementStore.getState().setSystemStats(stats)
            const state = useManagementStore.getState()
            expect(state.systemStats).toEqual(stats)
            expect(state.lastUpdated).not.toBe(before)
            expect(state.lastUpdated).toBeTruthy()
        })
    })

    // ── selection + localStorage ───────────────────────────────────────────────

    describe('setSelectedSetup', () => {
        it('sets selectedSetupId and clears selectedQueueName', () => {
            useManagementStore.setState({ selectedQueueName: 'my-queue' })
            useManagementStore.getState().setSelectedSetup('default')
            const { selectedSetupId, selectedQueueName } = useManagementStore.getState()
            expect(selectedSetupId).toBe('default')
            expect(selectedQueueName).toBeNull()
        })

        it('persists setupId to localStorage', () => {
            useManagementStore.getState().setSelectedSetup('prod')
            expect(localStorage.getItem('pgq-selected-setup')).toBe('prod')
        })

        it('removes key from localStorage when set to null', () => {
            localStorage.setItem('pgq-selected-setup', 'prod')
            useManagementStore.getState().setSelectedSetup(null)
            expect(localStorage.getItem('pgq-selected-setup')).toBeNull()
        })
    })

    describe('setSelectedQueue', () => {
        it('sets selectedQueueName', () => {
            useManagementStore.getState().setSelectedQueue('orders')
            expect(useManagementStore.getState().selectedQueueName).toBe('orders')
        })

        it('persists queueName to localStorage', () => {
            useManagementStore.getState().setSelectedQueue('orders')
            expect(localStorage.getItem('pgq-selected-queue')).toBe('orders')
        })

        it('removes key from localStorage when set to null', () => {
            localStorage.setItem('pgq-selected-queue', 'orders')
            useManagementStore.getState().setSelectedQueue(null)
            expect(localStorage.getItem('pgq-selected-queue')).toBeNull()
        })
    })

    // ── loading / error ────────────────────────────────────────────────────────

    describe('setLoading / setError', () => {
        it('setLoading toggles the loading flag', () => {
            useManagementStore.getState().setLoading(true)
            expect(useManagementStore.getState().loading).toBe(true)
            useManagementStore.getState().setLoading(false)
            expect(useManagementStore.getState().loading).toBe(false)
        })

        it('setError stores and clears the error message', () => {
            useManagementStore.getState().setError('Something went wrong')
            expect(useManagementStore.getState().error).toBe('Something went wrong')
            useManagementStore.getState().setError(null)
            expect(useManagementStore.getState().error).toBeNull()
        })
    })

})
