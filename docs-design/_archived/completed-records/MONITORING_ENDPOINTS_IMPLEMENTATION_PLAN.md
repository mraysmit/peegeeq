# Real-Time Monitoring Endpoints Implementation Record

**Status:** IMPLEMENTED — HISTORICAL PLAN CLOSED

**Created:** 2025-12-30

**Last reconciled:** 2026-09-06

**Module:** `peegeeq-rest`

## Outcome

The real-time monitoring work described by the original plan is implemented. This document is a
closed implementation record, not a source of open tasks. Current work and verification evidence
are maintained in the [consolidated task register](../tasks/tasks.md).

Source inspection at repository baseline `7db748b8e77f3aba850be7b73547d192dac5b83f`
confirms the following surfaces:

| Surface | Current route or component |
|---|---|
| Monitoring WebSocket | `WS /ws/monitoring` |
| Metrics event stream | `GET /sse/metrics` and `GET /api/v1/sse/metrics` |
| Prometheus scrape | `GET /metrics` using the Prometheus meter registry |
| Server integration | `PeeGeeQRestServer` owns one `SystemMonitoringHandler` |
| Shutdown | Server shutdown closes the monitoring handler and its connections |
| CORS | Allowed origins are explicit configuration and validated at startup |

## Implemented behaviour

`SystemMonitoringHandler` owns monitoring WebSocket and server-sent-event connections. It:

- sends an initial metrics payload and periodic updates;
- supports WebSocket ping, configuration, and refresh commands;
- validates client-provided intervals;
- tracks active connections and per-address limits;
- uses bounded timers and cancels them during connection or server shutdown;
- publishes JVM, application, throughput, connection, and event-loop-lag measurements; and
- treats malformed commands and abrupt disconnects as contained connection events.

The management UI consumes the live stream and retains its polling fallback. Runtime behaviour
must continue to be established by integration and browser tests; the bullets above describe the
inspected implementation surface.

## Closed review findings

The old plan contained an early review section that simultaneously called findings unresolved and
fixed. The reviewed source now contains the corresponding remediation:

| Historical finding | Reconciled state |
|---|---|
| Monitoring handler not closed by server | Closed by `PeeGeeQRestServer` shutdown |
| Queue WebSocket handler lifecycle incomplete | Server-owned handler is closed during shutdown |
| CORS origins implicit | Origins are required and applied to the CORS handler |
| Metrics endpoint returned placeholder values | Endpoint emits the meter registry scrape |
| Monitoring handler instance created without lifecycle ownership | A server field owns the handler |

These rows record the resolution of the historical review; they do not create new work.

## Verification coverage

Relevant maintained tests include:

- `SystemMonitoringHandlerTest` for WebSocket and event-stream connections, commands, metrics,
  invalid input, disconnects, reconnects, and connection-count invariants;
- `SseMetricsCadencePinTest` for event-stream cadence;
- `ShutdownResourceLeakDetectionTest` for handler cleanup, repeated deployment cycles, and CORS
  startup validation;
- `RestServerConfigTest` and `CorsConfigQuickTest` for allowed-origin configuration; and
- management UI browser tests for live status and fallback behaviour.

The consolidated register records the most recent accepted Jenkins evidence. This document does
not infer a new test result from source inspection.

## Operational contract

- Configure explicit allowed origins for every deployment. A wildcard is appropriate only for an
  intentionally open environment.
- Route WebSocket and event-stream traffic through infrastructure that preserves long-lived
  connections and does not buffer the event stream.
- Scrape `/metrics` with a Prometheus-compatible collector.
- Alert on sustained reconnect churn, connection-limit rejection, event-loop lag, and stale
  metrics rather than treating a connected socket alone as proof of health.
- Exercise shutdown and reconnect behaviour after proxy, timeout, or topology changes.

## Performance claims

The original plan listed connection-count, latency, and CPU targets. Those values were design
targets, not measured release evidence. Any production capacity claim must come from a dated,
repeatable load test whose environment and results are recorded under the applicable release gate
in the consolidated task register.

## References

- [Consolidated task register](../tasks/tasks.md)
- [Coding principles](../dev/pgq-coding-principles.md)
- [Testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
