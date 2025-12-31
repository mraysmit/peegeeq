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

package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for WebSocketHandler close() method.
 * Verifies that the close() method properly cleans up resources.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-31
 * @version 1.0
 */
class WebSocketHandlerCloseTest {

    @Test
    @DisplayName("WebSocketHandler should have close() method and be callable")
    void testCloseMethodExists() {
        // Arrange
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketHandler handler = new WebSocketHandler(setupService, objectMapper);

        // Act & Assert - verify close() method exists and can be called
        assertDoesNotThrow(() -> handler.close(), 
            "close() method should be callable without throwing exceptions");
        
        // Verify initial state - no active connections
        assertEquals(0, handler.getActiveConnectionCount(), 
            "After close() with no connections, count should be 0");
    }

    @Test
    @DisplayName("WebSocketHandler should initialize with zero active connections")
    void testInitialState() {
        // Arrange
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Act
        WebSocketHandler handler = new WebSocketHandler(setupService, objectMapper);
        
        // Assert
        assertEquals(0, handler.getActiveConnectionCount(), 
            "New handler should have 0 active connections");
        assertNotNull(handler.getConnectionInfo(), 
            "Connection info should not be null");
    }

    @Test
    @DisplayName("WebSocketHandler close() should be idempotent")
    void testCloseIsIdempotent() {
        // Arrange
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketHandler handler = new WebSocketHandler(setupService, objectMapper);

        // Act - call close() multiple times
        assertDoesNotThrow(() -> {
            handler.close();
            handler.close();
            handler.close();
        }, "close() should be idempotent and safe to call multiple times");
        
        // Assert - state should remain consistent
        assertEquals(0, handler.getActiveConnectionCount(), 
            "After multiple close() calls, count should still be 0");
    }
}
