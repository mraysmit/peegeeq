# PeeGeeQ Operations Guide

**Status:** CURRENT CATEGORY GUIDE

This is the maintained destination for deployment operations, recovery, shutdown, PostgreSQL
connectivity and failover, monitoring, dashboards, and evidence-based performance guidance.

## Current maintained companions

- [Database Setup Guide](PEEGEEQ_DATABASE_SETUP_GUIDE.md)
- [Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md)
- [Tracing and Logging User Guide](PEEGEEQ_TRACING_USER_GUIDE.md)
- [Tracing Technical Reference](PEEGEEQ_TRACING_TECHNICAL_REFERENCE.md)
- [REST health and metrics endpoints](PEEGEEQ_REST_API_REFERENCE.md#health--metrics-endpoints)

## Service lifecycle and shutdown

Startup must settle only after required resources are usable. Shutdown must stop new work, observe
in-flight asynchronous operations, close consumers and services, release database pools, and report
cleanup failures. Historical shutdown reviews remain evidence for the problems and remedies they
recorded; current behavior requires tests at the applicable lifecycle boundary.

## Message recovery

Recovery settings define when abandoned work becomes eligible again. Operators must distinguish a
stuck queue claim from a handler that completed an external side effect but failed before its queue
state or cursor was committed. Idempotent handlers remain required.

## PostgreSQL connectivity and failover

The Vert.x PostgreSQL pool manages connections but does not by itself elect or discover a new
database primary. HAProxy, PgBouncer, a sidecar, Patroni, or another deployment component may own
routing and primary detection. The exact supported behavior depends on the deployed topology.

Detailed retained reference:

- [PostgreSQL connection management and HAProxy](<../docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md>)

Known gaps and proposed alternatives remain clearly labelled and are not operational guarantees.

## Monitoring and metrics

The REST service documents Prometheus scraping, metrics event streams, and monitoring WebSocket
surfaces. Proxies must preserve long-lived connections and avoid buffering event streams. Operators
should monitor reconnect churn, event-loop lag, connection pressure, stale metrics, processing lag,
and recovery activity rather than treating socket connectivity alone as health.

## Performance and capacity

Performance results are meaningful only with their workload, hardware, database, pool, concurrency,
and revision recorded. The performance harness and Grafana materials are retained in the
[Documentation Source Catalogue](PEEGEEQ_DOCUMENTATION_SOURCE_CATALOGUE.md). Dated benchmark
results remain evidence for their recorded environment, not permanent capacity claims.

## Operating-source retention

Detailed operating records remain under `docs-design/failover and resilience`,
`docs-design/performance`, and `docs-design/tracing-observability`. This guide provides the current
navigation and contract boundary without erasing those records.
