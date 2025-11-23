# PeeGeeQ REST API Reference

**Version:** 1.0-SNAPSHOT  
**Base URL:** `http://localhost:8080`  
**Framework:** Vert.x 5.0.4

## Table of Contents

1. [Database Setup Endpoints](#database-setup-endpoints)
2. [Queue Management Endpoints](#queue-management-endpoints)
3. [Queue Message Operations](#queue-message-operations)
4. [Consumer Group Endpoints](#consumer-group-endpoints)
5. [Event Store Endpoints](#event-store-endpoints)
6. [Management API Endpoints](#management-api-endpoints)
7. [Health & Metrics Endpoints](#health--metrics-endpoints)
8. [Real-time Streaming Endpoints](#real-time-streaming-endpoints)

---

## Database Setup Endpoints

### Create Database Setup

Creates a complete database setup with queues and event stores.

**Endpoint:** `POST /api/v1/database-setup/create`  
**Handler:** `DatabaseSetupHandler.createSetup()`  
**Service:** `DatabaseSetupService.createCompleteSetup()`

**Request Body:**
```json
{
  "setupId": "string",
  "queues": [
    {
      "queueName": "string",
      "maxRetries": 3,
      "visibilityTimeoutSeconds": 30,
      "deadLetterEnabled": true
    }
  ],
  "eventStores": [
    {
      "eventStoreName": "string",
      "tableName": "string",
      "biTemporalEnabled": true,
      "notificationPrefix": "string"
    }
  ]
}
```

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED",
  "createdAt": "timestamp",
  "queueFactories": {},
  "eventStores": {}
}
```

---

### Destroy Database Setup

Destroys a database setup and cleans up resources.

**Endpoint:** `DELETE /api/v1/database-setup/:setupId`  
**Handler:** `DatabaseSetupHandler.destroySetup()`  
**Service:** `DatabaseSetupService.destroySetup()`

**Path Parameters:**
- `setupId` (string, required): The ID of the setup to destroy

**Response:** `204 No Content`

---

### Get Setup Status

Gets the status of a database setup.

**Endpoint:** `GET /api/v1/database-setup/:setupId/status`  
**Handler:** `DatabaseSetupHandler.getStatus()`  
**Service:** `DatabaseSetupService.getSetupStatus()`

**Path Parameters:**
- `setupId` (string, required): The ID of the setup

**Response:** `200 OK`
```json
{
  "setupId": "string",
  "status": "ACTIVE|CREATING|FAILED",
  "createdAt": "timestamp"
}
```

---

### Add Queue to Setup

Adds a new queue to an existing database setup.

**Endpoint:** `POST /api/v1/database-setup/:setupId/queues`  
**Handler:** `DatabaseSetupHandler.addQueue()`  
**Service:** `DatabaseSetupService.addQueue()`

**Path Parameters:**
- `setupId` (string, required): The ID of the setup

**Request Body:**
```json
{
  "queueName": "string",
  "maxRetries": 3,
  "visibilityTimeoutSeconds": 30,
  "deadLetterEnabled": true
}
```

**Response:** `201 Created`
```json
{
  "message": "Queue added successfully"
}
```

---

### Add Event Store to Setup

Adds a new event store to an existing database setup.

**Endpoint:** `POST /api/v1/database-setup/:setupId/eventstores`  
**Handler:** `DatabaseSetupHandler.addEventStore()`  
**Service:** `DatabaseSetupService.addEventStore()`

**Path Parameters:**
- `setupId` (string, required): The ID of the setup

**Request Body:**
```json
{
  "eventStoreName": "string",
  "tableName": "string",
  "biTemporalEnabled": true,
  "notificationPrefix": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Event store added successfully"
}
```

---

## Queue Management Endpoints

### Get Queue Details

Gets detailed information about a specific queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName`  
**Handler:** `ManagementApiHandler.getQueueDetails()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "name": "string",
  "setup": "string",
  "implementationType": "peegeeq-native|peegeeq-outbox",
  "status": "active|error",
  "messages": 0,
  "consumers": 0,
  "messageRate": 0.0,
  "consumerRate": 0.0,
  "durability": "durable",
  "autoDelete": false,
  "createdAt": "timestamp",
  "lastActivity": "timestamp"
}
```

---

### Get Queue Statistics

Gets statistics for a specific queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/stats`  
**Handler:** `QueueHandler.getQueueStats()`  
**Service:** `DatabaseSetupService.getSetupStatus()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "queueName": "string",
  "totalMessages": 0,
  "pendingMessages": 0,
  "processedMessages": 0
}
```

---

### Get Queue Consumers

Gets list of active consumers for a queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/consumers`  
**Handler:** `ManagementApiHandler.getQueueConsumers()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Consumers retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "consumerCount": 0,
  "consumers": [],
  "timestamp": 0
}
```

---

### Get Queue Bindings

Gets bindings for a specific queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/bindings`  
**Handler:** `ManagementApiHandler.getQueueBindings()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Bindings retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "bindingCount": 0,
  "bindings": [],
  "timestamp": 0
}
```

---

### Purge Queue

Purges all messages from a queue.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/purge`  
**Handler:** `ManagementApiHandler.purgeQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Queue purge initiated",
  "queueName": "string",
  "setupId": "string",
  "timestamp": 0
}
```

---

## Queue Message Operations

### Send Message

Sends a single message to a queue.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/messages`  
**Handler:** `QueueHandler.sendMessage()`  
**Service:** Uses `QueueFactory.createProducer()` and `MessageProducer.send()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Request Body:**
```json
{
  "payload": {},
  "headers": {
    "key": "value"
  },
  "priority": 5,
  "delaySeconds": 0,
  "messageType": "string"
}
```

**Response:** `200 OK`
```json
{
  "message": "Message sent successfully",
  "queueName": "string",
  "setupId": "string",
  "messageId": "string",
  "timestamp": 0,
  "messageType": "string",
  "priority": 5,
  "delaySeconds": 0,
  "customHeadersCount": 0
}
```

---

### Send Batch Messages

Sends multiple messages to a queue in a batch.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/messages/batch`  
**Handler:** `QueueHandler.sendMessages()`  
**Service:** Uses `QueueFactory.createProducer()` and `MessageProducer.send()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Request Body:**
```json
{
  "messages": [
    {
      "payload": {},
      "headers": {},
      "priority": 5,
      "delaySeconds": 0
    }
  ],
  "failOnError": true,
  "maxBatchSize": 100
}
```

**Response:** `200 OK` or `207 Multi-Status`
```json
{
  "message": "Batch messages processed",
  "queueName": "string",
  "setupId": "string",
  "totalMessages": 0,
  "successfulMessages": 0,
  "failedMessages": 0,
  "messageIds": []
}
```

---

### Get Next Message

Polls for the next available message from a queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/messages/next`  
**Handler:** `QueueHandler.getNextMessage()`  
**Service:** Uses `QueueFactory.createConsumer()` and internal polling

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Query Parameters:**
- `timeout` (number, optional): Timeout in milliseconds (default: 30000)
- `maxWait` (number, optional): Max wait time in milliseconds (default: 5000)
- `consumerGroup` (string, optional): Consumer group name

**Response:** `200 OK` or `204 No Content`
```json
{
  "message": "Message retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "messageId": "string",
  "payload": {},
  "headers": {},
  "timestamp": 0,
  "priority": 5,
  "messageType": "string"
}
```

---

### Get Multiple Messages

Polls for multiple messages from a queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/messages`  
**Handler:** `QueueHandler.getMessages()`  
**Service:** Uses `QueueFactory.createConsumer()` and internal polling

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Query Parameters:**
- `limit` (number, optional): Maximum number of messages (default: 10, max: 100)
- `timeout` (number, optional): Timeout in milliseconds (default: 5000)
- `consumerGroup` (string, optional): Consumer group name

**Response:** `200 OK`
```json
{
  "message": "Messages retrieved successfully",
  "queueName": "string",
  "setupId": "string",
  "messageCount": 0,
  "timestamp": 0,
  "messages": []
}
```

---

### Acknowledge Message

Acknowledges a message (marks it as processed).

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName/messages/:messageId`  
**Handler:** `QueueHandler.acknowledgeMessage()`  
**Service:** Uses `QueueFactory.createConsumer()` and internal acknowledgment

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `messageId` (string, required): The message ID to acknowledge

**Response:** `200 OK` or `404 Not Found`
```json
{
  "message": "Message acknowledged successfully",
  "queueName": "string",
  "setupId": "string",
  "messageId": "string",
  "timestamp": 0
}
```

---

## Consumer Group Endpoints

### Create Consumer Group

Creates a new consumer group for a queue.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/consumer-groups`  
**Handler:** `ConsumerGroupHandler.createConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Request Body:**
```json
{
  "groupName": "string",
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000
}
```

**Response:** `201 Created`
```json
{
  "message": "Consumer group created successfully",
  "groupName": "string",
  "setupId": "string",
  "queueName": "string",
  "groupId": "string",
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000,
  "timestamp": 0
}
```

---

### List Consumer Groups

Lists all consumer groups for a queue.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/consumer-groups`  
**Handler:** `ConsumerGroupHandler.listConsumerGroups()`  
**Service:** Internal consumer group registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Response:** `200 OK`
```json
{
  "message": "Consumer groups retrieved successfully",
  "setupId": "string",
  "queueName": "string",
  "groupCount": 0,
  "groups": [
    {
      "groupName": "string",
      "groupId": "string",
      "memberCount": 0,
      "maxMembers": 10,
      "loadBalancingStrategy": "ROUND_ROBIN",
      "sessionTimeout": 30000,
      "createdAt": 0,
      "lastActivity": 0
    }
  ],
  "timestamp": 0
}
```

---

### Get Consumer Group

Gets details of a specific consumer group.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName`  
**Handler:** `ConsumerGroupHandler.getConsumerGroup()`  
**Service:** Internal consumer group registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`
```json
{
  "message": "Consumer group retrieved successfully",
  "groupName": "string",
  "groupId": "string",
  "setupId": "string",
  "queueName": "string",
  "memberCount": 0,
  "maxMembers": 10,
  "loadBalancingStrategy": "ROUND_ROBIN",
  "sessionTimeout": 30000,
  "createdAt": 0,
  "lastActivity": 0,
  "members": [
    {
      "memberId": "string",
      "memberName": "string",
      "joinedAt": 0,
      "lastHeartbeat": 0,
      "assignedPartitions": 0,
      "status": "ACTIVE"
    }
  ],
  "timestamp": 0
}
```

---

### Join Consumer Group

Joins a consumer group as a new member.

**Endpoint:** `POST /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members`  
**Handler:** `ConsumerGroupHandler.joinConsumerGroup()`  
**Service:** Internal consumer group registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Request Body:**
```json
{
  "memberName": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Successfully joined consumer group",
  "groupName": "string",
  "memberId": "string",
  "memberName": "string",
  "assignedPartitions": 0,
  "memberCount": 0,
  "timestamp": 0
}
```

---

### Leave Consumer Group

Leaves a consumer group.

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId`  
**Handler:** `ConsumerGroupHandler.leaveConsumerGroup()`  
**Service:** Internal consumer group registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name
- `memberId` (string, required): The member ID

**Response:** `200 OK`
```json
{
  "message": "Successfully left consumer group",
  "groupName": "string",
  "memberId": "string",
  "memberCount": 0,
  "timestamp": 0
}
```

---

### Delete Consumer Group

Deletes a consumer group.

**Endpoint:** `DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName`  
**Handler:** `ConsumerGroupHandler.deleteConsumerGroup()`  
**Service:** Internal consumer group registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`
```json
{
  "message": "Consumer group deleted successfully",
  "groupName": "string",
  "setupId": "string",
  "queueName": "string",
  "timestamp": 0
}
```

---

## Consumer Group Subscription Options (Phase 3.2)

### Set Subscription Options

Sets subscription options for a consumer group, controlling how consumers start reading messages.

**Endpoint:** `POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`  
**Handler:** `ConsumerGroupHandler.updateSubscriptionOptions()`  
**Service:** Internal consumer group subscription registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Request Body:**
```json
{
  "startPosition": "FROM_NOW|FROM_BEGINNING|FROM_MESSAGE_ID|FROM_TIMESTAMP",
  "startFromMessageId": 12345,
  "startFromTimestamp": "2025-11-23T00:00:00Z",
  "heartbeatIntervalSeconds": 60,
  "heartbeatTimeoutSeconds": 300
}
```

**Field Descriptions:**
- `startPosition` (string, required): Where consumers should start reading messages
  - `FROM_NOW`: Start from next new message (default)
  - `FROM_BEGINNING`: Start from oldest available message (backfill scenario)
  - `FROM_MESSAGE_ID`: Start from specific message ID (requires `startFromMessageId`)
  - `FROM_TIMESTAMP`: Start from specific timestamp (requires `startFromTimestamp`)
- `startFromMessageId` (number, optional): Message ID to start from when `startPosition=FROM_MESSAGE_ID`
- `startFromTimestamp` (string, optional): ISO-8601 timestamp when `startPosition=FROM_TIMESTAMP`
- `heartbeatIntervalSeconds` (number, optional): Seconds between heartbeats (default: 60, range: 10-300)
- `heartbeatTimeoutSeconds` (number, optional): Seconds before consumer considered dead (default: 300, range: 60-3600)

**Validation Rules:**
- Consumer group MUST exist (returns 404 if not found)
- `heartbeatIntervalSeconds` must be less than `heartbeatTimeoutSeconds`
- If `startPosition=FROM_MESSAGE_ID`, `startFromMessageId` is required
- If `startPosition=FROM_TIMESTAMP`, `startFromTimestamp` is required and must be valid ISO-8601

**Response:** `200 OK`
```json
{
  "message": "Subscription options updated successfully",
  "setupId": "string",
  "queueName": "string",
  "groupName": "string",
  "subscriptionOptions": {
    "startPosition": "FROM_BEGINNING",
    "startFromMessageId": null,
    "startFromTimestamp": null,
    "heartbeatIntervalSeconds": 60,
    "heartbeatTimeoutSeconds": 300
  },
  "timestamp": 1732320000000
}
```

**Error Responses:**

`404 Not Found` - Consumer group doesn't exist:
```json
{
  "error": "Consumer group 'my-group' not found for queue 'my-queue' in setup 'my-setup'",
  "timestamp": 1732320000000
}
```

`400 Bad Request` - Invalid parameters:
```json
{
  "error": "startFromMessageId is required when startPosition=FROM_MESSAGE_ID",
  "timestamp": 1732320000000
}
```

**Use Cases:**

1. **Late-joining consumers (Backfill):**
```json
{
  "startPosition": "FROM_BEGINNING"
}
```
New consumers will receive all historical messages from the beginning.

2. **Replay from specific point:**
```json
{
  "startPosition": "FROM_MESSAGE_ID",
  "startFromMessageId": 98765
}
```
Consumers will start reading from message ID 98765 onwards.

3. **Time-based replay:**
```json
{
  "startPosition": "FROM_TIMESTAMP",
  "startFromTimestamp": "2025-11-22T12:00:00Z"
}
```
Consumers will start reading messages sent after noon on Nov 22, 2025.

4. **Custom heartbeat settings:**
```json
{
  "startPosition": "FROM_NOW",
  "heartbeatIntervalSeconds": 30,
  "heartbeatTimeoutSeconds": 180
}
```
More frequent heartbeats for faster failure detection.

---

### Get Subscription Options

Gets the current subscription options for a consumer group.

**Endpoint:** `GET /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`  
**Handler:** `ConsumerGroupHandler.getSubscriptionOptions()`  
**Service:** Internal consumer group subscription registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `200 OK`
```json
{
  "message": "Subscription options retrieved successfully",
  "setupId": "string",
  "queueName": "string",
  "groupName": "string",
  "subscriptionOptions": {
    "startPosition": "FROM_NOW",
    "startFromMessageId": null,
    "startFromTimestamp": null,
    "heartbeatIntervalSeconds": 60,
    "heartbeatTimeoutSeconds": 300
  },
  "timestamp": 1732320000000
}
```

**Behavior for Non-Existent Groups:**
- Returns `200 OK` with default options
- Does not return 404 error
- Default options are returned as if explicitly set

**Default Subscription Options:**
```json
{
  "startPosition": "FROM_NOW",
  "startFromMessageId": null,
  "startFromTimestamp": null,
  "heartbeatIntervalSeconds": 60,
  "heartbeatTimeoutSeconds": 300
}
```

---

### Delete Subscription Options

Deletes subscription options for a consumer group, reverting to defaults.

**Endpoint:** `DELETE /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`  
**Handler:** `ConsumerGroupHandler.deleteSubscriptionOptions()`  
**Service:** Internal consumer group subscription registry

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name
- `groupName` (string, required): The consumer group name

**Response:** `204 No Content`

No response body. Subscription options are deleted, and subsequent consumers will use default options.

**Note:** Deleting subscription options does NOT affect currently active consumers. Only new consumers joining the group will use default options.

---

### SSE Streaming with Consumer Group

Streams messages via Server-Sent Events using consumer group subscription options.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/stream?consumerGroup={groupName}`  
**Handler:** `ServerSentEventsHandler.handleQueueStream()`  
**Service:** Uses `ConsumerGroupHandler.getSubscriptionOptionsInternal()` for configuration

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Query Parameters:**
- `consumerGroup` (string, optional): Consumer group name
- `batchSize` (number, optional): Messages per batch (default: 10, max: 100)
- `maxWaitTime` (number, optional): Max wait time in milliseconds (default: 5000)
- `messageType` (string, optional): Filter by message type
- `filterHeaders` (string, optional): Comma-separated header filters (e.g., "key1:value1,key2:value2")

**Response:** `200 OK` (Content-Type: text/event-stream)

**Event Types:**

1. **Connection Event** (sent immediately):
```
event: connection
data: {"connectionId":"sse-1","setupId":"my-setup","queueName":"my-queue","consumerGroup":"my-group","batchSize":10,"maxWaitTime":5000,"filters":{},"timestamp":1732320000000}
```

2. **Configured Event** (sent after applying subscription options):
```
event: configured
data: {"startPosition":"FROM_BEGINNING","heartbeatIntervalSeconds":60,"heartbeatTimeoutSeconds":300,"consumerGroup":"my-group","timestamp":1732320000001}
```

3. **Message Event**:
```
event: message
data: {"messageId":"msg-123","payload":{"key":"value"},"headers":{"content-type":"application/json"},"timestamp":1732320000002}
```

4. **Heartbeat Event** (sent every heartbeatIntervalSeconds):
```
event: heartbeat
data: {"timestamp":1732320060000}
```

5. **Error Event**:
```
event: error
data: {"error":"Failed to fetch messages","timestamp":1732320000003}
```

6. **End Event** (connection closed):
```
event: end
data: {"reason":"Connection closed by client","timestamp":1732320000004}
```

**Subscription Options Application:**

When `consumerGroup` parameter is provided:
1. Handler retrieves subscription options via `getSubscriptionOptionsInternal()`
2. If consumer group exists, configured options are used
3. If consumer group doesn't exist:
   - Logs warning: "Consumer group '{groupName}' not found, using default subscription options"
   - Falls back to default options (FROM_NOW, 60s heartbeat)
   - Connection succeeds (no 404 error)
4. Subscription options are applied to the message consumer
5. `configured` event is sent with applied options

**Example Usage:**

```bash
# Stream with consumer group (uses FROM_BEGINNING if configured)
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=my-group"

# Stream without consumer group (uses defaults)
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream"

# Stream with filters
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=my-group&messageType=order.created&filterHeaders=region:us-east"
```

**Client Implementation Example:**

```javascript
const evtSource = new EventSource(
  'http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=my-group'
);

evtSource.addEventListener('connection', (e) => {
  const data = JSON.parse(e.data);
  console.log('Connected:', data.connectionId);
  console.log('Consumer group:', data.consumerGroup);
});

evtSource.addEventListener('configured', (e) => {
  const data = JSON.parse(e.data);
  console.log('Start position:', data.startPosition);
  console.log('Heartbeat interval:', data.heartbeatIntervalSeconds);
});

evtSource.addEventListener('message', (e) => {
  const data = JSON.parse(e.data);
  console.log('Received message:', data.messageId, data.payload);
  // Process message...
});

evtSource.addEventListener('heartbeat', (e) => {
  const data = JSON.parse(e.data);
  console.log('Heartbeat at:', new Date(data.timestamp));
});

evtSource.addEventListener('error', (e) => {
  const data = JSON.parse(e.data);
  console.error('Error:', data.error);
});

// Close connection when done
// evtSource.close();
```

---

### Consumer Group Subscription Options Workflow

**Complete Workflow Example:**

```bash
# 1. Create consumer group
curl -X POST http://localhost:8080/api/v1/queues/my-setup/my-queue/consumer-groups \
  -H "Content-Type: application/json" \
  -d '{
    "groupName": "analytics-team",
    "maxMembers": 5
  }'

# 2. Set subscription options for backfill
curl -X POST http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription \
  -H "Content-Type: application/json" \
  -d '{
    "startPosition": "FROM_BEGINNING",
    "heartbeatIntervalSeconds": 45
  }'

# 3. Connect via SSE with consumer group
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/v1/queues/my-setup/my-queue/stream?consumerGroup=analytics-team"

# Consumer will receive:
# - connection event with groupName="analytics-team"
# - configured event with startPosition="FROM_BEGINNING", heartbeatIntervalSeconds=45
# - All historical messages starting from the beginning
# - Heartbeats every 45 seconds

# 4. Update subscription options (for next consumers)
curl -X POST http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription \
  -H "Content-Type: application/json" \
  -d '{
    "startPosition": "FROM_NOW",
    "heartbeatIntervalSeconds": 60
  }'

# 5. Get current subscription options
curl http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription

# 6. Delete subscription options (revert to defaults)
curl -X DELETE http://localhost:8080/api/v1/consumer-groups/my-setup/my-queue/analytics-team/subscription

# 7. Delete consumer group
curl -X DELETE http://localhost:8080/api/v1/queues/my-setup/my-queue/consumer-groups/analytics-team
```

---

### Subscription Options Best Practices

**1. Choose the Right Start Position:**

- **FROM_NOW** (default): Production consumers that only need new messages
- **FROM_BEGINNING**: Analytics, reporting, data reprocessing, backfill scenarios
- **FROM_MESSAGE_ID**: Resume processing after known last processed message
- **FROM_TIMESTAMP**: Replay messages from specific time (incident recovery, time-based replay)

**2. Configure Heartbeats Based on Requirements:**

- **Low latency failure detection**: 30s interval, 180s timeout
- **Standard (default)**: 60s interval, 300s timeout
- **Low network overhead**: 120s interval, 600s timeout

**3. Set Subscription Options BEFORE Consumers Connect:**

```bash
# ✅ CORRECT ORDER
POST /api/v1/queues/{setupId}/{queueName}/consumer-groups  # Create group
POST /api/v1/consumer-groups/{setupId}/{queueName}/{groupName}/subscription  # Configure
GET  /api/v1/queues/{setupId}/{queueName}/stream?consumerGroup={groupName}  # Connect

# ❌ WRONG ORDER - Consumer will use defaults first
GET  /api/v1/queues/{setupId}/{queueName}/stream?consumerGroup={groupName}  # Connect
POST /api/v1/consumer-groups/{setupId}/{queueName}/{groupName}/subscription  # Too late!
```

**4. Validation Considerations:**

- Always validate consumer group exists before setting subscription options
- 404 error means the group hasn't been created yet
- GET subscription options returns defaults for non-existent groups (no validation)

**5. Impact on Active Consumers:**

- Changing subscription options does NOT affect currently connected consumers
- Only new consumers joining the group use updated options
- To apply new options to existing consumers: disconnect and reconnect

---

### Integration Testing

See `ConsumerGroupSubscriptionIntegrationTest.java` for comprehensive test coverage:

1. **testSetSubscriptionOptionsWithoutConsumerGroup** - Validates 404 error for non-existent groups
2. **testSSEWithNonExistentConsumerGroup** - Validates graceful fallback to defaults
3. **testCompleteWorkflow** - Tests create group → set options → connect SSE → verify options applied
4. **testGetSubscriptionOptionsForNonExistentGroup** - Validates GET returns defaults without 404
5. **testDeleteSubscriptionOptions** - Tests delete operation
6. **testSSEWithoutConsumerGroupUsesDefaults** - Tests SSE without consumer group parameter

All tests use TestContainers with PostgreSQL for realistic integration testing.

---

## Event Store Endpoints

### Store Event

Stores a new event in a bi-temporal event store.

**Endpoint:** `POST /api/v1/eventstores/:setupId/:eventStoreName/events`  
**Handler:** `EventStoreHandler.storeEvent()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Request Body:**
```json
{
  "eventType": "string",
  "eventData": {},
  "validFrom": "ISO-8601 timestamp",
  "validTo": "ISO-8601 timestamp",
  "correlationId": "string",
  "causationId": "string",
  "metadata": {}
}
```

**Response:** `200 OK`
```json
{
  "message": "Event stored successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "transactionTime": "ISO-8601 timestamp"
}
```

---

### Query Events

Queries events by type and time range.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events`  
**Handler:** `EventStoreHandler.queryEvents()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Query Parameters:**
- `eventType` (string, optional): Filter by event type
- `fromTime` (string, optional): ISO-8601 timestamp for start of time range
- `toTime` (string, optional): ISO-8601 timestamp for end of time range
- `limit` (number, optional): Maximum number of events (default: 100, max: 1000)
- `offset` (number, optional): Offset for pagination (default: 0)
- `correlationId` (string, optional): Filter by correlation ID
- `causationId` (string, optional): Filter by causation ID

**Response:** `200 OK`
```json
{
  "message": "Events retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventCount": 0,
  "limit": 100,
  "offset": 0,
  "hasMore": false,
  "filters": {},
  "events": [
    {
      "id": "string",
      "eventType": "string",
      "eventData": {},
      "validFrom": "ISO-8601 timestamp",
      "validTo": "ISO-8601 timestamp",
      "transactionTime": "ISO-8601 timestamp",
      "correlationId": "string",
      "causationId": "string",
      "version": 1,
      "metadata": {}
    }
  ],
  "timestamp": 0
}
```

---

### Get Event

Gets a specific event by ID.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId`  
**Handler:** `EventStoreHandler.getEvent()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name
- `eventId` (string, required): The event ID

**Response:** `200 OK`
```json
{
  "message": "Event retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "eventId": "string",
  "event": {
    "id": "string",
    "eventType": "string",
    "eventData": {},
    "validFrom": "ISO-8601 timestamp",
    "validTo": "ISO-8601 timestamp",
    "transactionTime": "ISO-8601 timestamp",
    "correlationId": "string",
    "causationId": "string",
    "version": 1,
    "metadata": {}
  },
  "timestamp": 0
}
```

---

### Get Event Store Statistics

Gets statistics for an event store.

**Endpoint:** `GET /api/v1/eventstores/:setupId/:eventStoreName/stats`  
**Handler:** `EventStoreHandler.getStats()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `eventStoreName` (string, required): The event store name

**Response:** `200 OK`
```json
{
  "message": "Event store statistics retrieved successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "stats": {
    "eventStoreName": "string",
    "totalEvents": 0,
    "totalCorrections": 0,
    "eventCountsByType": {}
  },
  "timestamp": 0
}
```

---

## Management API Endpoints

### Get System Overview

Gets overall system statistics and overview.

**Endpoint:** `GET /api/v1/management/overview`  
**Handler:** `ManagementApiHandler.getSystemOverview()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "systemStats": {
    "totalQueues": 0,
    "totalConsumerGroups": 0,
    "totalEventStores": 0,
    "totalMessages": 0,
    "messagesPerSecond": 0.0,
    "activeConnections": 0,
    "uptime": "string"
  },
  "queueSummary": {
    "total": 0,
    "active": 0,
    "idle": 0,
    "error": 0
  },
  "consumerGroupSummary": {
    "total": 0,
    "active": 0,
    "members": 0
  },
  "eventStoreSummary": {
    "total": 0,
    "events": 0,
    "corrections": 0
  },
  "recentActivity": [],
  "timestamp": 0
}
```

---

### Get All Queues

Gets a list of all queues across all setups.

**Endpoint:** `GET /api/v1/management/queues`  
**Handler:** `ManagementApiHandler.getQueues()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "message": "Queues retrieved successfully",
  "queueCount": 0,
  "queues": [
    {
      "name": "string",
      "setup": "string",
      "implementationType": "peegeeq-native|peegeeq-outbox",
      "status": "active|idle|error",
      "messages": 0,
      "consumers": 0,
      "messageRate": 0.0,
      "consumerRate": 0.0,
      "durability": "durable",
      "autoDelete": false,
      "createdAt": "timestamp",
      "lastActivity": "timestamp"
    }
  ],
  "timestamp": 0
}
```

---

### Create Queue

Creates a new queue in a setup.

**Endpoint:** `POST /api/v1/management/queues`  
**Handler:** `ManagementApiHandler.createQueue()`  
**Service:** `DatabaseSetupService.addQueue()`

**Request Body:**
```json
{
  "name": "string",
  "setup": "string",
  "maxRetries": 3,
  "visibilityTimeoutSeconds": 30,
  "deadLetterEnabled": true
}
```

**Response:** `201 Created`
```json
{
  "message": "Queue created successfully",
  "queueName": "string",
  "setupId": "string",
  "queueId": "string",
  "timestamp": 0
}
```

---

### Update Queue

Updates queue configuration.

**Endpoint:** `PUT /api/v1/management/queues/:queueId`  
**Handler:** `ManagementApiHandler.updateQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `queueId` (string, required): Queue ID in format "setupId-queueName"

