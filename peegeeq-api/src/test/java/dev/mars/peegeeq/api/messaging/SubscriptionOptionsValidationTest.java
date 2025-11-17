package dev.mars.peegeeq.api.messaging;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubscriptionOptions} validation and builder pattern.
 * 
 * <p>Tests configuration edge cases, validation rules, and ensures proper
 * builder pattern behavior for subscription configuration DTOs.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-17
 * @version 1.1.0
 */
@Tag("core")
@DisplayName("SubscriptionOptions Validation Tests")
class SubscriptionOptionsValidationTest {

    @Nested
    @DisplayName("Builder Pattern Tests")
    class BuilderTests {

        @Test
        @DisplayName("should build valid configuration with all parameters")
        void testBuilder_ValidConfiguration() {
            // Arrange & Act
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(30)
                .heartbeatTimeoutSeconds(120)
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(StartPosition.FROM_BEGINNING, options.getStartPosition());
            assertEquals(30, options.getHeartbeatIntervalSeconds());
            assertEquals(120, options.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should use correct default values")
        void testBuilder_DefaultValues() {
            // Arrange & Act
            SubscriptionOptions defaults = SubscriptionOptions.defaults();

            // Assert
            assertNotNull(defaults);
            assertEquals(StartPosition.FROM_NOW, defaults.getStartPosition());
            assertEquals(60, defaults.getHeartbeatIntervalSeconds());
            assertEquals(300, defaults.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should throw exception for negative heartbeat interval")
        void testBuilder_NegativeHeartbeatInterval() {
            // Arrange & Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(-1)
                    .build()
            );

            assertTrue(exception.getMessage().contains("positive"),
                "Exception message should mention 'positive': " + exception.getMessage());
        }

        @Test
        @DisplayName("should throw exception for zero heartbeat interval")
        void testBuilder_ZeroHeartbeatInterval() {
            // Arrange & Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(0)
                    .build()
            );

            assertTrue(exception.getMessage().contains("heartbeat interval") ||
                       exception.getMessage().contains("must be positive"),
                "Exception message should indicate invalid heartbeat interval");
        }

        @Test
        @DisplayName("should throw exception for negative heartbeat timeout")
        void testBuilder_NegativeHeartbeatTimeout() {
            // Arrange & Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .heartbeatTimeoutSeconds(-1)
                    .build()
            );

            assertTrue(exception.getMessage().contains("positive"),
                "Exception message should mention 'positive': " + exception.getMessage());
        }

        @Test
        @DisplayName("should throw exception when timeout less than interval")
        void testBuilder_TimeoutLessThanInterval() {
            // Arrange & Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(60)
                    .heartbeatTimeoutSeconds(30)  // Less than interval
                    .build()
            );

            assertTrue(exception.getMessage().contains("timeout") ||
                       exception.getMessage().contains("greater than") ||
                       exception.getMessage().contains("interval"),
                "Exception message should indicate timeout must be greater than interval");
        }

        @Test
        @DisplayName("should require timestamp when using FROM_TIMESTAMP")
        void testBuilder_FromTimestampWithoutTimestamp() {
            // Arrange & Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_TIMESTAMP)
                    // Missing startFromTimestamp()
                    .build()
            );

            assertTrue(exception.getMessage().contains("timestamp") ||
                       exception.getMessage().contains("FROM_TIMESTAMP"),
                "Exception message should indicate timestamp is required");
        }

        @Test
        @DisplayName("should require message ID when using FROM_MESSAGE_ID")
        void testBuilder_FromMessageIdWithoutMessageId() {
            // Arrange & Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_MESSAGE_ID)
                    // Missing startFromMessageId()
                    .build()
            );

