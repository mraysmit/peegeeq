# CloudEvents Support and Integration in PeeGeeQ

## What is CloudEvents?

CloudEvents is a specification for describing event data in a common way. It was created by the Cloud Native Computing Foundation (CNCF) to provide interoperability across services, platforms, and systems.

### CloudEvents v1.0 Specification

CloudEvents defines a set of standard attributes that every event must have:

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
| `id` | String | Yes | Unique identifier for the event |
| `source` | URI | Yes | Identifies the context in which the event happened |
| `specversion` | String | Yes | CloudEvents specification version (e.g., "1.0") |
| `type` | String | Yes | Type of event (e.g., "com.example.order.created.v1") |
| `datacontenttype` | String | No | Content type of the data (e.g., "application/json") |
| `dataschema` | URI | No | Schema that the data adheres to |
| `subject` | String | No | Subject of the event in context of the source |
| `time` | Timestamp | No | Timestamp when the event occurred |
| `data` | Any | No | The event payload |

### Extension Attributes

CloudEvents supports custom extension attributes for domain-specific metadata. PeeGeeQ uses these extensions:

| Extension | Description |
|-----------|-------------|
| `correlationid` | Links all events in a business workflow |
| `causationid` | Identifies the parent event that caused this event |
| `validtime` | Business time (when event occurred in real world) |
| `bookingsystem` | Source booking system (e.g., "Murex", "Calypso") |
| `clearinghouse` | Clearing house (e.g., "DTCC", "LCH") |

## Why Use CloudEvents?

### 1. Interoperability

CloudEvents provides a vendor-neutral format that works across:
- Different programming languages (Java, Python, Go, JavaScript, etc.)
- Different messaging systems (Kafka, RabbitMQ, NATS, etc.)
- Different cloud providers (AWS, Azure, GCP)
- Different protocols (HTTP, AMQP, MQTT, etc.)

### 2. Standardized Event Metadata

Every CloudEvent carries consistent metadata:
- **Traceability**: `id`, `source`, and `time` enable event tracking
- **Routing**: `type` and `subject` enable content-based routing
- **Correlation**: Extension attributes enable workflow tracking

### 3. Schema Evolution

CloudEvents supports:
- Versioned event types (e.g., `com.example.order.created.v1`, `v2`)
- Optional `dataschema` for payload validation
- Backward-compatible extension attributes

### 4. Ecosystem Support

CloudEvents has SDKs for:
- Java, Go, Python, JavaScript, C#, Ruby, PHP, Rust
- Integration with major frameworks (Spring, Quarkus, Micronaut)
- Native support in cloud services (Azure Event Grid, Google Eventarc)

## CloudEvents Integration in PeeGeeQ

### Architecture Overview

```
+------------------+     +-------------------+     +------------------+
|   Application    |     |     PeeGeeQ       |     |   PostgreSQL     |
|                  |     |                   |     |                  |
| CloudEventBuilder|---->| OutboxFactory     |---->| JSONB Storage    |
|                  |     | BiTemporalFactory |     |                  |
|                  |     | PgNativeFactory   |     | JSONB Queries    |
+------------------+     +-------------------+     +------------------+
```

### Dependency Configuration

CloudEvents is an **optional dependency** in PeeGeeQ. The core modules include it as optional:

```xml
<!-- In peegeeq-outbox/pom.xml, peegeeq-bitemporal/pom.xml, peegeeq-native/pom.xml -->
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-core</artifactId>
    <version>4.0.1</version>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-json-jackson</artifactId>
    <version>4.0.1</version>
    <optional>true</optional>
</dependency>
```

To use CloudEvents in your application, add the dependencies explicitly:

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

### Automatic Jackson Module Registration

PeeGeeQ automatically detects and registers the CloudEvents Jackson module when available on the classpath. This happens in the factory classes:

```java
// From OutboxFactory.java, BiTemporalEventStoreFactory.java, PgNativeQueueFactory.java
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
        logger.debug("CloudEvents Jackson module not available on classpath, skipping registration");
    }

    return mapper;
}
```

This approach:
- Uses reflection to avoid compile-time dependency
- Logs at `debug` level (not visible by default)
- Gracefully degrades when CloudEvents is not available

## Usage Examples

### Basic CloudEvent Creation

