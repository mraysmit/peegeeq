-- Database schema for Spring Boot Bi-Temporal Transaction Coordination example
-- This schema is minimal as the example uses only PeeGeeQ infrastructure tables
-- (outbox and bitemporal_event_log) which are created by PeeGeeQTestSchemaInitializer

-- The bi-temporal transaction coordination example is purely event-based
-- and doesn't require application-specific tables.
-- All data is stored as events in the bi-temporal event stores.

-- This file exists to satisfy the Spring Boot configuration requirement
-- but contains no actual schema definitions since the PeeGeeQ infrastructure
-- handles all table creation through the centralized schema initializer.

-- Note: In a real production environment, you might want to add:
-- 1. Application-specific lookup tables
-- 2. Materialized views for query optimization  
-- 3. Indexes for performance tuning
-- 4. Partitioning strategies for large datasets

-- For this example, all state is managed through events in the bi-temporal stores:
-- - OrderEvent store: Order lifecycle events
-- - InventoryEvent store: Stock movement events  
-- - PaymentEvent store: Payment processing events
-- - AuditEvent store: Regulatory compliance events
