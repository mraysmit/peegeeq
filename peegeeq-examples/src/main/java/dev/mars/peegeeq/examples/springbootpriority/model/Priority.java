package dev.mars.peegeeq.examples.springbootpriority.model;

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

/**
 * Priority levels for trade settlement processing.
 * 
 * Demonstrates priority-based message processing for middle and back office
 * trade settlement workflows.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public enum Priority {
    
    /**
     * CRITICAL priority (10) - Settlement fails, breaks, urgent corrections.
     * Requires immediate attention from operations team.
     * Examples: Settlement failures, reconciliation breaks, urgent trade corrections
     */
    CRITICAL("CRITICAL", 10, "Settlement fails, breaks, urgent corrections"),
    
    /**
     * HIGH priority (7) - Trade amendments, cancellations, time-sensitive updates.
     * Must be processed quickly to meet settlement deadlines.
     * Examples: Trade amendments, cancellations, same-day settlement updates
     */
    HIGH("HIGH", 7, "Trade amendments, cancellations, time-sensitive updates"),
    
    /**
     * NORMAL priority (5) - New trade confirmations, standard processing.
     * Regular business operations with standard SLAs.
     * Examples: New trade confirmations, routine settlement instructions
     */
    NORMAL("NORMAL", 5, "New trade confirmations, standard processing");
    
    private final String level;
    private final int value;
    private final String description;
    
    Priority(String level, int value, String description) {
        this.level = level;
        this.value = value;
        this.description = description;
    }
    
    public String getLevel() {
        return level;
    }
    
    public int getValue() {
        return value;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Parse priority from string value.
     * 
     * @param level Priority level string (CRITICAL, HIGH, NORMAL)
     * @return Priority enum value
     * @throws IllegalArgumentException if level is invalid
     */
    public static Priority fromString(String level) {
        if (level == null) {
            return NORMAL; // Default to NORMAL if not specified
        }
        
        for (Priority priority : Priority.values()) {
            if (priority.level.equalsIgnoreCase(level)) {
                return priority;
            }
        }
        
        throw new IllegalArgumentException("Invalid priority level: " + level);
    }
    
    @Override
    public String toString() {
        return level;
    }
}

