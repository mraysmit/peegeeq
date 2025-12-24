# Understanding Blank Trace IDs in PeeGeeQ Logs

## TL;DR

**Blank trace IDs are normal and expected** for logs that occur outside of message processing context. They only appear when processing messages that include trace headers.

## When Trace IDs Are Blank (Expected Behavior)

### 1. Application Startup/Initialization

```
21:32:34.059 [vert.x-eventloop-thread-0] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] Validating database connectivity...
21:32:34.061 [vert.x-eventloop-thread-0] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] Starting all PeeGeeQ components...
21:32:34.063 [vert.x-eventloop-thread-0] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] PeeGeeQ Manager started successfully
```

**Why?** These logs occur during application startup, before any messages are being processed. There's no trace context yet.

**Is this a problem?** ❌ No - this is completely normal and expected.

### 2. Application Shutdown

```
23:25:03.082 [main] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] Stopping PeeGeeQ Manager...
23:25:03.083 [main] INFO  HealthCheckManager - [traceId= spanId= correlationId=] Stopping health check manager reactively...
23:25:03.083 [main] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] PeeGeeQ Manager stopped successfully
```

**Why?** Shutdown logs occur outside of message processing context.

**Is this a problem?** ❌ No - this is normal.

### 3. Background Tasks (Health Checks, Metrics Collection)

```
21:32:34.061 [vert.x-eventloop-thread-0] INFO  HealthCheckManager - [traceId= spanId= correlationId=] Health check manager started reactively
21:32:34.062 [vert.x-eventloop-thread-0] INFO  PeeGeeQManager - [traceId= spanId= correlationId=] Started metrics collection every PT1M
```

**Why?** Background tasks run independently of message processing and don't have trace context.

**Is this a problem?** ❌ No - this is expected for background operations.

### 4. Producer Sending Messages (Before Consumer Processing)

```
23:28:12.515 [main] INFO  OutboxProducer - [traceId= spanId= correlationId=] Created outbox producer for topic: orders
```

**Why?** The producer is just sending messages. The trace context is in the message headers, not in the producer's thread MDC.

**Is this a problem?** ❌ No - the trace context is stored in the message and will be available when the consumer processes it.

### 5. Unsubscribe/Cleanup Operations

```
23:42:32.354 [main] INFO  OutboxConsumer - [traceId= spanId= correlationId=] Unsubscribed from topic: orders
23:42:32.354 [main] INFO  OutboxConsumer - [traceId= spanId= correlationId=] Closed outbox consumer
```

**Why?** Unsubscribe and close are **administrative operations** that happen during cleanup, not part of the traced request flow.

**Is this a problem?** ❌ No - this is expected. Message queues are asynchronous (fire-and-forget), so there's no "response" that flows back to the producer. The trace context lives within the message processing lifecycle only.

**Important**: This is different from HTTP request-response where trace context flows both ways. See the "Understanding Asynchronous vs Synchronous Tracing" section in the [Distributed Tracing Guide](DISTRIBUTED_TRACING_GUIDE.md).

## When Trace IDs Are Populated (Active Tracing)

### 1. Consumer Processing Messages

```
23:28:17.561 [outbox-processor-1] INFO  OutboxConsumer - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] MDC set for message 1
23:28:17.562 [outbox-processor-1] INFO  OutboxConsumer - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] Processing message 1
```

**Why?** The consumer extracted trace context from message headers and set it in MDC.

**This is what you want!** ✅ All logs within the message handler show the trace context.

### 2. Business Logic Inside Message Handler

```
23:28:17.562 [outbox-processor-1] INFO  OrderService - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] Processing order
23:28:17.562 [outbox-processor-1] INFO  OrderService - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] Order processed successfully
```

**Why?** MDC is thread-local, so all logs in the same thread (within the message handler) automatically include the trace context.

**This is the power of distributed tracing!** ✅ You can track the entire request flow.

## Visual Comparison

### ❌ Blank Trace IDs (Normal for Non-Message Logs)

```
┌─────────────────────────────────────────────────────────────────┐
│ Application Startup                                             │
├─────────────────────────────────────────────────────────────────┤
│ [traceId= spanId= correlationId=] Initializing PeeGeeQ Manager │
│ [traceId= spanId= correlationId=] Starting components...       │
│ [traceId= spanId= correlationId=] Manager started              │
└─────────────────────────────────────────────────────────────────┘
                          ↓
                   No trace context
                   (not processing messages)
```

