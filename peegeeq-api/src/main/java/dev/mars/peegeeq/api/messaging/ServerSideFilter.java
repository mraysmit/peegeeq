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
package dev.mars.peegeeq.api.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a filter that can be translated to SQL WHERE clauses for server-side
 * (database-level) message filtering. This reduces network traffic and client CPU
 * usage by filtering messages at the PostgreSQL level before they are fetched.
 *
 * <p>Unlike {@link MessageFilter} which creates client-side predicates, ServerSideFilter
 * generates SQL conditions that are executed by PostgreSQL on the JSONB headers column.</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Simple header equality
 * ServerSideFilter filter = ServerSideFilter.headerEquals("type", "order-created");
 *
 * // Multiple values (IN clause)
 * ServerSideFilter filter = ServerSideFilter.headerIn("region", Set.of("US", "EU"));
 *
 * // Combined filters (AND)
 * ServerSideFilter filter = ServerSideFilter.and(
 *     ServerSideFilter.headerEquals("type", "order"),
 *     ServerSideFilter.headerEquals("priority", "HIGH")
 * );
 * }</pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-18
 * @version 1.0
 * @see MessageFilter
 */
public final class ServerSideFilter {

    // Pattern for validating header keys to prevent SQL injection
    private static final Pattern VALID_HEADER_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]*$");

    /**
     * Operators supported for server-side filtering.
     */
    public enum Operator {
        /** Exact equality: headers->>'key' = value */
        EQUALS,
        /** Set membership: headers->>'key' IN (values) */
        IN,
        /** Inequality: headers->>'key' != value */
        NOT_EQUALS,
        /** Pattern matching: headers->>'key' LIKE pattern */
        LIKE,
        /** Logical AND of multiple filters */
        AND,
        /** Logical OR of multiple filters */
        OR
    }

    private final Operator operator;
    private final String headerKey;
    private final Object value; // String, Set<String>, or List<ServerSideFilter>

    private ServerSideFilter(Operator operator, String headerKey, Object value) {
        this.operator = Objects.requireNonNull(operator, "Operator cannot be null");
        this.headerKey = headerKey;
        this.value = value;
    }

    // ========== Factory Methods ==========

    /**
     * Creates a filter that matches messages where the header equals the specified value.
     *
     * @param key   The header key to check
     * @param value The expected header value
     * @return A filter for header equality
     * @throws IllegalArgumentException if key is invalid
     */
    public static ServerSideFilter headerEquals(String key, String value) {
        validateHeaderKey(key);
        Objects.requireNonNull(value, "Value cannot be null");
        return new ServerSideFilter(Operator.EQUALS, key, value);
    }

    /**
     * Creates a filter that matches messages where the header is in the specified set.
     *
     * @param key    The header key to check
     * @param values The set of allowed header values
     * @return A filter for header set membership
     * @throws IllegalArgumentException if key is invalid or values is empty
     */
    public static ServerSideFilter headerIn(String key, Set<String> values) {
        validateHeaderKey(key);
        Objects.requireNonNull(values, "Values cannot be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Values set cannot be empty");
        }
        return new ServerSideFilter(Operator.IN, key, Set.copyOf(values));
    }

    /**
     * Creates a filter that matches messages where the header does not equal the value.
     *
     * @param key   The header key to check
     * @param value The value to exclude
     * @return A filter for header inequality
     * @throws IllegalArgumentException if key is invalid
     */
    public static ServerSideFilter headerNotEquals(String key, String value) {
        validateHeaderKey(key);
        Objects.requireNonNull(value, "Value cannot be null");
        return new ServerSideFilter(Operator.NOT_EQUALS, key, value);
    }

    /**
     * Creates a filter that matches messages where the header matches a LIKE pattern.
     * Use '%' for wildcard matching.
     *
     * @param key     The header key to check
     * @param pattern The LIKE pattern (e.g., "order-%")
     * @return A filter for header pattern matching
     * @throws IllegalArgumentException if key is invalid
     */
    public static ServerSideFilter headerLike(String key, String pattern) {
        validateHeaderKey(key);
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        return new ServerSideFilter(Operator.LIKE, key, pattern);
    }

    /**
     * Combines multiple filters with AND logic.
     *
     * @param filters The filters to combine
     * @return A filter that requires all sub-filters to match
     * @throws IllegalArgumentException if fewer than 2 filters provided
     */
    public static ServerSideFilter and(ServerSideFilter... filters) {
        if (filters == null || filters.length < 2) {
            throw new IllegalArgumentException("AND requires at least 2 filters");
        }
        return new ServerSideFilter(Operator.AND, null, List.of(filters));
    }

    /**
     * Combines multiple filters with OR logic.
     *
     * @param filters The filters to combine
     * @return A filter that requires at least one sub-filter to match
     * @throws IllegalArgumentException if fewer than 2 filters provided
     */
    public static ServerSideFilter or(ServerSideFilter... filters) {
        if (filters == null || filters.length < 2) {
            throw new IllegalArgumentException("OR requires at least 2 filters");
        }
        return new ServerSideFilter(Operator.OR, null, List.of(filters));
    }

    // ========== SQL Generation ==========

    /**
     * Generates the SQL condition fragment for this filter.
     * The generated SQL uses NULL-safe key existence checks.
     *
     * @param paramOffset Starting parameter index (e.g., 4 for $4)
     * @return SQL fragment like "(headers ? 'type' AND headers->>'type' = $4)"
     */
    public String toSqlCondition(int paramOffset) {
        return switch (operator) {
            case EQUALS -> String.format(
                "(headers ? '%s' AND headers->>'%s' = $%d)",
                headerKey, headerKey, paramOffset);
            case NOT_EQUALS -> String.format(
                "(NOT headers ? '%s' OR headers->>'%s' != $%d)",
                headerKey, headerKey, paramOffset);
            case LIKE -> String.format(
                "(headers ? '%s' AND headers->>'%s' LIKE $%d)",
                headerKey, headerKey, paramOffset);
            case IN -> buildInClause(paramOffset);
            case AND -> buildCompoundClause(" AND ", paramOffset);
            case OR -> buildCompoundClause(" OR ", paramOffset);
        };
    }

    @SuppressWarnings("unchecked")
    private String buildInClause(int paramOffset) {
        Set<String> values = (Set<String>) this.value;
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) params.append(", ");
            params.append("$").append(paramOffset + i);
        }
        return String.format(
            "(headers ? '%s' AND headers->>'%s' IN (%s))",
            headerKey, headerKey, params);
    }

    @SuppressWarnings("unchecked")
    private String buildCompoundClause(String joiner, int paramOffset) {
        List<ServerSideFilter> filters = (List<ServerSideFilter>) this.value;
        StringBuilder sb = new StringBuilder("(");
        int currentOffset = paramOffset;
        for (int i = 0; i < filters.size(); i++) {
            if (i > 0) sb.append(joiner);
            ServerSideFilter f = filters.get(i);
            sb.append(f.toSqlCondition(currentOffset));
            currentOffset += f.getParameterCount();
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Returns the parameters to bind to the SQL query.
     *
     * @return List of parameter values in order
     */
    @SuppressWarnings("unchecked")
    public List<Object> getParameters() {
        return switch (operator) {
            case EQUALS, NOT_EQUALS, LIKE -> Collections.singletonList(value);
            case IN -> new ArrayList<>((Set<String>) value);
            case AND, OR -> {
                List<Object> params = new ArrayList<>();
                for (ServerSideFilter f : (List<ServerSideFilter>) value) {
                    params.addAll(f.getParameters());
                }
                yield params;
            }
        };
    }

    /**
     * Returns the number of parameters this filter uses.
     *
     * @return Parameter count
     */
    @SuppressWarnings("unchecked")
    public int getParameterCount() {
        return switch (operator) {
            case EQUALS, NOT_EQUALS, LIKE -> 1;
            case IN -> ((Set<String>) value).size();
            case AND, OR -> {
                int count = 0;
                for (ServerSideFilter f : (List<ServerSideFilter>) value) {
                    count += f.getParameterCount();
                }
                yield count;
            }
        };
    }

    // ========== Getters ==========

    public Operator getOperator() {
        return operator;
    }

    public String getHeaderKey() {
        return headerKey;
    }

    public Object getValue() {
        return value;
    }

    // ========== Validation ==========

    private static void validateHeaderKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Header key cannot be null or empty");
        }
        if (!VALID_HEADER_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                "Invalid header key: '" + key + "'. Must match pattern: " + VALID_HEADER_KEY.pattern());
        }
    }

    @Override
    public String toString() {
        return switch (operator) {
            case EQUALS -> "headerEquals('" + headerKey + "', '" + value + "')";
            case NOT_EQUALS -> "headerNotEquals('" + headerKey + "', '" + value + "')";
            case LIKE -> "headerLike('" + headerKey + "', '" + value + "')";
            case IN -> "headerIn('" + headerKey + "', " + value + ")";
            case AND -> "and(" + value + ")";
            case OR -> "or(" + value + ")";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerSideFilter that = (ServerSideFilter) o;
        return operator == that.operator &&
               Objects.equals(headerKey, that.headerKey) &&
               Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, headerKey, value);
    }
}

