# Phase 4: Real-time Streaming, Consumer Groups & Event Store Enhancement

#### © Mark Andrew Ray-Smith Cityline Ltd 2025

## Overview

Phase 4 completes the transformation of PeeGeeQ into an enterprise-grade messaging platform by adding real-time streaming capabilities, advanced consumer group management, and comprehensive event store querying. This phase elevates PeeGeeQ to compete with commercial messaging solutions like Apache Kafka, RabbitMQ, and AWS EventBridge.

## 🎯 Phase 4 Features Implemented

### 🌐 1. WebSocket Real-time Message Streaming

#### **WebSocket Connection**
```
ws://localhost:8080/ws/queues/{setupId}/{queueName}
```

**Features:**
- **Bidirectional Communication** - Full duplex messaging between client and server
- **Connection Management** - Automatic connection lifecycle with heartbeat
- **Message Filtering** - Server-side filtering by type, headers, and content
- **Subscription Management** - Dynamic subscription configuration
- **Consumer Group Integration** - Coordinate with consumer groups for load balancing

#### **Client Messages (to server):**
```json
// Ping for keep-alive
{"type": "ping", "id": "ping-123"}

// Subscribe with filters
{
  "type": "subscribe",
  "consumerGroup": "my-group",
  "filters": {
    "messageType": "OrderCreated",
    "headers": {"region": "US-WEST"},
    "payloadContains": "urgent"
  }
}

// Configure connection
{
  "type": "configure",
  "batchSize": 10,
  "maxWaitTime": 30000
}

// Unsubscribe
{"type": "unsubscribe"}
```

#### **Server Messages (to client):**
```json
// Welcome message
{
  "type": "welcome",
  "connectionId": "ws-123",
  "setupId": "my-setup",
  "queueName": "orders",
  "timestamp": 1752929815000,
  "message": "Connected to PeeGeeQ WebSocket stream"
}

// Data message
{
  "type": "data",
  "connectionId": "ws-123",
  "messageId": "msg-456",
  "payload": {"orderId": "12345", "amount": 99.99},
  "messageType": "OrderCreated",
  "headers": {"source": "order-service"},
  "timestamp": 1752929815000
}

// Batch message
{
  "type": "batch",
  "connectionId": "ws-123",
  "messageCount": 3,
  "messages": [...],
  "timestamp": 1752929815000
}

// Heartbeat
{
  "type": "heartbeat",
  "connectionId": "ws-123",
  "messagesReceived": 150,
  "messagesSent": 25,
  "timestamp": 1752929815000
}
```

### 📡 2. Server-Sent Events (SSE) Streaming

#### **SSE Connection**
```http
GET /api/v1/queues/{setupId}/{queueName}/stream
```

**Query Parameters:**
- `consumerGroup` - Consumer group name
- `batchSize` - Messages per batch (1-100)
- `maxWait` - Maximum wait time in milliseconds (1s-60s)
- `messageType` - Filter by message type
- `header.{key}` - Filter by header values

#### **SSE Event Types:**
- `connection` - Initial connection established
- `configured` - Configuration confirmation
- `message` - Individual queue message
- `batch` - Multiple messages
- `heartbeat` - Connection keep-alive
- `error` - Error notifications

#### **Example Usage (JavaScript):**
```javascript
const eventSource = new EventSource(
  '/api/v1/queues/my-setup/orders/stream?messageType=OrderCreated&batchSize=5'
);

eventSource.addEventListener('message', (event) => {
  const data = JSON.parse(event.data);
  console.log('Received message:', data);
});

eventSource.addEventListener('error', (event) => {
  console.error('SSE error:', event);
});
```

### 👥 3. Consumer Group Management

#### **Consumer Group Operations**

**Create Consumer Group:**
```http
POST /api/v1/queues/{setupId}/{queueName}/consumer-groups
```
```json
{
  "groupName": "order-processors",
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000
}
```

**List Consumer Groups:**
```http
GET /api/v1/queues/{setupId}/{queueName}/consumer-groups
```

**Get Consumer Group Details:**
```http
GET /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}
```

**Delete Consumer Group:**
```http
DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}
```

#### **Member Operations**

**Join Consumer Group:**
```http
POST /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members
```
```json
{
  "memberName": "consumer-instance-1"
}
```

**Leave Consumer Group:**
```http
DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members/{memberId}
```

