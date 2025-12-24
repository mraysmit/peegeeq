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
package dev.mars.peegeeq.api.info;

/**
 * Standard info codes for PeeGeeQ system.
 * These codes are used for INFO-level logging to enable log tracing and monitoring.
 * 
 * Info code ranges (parallel to error codes):
 * - PGQINF0001-0049: General/System info
 * - PGQINF0050-0099: Subscription info
 * - PGQINF0100-0149: Setup/Configuration info
 * - PGQINF0150-0199: Queue info
 * - PGQINF0200-0249: Dead Letter Queue info
 * - PGQINF0250-0299: Event Store info
 * - PGQINF0300-0349: Webhook info
 * - PGQINF0350-0399: Health/Metrics info
 * - PGQINF0400-0449: Message info
 * - PGQINF0450-0499: Consumer Group info
 * - PGQINF0500-0549: Database/Connection info
 * - PGQINF0550-0599: Schema/Infrastructure info
 * - PGQINF0600-0649: Notice Handler info
 * - PGQINF0650-0699: Cleanup/Maintenance info
 */
public final class PeeGeeQInfoCodes {

    private PeeGeeQInfoCodes() {
        // Utility class - no instantiation
    }

    // ========================================================================
    // General/System Info (0001-0049)
    // ========================================================================
    public static final String SYSTEM_STARTED = "PGQINF0001";
    public static final String SYSTEM_STOPPED = "PGQINF0002";
    public static final String SYSTEM_READY = "PGQINF0003";

    // ========================================================================
    // Subscription Info (0050-0099)
    // ========================================================================
    public static final String SUBSCRIPTION_CREATED = "PGQINF0050";
    public static final String SUBSCRIPTION_PAUSED = "PGQINF0051";
    public static final String SUBSCRIPTION_RESUMED = "PGQINF0052";
    public static final String SUBSCRIPTION_CANCELLED = "PGQINF0053";

    // ========================================================================
    // Setup/Configuration Info (0100-0149)
    // ========================================================================
    public static final String SETUP_CREATED = "PGQINF0100";
    public static final String SETUP_DELETED = "PGQINF0101";
    public static final String SETUP_ACTIVATED = "PGQINF0102";

    // ========================================================================
    // Queue Info (0150-0199)
    // ========================================================================
    public static final String QUEUE_CREATED = "PGQINF0150";
    public static final String QUEUE_DELETED = "PGQINF0151";

    // ========================================================================
    // Dead Letter Queue Info (0200-0249)
    // ========================================================================
    public static final String DLQ_MESSAGE_REPROCESSED = "PGQINF0200";
    public static final String DLQ_MESSAGE_DELETED = "PGQINF0201";
    public static final String DLQ_PURGED = "PGQINF0202";

    // ========================================================================
    // Event Store Info (0250-0299)
    // ========================================================================
    public static final String EVENT_STORE_CREATED = "PGQINF0250";
    public static final String EVENT_STORED = "PGQINF0251";
    public static final String EVENT_CORRECTED = "PGQINF0252";

    // ========================================================================
    // Webhook Info (0300-0349)
    // ========================================================================
    public static final String WEBHOOK_SUBSCRIPTION_CREATED = "PGQINF0300";
    public static final String WEBHOOK_SUBSCRIPTION_DELETED = "PGQINF0301";
    public static final String WEBHOOK_DELIVERED = "PGQINF0302";

    // ========================================================================
    // Health/Metrics Info (0350-0399)
    // ========================================================================
    public static final String HEALTH_CHECK_PASSED = "PGQINF0350";
    public static final String METRICS_COLLECTED = "PGQINF0351";

    // ========================================================================
    // Message Info (0400-0449)
    // ========================================================================
    public static final String MESSAGE_SENT = "PGQINF0400";
    public static final String MESSAGE_ACKNOWLEDGED = "PGQINF0401";
    public static final String MESSAGE_RECEIVED = "PGQINF0402";

    // ========================================================================
    // Consumer Group Info (0450-0499)
    // ========================================================================
    public static final String CONSUMER_GROUP_CREATED = "PGQINF0450";
    public static final String CONSUMER_JOINED = "PGQINF0451";
    public static final String CONSUMER_LEFT = "PGQINF0452";

    // ========================================================================
    // Database/Connection Info (0500-0549)
    // ========================================================================
    public static final String DATABASE_CONNECTED = "PGQINF0500";
    public static final String DATABASE_CREATED = "PGQINF0501";
    public static final String DATABASE_POOL_CREATED = "PGQINF0502";
    public static final String DATABASE_TRANSACTION_COMMITTED = "PGQINF0503";

    // ========================================================================
    // Schema/Infrastructure Info (0550-0599)
    // ========================================================================
    public static final String SCHEMA_CREATED = "PGQINF0550";
    public static final String SCHEMA_EXISTS = "PGQINF0551";
    public static final String TABLE_CREATED = "PGQINF0552";
    public static final String TABLE_EXISTS = "PGQINF0553";
    public static final String INDEX_CREATED = "PGQINF0554";
    public static final String FUNCTION_CREATED = "PGQINF0555";
    public static final String TRIGGER_CREATED = "PGQINF0556";
    public static final String EXTENSION_CREATED = "PGQINF0557";
    public static final String INFRASTRUCTURE_READY = "PGQINF0558";
    public static final String SCHEMA_SETUP_STARTED = "PGQINF0559";

    // ========================================================================
    // Notice Handler Info (0600-0649)
    // ========================================================================
    public static final String NOTICE_HANDLER_ATTACHED = "PGQINF0600";
    public static final String NOTICE_HANDLER_INFO_RECEIVED = "PGQINF0601";
    public static final String NOTICE_HANDLER_WARNING_RECEIVED = "PGQINF0602";

    // ========================================================================
    // Cleanup/Maintenance Info (0650-0699)
    // ========================================================================
    public static final String CLEANUP_COMPLETED = "PGQINF0650";
    public static final String CLEANUP_MESSAGE_PROCESSING = "PGQINF0651";
    public static final String CLEANUP_OUTBOX_MESSAGES = "PGQINF0652";
    public static final String CLEANUP_CONSUMER_GROUPS = "PGQINF0653";
    public static final String CLEANUP_METRICS = "PGQINF0654";
}

