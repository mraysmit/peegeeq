/**
 * PeeGeeQ REST API Client
 * Main client class for interacting with the PeeGeeQ REST API
 */

import {
  API_BASE_URL,
  SETUP_ENDPOINTS,
  DEAD_LETTER_ENDPOINTS,
  SUBSCRIPTION_ENDPOINTS,
  HEALTH_ENDPOINTS,
  EVENT_STORE_ENDPOINTS,
  CONSUMER_GROUP_ENDPOINTS,
} from './endpoints';

import type {
  DatabaseSetupRequest,
  DatabaseSetupResult,
  DeadLetterMessageInfo,
  DeadLetterStatsInfo,
  DeadLetterListResponse,
  SubscriptionInfo,
  SubscriptionListResponse,
  OverallHealthInfo,
  HealthStatusInfo,
  BiTemporalEvent,
  EventQuery,
  EventQueryResult,
  AppendEventRequest,
  CorrectionRequest,
  ApiError,
  ConsumerGroupInfo,
  ConsumerGroupMemberInfo,
  ConsumerGroupStats,
} from './types';

// ============================================================================
// Client Configuration
// ============================================================================

export interface PeeGeeQClientConfig {
  baseUrl?: string;
  timeout?: number;
  retryAttempts?: number;
  retryDelayMs?: number;
  headers?: Record<string, string>;
}

const DEFAULT_CONFIG: Required<PeeGeeQClientConfig> = {
  baseUrl: API_BASE_URL,
  timeout: 30000,
  retryAttempts: 3,
  retryDelayMs: 1000,
  headers: {
    'Content-Type': 'application/json',
  },
};

// ============================================================================
// Error Classes
// ============================================================================

export class PeeGeeQApiError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly apiError: ApiError,
    public readonly response?: Response
  ) {
    super(apiError.message || `API Error: ${statusCode}`);
    this.name = 'PeeGeeQApiError';
  }
}

export class PeeGeeQNetworkError extends Error {
  constructor(
    message: string,
    public readonly cause?: Error
  ) {
    super(message);
    this.name = 'PeeGeeQNetworkError';
  }
}

// ============================================================================
// Main Client Class
// ============================================================================

export class PeeGeeQClient {
  private readonly config: Required<PeeGeeQClientConfig>;

  constructor(config: PeeGeeQClientConfig = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  // --------------------------------------------------------------------------
  // HTTP Helper Methods
  // --------------------------------------------------------------------------

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    queryParams?: Record<string, string | number | boolean | undefined>
  ): Promise<T> {
    const url = new URL(path, this.config.baseUrl);

    if (queryParams) {
      Object.entries(queryParams).forEach(([key, value]) => {
        if (value !== undefined) {
          url.searchParams.append(key, String(value));
        }
      });
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this.config.timeout);

    try {
      const response = await this.executeWithRetry(async () => {
        return fetch(url.toString(), {
          method,
          headers: this.config.headers,
          body: body ? JSON.stringify(body) : undefined,
          signal: controller.signal,
        });
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        const errorBody = await response.json().catch(() => ({
          error: 'Unknown Error',
          message: response.statusText,
          statusCode: response.status,
          timestamp: new Date().toISOString(),
        }));
        throw new PeeGeeQApiError(response.status, errorBody as ApiError, response);
      }

      // Handle 204 No Content
      if (response.status === 204) {
        return undefined as T;
      }

      return response.json() as Promise<T>;
    } catch (error) {
      clearTimeout(timeoutId);
      if (error instanceof PeeGeeQApiError) {
        throw error;
      }
      if (error instanceof Error && error.name === 'AbortError') {
        throw new PeeGeeQNetworkError('Request timeout', error);
      }
      throw new PeeGeeQNetworkError('Network error', error instanceof Error ? error : undefined);
    }
  }

  private async executeWithRetry(fn: () => Promise<Response>): Promise<Response> {
    let lastError: Error | undefined;

    for (let attempt = 0; attempt < this.config.retryAttempts; attempt++) {
      try {
        const response = await fn();
        // Don't retry on client errors (4xx)
        if (response.status >= 400 && response.status < 500) {
          return response;
        }
        // Retry on server errors (5xx)
        if (response.status >= 500) {
          lastError = new Error(`Server error: ${response.status}`);
          await this.delay(this.config.retryDelayMs * Math.pow(2, attempt));
          continue;
        }
        return response;
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));
        if (attempt < this.config.retryAttempts - 1) {
          await this.delay(this.config.retryDelayMs * Math.pow(2, attempt));
        }
      }
    }

