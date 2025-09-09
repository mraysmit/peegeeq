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
package dev.mars.peegeeq.pgqueue;

import org.junit.jupiter.api.Test;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ConsumerConfig and ConsumerMode classes.
 * Validates the configuration builder pattern and default values.
 */
class ConsumerConfigTest {

    @Test
    void testDefaultConfig() {
        ConsumerConfig config = ConsumerConfig.defaultConfig();
        
        // Verify backward compatibility defaults
        assertEquals(ConsumerMode.HYBRID, config.getMode());
        assertEquals(Duration.ofSeconds(1), config.getPollingInterval());
        assertTrue(config.isNotificationsEnabled());
        assertEquals(10, config.getBatchSize());
        assertEquals(1, config.getConsumerThreads());
    }

    @Test
    void testBuilderPattern() {
        ConsumerConfig config = ConsumerConfig.builder()
            .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ofMinutes(5))
            .enableNotifications(false)
            .batchSize(50)
            .consumerThreads(4)
            .build();
        
        assertEquals(ConsumerMode.LISTEN_NOTIFY_ONLY, config.getMode());
        assertEquals(Duration.ofMinutes(5), config.getPollingInterval());
        assertFalse(config.isNotificationsEnabled());
        assertEquals(50, config.getBatchSize());
        assertEquals(4, config.getConsumerThreads());
    }

    @Test
    void testPollingOnlyModeValidation() {
        // Should throw exception for zero polling interval with POLLING_ONLY mode
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ZERO)
                .build();
        });
    }

    @Test
    void testConsumerModeValues() {
        // Verify all enum values exist
        assertEquals(3, ConsumerMode.values().length);
        assertNotNull(ConsumerMode.LISTEN_NOTIFY_ONLY);
        assertNotNull(ConsumerMode.POLLING_ONLY);
        assertNotNull(ConsumerMode.HYBRID);
    }

    @Test
    void testToString() {
        ConsumerConfig config = ConsumerConfig.builder()
            .mode(ConsumerMode.HYBRID)
            .build();
        
        String toString = config.toString();
        assertTrue(toString.contains("HYBRID"));
        assertTrue(toString.contains("pollingInterval"));
        assertTrue(toString.contains("batchSize"));
    }
}
