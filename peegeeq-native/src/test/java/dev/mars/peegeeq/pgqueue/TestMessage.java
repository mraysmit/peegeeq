package dev.mars.peegeeq.pgqueue;

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


import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/**
 * A simple message class for testing purposes.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * A simple message class for testing purposes.
 */
public class TestMessage {
    private String text;
    private int index;

    // Default constructor for Jackson
    public TestMessage() {
    }

    /**
     * Creates a new TestMessage with the given text and index.
     *
     * @param text The message text
     * @param index The message index
     */
    public TestMessage(String text, int index) {
        this.text = text;
        this.index = index;
    }

    /**
     * Creates a new TestMessage with the given text and index 0.
     *
     * @param text The message text
     */
    public TestMessage(String text) {
        this(text, 0);
    }

    /**
     * Gets the message text.
     *
     * @return The message text
     */
    public String getText() {
        return text;
    }

    /**
     * Sets the message text.
     *
     * @param text The message text
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * Gets the message index.
     *
     * @return The message index
     */
    public int getIndex() {
        return index;
    }

    /**
     * Sets the message index.
     *
     * @param index The message index
     */
    public void setIndex(int index) {
        this.index = index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestMessage that = (TestMessage) o;
        return index == that.index && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, index);
    }

    @Override
    public String toString() {
        try {
            // Use Jackson to serialize the object to a JSON string
            return new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            // Fallback to a simple JSON-like string if serialization fails
            return "{\"text\":\"" + text.replace("\"", "\\\"") + "\",\"index\":" + index + "}";
        }
    }
}