**Request Body:**
```json
{
  "maxRetries": 3,
  "visibilityTimeoutSeconds": 30,
  "deadLetterEnabled": true
}
```

**Response:** `200 OK`
```json
{
  "message": "Queue configuration updated successfully",
  "queueId": "string",
  "setupId": "string",
  "queueName": "string",
  "note": "Configuration updates are applied to runtime settings",
  "timestamp": 0
}
```

---

### Delete Queue

Deletes a queue from a setup.

**Endpoint:** `DELETE /api/v1/management/queues/:queueId`  
**Handler:** `ManagementApiHandler.deleteQueue()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `queueId` (string, required): Queue ID in format "setupId-queueName"

**Response:** `200 OK`
```json
{
  "message": "Queue deleted successfully",
  "queueId": "string",
  "setupId": "string",
  "queueName": "string",
  "note": "Queue resources have been cleaned up",
  "timestamp": 0
}
```

---

### Get All Consumer Groups

Gets a list of all consumer groups across all queues.

**Endpoint:** `GET /api/v1/management/consumer-groups`  
**Handler:** `ManagementApiHandler.getConsumerGroups()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "message": "Consumer groups retrieved successfully",
  "groupCount": 0,
  "consumerGroups": [
    {
      "name": "string",
      "setup": "string",
      "queueName": "string",
      "implementationType": "string",
      "members": 0,
      "status": "active|error",
      "partition": 0,
      "lag": 0,
      "createdAt": "timestamp",
      "lastRebalance": "timestamp"
    }
  ],
  "timestamp": 0
}
```

---

### Create Consumer Group (Management)

Creates a new consumer group via management API.

**Endpoint:** `POST /api/v1/management/consumer-groups`  
**Handler:** `ManagementApiHandler.createConsumerGroup()`  
**Service:** `QueueFactory.createConsumerGroup()`

**Request Body:**
```json
{
  "name": "string",
  "setup": "string",
  "queueName": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Consumer group created successfully",
  "groupName": "string",
  "setupId": "string",
  "queueName": "string",
  "groupId": "string",
  "timestamp": 0
}
```

---

### Delete Consumer Group (Management)

Deletes a consumer group via management API.

**Endpoint:** `DELETE /api/v1/management/consumer-groups/:groupId`  
**Handler:** `ManagementApiHandler.deleteConsumerGroup()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `groupId` (string, required): Group ID in format "setupId-groupName"

