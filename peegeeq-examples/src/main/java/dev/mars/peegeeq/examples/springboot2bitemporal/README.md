# Spring Boot 2 Reactive Bi-Temporal Event Store Example

This example demonstrates integrating PeeGeeQ's bi-temporal event store with Spring Boot WebFlux for reactive applications.

## Overview

This example shows how to:
- Integrate PeeGeeQ bi-temporal event store with Spring Boot WebFlux
- Convert CompletableFuture → Mono/Flux using reactive adapters
- Implement back office settlement processing with bi-temporal events
- Use event naming pattern: `{entity}.{action}.{state}`
- Demonstrate both current and proposed API approaches

## Use Case: Back Office Settlement Processing

The example implements a settlement instruction lifecycle:

1. **instruction.settlement.submitted** - Settlement instruction submitted to custodian
2. **instruction.settlement.matched** - Settlement matched with counterparty
3. **instruction.settlement.confirmed** - Settlement confirmed by custodian
4. **instruction.settlement.failed** - Settlement failed (with reason)
5. **instruction.settlement.corrected** - Settlement data corrected (bi-temporal correction)

## Architecture

### PeeGeeQ API Design

PeeGeeQ uses a layered reactive architecture:

```
Vert.x 5.x Future (PeeGeeQ internal)
    ↓ .toCompletionStage().toCompletableFuture()
CompletableFuture (PeeGeeQ public API)
    ↓ Mono.fromFuture()
Mono/Flux (Spring WebFlux)
```

**Current State:**
- PeeGeeQ internally uses Vert.x 5.x `Future` for reactive operations
- Public API exposes `CompletableFuture` for broad compatibility
- Conversion happens via `.toCompletionStage().toCompletableFuture()`

**Proposed Enhancement:**
- Expose native Vert.x 5.x `Future` API alongside `CompletableFuture`
- Benefits: Better performance, more composable, clearer intent
- Dual API pattern allows users to choose based on their stack

### Reactive Adapter Pattern

The `ReactiveBiTemporalAdapter` demonstrates BOTH approaches:

**Approach A: Using CompletableFuture API (Current)**
```java
public <T> Mono<BiTemporalEvent<T>> toMono(CompletableFuture<BiTemporalEvent<T>> future) {
    return Mono.fromFuture(future);
}
```

**Approach B: Using Native Vert.x Future API (Proposed)**
```java
public <T> Mono<BiTemporalEvent<T>> toMonoFromVertxFuture(Future<BiTemporalEvent<T>> future) {
    return Mono.fromCompletionStage(future.toCompletionStage());
}
```

## Project Structure

```
springboot2bitemporal/
├── adapter/
│   └── ReactiveBiTemporalAdapter.java    # Dual reactive adapter (CompletableFuture + Vert.x Future)
├── config/
│   ├── ReactiveBiTemporalConfig.java     # Spring configuration
│   └── ReactiveBiTemporalProperties.java # Configuration properties
├── controller/
│   └── SettlementController.java         # REST endpoints
├── events/
│   └── SettlementEvent.java              # Settlement event payload
├── model/
│   └── SettlementStatus.java             # Settlement status enum
├── service/
│   └── SettlementService.java            # Business logic
└── SpringBoot2BitemporalApplication.java # Main application
```

## Running the Application

### Prerequisites

- Java 21+
- PostgreSQL 15+
- Maven 3.9+

### Configuration

Create `application-development.yml`:

```yaml
reactive-bitemporal:
  profile: development

peegeeq:
  database:
    host: localhost
    port: 5432
    name: peegeeq
    username: peegeeq
    password: peegeeq
```

### Start the Application

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=development
```

The application starts on port 8083.

## API Examples

### Submit Settlement Instruction

```bash
curl -X POST http://localhost:8083/api/settlements/submit \
  -H "Content-Type: application/json" \
  -d '{
    "instructionId": "SSI-12345",
    "tradeId": "TRD-67890",
    "counterparty": "CUSTODIAN-XYZ",
    "amount": "1000000.00",
    "currency": "USD",
    "settlementDate": "2025-10-10",
    "status": "SUBMITTED",
    "eventTime": "2025-10-07T10:00:00Z"
  }'