#### **Load Balancing Strategies**
- **ROUND_ROBIN** - Distribute partitions evenly in round-robin fashion
- **RANGE** - Assign contiguous ranges of partitions to members
- **STICKY** - Minimize partition reassignment during rebalancing
- **RANDOM** - Randomly distribute partitions to members

#### **Consumer Group Response Example:**
```json
{
  "message": "Consumer group retrieved successfully",
  "groupName": "order-processors",
  "groupId": "group-uuid-123",
  "setupId": "my-setup",
  "queueName": "orders",
  "memberCount": 3,
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000,
  "members": [
    {
      "memberId": "member-1",
      "memberName": "consumer-1",
      "joinedAt": 1752929815000,
      "lastHeartbeat": 1752929820000,
      "assignedPartitions": [0, 1, 2, 3],
      "status": "ACTIVE"
    }
  ],
  "timestamp": 1752929815000
}
```

### 🗃️ 4. Enhanced Event Store Querying

#### **Advanced Event Querying**
```http
GET /api/v1/eventstores/{setupId}/{eventStoreName}/events
```

**Query Parameters:**
- `eventType` - Filter by event type
- `fromTime` - Start time (ISO 8601 format)
- `toTime` - End time (ISO 8601 format)
- `limit` - Maximum events to return (1-1000, default: 100)
- `offset` - Number of events to skip (default: 0)
- `correlationId` - Filter by correlation ID
- `causationId` - Filter by causation ID

#### **Single Event Retrieval**
```http
GET /api/v1/eventstores/{setupId}/{eventStoreName}/events/{eventId}
```

#### **Event Store Statistics**
```http
GET /api/v1/eventstores/{setupId}/{eventStoreName}/stats
```

#### **Event Query Response Example:**
```json
{
  "message": "Events retrieved successfully",
  "eventStoreName": "order-events",
  "setupId": "my-setup",
  "eventCount": 2,
  "limit": 100,
  "offset": 0,
  "hasMore": false,
  "filters": {
    "eventType": "OrderCreated",
    "fromTime": "2025-07-19T00:00:00Z",
    "toTime": "2025-07-19T23:59:59Z"
  },
  "events": [
    {
      "id": "event-123",
      "eventType": "OrderCreated",
      "eventData": {
        "orderId": "ORD-12345",
        "customerId": "CUST-67890",
        "amount": 199.99
      },
      "validFrom": "2025-07-19T10:00:00Z",
      "validTo": null,
      "transactionTime": "2025-07-19T10:01:00Z",
      "correlationId": "corr-123",
      "causationId": "cause-456",
      "version": 1,
      "metadata": {
        "source": "order-service",
        "region": "US-WEST"
      }
    }
  ],
  "timestamp": 1752929815000
}
```

#### **Bi-Temporal Support**
- **validFrom/validTo** - Business time (when events actually happened)
- **transactionTime** - System time (when events were recorded)
- **version** - Event version for corrections and updates

## 🏗️ Implementation Architecture

### **Handler Classes**
- **WebSocketHandler** - WebSocket connection and message management
- **WebSocketConnection** - Individual WebSocket connection state
- **ServerSentEventsHandler** - SSE streaming management
- **SSEConnection** - Individual SSE connection state
- **ConsumerGroupHandler** - Consumer group lifecycle management
- **ConsumerGroup** - Consumer group state and partition management
- **ConsumerGroupMember** - Individual member state and assignments
- **EventStoreHandler** (Enhanced) - Advanced event querying and statistics

### **Route Registration**
```java
// WebSocket (handled at server level)
server.webSocketHandler(webSocket -> {
    if (webSocket.path().startsWith("/ws/queues/")) {
        webSocketHandler.handleQueueStream(webSocket);
    }
});

// SSE
router.get("/api/v1/queues/:setupId/:queueName/stream")
      .handler(sseHandler::handleQueueStream);

// Consumer Groups
router.post("/api/v1/queues/:setupId/:queueName/consumer-groups")
      .handler(consumerGroupHandler::createConsumerGroup);
router.get("/api/v1/queues/:setupId/:queueName/consumer-groups")
      .handler(consumerGroupHandler::listConsumerGroups);
// ... additional consumer group routes

// Enhanced Event Store
router.get("/api/v1/eventstores/:setupId/:eventStoreName/events")
      .handler(eventStoreHandler::queryEvents);
router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId")
      .handler(eventStoreHandler::getEvent);
```

## 🧪 Comprehensive Testing

