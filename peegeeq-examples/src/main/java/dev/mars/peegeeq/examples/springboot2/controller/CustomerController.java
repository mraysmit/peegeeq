package dev.mars.peegeeq.examples.springboot2.controller;

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
import dev.mars.peegeeq.examples.springboot2.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for Customer operations demonstrating pure CRUD without messaging.
 * 
 * This controller shows standard REST endpoints using Spring WebFlux reactive types
 * with PeeGeeQ's DatabaseService underneath. No R2DBC needed.
 * 
 * Endpoints:
 * - POST   /api/customers          - Create customer
 * - GET    /api/customers/{id}     - Get customer by ID
 * - PUT    /api/customers/{id}     - Update customer
 * - DELETE /api/customers/{id}     - Delete customer
 * - GET    /api/customers          - List customers (paginated)
 * - GET    /api/customers/search   - Search customers by name
 * - GET    /api/customers/exists   - Check if email exists
 * - GET    /api/customers/count    - Count total customers
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    
    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);
    
    private final CustomerService customerService;
    
    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }
    
    /**
     * Create a new customer.
     * 
     * Example:
     * POST /api/customers
     * {
     *   "name": "John Doe",
     *   "email": "john@example.com",
     *   "phone": "+1-555-1234",
     *   "address": "123 Main St"
     * }
     */
    @PostMapping
    public Mono<ResponseEntity<Customer>> createCustomer(@RequestBody Customer customer) {
        log.info("REST: Creating customer: {}", customer.getName());
        
        return customerService.createCustomer(customer)
            .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
            .onErrorResume(error -> {
                log.error("Error creating customer", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Get customer by ID.
     * 
     * Example:
     * GET /api/customers/123e4567-e89b-12d3-a456-426614174000
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Customer>> getCustomer(@PathVariable String id) {
        log.debug("REST: Getting customer: {}", id);

        return customerService.findById(id)
            .map(customer -> customer != null ? ResponseEntity.ok(customer) : ResponseEntity.notFound().build());
    }
    
    /**
     * Update existing customer.
     * 
     * Example:
     * PUT /api/customers/123e4567-e89b-12d3-a456-426614174000
     * {
     *   "name": "John Doe Updated",
     *   "email": "john.updated@example.com"
     * }
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<Customer>> updateCustomer(
            @PathVariable String id, 
            @RequestBody Customer customer) {
        log.info("REST: Updating customer: {}", id);
        
        customer.setId(id);
        return customerService.updateCustomer(customer)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                log.error("Error updating customer", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Delete customer.
     * 
     * Example:
     * DELETE /api/customers/123e4567-e89b-12d3-a456-426614174000
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteCustomer(@PathVariable String id) {
        log.info("REST: Deleting customer: {}", id);
        
        return customerService.deleteCustomer(id)
            .then(Mono.just(ResponseEntity.noContent().<Void>build()))
            .onErrorResume(error -> {
                log.error("Error deleting customer", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * List customers with pagination.
     * 
     * Example:
     * GET /api/customers?page=0&size=20
     */
    @GetMapping
    public Flux<Customer> listCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("REST: Listing customers (page: {}, size: {})", page, size);
        
        return customerService.findAll(page, size)
            .onErrorResume(error -> {
                log.error("Error listing customers", error);
                return Flux.empty();
            });
    }
    
    /**
     * Search customers by name.
     * 
     * Example:
     * GET /api/customers/search?name=John
     */
    @GetMapping("/search")
    public Flux<Customer> searchCustomers(@RequestParam String name) {
        log.debug("REST: Searching customers by name: {}", name);
        
        return customerService.searchByName(name)
            .onErrorResume(error -> {
                log.error("Error searching customers", error);
                return Flux.empty();
            });
    }
    
    /**
     * Check if customer exists by email.
     * 
     * Example:
     * GET /api/customers/exists?email=john@example.com
     */
    @GetMapping("/exists")
    public Mono<ResponseEntity<Boolean>> checkEmailExists(@RequestParam String email) {
        log.debug("REST: Checking if email exists: {}", email);
        
        return customerService.existsByEmail(email)
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                log.error("Error checking email existence", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Count total customers.
     * 
     * Example:
     * GET /api/customers/count
     */
    @GetMapping("/count")
    public Mono<ResponseEntity<Long>> countCustomers() {
        log.debug("REST: Counting customers");
        
        return customerService.count()
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                log.error("Error counting customers", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
}

