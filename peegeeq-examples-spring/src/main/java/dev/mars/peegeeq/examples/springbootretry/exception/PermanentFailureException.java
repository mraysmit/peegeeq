package dev.mars.peegeeq.examples.springbootretry.exception;

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

/**
 * Exception for permanent failures that should NOT be retried.
 * 
 * Examples:
 * - Invalid data format
 * - Business rule violations
 * - Authorization failures
 * - Resource not found
 * 
 * Note: In PeeGeeQ, we still throw this exception, but we can track it
 * separately and potentially move to DLQ immediately without retries.
 */
public class PermanentFailureException extends RuntimeException {
    
    public PermanentFailureException(String message) {
        super(message);
    }
    
    public PermanentFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}