### ✅ Populated Trace IDs (Message Processing)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Message Processing                                                           │
├──────────────────────────────────────────────────────────────────────────────┤
│ [traceId=52a0d370... spanId=d6ae23cb... correlationId=order-123] MDC set    │
│ [traceId=52a0d370... spanId=d6ae23cb... correlationId=order-123] Processing │
│ [traceId=52a0d370... spanId=d6ae23cb... correlationId=order-123] Complete   │
└──────────────────────────────────────────────────────────────────────────────┘
                          ↓
                   Full trace context
                   (processing message with headers)
```

## How to Verify Distributed Tracing is Working

### Step 1: Run the Test

```bash
mvn test -Dtest=DistributedTracingTest -Pintegration-tests -pl peegeeq-outbox
```

### Step 2: Look for These Logs

You should see logs like this:

```
📋 Generated trace context:
  🔍 traceId:       52a0d3705aba4122aa266f1216f87e10
  🔍 spanId:        d6ae23cb58e1467d
  🔍 correlationId: order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd

📨 Consumer received message with trace context:
  ✅ traceId from MDC:       52a0d3705aba4122aa266f1216f87e10
  ✅ spanId from MDC:        d6ae23cb58e1467d
  ✅ correlationId from MDC: order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd
```

And then logs from the consumer with populated trace IDs:

```
23:28:17.562 [outbox-processor-1] INFO  OutboxConsumer - [traceId=52a0d3705aba4122aa266f1216f87e10 spanId=d6ae23cb58e1467d correlationId=order-e0fa5cc5-aa9b-4957-8dc1-0f7e0b96decd] Processing message
```

### Step 3: Verify Trace Context Propagation

The test verifies that:
- ✅ Trace ID sent by producer matches trace ID in consumer
- ✅ Span ID sent by producer matches span ID in consumer
- ✅ Correlation ID sent by producer matches correlation ID in consumer

## Common Misconceptions

### ❌ Misconception: "All logs should have trace IDs"

**Reality**: Only logs within message processing context have trace IDs. Initialization, shutdown, and background tasks don't have trace context.

### ❌ Misconception: "Blank trace IDs mean tracing is broken"

**Reality**: Blank trace IDs are normal for logs outside of message processing. Tracing is working correctly if consumer logs show populated trace IDs.

### ❌ Misconception: "Producer logs should show trace IDs"

**Reality**: Producer logs typically don't show trace IDs because the trace context is in the message headers, not in the producer's thread MDC. The consumer will extract and use the trace context.

## When to Be Concerned

### 🚨 Problem: Consumer Logs Have Blank Trace IDs

```
23:28:17.562 [outbox-processor-1] INFO  OutboxConsumer - [traceId= spanId= correlationId=] Processing message
```

**This is a problem!** The consumer should have trace IDs when processing messages.

**Possible causes:**
1. Message was sent without `traceparent` header
2. Logback configuration doesn't include MDC placeholders
3. MDC is being cleared prematurely

**Solution**: See [DISTRIBUTED_TRACING_GUIDE.md](DISTRIBUTED_TRACING_GUIDE.md) troubleshooting section.

## Summary

| Log Context | Trace IDs | Expected? | Reason |
|-------------|-----------|-----------|--------|
| Application startup | Blank | ✅ Yes | No message being processed |
| Application shutdown | Blank | ✅ Yes | No message being processed |
| Background tasks | Blank | ✅ Yes | Independent of message flow |
| Producer sending | Blank | ✅ Yes | Trace context in message headers, not producer thread |
| Unsubscribe/cleanup | Blank | ✅ Yes | Administrative operation, not part of traced request |
| **Consumer processing** | **Populated** | **✅ Yes** | **Trace context from message headers** |
| Business logic in handler | Populated | ✅ Yes | Same thread as consumer processing |

**Key Takeaway**: Blank trace IDs are normal for most logs. The important thing is that **consumer logs show populated trace IDs when processing messages**.

**Important**: Message queues are asynchronous (fire-and-forget). Trace context flows **forward** through the message, not **backward** to the producer. For full distributed tracing across multiple services, see the [Full Distributed Tracing Example](DISTRIBUTED_TRACING_GUIDE.md#example-2-full-distributed-tracing-flow).

## Next Steps

- Read [DISTRIBUTED_TRACING_GUIDE.md](DISTRIBUTED_TRACING_GUIDE.md) for complete usage guide
- Run [DistributedTracingTest.java](../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/DistributedTracingTest.java) to see it in action
- Configure your observability platform to collect and visualize traces

