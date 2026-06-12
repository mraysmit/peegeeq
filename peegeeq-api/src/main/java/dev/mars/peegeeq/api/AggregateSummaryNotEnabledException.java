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

package dev.mars.peegeeq.api;

/**
 * Thrown when a summary-only operation (forced {@link EventStore.AggregateSource#SUMMARY}
 * queries, {@link EventStore#reconcileAggregateSummary}) is requested on an event store
 * that was not created with {@code aggregateSummaryEnabled=true}.
 *
 * <p>A typed exception so callers (e.g. REST handlers mapping it to HTTP 400) can classify
 * it by type rather than by message text.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-12
 * @version 1.0
 */
public class AggregateSummaryNotEnabledException extends IllegalStateException {
    public AggregateSummaryNotEnabledException(String message) {
        super(message);
    }
}
