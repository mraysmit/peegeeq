# PeeGeeQ REST API Guide
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 1.0

This guide describes the HTTP, Server-Sent Events (SSE) and WebSocket interface of the `peegeeq-rest` module. It is written from the router in `PeeGeeQRestServer` and from the handler classes it registers. It owns endpoint detail. No endpoint was called while it was written. A statement marked "(read in code, not run)" depends on runtime behaviour of a framework or of another module that this guide did not exercise.

## Contents

1. Overview
2. Setup lifecycle
3. Queues and messages
4. Consumer groups and subscription options
5. Subscription lifecycle, backfill and partitioned consumption
6. Webhook subscriptions
7. Dead letter queue
8. Event store
9. Health, consumer alerts, database telemetry and metrics
10. Management API
11. Streaming
12. Error responses
13. Route index
14. Not provided

---

## 1. Overview

### Base URL and port

| Item | Value |
|---|---|
| API base path | `/api/v1` |
| Other paths | `/health`, `/metrics`, `/ui/*`, `/`, `/sse/metrics`, `/ws/...` |
| Port in `peegeeq-rest/src/main/resources/conf/rest-server.json` | `8088` |
| Port when the configuration has no `port` key | `8080` (`RestServerConfig.from`) |
| Port `0` | The operating system assigns a port. `PeeGeeQRestServer.actualPort()` returns it. |

`StartRestServer` merges three configuration sources. Later sources override earlier ones:

1. The file `conf/rest-server.json` (optional).
2. Environment variables (for example `port`).
3. System properties (for example `-Dport=9090`).

The console message that `StartRestServer` prints after start names `http://localhost:8080` as fixed text. The server listens on the configured port.

`allowedOrigins` is required and must not be empty. The server refuses to start without it. A single `*` entry is accepted and logged as a warning.

### Content type and body parsing

- Request bodies are JSON. A `BodyHandler` reads the body of every route.
- Responses are `application/json`, except SSE streams (`text/event-stream`), `/metrics` (`text/plain; version=0.0.4; charset=utf-8`), the static UI, and the plain-text rejections of the metrics SSE stream.
- Four bodies are bound to Java classes with a plain Jackson `ObjectMapper` (no unknown-property setting is changed): send message, send batch, store event, append correction. A property that the class does not declare fails the parse, and the handler answers 400 (read in code, not run).
- All other bodies are read as a Vert.x `JsonObject`. Fields the handler does not read are ignored.

### CORS

The router installs one `CorsHandler`:

| Setting | Value |
|---|---|
| Origins | Each entry of `allowedOrigins` |
| Methods | `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS` |
| Allowed request headers | `Content-Type`, `traceparent`, `Authorization` |

`tracestate`, `baggage`, `Idempotency-Key`, `Last-Event-ID` and `Cache-Control` are not in the allowed-header list. A browser that sends one of them cross-origin is refused at preflight (read in code, not run). Three SSE handlers also write `Access-Control-Allow-Origin: *` and `Access-Control-Allow-Headers` on their own response: the queue message stream and the queue-updates stream write `Access-Control-Allow-Headers: Cache-Control`; the event store stream writes `Access-Control-Allow-Headers: Cache-Control, Last-Event-ID`.

### Request headers the server reads

| Header | Where | Effect |
|---|---|---|
| `traceparent` | Every request (router-level handler) and the send, batch, create-setup and connect-setup handlers | Parsed into the trace context, or a new context is created. On send it is copied into the message headers. |
| `tracestate` | Send and batch | Copied into the message headers. |
| `baggage` | Send and batch | Copied into the message headers. |
| `Idempotency-Key` | Send and batch | Stored in the message headers as `idempotencyKey`. |
| `Last-Event-ID` | Event store SSE stream | Resume point. See section 11, "Event store event stream". |

### Authentication

The server has no authentication or authorization. The router registers no auth handler. `Authorization` is accepted by CORS and is never read.

### Names

Send, batch, queue stats, the stats stream and the queue WebSocket check the queue name against `^[a-zA-Z0-9._-]{1,128}$`. A name that does not match returns 400 `{"error":"Invalid queue name"}`. The WebSocket closes with code `4000`.

### State held in server memory

Two kinds of object exist only in the memory of one server process and are lost when it stops:

- Consumer groups created through section 4.
- Webhook subscriptions (section 6).

Subscriptions (section 5) are stored in the database.

### Request metrics

Every request is recorded in the Micrometer timer `peegeeq.http.requests.duration` and the counter `peegeeq.http.requests.total`, tagged `method`, `path` and `status`. The `path` tag is `ctx.normalizedPath()`, the actual request path, not the route pattern. Each distinct ID in a path creates its own series. `/metrics` exposes them.

---

## 2. Setup lifecycle

A setup is one PostgreSQL database and schema with its queues and event stores. Create, connect, delete and detach never drop a database. Only the guarded drop does.

Errors from this family have the body `{"error", "statusCode", "timestamp"}`. See section 12.

### Create setup

`POST /api/v1/setups` and `POST /api/v1/database-setup/create` (same handler).

| Field | Type | Required | Default |
|---|---|---|---|
| `setupId` | string | yes | |
| `databaseConfig` | object | yes | |
| `databaseConfig.schema` | string | yes | |
| `databaseConfig.host` | string | no | `localhost` |
| `databaseConfig.port` | integer | no | `5432` |
| `databaseConfig.databaseName` | string | no | none |
| `databaseConfig.username` | string | no | `postgres` |
| `databaseConfig.password` | string | no | `postgres` |
| `databaseConfig.templateDatabase` | string | no | `template0` |
| `databaseConfig.encoding` | string | no | `UTF8` |
| `queues` | array of queue objects | no | empty |
| `eventStores` | array of event store objects | no | empty |

Queue object (also the body of the add-queue endpoints):

| Field | Type | Required | Default |
|---|---|---|---|
| `queueName` (alias `name`) | string | yes | |
| `visibilityTimeout` | number (seconds) or ISO-8601 duration string | no | 5 minutes |
| `visibilityTimeoutSeconds` | integer | no | read only when `visibilityTimeout` is absent |
| `maxRetries` | integer | no | `3` |
| `deadLetterEnabled` | boolean | no | `true` |
| `deadLetterQueueName` | string | no | none |
| `batchSize` | integer | no | `10` |
| `pollingInterval` | number (seconds) or ISO-8601 duration string | no | 5 seconds |
| `pollingIntervalSeconds` | integer | no | read only when `pollingInterval` is absent |
| `fifoEnabled` | boolean | no | `false` |
| `implementationType` (alias `type`) | `native` or `outbox` | no | the setup chooses the implementation |

`implementationType` is set per queue. It is trimmed and compared without case. Any other value returns 400.

Event store object (also the body of the add-event-store endpoints):

| Field | Type | Required | Default |
|---|---|---|---|
| `eventStoreName` (alias `name`) | string | yes | |
| `tableName` | string | no | name with `-` replaced by `_`, plus `_events` |
| `notificationPrefix` | string | no | name with `-` replaced by `_`, plus `_` |
| `queryLimit` | integer | no | `1000` |
| `metricsEnabled` | boolean | no | `true` |
| `biTemporalEnabled` | boolean | no | `true` |
| `aggregateSummaryEnabled` | boolean | no | `false` |
| `partitionStrategy` | string | no | `monthly` |

Example request:

```json
{
  "setupId": "demo",
  "databaseConfig": {
    "host": "localhost",
    "port": 5432,
    "databaseName": "demo_db",
    "username": "postgres",
    "password": "postgres",
    "schema": "demo"
  },
  "queues": [
    { "queueName": "orders", "implementationType": "native", "visibilityTimeout": "PT5M" }
  ],
  "eventStores": [
    { "eventStoreName": "order-events" }
  ]
}
```

Success: 201 with `setupId`, `status` (setup status name), `queueCount`, `eventStoreCount`, `message` (`"Database setup created successfully"`).

Failures:

| Status | Cause |
|---|---|
| 400 | Missing `setupId`, `databaseConfig` or `schema`; invalid queue or event store object; body not JSON; a failure whose message contains `invalid` |
| 409 | A database creation conflict, or any failure whose message contains `already exists`. Create does not overwrite an existing database. Use connect, or drop the database first. |
| 503 | Any other failure |

### Connect to an existing setup

`POST /api/v1/database-setup/connect`

The body is parsed like create: `setupId` and `databaseConfig.schema` are required. `queues` and `eventStores` are parsed and then ignored. The service rebuilds the setup's queues and event stores from the schema's registry tables and checks that `setupId` matches the stored value. Nothing is created.

Success: 200 with `setupId`, `status`, `queueCount`, `eventStoreCount`, `message` (`"Database setup connected successfully"`).

Failures: 400 (body or validation; the PeeGeeQ schema is absent; `setupId` does not match; the setup is already active in this server), 503 (connection, authentication and other failures).

### List setups

`GET /api/v1/setups`

Success: 200 `{"count": <int>, "setupIds": [<string>...]}`. Failure: 503.

### Setup details

`GET /api/v1/setups/:setupId`

Success: 200 with `setupId`, `status`, `host`, `port`, `databaseName`, `schema`, `queueFactories` (array of queue names), `eventStores` (array of event store names). Credentials are not returned.

Failures: 404 (setup not found), 503.

### Setup status

`GET /api/v1/setups/:setupId/status` and `GET /api/v1/database-setup/:setupId/status`

Success: 200 `{"setupId", "status"}`. `status` is one of `CREATING`, `ACTIVE`, `DESTROYING`, `DESTROYED`, `FAILED`.

Failures: 404, 503.

### Delete setup (non-destructive)

`DELETE /api/v1/setups/:setupId` and `DELETE /api/v1/database-setup/:setupId`

The service closes the setup's queue factories, event stores and manager and removes the in-memory binding. It drops no database and no schema. It does not delete the persisted binding, so the setup can be reloaded from the binding registry. For a setup ID that is not bound, the service returns success and the handler answers 204 (read in code, not run).

Success: 204, no body. Failure: 503.

### Detach setup (non-destructive)

`POST /api/v1/setups/:setupId/detach`

Stops the manager and releases the in-memory binding. When the service has a binding registry, detach also deletes the persisted binding, so the setup is not reloaded at the next start. The database and schema stay. The setup can be connected again. No body is read.

Success: 204. Failure: 503.

### Drop setup database (destructive, guarded)

`POST /api/v1/setups/:setupId/database/drop`

| Field | Type | Required |
|---|---|---|
| `confirmDatabaseName` | string | yes; must equal the setup's database name |

This is the only route that issues `DROP DATABASE`. The statement is `DROP DATABASE IF EXISTS ... WITH (FORCE)`, which terminates live connections (read in code, not run). After the drop succeeds, the service starts a detach and does not wait for it. A detach failure is logged and the response is still 200.

Success: 200 `{"setupId", "message": "Database dropped successfully"}`.

Failures: 400 (body not JSON; confirmation does not match, nothing dropped), 404 (setup not found), 503.

### List queues of a setup

`GET /api/v1/setups/:setupId/queues`

Success: 200 with `count`, `queues` (array of names), `queueDetails` (array of `{"name", "implementationType"}`).

Failures: 404, 503.

### Add a queue

`POST /api/v1/setups/:setupId/queues` and `POST /api/v1/database-setup/:setupId/queues`

Body: the queue object above. The setup ID comes from the path.

Success: 201 with `message`, `queueName`, `setupId`, and `implementationType` when the created factory reports one.

Failures:

| Status | Cause |
|---|---|
| 400 | Invalid queue object or body |
| 404 | Setup not found |
| 409 | The queue exists and has subscriptions, and its registered implementation type differs from the requested type or, when the request names none, from the best available type. A request without `implementationType` can therefore get 409. Nothing changed. |
| 503 | Any other failure |

A re-add of an existing queue without a conflict returns 201 and replaces the in-memory queue factory. The old factory is not closed.

### List event stores of a setup

`GET /api/v1/setups/:setupId/eventstores`

Success: 200 `{"count", "eventStores": [<name>...]}`. Failures: 404, 503.

### Add an event store

`POST /api/v1/setups/:setupId/eventstores`

Body: the event store object above.