            assertTrue(exception.getMessage().contains("message") ||
                       exception.getMessage().contains("FROM_MESSAGE_ID") ||
                       exception.getMessage().contains("ID"),
                "Exception message should indicate message ID is required");
        }

        @Test
        @DisplayName("should throw exception for null startPosition")
        void testBuilder_NullStartPosition() {
            // Arrange & Act & Assert
            // API uses Objects.requireNonNull which throws NullPointerException
            assertThrows(
                NullPointerException.class,
                () -> SubscriptionOptions.builder()
                    .startPosition(null)
                    .build(),
                "Null startPosition should throw NullPointerException"
            );
        }

        @Test
        @DisplayName("should support all StartPosition values")
        void testBuilder_AllStartPositions() {
            // Test FROM_NOW
            SubscriptionOptions fromNow = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();
            assertEquals(StartPosition.FROM_NOW, fromNow.getStartPosition());

            // Test FROM_BEGINNING
            SubscriptionOptions fromBeginning = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();
            assertEquals(StartPosition.FROM_BEGINNING, fromBeginning.getStartPosition());

            // Test FROM_TIMESTAMP
            Instant now = Instant.now();
            SubscriptionOptions fromTimestamp = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_TIMESTAMP)
                .startFromTimestamp(now)
                .build();
            assertEquals(StartPosition.FROM_TIMESTAMP, fromTimestamp.getStartPosition());
            assertEquals(now, fromTimestamp.getStartFromTimestamp());

            // Test FROM_MESSAGE_ID
            SubscriptionOptions fromMessageId = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_MESSAGE_ID)
                .startFromMessageId(123L)
                .build();
            assertEquals(StartPosition.FROM_MESSAGE_ID, fromMessageId.getStartPosition());
            assertEquals(123L, fromMessageId.getStartFromMessageId());
        }

        @Test
        @DisplayName("should be immutable after build")
        void testBuilder_Immutability() {
            // Arrange
            SubscriptionOptions.Builder builder = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(30);

            SubscriptionOptions options1 = builder.build();

            // Modify builder after first build
            builder.heartbeatIntervalSeconds(60);
            SubscriptionOptions options2 = builder.build();

            // Assert - original should be unchanged
            assertEquals(30, options1.getHeartbeatIntervalSeconds(),
                "Original options should be immutable");
            assertEquals(60, options2.getHeartbeatIntervalSeconds(),
                "New options should reflect changes");
        }
    }

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsAndHashCodeTests {

        @Test
        @DisplayName("should implement equals correctly")
        void testEquals() {
            // Arrange
            SubscriptionOptions options1 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(30)
                .build();

            SubscriptionOptions options2 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(30)
                .build();

            SubscriptionOptions options3 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(30)
                .build();

            // Assert
            assertEquals(options1, options2, "Equal options should be equal");
            assertNotEquals(options1, options3, "Different options should not be equal");
            assertNotEquals(options1, null, "Options should not equal null");
            assertNotEquals(options1, "string", "Options should not equal different type");
        }

        @Test
        @DisplayName("should implement hashCode consistently")
        void testHashCode() {
            // Arrange
            SubscriptionOptions options1 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(30)
                .build();

            SubscriptionOptions options2 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(30)
                .build();

            // Assert
            assertEquals(options1.hashCode(), options2.hashCode(),
                "Equal objects should have equal hash codes");

            // Verify consistency
            int hash1 = options1.hashCode();
            int hash2 = options1.hashCode();
            assertEquals(hash1, hash2, "Hash code should be consistent");
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle minimum valid heartbeat interval")
        void testMinimumHeartbeatInterval() {
            // Arrange & Act
            SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)  // Minimum valid value
                .heartbeatTimeoutSeconds(5)
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(1, options.getHeartbeatIntervalSeconds());
        }

        @Test
        @DisplayName("should handle very large heartbeat values")
        void testLargeHeartbeatValues() {
            // Arrange & Act
            SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(3600)      // 1 hour
                .heartbeatTimeoutSeconds(7200)       // 2 hours
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(3600, options.getHeartbeatIntervalSeconds());
            assertEquals(7200, options.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should handle timestamp in the past")
        void testTimestampInPast() {
            // Arrange
            Instant pastTime = Instant.now().minusSeconds(86400); // 24 hours ago

            // Act
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_TIMESTAMP)
                .startFromTimestamp(pastTime)
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(pastTime, options.getStartFromTimestamp());
        }

        @Test
        @DisplayName("should handle timestamp in the future")
        void testTimestampInFuture() {
            // Arrange
            Instant futureTime = Instant.now().plusSeconds(3600); // 1 hour ahead

            // Act
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_TIMESTAMP)
                .startFromTimestamp(futureTime)
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(futureTime, options.getStartFromTimestamp());
        }

        @Test
        @DisplayName("should handle negative message ID")
        void testNegativeMessageId() {
            // Arrange & Act
            // Note: negative long values are technically valid for message IDs
            // This test verifies the API accepts them (database may use negative IDs)
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_MESSAGE_ID)
                .startFromMessageId(-1L)
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(-1L, options.getStartFromMessageId());
        }

        @Test
        @DisplayName("should handle null timestamp with FROM_TIMESTAMP")
        void testNullTimestampWithFromTimestamp() {
            // Arrange & Act & Assert
            // API uses Objects.requireNonNull which throws NullPointerException
            assertThrows(
                NullPointerException.class,
                () -> SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_TIMESTAMP)
                    .startFromTimestamp(null)
                    .build(),
                "Null timestamp should throw NullPointerException"
            );
        }

        @Test
        @DisplayName("should require message ID when using FROM_MESSAGE_ID position")
        void testFromMessageIdRequiresValue() {
            // Arrange & Act & Assert
            // When using FROM_MESSAGE_ID, startFromMessageId() must be called
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_MESSAGE_ID)
                    // Missing .startFromMessageId() call
                    .build()
            );

            assertTrue(exception.getMessage().contains("message") ||
                       exception.getMessage().contains("FROM_MESSAGE_ID"),
                "Exception should indicate message ID is required");
        }
    }

    @Nested
    @DisplayName("Builder Fluent API Tests")
    class FluentAPITests {

        @Test
        @DisplayName("should support method chaining")
        void testMethodChaining() {
            // Arrange & Act
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(45)
                .heartbeatTimeoutSeconds(180)
                .build();

            // Assert
            assertNotNull(options);
            assertEquals(StartPosition.FROM_BEGINNING, options.getStartPosition());
            assertEquals(45, options.getHeartbeatIntervalSeconds());
            assertEquals(180, options.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should allow partial configuration with defaults")
        void testPartialConfiguration() {
            // Arrange & Act - only specify startPosition
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            // Assert - other fields should have defaults
            assertNotNull(options);
            assertEquals(StartPosition.FROM_BEGINNING, options.getStartPosition());
            assertTrue(options.getHeartbeatIntervalSeconds() > 0,
                "Should have default heartbeat interval");
            assertTrue(options.getHeartbeatTimeoutSeconds() > 0,
                "Should have default heartbeat timeout");
        }

        @Test
        @DisplayName("should allow overwriting builder values")
        void testOverwritingValues() {
            // Arrange
            SubscriptionOptions.Builder builder = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(30);

            // Act - overwrite
            builder.heartbeatIntervalSeconds(60);
            SubscriptionOptions options = builder.build();

            // Assert - should use last value
            assertEquals(60, options.getHeartbeatIntervalSeconds(),
                "Should use the last value set");
        }
    }
}