```java
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

CloudEvent event = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withType("com.example.order.created.v1")
    .withSource(URI.create("https://example.com/orders"))
    .withTime(OffsetDateTime.now())
    .withDataContentType("application/json")
    .withData(orderPayloadBytes)
    .withExtension("correlationid", "workflow-123")
    .withExtension("causationid", "user-action-456")
    .build();
```

### Sending CloudEvents via Outbox

```java
// Create producer for CloudEvents
MessageProducer<CloudEvent> producer = factory.createProducer("orders", CloudEvent.class);

// Send CloudEvent
producer.send(event, Map.of("priority", "HIGH")).get();
```

### Storing CloudEvents in Bi-Temporal Event Store

```java
// Create event store for CloudEvents
EventStore<CloudEvent> eventStore = factory.createEventStore(CloudEvent.class);

// Append with valid time
Instant validTime = Instant.now();
eventStore.append("OrderCreated", event, validTime).get();
```

### Querying CloudEvents with PostgreSQL JSONB

CloudEvents stored in PeeGeeQ can be queried using PostgreSQL's powerful JSONB operators:

```sql
-- Query by CloudEvent type
SELECT event_id, payload->>'type' as event_type, payload->>'subject' as trade_id
FROM bitemporal_event_log
WHERE payload->>'type' = 'backoffice.trade.new.v1'
ORDER BY transaction_time;

-- Query by extension attribute (correlationid)
SELECT event_id, payload->>'type' as event_type
FROM bitemporal_event_log
WHERE payload->>'correlationid' = 'TRD-001'
ORDER BY valid_time;

-- Query by data payload fields
SELECT event_id, payload->>'subject' as trade_id,
       (payload->'data'->>'notionalAmount')::numeric as notional
FROM bitemporal_event_log
WHERE (payload->'data'->>'notionalAmount')::numeric > 100000
ORDER BY notional DESC;

-- Combine JSONB query with bi-temporal time range
SELECT event_id, payload->>'subject' as trade_id, valid_time
FROM bitemporal_event_log
WHERE payload->>'clearinghouse' = 'DTCC'
  AND valid_time < '2025-01-01T00:00:00Z'
ORDER BY valid_time;

-- Aggregate by counterparty
SELECT payload->'data'->>'counterparty' as counterparty,
       COUNT(*) as trade_count,
       SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional
FROM bitemporal_event_log
WHERE payload->>'type' = 'backoffice.trade.new.v1'
GROUP BY payload->'data'->>'counterparty'
ORDER BY total_notional DESC;
```

## Spring Boot Integration

PeeGeeQ provides Spring Boot integration with CloudEvents through the `peegeeq-examples-spring` module.

### FinancialCloudEventBuilder

A Spring component that creates CloudEvents with financial fabric extensions:

```java
@Component
public class FinancialCloudEventBuilder {

    private final FinancialFabricProperties properties;
    private final ObjectMapper objectMapper;

    public <T> CloudEvent buildCloudEvent(
            String eventType,
            T payload,
            String correlationId,
            String causationId,
            Instant validTime) throws JsonProcessingException {

        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(eventType)
            .withSource(URI.create(properties.getCloudevents().getSource()))
            .withTime(OffsetDateTime.now())
            .withDataContentType(properties.getCloudevents().getDataContentType())
            .withData(objectMapper.writeValueAsBytes(payload))
            .withExtension("correlationid", correlationId)
            .withExtension("causationid", causationId != null ? causationId : "")
            .withExtension("validtime", validTime.toString())
            .build();
    }
}
```

### CloudEventExtensions Utility

Helper class for extracting CloudEvent extensions:

```java
public class CloudEventExtensions {

    public static String getCorrelationId(CloudEvent event) {
        return (String) event.getExtension("correlationid");
    }

    public static String getCausationId(CloudEvent event) {
        String causationId = (String) event.getExtension("causationid");
        return causationId != null && !causationId.isEmpty() ? causationId : null;
    }

    public static Instant getValidTime(CloudEvent event) {
        String validTime = (String) event.getExtension("validtime");
        return validTime != null ? Instant.parse(validTime) : null;
    }

    public static <T> T getPayload(CloudEvent event, Class<T> payloadClass, ObjectMapper objectMapper)
            throws IOException {
        byte[] data = event.getData() != null ? event.getData().toBytes() : new byte[0];
        return objectMapper.readValue(data, payloadClass);
    }
}
```

### Configuration Properties

```yaml
peegeeq:
  financial-fabric:
    cloudevents:
      source: "trading-system"
      spec-version: "1.0"
      data-content-type: "application/json"
```

