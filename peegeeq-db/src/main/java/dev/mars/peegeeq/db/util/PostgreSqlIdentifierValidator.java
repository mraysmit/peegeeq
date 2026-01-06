package dev.mars.peegeeq.db.util;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for validating and sanitizing PostgreSQL identifiers.
 * 
 * <p>PostgreSQL has specific rules for identifiers (table names, column names, schema names, etc.):
 * <ul>
 *   <li>Maximum length: 63 characters</li>
 *   <li>Must start with a letter (a-z) or underscore (_)</li>
 *   <li>Can contain letters, digits (0-9), and underscores</li>
 *   <li>Cannot be reserved keywords (pg_*, information_schema)</li>
 * </ul>
 * 
 * <p>This class provides methods to:
 * <ul>
 *   <li>Validate identifier format and length</li>
 *   <li>Truncate long identifiers using MD5 hashing for uniqueness</li>
 *   <li>Sanitize identifiers to ensure PostgreSQL compliance</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-06
 * @version 1.0
 */
public final class PostgreSqlIdentifierValidator {
    private static final Logger logger = LoggerFactory.getLogger(PostgreSqlIdentifierValidator.class);
    
    /**
     * PostgreSQL maximum identifier length.
     * @see <a href="https://www.postgresql.org/docs/current/sql-syntax-lexical.html#SQL-SYNTAX-IDENTIFIERS">PostgreSQL Documentation</a>
     */
    public static final int MAX_IDENTIFIER_LENGTH = 63;
    
    /**
     * Pattern for valid PostgreSQL identifiers.
     * Must start with letter or underscore, followed by alphanumeric or underscore.
     */
    private static final String IDENTIFIER_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*$";
    
    /**
     * Length of MD5 hash suffix used for truncation (8 hex chars + 1 underscore).
     */
    private static final int HASH_SUFFIX_LENGTH = 9; // "_" + 8 hex chars
    
    // Private constructor to prevent instantiation
    private PostgreSqlIdentifierValidator() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Validates that an identifier meets PostgreSQL requirements.
     * 
     * @param identifier The identifier to validate
     * @param identifierType Type of identifier for error messages (e.g., "schema", "table", "queue")
     * @throws IllegalArgumentException if validation fails
     */
    public static void validate(String identifier, String identifierType) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException(identifierType + " name cannot be null or empty");
        }
        
        String trimmed = identifier.trim();
        
        // Check length
        if (trimmed.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                String.format("%s name '%s' exceeds PostgreSQL maximum length of %d characters (length: %d). " +
                    "Consider using a shorter name or let the system truncate it automatically.",
                    identifierType, trimmed, MAX_IDENTIFIER_LENGTH, trimmed.length()));
        }
        
        // Check format
        if (!trimmed.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(
                String.format("Invalid %s name: '%s'. Must start with letter or underscore, " +
                    "followed by alphanumeric characters or underscores only.",
                    identifierType, trimmed));
        }
        
        // Check for reserved prefixes/names
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("pg_") || lower.equals("information_schema")) {
            throw new IllegalArgumentException(
                String.format("Reserved %s name: '%s'. Cannot use PostgreSQL system schemas " +
                    "(pg_*, information_schema).",
                    identifierType, trimmed));
        }
    }
    
    /**
     * Sanitizes an identifier to ensure PostgreSQL compliance.
     * 
     * <p>This method:
     * <ul>
     *   <li>Removes leading/trailing whitespace</li>
     *   <li>Replaces hyphens and spaces with underscores</li>
     *   <li>Removes invalid characters</li>
     *   <li>Ensures it starts with a letter or underscore</li>
     * </ul>
     * 
     * @param identifier The identifier to sanitize
     * @return Sanitized identifier
     */
    public static String sanitize(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return identifier;
        }
        
        String sanitized = identifier.trim();
        
        // Replace hyphens and spaces with underscores
        sanitized = sanitized.replace('-', '_');
        sanitized = sanitized.replace(' ', '_');
        
        // Remove invalid characters (keep only alphanumeric and underscore)
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_]", "");
        
        // Ensure it starts with letter or underscore
        if (!sanitized.isEmpty() && !Character.isLetter(sanitized.charAt(0)) && sanitized.charAt(0) != '_') {
            sanitized = "_" + sanitized;
        }
        
        return sanitized;
    }
    
    /**
     * Truncates a long identifier using MD5 hashing for uniqueness.
     * 
     * <p>Strategy:
     * <ul>
     *   <li>If identifier is within limit, return as-is</li>
     *   <li>Otherwise, create MD5 hash of full identifier</li>
     *   <li>Truncate to (63 - 9) = 54 characters and append "_" + 8-char hash</li>
     *   <li>This ensures uniqueness and deterministic naming</li>
     * </ul>
     * 
     * <p>Example:
     * <pre>
     * Input:  "tenant_acme_corporation_very_long_company_name_orders_queue_v2"
     * Output: "tenant_acme_corporation_very_long_company_name_ord_a1b2c3d4"
     * </pre>
     * 
     * @param identifier The identifier to truncate
     * @return Truncated identifier (max 63 chars)
     */
    public static String truncateWithHash(String identifier) {
        if (identifier == null || identifier.length() <= MAX_IDENTIFIER_LENGTH) {
            return identifier;
        }
        
        // Generate MD5 hash of full identifier
        String md5Hash;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(identifier.getBytes(StandardCharsets.UTF_8));
            
            // Convert to hex and take first 8 characters
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            md5Hash = hexString.toString().substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to hashCode if MD5 not available (shouldn't happen)
            md5Hash = Integer.toHexString(Math.abs(identifier.hashCode())).substring(0, 8);
            logger.warn("MD5 algorithm not available, using hashCode fallback for identifier: {}", identifier);
        }
        
        String hashSuffix = "_" + md5Hash;
        
        // Truncate to fit: 63 - 9 = 54 characters + hash suffix
        int maxPrefixLength = MAX_IDENTIFIER_LENGTH - HASH_SUFFIX_LENGTH;
        String truncatedBase = identifier.substring(0, Math.min(identifier.length(), maxPrefixLength));
        String result = truncatedBase + hashSuffix;
        
        logger.debug("Truncated identifier from '{}' (length: {}) to '{}' (length: {}, hash: {})",
            identifier.substring(0, Math.min(identifier.length(), 80)), 
            identifier.length(),
            result, 
            result.length(),
            md5Hash);
        
        return result;
    }
    
    /**
     * Creates a safe PostgreSQL identifier by sanitizing and truncating if needed.
     * 
     * <p>This is a convenience method that combines sanitize() and truncateWithHash().
     * 
     * @param identifier The identifier to make safe
     * @return Safe identifier that meets PostgreSQL requirements
     */
    public static String makeSafe(String identifier) {
        String sanitized = sanitize(identifier);
        return truncateWithHash(sanitized);
    }
    
    /**
     * Checks if an identifier is valid without throwing an exception.
     * 
     * @param identifier The identifier to check
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = identifier.trim();
        
        if (trimmed.length() > MAX_IDENTIFIER_LENGTH) {
            return false;
        }
        
        if (!trimmed.matches(IDENTIFIER_PATTERN)) {
            return false;
        }
        
        String lower = trimmed.toLowerCase();
        return !lower.startsWith("pg_") && !lower.equals("information_schema");
    }
}