**Response:** `200 OK`
```json
{
  "message": "Consumer group deleted successfully",
  "groupId": "string",
  "setupId": "string",
  "groupName": "string",
  "note": "Consumer group has been stopped and cleaned up",
  "timestamp": 0
}
```

---

### Get All Event Stores

Gets a list of all event stores across all setups.

**Endpoint:** `GET /api/v1/management/event-stores`  
**Handler:** `ManagementApiHandler.getEventStores()`  
**Service:** `DatabaseSetupService.getAllActiveSetupIds()`

**Response:** `200 OK`
```json
{
  "message": "Event stores retrieved successfully",
  "eventStoreCount": 0,
  "eventStores": [
    {
      "name": "string",
      "setup": "string",
      "events": 0,
      "aggregates": 0,
      "corrections": 0,
      "biTemporal": true,
      "retention": "365d",
      "status": "active",
      "createdAt": "timestamp",
      "lastEvent": "timestamp"
    }
  ],
  "timestamp": 0
}
```

---

### Create Event Store

Creates a new event store in a setup.

**Endpoint:** `POST /api/v1/management/event-stores`  
**Handler:** `ManagementApiHandler.createEventStore()`  
**Service:** `DatabaseSetupService.addEventStore()`

**Request Body:**
```json
{
  "name": "string",
  "setup": "string",
  "tableName": "string",
  "biTemporalEnabled": true,
  "notificationPrefix": "string"
}
```