## Testing CloudEvents Integration

### Test Classes

PeeGeeQ includes comprehensive tests for CloudEvents integration:

| Test Class | Module | Description |
|------------|--------|-------------|
| `CloudEventsObjectMapperTest` | peegeeq-examples | Unit tests for Jackson serialization/deserialization |
| `CloudEventsJsonbQueryTest` | peegeeq-examples | Integration tests for JSONB queries with CloudEvents |
| `FinancialFabricServicesTest` | peegeeq-examples-spring | End-to-end workflow tests with CloudEvents |
| `SpringBootFinancialFabricApplicationTest` | peegeeq-examples-spring | Spring Boot configuration tests |

### CloudEventsObjectMapperTest

Tests CloudEvents Jackson module integration:

```java
@Test
void testCloudEventsObjectMapperIntegration() throws Exception {
    // Create CloudEvent
    CloudEvent originalEvent = CloudEventBuilder.v1()
        .withId("test-event-123")
        .withType("com.example.test.event.v1")
        .withSource(URI.create("https://example.com/test"))
        .withTime(OffsetDateTime.now())
        .withDataContentType("application/json")
        .withData("application/json", "{\"message\": \"Hello CloudEvents!\"}".getBytes())
        .withExtension("correlationid", "test-correlation-123")
        .build();

    // Create ObjectMapper with CloudEvents module
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(JsonFormat.getCloudEventJacksonModule());

    // Serialize and deserialize
    String serialized = mapper.writeValueAsString(originalEvent);
    CloudEvent deserializedEvent = mapper.readValue(serialized, CloudEvent.class);

    // Verify
    assertEquals(originalEvent.getId(), deserializedEvent.getId());
    assertEquals(originalEvent.getType(), deserializedEvent.getType());
    assertEquals("test-correlation-123", deserializedEvent.getExtension("correlationid"));
}
```

### CloudEventsJsonbQueryTest

Integration tests demonstrating JSONB queries with CloudEvents stored in the bi-temporal event store:

**Test Coverage (14 tests):**

1. **testStoreTradeLifecycleEvents** - Stores 6 trade lifecycle CloudEvents (3 trades in various stages)
2. **testQueryByCloudEventType** - Query by `payload->>'type'`
3. **testQueryByExtensionAttribute** - Query by `payload->>'correlationid'`
4. **testQueryByBookingSystem** - Query by custom extension `payload->>'bookingsystem'`
5. **testQueryByDataPayloadField** - Query by nested data `(payload->'data'->>'notionalAmount')::numeric`
6. **testQueryByCounterpartyAndStatus** - Multi-field query on data payload
7. **testQueryByClearingHouseWithTimeRange** - Combine JSONB with bi-temporal time range
8. **testQueryByCloudEventSource** - Query by `payload->>'source'`
9. **testQueryBySymbolAndSide** - Query nested data fields
10. **testComplexAggregationQuery** - Aggregate notional amounts by counterparty
11. **testBiTemporalPointInTimeQuery** - Point-in-time query with JSONB filtering
12. **testTradeLifecycleReconstruction** - Reconstruct complete trade lifecycle using correlationid
13. **testSettlementDateRangeQuery** - Query by settlement date range in data payload
14. **testMultiSystemQuery** - Query across multiple booking systems and clearing houses

### Running CloudEvents Tests

```bash
# Run CloudEvents ObjectMapper tests (unit tests)
mvn test -pl peegeeq-examples -Dtest=CloudEventsObjectMapperTest

# Run CloudEvents JSONB query tests (integration tests)
mvn test -pl peegeeq-examples -Pintegration-tests -Dtest=CloudEventsJsonbQueryTest

# Run Spring Boot CloudEvents tests
mvn test -pl peegeeq-examples-spring -Pintegration-tests -Dtest=SpringBootFinancialFabricApplicationTest
```

## Best Practices

### 1. Event Type Naming

Use reverse domain notation with version suffix:

```
com.{company}.{domain}.{entity}.{action}.v{version}

Examples:
- com.fincorp.trading.equities.capture.completed.v1
- com.fincorp.settlement.instruction.submitted.v1
- com.fincorp.regulatory.transaction.reported.v1
```

### 2. Correlation and Causation

Always set correlation and causation IDs for workflow tracking:

