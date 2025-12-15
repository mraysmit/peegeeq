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

package dev.mars.peegeeq.client.dto;

/**
 * Information about an event store from the Management API.
 */
public record EventStoreInfo(
    String name,
    String setupId,
    long eventCount,
    long correctionCount,
    int subscriberCount,
    String status
) {
    /**
     * Creates a new event store info with default values.
     */
    public static EventStoreInfo create(String name, String setupId) {
        return new EventStoreInfo(name, setupId, 0, 0, 0, "ACTIVE");
    }
}

