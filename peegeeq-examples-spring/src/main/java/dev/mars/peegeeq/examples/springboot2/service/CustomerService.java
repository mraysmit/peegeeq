package dev.mars.peegeeq.examples.springboot2.service;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.examples.springboot2.adapter.ReactiveOutboxAdapter;
import dev.mars.peegeeq.examples.springboot2.model.Customer;
import dev.mars.peegeeq.examples.springboot2.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service layer for Customer operations demonstrating pure CRUD without messaging.
 * 
 * This service shows how to use PeeGeeQ's DatabaseService for standard
 * database operations without any outbox pattern or messaging infrastructure.
 * 
 * Key patterns:
 * - Use withConnection() for single operations (auto-commits)
 * - Use withTransaction() for multi-step operations
 * - Convert CompletableFuture to Mono/Flux for reactive APIs
 * - No R2DBC needed - everything works with Vert.x SQL Client
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@Service
public class CustomerService {
    
    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    
    private final DatabaseService databaseService;
    private final CustomerRepository customerRepository;
    private final ReactiveOutboxAdapter adapter;
    
    public CustomerService(DatabaseService databaseService, 
                          CustomerRepository customerRepository,
                          ReactiveOutboxAdapter adapter) {
        this.databaseService = databaseService;
        this.customerRepository = customerRepository;
        this.adapter = adapter;
    }
    
    /**
     * Create a new customer.
     * Uses withConnection() for single operation (auto-commits).
     */
    public Mono<Customer> createCustomer(Customer customer) {
        log.info("Creating customer: {}", customer.getName());
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                customerRepository.save(customer, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Find customer by ID.
     */
    public Mono<Customer> findById(String id) {
        log.debug("Finding customer by ID: {}", id);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                customerRepository.findById(id, connection)
                    .map(opt -> opt.orElse(null))
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Update existing customer.
     * Uses withConnection() since it's a single operation.
     */
    public Mono<Customer> updateCustomer(Customer customer) {
        log.info("Updating customer: {}", customer.getId());
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                customerRepository.update(customer, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Delete customer by ID.
     */
    public Mono<Void> deleteCustomer(String id) {
        log.info("Deleting customer: {}", id);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMonoVoid(
            cp.withConnection("peegeeq-main", connection -> 
                customerRepository.deleteById(id, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Find all customers with pagination.
     * Returns Flux for streaming results.
     */
    public Flux<Customer> findAll(int page, int size) {
        log.debug("Finding all customers (page: {}, size: {})", page, size);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> 
                    customerRepository.findAll(page, size, connection)
                ).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }
    
    /**
     * Search customers by name.
     */
    public Flux<Customer> searchByName(String name) {
        log.debug("Searching customers by name: {}", name);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> 
                    customerRepository.searchByName(name, connection)
                ).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }
    
    /**
     * Check if customer exists by email.
     */
    public Mono<Boolean> existsByEmail(String email) {
        log.debug("Checking if customer exists by email: {}", email);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                customerRepository.existsByEmail(email, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Count total customers.
     */
    public Mono<Long> count() {
        log.debug("Counting total customers");
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                customerRepository.count(connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
}