**Response:** `201 Created`
```json
{
  "message": "Event store created successfully",
  "eventStoreName": "string",
  "setupId": "string",
  "tableName": "string",
  "biTemporalEnabled": true,
  "storeId": "string",
  "timestamp": 0
}
```

---

### Delete Event Store

Deletes an event store from a setup.

**Endpoint:** `DELETE /api/v1/management/event-stores/:storeId`  
**Handler:** `ManagementApiHandler.deleteEventStore()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Path Parameters:**
- `storeId` (string, required): Store ID in format "setupId-storeName"

**Response:** `200 OK`
```json
{
  "message": "Event store deleted successfully",
  "storeId": "string",
  "setupId": "string",
  "storeName": "string",
  "note": "Event store and associated data have been removed",
  "timestamp": 0
}
```

---

### Get Messages

Gets messages for browsing (management view).

**Endpoint:** `GET /api/v1/management/messages`  
**Handler:** `ManagementApiHandler.getMessages()`  
**Service:** `DatabaseSetupService.getSetupResult()`

**Query Parameters:**
- `setup` (string, optional): Filter by setup ID
- `queue` (string, optional): Filter by queue name
- `limit` (number, optional): Maximum number of messages
- `offset` (number, optional): Offset for pagination

**Response:** `200 OK`
```json
{
  "message": "Messages retrieved successfully",
  "messageCount": 0,
  "messages": [],
  "timestamp": 0
}
```

---

### Get System Metrics

Gets real-time system metrics.

**Endpoint:** `GET /api/v1/management/metrics`  
**Handler:** `ManagementApiHandler.getMetrics()`  
**Service:** Internal metrics cache

**Response:** `200 OK`
```json
{
  "timestamp": 0,
  "uptime": 0,
  "memoryUsed": 0,
  "memoryTotal": 0,
  "memoryMax": 0,
  "cpuCores": 0,
  "threadsActive": 0,
  "messagesPerSecond": 0.0,
  "activeConnections": 0,
  "totalMessages": 0
}
```

---

## Health & Metrics Endpoints

### Health Check

Basic health check endpoint.

**Endpoint:** `GET /health`  
**Handler:** Inline handler in `PeeGeeQRestServer.createRouter()`  
**Service:** None

**Response:** `200 OK`
```json
{
  "status": "UP",
  "service": "peegeeq-rest-api"
}
```

---

### Health Check (Management)

Enhanced health check for management UI.

**Endpoint:** `GET /api/v1/health`  
**Handler:** `ManagementApiHandler.getHealth()`  
**Service:** Internal system information

**Response:** `200 OK`
```json
{
  "status": "UP",
  "timestamp": "ISO-8601 timestamp",
  "uptime": "string",
  "version": "1.0.0",
  "build": "Phase-5-Management-UI"
}
```

---

### Metrics Endpoint

Prometheus-compatible metrics endpoint.

**Endpoint:** `GET /metrics`  
**Handler:** Inline handler in `PeeGeeQRestServer.createRouter()`  
**Service:** Micrometer MeterRegistry

**Response:** `200 OK` (Content-Type: text/plain; version=0.0.4)
```
# HELP peegeeq_http_requests_total Total HTTP requests
# TYPE peegeeq_http_requests_total counter
peegeeq_http_requests_total 0
# HELP peegeeq_active_connections Active connections
# TYPE peegeeq_active_connections gauge
peegeeq_active_connections 0
# HELP peegeeq_messages_sent_total Total messages sent
# TYPE peegeeq_messages_sent_total counter
peegeeq_messages_sent_total 0
```

---

## Real-time Streaming Endpoints

### Server-Sent Events (SSE) Stream

Streams queue messages in real-time via Server-Sent Events.

**Endpoint:** `GET /api/v1/queues/:setupId/:queueName/stream`  
**Handler:** `ServerSentEventsHandler.handleQueueStream()`  
**Service:** `DatabaseSetupService.getSetupResult()` and `QueueFactory.createConsumer()`

**Path Parameters:**
- `setupId` (string, required): The setup ID
- `queueName` (string, required): The queue name

**Query Parameters:**
- `consumerGroup` (string, optional): Consumer group name
- `autoAck` (boolean, optional): Auto-acknowledge messages (default: false)

**Response:** `200 OK` (Content-Type: text/event-stream)

**Event Stream Format:**
```
event: message
data: {"messageId":"...","payload":{...},"headers":{...}}

