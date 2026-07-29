/**
 * Zustand store backing the Overview page.
 *
 * Holds exactly what Overview renders: the per-setup list, plus the loading and
 * error state of the one fetch that produces it.
 *
 * Trimmed 2026-07-29. The store previously carried a large second surface that
 * nothing consumed: global `systemStats`, flat `queues` / `consumerGroups`
 * arrays with their own fetchers, `throughputData` / `connectionData` rolling
 * series with an `updateChartData` reducer, `wsConnected` / `sseConnected`
 * flags with setters, `lastUpdated`, `refreshAll`, and `setSystemStats` /
 * `setLoading` / `setError`. Overview is the store's only consumer and
 * destructures four members; every other member had zero references anywhere in
 * the app. `updateChartData` ran on every 30-second poll appending to two arrays
 * no component rendered, and `recharts` — the library those series existed for —
 * was a declared dependency imported by no file. Deleted rather than left in
 * place: unused state invites a future reader to trust it, and this store's
 * global counters were a per-setup design's discarded predecessor.
 *
 * `activeConnections` went with it. `getSystemOverview` returns a real value for
 * it, which `fetchSystemData` discarded and replaced with a hardcoded 0 before
 * plotting it into the chart series nothing displayed.
 */
import { create } from 'zustand'
import { devtools } from 'zustand/middleware'
import axios from 'axios'
import { getVersionedApiUrl } from '../services/configService'

/** A queue within a setup, as rendered by the Overview detail card. */
export interface QueueInfo {
    name: string
    type?: string
    messages: number
    messageRate: number
}

/** A consumer group within a setup, as rendered by the Overview detail card. */
export interface ConsumerGroupInfo {
    groupName: string
    queueName: string
    status: string
}

/** One setup and its contents — the unit the Overview page is built around. */
export interface SetupSummary {
    setupId: string
    status: string
    totalQueues: number
    queues: QueueInfo[]
    consumerGroups: ConsumerGroupInfo[]
    eventStores: string[]
}

export interface UtilitiesState {
    setups: SetupSummary[]
    loading: boolean
    error: string | null
    fetchSystemData: () => Promise<void>
}

export const useUtilitiesStore = create<UtilitiesState>()(
    devtools(
        (set) => ({
            setups: [],
            loading: false,
            error: null,

            fetchSystemData: async () => {
                set({ loading: true, error: null })
                try {
                    const response = await axios.get(getVersionedApiUrl('management/overview'))
                    const data = response.data

                    const setups: SetupSummary[] = (data.setups || []).map((s: any) => ({
                        setupId: s.setupId,
                        status: s.status,
                        totalQueues: s.totalQueues || 0,
                        queues: (s.queues || []).map((q: any) => ({
                            name: q.name,
                            type: q.type || q.implementationType,
                            messages: q.messages || 0,
                            messageRate: q.messageRate || 0,
                        })),
                        consumerGroups: (s.consumerGroups || []).map((g: any) => ({
                            groupName: g.name || g.groupName,
                            queueName: g.queueName,
                            status: g.status || 'ACTIVE',
                        })),
                        eventStores: (s.eventStores || []).map((es: any) =>
                            typeof es === 'string' ? es : es.name
                        ),
                    }))

                    set({ setups, loading: false })
                } catch (error) {
                    console.error('Failed to fetch system data:', error)
                    set({
                        error: 'Failed to load system data. Please check if the backend service is running.',
                        loading: false
                    })
                }
            },
        }),
        { name: 'utilities-store' }
    )
)
