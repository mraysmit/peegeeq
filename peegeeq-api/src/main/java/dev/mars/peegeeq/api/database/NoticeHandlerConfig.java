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
package dev.mars.peegeeq.api.database;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for PostgreSQL notice handling.
 * 
 * Controls how PostgreSQL notices (RAISE INFO, RAISE NOTICE, RAISE WARNING)
 * are logged and monitored in the PeeGeeQ system.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-20
 * @version 1.0
 */
public class NoticeHandlerConfig {
    private final boolean peeGeeQInfoLoggingEnabled;
    private final String peeGeeQInfoLogLevel;
    private final boolean otherNoticesLoggingEnabled;
    private final String otherNoticesLogLevel;
    private final boolean metricsEnabled;

    /**
     * Creates a NoticeHandlerConfig with default values.
     * Default: PeeGeeQ info logging enabled at INFO level, other notices disabled, metrics enabled.
     */
    public NoticeHandlerConfig() {
        this(true, "INFO", false, "DEBUG", true);
    }

    @JsonCreator
    public NoticeHandlerConfig(
            @JsonProperty("peeGeeQInfoLoggingEnabled") boolean peeGeeQInfoLoggingEnabled,
            @JsonProperty("peeGeeQInfoLogLevel") String peeGeeQInfoLogLevel,
            @JsonProperty("otherNoticesLoggingEnabled") boolean otherNoticesLoggingEnabled,
            @JsonProperty("otherNoticesLogLevel") String otherNoticesLogLevel,
            @JsonProperty("metricsEnabled") boolean metricsEnabled) {
        this.peeGeeQInfoLoggingEnabled = peeGeeQInfoLoggingEnabled;
        this.peeGeeQInfoLogLevel = peeGeeQInfoLogLevel != null ? peeGeeQInfoLogLevel : "INFO";
        this.otherNoticesLoggingEnabled = otherNoticesLoggingEnabled;
        this.otherNoticesLogLevel = otherNoticesLogLevel != null ? otherNoticesLogLevel : "DEBUG";
        this.metricsEnabled = metricsEnabled;
    }

    public boolean isPeeGeeQInfoLoggingEnabled() {
        return peeGeeQInfoLoggingEnabled;
    }

    public String getPeeGeeQInfoLogLevel() {
        return peeGeeQInfoLogLevel;
    }

    public boolean isOtherNoticesLoggingEnabled() {
        return otherNoticesLoggingEnabled;
    }

    public String getOtherNoticesLogLevel() {
        return otherNoticesLogLevel;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    /**
     * Builder for NoticeHandlerConfig.
     */
    public static class Builder {
        private boolean peeGeeQInfoLoggingEnabled = true;
        private String peeGeeQInfoLogLevel = "INFO";
        private boolean otherNoticesLoggingEnabled = false;
        private String otherNoticesLogLevel = "DEBUG";
        private boolean metricsEnabled = true;

        public Builder peeGeeQInfoLoggingEnabled(boolean enabled) {
            this.peeGeeQInfoLoggingEnabled = enabled;
            return this;
        }

        public Builder peeGeeQInfoLogLevel(String level) {
            this.peeGeeQInfoLogLevel = level;
            return this;
        }

        public Builder otherNoticesLoggingEnabled(boolean enabled) {
            this.otherNoticesLoggingEnabled = enabled;
            return this;
        }

        public Builder otherNoticesLogLevel(String level) {
            this.otherNoticesLogLevel = level;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public NoticeHandlerConfig build() {
            return new NoticeHandlerConfig(
                peeGeeQInfoLoggingEnabled,
                peeGeeQInfoLogLevel,
                otherNoticesLoggingEnabled,
                otherNoticesLogLevel,
                metricsEnabled
            );
        }
    }
}

