/**
 * API Constants for PeeGeeQ Management UI
 */

export const API_BASE = '/api'
export const API_VERSION = '/v1'
export const API_PREFIX = `${API_BASE}${API_VERSION}`

// Common Endpoints (Relative to versioned root)
export const ENDPOINTS = {
    HEALTH: 'health',
    SSE_HEALTH: 'sse/health',
    METRICS: 'management/metrics',
    OVERVIEW: 'management/overview',
    SETUPS: 'setups',
    QUEUES: 'management/queues',
    EVENT_STORES: 'management/event-stores',
    CONSUMER_GROUPS: 'management/consumer-groups',
} as const
