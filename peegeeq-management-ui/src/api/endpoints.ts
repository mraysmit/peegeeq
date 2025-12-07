/**
 * PeeGeeQ REST API Endpoint Constants
 * Matches the routes defined in peegeeq-rest/PeeGeeQRestServer.java
 */

// Get API base URL from environment or default to localhost
export const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
export const API_V1_PREFIX = '/api/v1';

// ============================================================================
// Setup Endpoints
// ============================================================================

export const SETUP_ENDPOINTS = {
  /** POST - Create a new database setup */
  CREATE: `${API_V1_PREFIX}/setups`,
  /** GET - List all setups */
  LIST: `${API_V1_PREFIX}/setups`,
  /** GET - Get setup by ID */
  GET: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}`,
  /** DELETE - Delete a setup */
  DELETE: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}`,
  /** POST - Add a queue to an existing setup */
  ADD_QUEUE: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/queues`,
  /** POST - Add an event store to an existing setup */
  ADD_EVENT_STORE: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/eventstores`,
} as const;

// ============================================================================
// Database Setup Endpoints (alternative path)
// ============================================================================

export const DATABASE_SETUP_ENDPOINTS = {
  /** POST - Create database setup */
  CREATE: `${API_V1_PREFIX}/database-setup`,
  /** GET - Get database setup status */
  STATUS: (setupId: string) => `${API_V1_PREFIX}/database-setup/${setupId}/status`,
} as const;

// ============================================================================
// Queue Endpoints
// ============================================================================

export const QUEUE_ENDPOINTS = {
  /** GET - List queues for a setup */
  LIST: (setupId: string) => `${API_V1_PREFIX}/queues/${setupId}`,
  /** GET - Get queue details */
  GET: (setupId: string, queueName: string) => `${API_V1_PREFIX}/queues/${setupId}/${queueName}`,
  /** POST - Publish message to queue */
  PUBLISH: (setupId: string, queueName: string) => `${API_V1_PREFIX}/queues/${setupId}/${queueName}/publish`,
  /** GET - Get messages from queue */
  MESSAGES: (setupId: string, queueName: string) => `${API_V1_PREFIX}/queues/${setupId}/${queueName}/messages`,
  /** POST - Acknowledge message */
  ACK: (setupId: string, queueName: string, messageId: string) =>
    `${API_V1_PREFIX}/queues/${setupId}/${queueName}/messages/${messageId}/ack`,
  /** POST - Negative acknowledge message */
  NACK: (setupId: string, queueName: string, messageId: string) =>
    `${API_V1_PREFIX}/queues/${setupId}/${queueName}/messages/${messageId}/nack`,
} as const;

// ============================================================================
// Dead Letter Endpoints
// ============================================================================

export const DEAD_LETTER_ENDPOINTS = {
  /** GET - List dead letter messages */
  LIST: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/deadletters`,
  /** GET - Get dead letter message by ID */
  GET: (setupId: string, messageId: number) => `${API_V1_PREFIX}/setups/${setupId}/deadletters/${messageId}`,
  /** POST - Reprocess a dead letter message */
  REPROCESS: (setupId: string, messageId: number) =>
    `${API_V1_PREFIX}/setups/${setupId}/deadletters/${messageId}/reprocess`,
  /** DELETE - Delete a dead letter message */
  DELETE: (setupId: string, messageId: number) => `${API_V1_PREFIX}/setups/${setupId}/deadletters/${messageId}`,
  /** POST - Cleanup old dead letter messages */
  CLEANUP: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/deadletters/cleanup`,
  /** GET - Get dead letter statistics */
  STATS: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/deadletters/stats`,
} as const;

// ============================================================================
// Subscription Endpoints
// ============================================================================

export const SUBSCRIPTION_ENDPOINTS = {
  /** GET - List subscriptions */
  LIST: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/subscriptions`,
  /** GET - Get subscription by topic and group */
  GET: (setupId: string, topic: string, groupName: string) =>
    `${API_V1_PREFIX}/setups/${setupId}/subscriptions/${topic}/${groupName}`,
  /** POST - Pause a subscription */
  PAUSE: (setupId: string, topic: string, groupName: string) =>
    `${API_V1_PREFIX}/setups/${setupId}/subscriptions/${topic}/${groupName}/pause`,
  /** POST - Resume a subscription */
  RESUME: (setupId: string, topic: string, groupName: string) =>
    `${API_V1_PREFIX}/setups/${setupId}/subscriptions/${topic}/${groupName}/resume`,
  /** POST - Send heartbeat for a subscription */
  HEARTBEAT: (setupId: string, topic: string, groupName: string) =>
    `${API_V1_PREFIX}/setups/${setupId}/subscriptions/${topic}/${groupName}/heartbeat`,
  /** DELETE - Cancel a subscription */
  CANCEL: (setupId: string, topic: string, groupName: string) =>
    `${API_V1_PREFIX}/setups/${setupId}/subscriptions/${topic}/${groupName}`,
} as const;

