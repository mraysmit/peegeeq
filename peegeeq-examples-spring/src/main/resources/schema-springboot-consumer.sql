-- Schema for Spring Boot Consumer Example
-- Demonstrates consumer group pattern with order processing

-- Orders table - stores processed orders
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    processed_by VARCHAR(255)
);

-- Consumer status table - tracks consumer health and metrics
CREATE TABLE IF NOT EXISTS consumer_status (
    consumer_id VARCHAR(255) PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    messages_processed BIGINT NOT NULL DEFAULT 0,
    last_message_at TIMESTAMP,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_consumer_status_updated_at ON consumer_status(updated_at);

