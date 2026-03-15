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

import java.util.regex.Pattern;

/**
 * Validates topic and queue names to prevent SQL injection via LISTEN/NOTIFY channels.
 *
 * <p>Topic names are interpolated into PostgreSQL LISTEN, UNLISTEN, and pg_notify()
 * statements that cannot use parameterized queries. This validator enforces a strict
 * allowlist to ensure only safe identifiers reach those code paths.</p>
 */
public final class TopicNameValidator {

    /**
     * Allows alphanumeric characters, hyphens, underscores, and dots.
     * Max length 128 to stay well within PostgreSQL's 63-byte identifier limit
     * after the schema prefix is added.
     */
    private static final Pattern VALID_TOPIC = Pattern.compile("^[a-zA-Z0-9._-]{1,128}$");

    private TopicNameValidator() {}

    /**
     * Returns true if the topic name is safe for use in LISTEN/NOTIFY channels.
     */
    public static boolean isValid(String topic) {
        return topic != null && VALID_TOPIC.matcher(topic).matches();
    }

    /**
     * Validates the topic name and throws if it is unsafe.
     *
     * @throws IllegalArgumentException if the topic name is null, empty, or
     *         contains characters outside the allowed set
     */
    public static void validate(String topic) {
        if (!isValid(topic)) {
            throw new IllegalArgumentException(
                    "Invalid topic name: must be 1-128 characters, alphanumeric, hyphens, underscores, or dots");
        }
    }
}
