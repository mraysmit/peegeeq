# PeeGeeQ Smoke Test Coverage

This document provides a comprehensive overview of the smoke test suite for PeeGeeQ REST API endpoints.

## Overview

Smoke tests verify that the REST API endpoints are accessible and return expected responses. They test the complete flow from HTTP client through the REST API to the PostgreSQL database.

**Test Flow:** HTTP Client -> REST API -> Runtime -> PostgreSQL

## Test Categories

| Category | Test File | Tests | Description |
|----------|-----------|-------|-------------|
| Health & Metrics | HealthMetricsSmokeTest | 6 | Health endpoints and Prometheus metrics |
| Health Check | HealthCheckSmokeTest | 4 | Server health and management overview |
| System Overview | SystemOverviewSmokeTest | 5 | Management dashboard endpoints |
| Native Queue | NativeQueueSmokeTest | 3 | Queue creation and message operations |
| Consumer Groups | ConsumerGroupSmokeTest | 6 | Consumer group lifecycle |
| Dead Letter Queue | DeadLetterQueueSmokeTest | 6 | DLQ operations and statistics |
| BiTemporal Events | BiTemporalEventStoreSmokeTest | 3 | Event store operations |
| Subscriptions | SubscriptionLifecycleSmokeTest | 6 | Subscription lifecycle management |
| Webhooks | WebhookSubscriptionSmokeTest | 4 | Webhook subscription endpoints |

**Total: 43 smoke tests**

## API Endpoint Coverage

### Health & Monitoring

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/health` | GET | testSimpleHealthCheck | Covered |
| `/api/v1/health` | GET | testApiHealthCheck, testServerHealth | Covered |
| `/metrics` | GET | testPrometheusMetrics | Covered |
| `/api/v1/management/metrics` | GET | testManagementMetrics | Covered |
| `/api/v1/management/overview` | GET | testSystemOverview, testManagementOverview | Covered |
| `/api/v1/setups/:setupId/health` | GET | testPerSetupHealth, testSetupHealth | Covered |
| `/api/v1/setups/:setupId/health/components` | GET | testComponentHealthList | Covered |

### Management Dashboard

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/management/queues` | GET | testManagementQueues | Covered |
| `/api/v1/management/consumer-groups` | GET | testManagementConsumerGroups | Covered |
| `/api/v1/management/event-stores` | GET | testManagementEventStores | Covered |
| `/api/v1/management/messages` | GET | testManagementMessages | Covered |

### Database Setup

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/database-setup/create` | POST | testCreateDatabaseSetup, testCreateEventStoreSetup | Covered |
| `/api/v1/setups` | GET | testListSetups | Covered |
| `/api/v1/setups/:setupId` | DELETE | cleanup methods | Covered |

### Queue Operations

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/queues/:setupId/:queueName/messages` | POST | testSendMessage, testCorrelationIdPropagation | Covered |

### Consumer Groups

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/queues/:setupId/:queueName/consumer-groups` | POST | testCreateConsumerGroup | Covered |
| `/api/v1/queues/:setupId/:queueName/consumer-groups` | GET | testListConsumerGroups | Covered |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | GET | testGetConsumerGroup | Covered |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | DELETE | testDeleteConsumerGroup | Covered |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members` | POST | testJoinConsumerGroup | Covered |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId` | DELETE | testLeaveConsumerGroup | Covered |

### Dead Letter Queue

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/setups/:setupId/deadletter/messages` | GET | testListDeadLetterMessages | Covered |
| `/api/v1/setups/:setupId/deadletter/messages/:messageId` | GET | testGetDeadLetterMessage | Covered |
| `/api/v1/setups/:setupId/deadletter/messages/:messageId` | DELETE | testDeleteDeadLetterMessage | Covered |
| `/api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess` | POST | testReprocessDeadLetterMessage | Covered |
| `/api/v1/setups/:setupId/deadletter/stats` | GET | testGetDeadLetterStats | Covered |
| `/api/v1/setups/:setupId/deadletter/cleanup` | POST | testCleanupDeadLetterQueue | Covered |

### BiTemporal Event Store

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/eventstores/:setupId/:eventStoreName/events` | POST | testAppendEvent | Covered |
| `/api/v1/eventstores/:setupId/:eventStoreName/events` | GET | testQueryEventsByType | Covered |

### Subscriptions

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/setups/:setupId/subscriptions/:topic` | GET | testListSubscriptions | Covered |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | GET | testGetSubscription | Covered |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | DELETE | testCancelSubscription | Covered |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause` | POST | testPauseSubscription | Covered |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume` | POST | testResumeSubscription | Covered |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat` | POST | testUpdateHeartbeat | Covered |

### Webhook Subscriptions

| Endpoint | Method | Test | Status |
|----------|--------|------|--------|
| `/api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions` | POST | testCreateWebhookRequiresUrl, testCreateWebhookInvalidSetup | Covered |
| `/api/v1/webhook-subscriptions/:subscriptionId` | GET | testGetWebhookSubscriptionNotFound | Covered |
| `/api/v1/webhook-subscriptions/:subscriptionId` | DELETE | testDeleteWebhookSubscriptionNotFound | Covered |

## Running Smoke Tests

### Run All Smoke Tests

```bash
cd peegeeq-integration-tests
mvn test -Dgroups=smoke
```

### Run Specific Test Class

```bash
mvn test -Dtest=HealthMetricsSmokeTest
mvn test -Dtest=ConsumerGroupSmokeTest
```

### Run with Verbose Output

```bash
mvn test -Dgroups=smoke -Dsurefire.useFile=false
```

## Test Infrastructure

All smoke tests extend `SmokeTestBase` which provides:

- WebClient for HTTP requests
- PostgreSQL connection configuration
- Helper methods for creating database setups
- Cleanup utilities for test isolation
- Logging infrastructure

## Test Patterns

1. **Setup Creation**: Tests that require database resources first create a setup via `/api/v1/database-setup/create`
2. **Cleanup**: All tests clean up created resources in finally blocks or after assertions
3. **Error Handling**: Tests verify both success paths and error responses (404, 500)
4. **Correlation IDs**: Tests verify correlation ID propagation through the system

