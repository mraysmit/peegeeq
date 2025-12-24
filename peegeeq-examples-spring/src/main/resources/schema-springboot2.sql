-- Database schema for Spring Boot Reactive (springboot2) example
-- This schema supports:
-- 1. Order and OrderItem entities (with outbox pattern)
-- 2. Customer entity (pure CRUD without messaging)
-- 3. Product and Category entities (complex queries without messaging)

-- Drop tables if they exist (for development/testing)
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS categories CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS outbox_consumer_groups CASCADE;
DROP TABLE IF EXISTS outbox CASCADE;
DROP TABLE IF EXISTS dead_letter_queue CASCADE;
DROP TABLE IF EXISTS queue_messages CASCADE;

-- Orders table
CREATE TABLE orders (
    id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Order items table
CREATE TABLE order_items (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    name VARCHAR(500) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    price DECIMAL(19, 2) NOT NULL CHECK (price > 0),
    CONSTRAINT fk_order_items_order_id FOREIGN KEY (order_id) 
        REFERENCES orders(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

-- Comments for documentation
COMMENT ON TABLE orders IS 'Orders table for Spring Boot Reactive example with R2DBC';
COMMENT ON TABLE order_items IS 'Order items table for Spring Boot Reactive example with R2DBC';

COMMENT ON COLUMN orders.id IS 'Unique order identifier (UUID)';
COMMENT ON COLUMN orders.customer_id IS 'Customer identifier';
COMMENT ON COLUMN orders.amount IS 'Total order amount';
COMMENT ON COLUMN orders.status IS 'Order status: CREATED, VALIDATED, RESERVED, COMPLETED, CANCELLED';
COMMENT ON COLUMN orders.created_at IS 'Order creation timestamp';

COMMENT ON COLUMN order_items.id IS 'Unique order item identifier (UUID)';
COMMENT ON COLUMN order_items.order_id IS 'Reference to parent order';
COMMENT ON COLUMN order_items.product_id IS 'Product identifier';
COMMENT ON COLUMN order_items.name IS 'Product name';
COMMENT ON COLUMN order_items.quantity IS 'Quantity ordered (must be positive)';
COMMENT ON COLUMN order_items.price IS 'Unit price (must be positive)';

-- ============================================================================
-- PURE CRUD EXAMPLES (NO MESSAGING)
-- ============================================================================

-- Customers table (demonstrates pure CRUD operations)
CREATE TABLE customers (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(50),
    address TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Categories table (for product categorization)
CREATE TABLE categories (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Products table (demonstrates complex queries, JOINs, batch operations)
CREATE TABLE products (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    description TEXT,
    category_id VARCHAR(255),
    price DECIMAL(19, 2) NOT NULL CHECK (price >= 0),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_products_category_id FOREIGN KEY (category_id)
        REFERENCES categories(id) ON DELETE SET NULL
);

-- Indexes for performance
CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_name ON customers(name);
CREATE INDEX idx_customers_created_at ON customers(created_at DESC);

CREATE INDEX idx_categories_name ON categories(name);

CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_name ON products(name);
CREATE INDEX idx_products_price ON products(price);
CREATE INDEX idx_products_active ON products(active);
CREATE INDEX idx_products_created_at ON products(created_at DESC);

-- Comments for documentation
COMMENT ON TABLE customers IS 'Customers table demonstrating pure CRUD operations without messaging';
COMMENT ON TABLE categories IS 'Product categories for demonstrating JOIN operations';
COMMENT ON TABLE products IS 'Products table demonstrating complex queries, JOINs, and batch operations';

COMMENT ON COLUMN customers.id IS 'Unique customer identifier (UUID)';
COMMENT ON COLUMN customers.name IS 'Customer full name';
COMMENT ON COLUMN customers.email IS 'Customer email (unique)';
COMMENT ON COLUMN customers.phone IS 'Customer phone number';
COMMENT ON COLUMN customers.address IS 'Customer address';
COMMENT ON COLUMN customers.created_at IS 'Customer creation timestamp';
COMMENT ON COLUMN customers.updated_at IS 'Customer last update timestamp';

COMMENT ON COLUMN categories.id IS 'Unique category identifier (UUID)';
COMMENT ON COLUMN categories.name IS 'Category name (unique)';
COMMENT ON COLUMN categories.description IS 'Category description';
COMMENT ON COLUMN categories.created_at IS 'Category creation timestamp';

COMMENT ON COLUMN products.id IS 'Unique product identifier (UUID)';
COMMENT ON COLUMN products.name IS 'Product name';
COMMENT ON COLUMN products.description IS 'Product description';
COMMENT ON COLUMN products.category_id IS 'Reference to category (nullable)';
COMMENT ON COLUMN products.price IS 'Product price (must be non-negative)';
COMMENT ON COLUMN products.active IS 'Product active status (soft delete)';
COMMENT ON COLUMN products.created_at IS 'Product creation timestamp';
COMMENT ON COLUMN products.updated_at IS 'Product last update timestamp';

-- Sample data for testing
INSERT INTO categories (id, name, description) VALUES
    ('cat-electronics', 'Electronics', 'Electronic devices and accessories'),
    ('cat-books', 'Books', 'Books and publications'),
    ('cat-clothing', 'Clothing', 'Apparel and accessories');

INSERT INTO products (id, name, description, category_id, price, active) VALUES
    ('prod-laptop', 'Laptop Computer', 'High-performance laptop', 'cat-electronics', 1299.99, true),
    ('prod-mouse', 'Wireless Mouse', 'Ergonomic wireless mouse', 'cat-electronics', 29.99, true),
    ('prod-book1', 'Programming Guide', 'Comprehensive programming guide', 'cat-books', 49.99, true),
    ('prod-shirt', 'Cotton T-Shirt', 'Comfortable cotton t-shirt', 'cat-clothing', 19.99, true);

INSERT INTO customers (id, name, email, phone, address) VALUES
    ('cust-001', 'Alice Johnson', 'alice@example.com', '+1-555-0001', '123 Main St, City, State'),
    ('cust-002', 'Bob Smith', 'bob@example.com', '+1-555-0002', '456 Oak Ave, City, State'),
    ('cust-003', 'Carol White', 'carol@example.com', '+1-555-0003', '789 Pine Rd, City, State');

-- ============================================================================
-- PEEGEEQ INFRASTRUCTURE TABLES
-- ============================================================================

-- Outbox pattern table for reliable message delivery
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    version INT DEFAULT 0,
    headers JSONB DEFAULT '{}',
    error_message TEXT,
    correlation_id VARCHAR(255),
    message_group VARCHAR(255),
    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
);

-- Outbox consumer groups table for consumer group coordination
CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    processed_at TIMESTAMP WITH TIME ZONE,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(message_id, group_name)
);

-- Dead letter queue table for failed messages
CREATE TABLE IF NOT EXISTS dead_letter_queue (
    id BIGSERIAL PRIMARY KEY,
    original_table VARCHAR(50) NOT NULL,
    original_id BIGINT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    failure_reason TEXT NOT NULL,
    retry_count INT NOT NULL,
    headers JSONB DEFAULT '{}',
    correlation_id VARCHAR(255),
    message_group VARCHAR(255)
);

-- Native queue messages table for native queue pattern
CREATE TABLE IF NOT EXISTS queue_messages (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    lock_id BIGINT,
    lock_until TIMESTAMP WITH TIME ZONE,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
    headers JSONB DEFAULT '{}',
    correlation_id VARCHAR(255),
    message_group VARCHAR(255),
    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    idempotency_key VARCHAR(255)
);

-- Indexes for PeeGeeQ infrastructure tables
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox(status);
CREATE INDEX IF NOT EXISTS idx_outbox_topic ON outbox(topic);
CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON outbox(created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_next_retry_at ON outbox(next_retry_at);
CREATE INDEX IF NOT EXISTS idx_outbox_priority ON outbox(priority);

CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_outbox_message_id ON outbox_consumer_groups(outbox_message_id);
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_status ON outbox_consumer_groups(status);

CREATE INDEX IF NOT EXISTS idx_dead_letter_queue_topic ON dead_letter_queue(topic);
CREATE INDEX IF NOT EXISTS idx_dead_letter_queue_failed_at ON dead_letter_queue(failed_at);

CREATE INDEX IF NOT EXISTS idx_queue_messages_topic ON queue_messages(topic);
CREATE INDEX IF NOT EXISTS idx_queue_messages_status ON queue_messages(status);
CREATE INDEX IF NOT EXISTS idx_queue_messages_visible_at ON queue_messages(visible_at);
CREATE INDEX IF NOT EXISTS idx_queue_messages_priority ON queue_messages(priority);
CREATE UNIQUE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key ON queue_messages(topic, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key_lookup ON queue_messages(idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Comments for PeeGeeQ infrastructure tables
COMMENT ON TABLE outbox IS 'Outbox pattern table for reliable message delivery';
COMMENT ON TABLE outbox_consumer_groups IS 'Consumer group coordination for outbox messages';
COMMENT ON TABLE dead_letter_queue IS 'Dead letter queue for failed messages';
COMMENT ON TABLE queue_messages IS 'Native queue messages table';