Success: 201 `{"message", "eventStoreName", "setupId"}`. Failures: 400 (invalid object or body), 404 (setup not found), 503 (any other failure, including a table name that is not a valid PostgreSQL identifier).

---

## 3. Queues and messages

Path parameters: `setupId`, `queueName`. Errors from `QueueHandler` have the body `{"error", "timestamp"}`, except the invalid-name response `{"error"}`. Errors from the management handler routes in this section have `{"error", "timestamp"}`.

### Send a message

`POST /api/v1/queues/:setupId/:queueName/messages`

`POST /api/v1/queues/:setupId/:queueName/publish` is the same handler.

| Field | Type | Required | Rule |
|---|---|---|---|
| `payload` | any JSON value | yes | |
| `headers` | object of string to string | no | keys not empty, values not null |
| `priority` | integer | no | 1 to 10 |
| `delaySeconds` | integer | no | not negative |
| `messageType` | string | no | |
| `correlationId` | string | no | |
| `messageGroup` | string | no | |

Any other property returns 400. The request has no `causationId`; `causationId` belongs to the event store only.

The handler builds the message headers in this order: the request `headers`; then `traceparent`, `tracestate`, `baggage` from the HTTP request; `priority`; `delaySeconds` when greater than 0; `messageType` (the detected type); `payloadSize` (length of the payload written as JSON); `timestamp`; `idempotencyKey` from the `Idempotency-Key` HTTP header. `priority` and `delaySeconds` reach the queue only as these headers. What the queue implementation does with them is not traced in this guide.

The correlation ID is the body `correlationId`, else `headers.correlationId`, else a random UUID. The message group is the body `messageGroup`, else `headers.messageGroup`.

The detected message type is `messageType` when given. Otherwise: an object payload gives its own `messageType` value, else `Event` (has `eventType`), `Command` (has `commandType`), `Order` (has `orderId`), else `Object`. A string gives `Text`, a number `Numeric`, an array `Array`, anything else `Unknown`.

Example request:

```json
{ "payload": { "orderId": "o-1" }, "priority": 5 }
```

Response (200) for that request:

```json
{
  "message": "Message sent successfully to queue 'orders' in setup 'demo'",
  "queueName": "orders",
  "setupId": "demo",
  "messageId": "<correlation id>",
  "correlationId": "<correlation id>",
  "timestamp": 1700000000000,
  "messageType": "Order",
  "priority": 5,
  "delaySeconds": null
}
```

`messageId` is the correlation ID, not the database row ID. `messageGroup` is added when a group was given. `customHeadersCount` is added when `headers` was given.

Failures:

| Status | Cause |
|---|---|
| 400 | Invalid queue name; empty or invalid body; validation; setup not `ACTIVE` |
| 404 | Setup not found; queue not found |
| 503 | The send failed |

A successful send publishes a `MESSAGE_SENT` event on the queue-updates stream (section 11, "Queue-updates stream").

### Send a batch

`POST /api/v1/queues/:setupId/:queueName/messages/batch`

| Field | Type | Required | Default |
|---|---|---|---|
| `messages` | array of message objects (as above) | yes, at least one | |
| `failOnError` | boolean | no | `true` |
| `maxBatchSize` | integer | no | `100` |

The request fails with 400 when `messages` is empty, has more entries than `maxBatchSize`, or contains an invalid message. `maxBatchSize` is a request field, so the client sets the limit.

All sends start before any outcome is known (read in code, not run). With `failOnError` `true`, the first failure fails the request with 503; messages already sent stay sent. With `failOnError` `false`, failures are collected.

Success response:

| Field | Meaning |
|---|---|
| `message`, `queueName`, `setupId` | |
| `totalMessages` | entries in the batch |
| `successfulMessages`, `failedMessages` | counts |
| `messageIds` | correlation IDs of the sent messages only |
| `failures` | array of `{"index", "error"}`; empty when all were sent |

Status: 200 when every message was sent, 207 when at least one failed, including when every message failed with `failOnError` `false`. Other failures: 400, 404, 503 as for a single send. A batch with at least one sent message publishes `MESSAGES_SENT`.

### Queue statistics

`GET /api/v1/queues/:setupId/:queueName/stats`

Success: 200 with:

| Field | Presence |
|---|---|
| `queueName`, `setupId`, `implementationType`, `healthy` | always |
| `totalMessages`, `pendingMessages`, `processedMessages`, `inFlightMessages`, `deadLetteredMessages` | always |
| `messagesPerSecond`, `avgProcessingTimeMs`, `successRatePercent`, `timestamp` | always |
| `processingTimeP50Ms`, `processingTimeP95Ms`, `processingTimeP99Ms`, `processingTimeSampleCount` | only when this server instance has measured processing time |
| `deliveryLatencyP50Ms`, `deliveryLatencyP95Ms`, `deliveryLatencyP99Ms`, `deliveryLatencySampleCount` | only when measured |
| `firstMessageAt`, `lastMessageAt` | only when known |

Failures: 400 (invalid name), 404 (setup not `ACTIVE`, setup not found, queue not found), 503.

The stats stream (section 11, "Queue stats stream") emits the same object.

### Queue details

`GET /api/v1/queues/:setupId/:queueName`

Success: 200 with `name`, `setup`, `implementationType`, `status`, `messages`, `consumers`, `messageRate`, `consumerRate` (always `0.0`), `statistics`, `config`, `durability` (always `"durable"`), `autoDelete` (always `false`), `createdAt` (epoch milliseconds when this server created or connected the setup binding; not when the database was created), `lastActivity` (time of the response).

- `status` is `error` when the queue reports unhealthy, `paused` when the queue has subscriptions and all are `PAUSED`, otherwise `active`.
- `consumers` counts `ACTIVE` subscriptions on the queue.
- `statistics`: `totalMessages`, `activeConsumers`, `messagesPerSecond`, `avgProcessingTimeMs`.
- `config`: `visibilityTimeoutSeconds`, `maxRetries`, `deadLetterEnabled`, `batchSize`, `pollingIntervalSeconds`, `fifoEnabled`, `deadLetterQueueName`. When the setup holds no stored configuration for the queue, the handler writes `300`, `3`, `true`, `10`, `5`, `false`, `null`.

Failures: 404 (setup not `ACTIVE`, queue not found, setup not found), 503.

### Queue consumers

`GET /api/v1/queues/:setupId/:queueName/consumers`

Lists the subscriptions whose topic is the queue. Success: 200 with `message`, `queueName`, `setupId`, `consumerCount`, `consumers`, `timestamp`. Each consumer: `groupName`, `topic`, `status` (lower-case state: `active`, `paused`, `dead`, `cancelled`, `stopped`), `subscribedAt`, `lastActiveAt`, `lastHeartbeatAt`, `heartbeatIntervalSeconds`, `heartbeatTimeoutSeconds`, `backfillStatus`, `backfillProcessedMessages`, `backfillTotalMessages`.

Failures: 404, 503.

### Queue bindings

`GET /api/v1/queues/:setupId/:queueName/bindings`

Success: 200 with `message`, `queueName`, `setupId`, `bindingCount` (`0`), `bindings` (always an empty array), `timestamp`. Failures: 404, 503.

### Browse messages

`GET /api/v1/queues/:setupId/:queueName/messages`

| Query | Default | Effect |
|---|---|---|
| `count` | `10` | page size |
| `offset` | `0` | rows to skip, counted from the newest message |
| `ackMode` | `manual` | echoed in the response; has no effect |

The handler reads through a queue browser. The native and outbox browsers run a plain `SELECT ... WHERE topic = $1 ORDER BY id DESC LIMIT $2 OFFSET $3`. The page holds messages in every status, newest first. The read does not consume, lock or acknowledge messages.

Success: 200 with `message`, `queueName`, `setupId`, `messageCount`, `ackMode`, `messages`, `timestamp`. Each message: `id`, `payload` (the handler writes `payload.toString()`, so it is a string), `createdAt`, `headers`.

Failures: 404 (setup not `ACTIVE`, queue not found), 503. A `count` or `offset` that is not an integer is not caught by the handler; the router's failure handler answers 500 `{"error", "timestamp"}` (read in code, not run).

### Purge a queue (destructive)

`POST /api/v1/queues/:setupId/:queueName/purge`

Deletes the queue's messages. Success: 200 with `message`, `queueName`, `setupId`, `purgedCount`, `timestamp`. Publishes `QUEUE_PURGED`. Failures: 404, 503.

### Pause a queue

`POST /api/v1/queues/:setupId/:queueName/pause`

Pauses every subscription on the queue's topic except those in `DEAD` or `CANCELLED`. An `ACTIVE` or `STOPPED` subscription becomes `PAUSED`, and a `PAUSED` one stays `PAUSED`. Success: 200 with `message`, `queueName`, `setupId`, `pausedSubscriptions`, `timestamp`. `pausedSubscriptions` counts every pause call made, including subscriptions that were already `PAUSED`.

The handler does not check that the setup is `ACTIVE` or that the queue exists. A queue name with no subscriptions, including an unknown queue, returns 200 with a count of 0.

Failures: 404 `{"error", "timestamp"}` (no subscription service for the setup), 503 (listing or any pause failed; pauses already applied are not reversed).

### Resume a queue

`POST /api/v1/queues/:setupId/:queueName/resume`

Resumes every subscription in `PAUSED`. Other states are left as they are. Success: 200 with `message`, `queueName`, `setupId`, `resumedSubscriptions`, `timestamp`. As for pause, the setup and the queue are not checked, and an unknown queue returns 200 with a count of 0. Failures: 404 `{"error", "timestamp"}`, 503.

### Delete a queue (destructive)

`DELETE /api/v1/queues/:setupId/:queueName`

Purges all messages, calls `close()` on the queue factory, and removes the queue from the setup held in memory. The Future returned by `close()` is discarded. The handler answers 503 only when `close()` throws at once, and it answers 200 before the close completes (register D38). Success: 200 with `message`, `queueName`, `setupId`, `deletedMessages`, `timestamp`. Publishes the management event `queue.deletion.completed` (section 11, "Monitoring WebSocket"). Failures: 404, 503.

The route does not drop the queue table and does not delete the queue's row in `peegeeq_object_registry`. Only the in-memory factory is removed, so a later connect of the setup rebuilds the queue.

`DELETE /api/v1/management/queues/:setupId/:queueName` (section 10) closes the queue without purging messages. It also leaves the table and the registry row in place.

---

## 4. Consumer groups and subscription options

Consumer groups in this section are created with the queue implementation's `createConsumerGroup` and held in the memory of the server process. The key is `<setupId>:<queueName>:<groupName>` and is returned as `groupId`. A group exists only in the server that created it.

Errors have the body `{"error", "timestamp"}`.

### Create a consumer group

`POST /api/v1/queues/:setupId/:queueName/consumer-groups`

The handler reads three fields:

| Field | Type | Required |
|---|---|---|
| `groupName` | string | yes |
| `subscriptionOptions` | object (below) | no |
| `groupFilter` | filter object (below) | no |

`subscriptionOptions`:

| Field | Type | Default |
|---|---|---|
| `startPosition` | `FROM_NOW`, `FROM_BEGINNING`, `FROM_MESSAGE_ID`, `FROM_TIMESTAMP` | `FROM_NOW` |
| `startFromMessageId` | integer | required with `FROM_MESSAGE_ID` |
| `startFromTimestamp` | ISO-8601 instant | required with `FROM_TIMESTAMP` |
| `heartbeatIntervalSeconds` | integer | `60` |
| `heartbeatTimeoutSeconds` | integer, greater than the interval | `300` |

When `subscriptionOptions` is present, the subscription (topic = queue name) is created first. A failed subscription fails the request and no group is created. Without `subscriptionOptions` no subscription is created.

When a subscription already exists for the group, the service reactivates it: `DEAD` and `STOPPED` become `ACTIVE`, `PAUSED` stays `PAUSED`, and the stored heartbeat settings are overwritten with the request's values. Only a `CANCELLED` subscription refuses the call (409).

With `startPosition` `FROM_BEGINNING`, and when the stored start bound is the beginning, the service runs the backfill inside the request. The response is sent after the backfill ends, and a backfill failure fails the request.

