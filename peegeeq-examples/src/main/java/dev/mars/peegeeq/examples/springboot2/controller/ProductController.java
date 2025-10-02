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

import dev.mars.peegeeq.examples.springboot2.model.Product;
import dev.mars.peegeeq.examples.springboot2.model.ProductWithCategory;
import dev.mars.peegeeq.examples.springboot2.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Product operations demonstrating complex queries without messaging.
 * 
 * This controller shows advanced REST endpoints including:
 * - JOINs with related tables
 * - Batch operations
 * - Conditional updates
 * - Soft deletes
 * - Aggregations
 * 
 * Endpoints:
 * - POST   /api/products                    - Create product
 * - GET    /api/products/{id}               - Get product by ID
 * - GET    /api/products/{id}/with-category - Get product with category (JOIN)
 * - GET    /api/products                    - List all products
 * - POST   /api/products/import             - Batch import products
 * - PATCH  /api/products/{id}/price         - Update price if changed
 * - DELETE /api/products/{id}               - Deactivate product (soft delete)
 * - GET    /api/products/stats/by-category  - Count products by category
 * - GET    /api/products/by-category/{id}   - Find products by category
 * - GET    /api/products/by-price-range     - Find products by price range
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {
    
    private static final Logger log = LoggerFactory.getLogger(ProductController.class);
    
    private final ProductService productService;
    
    public ProductController(ProductService productService) {
        this.productService = productService;
    }
    
    /**
     * Create a new product.
     */
    @PostMapping
    public Mono<ResponseEntity<Product>> createProduct(@RequestBody Product product) {
        log.info("REST: Creating product: {}", product.getName());
        
        return productService.createProduct(product)
            .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
            .onErrorResume(error -> {
                log.error("Error creating product", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Get product by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Product>> getProduct(@PathVariable String id) {
        log.debug("REST: Getting product: {}", id);

        return productService.findById(id)
            .map(product -> product != null ? ResponseEntity.ok(product) : ResponseEntity.<Product>notFound().build());
    }
    
    /**
     * Get product with category information (demonstrates JOIN).
     */
    @GetMapping("/{id}/with-category")
    public Mono<ResponseEntity<ProductWithCategory>> getProductWithCategory(@PathVariable String id) {
        log.debug("REST: Getting product with category: {}", id);

        return productService.findByIdWithCategory(id)
            .map(product -> product != null ? ResponseEntity.ok(product) : ResponseEntity.<ProductWithCategory>notFound().build());
    }
    
    /**
     * List all products.
     */
    @GetMapping
    public Flux<Product> listProducts() {
        log.debug("REST: Listing all products");
        
        return productService.findAllProducts()
            .onErrorResume(error -> {
                log.error("Error listing products", error);
                return Flux.empty();
            });
    }
    
    /**
     * Batch import products (demonstrates executeBatch).
     */
    @PostMapping("/import")
    public Mono<ResponseEntity<Map<String, Integer>>> importProducts(@RequestBody List<Product> products) {
        log.info("REST: Importing {} products", products.size());
        
        return productService.importProducts(products)
            .map(count -> ResponseEntity.ok(Map.of("imported", count)))
            .onErrorResume(error -> {
                log.error("Error importing products", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Update product price only if it has changed (demonstrates conditional update).
     */
    @PatchMapping("/{id}/price")
    public Mono<ResponseEntity<Map<String, Boolean>>> updatePrice(
            @PathVariable String id, 
            @RequestBody Map<String, BigDecimal> request) {
        log.info("REST: Updating price for product: {}", id);
        
        BigDecimal newPrice = request.get("price");
        return productService.updatePriceIfChanged(id, newPrice)
            .map(updated -> ResponseEntity.ok(Map.of("updated", updated)))
            .onErrorResume(error -> {
                log.error("Error updating price", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Deactivate product (demonstrates soft delete).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deactivateProduct(@PathVariable String id) {
        log.info("REST: Deactivating product: {}", id);
        
        return productService.deactivateProduct(id)
            .then(Mono.just(ResponseEntity.noContent().<Void>build()))
            .onErrorResume(error -> {
                log.error("Error deactivating product", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Count products by category (demonstrates aggregation with GROUP BY).
     */
    @GetMapping("/stats/by-category")
    public Mono<ResponseEntity<Map<String, Long>>> getStatsByCategory() {
        log.debug("REST: Getting product stats by category");
        
        return productService.countByCategory()
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                log.error("Error getting stats by category", error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    /**
     * Find products by category.
     */
    @GetMapping("/by-category/{categoryId}")
    public Flux<Product> getProductsByCategory(@PathVariable String categoryId) {
        log.debug("REST: Getting products by category: {}", categoryId);
        
        return productService.findByCategory(categoryId)
            .onErrorResume(error -> {
                log.error("Error getting products by category", error);
                return Flux.empty();
            });
    }
    
    /**
     * Find products by price range.
     */
    @GetMapping("/by-price-range")
    public Flux<Product> getProductsByPriceRange(
            @RequestParam BigDecimal minPrice,
            @RequestParam BigDecimal maxPrice) {
        log.debug("REST: Getting products by price range: {} - {}", minPrice, maxPrice);
        
        return productService.findByPriceRange(minPrice, maxPrice)
            .onErrorResume(error -> {
                log.error("Error getting products by price range", error);
                return Flux.empty();
            });
    }
}

