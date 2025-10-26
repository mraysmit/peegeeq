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

import dev.mars.peegeeq.examples.springboot2.model.Customer;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Customer entity demonstrating pure CRUD operations without messaging.
 * 
 * This repository shows that PeeGeeQ can handle all standard database operations
 * using Vert.x SQL Client without needing R2DBC or any other data access framework.
 * 
 * Key patterns demonstrated:
 * - INSERT, SELECT, UPDATE, DELETE operations
 * - Pagination with LIMIT/OFFSET
 * - Full-text search with ILIKE
 * - Existence checks with COUNT
 * - Manual SQL mapping (no annotations needed)
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@Repository
public class CustomerRepository {
    
    private static final Logger log = LoggerFactory.getLogger(CustomerRepository.class);
    
    /**
     * Save a new customer.
     */
    public Future<Customer> save(Customer customer, SqlConnection connection) {
        String sql = """
            INSERT INTO customers (id, name, email, phone, address, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            """;
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAddress(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
            ))
            .map(result -> {
                log.info("Customer saved successfully: {}", customer.getId());
                return customer;
            });
    }
    
    /**
     * Find customer by ID.
     */
    public Future<Optional<Customer>> findById(String id, SqlConnection connection) {
        String sql = "SELECT * FROM customers WHERE id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.size() == 0) {
                    log.debug("Customer not found: {}", id);
                    return Optional.empty();
                }
                Customer customer = mapRowToCustomer(rowSet.iterator().next());
                log.debug("Customer found: {}", customer);
                return Optional.of(customer);
            });
    }
    
    /**
     * Update existing customer.
     */
    public Future<Customer> update(Customer customer, SqlConnection connection) {
        String sql = """
            UPDATE customers 
            SET name = $1, email = $2, phone = $3, address = $4, updated_at = $5 
            WHERE id = $6
            """;
        
        customer.setUpdatedAt(Instant.now());
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAddress(),
                customer.getUpdatedAt(),
                customer.getId()
            ))
            .map(result -> {
                log.info("Customer updated successfully: {}", customer.getId());
                return customer;
            });
    }
    
    /**
     * Delete customer by ID.
     */
    public Future<Void> deleteById(String id, SqlConnection connection) {
        String sql = "DELETE FROM customers WHERE id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(result -> {
                log.info("Customer deleted successfully: {}", id);
                return null;
            });
    }
    
    /**
     * Find all customers with pagination.
     */
    public Future<List<Customer>> findAll(int page, int size, SqlConnection connection) {
        String sql = "SELECT * FROM customers ORDER BY created_at DESC LIMIT $1 OFFSET $2";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(size, page * size))
            .map(rowSet -> {
                List<Customer> customers = new ArrayList<>();
                rowSet.forEach(row -> customers.add(mapRowToCustomer(row)));
                log.debug("Found {} customers (page {}, size {})", customers.size(), page, size);
                return customers;
            });
    }
    
    /**
     * Search customers by name (case-insensitive).
     */
    public Future<List<Customer>> searchByName(String name, SqlConnection connection) {
        String sql = "SELECT * FROM customers WHERE name ILIKE $1 ORDER BY name";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of("%" + name + "%"))
            .map(rowSet -> {
                List<Customer> customers = new ArrayList<>();
                rowSet.forEach(row -> customers.add(mapRowToCustomer(row)));
                log.debug("Found {} customers matching name: {}", customers.size(), name);
                return customers;
            });
    }
    
    /**
     * Check if customer exists by email.
     */
    public Future<Boolean> existsByEmail(String email, SqlConnection connection) {
        String sql = "SELECT COUNT(*) FROM customers WHERE email = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(email))
            .map(rowSet -> {
                long count = rowSet.iterator().next().getLong(0);
                log.debug("Customer exists check for email {}: {}", email, count > 0);
                return count > 0;
            });
    }
    
    /**
     * Count total customers.
     */
    public Future<Long> count(SqlConnection connection) {
        String sql = "SELECT COUNT(*) FROM customers";
        
        return connection.preparedQuery(sql)
            .execute()
            .map(rowSet -> {
                long count = rowSet.iterator().next().getLong(0);
                log.debug("Total customers: {}", count);
                return count;
            });
    }
    
    /**
     * Map database row to Customer object.
     */
    private Customer mapRowToCustomer(Row row) {
        return new Customer(
            row.getString("id"),
            row.getString("name"),
            row.getString("email"),
            row.getString("phone"),
            row.getString("address"),
            row.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC),
            row.getLocalDateTime("updated_at").toInstant(ZoneOffset.UTC)
        );
    }
}

