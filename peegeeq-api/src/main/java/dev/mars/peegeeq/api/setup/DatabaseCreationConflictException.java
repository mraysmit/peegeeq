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

package dev.mars.peegeeq.api.setup;

/**
 * Thrown when concurrent setup requests collide while creating the same database.
 *
 * <p>Does not fill in a stack trace: the conflict is a known race outcome
 * (typically mapped to HTTP 409), not a programming error, so the trace adds
 * cost and log noise without diagnostic value.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-11
 * @version 1.0
 */
public class DatabaseCreationConflictException extends RuntimeException {
    public DatabaseCreationConflictException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