Filter object (`groupFilter`, and `messageFilter` on join). `type` is compared without case:

| `type` | Fields |
|---|---|
| `header` | `headerKey`, `headerValue` |
| `headerIn` | `headerKey`, `allowedValues` (array) |
| `region` | `allowedValues` (array) |
| `priority` | `minPriority` (`HIGH`, `NORMAL`, `LOW`) |
| `and` | `filters` (array of filter objects, at least one) |

The `priority` filter compares the message header `priority` with the strings `HIGH` and `NORMAL`. The send endpoint writes `priority` as an integer string (for example `"5"`). A message sent through REST with a priority therefore never matches `minPriority` `HIGH` or `NORMAL`, and `LOW` matches every message (register D39).

Success: 201 with `message`, `groupName`, `setupId`, `queueName`, `groupId`, `maxMembers`, `loadBalancingStrategy`, `sessionTimeout`, `implementationType`, `subscriptionConfigured` (boolean), `timestamp`. `maxMembers`, `loadBalancingStrategy` and `sessionTimeout` come from the queue implementation's group.

Failures:

| Status | Cause |
|---|---|
| 400 | Malformed JSON; `groupName` missing; invalid options or filter |
| 404 | Setup not `ACTIVE`; queue not found |
| 409 | A group with this key exists in this server; the existing subscription is `CANCELLED` |
| 501 | The queue implementation has no subscription store |
| 503 | Any other failure, including an unknown setup |

An empty body is not caught: the handler reads `groupName` from a null object, and the router's failure handler answers 500 (read in code, not run) (register D39).

### List consumer groups

`GET /api/v1/queues/:setupId/:queueName/consumer-groups`

Lists the groups this server holds for the queue. The setup is not checked. Success: 200 with `message`, `setupId`, `queueName`, `groupCount`, `groups`, `timestamp`.

Each group: `groupName`, `groupId`, `setupId`, `queueName`, `topic`, `memberCount`, `consumerIds`, `isActive`, `maxMembers`, `loadBalancingStrategy`, `sessionTimeout`, `createdAt` and `lastActivity` (epoch milliseconds, `0` when unknown), and `stats` (`messagesProcessed`, `messagesFiltered`, `messagesFailed`) when the group reports statistics.

### Get a consumer group

`GET /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName`

Success: 200 with the list fields plus `message`, `members` (array of `{"consumerId", "groupName", "topic"}`), `timestamp`; `stats` also carries `averageProcessingTimeMs`. Failure: 404.

### Delete a consumer group

`DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName`

Removes the group from memory and closes it. The subscription is not cancelled. Success: 200 with `message`, `groupName`, `setupId`, `queueName`, `timestamp`. The response is 200 also when the close fails; the failure is logged. Failure: 404.

### Join a group

`POST /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members`

| Field | Type | Required | Default |
|---|---|---|---|
| `memberName` | string | no | `member-<n>` |
| `messageFilter` | filter object | no | |

The member is identified by `consumerId`, which is `<memberName>-<n>`. The server attaches a placeholder handler to the member. The handler logs each message it receives and completes successfully, so a message delivered to this member counts as processed (read in code, not run).

Success: 201 with `message`, `groupName`, `consumerId`, `memberName`, `topic`, `isActive`, `joinedAt`, `memberCount`, `timestamp`.

Failures: 400 (invalid body or filter; group closed), 404 (group not found), 409 (group full; consumer ID exists).

### Leave a group

`DELETE /api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId`

`memberId` is the path parameter name. Its value is the `consumerId` returned by join.

Success: 200 with `message`, `groupName`, `consumerId`, `memberCount`, `timestamp`. Failures: 404 (group not found; consumer not in group).

### Set subscription options

`POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`

The group must exist in this server's memory (404 otherwise). The body is required (400). Fields: `startPosition`, `startFromMessageId`, `startFromTimestamp`, `heartbeatIntervalSeconds`, `heartbeatTimeoutSeconds`. The subscription topic is the queue name.

- No subscription exists: the request creates it. Omitted fields take the defaults above. With `FROM_BEGINNING` the backfill runs inside the request, as for create.
- A subscription exists: only the heartbeat values the request names change. The state and the stored start bound never change.
  - `startPosition` omitted: no change to the bound.
  - `startPosition` names the stored bound: accepted. `FROM_BEGINNING` names a bound of message ID `1` with no timestamp. `FROM_MESSAGE_ID` names the same message ID. `FROM_TIMESTAMP` names the same instant at microsecond precision.
  - Any other `startPosition` returns 409 and changes nothing. `FROM_NOW` always returns 409, because `FROM_NOW` is resolved to a message ID when the subscription is created.
- Heartbeat values must be positive, and the timeout must exceed the interval (400).

Success: 200 with `setupId`, `queueName`, `groupName`, `subscriptionOptions`, `message`, `timestamp`. `subscriptionOptions` holds the stored values: `startPosition`, `heartbeatIntervalSeconds`, `heartbeatTimeoutSeconds`, and `startFromMessageId` or `startFromTimestamp` when set. The stored bound is reported as `FROM_BEGINNING` (message ID `1`), `FROM_MESSAGE_ID` (other ID), `FROM_TIMESTAMP` (timestamp), or `FROM_NOW` (neither).

Failures: 400, 404 (group not found; queue not found), 409 (bound conflict; subscription state refuses the call), 501 (no subscription store), 503 (other, including a setup without a subscription service).

### Get subscription options

`GET /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`

The group does not need to exist in memory.

- No subscription: 200 with `setupId`, `queueName`, `groupName`, `status` `"NOT_CONFIGURED"`, `subscriptionOptions` (`FROM_NOW`, `60`, `300`), `timestamp`.
- A subscription: 200 with `setupId`, `queueName`, `groupName`, `status` (subscription state), `subscriptionOptions`, `lastHeartbeat`, `createdAt`, `timestamp`.

Failures: 503. For a setup without a subscription service the lookup throws an HTTP 404 exception that the router's failure handler answers as 404 `{"error", "timestamp"}` (read in code, not run).

`SubscriptionManagerFactory` caches one subscription service per setup ID on first lookup and never evicts it. The 404 therefore occurs only for a setup ID that this server has never looked up. After a setup is deleted or detached, the cached service is still returned (register D39).

### Delete subscription options

`DELETE /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`

Cancels the subscription. Every state can be cancelled. The cancel changes no message. A cancelled subscription is terminal and cannot be reactivated. The response is 204 also when the cancel fails; the failure is logged. The unknown-setup case and the service cache behave as for get.

---

## 5. Subscription lifecycle, backfill and partitioned consumption

Path parameters: `setupId`, `topic` (a queue name of the setup), `groupName`. Errors have the body `{"code", "error", "timestamp"}` (section 12). Every route returns 404 `PGQERR0100` when the setup has no subscription service. Create validates the body and `groupName`, and start backfill validates `backfillScope`, before the service lookup; for an unknown setup these validation failures return 400, not 404.

On create, start backfill, partition join, fetch and commit, a body that is not valid JSON is not caught by the handler, and the router's failure handler answers 500 (read in code, not run).

Subscription object:

| Field | Presence |
|---|---|
| `id`, `topic`, `groupName`, `state`, `heartbeatIntervalSeconds`, `heartbeatTimeoutSeconds`, `backfillStatus` | always |
| `subscribedAt`, `lastActiveAt`, `lastHeartbeatAt`, `startFromMessageId`, `startFromTimestamp` | when set |
| `backfillCheckpointId`, `backfillProcessedMessages`, `backfillTotalMessages`, `backfillStartedAt`, `backfillCompletedAt` | when set |

`state` is one of `ACTIVE`, `PAUSED`, `DEAD`, `CANCELLED`, `STOPPED`.

Action responses have the form `{"success": true, "topic", "groupName", "action"}`.

### List subscriptions

`GET /api/v1/setups/:setupId/subscriptions/:topic`

Success: 200, a bare JSON array of subscription objects. Failure: 503 `PGQERR0001`.

### Create a subscription

`POST /api/v1/setups/:setupId/subscriptions/:topic`

| Field | Type | Required | Default |
|---|---|---|---|
| `groupName` | string | yes | |
| `startPosition` | `FROM_NOW`, `FROM_BEGINNING`, `FROM_MESSAGE_ID`, `FROM_TIMESTAMP` | no | `FROM_NOW` |
| `startFromMessageId` | integer | with `FROM_MESSAGE_ID` | |
| `startFromTimestamp` | ISO-8601 instant | with `FROM_TIMESTAMP` | |
| `heartbeatIntervalSeconds` | integer | no | `60` |
| `heartbeatTimeoutSeconds` | integer, greater than the interval | no | `300` |
| `deadAfterMisses` | integer | no | `3` |
| `backfillScope` | `PENDING_ONLY`, `ALL_RETAINED` | no | `PENDING_ONLY` |

The message table of the start bound is taken from the queue named by `topic`. A subscription in a state other than `ACTIVE` is passed to the service again, which reactivates it: `DEAD` and `STOPPED` become `ACTIVE`, `PAUSED` stays `PAUSED`, and the heartbeat settings are overwritten. Only `CANCELLED` refuses the call (409 `PGQERR0052`). With `FROM_BEGINNING`, when the stored start bound is the beginning, the backfill runs inside the request; the response is sent after it ends, and a backfill failure fails the request.

Success: 201 `{"success": true, "topic", "groupName", "action": "subscribed", "subscription": {...}}`.

| Status | Code | Cause |
|---|---|---|
| 400 | `PGQERR0002` | No body; `groupName` missing; invalid option |
| 404 | `PGQERR0150` | `topic` is not a queue of the setup |
| 409 | `PGQERR0051` | An `ACTIVE` subscription exists |
| 409 | `PGQERR0052` | The existing subscription is `CANCELLED` |
| 501 | `PGQERR0057` | The queue has no subscription store |
| 503 | `PGQERR0057` | Any other failure |

### Get a subscription

`GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName`

Success: 200, the subscription object. Failures: 404 `PGQERR0050`, 503 `PGQERR0001`.

### Pause, resume, heartbeat, cancel

| Route | `action` | 503 code |
|---|---|---|
| `POST .../subscriptions/:topic/:groupName/pause` | `paused` | `PGQERR0053` |
| `POST .../subscriptions/:topic/:groupName/resume` | `resumed` | `PGQERR0054` |
| `POST .../subscriptions/:topic/:groupName/heartbeat` | `heartbeat_updated` | `PGQERR0056` |
| `DELETE .../subscriptions/:topic/:groupName` | `cancelled` | `PGQERR0055` |

No body is read. Success: 200. Failures: 404 `PGQERR0050` (subscription not found), 409 `PGQERR0052` (the current state refuses the call; pause, resume and heartbeat only), 503.

No state refuses a cancel. Cancel changes the subscription only: no message is marked processed.

### Force-remove a consumer group

`DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove`

Ends the subscription of a group that is gone for good: an `ACTIVE`, `STOPPED`, `PAUSED` or `DEAD` subscription becomes `CANCELLED`. No message is changed.

Success: 200 with `success`, `topic`, `groupName`, `action` (`force-removed`), `previousStatus`.

Failures: 404 `PGQERR0050` (no subscription), 409 `PGQERR0052` (the subscription is already `CANCELLED`), 503 `PGQERR0062`.

### Start a backfill

`POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`

| Field | Type | Required | Default |
|---|---|---|---|
| `backfillScope` | `PENDING_ONLY`, `ALL_RETAINED` | no | `PENDING_ONLY` |

Success: 200 `{"status", "processedMessages", "scope", "message"}`. The response is sent after the backfill run ends. `status` is one of `COMPLETED`, `CANCELLED`, `ALREADY_COMPLETED`, `SKIPPED` (the stored `backfillStatus` is `IN_PROGRESS`, so another worker holds the backfill).

Failures: 400 `PGQERR0002` (invalid scope), 404 `PGQERR0050`, 409 `PGQERR0061` (illegal state), 501 `PGQERR0058` (backfill not configured), 503 `PGQERR0058`. A backfill that fails after it started returns 503; the stored `backfillStatus` is then `FAILED`, and a later POST resumes it from the last checkpoint.

### Backfill progress

`GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`

Success: 200 with `topic`, `groupName`, `backfillStatus`, and when known `processedMessages`, `totalMessages`, `checkpointId`, `startedAt`, `completedAt`, `percentComplete` (one decimal place; only when the total is greater than 0).

Failures: 404 `PGQERR0050`, 503 `PGQERR0001`.

### Cancel a backfill

`DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`

Success: 200, `action` `backfill_cancelled`. The service updates only a backfill in `IN_PROGRESS` and reports success when none is running, so the route returns 200 also when no backfill was cancelled and for an unknown group (register D39). Failures: 501 `PGQERR0059`, 503 `PGQERR0059`. The 404 branch in the handler cannot be reached.

### Partitioned consumption

These routes work only on a topic whose completion tracking mode is `OFFSET_WATERMARK`. Another mode, or an unknown topic, returns 400 `PGQERR0002`.

Assignment object: `{"topic", "groupName", "partitionKey", "instanceId", "generation"}`.

| Route | Input | Success (200) | Failure codes |
|---|---|---|---|
| `POST .../partitions/join` | body `instanceId` (required) | bare array of assignments; offset rows are created for them | 400 `PGQERR0551` (missing), 400 `PGQERR0002`, 503 `PGQERR0460` |
| `DELETE .../partitions/leave` | query `instanceId` (required) | `{"success", "topic", "groupName", "instanceId", "action": "left"}` | 400 `PGQERR0551`, 400 `PGQERR0002`, 503 `PGQERR0461` |
| `GET .../partitions` | query `instanceId` (required) | bare array of assignments | 400 `PGQERR0551`, 400 `PGQERR0002`, 503 `PGQERR0464` |
| `POST .../partitions/fetch` | body `partitionKey` (required), `generation` (required), `batchSize` (default `10`) | bare array of messages | 400 `PGQERR0551`, 400 `PGQERR0002` (partition not assigned at that generation), 503 `PGQERR0462` |
| `POST .../partitions/commit` | body `partitionKey`, `offset` (integer), `generation`, all required | `{"committed", "topic", "groupName", "partitionKey", "offset"}` | 400 `PGQERR0551`, 400 `PGQERR0002`, 503 `PGQERR0463` |

The prefix `...` is `/api/v1/setups/:setupId/subscriptions/:topic/:groupName`. A fetched message has `id`, `topic`, `messageGroup`, and when set `payload`, `headers`, `correlationId`, `createdAt`.

---

## 6. Webhook subscriptions

Webhook subscriptions are held in server memory and are lost when the server stops. Errors have the body `{"code", "error", "timestamp"}`.

### Create a webhook subscription

`POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions`

The handler reads three fields:

| Field | Type | Required | Use |
|---|---|---|---|
| `webhookUrl` | string | yes | target of each POST |
| `headers` | object | no | added to each POST; values are converted to strings |
| `filters` | object | no | stored with the subscription; never applied and never returned |

The handler creates a queue consumer (`createConsumer`) and subscribes it. A webhook subscription is a consuming reader of the queue, like any other consumer of that queue (read in code, not run).

Success: 201 with `subscriptionId`, `setupId`, `queueName`, `webhookUrl`, `status` (`ACTIVE`), `createdAt`.

| Status | Code | Cause |
|---|---|---|
| 400 | `PGQERR0551` | `webhookUrl` missing |
| 400 | `PGQERR0002` | Body not readable |
| 404 | `PGQERR0102` | Setup not found or not active; queue not found (any failure whose message contains `not found`) |
| 503 | `PGQERR0301` | Any other failure |

The handler stores the subscription in its map before the consumer starts. When the start fails, the entry is not removed. The orphaned entry stays in memory, and the caller receives no ID for it (register D38).

### Delivery

For each message the handler sends `POST <webhookUrl>` with this JSON body:

| Field | Source |
|---|---|
| `subscriptionId` | the subscription |
| `queueName` | the subscription |
| `messageId` | message ID |
| `payload` | the message payload converted to a JSON object |
| `timestamp` | send time, epoch milliseconds |
| `correlationId` | the message header `correlationId`, when present |

Connect timeout is 5 seconds and request timeout 10 seconds. A 2xx answer is a success: `lastSuccessfulDelivery` is set and `consecutiveFailures` resets to 0. Any other answer or error is a failure: `lastDeliveryAttempt` is set, `consecutiveFailures` increases, and at 5 the status becomes `FAILED`. The consumer keeps running after `FAILED`. A later successful delivery resets the counter but does not set the status back to `ACTIVE`; `FAILED` is permanent for the subscription.

The handler makes one POST per delivery and has no retry loop. It returns the failed delivery to the queue consumer. What the queue consumer then does (retry, dead letter) follows the queue's retry settings and is not traced in this guide. A payload that cannot be converted to a JSON object, such as a plain string, fails the delivery (read in code, not run).

### Get a webhook subscription

`GET /api/v1/webhook-subscriptions/:subscriptionId`

Success: 200 with `subscriptionId`, `setupId`, `queueName`, `webhookUrl`, `status`, `createdAt`, `consecutiveFailures`, and when set `lastDeliveryAttempt`, `lastSuccessfulDelivery`. Failure: 404 `PGQERR0300`.

`status` values in the enum are `ACTIVE`, `PAUSED`, `FAILED`, `DELETED`. The handler sets `ACTIVE` at creation, `FAILED` after 5 failures, and `DELETED` immediately before it removes the subscription.

### Delete a webhook subscription

`DELETE /api/v1/webhook-subscriptions/:subscriptionId`

Closes the consumer and removes the subscription. Success: 204, also when the consumer close throws; the failure is logged. Failure: 404 `PGQERR0300`.

---

## 7. Dead letter queue

Path parameter: `setupId`. Every route returns 404 `PGQERR0100` when the setup has no dead letter service. Errors have the body `{"code", "error", "timestamp"}`.

Dead letter message object:

| Field | Type |
|---|---|
| `id` | integer |
| `originalTable` | string |
| `originalId` | integer |
| `topic` | string |
| `payload` | string (the stored payload text) |
| `originalCreatedAt`, `failedAt` | ISO-8601 string |
| `failureReason` | string |
| `retryCount` | integer |
| `headers` | object, when present |
| `correlationId`, `messageGroup` | string, when present |

| Route | Input | Success | Failures |
|---|---|---|---|
| `GET /api/v1/setups/:setupId/deadletter/messages` | query `topic` (optional), `limit` (default `50`), `offset` (default `0`) | 200, a bare JSON array of message objects | 400 `PGQERR0552` (not an integer), 503 `PGQERR0205` |
| `GET /api/v1/setups/:setupId/deadletter/messages/:messageId` | | 200, one message object | 400 `PGQERR0554` (ID not an integer), 404 `PGQERR0200`, 503 `PGQERR0206` |
| `POST /api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess` | body `reason` (optional, default `Reprocessed via REST API`) | 200 `{"success": true, "message": "Message reprocessed successfully"}` | 400 `PGQERR0554`, 404 `PGQERR0200`, 503 `PGQERR0201` |
| `DELETE /api/v1/setups/:setupId/deadletter/messages/:messageId` | query `reason` (optional, default `Deleted via REST API`) | 204 | 400 `PGQERR0554`, 404 `PGQERR0200`, 503 `PGQERR0202` |
| `GET /api/v1/setups/:setupId/deadletter/stats` | | 200 `{"totalMessages", "uniqueTopics", "uniqueTables", "averageRetryCount"}` plus `oldestFailure`, `newestFailure` when known | 503 `PGQERR0204` |
| `POST /api/v1/setups/:setupId/deadletter/cleanup` | query `retentionDays` (default `30`) | 200 `{"success": true, "messagesDeleted", "retentionDays"}` | 400 `PGQERR0552`, 503 `PGQERR0207` |

Without `topic`, the list covers all topics. A query value that is not an integer is refused and never replaced by the default. List and cleanup parse their query integers before the service lookup, so for an unknown setup a non-integer value returns 400, not 404.

- List: `limit` 0 or less, or a negative `offset`, makes the service throw at once; the handler does not catch it and the router's failure handler answers 500 (read in code, not run).
- Cleanup deletes dead letter messages whose `failed_at` is older than `retentionDays`. `retentionDays` 0 or less returns 503 `PGQERR0207`.
- Reprocess: the `reason` is written to the log only. It is not stored.

---

## 8. Event store

Path parameters: `setupId`, `eventStoreName`, `eventId`. Errors have the body `{"error", "timestamp"}`. The setup must be `ACTIVE` and the store must exist; otherwise 404 (`"Setup not found or not active: ..."` or `"Event store not found: ..."`). For an unknown setup ID, store, query, versions, as-of and correction answer 404; get, aggregates, reconcile and stats answer 503.

`causationId` exists only on event store requests and responses.

### Event object

The `event`, `events` and `versions` fields hold event objects serialized from `EventResponse`:

| Field | Content |
|---|---|
| `eventId` | event ID |
| `eventType` | |
| `eventData` | payload |
| `validFrom` | valid time |
| `validTime` | the same value as `validFrom` |
| `validTo` | always `null` |
| `transactionTime` | |
| `correlationId`, `causationId`, `aggregateId` | |
| `version` | integer |
| `metadata` | the event headers |

The three time fields are Java `Instant` values written by Jackson through the Vert.x `DatabindCodec` mapper (read in code, not run). A comment in `EventStoreHandler` states that this API writes event timestamps as epoch-seconds decimals; the output format was not observed (read in code, not run). The append and correction responses write `transactionTime` as an ISO-8601 string.

### Append an event

`POST /api/v1/eventstores/:setupId/:eventStoreName/events`

| Field | Type | Required | Rule |
|---|---|---|---|
| `eventType` | string | yes | |
| `eventData` (alias `payload`) | any JSON value | yes | `eventData` wins when both are set |
| `validFrom` | ISO-8601 instant | no | |
| `validTime` | ISO-8601 instant string | no | must name the same instant as `validFrom` when both are set |
| `aggregateId` | string | no | |
| `correlationId` | string | no | |
| `causationId` | string | no | |
| `metadata` | object | no | values are converted to strings and stored as headers |

There is no `validTo`. Any undeclared property, `validTo` included, returns 400. Without a valid time the event is valid from the time of the request.

Success: 201 with `message`, `eventStoreName`, `setupId`, `eventId`, `eventType`, `version`, `transactionTime`. Failures: 400, 404, 503.

### Query events

`GET /api/v1/eventstores/:setupId/:eventStoreName/events`

| Query | Default | Rule |
|---|---|---|
| `eventType` | | exact filter |
| `aggregateId` | | |
| `correlationId` | | |
| `causationId` | | |
| `validTimeFrom` (alias `fromTime`) | | ISO-8601; inclusive |
| `validTimeTo` (alias `toTime`) | | ISO-8601; inclusive |
| `transactionTimeFrom` | | ISO-8601; inclusive |
| `transactionTimeTo` | | ISO-8601; inclusive |
| `asOfTransactionTime` | | ISO-8601; events with transaction time up to this instant; overrides the transaction-time range |
| `sortOrder` | `TRANSACTION_TIME_ASC` | `VALID_TIME_ASC`, `VALID_TIME_DESC`, `TRANSACTION_TIME_ASC`, `TRANSACTION_TIME_DESC`, `VERSION_ASC`, `VERSION_DESC`; case ignored |
| `includeCorrections` | `true` | any value other than `true` (case ignored) is false |
| `minVersion`, `maxVersion` | | integers |
| `limit` | `100` | clamped to 1..1000 |
| `offset` | `0` | negative becomes 0 |
| `afterTransactionTime` | | keyset cursor; ISO-8601 or epoch-seconds decimal |
| `afterEventId` | | keyset cursor |

`afterTransactionTime` and `afterEventId` must be sent together (400 otherwise). The epoch-seconds form lets a client copy `transactionTime` from a previous event object without conversion. The `EventQueryParams` comment states that the cursor requires `TRANSACTION_TIME_ASC` or `TRANSACTION_TIME_DESC`. A rule violation that the store reports as an illegal argument returns 400. See `PEEGEEQ_EVENT_STORE_GUIDE.md`.

Success: 200 with:

| Field | Content |
|---|---|
| `message`, `eventStoreName`, `setupId` | |
| `eventCount` | events in this page |
| `limit`, `offset` | applied values |
| `totalCount` | count of events matching the query |
| `hasMore` | `offset + eventCount < totalCount` |
| `filters` | the applied filters; always `sortOrder` and `includeCorrections`; `causationId` is applied to the query but never echoed here |
| `events` | event objects |
| `timestamp` | |

Failures: 400 (unparseable value, invalid `sortOrder`, cursor half missing, illegal argument from the store), 404, 503.

### Get an event

`GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId`

Success: 200 `{"message", "eventStoreName", "setupId", "eventId", "event", "timestamp"}`. Failures: 404 (event not found, setup inactive, store not found), 503.

### Event versions

`GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions`

Success: 200 `{"message", "eventStoreName", "setupId", "eventId", "versions": [...], "timestamp"}`. Failures: 404, 503.

### Event as of a transaction time

`GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at?transactionTime=<ISO-8601>`

`transactionTime` is required. Success: 200 `{"message", "eventStoreName", "setupId", "eventId", "transactionTime" (as sent), "event", "timestamp"}`. Failures: 400 (missing or invalid), 404 (no event at that time; setup; store), 503.

### Append a correction

`POST /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections`

| Field | Type | Required | Default |
|---|---|---|---|
| `eventData` | any JSON value | yes | |
| `correctionReason` | string | yes | |
| `eventType` | string | no | the original event's type |
| `validFrom` | ISO-8601 instant | no | time of the request |
| `correlationId` | string | no | |
| `causationId` | string | no | |
| `metadata` | object | no | |

There is no `validTo`. Any undeclared property returns 400. `metadata` is passed to the store only when `correlationId` or `causationId` is also present; without them the handler calls the correction method that takes no headers.

Success: 201 with `message`, `eventStoreName`, `setupId`, `originalEventId`, `correctionEventId`, `version`, `transactionTime`, `correctionReason`.

Failures: 400, 404 (setup; store; original event, or any failure whose message contains `not found`), 503.

### Aggregates

`GET /api/v1/eventstores/:setupId/:eventStoreName/aggregates`

| Query | Default | Rule |
|---|---|---|
| `eventType` | | filter |
| `limit` | `1000` | clamped to 1..1000; not an integer returns 400 |
| `offset` | `0` | not an integer returns 400 |
| `source` | automatic | `log` (event log) or `summary` (summary table); other values return 400 |

Success: 200 with `aggregates` (array of `{"aggregateId", "eventCount", "firstEventTime", "lastEventTime", "eventTypes"}`), `count`, `totalCount`, `truncated`, `limit`, `offset`. A store without the aggregate summary answers 400 when the summary is required. Other failures: 404, 503.

### Reconcile the aggregate summary

`POST /api/v1/eventstores/:setupId/:eventStoreName/aggregate-summary/reconcile?mode=verify|rebuild`

`mode` is required. Success: 200 with `mode` (`VERIFY` or `REBUILD`), `aggregatesChecked`, `missingInSummary`, `staleInSummary`, `orphanedInSummary`, `repaired`, `sampleMismatches`, `timestamp`. Failures: 400 (missing or invalid mode; summary not enabled), 404, 503.

### Statistics

`GET /api/v1/eventstores/:setupId/:eventStoreName/stats`

Success: 200 `{"message", "eventStoreName", "setupId", "stats": {"eventStoreName", "totalEvents", "totalCorrections", "eventCountsByType"}, "timestamp"}`. Failures: 404, 503.

### Delete an event store (destructive)

`DELETE /api/v1/eventstores/:setupId/:eventStoreName`

Closes the store first. Then, in one transaction, drops its table and its `<table>_aggregate_summary` table with `CASCADE` and removes the store from the schema's object registry (read in code, not run). The close is outside the transaction: when the drop fails, the reply is 500 and the store stays listed but closed.

Success: 200 with `message`, `storeId` (`<setupId>-<storeName>`), `setupId`, `storeName`, `note`, `timestamp`. Failures: 404 (setup not found; store not found; the derived table name is not a valid PostgreSQL identifier), 500 (other).

### Event stream

`GET /api/v1/eventstores/:setupId/:eventStoreName/events/stream` is an SSE stream of new events. See section 11, "Event store event stream".

---

## 9. Health, consumer alerts, database telemetry and metrics

### Server health

| Route | Response (200) |
|---|---|
| `GET /health` | `{"status": "UP", "service": "peegeeq-rest-api"}` |
| `GET /api/v1/health` | `{"status": "UP", "timestamp", "uptime", "version": "1.0.0", "build": "Phase-5-Management-UI"}`; `timestamp` and `uptime` are milliseconds; `uptime` counts from server start |

Both values are fixed in the router. Neither route checks a database.

### Setup health

Errors have the body `{"error"}`.

`GET /api/v1/setups/:setupId/health`

Response fields: `healthy`, `status` (`UP` or `DOWN`), `timestamp` (ISO-8601), `componentCount`, `healthyCount`, `degradedCount`, `unhealthyCount`, `components` (object keyed by component name). Status 200 when `status` is `UP`, 503 otherwise; the body is the same in both cases.

Component object: `component`, `state` (`HEALTHY`, `DEGRADED`, `UNHEALTHY`), `timestamp`, and when present `message` and `details` (values converted to strings).

`GET /api/v1/setups/:setupId/health/components` returns 200 with a bare JSON array of component objects.

`GET /api/v1/setups/:setupId/health/components/:name` returns the component object with 200 for `HEALTHY` and `DEGRADED` and 503 for `UNHEALTHY`.

Failures on all three: 404 (no health service for the setup; component not found), 500 (the health read failed).

### Consumer alerts

Errors have the body `{"code", "error", "timestamp"}`: 404 `PGQERR0100` when the setup has no subscription service, 503 `PGQERR0063` on failure.

| Route | Response (200) |
|---|---|
| `GET /api/v1/setups/:setupId/consumer-alerts/dead` | `{"deadSubscriptions": [{"topic", "groupName", "state", "heartbeatTimeoutSeconds", "lastHeartbeatAt"?, "lastActiveAt"?}], "totalDead"}` |
| `GET /api/v1/setups/:setupId/consumer-alerts/summary` | `{"activeCount", "pausedCount", "deadCount", "cancelledCount", "topicCount", "totalCount", "hasDeadSubscriptions"}` |
| `GET /api/v1/setups/:setupId/consumer-alerts/blocked` | `{"blockedStats": [{"topic", "groupName", "blockedPending", "blockedProcessing", "totalBlocked", "oldestBlockedAgeSeconds"?}], "totalBlockedGroups"}` |

`?` marks a field that is present only when known.

### Database telemetry

`GET /api/v1/setups/:setupId/db-telemetry`

Each call opens a one-connection pool to the setup database, reads `pg_stat_*` views and closes the pool. Counters are cumulative since the last statistics reset; the client computes deltas. The handler comment recommends sampling at about 5-second intervals.

Response (200):

| Field | Content |
|---|---|
| `setupId`, `databaseName`, `schema` | |
| `sampledAt` | server clock, epoch milliseconds |
| `tables` | one entry per table in the setup schema |
| `cluster` | database and instance signals |

Table entry: `tableName`, `nTupIns`, `nTupUpd`, `nTupDel`, `nTupHotUpd`, `nLiveTup`, `nDeadTup`, `seqScan`, `vacuumCount`, `autovacuumCount`, `heapBlksHit`, `heapBlksRead`, `heapBytes`, `indexBytes`, `totalBytes`; `idxScan` when the table has indexes; `lastVacuum`, `lastAutovacuum`, `lastAutoanalyze` when they have happened.

Cluster entry: `backendsHoldingXmin`, `locksTotal`, `locksWaiting`, `xidAge`, `notifyQueueUsage` (0 to 1), `walRecords`, `walBytes`, `walLsnBytes`, `checkpointsTimed`, `checkpointsRequested`, `buffersCheckpoint`, `xactCommit`, `xactRollback`, `deadlocks`, `tupReturned`, `tupFetched`, `numbackends`, `blksHit`, `blksRead`; `longestTxnSeconds` when a non-idle session exists on the database. The telemetry query's own session is not idle while it runs, so from the SQL `longestTxnSeconds` is present on every response (read in code, not run).

The cluster query reads `checkpoints_timed`, `checkpoints_req` and `buffers_checkpoint` from `pg_stat_bgwriter`. PostgreSQL 17 moves these counters to `pg_stat_checkpointer`, so on PostgreSQL 17 or later the query fails and the route returns 503 (read in code, not run).

Failures: 404 `PGQERR0100` (setup not found), 503 `PGQERR0501`.

### Prometheus metrics

`GET /metrics` returns the Prometheus scrape text of the server's meter registry, content type `text/plain; version=0.0.4; charset=utf-8`. The registry holds the JVM and system binders (class loader, memory, GC, processor, threads, file descriptors, uptime), the gauge `peegeeq.server.status` (1), the HTTP request timer and counter from section 1, "Request metrics", the monitoring connection gauges `peegeeq.monitoring.connections` (tag `type` `ws` or `sse`) and `peegeeq.monitoring.connections.total`, the timer `peegeeq.monitoring.collection.duration` and the counter `peegeeq.monitoring.errors`. The timer is registered at the first monitoring collection and the counter at the first collection failure, so neither appears in the scrape before then.

---

## 10. Management API

These routes serve the management UI. Errors have the body `{"error", "timestamp"}`.

### Overview

`GET /api/v1/management/overview`

Response (200): `setups` (array), `systemStats`, `recentActivity` (array), `timestamp`. There is no `messagesPerSecond` field.