event: heartbeat
data: {"timestamp":1234567890}

event: error
data: {"error":"..."}

event: end
data: {"reason":"..."}
```

---

### WebSocket Stream

Streams queue messages in real-time via WebSocket.

**Endpoint:** `ws://localhost:8080/ws/queues/:setupId/:queueName`  
**Handler:** `WebSocketHandler.handleQueueStream()`  
**Service:** `DatabaseSetupService.getSetupResult()` and `QueueFactory.createConsumer()`

**Path Parameters:**
- `setupId` (string): The setup ID (in WebSocket path)
- `queueName` (string): The queue name (in WebSocket path)

**WebSocket Message Format (Incoming):**
```json
{
  "action": "subscribe",
  "consumerGroup": "string",
  "autoAck": false
}
```

**WebSocket Message Format (Outgoing):**
```json
{
  "type": "message",
  "messageId": "string",
  "payload": {},
  "headers": {},
  "timestamp": 0
}
```

---

## Services Used

### DatabaseSetupService

Primary service for managing database setups, queues, and event stores.

**Key Methods:**
- `createCompleteSetup(DatabaseSetupRequest)` - Creates a complete database setup
- `destroySetup(String setupId)` - Destroys a setup and cleans up resources
- `getSetupStatus(String setupId)` - Gets the status of a setup
- `getSetupResult(String setupId)` - Gets complete setup result with factories
- `addQueue(String setupId, QueueConfig)` - Adds a queue to an existing setup
- `addEventStore(String setupId, EventStoreConfig)` - Adds an event store to a setup
- `getAllActiveSetupIds()` - Gets all active setup IDs