```java
// Initial event (no causation)
CloudEvent tradeEvent = builder.buildCloudEvent(
    "com.fincorp.trading.trade.captured.v1",
    tradePayload,
    "workflow-" + UUID.randomUUID(),  // correlationId
    Instant.now()                      // validTime
);

// Subsequent event (with causation)
CloudEvent settlementEvent = builder.buildCloudEvent(
    "com.fincorp.settlement.instruction.submitted.v1",
    settlementPayload,
    tradeEvent.getExtension("correlationid").toString(),  // same correlationId
    tradeEvent.getId(),                                    // causationId = parent event ID
    Instant.now()
);
```

### 3. Valid Time vs Transaction Time

- **Valid Time**: When the event occurred in the real world (business time)
- **Transaction Time**: When the event was recorded in the system (automatically set by PeeGeeQ)

```java
// Trade executed at 10:00 AM but recorded at 10:05 AM
Instant validTime = Instant.parse("2025-01-15T10:00:00Z");  // Business time
eventStore.append("TradeExecuted", event, validTime).get();
// transaction_time will be 2025-01-15T10:05:00Z (system time)
```

### 4. Extension Attribute Naming

Use lowercase, no special characters:

```java
// Good
.withExtension("correlationid", "...")
.withExtension("bookingsystem", "Murex")
.withExtension("clearinghouse", "DTCC")

// Avoid
.withExtension("correlation-id", "...")  // hyphens
.withExtension("bookingSystem", "...")   // camelCase
```

## Performance Considerations

### JSONB Indexing

For high-volume CloudEvents queries, create GIN indexes on frequently queried fields:

```sql
-- Index on CloudEvent type
CREATE INDEX idx_events_type ON bitemporal_event_log ((payload->>'type'));

-- Index on correlationid extension
CREATE INDEX idx_events_correlationid ON bitemporal_event_log ((payload->>'correlationid'));

-- GIN index for flexible JSONB queries
CREATE INDEX idx_events_payload_gin ON bitemporal_event_log USING GIN (payload);

-- Composite index for common query patterns
CREATE INDEX idx_events_type_time ON bitemporal_event_log ((payload->>'type'), valid_time);
```

### Query Optimization

1. **Use specific field extraction** instead of full JSONB scans:
   ```sql
   -- Good: Uses index on type
   WHERE payload->>'type' = 'backoffice.trade.new.v1'

   -- Slower: Full JSONB containment check
   WHERE payload @> '{"type": "backoffice.trade.new.v1"}'
   ```

2. **Cast numeric fields** for proper comparison:
   ```sql
   WHERE (payload->'data'->>'notionalAmount')::numeric > 100000
   ```

3. **Combine with bi-temporal predicates** for efficient filtering:
   ```sql
   WHERE payload->>'type' = 'backoffice.trade.new.v1'
     AND valid_time BETWEEN '2025-01-01' AND '2025-01-31'
   ```

## Troubleshooting

### CloudEvents Module Not Registered

**Symptom**: `JsonMappingException` when serializing/deserializing CloudEvents

**Solution**: Ensure CloudEvents dependencies are on the classpath:

```xml
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-json-jackson</artifactId>
    <version>4.0.1</version>
</dependency>
```

### Extension Attributes Not Preserved

**Symptom**: Extension attributes are null after deserialization

**Solution**: Ensure you're using the CloudEvents Jackson module:

```java
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
```

### JSONB Query Returns No Results

**Symptom**: Query returns empty results even though data exists

**Common causes**:
1. **Case sensitivity**: JSONB keys are case-sensitive
   ```sql
   -- Wrong: 'Type' vs 'type'
   WHERE payload->>'Type' = 'backoffice.trade.new.v1'

   -- Correct
   WHERE payload->>'type' = 'backoffice.trade.new.v1'
   ```

2. **Extension attribute location**: Extensions are top-level, not nested
   ```sql
   -- Wrong: Looking in 'extensions' object
   WHERE payload->'extensions'->>'correlationid' = 'TRD-001'

   -- Correct: Extensions are top-level
   WHERE payload->>'correlationid' = 'TRD-001'
   ```

## References

- [CloudEvents Specification v1.0](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md)
- [CloudEvents Java SDK](https://github.com/cloudevents/sdk-java)
- [PostgreSQL JSONB Documentation](https://www.postgresql.org/docs/current/datatype-json.html)
- [PeeGeeQ Bi-Temporal Event Store](../peegeeq-bitemporal/README.md)

