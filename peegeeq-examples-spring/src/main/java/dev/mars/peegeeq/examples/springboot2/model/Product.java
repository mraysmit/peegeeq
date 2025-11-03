package dev.mars.peegeeq.examples.springboot2.model;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a product entity.
 * 
 * This is a plain POJO demonstrating reactive CRUD operations without messaging.
 * No R2DBC annotations needed - we use manual SQL mapping in the repository.
 * 
 * This example shows complex queries including JOINs, aggregations, and batch operations
 * using only PeeGeeQ's DatabaseService and Vert.x SQL Client.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
public class Product {
    
    private String id;
    private String name;
    private String description;
    private String categoryId;
    private BigDecimal price;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    
    /**
     * Default constructor.
     */
    public Product() {
        this.id = UUID.randomUUID().toString();
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    /**
     * Constructor with required fields.
     */
    public Product(String name, String categoryId, BigDecimal price) {
        this();
        this.name = name;
        this.categoryId = categoryId;
        this.price = price;
    }
    
    /**
     * Full constructor for database loading.
     */
    public Product(String id, String name, String description, String categoryId, 
                  BigDecimal price, boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.categoryId = categoryId;
        this.price = price;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    @Override
    public String toString() {
        return String.format("Product{id='%s', name='%s', categoryId='%s', price=%s, active=%s}", 
            id, name, categoryId, price, active);
    }
}

