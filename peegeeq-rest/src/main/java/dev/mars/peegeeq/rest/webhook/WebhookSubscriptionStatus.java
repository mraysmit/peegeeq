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

package dev.mars.peegeeq.rest.webhook;

/**
 * Status of a webhook subscription.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 1.0
 */
public enum WebhookSubscriptionStatus {
    /**
     * Subscription is active and receiving messages.
     */
    ACTIVE,
    
    /**
     * Subscription is temporarily paused (can be resumed).
     */
    PAUSED,
    
    /**
     * Subscription failed too many consecutive delivery attempts and was automatically disabled.
     */
    FAILED,
    
    /**
     * Subscription was explicitly deleted by the client.
     */
    DELETED
}