---

### QueueFactory

Factory for creating queue producers and consumers.

**Key Methods:**
- `createProducer(String queueName, Class<T>)` - Creates a message producer
- `createConsumer(String queueName, Class<T>)` - Creates a message consumer
- `createConsumerGroup(String groupName, String queueName, Class<T>)` - Creates a consumer group
- `isHealthy()` - Checks if the queue factory is healthy
- `getImplementationType()` - Gets the implementation type (peegeeq-native or peegeeq-outbox)
- `close()` - Closes the queue factory and releases resources

---

### MessageProducer

Interface for sending messages to queues.

**Key Methods:**
- `send(T message, Map<String,String> headers, String correlationId, String messageGroup)` - Sends a message
- `close()` - Closes the producer

---

### MessageConsumer

Interface for consuming messages from queues.

**Key Methods:**
- `start()` - Starts the consumer
- `stop()` - Stops the consumer
- `close()` - Closes the consumer

---

## Error Responses

All endpoints return standard error responses in the following format:

```json
{
  "error": "Error message description",
  "timestamp": 1234567890
}
```

**Common HTTP Status Codes:**
- `200 OK` - Request successful
- `201 Created` - Resource created successfully
- `204 No Content` - Request successful, no content to return
- `207 Multi-Status` - Batch operation with partial success
- `400 Bad Request` - Invalid request format or parameters
- `404 Not Found` - Resource not found
- `409 Conflict` - Resource already exists or conflict
- `500 Internal Server Error` - Server error
- `501 Not Implemented` - Feature not yet implemented

---

## Implementation Types

PeeGeeQ supports multiple queue implementation types:

1. **peegeeq-native**: Native PostgreSQL-based queue implementation with high performance
2. **peegeeq-outbox**: Transactional outbox pattern implementation for reliable messaging

The implementation type is determined during setup creation and affects message delivery guarantees and performance characteristics.

---

## Load Balancing Strategies

Consumer groups support different load balancing strategies:

1. **ROUND_ROBIN**: Messages are distributed evenly across all members in rotation
2. **STICKY**: Messages with the same message group ID are always sent to the same member
3. **LEAST_LOADED**: Messages are sent to the member with the least current load

---

## Notes

- All timestamps are in milliseconds since epoch unless otherwise specified
- ISO-8601 format is used for date/time strings in event stores
- WebSocket connections require upgrade from HTTP
- SSE streams maintain persistent connections with automatic reconnection
- Consumer groups provide coordinated message consumption across multiple consumers
- Bi-temporal event stores support both transaction time and valid time dimensions
- Queue messages support priorities from 1 (lowest) to 10 (highest)
- Dead letter queues are automatically created when `deadLetterEnabled` is true
- Message acknowledgment is required for at-least-once delivery semantics

---

## Gap Analysis: REST API vs Core Services

This section identifies gaps, missing features, and parameter misalignments between the REST API endpoints and the underlying PeeGeeQ core service APIs.

### 1. Queue Management Gaps

#### 1.1 Missing QueueConfig Parameters

**Status:** ⚠️ **CRITICAL GAP**

The REST API only exposes a subset of QueueConfig parameters:

**Exposed in REST API:**
- `maxRetries` (Integer)
- `visibilityTimeoutSeconds` (Integer) - Note: Core API uses Duration
- `deadLetterEnabled` (Boolean)

**Missing from REST API:**
- `batchSize` (Integer, default: 10) - Controls batch processing size
- `pollingInterval` (Duration, default: 5 seconds) - Controls polling frequency
- `fifoEnabled` (Boolean, default: false) - FIFO queue ordering
- `deadLetterQueueName` (String) - Custom dead letter queue name

**Impact:** REST API consumers cannot configure batch processing, polling intervals, FIFO ordering, or custom dead letter queues.

**Recommendation:** Add these parameters to the queue creation/update endpoints.

---

#### 1.2 Duration vs Seconds Mismatch

**Status:** ⚠️ **INCONSISTENCY**

- **Core API:** Uses `java.time.Duration` for `visibilityTimeout` (default: 5 minutes)
- **REST API:** Uses integer seconds `visibilityTimeoutSeconds`

**Impact:** Loss of precision for sub-second durations, inconsistent API design.

**Recommendation:** Consider supporting ISO-8601 duration format in REST API (e.g., "PT5M") or document the conversion clearly.

---

#### 1.3 Missing Consumer Configuration

**Status:** ⚠️ **MAJOR GAP**

The core `QueueFactory.createConsumer()` method supports custom consumer configuration:

```java
<T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig)
```

**REST API:** No endpoint supports consumer configuration (polling vs LISTEN/NOTIFY modes).

**Impact:** REST API consumers cannot configure advanced consumer behaviors like switching between polling and push-based consumption.

**Recommendation:** Add consumer configuration parameters to the subscription/consumption endpoints.

---

### 2. Message Operations Gaps

#### 2.1 Missing Message Acknowledgment Features

**Status:** ⚠️ **MAJOR GAP**

**Core API:** `MessageConsumer` provides push-based subscription with automatic message delivery via `MessageHandler`.

