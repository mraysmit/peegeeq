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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Utility class for creating message filters based on headers and other message properties.
 * Provides common filtering patterns for consumer groups and message routing.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public final class MessageFilter {
    private static final Logger logger = LoggerFactory.getLogger(MessageFilter.class);

    private MessageFilter() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Creates a filter that matches messages with a specific header value.
     * 
     * @param headerKey The header key to check
     * @param expectedValue The expected header value
     * @param <T> The message payload type
     * @return A predicate that filters messages by header value
     */
    public static <T> Predicate<Message<T>> byHeader(String headerKey, String expectedValue) {
        logger.debug("API-DEBUG: Creating header filter for key: {}, expectedValue: {}", headerKey, expectedValue);
        return message -> {
            Map<String, String> headers = message.getHeaders();
            boolean matches = headers != null && headerKey != null && Objects.equals(expectedValue, headers.get(headerKey));
            logger.debug("API-DEBUG: Header filter test - key: {}, expected: {}, actual: {}, matches: {}",
                        headerKey, expectedValue, headers != null ? headers.get(headerKey) : null, matches);
            return matches;
        };
    }
    
    /**
     * Creates a filter that matches messages with header values in a set of allowed values.
     * 
     * @param headerKey The header key to check
     * @param allowedValues The set of allowed header values
     * @param <T> The message payload type
     * @return A predicate that filters messages by header value set
     */
    public static <T> Predicate<Message<T>> byHeaderIn(String headerKey, Set<String> allowedValues) {
        logger.debug("API-DEBUG: Creating header-in filter for key: {}, allowedValues: {}", headerKey, allowedValues);
        return message -> {
            Map<String, String> headers = message.getHeaders();
            if (headers == null || headerKey == null || allowedValues == null) {
                logger.debug("API-DEBUG: Header-in filter failed null check - headers: {}, key: {}, values: {}",
                           headers != null, headerKey != null, allowedValues != null);
                return false;
            }
            String headerValue = headers.get(headerKey);
            boolean matches = headerValue != null && allowedValues.contains(headerValue);
            logger.debug("API-DEBUG: Header-in filter test - key: {}, value: {}, allowedValues: {}, matches: {}",
                        headerKey, headerValue, allowedValues, matches);
            return matches;
        };
    }
    
    /**
     * Creates a filter that matches messages with a header value matching a regex pattern.
     * 
     * @param headerKey The header key to check
     * @param pattern The regex pattern to match
     * @param <T> The message payload type
     * @return A predicate that filters messages by header pattern
     */
    public static <T> Predicate<Message<T>> byHeaderPattern(String headerKey, Pattern pattern) {
        return message -> {
            Map<String, String> headers = message.getHeaders();
            if (headers == null || headerKey == null || pattern == null) return false;
            String value = headers.get(headerKey);
            return value != null && pattern.matcher(value).matches();
        };
    }
    
    /**
     * Creates a filter that matches messages with multiple required header values (AND logic).
     * 
     * @param requiredHeaders Map of header keys to required values
     * @param <T> The message payload type
     * @return A predicate that filters messages by multiple headers
     */
    public static <T> Predicate<Message<T>> byHeaders(Map<String, String> requiredHeaders) {
        return message -> {
            Map<String, String> headers = message.getHeaders();
            if (headers == null || requiredHeaders == null) return false;

            return requiredHeaders.entrySet().stream()
                .allMatch(entry -> Objects.equals(entry.getValue(), headers.get(entry.getKey())));
        };
    }
    
    /**
     * Creates a filter that matches messages with any of the specified header values (OR logic).
     * 
     * @param headerOptions Map of header keys to sets of allowed values
     * @param <T> The message payload type
     * @return A predicate that filters messages by any matching header
     */
    public static <T> Predicate<Message<T>> byAnyHeader(Map<String, Set<String>> headerOptions) {
        return message -> {
            Map<String, String> headers = message.getHeaders();
            if (headers == null || headerOptions == null) return false;

            return headerOptions.entrySet().stream()
                .anyMatch(entry -> {
                    String value = headers.get(entry.getKey());
                    return value != null && entry.getValue() != null && entry.getValue().contains(value);
                });
        };
    }
    
    /**
     * Creates a priority-based filter that accepts messages with priority at or above the minimum.
     * Priority values: HIGH > NORMAL > LOW
     * 
     * @param minPriority The minimum priority level ("HIGH", "NORMAL", "LOW")
     * @param <T> The message payload type
     * @return A predicate that filters messages by priority
     */
    public static <T> Predicate<Message<T>> byPriority(String minPriority) {
        return message -> {
            Map<String, String> headers = message.getHeaders();
            if (headers == null || minPriority == null) return false;

            String priority = headers.get("priority");
            if (priority == null) return "LOW".equals(minPriority);

            return switch (minPriority.toUpperCase()) {
                case "HIGH" -> "HIGH".equals(priority);
                case "NORMAL" -> "HIGH".equals(priority) || "NORMAL".equals(priority);
                case "LOW" -> true; // Accept all priorities
                default -> false;
            };
        };
    }
    
    /**
     * Creates a filter that matches messages from specific regions.
     * 
     * @param allowedRegions Set of allowed region values
     * @param <T> The message payload type
     * @return A predicate that filters messages by region
     */
    public static <T> Predicate<Message<T>> byRegion(Set<String> allowedRegions) {
        return byHeaderIn("region", allowedRegions);
    }
    
    /**
     * Creates a filter that matches messages of specific types.
     * 
     * @param allowedTypes Set of allowed message types
     * @param <T> The message payload type
     * @return A predicate that filters messages by type
     */
    public static <T> Predicate<Message<T>> byType(Set<String> allowedTypes) {
        return byHeaderIn("type", allowedTypes);
    }
    
    /**
     * Creates a filter that matches messages from specific sources.
     * 
     * @param allowedSources Set of allowed source values
     * @param <T> The message payload type
     * @return A predicate that filters messages by source
     */
    public static <T> Predicate<Message<T>> bySource(Set<String> allowedSources) {
        return byHeaderIn("source", allowedSources);
    }
    
    /**
     * Creates a filter that accepts all messages (no filtering).
     * 
     * @param <T> The message payload type
     * @return A predicate that accepts all messages
     */
    public static <T> Predicate<Message<T>> acceptAll() {
        return message -> true;
    }
    
    /**
     * Creates a filter that rejects all messages.
     * 
     * @param <T> The message payload type
     * @return A predicate that rejects all messages
     */
    public static <T> Predicate<Message<T>> rejectAll() {
        return message -> false;
    }
    
    /**
     * Combines multiple filters with AND logic.
     * 
     * @param filters The filters to combine
     * @param <T> The message payload type
     * @return A predicate that requires all filters to pass
     */
    @SafeVarargs
    public static <T> Predicate<Message<T>> and(Predicate<Message<T>>... filters) {
        logger.debug("API-DEBUG: Creating AND filter with {} predicates", filters.length);
        return message -> {
            logger.debug("API-DEBUG: Testing AND filter with {} predicates", filters.length);
            for (int i = 0; i < filters.length; i++) {
                boolean result = filters[i].test(message);
                logger.debug("API-DEBUG: AND filter predicate {} result: {}", i, result);
                if (!result) {
                    logger.debug("API-DEBUG: AND filter failed at predicate {}", i);
                    return false;
                }
            }
            logger.debug("API-DEBUG: AND filter passed all {} predicates", filters.length);
            return true;
        };
    }
    
    /**
     * Combines multiple filters with OR logic.
     * 
     * @param filters The filters to combine
     * @param <T> The message payload type
     * @return A predicate that requires at least one filter to pass
     */
    @SafeVarargs
    public static <T> Predicate<Message<T>> or(Predicate<Message<T>>... filters) {
        return message -> {
            for (Predicate<Message<T>> filter : filters) {
                if (filter.test(message)) {
                    return true;
                }
            }
            return false;
        };
    }
    
    /**
     * Negates a filter.
     * 
     * @param filter The filter to negate
     * @param <T> The message payload type
     * @return A predicate that returns the opposite of the input filter
     */
    public static <T> Predicate<Message<T>> not(Predicate<Message<T>> filter) {
        return filter.negate();
    }
}
