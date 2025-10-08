# CloudEvents Integration in PeeGeeQ

## Overview

PeeGeeQ now includes built-in support for CloudEvents serialization and deserialization through the CloudEvents Jackson module. This integration allows you to seamlessly work with CloudEvents v1.0 specification across all PeeGeeQ components.

## What's Included

### Automatic CloudEvents Support

All PeeGeeQ ObjectMapper instances now automatically include CloudEvents Jackson module support when the CloudEvents library is available on the classpath:

- **PeeGeeQManager**: Core ObjectMapper with CloudEvents support
- **OutboxFactory**: Outbox pattern with CloudEvents serialization
- **BiTemporalEventStoreFactory**: Bi-temporal event store with CloudEvents support
- **PgNativeQueueFactory**: Native queue with CloudEvents support
- **PeeGeeQRestServer**: REST API with CloudEvents support

### Dependencies

The CloudEvents dependencies are included in the examples module:

```xml
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-core</artifactId>
    <version>4.0.1</version>
</dependency>
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-json-jackson</artifactId>
    <version>4.0.1</version>
</dependency>
```

## Usage Examples

### Basic CloudEvent Creation and Serialization

```java
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;

// Create a CloudEvent
CloudEvent event = CloudEventBuilder.v1()
    .withId("order-123")
    .withType("com.example.order.created.v1")
    .withSource(URI.create("https://example.com/orders"))
    .withTime(OffsetDateTime.now())
    .withDataContentType("application/json")
    .withData("{\"orderId\": \"123\", \"amount\": 100.50}".getBytes())
    .withExtension("correlationid", "workflow-456")
    .build();

// PeeGeeQ's ObjectMapper automatically supports CloudEvents
PeeGeeQManager manager = new PeeGeeQManager(config);
ObjectMapper mapper = manager.getObjectMapper();

// Serialize CloudEvent
String json = mapper.writeValueAsString(event);

// Deserialize CloudEvent
CloudEvent deserializedEvent = mapper.readValue(json, CloudEvent.class);
```

### Using CloudEvents with Outbox Pattern

```java
// Create outbox factory - automatically includes CloudEvents support
OutboxFactory outboxFactory = new OutboxFactory(databaseService);
MessageProducer<CloudEvent> producer = outboxFactory.createProducer("events", CloudEvent.class);

// Send CloudEvent through outbox
CloudEvent event = CloudEventBuilder.v1()
    .withId("trade-789")
    .withType("com.trading.trade.executed.v1")
    .withSource(URI.create("https://trading.example.com"))
    .withData(tradeData)
    .build();

producer.send(event).get();
```

### Using CloudEvents with Bi-Temporal Event Store

```java
// Create bi-temporal event store with CloudEvents support
BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
EventStore<CloudEvent> eventStore = factory.createEventStore(CloudEvent.class);

// Store CloudEvent with temporal information
CloudEvent event = CloudEventBuilder.v1()
    .withId("settlement-456")
    .withType("com.settlement.instruction.created.v1")
    .withSource(URI.create("https://settlement.example.com"))
    .withData(settlementData)
    .withExtension("validtime", validTime.toString())
    .build();

eventStore.append("SettlementCreated", event, validTime);
```

### Financial Fabric Example

The `springbootfinancialfabric` example demonstrates comprehensive CloudEvents usage:

```java
@Bean
public ObjectMapper cloudEventObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
    return mapper;
}

@Component
public class FinancialCloudEventBuilder {
    
    public <T> CloudEvent buildCloudEvent(
            String eventType,
            T payload,
            String correlationId,
            String causationId,
            Instant validTime) throws JsonProcessingException {
        
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(eventType)
            .withSource(URI.create("https://financial.example.com"))
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(objectMapper.writeValueAsBytes(payload))
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId)
            .withExtension("validtime", validTime.toString())
            .build();
    }
}
```

## Implementation Details

### Automatic Module Registration

PeeGeeQ automatically detects and registers the CloudEvents Jackson module when available:

```java
private static ObjectMapper createDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    
    // Add CloudEvents Jackson module support if available on classpath
    try {
        Class<?> jsonFormatClass = Class.forName("io.cloudevents.jackson.JsonFormat");
        Object cloudEventModule = jsonFormatClass.getMethod("getCloudEventJacksonModule").invoke(null);
        if (cloudEventModule instanceof com.fasterxml.jackson.databind.Module) {
            mapper.registerModule((com.fasterxml.jackson.databind.Module) cloudEventModule);
            logger.debug("CloudEvents Jackson module registered successfully");
        }
    } catch (Exception e) {
        logger.debug("CloudEvents Jackson module not available on classpath, skipping registration: {}", e.getMessage());
    }
    
    return mapper;
}
```

### Graceful Degradation

If CloudEvents dependencies are not available on the classpath, PeeGeeQ continues to work normally without CloudEvents support. The integration is designed to be optional and non-breaking.

## Benefits

1. **Standardized Event Format**: Use CloudEvents v1.0 specification for consistent event structure
2. **Interoperability**: CloudEvents are widely supported across cloud platforms and event systems
3. **Rich Metadata**: Built-in support for event metadata, extensions, and correlation IDs
4. **Seamless Integration**: Works transparently with all PeeGeeQ patterns (Outbox, Bi-Temporal, Native)
5. **Type Safety**: Full Jackson serialization/deserialization support

## Testing

The integration includes comprehensive tests in `CloudEventsObjectMapperTest` that verify:

- CloudEvents serialization/deserialization across all PeeGeeQ components
- Extension preservation (correlationid, causationid, validtime)
- Complex payload handling
- Module registration verification

## Migration

Existing PeeGeeQ applications can adopt CloudEvents gradually:

1. Add CloudEvents dependencies to your project
2. Existing ObjectMapper instances automatically gain CloudEvents support
3. Start using CloudEvents for new event types
4. Gradually migrate existing events to CloudEvents format

No breaking changes are introduced - existing functionality continues to work as before.
