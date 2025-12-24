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
package dev.mars.peegeeq.api.error;

/**
 * Standard error codes for PeeGeeQ system.
 * 
 * Error code ranges:
 * - PGQERR0001-0049: General/System errors
 * - PGQERR0050-0099: Subscription errors
 * - PGQERR0100-0149: Setup/Configuration errors
 * - PGQERR0150-0199: Queue errors
 * - PGQERR0200-0249: Dead Letter Queue errors
 * - PGQERR0250-0299: Event Store errors
 * - PGQERR0300-0349: Webhook errors
 * - PGQERR0350-0399: Health/Metrics errors
 * - PGQERR0400-0449: Message errors
 * - PGQERR0450-0499: Consumer Group errors
 * - PGQERR0500-0549: Database/Connection errors
 * - PGQERR0550-0599: Validation errors
 */
public final class PeeGeeQErrorCodes {

    private PeeGeeQErrorCodes() {
        // Utility class - no instantiation
    }

    // ========================================================================
    // General/System Errors (0001-0049)
    // ========================================================================
    public static final String INTERNAL_ERROR = "PGQERR0001";
    public static final String INVALID_REQUEST = "PGQERR0002";
    public static final String UNAUTHORIZED = "PGQERR0003";
    public static final String FORBIDDEN = "PGQERR0004";
    public static final String RESOURCE_NOT_FOUND = "PGQERR0005";
    public static final String METHOD_NOT_ALLOWED = "PGQERR0006";
    public static final String CONFLICT = "PGQERR0007";
    public static final String SERVICE_UNAVAILABLE = "PGQERR0008";
    public static final String TIMEOUT = "PGQERR0009";

    // ========================================================================
    // Subscription Errors (0050-0099)
    // ========================================================================
    public static final String SUBSCRIPTION_NOT_FOUND = "PGQERR0050";
    public static final String SUBSCRIPTION_ALREADY_EXISTS = "PGQERR0051";
    public static final String SUBSCRIPTION_INVALID_STATE = "PGQERR0052";
    public static final String SUBSCRIPTION_PAUSE_FAILED = "PGQERR0053";
    public static final String SUBSCRIPTION_RESUME_FAILED = "PGQERR0054";
    public static final String SUBSCRIPTION_CANCEL_FAILED = "PGQERR0055";
    public static final String SUBSCRIPTION_HEARTBEAT_FAILED = "PGQERR0056";
    public static final String SUBSCRIPTION_CREATE_FAILED = "PGQERR0057";

    // ========================================================================
    // Setup/Configuration Errors (0100-0149)
    // ========================================================================
    public static final String SETUP_NOT_FOUND = "PGQERR0100";
    public static final String SETUP_ALREADY_EXISTS = "PGQERR0101";
    public static final String SETUP_INACTIVE = "PGQERR0102";
    public static final String SETUP_CREATE_FAILED = "PGQERR0103";
    public static final String SETUP_DELETE_FAILED = "PGQERR0104";
    public static final String SETUP_INVALID_CONFIG = "PGQERR0105";

    // ========================================================================
    // Queue Errors (0150-0199)
    // ========================================================================
    public static final String QUEUE_NOT_FOUND = "PGQERR0150";
    public static final String QUEUE_ALREADY_EXISTS = "PGQERR0151";
    public static final String QUEUE_CREATE_FAILED = "PGQERR0152";
    public static final String QUEUE_DELETE_FAILED = "PGQERR0153";
    public static final String QUEUE_FULL = "PGQERR0154";
    public static final String QUEUE_EMPTY = "PGQERR0155";

    // ========================================================================
    // Dead Letter Queue Errors (0200-0249)
    // ========================================================================
    public static final String DLQ_MESSAGE_NOT_FOUND = "PGQERR0200";
    public static final String DLQ_REPROCESS_FAILED = "PGQERR0201";
    public static final String DLQ_DELETE_FAILED = "PGQERR0202";
    public static final String DLQ_PURGE_FAILED = "PGQERR0203";
    public static final String DLQ_STATS_FAILED = "PGQERR0204";
    public static final String DLQ_LIST_FAILED = "PGQERR0205";
    public static final String DLQ_GET_FAILED = "PGQERR0206";
    public static final String DLQ_CLEANUP_FAILED = "PGQERR0207";

    // ========================================================================
    // Event Store Errors (0250-0299)
    // ========================================================================
    public static final String EVENT_STORE_NOT_FOUND = "PGQERR0250";
    public static final String EVENT_NOT_FOUND = "PGQERR0251";
    public static final String EVENT_STORE_FAILED = "PGQERR0252";
    public static final String EVENT_QUERY_FAILED = "PGQERR0253";
    public static final String EVENT_CORRECTION_FAILED = "PGQERR0254";
    public static final String EVENT_INVALID_TEMPORAL_RANGE = "PGQERR0255";
    public static final String EVENT_VERSION_CONFLICT = "PGQERR0256";

    // ========================================================================
    // Webhook Errors (0300-0349)
    // ========================================================================
    public static final String WEBHOOK_NOT_FOUND = "PGQERR0300";
    public static final String WEBHOOK_CREATE_FAILED = "PGQERR0301";
    public static final String WEBHOOK_DELETE_FAILED = "PGQERR0302";
    public static final String WEBHOOK_DELIVERY_FAILED = "PGQERR0303";
    public static final String WEBHOOK_INVALID_URL = "PGQERR0304";

    // ========================================================================
    // Health/Metrics Errors (0350-0399)
    // ========================================================================
    public static final String HEALTH_CHECK_FAILED = "PGQERR0350";
    public static final String METRICS_UNAVAILABLE = "PGQERR0351";
    public static final String COMPONENT_UNHEALTHY = "PGQERR0352";

    // ========================================================================
    // Message Errors (0400-0449)
    // ========================================================================
    public static final String MESSAGE_NOT_FOUND = "PGQERR0400";
    public static final String MESSAGE_SEND_FAILED = "PGQERR0401";
    public static final String MESSAGE_ACK_FAILED = "PGQERR0402";
    public static final String MESSAGE_NACK_FAILED = "PGQERR0403";
    public static final String MESSAGE_INVALID_PAYLOAD = "PGQERR0404";

    // ========================================================================
    // Consumer Group Errors (0450-0499)
    // ========================================================================
    public static final String CONSUMER_GROUP_NOT_FOUND = "PGQERR0450";
    public static final String CONSUMER_GROUP_CREATE_FAILED = "PGQERR0451";
    public static final String CONSUMER_MEMBER_NOT_FOUND = "PGQERR0452";

    // ========================================================================
    // Database/Connection Errors (0500-0549)
    // ========================================================================
    public static final String DATABASE_CONNECTION_FAILED = "PGQERR0500";
    public static final String DATABASE_QUERY_FAILED = "PGQERR0501";
    public static final String DATABASE_TRANSACTION_FAILED = "PGQERR0502";
    public static final String DATABASE_POOL_EXHAUSTED = "PGQERR0503";

    // ========================================================================
    // Validation Errors (0550-0599)
    // ========================================================================
    public static final String VALIDATION_FAILED = "PGQERR0550";
    public static final String MISSING_REQUIRED_FIELD = "PGQERR0551";
    public static final String INVALID_FIELD_VALUE = "PGQERR0552";
    public static final String INVALID_JSON_FORMAT = "PGQERR0553";
    public static final String INVALID_MESSAGE_ID = "PGQERR0554";
}

