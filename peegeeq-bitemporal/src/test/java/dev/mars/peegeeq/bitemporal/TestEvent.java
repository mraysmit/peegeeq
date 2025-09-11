package dev.mars.peegeeq.bitemporal;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Shared test event class for bi-temporal performance tests.
 * 
 * This class consolidates the different TestEvent implementations found across
 * the bitemporal test suite into a single, consistent model.
 * 
 * Features:
 * - Jackson JSON serialization support
 * - Proper equals/hashCode implementation
 * - Immutable design for thread safety
 * - Compatible with existing test patterns
 * 
 * Following PGQ coding principles:
 * - Follow established conventions: Uses Jackson annotations like other event classes
 * - Fix root causes: Provides single source of truth for test events
 * - Document honestly: Clear purpose and usage
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
public class TestEvent {
    private final String id;
    private final String data;
    private final int value;

    /**
     * Default constructor for Jackson deserialization.
     */
    public TestEvent() {
        this("", "", 0);
    }

    /**
     * Constructor for creating test events.
     * 
     * @param id unique identifier for the event
     * @param data descriptive data for the event
     * @param value numeric value for testing
     */
    @JsonCreator
    public TestEvent(@JsonProperty("id") String id,
                    @JsonProperty("data") String data,
                    @JsonProperty("value") int value) {
        this.id = id;
        this.data = data;
        this.value = value;
    }

    /**
     * Get the event ID.
     * @return event ID
     */
    public String getId() {
        return id;
    }

    /**
     * Get the event data.
     * @return event data
     */
    public String getData() {
        return data;
    }

    /**
     * Get the event value.
     * @return event value
     */
    public int getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestEvent testEvent = (TestEvent) o;
        return value == testEvent.value &&
               Objects.equals(id, testEvent.id) &&
               Objects.equals(data, testEvent.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, data, value);
    }

    @Override
    public String toString() {
        return "TestEvent{id='" + id + "', data='" + data + "', value=" + value + "}";
    }
}