**REST API:** Implements pull-based polling with manual acknowledgment, but:
- `getNextMessage()` and `getMessages()` are **placeholder implementations**
- `acknowledgeMessage()` is **placeholder implementation**
- No negative acknowledgment (NACK) support
- No visibility timeout extension
- No message requeue with delay

**Impact:** REST API message consumption is not fully functional. Messages cannot be reliably consumed and acknowledged.

**Recommendation:** 
1. Implement actual database polling queries for message retrieval
2. Add advisory locking for message ownership
3. Implement proper acknowledgment with database updates
4. Add NACK endpoint for failed message processing
5. Add visibility timeout extension endpoint

---

#### 2.2 Missing MessageProducer Return Values

**Status:** ⚠️ **INCONSISTENCY**

**Core API:** `MessageProducer.send()` returns `CompletableFuture<Void>` - no message ID returned

**REST API:** Returns `messageId` in the response, using `correlationId` as the message ID

**Impact:** REST API creates an expectation that the core API doesn't fulfill. The `messageId` returned is actually the `correlationId`, not a database-generated message ID.

**Recommendation:** 
1. Update core API to return message IDs from `send()` methods
2. Document clearly that `messageId` in REST responses is the correlation ID
3. Consider adding a separate `databaseMessageId` field for the actual row ID

---

#### 2.3 Missing Reactive API Endpoints

**Status:** ⚠️ **FEATURE GAP**

**Core API:** Provides reactive methods (`sendReactive()`, `subscribeReactive()`) using Vert.x `Future`

**REST API:** Does not expose reactive endpoints. All endpoints use blocking `CompletableFuture` style.

**Impact:** Cannot leverage reactive programming benefits in REST API.

**Recommendation:** This is acceptable for REST APIs. Reactive programming is typically used internally, not exposed via REST.

---

### 3. Consumer Group Gaps

#### 3.1 Missing SubscriptionOptions

**Status:** ⚠️ **CRITICAL GAP**

**Core API:** `ConsumerGroup.start(SubscriptionOptions)` supports:
- `startPosition` (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP)
- `startFromMessageId` (Long) - Start from specific message
- `startFromTimestamp` (Instant) - Start from specific time
- `heartbeatIntervalSeconds` (Integer, default: 60)
- `heartbeatTimeoutSeconds` (Integer, default: 300)

**REST API:** 
- `createConsumerGroup()` endpoint has no subscription options
- `joinConsumerGroup()` endpoint has no subscription options
- No way to specify start position for late-joining consumers

**Impact:** Cannot implement backfill scenarios or replay messages from specific points. All consumers can only consume from NOW.

**Recommendation:** Add subscription options to consumer group creation and join endpoints.

---

#### 3.2 Missing ConsumerGroup Methods

**Status:** ⚠️ **FEATURE GAP**

**Core API** provides these methods not exposed in REST API:
- `addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> messageFilter)` - Per-consumer filtering
- `setMessageHandler(MessageHandler<T> handler)` - Simple single-handler setup
- `setGroupFilter(Predicate<Message<T>> groupFilter)` - Group-level filtering
- `getGroupFilter()` - Get current group filter
- `getStats()` - Detailed consumer group statistics

**REST API:** Only exposes basic add/remove member operations, no filtering or statistics.

**Impact:** Cannot implement message filtering at the group or consumer level via REST API.

**Recommendation:** Add filtering and statistics endpoints.

---

#### 3.3 Missing ConsumerGroupStats

**Status:** ⚠️ **FEATURE GAP**

**Core API:** Provides `ConsumerGroupStats` interface (details not in current codebase snapshot)

**REST API:** Returns basic member count and status, no detailed statistics.

**Impact:** No visibility into consumer group performance metrics.

**Recommendation:** Implement full statistics endpoint matching core API capabilities.

---

### 4. Event Store Gaps

#### 4.1 Missing EventStoreConfig Parameters

**Status:** ⚠️ **MAJOR GAP**

**REST API** only exposes:
- `eventStoreName` (String)
- `tableName` (String)
- `biTemporalEnabled` (Boolean)
- `notificationPrefix` (String)

**Core API** also supports:
- `queryLimit` (Integer, default: 1000) - Max query results
- `metricsEnabled` (Boolean, default: true) - Enable metrics collection
- `eventType` (Class<?>, default: Object.class) - Event payload type
- `partitionStrategy` (String, default: "monthly") - Table partitioning strategy

**Impact:** Cannot configure query limits, metrics, event types, or partitioning via REST API.

**Recommendation:** Add missing parameters to event store creation endpoint.

---

#### 4.2 Missing Event Store Methods

**Status:** ⚠️ **CRITICAL GAP**

**Core API** provides extensive event store operations:

**Missing from REST API:**
- `append()` variations with different parameter combinations
- `appendCorrection()` - Bi-temporal corrections with `correctionReason`
- `appendInTransaction()` - Transaction-aware event appending (4 variations)
- `getById(String eventId)` - Get specific event
- `getAllVersions(String eventId)` - Get all versions including corrections
- `getAsOfTransactionTime(String eventId, Instant asOfTransactionTime)` - Point-in-time query
- `subscribe()` methods - Real-time event subscription (2 variations)
- `unsubscribe()` - Unsubscribe from events
- `getStats()` - Event store statistics with detailed interface

**REST API** provides:
- Basic `storeEvent()` - Limited to single append
- `queryEvents()` - Basic query with filters
- `getEvent()` - Get single event (placeholder implementation)
- `getStats()` - Basic statistics (placeholder implementation)

**Impact:** 
- Cannot create bi-temporal corrections via REST API
- Cannot participate in transactions
- No version history queries
- No real-time event subscription (except via WebSocket/SSE which are separate)

**Recommendation:** Add comprehensive event store endpoints matching core API.

---

#### 4.3 Missing EventQuery Parameters

**Status:** ⚠️ **MAJOR GAP**

**REST API** query parameters:
- `eventType` (String)
- `fromTime` (ISO-8601)
- `toTime` (ISO-8601)
- `limit` (Integer, max: 1000)
- `offset` (Integer)
- `correlationId` (String)
- `causationId` (String)

**Core API** `EventQuery` also supports:
- `aggregateId` (String) - Group related events
- `validTimeRange` (TemporalRange) - Bi-temporal valid time filtering
- `transactionTimeRange` (TemporalRange) - Bi-temporal transaction time filtering
- `headerFilters` (Map<String, String>) - Filter by custom headers
- `sortOrder` (Enum: 6 options) - Various sort orders
- `includeCorrections` (Boolean, default: true) - Include/exclude corrections
- `versionRange` (Long min/max) - Filter by version numbers

**Impact:** Cannot perform bi-temporal queries via REST API. Missing critical filtering capabilities.

**Recommendation:** Add bi-temporal temporal ranges and additional filters to query endpoint.

---

#### 4.4 Placeholder Implementations

**Status:** 🔴 **BLOCKER**

These event store methods are **placeholder implementations** with no actual database queries:

- `queryEvents()` - Returns sample/mock events
- `getEvent()` - Returns sample event or null
- `getStats()` - Returns sample statistics

**Impact:** Event store REST API is **NOT FUNCTIONAL** for production use.

**Recommendation:** Implement actual database queries before production use.

---

### 5. Database Setup Gaps

#### 5.1 Complete Coverage

**Status:** ✅ **COMPLETE**

The Database Setup endpoints have good coverage:
- `createCompleteSetup()` ✅
- `destroySetup()` ✅
- `getSetupStatus()` ✅
- `getSetupResult()` ✅ (via internal use)
- `addQueue()` ✅
- `addEventStore()` ✅
- `getAllActiveSetupIds()` ✅ (via internal use)

