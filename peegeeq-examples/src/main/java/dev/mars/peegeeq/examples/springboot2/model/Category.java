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

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a product category.
 * 
 * This is a plain POJO used for demonstrating JOIN operations
 * between products and categories.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
public class Category {
    
    private String id;
    private String name;
    private String description;
    private Instant createdAt;
    
    /**
     * Default constructor.
     */
    public Category() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }
    
    /**
     * Constructor with required fields.
     */
    public Category(String name) {
        this();
        this.name = name;
    }
    
    /**
     * Full constructor for database loading.
     */
    public Category(String id, String name, String description, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    @Override
    public String toString() {
        return String.format("Category{id='%s', name='%s'}", id, name);
    }
}

