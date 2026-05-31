/**
 * API Constants for PeeGeeQ Utilities UI
 */

export const API_BASE = '/api'
export const API_VERSION = '/v1'
export const API_PREFIX = `${API_BASE}${API_VERSION}`

export const ENDPOINTS = {
    HEALTH: 'health',
    OVERVIEW: 'management/overview',
    QUEUES: 'management/queues',
    SETUPS: 'setups',
} as const
