# PeeGeeQ Service Manager
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 1.0

`peegeeq-service-manager` is an optional Vert.x HTTP service. It keeps a registry of PeeGeeQ REST
server instances in HashiCorp Consul, and it combines the management API responses of those
instances into one response. It is federation and discovery. It does not take part in database
failover; `docs/PEEGEEQ_OPERATIONS_GUIDE.md` describes the PostgreSQL
failover boundary.

"(read in code, not run)" marks runtime behaviour that was read in the source and not observed.

## How it works

| Item | Behaviour |
|---|---|
| Registry | Consul. Each PeeGeeQ instance is a Consul service named `peegeeq-instance`. The instance ID is the Consul service ID. |
| Tags | `version=<v>`, `environment=<e>`, `region=<r>`, and one `key=value` tag per metadata entry. |
| Instance health | The registration asks Consul to call `http://<host>:<port>/health` on the instance every 10 seconds (read in code, not run). For an instance registered with environment `test` the service registers no Consul check at all; for environment `test-unhealthy` it registers a check that always fails. |
| Discovery | A Consul health query for `peegeeq-instance` that returns passing instances only. |
| Cache | Instances are cached in memory. A timer refreshes the cache from Consul every 30 seconds. |
| Who registers instances | Nothing in PeeGeeQ registers itself. An operator or a deployment script calls `POST /api/v1/instances/register` for each instance. |

The PeeGeeQ REST server's `/health` returns a fixed `UP` body without reading its database
(`docs/PEEGEEQ_REST_API_GUIDE.md`). A passing Consul check therefore means the
instance's HTTP server answers, not that its database is reachable (read in code, not run).

The service also registers itself in Consul as `peegeeq-service-manager`, with the ID
`peegeeq-service-manager-<start time in ms>`, the address `localhost`, and an HTTP check on
`http://localhost:<port>/health` every 10 seconds. It does not deregister itself when it stops (D43). When
Consul cannot be reached at startup, the service logs a warning and starts without the self
registration (read in code, not run).

## Running it

Build the module and its upstream modules, then start it with the exec plugin:

```powershell
mvn clean install -DskipTests -pl ":peegeeq-service-manager" -am 2>&1 | Tee-Object -FilePath logs\service-manager-build.log
mvn exec:java -pl ":peegeeq-service-manager" 2>&1 | Tee-Object -FilePath logs\service-manager-run.log
```

The plugin configuration in the module `pom.xml` runs `PeeGeeQServiceManager.main` with the
argument `9090`.

`main` reads positional arguments only:

```text
<port> [consulHost] [consulPort]
```

| Argument | Default |
|---|---|
| `port` | `9090` |
| `consulHost` | `localhost` |
| `consulPort` | `8500` |

There are no system properties, environment variables or configuration files. A port argument that
is not a number fails with `NumberFormatException` before the service starts. `ServiceManagerConfig`
exists in the module, but `main` and the verticle do not use it.

To embed the service, deploy the verticle with its three-argument constructor:

```java
vertx.deployVerticle(new PeeGeeQServiceManager(9090, consulHost, consulPort))
    .onSuccess(id -> logger.info("Service manager deployed: {}", id))
    .onFailure(error -> logger.error("Service manager failed to start", error));
```

`ServiceDiscoveryExampleTest` in `peegeeq-examples` deploys it this way against a Consul
Testcontainer.

## Routes

The router registers 15 API routes, plus `/ui/*` and `/`.

| Method and path | Handler |
|---|---|
| `GET /health` | Service status |
| `POST /api/v1/instances/register` | `InstanceRegistrationHandler.registerInstance` |
| `DELETE /api/v1/instances/:instanceId/deregister` | `InstanceRegistrationHandler.deregisterInstance` |
| `GET /api/v1/instances` | `InstanceRegistrationHandler.listInstances` |
| `GET /api/v1/instances/:instanceId/health` | `InstanceRegistrationHandler.checkInstanceHealth` |
| `GET /api/v1/federated/overview` | `FederatedManagementHandler.getFederatedOverview` |
| `GET /api/v1/federated/queues` | `FederatedManagementHandler.getFederatedQueues` |
| `GET /api/v1/federated/consumer-groups` | `FederatedManagementHandler.getFederatedConsumerGroups` |
| `GET /api/v1/federated/event-stores` | `FederatedManagementHandler.getFederatedEventStores` |
| `GET /api/v1/federated/metrics` | `FederatedManagementHandler.getFederatedMetrics` |
| `GET /api/v1/instances/:instanceId/overview` | Passes through the instance's `/api/v1/management/overview` |
| `GET /api/v1/instances/:instanceId/queues` | Passes through `/api/v1/management/queues` |
| `GET /api/v1/instances/:instanceId/consumer-groups` | Passes through `/api/v1/management/consumer-groups` |
| `GET /api/v1/instances/:instanceId/event-stores` | Passes through `/api/v1/management/event-stores` |
| `GET /api/v1/instances/:instanceId/metrics` | Passes through `/api/v1/management/metrics` |
| `/ui/*` | Static files from a `webroot` directory. The module contains no `webroot` (D43). |
| `/` | 302 redirect to `/ui/` |

