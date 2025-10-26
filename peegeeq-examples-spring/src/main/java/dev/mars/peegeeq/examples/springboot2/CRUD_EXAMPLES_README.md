# Pure CRUD Examples for Spring Boot Reactive (springboot2)

This directory contains examples demonstrating **pure CRUD and complex query operations** using PeeGeeQ's `DatabaseService` **without** requiring the outbox pattern or any messaging infrastructure.

## Purpose

These examples show that PeeGeeQ can handle **all types of database operations** without needing R2DBC, JPA, or any other data access framework. The outbox pattern (demonstrated in `OrderService`) is **optional** and only needed when you want transactional messaging.

## Examples Overview

### 1. Customer Management (Pure CRUD)

**Location**: `model/Customer.java`, `repository/CustomerRepository.java`, `service/CustomerService.java`, `controller/CustomerController.java`

**Demonstrates**:
- Standard CRUD operations (Create, Read, Update, Delete)
- Pagination with LIMIT/OFFSET
- Full-text search with ILIKE
- Existence checks with COUNT
- Manual SQL mapping (no annotations needed)

**Key Pattern**: Use `withConnection()` for single operations (auto-commits)

**REST Endpoints**:
```
POST   /api/customers          - Create customer
GET    /api/customers/{id}     - Get customer by ID
PUT    /api/customers/{id}     - Update customer
DELETE /api/customers/{id}     - Delete customer
GET    /api/customers          - List customers (paginated)
GET    /api/customers/search   - Search customers by name
GET    /api/customers/exists   - Check if email exists
GET    /api/customers/count    - Count total customers
```

**Example Usage**:
```bash
# Create customer
curl -X POST http://localhost:8082/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "phone": "+1-555-1234",
    "address": "123 Main St"
  }'

# Search customers
curl "http://localhost:8082/api/customers/search?name=John"

# Check email exists
curl "http://localhost:8082/api/customers/exists?email=john@example.com"
```

---

### 2. Product Catalog (Complex Queries)

**Location**: `model/Product.java`, `model/Category.java`, `model/ProductWithCategory.java`, `repository/ProductRepository.java`, `service/ProductService.java`, `controller/ProductController.java`

**Demonstrates**:
- JOINs with related tables (products + categories)
- Batch operations with `executeBatch()`
- Conditional updates (update only if changed)
- Soft deletes (deactivate instead of delete)
- Aggregations with GROUP BY
- Price range queries

**Key Patterns**:
- Use `withConnection()` for read operations
- Use `withTransaction()` for batch operations

**REST Endpoints**:
```
POST   /api/products                    - Create product
GET    /api/products/{id}               - Get product by ID
GET    /api/products/{id}/with-category - Get product with category (JOIN)
GET    /api/products                    - List all products
POST   /api/products/import             - Batch import products
PATCH  /api/products/{id}/price         - Update price if changed
DELETE /api/products/{id}               - Deactivate product (soft delete)
GET    /api/products/stats/by-category  - Count products by category
GET    /api/products/by-category/{id}   - Find products by category
GET    /api/products/by-price-range     - Find products by price range
```

**Example Usage**:
```bash
# Create product
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Wireless Keyboard",
    "description": "Mechanical keyboard",
    "categoryId": "cat-electronics",
    "price": 89.99
  }'

# Get product with category (JOIN)
curl "http://localhost:8082/api/products/{id}/with-category"

# Batch import
curl -X POST http://localhost:8082/api/products/import \
  -H "Content-Type: application/json" \
  -d '[
    {"name": "Product 1", "categoryId": "cat-electronics", "price": 99.99},
    {"name": "Product 2", "categoryId": "cat-books", "price": 29.99}
  ]'

# Get stats by category (aggregation)
curl "http://localhost:8082/api/products/stats/by-category"

# Find by price range
curl "http://localhost:8082/api/products/by-price-range?minPrice=10&maxPrice=50"
```

---

## Key Takeaways

### 1. No R2DBC Needed

All examples use **only** PeeGeeQ's `DatabaseService` and Vert.x SQL Client. No R2DBC, JPA, Hibernate, or any other data access framework required.

### 2. Reactive Without R2DBC

The `ReactiveOutboxAdapter` converts PeeGeeQ's `CompletableFuture` to Spring WebFlux's `Mono`/`Flux`, giving you full reactive support without R2DBC.

### 3. Connection Management Patterns

```java
// Single operation (auto-commits)
ConnectionProvider cp = databaseService.getConnectionProvider();
return cp.withConnection("peegeeq-main", connection -> 
    repository.save(entity, connection)
);

// Multi-step operation (explicit transaction)
return cp.withTransaction("peegeeq-main", connection -> 
    repository.saveAll(entities, connection)
);
```

### 4. Outbox Pattern is Optional

The `OrderService` demonstrates the outbox pattern for transactional messaging. The `CustomerService` and `ProductService` show that you **don't need** the outbox pattern for regular CRUD operations.

### 5. Complex Queries Work Perfectly

- **JOINs**: `ProductWithCategory` demonstrates LEFT JOIN
- **Aggregations**: `countByCategory()` demonstrates GROUP BY
- **Batch operations**: `saveAll()` demonstrates `executeBatch()`
- **Conditional updates**: `updatePriceIfChanged()` demonstrates WHERE conditions
- **Soft deletes**: `deactivate()` demonstrates UPDATE instead of DELETE

---

## Running the Examples

### 1. Start PostgreSQL

Ensure PostgreSQL is running with the schema loaded:

```bash
psql -U postgres -d peegeeq -f src/main/resources/schema-springboot2.sql
```

### 2. Start the Application

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=springboot2
```

The application starts on port **8082**.

### 3. Test the Endpoints

Use the curl commands above or import the Postman collection (if available).

---

## Comparison: With vs Without Messaging

| Feature | OrderService (With Outbox) | CustomerService (Without Messaging) |
|---------|---------------------------|-------------------------------------|
| **Pattern** | Transactional outbox | Pure CRUD |
| **Transaction** | `withTransaction()` + `sendInTransaction()` | `withConnection()` |
| **Use Case** | Need to publish events | Just need to save data |
| **Complexity** | Higher (dual writes) | Lower (single write) |
| **Dependencies** | DatabaseService + OutboxProducer | DatabaseService only |

**Key Point**: Use the outbox pattern **only when you need transactional messaging**. For regular CRUD, use `withConnection()` or `withTransaction()` directly.

---

## Database Schema

The schema includes:

1. **orders** and **order_items** - For outbox pattern examples
2. **customers** - For pure CRUD examples
3. **categories** and **products** - For complex query examples

All tables are created by `schema-springboot2.sql` with sample data included.

---

## Further Reading

- [SPRING_BOOT_INTEGRATION_GUIDE.md](../../../../../../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md) - Complete integration guide
- [Example Use Cases](../../../../../../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md#example-use-cases) - Detailed examples with code
- [Common Mistakes](../../../../../../../docs/SPRING_BOOT_INTEGRATION_GUIDE.md#common-mistakes) - What to avoid

---

## Summary

These examples prove that **PeeGeeQ can handle all database operations** without R2DBC:

✅ Pure CRUD operations  
✅ Complex queries with JOINs  
✅ Batch operations  
✅ Aggregations and GROUP BY  
✅ Conditional updates  
✅ Soft deletes  
✅ Full reactive support with Mono/Flux  

**The outbox pattern is optional** - use it only when you need transactional messaging!

