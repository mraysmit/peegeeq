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

package dev.mars.peegeeq.examples.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ExampleResourceLoader utility.
 */
class ExampleResourceLoaderTest {

    @Test
    void testLoadMessageExample() {
        String orderJson = ExampleResourceLoader.loadMessageExample("order-message-request.json");
        
        assertNotNull(orderJson);
        assertFalse(orderJson.trim().isEmpty());
        assertTrue(orderJson.contains("orderId"));
        assertTrue(orderJson.contains("customerId"));
    }

    @Test
    void testLoadConfigExample() {
        String demoConfig = ExampleResourceLoader.loadConfigExample("demo-setup.json");
        
        assertNotNull(demoConfig);
        assertFalse(demoConfig.trim().isEmpty());
        assertTrue(demoConfig.contains("queues") || demoConfig.contains("configuration"));
    }

    @Test
    void testLoadExample() {
        String orderJson = ExampleResourceLoader.loadExample("messages/order-message-request.json");
        
        assertNotNull(orderJson);
        assertFalse(orderJson.trim().isEmpty());
        assertTrue(orderJson.contains("orderId"));
    }

    @Test
    void testGetMessageExampleStream() {
        try (InputStream stream = ExampleResourceLoader.getMessageExampleStream("order-message-request.json")) {
            assertNotNull(stream);
            assertTrue(stream.available() > 0);
        } catch (Exception e) {
            fail("Should be able to read stream: " + e.getMessage());
        }
    }

    @Test
    void testGetConfigExampleStream() {
        try (InputStream stream = ExampleResourceLoader.getConfigExampleStream("demo-setup.json")) {
            assertNotNull(stream);
            assertTrue(stream.available() > 0);
        } catch (Exception e) {
            fail("Should be able to read stream: " + e.getMessage());
        }
    }

    @Test
    void testResourceNotFound() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ExampleResourceLoader.loadMessageExample("non-existent-file.json");
        });
        
        assertTrue(exception.getMessage().contains("Resource not found"));
    }
}