    throw lastError || new Error('Request failed after retries');
  }

  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  // --------------------------------------------------------------------------
  // Setup Operations
  // --------------------------------------------------------------------------

  async createSetup(request: DatabaseSetupRequest): Promise<DatabaseSetupResult> {
    return this.request<DatabaseSetupResult>('POST', SETUP_ENDPOINTS.CREATE, request);
  }

  async listSetups(): Promise<DatabaseSetupResult[]> {
    return this.request<DatabaseSetupResult[]>('GET', SETUP_ENDPOINTS.LIST);
  }

  async getSetup(setupId: string): Promise<DatabaseSetupResult> {
    return this.request<DatabaseSetupResult>('GET', SETUP_ENDPOINTS.GET(setupId));
  }

  async deleteSetup(setupId: string): Promise<void> {
    return this.request<void>('DELETE', SETUP_ENDPOINTS.DELETE(setupId));
  }

  // --------------------------------------------------------------------------
  // Dead Letter Operations
  // --------------------------------------------------------------------------

  async listDeadLetters(
    setupId: string,
    options?: { page?: number; pageSize?: number; topic?: string }
  ): Promise<DeadLetterListResponse> {
    return this.request<DeadLetterListResponse>('GET', DEAD_LETTER_ENDPOINTS.LIST(setupId), undefined, options);
  }

  async getDeadLetter(setupId: string, messageId: number): Promise<DeadLetterMessageInfo> {
    return this.request<DeadLetterMessageInfo>('GET', DEAD_LETTER_ENDPOINTS.GET(setupId, messageId));
  }

  async reprocessDeadLetter(setupId: string, messageId: number): Promise<void> {
    return this.request<void>('POST', DEAD_LETTER_ENDPOINTS.REPROCESS(setupId, messageId));
  }

  async deleteDeadLetter(setupId: string, messageId: number): Promise<void> {
    return this.request<void>('DELETE', DEAD_LETTER_ENDPOINTS.DELETE(setupId, messageId));
  }

  async cleanupDeadLetters(setupId: string, olderThanDays?: number): Promise<{ deletedCount: number }> {
    return this.request<{ deletedCount: number }>(
      'POST',
      DEAD_LETTER_ENDPOINTS.CLEANUP(setupId),
      undefined,
      olderThanDays ? { olderThanDays } : undefined
    );
  }

  async getDeadLetterStats(setupId: string): Promise<DeadLetterStatsInfo> {
    return this.request<DeadLetterStatsInfo>('GET', DEAD_LETTER_ENDPOINTS.STATS(setupId));
  }

  // --------------------------------------------------------------------------
  // Subscription Operations
  // --------------------------------------------------------------------------

  async listSubscriptions(setupId: string): Promise<SubscriptionListResponse> {
    return this.request<SubscriptionListResponse>('GET', SUBSCRIPTION_ENDPOINTS.LIST(setupId));
  }

  async getSubscription(setupId: string, topic: string, groupName: string): Promise<SubscriptionInfo> {
    return this.request<SubscriptionInfo>('GET', SUBSCRIPTION_ENDPOINTS.GET(setupId, topic, groupName));
  }

  async pauseSubscription(setupId: string, topic: string, groupName: string): Promise<SubscriptionInfo> {
    return this.request<SubscriptionInfo>('POST', SUBSCRIPTION_ENDPOINTS.PAUSE(setupId, topic, groupName));
  }

  async resumeSubscription(setupId: string, topic: string, groupName: string): Promise<SubscriptionInfo> {
    return this.request<SubscriptionInfo>('POST', SUBSCRIPTION_ENDPOINTS.RESUME(setupId, topic, groupName));
  }

  async sendHeartbeat(setupId: string, topic: string, groupName: string): Promise<void> {
    return this.request<void>('POST', SUBSCRIPTION_ENDPOINTS.HEARTBEAT(setupId, topic, groupName));
  }

  async cancelSubscription(setupId: string, topic: string, groupName: string): Promise<void> {
    return this.request<void>('DELETE', SUBSCRIPTION_ENDPOINTS.CANCEL(setupId, topic, groupName));
  }

  // --------------------------------------------------------------------------
  // Health Operations
  // --------------------------------------------------------------------------

  async getOverallHealth(setupId: string): Promise<OverallHealthInfo> {
    return this.request<OverallHealthInfo>('GET', HEALTH_ENDPOINTS.OVERALL(setupId));
  }

  async getComponentsHealth(setupId: string): Promise<Record<string, HealthStatusInfo>> {
    return this.request<Record<string, HealthStatusInfo>>('GET', HEALTH_ENDPOINTS.COMPONENTS(setupId));
  }

  async getComponentHealth(setupId: string, componentName: string): Promise<HealthStatusInfo> {
    return this.request<HealthStatusInfo>('GET', HEALTH_ENDPOINTS.COMPONENT(setupId, componentName));
  }

  // --------------------------------------------------------------------------
  // Event Store Operations
  // --------------------------------------------------------------------------

  async listEventStores(setupId: string): Promise<string[]> {
    return this.request<string[]>('GET', EVENT_STORE_ENDPOINTS.LIST(setupId));
  }

  async getEventStore(setupId: string, storeName: string): Promise<{ name: string; eventCount: number }> {
    return this.request<{ name: string; eventCount: number }>('GET', EVENT_STORE_ENDPOINTS.GET(setupId, storeName));
  }

  async appendEvent<T>(
    setupId: string,
    storeName: string,
    event: AppendEventRequest<T>
  ): Promise<BiTemporalEvent<T>> {
    return this.request<BiTemporalEvent<T>>('POST', EVENT_STORE_ENDPOINTS.APPEND(setupId, storeName), event);
  }

  async queryEvents<T>(setupId: string, storeName: string, query: EventQuery): Promise<EventQueryResult<T>> {
    return this.request<EventQueryResult<T>>('POST', EVENT_STORE_ENDPOINTS.QUERY(setupId, storeName), query);
  }

  async getEvent<T>(setupId: string, storeName: string, eventId: string): Promise<BiTemporalEvent<T>> {
    return this.request<BiTemporalEvent<T>>('GET', EVENT_STORE_ENDPOINTS.GET_EVENT(setupId, storeName, eventId));
  }

  async getEventVersions<T>(setupId: string, storeName: string, eventId: string): Promise<BiTemporalEvent<T>[]> {
    return this.request<BiTemporalEvent<T>[]>('GET', EVENT_STORE_ENDPOINTS.VERSIONS(setupId, storeName, eventId));
  }

  async correctEvent<T>(
    setupId: string,
    storeName: string,
    eventId: string,
    correction: CorrectionRequest<T>
  ): Promise<BiTemporalEvent<T>> {
    return this.request<BiTemporalEvent<T>>(
      'POST',
      EVENT_STORE_ENDPOINTS.CORRECT(setupId, storeName, eventId),
      correction
    );
  }

  // --------------------------------------------------------------------------
  // Consumer Group Operations
  // --------------------------------------------------------------------------

  async listConsumerGroups(setupId: string, queueName: string): Promise<ConsumerGroupInfo[]> {
    return this.request<ConsumerGroupInfo[]>('GET', CONSUMER_GROUP_ENDPOINTS.LIST(setupId, queueName));
  }

  async getConsumerGroup(setupId: string, queueName: string, groupName: string): Promise<ConsumerGroupInfo> {
    return this.request<ConsumerGroupInfo>('GET', CONSUMER_GROUP_ENDPOINTS.GET(setupId, queueName, groupName));
  }

  async getConsumerGroupMembers(
    setupId: string,
    queueName: string,
    groupName: string
  ): Promise<ConsumerGroupMemberInfo[]> {
    return this.request<ConsumerGroupMemberInfo[]>(
      'GET',
      CONSUMER_GROUP_ENDPOINTS.MEMBERS(setupId, queueName, groupName)
    );
  }

  async getConsumerGroupStats(setupId: string, queueName: string, groupName: string): Promise<ConsumerGroupStats> {
    return this.request<ConsumerGroupStats>('GET', CONSUMER_GROUP_ENDPOINTS.STATS(setupId, queueName, groupName));
  }

  // --------------------------------------------------------------------------
  // SSE Streaming
  // --------------------------------------------------------------------------

  streamEvents<T>(
    setupId: string,
    storeName: string,
    onEvent: (event: BiTemporalEvent<T>) => void,
    onError?: (error: Error) => void
  ): () => void {
    const url = `${this.config.baseUrl}${EVENT_STORE_ENDPOINTS.STREAM(setupId, storeName)}`;
    const eventSource = new EventSource(url);

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data) as BiTemporalEvent<T>;
        onEvent(data);
      } catch (error) {
        onError?.(error instanceof Error ? error : new Error(String(error)));
      }
    };

    eventSource.onerror = (event) => {
      onError?.(new Error('SSE connection error'));
    };

    // Return cleanup function
    return () => {
      eventSource.close();
    };
  }
}

// ============================================================================
// Default Client Instance
// ============================================================================

export const peeGeeQClient = new PeeGeeQClient();

