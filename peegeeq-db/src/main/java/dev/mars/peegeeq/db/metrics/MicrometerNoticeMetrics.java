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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer-based implementation of NoticeMetrics.
 * 
 * Provides production-ready metrics for PostgreSQL notice handling
 * using Micrometer's metric registry.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-20
 * @version 1.0
 */
public class MicrometerNoticeMetrics implements NoticeMetrics {
    
    private final MeterRegistry registry;
    
    public MicrometerNoticeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }
    
    @Override
    public void incrementInfoNotices(String infoCode) {
        // Increment general PeeGeeQ info counter
        Counter.builder("peegeeq.notice.peegeeq_info")
            .description("Total count of PeeGeeQ info notices")
            .register(registry)
            .increment();

        // Also increment tagged counter for detailed metrics
        Counter.builder("peegeeq.notices.info.total")
            .tag("code", infoCode != null ? infoCode : "unknown")
            .description("Total count of PeeGeeQ info notices by code")
            .register(registry)
            .increment();
    }

    @Override
    public void incrementWarnings(String sqlState) {
        // Increment general PostgreSQL warning counter
        Counter.builder("peegeeq.notice.postgres_warning")
            .description("Total count of PostgreSQL warnings")
            .register(registry)
            .increment();

        // Also increment tagged counter for detailed metrics
        Counter.builder("peegeeq.notices.warnings.total")
            .tag("sql_state", sqlState != null ? sqlState : "unknown")
            .description("Total count of PostgreSQL warnings by SQL state")
            .register(registry)
            .increment();
    }

    @Override
    public void incrementOtherNotices(String severity) {
        // Increment general other notices counter
        Counter.builder("peegeeq.notice.other")
            .description("Total count of other PostgreSQL notices")
            .register(registry)
            .increment();

        // Also increment tagged counter for detailed metrics
        Counter.builder("peegeeq.notices.other.total")
            .tag("severity", severity != null ? severity : "unknown")
            .description("Total count of other PostgreSQL notices by severity")
            .register(registry)
            .increment();
    }
    
    @Override
    public void recordHandlerDuration(long nanos) {
        Timer.builder("peegeeq.notices.handler.duration")
            .description("Duration of notice handler execution")
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }
}

