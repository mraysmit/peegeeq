# Walkthrough: PeeGeeQ Examples Expansion

We have successfully closed the gap between the design documentation and the actual `peegeeq-examples` codebase! All the advanced architectural concepts from the outbox guide and the event catalogue are now fully realized in code.

## 1. Pure Vert.x Outbox Patterns

We created a new `outbox` package to demonstrate the infrastructure capabilities described in the `PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md`:

- **[VertxOutboxProducerExample.java](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/outbox/VertxOutboxProducerExample.java)**: Shows an `OrderVerticle` interacting with the Event Bus, publishing events non-blockingly using `TransactionPropagation.CONTEXT`.
- **[VertxTransactionParticipationExample.java](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/outbox/VertxTransactionParticipationExample.java)**: Shows how to weave the outbox into a manually managed Vert.x `SqlConnection` without breaking the transaction boundary.
- **[VertxBatchOutboxExample.java](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/outbox/VertxBatchOutboxExample.java)**: Demonstrates batch operations processing multiple orders and tying them to a single atomic commit.

## 2. Financial Services Event Catalogue

We expanded the `fundscustody` example to cover all the domains outlined in the `PEEGEEQ_FINANCIAL_SERVICES_EVENT_CATALOGUE.md`.

- **Treasury Domain**: Added `TreasuryService.java`, handling [CashMovementEvent](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/fundscustody/events/CashMovementEvent.java) and [LiquidityCheckEvent](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/fundscustody/events/LiquidityCheckEvent.java).
- **Operational Exceptions**: Added `ExceptionManagementService.java`, demonstrating how to capture validation failures using [ExceptionManagedEvent](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/fundscustody/events/ExceptionManagedEvent.java) and resume processing via human intervention with [ManualRepairEvent](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/fundscustody/events/ManualRepairEvent.java).
- **README**: [Updated the fundscustody README](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/fundscustody/README.md) to reflect the new capabilities.

## 3. Advanced Bi-Temporal Querying & Projections

Finally, we tackled the "projection trap" with concrete code examples addressing the two separate time axes (System/Transaction Time vs Business/Valid Time):

- **[BiTemporalCorrectionsExample.java](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalCorrectionsExample.java)**: Demonstrates how to record an event, discover an error later, and record a backdated correction, showcasing how history is preserved across queries.
- **[BiTemporalProjectionExample.java](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalProjectionExample.java)**: Explicitly contrasts a `Current Business View` (incorporating all known facts across valid time) with an `As-At Audit View` (rebuilding the read-model as it was known by the system on a past date).
- **[FinancialEventQueryService.java](../../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/fundscustody/service/FinancialEventQueryService.java)**: Demonstrates the underlying `EventStore.query` syntax to filter by temporal ranges and retrieve corrections.
