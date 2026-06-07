import { createApi } from '@reduxjs/toolkit/query/react';
import type {
    QueueDetails,
    QueueFilters,
    QueueListResponse,
    QueueConfig,
    GetMessagesOptions,
    GetMessagesResponse,
    PublishMessageRequest,
    QueueOperationRequest,
    MoveMessagesRequest,
    QueueChartData,
} from '../../types/queue';
import { validateQueueListResponse } from '../../types/queue.validation';
import { dynamicBaseQuery } from './apiBase';

export const queuesApi = createApi({
    reducerPath: 'queuesApi',
    baseQuery: dynamicBaseQuery,
    tagTypes: ['Queue', 'QueueDetails', 'QueueMessages', 'QueueCharts'],
    endpoints: (builder) => ({
        // Get list of queues with optional filters
        getQueues: builder.query<QueueListResponse, QueueFilters | void>({
            query: (filters) => {
                const params = new URLSearchParams();
                if (filters) {
                    if (filters.type) params.append('type', filters.type.join(','));
                    if (filters.status) params.append('status', filters.status.join(','));
                    if (filters.setupId) params.append('setupId', filters.setupId);
                    if (filters.search) params.append('search', filters.search);
                    if (filters.sortBy) params.append('sortBy', filters.sortBy);
                    if (filters.sortOrder) params.append('sortOrder', filters.sortOrder);
                    if (filters.page) params.append('page', filters.page.toString());
                    if (filters.pageSize) params.append('pageSize', filters.pageSize.toString());
                }
                return `/management/queues?${params.toString()}`;
            },
            transformResponse: (response: unknown) => {
                // Validate and sanitize the response data
                return validateQueueListResponse(response);
            },
            providesTags: (result) =>
                result
                    ? [
                        ...result.queues.map(({ setupId, queueName }) => ({ type: 'Queue' as const, id: `${setupId}:${queueName}` })),
                        { type: 'Queue', id: 'LIST' },
                    ]
                    : [{ type: 'Queue', id: 'LIST' }],
        }),

        // Get detailed information about a specific queue
        getQueueDetails: builder.query<QueueDetails, { setupId: string; queueName: string }>({
            query: ({ setupId, queueName }) => `/queues/${setupId}/${queueName}`,
            transformResponse: (raw: Record<string, any>): QueueDetails => {
                // Backend field names differ from the TypeScript interface:
                //   name → queueName, setup → setupId, implementationType → type,
                //   messages → messageCount, messageRate → messagesPerSecond,
                //   lastActivity → updatedAt
                // Statistics sub-object also uses different names:
                //   totalMessages → messageCount, avgProcessingTimeMs → processingTime.avg
                const stats = raw.statistics ?? {};
                const cfg = raw.config ?? {};
                const queueType = (raw.implementationType ?? raw.type ?? 'native') as import('../../types/queue').QueueType;
                return {
                    setupId: raw.setup ?? raw.setupId ?? '',
                    queueName: raw.name ?? raw.queueName ?? '',
                    type: queueType,
                    status: raw.status ?? 'active',
                    messageCount: raw.messages ?? raw.messageCount ?? 0,
                    consumerCount: raw.consumers ?? raw.consumerCount ?? 0,
                    messagesPerSecond: raw.messageRate ?? raw.messagesPerSecond ?? 0,
                    errorRate: raw.errorRate ?? 0,
                    createdAt: typeof raw.createdAt === 'number'
                        ? new Date(raw.createdAt).toISOString()
                        : (raw.createdAt ?? ''),
                    updatedAt: raw.lastActivity ?? raw.updatedAt ?? raw.createdAt ?? '',
                    description: raw.description,
                    tags: raw.tags,
                    config: {
                        type: queueType,
                        visibilityTimeoutSeconds: cfg.visibilityTimeoutSeconds ?? 300,
                        maxRetries: cfg.maxRetries ?? 3,
                        deadLetterEnabled: cfg.deadLetterEnabled ?? true,
                        batchSize: cfg.batchSize ?? 10,
                        pollingIntervalSeconds: cfg.pollingIntervalSeconds ?? 5,
                        fifoEnabled: cfg.fifoEnabled ?? false,
                        deadLetterQueueName: cfg.deadLetterQueueName ?? null,
                    } as any,
                    consumers: raw.consumersList ?? [],
                    statistics: {
                        messageCount: stats.totalMessages ?? stats.messageCount ?? raw.messages ?? 0,
                        messagesPerSecond: stats.messagesPerSecond ?? raw.messageRate ?? 0,
                        consumerCount: stats.activeConsumers ?? stats.consumerCount ?? raw.consumers ?? 0,
                        activeConsumers: stats.activeConsumers ?? 0,
                        processingTime: {
                            avg: stats.avgProcessingTimeMs ?? stats.processingTime?.avg ?? 0,
                            p50: stats.processingTime?.p50 ?? 0,
                            p95: stats.processingTime?.p95 ?? 0,
                            p99: stats.processingTime?.p99 ?? 0,
                        },
                        errorRate: stats.errorRate ?? 0,
                        queueDepth: stats.queueDepth ?? stats.totalMessages ?? raw.messages ?? 0,
                    },
                } as QueueDetails;
            },
            providesTags: (_result, _error, { setupId, queueName }) => [
                { type: 'QueueDetails', id: `${setupId}:${queueName}` },
            ],
        }),

        // Create a new queue
        createQueue: builder.mutation<QueueDetails, {
            setupId: string;
            name: string;
            type?: string;
            visibilityTimeoutSeconds?: number;
            maxRetries?: number;
            deadLetterEnabled?: boolean;
            batchSize?: number;
            pollingIntervalSeconds?: number;
            fifoEnabled?: boolean;
            deadLetterQueueName?: string;
        }>({
            query: ({ setupId, name, type = 'native', ...config }) => ({
                url: `/management/queues`,
                method: 'POST',
                // Backend expects "setup" not "setupId"; config fields are passed at top level
                body: { setup: setupId, name, type, ...config },
            }),
            invalidatesTags: [{ type: 'Queue', id: 'LIST' }],
        }),

        // Update queue configuration
        updateQueueConfig: builder.mutation<QueueDetails, { setupId: string; queueName: string; config: Partial<QueueConfig> }>({
            query: ({ setupId, queueName, config }) => ({
                url: `/management/queues/${setupId}/${queueName}/config`,
                method: 'PATCH',
                body: config,
            }),
            invalidatesTags: (_result, _error, { setupId, queueName }) => [
                { type: 'QueueDetails', id: `${setupId}:${queueName}` },
                { type: 'Queue', id: `${setupId}:${queueName}` },
            ],
        }),

        // Get messages from queue (non-destructive browsing)
        getMessages: builder.query<GetMessagesResponse, { setupId: string; queueName: string; options?: GetMessagesOptions }>({
            query: ({ setupId, queueName, options }) => {
                const params = new URLSearchParams();
                if (options?.count) params.append('count', options.count.toString());
                if (options?.ackMode) params.append('ackMode', options.ackMode);
                if (options?.offset) params.append('offset', options.offset.toString());
                if (options?.filter) params.append('filter', options.filter);
                return `/management/queues/${setupId}/${queueName}/messages?${params.toString()}`;
            },
            providesTags: (_result, _error, { setupId, queueName }) => [
                { type: 'QueueMessages', id: `${setupId}:${queueName}` },
            ],
        }),

        // Publish a test message to queue
        publishMessage: builder.mutation<{ messageId: string }, { setupId: string; queueName: string; message: PublishMessageRequest }>({
            query: ({ setupId, queueName, message }) => ({
                url: `/management/queues/${setupId}/${queueName}/publish`,
                method: 'POST',
                body: message,
            }),
            invalidatesTags: (_result, _error, { setupId, queueName }) => [
                { type: 'QueueDetails', id: `${setupId}:${queueName}` },
                { type: 'QueueMessages', id: `${setupId}:${queueName}` },
            ],
        }),

        // Perform queue operation (purge, delete, etc.)
        performQueueOperation: builder.mutation<void, { setupId: string; queueName: string; request: QueueOperationRequest }>({
            query: ({ setupId, queueName, request }) => {
                switch (request.operation) {
                    case 'PURGE':
                        return {
                            url: `/management/queues/${setupId}/${queueName}/purge`,
                            method: 'POST',
                        };
                    case 'DELETE': {
                        const params = new URLSearchParams();
                        if (request.options?.ifEmpty) params.append('ifEmpty', 'true');
                        if (request.options?.ifUnused) params.append('ifUnused', 'true');
                        return {
                            url: `/management/queues/${setupId}/${queueName}?${params.toString()}`,
                            method: 'DELETE',
                        };
                    }
                    case 'PAUSE':
                        return {
                            url: `/management/queues/${setupId}/${queueName}/pause`,
                            method: 'POST',
                        };
                    case 'RESUME':
                        return {
                            url: `/management/queues/${setupId}/${queueName}/resume`,
                            method: 'POST',
                        };
                    default:
                        throw new Error(`Unknown operation: ${request.operation}`);
                }
            },
            invalidatesTags: (_result, _error, { setupId, queueName, request }) => {
                if (request.operation === 'DELETE') {
                    return [
                        { type: 'Queue', id: 'LIST' },
                        { type: 'QueueDetails', id: `${setupId}:${queueName}` },
                    ];
                }
                return [
                    { type: 'QueueDetails', id: `${setupId}:${queueName}` },
                    { type: 'Queue', id: `${setupId}:${queueName}` },
                ];
            },
        }),

        // Move messages between queues
        moveMessages: builder.mutation<{ movedCount: number }, { setupId: string; queueName: string; request: MoveMessagesRequest }>({
            query: ({ setupId, queueName, request }) => ({
                url: `/management/queues/${setupId}/${queueName}/move`,
                method: 'POST',
                body: request,
            }),
            invalidatesTags: (_result, _error, { setupId, queueName, request }) => [
                { type: 'QueueDetails', id: `${setupId}:${queueName}` },
                { type: 'QueueDetails', id: `${request.targetSetupId}:${request.targetQueueName}` },
                { type: 'Queue', id: `${setupId}:${queueName}` },
                { type: 'Queue', id: `${request.targetSetupId}:${request.targetQueueName}` },
            ],
        }),

        // Get chart data for queue
        getQueueChartData: builder.query<QueueChartData, { setupId: string; queueName: string; timeRange?: string }>({
            query: ({ setupId, queueName, timeRange = '1h' }) =>
                `/management/queues/${setupId}/${queueName}/charts?timeRange=${timeRange}`,
            providesTags: (_result, _error, { setupId, queueName }) => [
                { type: 'QueueCharts', id: `${setupId}:${queueName}` },
            ],
        }),
    }),
});

export const {
    useGetQueuesQuery,
    useGetQueueDetailsQuery,
    useCreateQueueMutation,
    useUpdateQueueConfigMutation,
    useGetMessagesQuery,
    usePublishMessageMutation,
    usePerformQueueOperationMutation,
    useMoveMessagesMutation,
    useGetQueueChartDataQuery,
} = queuesApi;
