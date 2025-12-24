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
package dev.mars.peegeeq.db.metrics;

import dev.mars.peegeeq.api.metrics.NoticeMetrics;

/**
 * No-op implementation of NoticeMetrics.
 * 
 * Used when metrics collection is disabled or when no MeterRegistry is available.
 * All methods are no-ops with minimal overhead.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-20
 * @version 1.0
 */
public class NoOpNoticeMetrics implements NoticeMetrics {
    
    @Override
    public void incrementInfoNotices(String infoCode) {
        // No-op
    }
    
    @Override
    public void incrementWarnings(String sqlState) {
        // No-op
    }
    
    @Override
    public void incrementOtherNotices(String severity) {
        // No-op
    }
    
    @Override
    public void recordHandlerDuration(long nanos) {
        // No-op
    }
}