There is no `GET /api/v1/instances/:instanceId` route, no `/metrics` route and no WebSocket.

### Errors

Every handler error has this body, with the HTTP status repeated in `status`:

```json
{ "error": "<message>", "timestamp": "<ISO-8601 instant>", "status": 400 }
```

The handlers send 400 and 500 only. A path that matches no route, or a method that the path does
not accept, gets the Vert.x Web default 404 or 405 response (Vert.x Web behaviour, not run).

### `GET /health`

```json
{ "status": "UP", "service": "peegeeq-service-manager", "timestamp": 1764000000000, "consul": "registered" }
```

`timestamp` is epoch milliseconds. `consul` is `registered` only after Consul accepted the self
registration, otherwise `not-registered`.

### `POST /api/v1/instances/register`

Request body:

| Field | Rule |
|---|---|
| `instanceId` | Required, not blank. |
| `host` | Required, not blank. Consul must be able to reach it for the health check. |
| `port` | Required, 1 to 65535. |
| `version` | Optional, default `unknown`. |
| `environment` | Optional, default `default`. |
| `region` | Optional, default `default`. |
| `metadata` | Optional JSON object. Each value is stored as a string tag. |

```json
{
  "instanceId": "peegeeq-a",
  "host": "10.1.2.3",
  "port": 8088,
  "version": "1.0",
  "environment": "prod",
  "region": "eu-west-1",
  "metadata": { "team": "payments" }
}
```

201 response:

```json
{ "message": "Instance registered successfully", "instanceId": "peegeeq-a", "status": "registered", "timestamp": "<ISO-8601>" }
```

A missing or invalid field, or a body that is not JSON, returns 400. A Consul failure returns 500.

### `DELETE /api/v1/instances/:instanceId/deregister`

200 response:

```json
{ "message": "Instance unregistered successfully", "instanceId": "peegeeq-a", "status": "deregistered", "timestamp": "<ISO-8601>" }
```

A Consul failure returns 500.

### `GET /api/v1/instances`

Optional query parameters: `environment`, `region`, `status`. `environment` and `region` are exact
matches. `status` is compared without regard to case.

```json
{
  "message": "Instances retrieved successfully",
  "instanceCount": 1,
  "totalInstances": 1,
  "instances": [
    {
      "instanceId": "peegeeq-a",
      "host": "10.1.2.3",
      "port": 8088,
      "version": "1.0",
      "environment": "prod",
      "region": "eu-west-1",
      "status": "healthy",
      "baseUrl": "http://10.1.2.3:8088",
      "managementUrl": "http://10.1.2.3:8088/api/v1/management",
      "registeredAt": null,
      "lastHealthCheck": null,
      "metadata": { "team": "payments" }
    }
  ],
  "timestamp": "<ISO-8601>",
  "filteredByEnvironment": "prod"
}
```

`filteredByEnvironment`, `filteredByRegion` and `filteredByStatus` appear only when the parameter was
sent. `metadata` appears only when it is not empty. Read in code, not run:

- The list comes from Consul's passing instances, so every `status` is `healthy`, and
  `status=unhealthy` returns an empty list.
- An instance with a check appears only after its first Consul check has passed (Consul
  behaviour, not checked).
- `registeredAt` and `lastHealthCheck` are always null, because the entries are rebuilt from Consul (D43).

### `GET /api/v1/instances/:instanceId/health`

```json
{ "instanceId": "peegeeq-a", "status": "healthy", "healthy": true, "timestamp": "<ISO-8601>" }
```

The response is always 200. `status` is `healthy` when the ID is among Consul's passing instances,
`unhealthy` when it is not (an unknown ID included), and `unknown` when the Consul query fails.

### Federated endpoints

Each federated endpoint discovers the passing instances, calls the matching
`/api/v1/management/...` endpoint on every one of them with a 10-second timeout, and combines the
results. Read in code, not run:

- An instance whose call fails at the transport level (connection refused, timeout, a body that is
  not JSON) is left out of the combined data.
- An instance that answers with an error body fails the whole request. PeeGeeQ error bodies have a
  String `error` field, and the aggregators read that field with `getBoolean("error", false)`, which
  throws; the endpoint then returns 500 (D43). An empty response body has the same effect.
- `instanceCount` counts the instances that were asked, including those whose call failed.
  `overview.instanceDetails` lists every instance that was asked, with `status` `healthy`, including
  those whose call failed.
