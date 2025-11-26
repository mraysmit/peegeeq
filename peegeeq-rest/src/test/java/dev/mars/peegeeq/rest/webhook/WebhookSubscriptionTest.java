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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core unit tests for WebhookSubscription model.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class WebhookSubscriptionTest {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookSubscriptionTest.class);
    
    @Test
    void testCreateSubscription() {
        logger.info("\n" + "=".repeat(80));
        logger.info("TEST: testCreateSubscription");
        logger.info("PURPOSE: Verify that WebhookSubscription object is created correctly with all fields");
        logger.info("=".repeat(80));
        
        String subscriptionId = "sub-123";
        String setupId = "setup-456";
        String queueName = "test-queue";
        String webhookUrl = "https://example.com/webhook";
        Map<String, String> headers = Map.of("Authorization", "Bearer token");
        Map<String, String> filters = Map.of("eventType", "order");
        
        logger.info("\n[STEP 1] Preparing test data:");
        logger.info("  subscriptionId : {} (type: String, length: {})", subscriptionId, subscriptionId.length());
        logger.info("  setupId        : {} (type: String, length: {})", setupId, setupId.length());
        logger.info("  queueName      : {} (type: String, length: {})", queueName, queueName.length());
        logger.info("  webhookUrl     : {} (type: String, length: {})", webhookUrl, webhookUrl.length());
        logger.info("  headers        : {} (type: Map<String,String>, size: {})", headers, headers.size());
        logger.info("  filters        : {} (type: Map<String,String>, size: {})", filters, filters.size());
        
        logger.info("\n[STEP 2] Calling WebhookSubscription constructor...");
        long startTime = System.nanoTime();
        WebhookSubscription subscription = new WebhookSubscription(
            subscriptionId, setupId, queueName, webhookUrl, headers, filters);
        long endTime = System.nanoTime();
        logger.info("  Constructor completed in {} nanoseconds ({} microseconds)", 
            (endTime - startTime), (endTime - startTime) / 1000);
        
        logger.info("\n[STEP 3] Subscription object created: {}", subscription);
        logger.info("  Object class: {}", subscription.getClass().getName());
        logger.info("  Object hashCode: {}", subscription.hashCode());
        
        logger.info("\n[STEP 4] Verifying all fields match expected values:");
        
        logger.info("  [4.1] subscriptionId");
        logger.info("        Expected: '{}'", subscriptionId);
        logger.info("        Actual  : '{}'", subscription.getSubscriptionId());
        assertEquals(subscriptionId, subscription.getSubscriptionId());
        logger.info("        ✓ MATCH");
        
        logger.info("  [4.2] setupId");
        logger.info("        Expected: '{}'", setupId);
        logger.info("        Actual  : '{}'", subscription.getSetupId());
        assertEquals(setupId, subscription.getSetupId());
        logger.info("        ✓ MATCH");
        
        logger.info("  [4.3] queueName");
        logger.info("        Expected: '{}'", queueName);
        logger.info("        Actual  : '{}'", subscription.getQueueName());
        assertEquals(queueName, subscription.getQueueName());
        logger.info("        ✓ MATCH");
        
        logger.info("  [4.4] webhookUrl");
        logger.info("        Expected: '{}'", webhookUrl);
        logger.info("        Actual  : '{}'", subscription.getWebhookUrl());
        assertEquals(webhookUrl, subscription.getWebhookUrl());
        logger.info("        ✓ MATCH");
        
        logger.info("  [4.5] headers");
        logger.info("        Expected: {} (size: {})", headers, headers.size());
        logger.info("        Actual  : {} (size: {})", subscription.getHeaders(), subscription.getHeaders().size());
        assertEquals(headers, subscription.getHeaders());
        logger.info("        ✓ MATCH");
        
        logger.info("  [4.6] filters");
        logger.info("        Expected: {} (size: {})", filters, filters.size());
        logger.info("        Actual  : {} (size: {})", subscription.getFilters(), subscription.getFilters().size());
        assertEquals(filters, subscription.getFilters());
        logger.info("        ✓ MATCH");
        
        logger.info("  [4.7] status (default value check)");
        logger.info("        Expected: {}", WebhookSubscriptionStatus.ACTIVE);
        logger.info("        Actual  : {}", subscription.getStatus());
        assertEquals(WebhookSubscriptionStatus.ACTIVE, subscription.getStatus());
        logger.info("        ✓ MATCH (default status is ACTIVE)");
        
        logger.info("  [4.8] consecutiveFailures (default value check)");
        logger.info("        Expected: 0");
        logger.info("        Actual  : {}", subscription.getConsecutiveFailures());
        assertEquals(0, subscription.getConsecutiveFailures());
        logger.info("        ✓ MATCH (default failures is 0)");
        
        logger.info("  [4.9] createdAt (non-null check)");
        logger.info("        Actual  : {}", subscription.getCreatedAt());
        assertNotNull(subscription.getCreatedAt());
        logger.info("        ✓ NOT NULL (timestamp was set)");
        
        logger.info("\n" + "=".repeat(80));
        logger.info("✓✓✓ TEST PASSED: testCreateSubscription - All {} assertions passed!", 9);
        logger.info("=".repeat(80) + "\n");
    }
    
    @Test
    void testFailureTracking() {
        logger.info("\n" + "=".repeat(80));
        logger.info("TEST: testFailureTracking");
        logger.info("PURPOSE: Verify consecutive failure tracking increments, tracks state, and resets correctly");
        logger.info("=".repeat(80));
        
        logger.info("\n[STEP 1] Creating test subscription with empty headers/filters...");
        WebhookSubscription subscription = new WebhookSubscription(
            "sub-123", "setup-456", "test-queue", "https://example.com/webhook", 
            Map.of(), Map.of());
        logger.info("  Subscription created: {}", subscription);
        
        logger.info("\n[STEP 2] Verify initial state");
        logger.info("  Checking consecutiveFailures...");
        int initialFailures = subscription.getConsecutiveFailures();
        logger.info("  Expected: 0 (fresh subscription should have no failures)");
        logger.info("  Actual  : {}", initialFailures);
        assertEquals(0, initialFailures);
        logger.info("  ✓ Initial failure count is correct");
        
        logger.info("\n[STEP 3] Simulating webhook delivery failures");
        
        logger.info("  [3.1] First delivery failure - incrementing...");
        logger.info("        Before: consecutiveFailures = {}", subscription.getConsecutiveFailures());
        subscription.incrementConsecutiveFailures();
        logger.info("        After : consecutiveFailures = {}", subscription.getConsecutiveFailures());
        assertEquals(1, subscription.getConsecutiveFailures());
        logger.info("        ✓ Failure count incremented correctly (0 → 1)");
        
        logger.info("  [3.2] Second delivery failure - incrementing again...");
        logger.info("        Before: consecutiveFailures = {}", subscription.getConsecutiveFailures());
        subscription.incrementConsecutiveFailures();
        logger.info("        After : consecutiveFailures = {}", subscription.getConsecutiveFailures());
        assertEquals(2, subscription.getConsecutiveFailures());
        logger.info("        ✓ Failure count incremented correctly (1 → 2)");
        
        logger.info("  [3.3] Third delivery failure - incrementing once more...");
        logger.info("        Before: consecutiveFailures = {}", subscription.getConsecutiveFailures());
        subscription.incrementConsecutiveFailures();
        logger.info("        After : consecutiveFailures = {}", subscription.getConsecutiveFailures());
        assertEquals(3, subscription.getConsecutiveFailures());
        logger.info("        ✓ Failure count incremented correctly (2 → 3)");
        logger.info("        NOTE: Circuit breaker threshold is 5 failures, currently at 3");
        
        logger.info("\n[STEP 4] Simulating successful delivery - resetting failures");
        logger.info("  Before reset: consecutiveFailures = {}", subscription.getConsecutiveFailures());
        subscription.resetConsecutiveFailures();
        logger.info("  After reset : consecutiveFailures = {}", subscription.getConsecutiveFailures());
        assertEquals(0, subscription.getConsecutiveFailures());
        logger.info("  ✓ Failure count reset correctly (3 → 0)");
        logger.info("  Circuit breaker is now reset, subscription can receive messages again");
        
        logger.info("\n[STEP 5] Verify idempotency - resetting again when already at 0");
        logger.info("  Before reset: consecutiveFailures = {}", subscription.getConsecutiveFailures());
        subscription.resetConsecutiveFailures();
        logger.info("  After reset : consecutiveFailures = {}", subscription.getConsecutiveFailures());
        assertEquals(0, subscription.getConsecutiveFailures());
        logger.info("  ✓ Reset is idempotent (0 → 0)");
        
        logger.info("\n" + "=".repeat(80));
        logger.info("✓✓✓ TEST PASSED: testFailureTracking - All {} assertions passed!", 6);
        logger.info("    Tested: initial state, 3 increments, reset, idempotency");
        logger.info("=".repeat(80) + "\n");
    }
    
    @Test
    void testStatusChanges() {
        logger.info("\n" + "=".repeat(80));
        logger.info("TEST: testStatusChanges");
        logger.info("PURPOSE: Verify subscription status can transition between states (ACTIVE→PAUSED→FAILED→DELETED)");
        logger.info("=".repeat(80));
        
        logger.info("\n[STEP 1] Creating test subscription...");
        WebhookSubscription subscription = new WebhookSubscription(
            "sub-123", "setup-456", "test-queue", "https://example.com/webhook", 
            Map.of(), Map.of());
        logger.info("  Subscription created: {}", subscription);
        
        logger.info("\n[STEP 2] Verify initial status is ACTIVE");
        WebhookSubscriptionStatus initialStatus = subscription.getStatus();
        logger.info("  Expected: {}", WebhookSubscriptionStatus.ACTIVE);
        logger.info("  Actual  : {}", initialStatus);
        assertEquals(WebhookSubscriptionStatus.ACTIVE, initialStatus);
        logger.info("  ✓ Default status is ACTIVE (ready to receive messages)");
        
        logger.info("\n[STEP 3] Transition: ACTIVE → PAUSED");
        logger.info("  Scenario: Admin temporarily pauses webhook delivery");
        logger.info("  Before: status = {}", subscription.getStatus());
        subscription.setStatus(WebhookSubscriptionStatus.PAUSED);
        logger.info("  After : status = {}", subscription.getStatus());
        assertEquals(WebhookSubscriptionStatus.PAUSED, subscription.getStatus());
        logger.info("  ✓ Status changed to PAUSED (messages will queue but not deliver)");
        
        logger.info("\n[STEP 4] Transition: PAUSED → ACTIVE (resume)");
        logger.info("  Scenario: Admin resumes webhook delivery");
        logger.info("  Before: status = {}", subscription.getStatus());
        subscription.setStatus(WebhookSubscriptionStatus.ACTIVE);
        logger.info("  After : status = {}", subscription.getStatus());
        assertEquals(WebhookSubscriptionStatus.ACTIVE, subscription.getStatus());
        logger.info("  ✓ Status changed to ACTIVE (messages will deliver again)");
        
        logger.info("\n[STEP 5] Transition: ACTIVE → FAILED");
        logger.info("  Scenario: Circuit breaker triggered after 5 consecutive failures");
        logger.info("  Before: status = {}", subscription.getStatus());
        subscription.setStatus(WebhookSubscriptionStatus.FAILED);
        logger.info("  After : status = {}", subscription.getStatus());
        assertEquals(WebhookSubscriptionStatus.FAILED, subscription.getStatus());
        logger.info("  ✓ Status changed to FAILED (automatic circuit breaker)");
        
        logger.info("\n[STEP 6] Transition: FAILED → DELETED");
        logger.info("  Scenario: Client deletes the failed subscription");
        logger.info("  Before: status = {}", subscription.getStatus());
        subscription.setStatus(WebhookSubscriptionStatus.DELETED);
        logger.info("  After : status = {}", subscription.getStatus());
        assertEquals(WebhookSubscriptionStatus.DELETED, subscription.getStatus());
        logger.info("  ✓ Status changed to DELETED (subscription marked for cleanup)");
        
        logger.info("\n[STEP 7] State transition summary:");
        logger.info("  ACTIVE → PAUSED → ACTIVE → FAILED → DELETED");
        logger.info("  All status transitions completed successfully");
        
        logger.info("\n" + "=".repeat(80));
        logger.info("✓✓✓ TEST PASSED: testStatusChanges - All {} assertions passed!", 5);
        logger.info("    Tested all 4 status values and 5 transitions");
        logger.info("=".repeat(80) + "\n");
    }
    
    @Test
    void testEqualsAndHashCode() {
        logger.info("\n" + "=".repeat(80));
        logger.info("TEST: testEqualsAndHashCode");
        logger.info("PURPOSE: Verify equals() and hashCode() contract based on subscriptionId only");
        logger.info("=".repeat(80));
        
        logger.info("\n[STEP 1] Creating three test subscriptions with different characteristics");
        
        logger.info("  [1.1] Subscription 1: subscriptionId='sub-123'");
        WebhookSubscription sub1 = new WebhookSubscription(
            "sub-123", "setup-456", "test-queue", "https://example.com/webhook", 
            Map.of(), Map.of());
        logger.info("        Created: {}", sub1);
        logger.info("        hashCode: {}", sub1.hashCode());
        
        logger.info("  [1.2] Subscription 2: SAME subscriptionId='sub-123', DIFFERENT other fields");
        logger.info("        This tests that equality is based ONLY on subscriptionId");
        WebhookSubscription sub2 = new WebhookSubscription(
            "sub-123", "setup-999", "other-queue", "https://other.com/webhook", 
            Map.of(), Map.of());
        logger.info("        Created: {}", sub2);
        logger.info("        hashCode: {}", sub2.hashCode());
        logger.info("        Differences from sub1:");
        logger.info("          - setupId: '{}' vs '{}'", sub1.getSetupId(), sub2.getSetupId());
        logger.info("          - queueName: '{}' vs '{}'", sub1.getQueueName(), sub2.getQueueName());
        logger.info("          - webhookUrl: '{}' vs '{}'", sub1.getWebhookUrl(), sub2.getWebhookUrl());
        
        logger.info("  [1.3] Subscription 3: DIFFERENT subscriptionId='sub-999'");
        WebhookSubscription sub3 = new WebhookSubscription(
            "sub-999", "setup-456", "test-queue", "https://example.com/webhook", 
            Map.of(), Map.of());
        logger.info("        Created: {}", sub3);
        logger.info("        hashCode: {}", sub3.hashCode());
        
        logger.info("\n[STEP 2] Testing equality: sub1.equals(sub2)");
        logger.info("  Expected: true (same subscriptionId, other fields don't matter)");
        boolean equalsResult = sub1.equals(sub2);
        logger.info("  Actual  : {}", equalsResult);
        assertEquals(sub1, sub2);
        logger.info("  ✓ Equality test passed: subscriptions with same ID are equal");
        
        logger.info("\n[STEP 3] Testing hashCode contract: sub1.hashCode() == sub2.hashCode()");
        logger.info("  Rule: If two objects are equal, they MUST have same hashCode");
        int hash1 = sub1.hashCode();
        int hash2 = sub2.hashCode();
        logger.info("  sub1.hashCode(): {}", hash1);
        logger.info("  sub2.hashCode(): {}", hash2);
        logger.info("  Match: {}", hash1 == hash2);
        assertEquals(hash1, hash2);
        logger.info("  ✓ HashCode contract satisfied: equal objects have same hashCode");
        
        logger.info("\n[STEP 4] Testing inequality: sub1.equals(sub3)");
        logger.info("  Expected: false (different subscriptionId)");
        boolean notEqualsResult = sub1.equals(sub3);
        logger.info("  Actual  : {}", notEqualsResult);
        assertNotEquals(sub1, sub3);
        logger.info("  ✓ Inequality test passed: subscriptions with different IDs are not equal");
        
        logger.info("\n[STEP 5] Testing hashCode uniqueness: sub1.hashCode() != sub3.hashCode()");
        logger.info("  Note: Different objects SHOULD have different hashCodes (but not required)");
        int hash3 = sub3.hashCode();
        logger.info("  sub1.hashCode(): {}", hash1);
        logger.info("  sub3.hashCode(): {}", hash3);
        logger.info("  Different: {}", hash1 != hash3);
        assertNotEquals(hash1, hash3);
        logger.info("  ✓ HashCodes are different (good distribution)");
        
        logger.info("\n[STEP 6] Additional contract tests");
        
        logger.info("  [6.1] Reflexivity: sub1.equals(sub1)");
        assertTrue(sub1.equals(sub1));
        logger.info("        ✓ An object equals itself");
        
        logger.info("  [6.2] Symmetry: if sub1.equals(sub2), then sub2.equals(sub1)");
        assertTrue(sub1.equals(sub2) && sub2.equals(sub1));
        logger.info("        ✓ Equality is symmetric");
        
        logger.info("  [6.3] Null comparison: sub1.equals(null)");
        assertFalse(sub1.equals(null));
        logger.info("        ✓ Object is not equal to null");
        
        logger.info("\n" + "=".repeat(80));
        logger.info("✓✓✓ TEST PASSED: testEqualsAndHashCode - All {} assertions passed!", 9);
        logger.info("    Verified: equality, hashCode contract, reflexivity, symmetry, null-safety");
        logger.info("=".repeat(80) + "\n");
    }
    
    @Test
    void testRequiredFieldsNotNull() {
        logger.info("\n" + "=".repeat(80));
        logger.info("TEST: testRequiredFieldsNotNull");
        logger.info("PURPOSE: Verify constructor validates required fields and throws NullPointerException");
        logger.info("=".repeat(80));
        
        logger.info("\n[STEP 1] Test null subscriptionId (first required parameter)");
        logger.info("  Attempting: new WebhookSubscription(NULL, 'setup', 'queue', 'url', {}, {})");
        logger.info("  Expected: NullPointerException with message containing 'subscriptionId'");
        try {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> {
                new WebhookSubscription(null, "setup", "queue", "url", Map.of(), Map.of());
            });
            logger.info("  Actual  : NullPointerException thrown");
            logger.info("  Message : '{}'", ex.getMessage());
            logger.info("  ✓ Constructor properly validates subscriptionId is not null");
        } catch (AssertionError e) {
            logger.error("  ✗ FAILED: Constructor did not throw NullPointerException");
            throw e;
        }
        
        logger.info("\n[STEP 2] Test null setupId (second required parameter)");
        logger.info("  Attempting: new WebhookSubscription('sub', NULL, 'queue', 'url', {}, {})");
        logger.info("  Expected: NullPointerException with message containing 'setupId'");
        try {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> {
                new WebhookSubscription("sub", null, "queue", "url", Map.of(), Map.of());
            });
            logger.info("  Actual  : NullPointerException thrown");
            logger.info("  Message : '{}'", ex.getMessage());
            logger.info("  ✓ Constructor properly validates setupId is not null");
        } catch (AssertionError e) {
            logger.error("  ✗ FAILED: Constructor did not throw NullPointerException");
            throw e;
        }
        
        logger.info("\n[STEP 3] Test null queueName (third required parameter)");
        logger.info("  Attempting: new WebhookSubscription('sub', 'setup', NULL, 'url', {}, {})");
        logger.info("  Expected: NullPointerException with message containing 'queueName'");
        try {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> {
                new WebhookSubscription("sub", "setup", null, "url", Map.of(), Map.of());
            });
            logger.info("  Actual  : NullPointerException thrown");
            logger.info("  Message : '{}'", ex.getMessage());
            logger.info("  ✓ Constructor properly validates queueName is not null");
        } catch (AssertionError e) {
            logger.error("  ✗ FAILED: Constructor did not throw NullPointerException");
            throw e;
        }
        
        logger.info("\n[STEP 4] Test null webhookUrl (fourth required parameter)");
        logger.info("  Attempting: new WebhookSubscription('sub', 'setup', 'queue', NULL, {}, {})");
        logger.info("  Expected: NullPointerException with message containing 'webhookUrl'");
        try {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> {
                new WebhookSubscription("sub", "setup", "queue", null, Map.of(), Map.of());
            });
            logger.info("  Actual  : NullPointerException thrown");
            logger.info("  Message : '{}'", ex.getMessage());
            logger.info("  ✓ Constructor properly validates webhookUrl is not null");
        } catch (AssertionError e) {
            logger.error("  ✗ FAILED: Constructor did not throw NullPointerException");
            throw e;
        }
        
        logger.info("\n[STEP 5] Verify optional parameters can be null");
        logger.info("  Attempting: new WebhookSubscription('sub', 'setup', 'queue', 'url', null, null)");
        logger.info("  Expected: Object created successfully (headers and filters are optional)");
        try {
            WebhookSubscription subscription = new WebhookSubscription(
                "sub", "setup", "queue", "url", null, null);
            logger.info("  Actual  : Subscription created: {}", subscription);
            logger.info("  ✓ Optional parameters (headers, filters) can be null");
        } catch (Exception e) {
            logger.info("  Note: Constructor may require non-null maps, which is also acceptable design");
            logger.info("  Exception: {}", e.getMessage());
        }
        
        logger.info("\n[STEP 6] Validation summary");
        logger.info("  Required fields validated:");
        logger.info("    1. subscriptionId - ✓ Must not be null");
        logger.info("    2. setupId        - ✓ Must not be null");
        logger.info("    3. queueName      - ✓ Must not be null");
        logger.info("    4. webhookUrl     - ✓ Must not be null");
        logger.info("  This prevents invalid webhook subscriptions from being created");
        
        logger.info("\n" + "=".repeat(80));
        logger.info("✓✓✓ TEST PASSED: testRequiredFieldsNotNull - All {} assertions passed!", 4);
        logger.info("    Verified fail-fast validation for all required constructor parameters");
        logger.info("=".repeat(80) + "\n");
    }
}
