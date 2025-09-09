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

/**
 * Defines the operational mode for native queue consumers.
 * Following the established pattern of feature toggles in PeeGeeQ.
 */
public enum ConsumerMode {
    /**
     * Uses only LISTEN/NOTIFY for real-time message processing.
     * No polling scheduler is created. Lowest database load.
     * Best for: Real-time applications with reliable connections.
     */
    LISTEN_NOTIFY_ONLY,
    
    /**
     * Uses only scheduled polling for message processing.
     * No LISTEN/NOTIFY setup. Works with connection issues.
     * Best for: Batch processing, unreliable networks.
     */
    POLLING_ONLY,
    
    /**
     * Uses both LISTEN/NOTIFY and polling (current behavior).
     * Maximum reliability with polling as backup.
     * Best for: Critical applications requiring guaranteed delivery.
     */
    HYBRID
}
