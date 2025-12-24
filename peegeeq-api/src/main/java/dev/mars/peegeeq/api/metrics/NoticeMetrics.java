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
package dev.mars.peegeeq.api.metrics;

/**
 * Metrics interface for PostgreSQL notice handling.
 * 
 * Provides observability into PostgreSQL notice patterns including
 * PeeGeeQ info messages, PostgreSQL warnings, and other notices.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-20
 * @version 1.0
 */
public interface NoticeMetrics {
    
    /**
     * Increment counter for PeeGeeQ info notices by code.
     * 
     * Metric name: peegeeq_notices_info_total
     * Tags: code (e.g., "PGQINF0100")
     * 
     * @param infoCode The PeeGeeQ info code (e.g., "PGQINF0100")
     */
    void incrementInfoNotices(String infoCode);
    
    /**
     * Increment counter for PostgreSQL warnings by SQL state.
     * 
     * Metric name: peegeeq_notices_warnings_total
     * Tags: sql_state (e.g., "01000")
     * 
     * @param sqlState The PostgreSQL SQL state code
     */
    void incrementWarnings(String sqlState);
    
    /**
     * Increment counter for other notices by severity.
     * 
     * Metric name: peegeeq_notices_other_total
     * Tags: severity (e.g., "NOTICE", "DEBUG", "LOG")
     * 
     * @param severity The PostgreSQL notice severity level
     */
    void incrementOtherNotices(String severity);
    
    /**
     * Record timing for notice handler execution.
     * 
     * Metric name: peegeeq_notices_handler_duration_seconds
     * Type: Histogram/Timer
     * 
     * @param nanos The duration in nanoseconds
     */
    void recordHandlerDuration(long nanos);
}

