/**
 * PeeGeeQ REST API Client
 * 
 * This module provides a TypeScript client for interacting with the PeeGeeQ REST API.
 * 
 * Usage:
 * ```typescript
 * import { peeGeeQClient, PeeGeeQClient } from './api';
 * 
 * // Use the default client instance
 * const setups = await peeGeeQClient.listSetups();
 * 
 * // Or create a custom client with configuration
 * const customClient = new PeeGeeQClient({
 *   baseUrl: 'http://custom-host:8080',
 *   timeout: 60000,
 *   retryAttempts: 5,
 * });
 * ```
 */

// Export the main client class and default instance
export { PeeGeeQClient, peeGeeQClient, PeeGeeQApiError, PeeGeeQNetworkError } from './PeeGeeQClient';
export type { PeeGeeQClientConfig } from './PeeGeeQClient';

// Export all types
export type {
  // Database Setup Types
  DatabaseSetupStatus,
  ConnectionPoolConfig,
  DatabaseConfig,
  QueueConfigDto,
  EventStoreConfigDto,
  DatabaseSetupRequest,
  DatabaseSetupResult,
  // Dead Letter Types
  DeadLetterMessageInfo,
  DeadLetterStatsInfo,
  DeadLetterListResponse,
  // Subscription Types
  SubscriptionState,
  SubscriptionInfo,
  SubscriptionListResponse,
  // Health Types
  ComponentHealthState,
  HealthStatusInfo,
  OverallHealthInfo,
  // Bi-Temporal Event Types
  EventQuerySortOrder,
  TemporalRange,
  EventQuery,
  BiTemporalEvent,
  EventQueryResult,
  AppendEventRequest,
  CorrectionRequest,
  // API Response Types
  ApiError,
  ApiResponse,
  // Consumer Group Types
  ConsumerGroupInfo,
  ConsumerGroupMemberInfo,
  ConsumerGroupStats,
} from './types';

// Export endpoint constants
export {
  API_BASE_URL,
  API_V1_PREFIX,
  SETUP_ENDPOINTS,
  DATABASE_SETUP_ENDPOINTS,
  QUEUE_ENDPOINTS,
  DEAD_LETTER_ENDPOINTS,
  SUBSCRIPTION_ENDPOINTS,
  HEALTH_ENDPOINTS,
  EVENT_STORE_ENDPOINTS,
  CONSUMER_GROUP_ENDPOINTS,
  MANAGEMENT_ENDPOINTS,
  SSE_ENDPOINTS,
} from './endpoints';

