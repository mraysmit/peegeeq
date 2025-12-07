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

import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;

import java.util.List;

/**
 * Response containing a paginated list of dead letter messages.
 */
public record DeadLetterListResponse(
    List<DeadLetterMessageInfo> messages,
    long total,
    int page,
    int pageSize
) {
    /**
     * Returns true if there are more pages available.
     */
    public boolean hasMore() {
        return (long) (page + 1) * pageSize < total;
    }

    /**
     * Returns the total number of pages.
     */
    public int totalPages() {
        return (int) Math.ceil((double) total / pageSize);
    }
}