Setup entry: `setupId`, `status` (`ACTIVE`, or `ERROR` with `error` when the setup's read failed), `totalQueues`, `totalConsumerGroups`, `totalEventStores`, `totalMessages`, `queues` (queue items), `consumerGroups` (group items), `eventStores` (store items). The item shapes are those of the list routes below.

`systemStats`: `totalSetups`, `totalQueues`, `totalConsumerGroups`, `totalEventStores`, `totalMessages`, `activeConnections`, `uptime` (`"<d>d <h>h <m>m"`). `activeConnections` sums the `pg_stat_activity` rows of each setup database, read through a temporary one-connection pool per setup.

`recentActivity`: up to 20 events from the last hour across all event stores, newest first. Each: `id`, `type` (`event`), `action` (event type), `status` (`error` when the event type contains `error`, `fail` or `dead`; `warning` when it contains `warn` or `retry`; else `success`), `details`, `source` (store name), `setup`, `aggregateId`, `timestamp` (transaction time), `validTime`, and `correlationId` when set.

Failure: 503.

### Queues

`GET /api/v1/management/queues?search=<text>`

`search` keeps queues whose name contains the text, case ignored. Response (200): `message`, `queueCount`, `queues`, `timestamp`.

Queue item: `setupId`, `setup`, `queueName`, `name`, `type`, `implementationType`, `status` (`active` or `error` from the queue health check), `messageCount`, `messages`, `consumerCount`, `consumers` (count of `ACTIVE` subscriptions), `messageRate`, `errorRate` (dead-letter rate percent divided by 100), `statistics` (`totalMessages`, `activeConsumers`, `messagesPerSecond`, `avgProcessingTimeMs`), `createdAt` (epoch milliseconds when this server created or connected the setup binding), `updatedAt` (time of the response). Pairs such as `setupId`/`setup` and `messages`/`messageCount` carry the same value.

Failure: 503.

`POST /api/v1/management/queues`

Body: the queue object from section 2, "Create setup" plus the setup ID in `setup` or `setupId`, required. When both are present, `setup` takes precedence. Success: 201 with `message`, `queueName`, `setupId`, `queueId` and `id` (both `<setupId>-<queueName>`), `maxRetries`, `visibilityTimeoutSeconds`, `deadLetterEnabled`, `batchSize`, `pollingIntervalSeconds`, `fifoEnabled`, `deadLetterQueueName`, `timestamp`, and `implementationType` when known. Publishes `QUEUE_CREATED` and the management event `queue.creation.completed`.

Failures: 400 (invalid body or configuration), 404 (setup not found), 409 (implementation type change refused), 503.

`PUT /api/v1/management/queues/:setupId/:queueName`

No queue setting can be changed at run time. For an existing queue the route returns 501 `"Queue configuration cannot be changed at run time; queue '...' in setup '...' is unchanged"`. Other outcomes: 400 (blank path parameter), 404 (setup not `ACTIVE`; queue not found; setup not found), 503.

`DELETE /api/v1/management/queues/:setupId/:queueName`

Closes the queue factory and, when the close succeeds, removes the queue from the setup held in memory. Messages are not purged. The queue table and the queue's `peegeeq_object_registry` row stay, so a later connect of the setup rebuilds the queue. Success: 200 with `message`, `setupId`, `queueName`, `note`, `timestamp`. Publishes `QUEUE_DELETED` and `queue.deletion.completed`. Failures: 400, 404, 503 (a failed close keeps the queue listed).

### Consumer groups

These routes work on database subscriptions, not on the in-memory groups of section 4.

`GET /api/v1/management/consumer-groups`

Response (200): `message`, `groupCount`, `consumerGroups`, `timestamp`. Group item: `name`, `setup`, `queueName`, `implementationType`, `members` (`1` when the subscription is `ACTIVE` and its heartbeat has not timed out, else `0`), `status` (lower-case state), `partition` (`0`), `lag` (`0`), `subscribedAt`, `lastActiveAt`, `lastHeartbeatAt`, `backfillStatus`, `backfillProcessedMessages` and `backfillTotalMessages` (`0` when unknown), `backfillStartedAt`, `backfillCompletedAt`, `createdAt` (when this server created or connected the setup binding). Failure: 503.

`POST /api/v1/management/consumer-groups`

| Field | Type | Required |
|---|---|---|
| `name` | string | yes |
| `setup` | string | yes |
| `queueName` | string | yes |

Creates a subscription with default options. An existing subscription that is not `ACTIVE` is reactivated as described in section 4, "Create a consumer group"; a `CANCELLED` one returns 409. Success: 201 with `message`, `groupName`, `setupId`, `queueName`, `timestamp`. Publishes `consumer-group.creation.completed`. Failures: 400 (missing field; body not JSON), 404 (setup not `ACTIVE`; queue not found), 409 (an `ACTIVE` subscription exists; the subscription refuses the call), 501 (no subscription store), 503 (including an unknown setup).

`DELETE /api/v1/management/consumer-groups/:setupId/:queueName/:groupName`

Cancels the subscription. Success: 200 with `message`, `setupId`, `queueName`, `groupName`, `timestamp`. Publishes `consumer-group.deletion.completed`. Failures: 400, 404 (setup not `ACTIVE`), 503 (including an unknown setup).

`POST .../:setupId/:queueName/:groupName/pause` and `.../resume` (prefix `/api/v1/management/consumer-groups`)

Success: 200 with `message`, `setupId`, `queueName`, `groupName`, `status` (`paused` or `active`), `timestamp`. Failures: 400, 404 (setup not `ACTIVE`), 409 (the state refuses the call, or the failure message contains `invalid`), 503 (including an unknown setup).

`POST /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill`

Starts a `PENDING_ONLY` backfill. Success: 200 `{"status", "processedMessages", "scope", "message"}`. Failures: 400, 404, 409 (failure message contains `invalid`), 503 (including backfill not configured).

### Event stores

`GET /api/v1/management/event-stores`

Response (200): `message`, `eventStoreCount`, `eventStores`, `timestamp`. Store item: `name`, `setup`, `events`, `aggregates`, `corrections` (from the store statistics), and fixed values `biTemporal` (`true`), `retention` (`"365d"`), `status` (`"active"`); `createdAt` (when this server created or connected the setup binding); `lastEvent` (time of the response, not the time of the last event). Failure: 503.

`POST /api/v1/management/event-stores`

Body: the event store object from section 2, "Create setup" plus the setup ID in `setup` or `setupId`, required. When both are present, `setup` takes precedence. Success: 201 with `message`, `eventStoreName`, `setupId`, `eventStoreId`, `storeId` and `id` (all `<setupId>-<eventStoreName>`), `tableName`, `biTemporalEnabled`, `notificationPrefix`, `queryLimit`, `metricsEnabled`, `partitionStrategy`, `timestamp`. Publishes `event-store.creation.completed`. Failures: 400, 404, 503 (any other failure, including a table name that is not a valid PostgreSQL identifier).

`DELETE /api/v1/management/event-stores/:storeId`

`storeId` is `<setupId>-<storeName>`, split at the last hyphen. A store name that contains a hyphen cannot be addressed by this route; use `DELETE /api/v1/eventstores/:setupId/:eventStoreName`. Behaviour and responses are those of section 8, "Delete an event store (destructive)". A `storeId` without a hyphen returns 400.

### Messages

`GET /api/v1/management/messages?setup=<id>&queue=<name>&limit=50&offset=0`

Browses messages without consuming them, with the same browser query as section 3, "Browse messages": every status, newest first, `offset` counted from the newest message. Response (200): `message`, `messageCount`, `messages` (`id`, `payload` as a string, `createdAt`, `headers`), `timestamp`. A request without `setup` or `queue`, for a setup not `ACTIVE`, or for an unknown queue returns 200 with an empty list. Failures: 400 (`limit` or `offset` not an integer), 503 (including an unknown setup ID).

### JVM metrics

`GET /api/v1/management/metrics`

Response (200): `timestamp`, `uptime` (JVM uptime, milliseconds), `memoryUsed`, `memoryTotal`, `memoryMax`, `cpuCores`, `threadsActive`, `totalMessages`, `activeConnections`. Values are cached for 30 seconds. When active setups exist, `totalMessages` and `activeConnections` come from asynchronous reads that complete after the cache refresh returns. The first response after a refresh then carries the previous values, and the first response after start has neither field (read in code, not run).

---

## 11. Streaming

SSE frames have the form `event: <name>` then `data: <json>` then a blank line. Some streams add `id: <value>`. Every message stream in this section reads with a queue browser (`QueueBrowser.tail`). The handlers never call a consumer, an acknowledge or a delete, and the tail never consumes, locks or acknowledges a message (read in code, not run).

### SSE health

`GET /api/v1/sse/health` writes one frame without an event name, `data: {"status":"UP","type":"sse","timestamp":<ms>}`, and ends the response.

### Metrics stream

`GET /api/v1/sse/metrics` and `GET /sse/metrics` (same handler).

| Query | Default | Rule |
|---|---|---|
| `interval` | `5` seconds | clamped to the configured minimum and maximum (`1`..`60` in `rest-server.json`) |
| `heartbeat` | `30` seconds | minimum `10` |

Rejections before the stream starts are plain text: 503 `Max connections reached` (when `maxConnections` monitoring connections are open), 429 `Per-IP connection limit reached` (when `maxConnectionsPerIp` are open from the client address), 400 for a value that is not an integer.

| Event | Data |
|---|---|
| `connected` | `{"connectionId", "timestamp"}` |
| `metrics` | system stats object (below), with `id:` counting from 1 |
| `heartbeat` | `{"timestamp"}` |

The first `metrics` frame is sent at once. The next frame follows after a random delay below `jitterMs` (1000 in `rest-server.json`), then one frame per interval. The server closes a connection with no activity for `idleTimeoutMs` (300000 in `rest-server.json`), checked every 60 seconds. Each metrics frame the server sends resets the activity time, so the idle close fires only when the sends stop.

System stats object (also the `data` of the WebSocket `system_stats` frame):

| Field | Content |
|---|---|
| `type` | `system_stats` |
| `timestamp`, `uptime` (text such as `"5m 3s"`) | |
| `memoryUsed`, `memoryTotal`, `memoryMax`, `cpuCores`, `threadsActive` | JVM values |
| `monitoringSessions` | open monitoring WebSocket and SSE connections |
| `activeSubscriptions` | subscriptions on all queues of all setups, all states |
| `dbPool` | `active`, `idle`, `pending`, `total`, `perSetup` (array of `{"setupId", "active", "idle", "pending", "total"}`) from `pg_stat_activity`; counts every backend on each setup database, including the connection that measures |
| `totalPendingMessages` | sum of pending messages across queues |
| `totalQueues`, `totalConsumerGroups`, `totalEventStores`, `totalSetups` | |
| `subscriptionHealth` | `active`, `paused`, `dead`, `cancelled`, `total`, `topics` |
| `activeBackfills` | array of `{"topic", "groupName", "processedMessages", "totalMessages", "percentComplete"}` for `IN_PROGRESS` backfills |
| `saturation` | per-setup event-loop lag and pool acquire wait; present only when a setup has measured |
| `eventLoopLagMs`, `poolAcquireWaitMs` | worst values across setups; present only when measured |
| `messagesPerSecond` | change of `totalPendingMessages` per second since this connection's previous frame; signed; `0.0` on the first frame |

The collected object is cached for `cacheTtlMs` (5000 in `rest-server.json`). When the list of active setups cannot be read, the object has only `timestamp`, `uptime` (milliseconds), `cpuCores`, `memoryUsed`, `memoryTotal`, `memoryMax` and `error`, plus `messagesPerSecond`. This fallback object is cached for `cacheTtlMs` like a normal one. A failed read of one setup, or of one topic's subscriptions, is left out of the totals without an `error` field, and a failed `pg_stat_activity` read adds zeros to `dbPool`.

### Queue message stream

`GET /api/v1/queues/:setupId/:queueName/messages/stream`

| Event | Data |
|---|---|
| `connected` | `{"type": "connected", "connectionId", "setupId", "queueName", "timestamp"}` |
| `subscribed` | `{"type": "subscribed", "connectionId", "queueName", "timestamp"}`; sent when the tail is in place; the handler comment states that messages published after it are observed (read in code, not run) |
| `message` | `{"type": "message", "connectionId", "messageId", "payload", "timestamp", "enqueuedAt"?, "headers"?}` |
| `heartbeat` | `{"type": "heartbeat", "connectionId", "timestamp"}` every 30 seconds |
| `error` | `{"type": "error", "connectionId", "error", "timestamp"}` |

`timestamp` on a `message` frame is the time the frame was written. `enqueuedAt` is the message creation time; use it for latency. A missing setup or queue is reported as an `error` event after the HTTP 200 headers. After an `error` event the response stays open and `heartbeat` events continue until the client disconnects.

### Queue stats stream

`GET /api/v1/queues/:setupId/:queueName/stats/stream?intervalMs=1000`

`intervalMs` defaults to 1000 and is clamped to 200..10000. Before the stream starts: 400 `{"error"}` (invalid queue name; `intervalMs` not an integer), 404 `{"error"}` (setup not found or not `ACTIVE`; queue not found).

| Event | Data |
|---|---|
| `connected` | `{"connectionId", "setupId", "queueName", "intervalMs", "timestamp"}` |
| `stats` | the queue statistics object (section 3, "Queue statistics") |
| `error` | `{"error": "Stats read failed: ...", "timestamp"}`; the stream then ends |

The first `stats` frame is sent at once. A tick is skipped while the previous read is still running. This stream has no heartbeat event.

### Queue-updates stream

`GET /api/v1/sse/queues/:setupId`

The setup is not checked.

| Event | Data |
|---|---|
| `connected` | `{"type": "connected", "connectionId", "setupId", "timestamp"}` |
| `queue-changed` | `{"event", "setupId", "queueName", "timestamp"}` |
| `heartbeat` | `{"type": "heartbeat", "connectionId", "timestamp"}` every 30 seconds |

`event` values and their sources:

| `event` | Published by |
|---|---|
| `MESSAGE_SENT` | send and publish |
| `MESSAGES_SENT` | batch with at least one sent message |
| `QUEUE_CREATED` | `POST /api/v1/management/queues` |
| `QUEUE_DELETED` | `DELETE /api/v1/management/queues/:setupId/:queueName` |
| `QUEUE_PURGED` | `POST /api/v1/queues/:setupId/:queueName/purge` |

The add-queue routes of section 2 and `DELETE /api/v1/queues/:setupId/:queueName` publish no `queue-changed` event.

### Event store event stream

`GET /api/v1/eventstores/:setupId/:eventStoreName/events/stream`

| Input | Use |
|---|---|
| query `eventType` | filter passed to the store subscription |
| query `aggregateId` | filter passed to the store subscription |
| header `Last-Event-ID` | resume point |

| Event | `id:` | Data |
|---|---|---|
| `connection` | | `{"type": "connection", "connectionId", "setupId", "eventStoreName", "eventTypeFilter", "aggregateIdFilter", "timestamp", "message"}` |
| `subscribed` | | `{"type": "subscribed", "connectionId", "eventTypeFilter", "aggregateIdFilter", "timestamp"}` |
| `event` | event ID | `{"type": "event", "eventId", "eventType", "aggregateId", "payload", "validTime", "transactionTime", "version", "correlationId", "timestamp", "headers"?}`; times are ISO-8601 strings |
| `heartbeat` | | `{"type": "heartbeat", "connectionId", "eventsReceived", "eventsSent", "timestamp"}` every 30 seconds |
| `error` | | `{"type": "error", "connectionId", "error", "timestamp"}` |

With `Last-Event-ID`, the stream drops live events until the event with that ID arrives, drops that event too, and sends the events after it. The handler contains no replay of stored events, so stored events are not replayed (read in code, not run). When the named event never arrives on the live subscription, no `event` frame is sent.

When the client disconnects, the handler removes the connection and ends the response, but it does not cancel the store subscription. After an `error` frame (setup not `ACTIVE`, store missing, subscribe failed) the response stays open and no `heartbeat` follows, because the heartbeat timer starts only after a successful subscribe.

### WebSocket health

`ws://<host>:<port>/ws/health` sends one text frame `{"status": "UP", "type": "websocket", "timestamp"}` and closes.

### Monitoring WebSocket

`ws://<host>:<port>/ws/monitoring`

A connection over `maxConnections` or `maxConnectionsPerIp` is closed without a frame.

Server frames:

| `type` | Fields |
|---|---|
| `welcome` | `connectionId`, `timestamp`, `message` |
| `system_stats` | `data` (system stats object above), `timestamp`; sent at once, then after a random delay below `jitterMs`, then every interval (default `defaultIntervalSeconds`, 5); `configure` applies the same jitter to the new timer |
| `pong` | `timestamp`, `id` (echoed when sent) |
| `configured` | `interval`, `timestamp` |
| `management_event` | `data`: `{"eventName", "entity", "action", "state", "name", "setupId", "timestamp"}` |
| `error` | `message` |

Client frames, keyed by `type`:

| `type` | Fields | Effect |
|---|---|---|
| `ping` | `id` (optional) | `pong` |
| `configure` | `interval` (seconds) | restarts the timer; outside the configured range (1..60) returns `error` |
| `refresh` | | sends `system_stats` at once |

An unknown `type` returns `error` `"Unknown command type: <type>"`. A frame that is not JSON, or has no `type`, returns `error` `"Invalid command format"`.

`management_event` `eventName` values: `queue.creation.completed` (`POST /api/v1/management/queues`), `queue.deletion.completed` (`DELETE /api/v1/management/queues/...` and `DELETE /api/v1/queues/...`), `consumer-group.creation.completed` and `consumer-group.deletion.completed` (management consumer-group routes), `event-store.creation.completed` (`POST /api/v1/management/event-stores`), `event-store.deletion.completed` (both event store delete routes).

The server closes a connection with no activity for `idleTimeoutMs`, checked every 60 seconds. Each `system_stats` frame the server sends, and each client command, resets the activity time, so the idle close fires only when the sends stop.

### Queue WebSocket

`ws://<host>:<port>/ws/queues/:setupId/:queueName`

An invalid queue name closes the socket with code `4000` `"Invalid queue name"`. A WebSocket path not listed in this section is closed. When the handler is not initialized the socket closes with `1011`. A path `/ws/queues/<setupId>` with no queue segment makes the handler throw `ArrayIndexOutOfBoundsException` while it splits the path (register D39); the resulting socket state was not traced (read in code, not run).

A setup that is not `ACTIVE`, or a missing queue, is reported as an `error` frame `"Failed to setup message streaming: ..."` after `welcome`, and the socket stays open.

Server frames:

| `type` | Fields |
|---|---|
| `welcome` | `connectionId`, `setupId`, `queueName`, `timestamp`, `message` |
| `subscribed` | `connectionId`, `queueName`, `timestamp`; sent when the tail is in place |
| `data` | `connectionId`, `messageId` (string), `payload`, `messageType` (always `null`), `timestamp` |
| `pong` | `connectionId`, `timestamp`, `id` (echoed) |
| `subscribed` (reply to `subscribe`) | `connectionId`, `consumerGroup`, `filters`, `timestamp` |
| `unsubscribed` | `connectionId`, `timestamp` |
| `configured` | `connectionId`, `batchSize`, `maxWaitTime`, `timestamp` |
| `error` | `connectionId`, `error`, `timestamp` |

Client frames, keyed by `type`:

| `type` | Fields | Effect |
|---|---|---|
| `ping` | `id` (optional) | `pong` |
| `subscribe` | `consumerGroup`, `filters` | values are stored and echoed; they do not change which messages are sent |
| `unsubscribe` | | marks the connection inactive; later `data` frames are dropped; the tail runs until the socket closes. Sent before the tail is in place, it makes the handler close the browser and the socket |
| `configure` | `batchSize` (clamped 1..100), `maxWaitTime` (milliseconds, clamped 1000..60000) | values are stored and echoed; delivery stays one message per frame |

An unknown `type` returns `error` `"Unknown message type: <type>"`. A frame that is not JSON, or has no `type`, returns `error` `"Invalid message format: ..."`. A binary frame returns `error` `"Binary messages are not supported"`. A failure to open the tail is sent as an `error` frame. This socket has no heartbeat frame.

---

## 12. Error responses

The handler families write different error bodies.

| Body | Written by |
|---|---|
| `{"code", "error", "timestamp", "details"?}` (`ErrorResponse`) | subscription lifecycle, dead letter queue, webhook, consumer alert and database telemetry handlers |
| `{"error", "statusCode", "timestamp"}` | setup handler (section 2) |
| `{"error", "timestamp"}` | queue, consumer group, event store and management handlers; the router failure handler |
| `{"error"}` | setup health handler; invalid queue name; stats stream rejections |
| plain text | metrics SSE rejections (503, 429, 400) |

`timestamp` is epoch milliseconds. `details` is present only when the error carries details.

Examples, one per JSON form:

```json
{"code":"PGQERR0100","error":"Setup not found: demo","timestamp":1700000000000}
```

```json
{"error":"Setup not found: demo","statusCode":404,"timestamp":1700000000000}
```

```json
{"error":"Queue not found: orders","timestamp":1700000000000}
```

### Router failure handler

An exception that a handler does not catch reaches the router's failure handler. It answers with the status set on the request context, or 500, and the body `{"error": <exception message>, "timestamp"}`. When the response was already ended it writes nothing. An exception that carries an HTTP status, such as the 404 thrown when a setup has no subscription service in the subscription options routes (section 4), keeps that status (read in code, not run).

A path that matches no route is answered by Vert.x Web itself; this guide does not describe that response (read in code, not run).

### Status codes

| Status | Use |
|---|---|
| 200, 201, 204, 207 | success; 207 only for a batch with at least one failed message (`failOnError` `false`) |
| 400 | invalid body, field, parameter or name |
| 404 | setup, queue, store, event, subscription, group, member, message or component not found; setup not `ACTIVE` on most routes |
| 409 | conflict: setup exists, implementation change refused, subscription exists, start bound conflict, state refuses the call, group full |
| 429 | metrics SSE per-IP limit |
| 500 | health read failure; event store delete failure; uncaught exception |
| 501 | update queue; queue without a subscription store; backfill not configured |
| 503 | a dependency failed (database, service); setup health not `UP`; component `UNHEALTHY`; metrics SSE connection limit |

### Error codes used by the REST handlers

| Code | Meaning |
|---|---|
| `PGQERR0001` | internal error |
| `PGQERR0002` | invalid request |
| `PGQERR0050` | subscription not found |
| `PGQERR0051` | subscription already exists |
| `PGQERR0052` | subscription state refuses the call |
| `PGQERR0053`..`PGQERR0057` | pause, resume, cancel, heartbeat, create failed |
| `PGQERR0058`, `PGQERR0059`, `PGQERR0061` | backfill start failed, cancel failed, invalid state |
| `PGQERR0062` | force remove failed |
| `PGQERR0063` | consumer alerts failed |
| `PGQERR0100` | setup not found |
| `PGQERR0102` | setup not found or inactive (webhook create) |
| `PGQERR0150` | queue not found |
| `PGQERR0200`..`PGQERR0207` | dead letter: not found, reprocess, delete, purge (no route uses it), stats, list, get, cleanup failed |
| `PGQERR0300`, `PGQERR0301` | webhook not found, create failed |
| `PGQERR0460`..`PGQERR0464` | partition join, leave, fetch, commit, assignment failed |
| `PGQERR0501` | database query failed (telemetry) |
| `PGQERR0551`, `PGQERR0552`, `PGQERR0554` | missing required field, invalid field value, invalid message ID |

---

## 13. Route index

The router registers 107 HTTP routes. The WebSocket handler routes 3 paths. Inline handlers are lambdas in `PeeGeeQRestServer.createRouter`.

| Method | Path | Handler | Section |
|---|---|---|---|
| GET | `/api/v1/health` | inline | 9, "Server health" |
| GET | `/api/v1/sse/health` | inline | 11, "SSE health" |
| GET | `/api/v1/sse/metrics` | `SystemMonitoringHandler.handleSSEMetrics` | 11, "Metrics stream" |
| GET | `/sse/metrics` | `SystemMonitoringHandler.handleSSEMetrics` | 11, "Metrics stream" |
| POST | `/api/v1/database-setup/create` | `DatabaseSetupHandler.createSetup` | 2, "Create setup" |
| POST | `/api/v1/database-setup/connect` | `DatabaseSetupHandler.connectToExistingSetup` | 2, "Connect to an existing setup" |
| DELETE | `/api/v1/database-setup/:setupId` | `DatabaseSetupHandler.destroySetup` | 2, "Delete setup (non-destructive)" |
| GET | `/api/v1/database-setup/:setupId/status` | `DatabaseSetupHandler.getSetupStatus` | 2, "Setup status" |
| POST | `/api/v1/database-setup/:setupId/queues` | `DatabaseSetupHandler.addQueue` | 2, "Add a queue" |
| GET | `/api/v1/setups` | `DatabaseSetupHandler.listSetups` | 2, "List setups" |
| POST | `/api/v1/setups` | `DatabaseSetupHandler.createSetup` | 2, "Create setup" |
| GET | `/api/v1/setups/:setupId` | `DatabaseSetupHandler.getSetupDetails` | 2, "Setup details" |
| GET | `/api/v1/setups/:setupId/status` | `DatabaseSetupHandler.getSetupStatus` | 2, "Setup status" |
| DELETE | `/api/v1/setups/:setupId` | `DatabaseSetupHandler.deleteSetup` | 2, "Delete setup (non-destructive)" |
| POST | `/api/v1/setups/:setupId/detach` | `DatabaseSetupHandler.detachSetup` | 2, "Detach setup (non-destructive)" |
| POST | `/api/v1/setups/:setupId/database/drop` | `DatabaseSetupHandler.dropSetupDatabase` | 2, "Drop setup database (destructive, guarded)" |
| GET | `/api/v1/setups/:setupId/queues` | `DatabaseSetupHandler.listQueues` | 2, "List queues of a setup" |
| POST | `/api/v1/setups/:setupId/queues` | `DatabaseSetupHandler.addQueue` | 2, "Add a queue" |
| GET | `/api/v1/setups/:setupId/eventstores` | `DatabaseSetupHandler.listEventStores` | 2, "List event stores of a setup" |
| POST | `/api/v1/setups/:setupId/eventstores` | `DatabaseSetupHandler.addEventStore` | 2, "Add an event store" |
| POST | `/api/v1/queues/:setupId/:queueName/messages` | `QueueHandler.sendMessage` | 3, "Send a message" |
| POST | `/api/v1/queues/:setupId/:queueName/messages/batch` | `QueueHandler.sendMessages` | 3, "Send a batch" |
| GET | `/api/v1/queues/:setupId/:queueName/stats` | `QueueHandler.getQueueStats` | 3, "Queue statistics" |
| POST | `/api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions` | `WebhookSubscriptionHandler.createSubscription` | 6, "Create a webhook subscription" |
| GET | `/api/v1/webhook-subscriptions/:subscriptionId` | `WebhookSubscriptionHandler.getSubscription` | 6, "Get a webhook subscription" |
| DELETE | `/api/v1/webhook-subscriptions/:subscriptionId` | `WebhookSubscriptionHandler.deleteSubscription` | 6, "Delete a webhook subscription" |
| GET | `/api/v1/queues/:setupId/:queueName/messages/stream` | `ServerSentEventsHandler.handleQueueMessageStream` | 11, "Queue message stream" |
| GET | `/api/v1/sse/queues/:setupId` | `ServerSentEventsHandler.handleQueueUpdates` | 11, "Queue-updates stream" |
| GET | `/api/v1/queues/:setupId/:queueName/stats/stream` | `ServerSentEventsHandler.handleQueueStatsStream` | 11, "Queue stats stream" |
| POST | `/api/v1/queues/:setupId/:queueName/consumer-groups` | `ConsumerGroupHandler.createConsumerGroup` | 4, "Create a consumer group" |
| GET | `/api/v1/queues/:setupId/:queueName/consumer-groups` | `ConsumerGroupHandler.listConsumerGroups` | 4, "List consumer groups" |
| GET | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `ConsumerGroupHandler.getConsumerGroup` | 4, "Get a consumer group" |
| DELETE | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | `ConsumerGroupHandler.deleteConsumerGroup` | 4, "Delete a consumer group" |
| POST | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members` | `ConsumerGroupHandler.joinConsumerGroup` | 4, "Join a group" |
| DELETE | `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId` | `ConsumerGroupHandler.leaveConsumerGroup` | 4, "Leave a group" |
| POST | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `ConsumerGroupHandler.updateSubscriptionOptions` | 4, "Set subscription options" |
| GET | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `ConsumerGroupHandler.getSubscriptionOptions` | 4, "Get subscription options" |
| DELETE | `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | `ConsumerGroupHandler.deleteSubscriptionOptions` | 4, "Delete subscription options" |
| GET | `/api/v1/management/overview` | `ManagementApiHandler.getSystemOverview` | 10, "Overview" |
| GET | `/api/v1/management/queues` | `ManagementApiHandler.getQueues` | 10, "Queues" |
| POST | `/api/v1/management/queues` | `ManagementApiHandler.createQueue` | 10, "Queues" |
| PUT | `/api/v1/management/queues/:setupId/:queueName` | `ManagementApiHandler.updateQueue` | 10, "Queues" |
| DELETE | `/api/v1/management/queues/:setupId/:queueName` | `ManagementApiHandler.deleteQueue` | 10, "Queues" |
| GET | `/api/v1/management/consumer-groups` | `ManagementApiHandler.getConsumerGroups` | 10, "Consumer groups" |
| POST | `/api/v1/management/consumer-groups` | `ManagementApiHandler.createConsumerGroup` | 10, "Consumer groups" |
| DELETE | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName` | `ManagementApiHandler.deleteConsumerGroup` | 10, "Consumer groups" |
| POST | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/pause` | `ManagementApiHandler.pauseConsumerGroup` | 10, "Consumer groups" |
| POST | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/resume` | `ManagementApiHandler.resumeConsumerGroup` | 10, "Consumer groups" |
| POST | `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill` | `ManagementApiHandler.backfillConsumerGroup` | 10, "Consumer groups" |
| GET | `/api/v1/management/event-stores` | `ManagementApiHandler.getEventStores` | 10, "Event stores" |
| POST | `/api/v1/management/event-stores` | `ManagementApiHandler.createEventStore` | 10, "Event stores" |
| DELETE | `/api/v1/management/event-stores/:storeId` | `ManagementApiHandler.deleteEventStore` | 10, "Event stores" |
| GET | `/api/v1/management/messages` | `ManagementApiHandler.getMessages` | 10, "Messages" |
| GET | `/api/v1/management/metrics` | `ManagementApiHandler.getMetrics` | 10, "JVM metrics" |
| GET | `/api/v1/queues/:setupId/:queueName` | `ManagementApiHandler.getQueueDetails` | 3, "Queue details" |
| GET | `/api/v1/queues/:setupId/:queueName/consumers` | `ManagementApiHandler.getQueueConsumers` | 3, "Queue consumers" |
| GET | `/api/v1/queues/:setupId/:queueName/bindings` | `ManagementApiHandler.getQueueBindings` | 3, "Queue bindings" |
| GET | `/api/v1/queues/:setupId/:queueName/messages` | `ManagementApiHandler.getQueueMessages` | 3, "Browse messages" |
| POST | `/api/v1/queues/:setupId/:queueName/publish` | `QueueHandler.sendMessage` | 3, "Send a message" |
| POST | `/api/v1/queues/:setupId/:queueName/purge` | `ManagementApiHandler.purgeQueue` | 3, "Purge a queue (destructive)" |
| POST | `/api/v1/queues/:setupId/:queueName/pause` | `ManagementApiHandler.pauseQueue` | 3, "Pause a queue" |
| POST | `/api/v1/queues/:setupId/:queueName/resume` | `ManagementApiHandler.resumeQueue` | 3, "Resume a queue" |
| DELETE | `/api/v1/queues/:setupId/:queueName` | `ManagementApiHandler.deleteQueueByName` | 3, "Delete a queue (destructive)" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/stream` | `EventStoreHandler.handleEventStream` | 11, "Event store event stream" |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/events` | `EventStoreHandler.storeEvent` | 8, "Append an event" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events` | `EventStoreHandler.queryEvents` | 8, "Query events" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId` | `EventStoreHandler.getEvent` | 8, "Get an event" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions` | `EventStoreHandler.getAllVersions` | 8, "Event versions" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at` | `EventStoreHandler.getAsOfTransactionTime` | 8, "Event as of a transaction time" |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections` | `EventStoreHandler.appendCorrection` | 8, "Append a correction" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/aggregates` | `EventStoreHandler.getUniqueAggregates` | 8, "Aggregates" |
| POST | `/api/v1/eventstores/:setupId/:eventStoreName/aggregate-summary/reconcile` | `EventStoreHandler.reconcileAggregateSummary` | 8, "Reconcile the aggregate summary" |
| GET | `/api/v1/eventstores/:setupId/:eventStoreName/stats` | `EventStoreHandler.getStats` | 8, "Statistics" |
| DELETE | `/api/v1/eventstores/:setupId/:eventStoreName` | `ManagementApiHandler.deleteEventStoreByName` | 8, "Delete an event store (destructive)" |
| GET | `/api/v1/setups/:setupId/deadletter/messages` | `DeadLetterHandler.listMessages` | 7 |
| GET | `/api/v1/setups/:setupId/deadletter/messages/:messageId` | `DeadLetterHandler.getMessage` | 7 |
| POST | `/api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess` | `DeadLetterHandler.reprocessMessage` | 7 |
| DELETE | `/api/v1/setups/:setupId/deadletter/messages/:messageId` | `DeadLetterHandler.deleteMessage` | 7 |
| GET | `/api/v1/setups/:setupId/deadletter/stats` | `DeadLetterHandler.getStats` | 7 |
| POST | `/api/v1/setups/:setupId/deadletter/cleanup` | `DeadLetterHandler.cleanup` | 7 |
| GET | `/api/v1/setups/:setupId/subscriptions/:topic` | `SubscriptionHandler.listSubscriptions` | 5, "List subscriptions" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic` | `SubscriptionHandler.createSubscription` | 5, "Create a subscription" |
| GET | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | `SubscriptionHandler.getSubscription` | 5, "Get a subscription" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause` | `SubscriptionHandler.pauseSubscription` | 5, "Pause, resume, heartbeat, cancel" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume` | `SubscriptionHandler.resumeSubscription` | 5, "Pause, resume, heartbeat, cancel" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat` | `SubscriptionHandler.updateHeartbeat` | 5, "Pause, resume, heartbeat, cancel" |
| DELETE | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | `SubscriptionHandler.cancelSubscription` | 5, "Pause, resume, heartbeat, cancel" |
| DELETE | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove` | `SubscriptionHandler.forceRemoveConsumerGroup` | 5, "Force-remove a consumer group" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill` | `SubscriptionHandler.startBackfill` | 5, "Start a backfill" |
| GET | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill` | `SubscriptionHandler.getBackfillProgress` | 5, "Backfill progress" |
| DELETE | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill` | `SubscriptionHandler.cancelBackfill` | 5, "Cancel a backfill" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/join` | `SubscriptionHandler.joinPartitionedGroup` | 5, "Partitioned consumption" |
| DELETE | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/leave` | `SubscriptionHandler.leavePartitionedGroup` | 5, "Partitioned consumption" |
| GET | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions` | `SubscriptionHandler.getPartitionAssignments` | 5, "Partitioned consumption" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/fetch` | `SubscriptionHandler.fetchPartitioned` | 5, "Partitioned consumption" |
| POST | `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/commit` | `SubscriptionHandler.commitPartitionedOffset` | 5, "Partitioned consumption" |
| GET | `/api/v1/setups/:setupId/db-telemetry` | `DatabaseTelemetryHandler.getDatabaseTelemetry` | 9, "Database telemetry" |
| GET | `/api/v1/setups/:setupId/consumer-alerts/dead` | `ConsumerAlertHandler.listDeadSubscriptions` | 9, "Consumer alerts" |
| GET | `/api/v1/setups/:setupId/consumer-alerts/summary` | `ConsumerAlertHandler.getHealthSummary` | 9, "Consumer alerts" |
| GET | `/api/v1/setups/:setupId/consumer-alerts/blocked` | `ConsumerAlertHandler.getBlockedStats` | 9, "Consumer alerts" |
| GET | `/api/v1/setups/:setupId/health` | `HealthHandler.getOverallHealth` | 9, "Setup health" |
| GET | `/api/v1/setups/:setupId/health/components` | `HealthHandler.listComponentHealth` | 9, "Setup health" |
| GET | `/api/v1/setups/:setupId/health/components/:name` | `HealthHandler.getComponentHealth` | 9, "Setup health" |
| any | `/ui/*` | `StaticHandler` (`webroot`, index `index.html`) | 13, "Static UI" |
| any | `/` | inline: 302 redirect to `/ui/` | 13, "Static UI" |
| GET | `/health` | inline | 9, "Server health" |
| GET | `/metrics` | inline | 9, "Prometheus metrics" |
| WS | `/ws/queues/:setupId/:queueName` | `WebSocketHandler.handleQueueStream` | 11, "Queue WebSocket" |
| WS | `/ws/monitoring` | `SystemMonitoringHandler.handleWebSocketMonitoring` | 11, "Monitoring WebSocket" |
| WS | `/ws/health` | inline in `PeeGeeQRestServer.routeWebSocket` | 11, "WebSocket health" |

### Static UI

`/ui/*` serves the management UI files from the `webroot` resource directory, with `index.html` as the index page (read in code, not run). `/` answers 302 with `location: /ui/`.

---

## 14. Not provided

- No polling, next-message, acknowledge or negative-acknowledge endpoint. Messages are consumed by queue consumers, webhook subscriptions, consumer group members and the partitioned fetch routes.
- No route `GET /api/v1/queues/:setupId/:queueName/stream` exists. The non-destructive message stream (section 11, "Queue message stream") is `GET /api/v1/queues/:setupId/:queueName/messages/stream`.
- No route `POST /api/v1/database-setup/:setupId/eventstores` exists. Use `POST /api/v1/setups/:setupId/eventstores`.
- No authentication or authorization.
- No route lists or updates webhook subscriptions.
- No route changes a queue's configuration; `PUT /api/v1/management/queues/:setupId/:queueName` answers 501.
- No route updates or deletes a single event. Corrections append new versions.
- The router serves no OpenAPI document.
