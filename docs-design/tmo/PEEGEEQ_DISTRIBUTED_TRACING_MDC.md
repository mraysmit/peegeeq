# MDC Distributed Tracing in PeeGeeQ

## 📚 Quick Links

- **[Distributed Tracing Guide](DISTRIBUTED_TRACING_GUIDE.md)** - Complete guide with examples and best practices ⭐ **Start here!**
- **[Understanding Blank Trace IDs](UNDERSTANDING_BLANK_TRACE_IDS.md)** - Why some logs have blank trace IDs (and why that's normal)
- **This document** - Technical API reference

## Overview

PeeGeeQ now supports **W3C Trace Context** and **SLF4J MDC (Mapped Diagnostic Context)** for distributed tracing across REST APIs, message queues, and consumers. This enables you to correlate logs across the entire message lifecycle from HTTP ingress to message processing.

## What is MDC?

MDC (Mapped Diagnostic Context) is a feature of SLF4J that allows you to attach contextual information to log statements. This information is automatically included in all log messages within the same thread or async context.

## W3C Trace Context Support

PeeGeeQ implements the [W3C Trace Context](https://www.w3.org/TR/trace-context/) specification, which defines a standard format for propagating trace context across services:

```
traceparent: 00-{trace-id}-{parent-id}-{trace-flags}
```

Example:
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

## MDC Fields

PeeGeeQ automatically populates the following MDC fields:

| Field | Description | Example |
|-------|-------------|---------|
| `traceId` | W3C trace ID (32 hex chars) | `4bf92f3577b34da6a3ce929d0e0e4736` |
| `spanId` | W3C span/parent ID (16 hex chars) | `00f067aa0ba902b7` |
| `correlationId` | Message correlation ID | `msg-12345` |
| `messageId` | Unique message ID | `msg-12345` |
| `topic` | Queue/topic name | `orders` |
| `setupId` | Database setup ID | `prod-db` |
| `queueName` | Queue name | `orders` |

## How It Works

### 1. REST Layer (HTTP Ingress)

When an HTTP request arrives at PeeGeeQ REST API with a `traceparent` header:

```http
POST /api/v1/setups/prod-db/queues/orders/messages
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

The `QueueHandler` automatically:
1. Extracts the `traceparent` header
2. Parses it to get `traceId` and `spanId`
3. Sets MDC values
4. Stores trace context in message headers (JSONB)
5. Clears MDC after request completes

### 2. Message Storage

The trace context is stored in the message's `headers` JSONB column:

```json
{
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "correlationId": "msg-12345"
}
```

### 3. Consumer Layer (Message Processing)

When a consumer processes the message:

1. `PgNativeQueueConsumer` or `OutboxConsumer` extracts headers from the database
2. Restores trace context from headers to MDC
3. Invokes your message handler (with MDC set)
4. Clears MDC after processing completes

**Your application code automatically inherits the trace context!**

## Log Output

With MDC enabled, your logs will look like this:

```
2025-12-24 20:00:00.123 [vert.x-worker-thread-1] INFO  QueueHandler - [traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7 correlationId=msg-12345] Sending message to queue orders in setup: prod-db

2025-12-24 20:00:00.456 [pool-2-thread-1] INFO  PgNativeQueueConsumer - [traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7 correlationId=msg-12345] Message msg-12345 processed successfully

2025-12-24 20:00:00.789 [pool-2-thread-1] INFO  YourMessageHandler - [traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7 correlationId=msg-12345] Processing order: 12345
```

Notice how all logs share the same `traceId`, `spanId`, and `correlationId`!

## Usage Examples

### Example 1: Sending a Message with Trace Context

```bash
curl -X POST http://localhost:8080/api/v1/setups/prod-db/queues/orders/messages \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  -d '{
    "payload": {"orderId": 12345, "amount": 99.99},
    "headers": {"customerId": "cust-456"}
  }'
```

### Example 2: Accessing MDC in Your Message Handler

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class OrderMessageHandler implements MessageHandler<Order> {
    private static final Logger logger = LoggerFactory.getLogger(OrderMessageHandler.class);

    @Override
    public CompletableFuture<Void> handle(Message<Order> message) {
        // MDC is automatically set by PeeGeeQ!
        String traceId = MDC.get("traceId");
        String correlationId = MDC.get("correlationId");

        logger.info("Processing order: {}", message.getPayload().getOrderId());
        // Log output will include: [traceId=... spanId=... correlationId=...]

        return CompletableFuture.completedFuture(null);
    }
}
```

### Example 3: Configuring Logback to Display MDC

Add this to your `logback.xml`:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [traceId=%X{traceId} spanId=%X{spanId} correlationId=%X{correlationId}] %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

The `%X{...}` syntax extracts MDC values.

### Example 4: Propagating Trace Context to External Services

If your message handler calls external services, propagate the trace context:

```java
public class OrderMessageHandler implements MessageHandler<Order> {
    private static final Logger logger = LoggerFactory.getLogger(OrderMessageHandler.class);
    private final HttpClient httpClient;

    @Override
    public CompletableFuture<Void> handle(Message<Order> message) {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");

        // Propagate trace context to downstream service
        String traceparent = String.format("00-%s-%s-01", traceId, generateNewSpanId());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://payment-service/api/charge"))
            .header("traceparent", traceparent)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(toJson(message.getPayload())))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                logger.info("Payment processed: {}", response.body());
                return null;
            });
    }

    private String generateNewSpanId() {
        // Generate a new 16-character hex span ID
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }
}
```

## Integration with Observability Tools

### OpenTelemetry

PeeGeeQ's W3C Trace Context is compatible with OpenTelemetry. You can:

1. Use OpenTelemetry Java Agent to auto-instrument your application
2. The agent will automatically pick up `traceparent` headers
3. Traces will be exported to your observability backend (Jaeger, Zipkin, etc.)

### Datadog

Datadog APM supports W3C Trace Context. Configure your Datadog agent:

```yaml
# datadog.yaml
apm_config:
  propagation_style_extract:
    - tracecontext  # W3C Trace Context
    - datadog
```

### Elastic APM

Elastic APM also supports W3C Trace Context:

```properties
# elasticapm.properties
trace_context_propagation_style=tracecontext
```

## Best Practices

1. **Always include `traceparent` header** when sending messages via REST API
2. **Use structured logging** (JSON) to make MDC fields searchable
3. **Generate new span IDs** when calling downstream services
4. **Don't modify MDC manually** - let PeeGeeQ manage it
5. **Use correlation IDs** for business-level tracking (e.g., order ID, customer ID)

## Troubleshooting

### MDC values are null

**Cause**: No `traceparent` header was provided in the HTTP request.

**Solution**: Always include the `traceparent` header when sending messages.

### MDC values are not showing in logs

**Cause**: Logback pattern doesn't include MDC placeholders.

**Solution**: Update your `logback.xml` pattern to include `%X{traceId}`, `%X{spanId}`, etc.

### Trace context is lost in async operations

**Cause**: MDC is thread-local and doesn't automatically propagate to new threads.

**Solution**: PeeGeeQ handles this automatically for message processing. If you create your own threads, use `MDC.getCopyOfContextMap()` and `MDC.setContextMap()`.

## Implementation Details

### Classes Involved

- **`QueueHandler`**: Extracts `traceparent` from HTTP headers, sets MDC, stores in message headers
- **`PgNativeQueueConsumer`**: Restores MDC from message headers before invoking handler
- **`OutboxConsumer`**: Restores MDC from message headers before invoking handler
- **`TraceContextUtil`**: Utility class for parsing/formatting W3C Trace Context

### Database Schema

Trace context is stored in the `headers` JSONB column of the `messages` table:

```sql
SELECT id, headers->>'traceparent' AS traceparent
FROM messages
WHERE id = 'msg-12345';
```

## References

- [W3C Trace Context Specification](https://www.w3.org/TR/trace-context/)
- [SLF4J MDC Documentation](http://www.slf4j.org/manual.html#mdc)
- [OpenTelemetry Java](https://opentelemetry.io/docs/instrumentation/java/)
- [Logback MDC](http://logback.qos.ch/manual/mdc.html)