- With no passing instance the response is 200 with `"message": "No healthy instances available"`
  and `timestamp`. `overview` adds `instanceCount: 0` and an empty `aggregatedData`. `queues`,
  `consumer-groups` and `event-stores` add `queueCount`, `groupCount` or `eventStoreCount` set to 0
  and an empty array. `metrics` adds `metrics: {}`.

| Endpoint | Response fields | Read from each instance |
|---|---|---|
| `overview` | `message`, `instanceCount`, `aggregatedData` (`totalInstances`, `totalQueues`, `totalConsumerGroups`, `totalEventStores`, `totalMessages`, `activeConnections`), `instanceDetails` (`instanceId`, `host`, `port`, `environment`, `region`, `status`), `timestamp` | `systemStats` of `/api/v1/management/overview` |
| `queues` | `message`, `instanceCount`, `queueCount`, `queues`, `timestamp` | `queues` array |
| `consumer-groups` | `message`, `instanceCount`, `groupCount`, `consumerGroups`, `timestamp` | `consumerGroups` array |
| `event-stores` | `message`, `instanceCount`, `eventStoreCount`, `eventStores`, `timestamp` | `eventStores` array |
| `metrics` | `message`, `instanceCount`, `metrics` with `aggregated` (`totalMemoryUsed`, `totalMemoryTotal`, `totalMemoryMax`, `totalCpuCores`, `totalThreadsActive`, `totalActiveConnections`, `totalMessages`, `averageMemoryUsage`) and `instances`, `timestamp` | Top-level fields of `/api/v1/management/metrics` |

Each item in `queues`, `consumerGroups` and `eventStores` is the instance's own object with five
fields added: `sourceInstanceId`, `sourceHost`, `sourcePort`, `sourceEnvironment` and
`sourceRegion`. In `metrics.instances`, an instance whose call failed at the transport level appears as
`{ "instanceId": ..., "error": true, "message": ... }`. The shapes of the instance objects are
owned by `docs/PEEGEEQ_REST_API_GUIDE.md`.

### Instance-specific endpoints

`GET /api/v1/instances/:instanceId/<view>` looks the instance up in the cache, then in Consul, and
returns the instance's response body with status 200. Read in code, not run:

- An unknown instance or a failed call returns 500. The handler also checks that the instance is
  marked healthy, but every cached or discovered instance is marked healthy, so that check never
  fails.
- The instance's own HTTP status is not checked. An error body from the instance is returned with
  status 200 (D43).
- An empty response body gives a null JSON object. The success handler then throws
  `NullPointerException` and writes no response, so the client waits until its own timeout.
- The cache is refreshed by adding passing instances; an instance whose Consul check later fails
  stays in the cache, so a call is still routed to it until it is deregistered (D43).

## Cross-cutting behaviour

| Item | Behaviour |
|---|---|
| CORS | Methods `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`. Headers `Content-Type`, `Authorization`. `CorsHandler.create()` is called with no origin, so any origin is accepted (Vert.x Web behaviour, not run). |
| Authentication and TLS | None. |
| Tracing | A router handler reads the request `traceparent`, or creates a new trace, puts `traceId` and `spanId` in the MDC, and clears them when the response ends. `traceparent` is not in the CORS header list. |

## Not wired

- `HealthMonitor`, `ConnectionRouter` and `LoadBalancer` are never constructed by the service. There
  is no service-side health polling, load balancing, retry or failover between instances.
  `LoadBalancingStrategy` defines `ROUND_ROBIN`, `RANDOM`, `FIRST_AVAILABLE`, `LEAST_CONNECTIONS` and
  `WEIGHTED_ROUND_ROBIN`; `LoadBalancer` implements the first three and falls back to round robin for
  the other two. Only the unit tests use it.
- `ServiceManagerConfig` is used only by its unit test.
- The service records no metrics. The `pom.xml` declares `micrometer-core`,
  `resilience4j-circuitbreaker` and `vertx-service-discovery`; no main class uses them (D43).
- No Consul `DeregisterCriticalServiceAfter` is set. An instance whose check fails stays in Consul
  in the critical state until it is deregistered (Consul behaviour, not checked).

## Tests

Core tests: `ServiceManagerConfigTest`, `HealthCheckResultTest`, `HealthStatusTest`,
`PeeGeeQInstanceTest`, `LoadBalancerTest`, `ManualHealthCheckTest`,
`PeeGeeQServiceManagerConsulUnavailableTest`. Integration tests, which start a Consul
Testcontainer: `PeeGeeQServiceManagerIntegrationTest`, `RealServiceDiscoveryIntegrationTest`,
`ConsulServiceDiscoveryIntegrationTest`. The integration tests run under `-Pintegration-tests`
and under `-Pall-tests`; the default build excludes them. The command forms are in
`docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`.

## License

Apache License 2.0. See `LICENSE`.