### **Test Coverage**
- ✅ **WebSocketHandlerTest** - WebSocket functionality and message structures
- ✅ **ServerSentEventsHandlerTest** - SSE streaming and event formats
- ✅ **ConsumerGroupHandlerTest** - Consumer group management and load balancing
- ✅ **EventStoreEnhancementTest** - Advanced event querying and bi-temporal support
- ✅ **Phase4IntegrationTest** - End-to-end feature integration and interoperability

### **Integration Scenarios Tested**
- ✅ WebSocket real-time message streaming with filtering
- ✅ SSE streaming with query parameter configuration
- ✅ Consumer group creation, membership, and partition rebalancing
- ✅ Advanced event store querying with time-range and correlation filters
- ✅ Feature interoperability (consumer groups + WebSocket + event store)

## 🚀 Production Usage Examples

### **Node.js WebSocket Consumer**
```javascript
const WebSocket = require('ws');

const ws = new WebSocket('ws://localhost:8080/ws/queues/my-setup/orders');

ws.on('open', () => {
  // Subscribe with filters
  ws.send(JSON.stringify({
    type: 'subscribe',
    consumerGroup: 'order-processors',
    filters: {
      messageType: 'OrderCreated',
      headers: { priority: 'HIGH' }
    }
  }));
});

ws.on('message', (data) => {
  const message = JSON.parse(data);
  if (message.type === 'data') {
    console.log('Processing order:', message.payload);
    // Process the order...
  }
});
```

### **Python SSE Consumer**
```python
import requests
import json

def consume_events():
    url = "http://localhost:8080/api/v1/queues/my-setup/orders/stream"
    params = {
        'messageType': 'OrderCreated',
        'batchSize': 10,
        'consumerGroup': 'python-consumers'
    }
    
    response = requests.get(url, params=params, stream=True)
    
    for line in response.iter_lines():
        if line.startswith(b'data: '):
            data = json.loads(line[6:])  # Remove 'data: ' prefix
            if data.get('type') == 'data':
                print(f"Processing: {data['payload']}")
```

### **Consumer Group Management Script**
```bash
#!/bin/bash

SETUP_ID="my-setup"
QUEUE_NAME="orders"
GROUP_NAME="batch-processors"
BASE_URL="http://localhost:8080/api/v1/queues/$SETUP_ID/$QUEUE_NAME/consumer-groups"

# Create consumer group
curl -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "groupName": "'$GROUP_NAME'",
    "maxMembers": 5,
    "loadBalancingStrategy": "ROUND_ROBIN",
    "sessionTimeout": 30000
  }'

# Join consumer group
MEMBER_RESPONSE=$(curl -X POST "$BASE_URL/$GROUP_NAME/members" \
  -H "Content-Type: application/json" \
  -d '{"memberName": "batch-processor-1"}')

MEMBER_ID=$(echo $MEMBER_RESPONSE | jq -r '.memberId')
echo "Joined as member: $MEMBER_ID"

# Get group details
curl -X GET "$BASE_URL/$GROUP_NAME"
```

---

## 🏆 Phase 4 Success Summary

**Phase 4 successfully transforms PeeGeeQ into a complete, enterprise-grade messaging platform:**

### ✅ **Real-time Capabilities**
- **WebSocket Streaming** - Bidirectional real-time communication
- **Server-Sent Events** - HTTP-based streaming for web applications
- **Message Filtering** - Server-side filtering for efficient bandwidth usage
- **Connection Management** - Robust connection lifecycle with heartbeat

### ✅ **Advanced Consumer Management**
- **Consumer Groups** - Scalable consumer coordination
- **Load Balancing** - Multiple partition assignment strategies
- **Automatic Rebalancing** - Dynamic partition redistribution
- **Session Management** - Heartbeat-based member health monitoring

### ✅ **Enhanced Event Store**
- **Advanced Querying** - Time-range, type, and correlation filtering
- **Bi-temporal Support** - Business time and transaction time tracking
- **Pagination** - Efficient large dataset handling
- **Comprehensive Statistics** - Detailed event store metrics

### ✅ **Enterprise Features**
- **Production Ready** - Comprehensive error handling and validation
- **Scalable Architecture** - Designed for high-throughput scenarios
- **Feature Interoperability** - All components work seamlessly together
- **Extensive Testing** - Complete test coverage with integration scenarios

**PeeGeeQ now provides a complete messaging platform that rivals commercial solutions like Apache Kafka, RabbitMQ, and AWS EventBridge, with the added benefits of HTTP-based APIs and comprehensive real-time streaming capabilities!** 🎉
