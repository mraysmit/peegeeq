-- Database schema for Spring Boot (springboot) example
-- This schema supports the Order and OrderItem entities with Vert.x SQL Client

-- Drop tables if they exist (for development/testing)
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Order items table
CREATE TABLE IF NOT EXISTS order_items (
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
CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product_id ON order_items(product_id);

-- Comments for documentation
COMMENT ON TABLE orders IS 'Orders table for Spring Boot example with Vert.x SQL Client';
COMMENT ON TABLE order_items IS 'Order items table for Spring Boot example with Vert.x SQL Client';

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