// ============================================================================
// Health Endpoints
// ============================================================================

export const HEALTH_ENDPOINTS = {
  /** GET - Get overall health status */
  OVERALL: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/health`,
  /** GET - Get all component health statuses */
  COMPONENTS: (setupId: string) => `${API_V1_PREFIX}/setups/${setupId}/health/components`,
  /** GET - Get health status for a specific component */
  COMPONENT: (setupId: string, componentName: string) =>
    `${API_V1_PREFIX}/setups/${setupId}/health/components/${componentName}`,
} as const;

// ============================================================================
// Event Store Endpoints
// ============================================================================

export const EVENT_STORE_ENDPOINTS = {
  /** GET - List event stores for a setup */
  LIST: (setupId: string) => `${API_V1_PREFIX}/eventstores/${setupId}`,
  /** GET - Get event store details */
  GET: (setupId: string, storeName: string) => `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}`,
  /** POST - Append event to store */
  APPEND: (setupId: string, storeName: string) => `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}/events`,
  /** POST - Query events */
  QUERY: (setupId: string, storeName: string) => `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}/query`,
  /** GET - Get event by ID */
  GET_EVENT: (setupId: string, storeName: string, eventId: string) =>
    `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}/events/${eventId}`,
  /** GET - Get all versions of an event */
  VERSIONS: (setupId: string, storeName: string, eventId: string) =>
    `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}/events/${eventId}/versions`,
  /** POST - Create a correction for an event */
  CORRECT: (setupId: string, storeName: string, eventId: string) =>
    `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}/events/${eventId}/correct`,
  /** GET - Stream events via SSE */
  STREAM: (setupId: string, storeName: string) => `${API_V1_PREFIX}/eventstores/${setupId}/${storeName}/stream`,
} as const;

// ============================================================================
// Consumer Group Endpoints
// ============================================================================

export const CONSUMER_GROUP_ENDPOINTS = {
  /** GET - List consumer groups for a queue */
  LIST: (setupId: string, queueName: string) =>
    `${API_V1_PREFIX}/queues/${setupId}/${queueName}/consumer-groups`,
  /** GET - Get consumer group details */
  GET: (setupId: string, queueName: string, groupName: string) =>
    `${API_V1_PREFIX}/queues/${setupId}/${queueName}/consumer-groups/${groupName}`,
  /** GET - Get consumer group members */
  MEMBERS: (setupId: string, queueName: string, groupName: string) =>
    `${API_V1_PREFIX}/queues/${setupId}/${queueName}/consumer-groups/${groupName}/members`,
  /** GET - Get consumer group statistics */
  STATS: (setupId: string, queueName: string, groupName: string) =>
    `${API_V1_PREFIX}/queues/${setupId}/${queueName}/consumer-groups/${groupName}/stats`,
} as const;

// ============================================================================
// Management API Endpoints
// ============================================================================

export const MANAGEMENT_ENDPOINTS = {
  /** GET - List all queues across all setups */
  QUEUES: `${API_V1_PREFIX}/management/queues`,
  /** GET - Get queue details */
  QUEUE_DETAILS: (setupId: string, queueName: string) =>
    `${API_V1_PREFIX}/management/queues/${setupId}/${queueName}`,
  /** GET - Get system metrics */
  METRICS: `${API_V1_PREFIX}/management/metrics`,
  /** GET - Get system info */
  INFO: `${API_V1_PREFIX}/management/info`,
} as const;

// ============================================================================
// SSE Streaming Endpoints
// ============================================================================

export const SSE_ENDPOINTS = {
  /** GET - Stream queue updates */
  QUEUE_UPDATES: (setupId: string, queueName: string) =>
    `${API_V1_PREFIX}/sse/queues/${setupId}/${queueName}`,
  /** GET - Stream system metrics */
  SYSTEM_METRICS: `${API_V1_PREFIX}/sse/metrics`,
  /** GET - Stream all queue updates */
  ALL_QUEUES: `${API_V1_PREFIX}/sse/queues`,
} as const;