All reactive variants are internal convenience methods - not needed in REST API.

---

### 6. Streaming Endpoints Gaps

#### 6.1 Server-Sent Events (SSE)

**Status:** ⚠️ **PARTIAL IMPLEMENTATION**

**REST API:** Provides SSE endpoint `GET /api/v1/queues/:setupId/:queueName/stream`

**Core API:** Uses `MessageConsumer.subscribe(MessageHandler)` for push-based consumption

**Gap:** SSE handler creates consumers but:
- No consumer configuration support
- No start position control
- Limited error handling
- No reconnection token support

**Recommendation:** Add subscription options and reconnection tokens to SSE endpoint.

---

#### 6.2 WebSocket Stream

**Status:** ⚠️ **PARTIAL IMPLEMENTATION**

**REST API:** Provides WebSocket endpoint `ws://localhost:8080/ws/queues/:setupId/:queueName`

**Core API:** Uses `MessageConsumer.subscribe(MessageHandler)` for push-based consumption

**Gap:** WebSocket handler:
- No consumer configuration support
- No start position control
- Limited protocol for control messages
- No consumer group support in WebSocket mode

**Recommendation:** Define comprehensive WebSocket protocol with control messages.

---

### 7. Management API Gaps

#### 7.1 Metrics Integration

**Status:** ⚠️ **INCOMPLETE**

**REST API:** 
- Basic Prometheus metrics endpoint with hardcoded values
- `getMetrics()` endpoint with cached system metrics

**Core API:** Has `MetricsProvider` interface (not examined in detail)

**Gap:** 
- Metrics are not integrated with actual queue/event store operations
- No per-queue, per-consumer-group, or per-event-store metrics
- Prometheus endpoint returns placeholder data

**Recommendation:** Integrate with core `MetricsProvider` for real metrics.

---

#### 7.2 Real-time Statistics

**Status:** 🔴 **NOT IMPLEMENTED**

**REST API Methods Return Zero/Empty:**
- `getRealMessageCount()` - Always returns 0
- `getRealConsumerCount()` - Always returns 0
- `getRealMessageRate()` - Always returns 0.0
- `getRealConsumerRate()` - Always returns 0.0
- `getRealEventCount()` - Always returns 0
- `getRealAggregateCount()` - Always returns 0
- `getRealCorrectionCount()` - Always returns 0

**Impact:** Management UI shows no real data.

**Recommendation:** Implement database queries for real-time statistics.

---

### 8. Missing Core Features in REST API

#### 8.1 Message Filtering

**Status:** 🔴 **NOT AVAILABLE**

**Core API:** `MessageFilter` interface for filtering messages

**REST API:** No message filtering support

**Recommendation:** Add filtering parameters to consumption endpoints.

---

#### 8.2 Message Handlers

**Status:** ⚠️ **NOT APPLICABLE**

**Core API:** `MessageHandler<T>` for push-based async message processing

**REST API:** Uses pull-based HTTP polling - handlers not applicable

**Decision:** This is an expected difference between push and pull models.

---

#### 8.3 Transaction Participation

**Status:** 🔴 **NOT AVAILABLE**

**Core API:** 
- `EventStore.appendInTransaction()` - 4 variations for ACID guarantees
- Requires `io.vertx.sqlclient.SqlConnection` parameter

**REST API:** No transaction support

**Impact:** Cannot ensure ACID guarantees between business operations and event logging via REST API.

**Recommendation:** This is a limitation of REST APIs. Document that transactional operations require direct SDK usage.

---

#### 8.4 CloudEvents Support

**Status:** ⚠️ **PARTIAL**

**Core API:** Detects CloudEvents Jackson module at runtime

**REST API:** Message sending supports custom headers and payloads but no explicit CloudEvents schema validation

**Recommendation:** Add CloudEvents-specific endpoints or document CloudEvents payload structure.

---

### 9. Parameter Type Conversions

#### 9.1 Duration to Seconds

**Issue:** Core API uses `java.time.Duration`, REST API uses integer seconds

**Affected Parameters:**
- `visibilityTimeout` (Core: Duration, REST: visibilityTimeoutSeconds)
- `pollingInterval` (Core: Duration, REST: not exposed)

**Recommendation:** Document conversion clearly or support ISO-8601 duration strings.

---

#### 9.2 Instant to String

**Issue:** Core API uses `java.time.Instant`, REST API uses ISO-8601 strings

**Affected Parameters:**
- All timestamp fields in event queries
- Valid time and transaction time in events

**Status:** ✅ **ACCEPTABLE** - Standard REST API practice

---

#### 9.3 Enum to String

**Issue:** Core API uses Java enums, REST API uses strings

**Affected:**
- `LoadBalancingStrategy` (ROUND_ROBIN, STICKY, LEAST_LOADED)
- `EventQuery.SortOrder` (6 enum values)
- `StartPosition` (4 enum values)

**Status:** ✅ **ACCEPTABLE** - Standard REST API practice with validation needed

---

### 10. Summary of Critical Gaps

#### 🔴 Blockers (Must Fix Before Production)

1. **Message consumption endpoints are placeholders** - No actual polling implementation
2. **Message acknowledgment is placeholder** - No actual database updates
3. **Event store query is placeholder** - Returns mock data
4. **Event store stats is placeholder** - Returns mock data
5. **All real-time statistics return zero** - No database queries implemented

#### ⚠️ High Priority (Significant Feature Gaps)

1. Missing QueueConfig parameters (batchSize, pollingInterval, fifoEnabled, deadLetterQueueName)
2. Missing SubscriptionOptions in consumer groups (start position, heartbeats)
3. Missing EventStoreConfig parameters (queryLimit, metricsEnabled, partitionStrategy)
4. Missing bi-temporal query capabilities (temporal ranges, corrections)
5. Missing consumer group filtering (per-consumer and group-level)
6. Missing event correction endpoints (appendCorrection)
7. No transaction support for event appending

#### ℹ️ Medium Priority (Nice to Have)

1. Consumer configuration support
2. Message filtering in consumption
3. Extended statistics and metrics
4. Version history queries for events
5. Real-time event subscription via REST
6. CloudEvents explicit support
7. Negative acknowledgment (NACK) support

#### ✅ Low Priority (Acceptable As-Is)

1. Reactive API methods (internal use only)
2. Type conversions (Duration, Instant, Enum) - standard REST practice
3. Message handlers (push vs pull model difference)
4. Transaction participation (SDK-only feature)

---

### 11. Alignment Recommendations

#### Phase 1: Critical Fixes (Blockers)
1. Implement actual database queries for message polling
2. Implement actual database queries for acknowledgment
3. Implement actual database queries for event store operations
4. Implement actual database queries for statistics

#### Phase 2: High Priority Features
1. Add missing configuration parameters to all creation endpoints
2. Add subscription options to consumer groups
3. Add bi-temporal query support to event store
4. Add correction endpoints to event store
5. Add filtering support to consumer groups

#### Phase 3: Medium Priority Enhancements
1. Add consumer configuration to message consumption
2. Add message filtering to consumption endpoints
3. Enhance statistics and metrics integration
4. Add version history queries
5. Document CloudEvents support

#### Phase 4: Documentation
1. Document all parameter type conversions
2. Document feature limitations (transactions, reactive APIs)
3. Create migration guide from SDK to REST API
4. Add examples for common use cases

---

**Document Version:** 1.0  
**Last Updated:** 2025-07-19  
**Author:** Mark Andrew Ray-Smith Cityline Ltd
