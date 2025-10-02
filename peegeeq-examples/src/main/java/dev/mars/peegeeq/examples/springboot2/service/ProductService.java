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
import dev.mars.peegeeq.examples.springboot2.model.Product;
import dev.mars.peegeeq.examples.springboot2.model.ProductWithCategory;
import dev.mars.peegeeq.examples.springboot2.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Service layer for Product operations demonstrating complex queries without messaging.
 * 
 * This service shows advanced database operations including:
 * - JOINs with related tables
 * - Batch operations
 * - Conditional updates
 * - Soft deletes
 * - Aggregations
 * - All using only PeeGeeQ's DatabaseService
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@Service
public class ProductService {
    
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    
    private final DatabaseService databaseService;
    private final ProductRepository productRepository;
    private final ReactiveOutboxAdapter adapter;
    
    public ProductService(DatabaseService databaseService,
                         ProductRepository productRepository,
                         ReactiveOutboxAdapter adapter) {
        this.databaseService = databaseService;
        this.productRepository = productRepository;
        this.adapter = adapter;
    }
    
    /**
     * Create a new product.
     */
    public Mono<Product> createProduct(Product product) {
        log.info("Creating product: {}", product.getName());
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                productRepository.save(product, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Find product by ID.
     */
    public Mono<Product> findById(String id) {
        log.debug("Finding product by ID: {}", id);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                productRepository.findById(id, connection)
                    .map(opt -> opt.orElse(null))
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Find product with category information (demonstrates JOIN).
     */
    public Mono<ProductWithCategory> findByIdWithCategory(String id) {
        log.debug("Finding product with category: {}", id);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                productRepository.findByIdWithCategory(id, connection)
                    .map(opt -> opt.orElse(null))
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Find all products.
     */
    public Flux<Product> findAllProducts() {
        log.debug("Finding all products");
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> 
                    productRepository.findAll(connection)
                ).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }
    
    /**
     * Batch import products (demonstrates executeBatch).
     * Uses withTransaction() for atomicity.
     */
    public Mono<Integer> importProducts(List<Product> products) {
        log.info("Importing {} products", products.size());
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withTransaction("peegeeq-main", connection -> 
                productRepository.saveAll(products, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Update product price only if it has changed (demonstrates conditional update).
     */
    public Mono<Boolean> updatePriceIfChanged(String id, BigDecimal newPrice) {
        log.info("Updating price for product {}: {}", id, newPrice);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                productRepository.updatePriceIfChanged(id, newPrice, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Deactivate product (demonstrates soft delete).
     */
    public Mono<Void> deactivateProduct(String id) {
        log.info("Deactivating product: {}", id);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMonoVoid(
            cp.withConnection("peegeeq-main", connection -> 
                productRepository.deactivate(id, connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Count products by category (demonstrates aggregation with GROUP BY).
     */
    public Mono<Map<String, Long>> countByCategory() {
        log.debug("Counting products by category");
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return adapter.toMono(
            cp.withConnection("peegeeq-main", connection -> 
                productRepository.countByCategory(connection)
            ).toCompletionStage().toCompletableFuture()
        );
    }
    
    /**
     * Find products by category.
     */
    public Flux<Product> findByCategory(String categoryId) {
        log.debug("Finding products by category: {}", categoryId);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> 
                    productRepository.findByCategory(categoryId, connection)
                ).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }
    
    /**
     * Find products by price range.
     */
    public Flux<Product> findByPriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        log.debug("Finding products by price range: {} - {}", minPrice, maxPrice);
        
        ConnectionProvider cp = databaseService.getConnectionProvider();
        return Flux.from(
            adapter.toMono(
                cp.withConnection("peegeeq-main", connection -> 
                    productRepository.findByPriceRange(minPrice, maxPrice, connection)
                ).toCompletionStage().toCompletableFuture()
            ).flatMapMany(Flux::fromIterable)
        );
    }
}