```

### Record Settlement Matched

```bash
curl -X POST http://localhost:8083/api/settlements/match \
  -H "Content-Type: application/json" \
  -d '{
    "instructionId": "SSI-12345",
    "tradeId": "TRD-67890",
    "counterparty": "CUSTODIAN-XYZ",
    "amount": "1000000.00",
    "currency": "USD",
    "settlementDate": "2025-10-10",
    "status": "MATCHED",
    "eventTime": "2025-10-07T11:00:00Z"
  }'
```

### Get Settlement History

```bash
curl http://localhost:8083/api/settlements/history/SSI-12345
```

### Get Settlement State at Point in Time

```bash
curl "http://localhost:8083/api/settlements/state-at/SSI-12345?pointInTime=2025-10-07T10:30:00Z"
```

### Correct Settlement Data

```bash
curl -X POST http://localhost:8083/api/settlements/correct \
  -H "Content-Type: application/json" \
  -d '{
    "instructionId": "SSI-12345",
    "tradeId": "TRD-67890",
    "counterparty": "CUSTODIAN-ABC",
    "amount": "1000000.00",
    "currency": "USD",
    "settlementDate": "2025-10-10",
    "status": "CORRECTED",
    "eventTime": "2025-10-07T12:00:00Z"
  }'
```

### View Corrections

```bash
curl http://localhost:8083/api/settlements/corrections/SSI-12345
```

## Key Features Demonstrated

### 1. Reactive Adapter Pattern

The `ReactiveBiTemporalAdapter` shows how to convert PeeGeeQ's async API to Spring WebFlux:

```java
// Convert CompletableFuture → Mono
public <T> Mono<BiTemporalEvent<T>> toMono(CompletableFuture<BiTemporalEvent<T>> future) {
    return Mono.fromFuture(future)
        .doOnError(error -> log.error("Error converting CompletableFuture to Mono", error))
        .doOnSuccess(result -> log.trace("Successfully converted CompletableFuture to Mono"));
}

// Convert CompletableFuture<List> → Flux
public <T> Flux<BiTemporalEvent<T>> toFlux(CompletableFuture<List<BiTemporalEvent<T>>> future) {
    return Mono.fromFuture(future)
        .flatMapMany(Flux::fromIterable)
        .doOnError(error -> log.error("Error converting CompletableFuture to Flux", error))
        .doOnComplete(() -> log.trace("Successfully converted CompletableFuture to Flux"));
}
```

### 2. Bi-Temporal Event Appending

```java
public Mono<BiTemporalEvent<SettlementEvent>> recordSettlement(String eventType, SettlementEvent event) {
    CompletableFuture<BiTemporalEvent<SettlementEvent>> future = 
        eventStore.append(eventType, event, event.getEventTime());
    
    return adapter.toMono(future);
}
```

### 3. Point-in-Time Queries

```java
public Mono<SettlementEvent> getSettlementStateAt(String instructionId, Instant pointInTime) {
    return getSettlementHistory(instructionId)
        .filter(event -> !event.getValidTime().isAfter(pointInTime))
        .sort((a, b) -> b.getValidTime().compareTo(a.getValidTime()))
        .next()
        .map(BiTemporalEvent::getPayload);
}
```

### 4. Bi-Temporal Corrections

```java
public Mono<BiTemporalEvent<SettlementEvent>> correctSettlement(String instructionId, 
                                                                SettlementEvent correctedEvent) {
    return recordSettlement("instruction.settlement.corrected", correctedEvent);
}
```

## Testing

Run the integration test:

```bash
mvn test -Dtest=SpringBoot2BitemporalApplicationTest
```

The test verifies:
- Spring Boot WebFlux context loading
- PeeGeeQ Manager initialization
- Bi-Temporal Event Store configuration
- Reactive adapter integration

## Benefits of Native Vert.x Future API

If PeeGeeQ were to expose the native Vert.x Future API:

1. **Performance** - Eliminates intermediate CompletableFuture conversion
2. **Composability** - Can use Vert.x operators (`.compose()`, `.map()`, `.recover()`)
3. **Flexibility** - Users choose based on their stack
4. **Clarity** - Shows PeeGeeQ is Vert.x-based

## License

Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd

Licensed under the Apache License, Version 2.0

