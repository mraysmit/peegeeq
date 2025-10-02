package dev.mars.peegeeq.examples.springboot2.repository;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.examples.springboot2.model.Product;
import dev.mars.peegeeq.examples.springboot2.model.ProductWithCategory;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repository for Product entity demonstrating complex queries without messaging.
 * 
 * This repository shows advanced database operations including:
 * - JOINs with related tables
 * - Batch operations with executeBatch()
 * - Conditional updates
 * - Soft deletes
 * - Aggregations and GROUP BY
 * - All using only Vert.x SQL Client
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@Repository
public class ProductRepository {
    
    private static final Logger log = LoggerFactory.getLogger(ProductRepository.class);
    
    /**
     * Save a new product.
     */
    public Future<Product> save(Product product, SqlConnection connection) {
        String sql = """
            INSERT INTO products (id, name, description, category_id, price, active, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            """;
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getPrice(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
            ))
            .map(result -> {
                log.info("Product saved successfully: {}", product.getId());
                return product;
            });
    }
    
    /**
     * Find product by ID.
     */
    public Future<Optional<Product>> findById(String id, SqlConnection connection) {
        String sql = "SELECT * FROM products WHERE id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.size() == 0) {
                    return Optional.empty();
                }
                return Optional.of(mapRowToProduct(rowSet.iterator().next()));
            });
    }
    
    /**
     * Find product with category information (JOIN).
     */
    public Future<Optional<ProductWithCategory>> findByIdWithCategory(String id, SqlConnection connection) {
        String sql = """
            SELECT p.*, c.name as category_name
            FROM products p
            LEFT JOIN categories c ON p.category_id = c.id
            WHERE p.id = $1
            """;
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.size() == 0) {
                    return Optional.empty();
                }
                return Optional.of(mapRowToProductWithCategory(rowSet.iterator().next()));
            });
    }
    
    /**
     * Find all products.
     */
    public Future<List<Product>> findAll(SqlConnection connection) {
        String sql = "SELECT * FROM products WHERE active = true ORDER BY name";
        
        return connection.preparedQuery(sql)
            .execute()
            .map(rowSet -> {
                List<Product> products = new ArrayList<>();
                rowSet.forEach(row -> products.add(mapRowToProduct(row)));
                log.debug("Found {} products", products.size());
                return products;
            });
    }
    
    /**
     * Batch insert products.
     */
    public Future<Integer> saveAll(List<Product> products, SqlConnection connection) {
        String sql = """
            INSERT INTO products (id, name, description, category_id, price, active, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            """;
        
        List<Tuple> batch = products.stream()
            .map(p -> Tuple.of(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getCategoryId(),
                p.getPrice(),
                p.isActive(),
                p.getCreatedAt(),
                p.getUpdatedAt()
            ))
            .collect(Collectors.toList());
        
        return connection.preparedQuery(sql)
            .executeBatch(batch)
            .map(result -> {
                log.info("Batch inserted {} products", products.size());
                return products.size();
            });
    }
    
    /**
     * Update product price only if it has changed.
     */
    public Future<Boolean> updatePriceIfChanged(String id, BigDecimal newPrice, SqlConnection connection) {
        String sql = """
            UPDATE products 
            SET price = $1, updated_at = $2 
            WHERE id = $3 AND price != $1
            """;
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(newPrice, Instant.now(), id))
            .map(result -> {
                boolean updated = result.rowCount() > 0;
                log.info("Product {} price update: {}", id, updated ? "changed" : "unchanged");
                return updated;
            });
    }
    
    /**
     * Soft delete (deactivate) product.
     */
    public Future<Void> deactivate(String id, SqlConnection connection) {
        String sql = "UPDATE products SET active = false, updated_at = $1 WHERE id = $2";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(Instant.now(), id))
            .map(result -> {
                log.info("Product deactivated: {}", id);
                return null;
            });
    }
    
    /**
     * Count products by category.
     */
    public Future<Map<String, Long>> countByCategory(SqlConnection connection) {
        String sql = """
            SELECT c.name, COUNT(p.id) as product_count
            FROM categories c
            LEFT JOIN products p ON c.id = p.category_id AND p.active = true
            GROUP BY c.name
            ORDER BY product_count DESC
            """;
        
        return connection.preparedQuery(sql)
            .execute()
            .map(rowSet -> {
                Map<String, Long> counts = new HashMap<>();
                rowSet.forEach(row -> counts.put(
                    row.getString("name"),
                    row.getLong("product_count")
                ));
                log.debug("Product counts by category: {}", counts);
                return counts;
            });
    }
    
    /**
     * Find products by category.
     */
    public Future<List<Product>> findByCategory(String categoryId, SqlConnection connection) {
        String sql = "SELECT * FROM products WHERE category_id = $1 AND active = true ORDER BY name";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(categoryId))
            .map(rowSet -> {
                List<Product> products = new ArrayList<>();
                rowSet.forEach(row -> products.add(mapRowToProduct(row)));
                return products;
            });
    }
    
    /**
     * Find products by price range.
     */
    public Future<List<Product>> findByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, SqlConnection connection) {
        String sql = """
            SELECT * FROM products 
            WHERE active = true AND price >= $1 AND price <= $2 
            ORDER BY price
            """;
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(minPrice, maxPrice))
            .map(rowSet -> {
                List<Product> products = new ArrayList<>();
                rowSet.forEach(row -> products.add(mapRowToProduct(row)));
                return products;
            });
    }
    
    /**
     * Map database row to Product object.
     */
    private Product mapRowToProduct(Row row) {
        return new Product(
            row.getString("id"),
            row.getString("name"),
            row.getString("description"),
            row.getString("category_id"),
            row.getBigDecimal("price"),
            row.getBoolean("active"),
            row.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC),
            row.getLocalDateTime("updated_at").toInstant(ZoneOffset.UTC)
        );
    }
    
    /**
     * Map database row to ProductWithCategory object.
     */
    private ProductWithCategory mapRowToProductWithCategory(Row row) {
        return new ProductWithCategory(
            row.getString("id"),
            row.getString("name"),
            row.getString("description"),
            row.getString("category_id"),
            row.getString("category_name"),
            row.getBigDecimal("price"),
            row.getBoolean("active"),
            row.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC)
        );
    }
}

