package dev.mars.peegeeq.test.logging;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Core contracts for D11 ERROR expectation matching and ownership. */
@Tag(TestCategories.CORE)
class ErrorLogLedgerTest {

    @Test
    void exactMessageWithoutThrowableConsumesOneExpectation() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openScope("owner", List.of(exactWithoutThrowable("subject", "expected")));

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("subject", "expected"), "owner");

        assertTrue(ledger.closeScope("owner").isSuccessful());
    }

    @Test
    void prefixAndCauseTypeMatchNestedThrowable() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openScope("owner", List.of(prefixWithThrowable(
                "subject", "operation failed for ", IOException.class)));
        RuntimeException failure = new RuntimeException("outer", new IOException("disk"));

        ledger.capture(ErrorLogLedger.ErrorEvent.withThrowable(
                "subject", "operation failed for item-7", failure), "owner");

        assertTrue(ledger.closeScope("owner").isSuccessful());
    }

    @Test
    void missingExpectedErrorFailsScope() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openScope("owner", List.of(exactWithoutThrowable("subject", "expected")));

        ErrorLogLedger.ScopeResult result = ledger.closeScope("owner");

        assertFalse(result.isSuccessful());
        assertTrue(result.failureMessage().contains("observed=0"));
    }

    @Test
    void extraExpectedErrorFailsFiniteMaximum() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openScope("owner", List.of(exactWithoutThrowable("subject", "expected")));
        ErrorLogLedger.ErrorEvent event =
                ErrorLogLedger.ErrorEvent.withoutThrowable("subject", "expected");

        ledger.capture(event, "owner");
        ledger.capture(event, "owner");

        ErrorLogLedger.ScopeResult result = ledger.closeScope("owner");
        assertFalse(result.isSuccessful());
        assertTrue(result.failureMessage().contains("observed=2"));
    }

    @Test
    void unexpectedErrorUsesCurrentOwnerHint() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openScope("owner-a", List.of());
        ledger.openScope("owner-b", List.of());

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("subject", "unexpected"),
                "owner-b");

        assertTrue(ledger.closeScope("owner-a").isSuccessful());
        ErrorLogLedger.ScopeResult ownerB = ledger.closeScope("owner-b");
        assertFalse(ownerB.isSuccessful());
        assertTrue(ownerB.failureMessage().contains("Unexpected ERROR"));
    }

    @Test
    void uniqueExpectationRoutesAcrossConcurrentActiveScopes() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openScope("owner-a", List.of(exactWithoutThrowable("subject.a", "error-a")));
        ledger.openScope("owner-b", List.of(exactWithoutThrowable("subject.b", "error-b")));

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("subject.b", "error-b"), null);
        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("subject.a", "error-a"), null);

        assertTrue(ledger.closeScope("owner-a").isSuccessful());
        assertTrue(ledger.closeScope("owner-b").isSuccessful());
    }

    @Test
    void eventMatchingMultipleActiveExpectationsFailsEveryOwner() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ErrorLogExpectation expectation = exactWithoutThrowable("subject", "same");
        ledger.openScope("owner-a", List.of(expectation));
        ledger.openScope("owner-b", List.of(expectation));

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("subject", "same"), null);

        ErrorLogLedger.ScopeResult ownerA = ledger.closeScope("owner-a");
        ErrorLogLedger.ScopeResult ownerB = ledger.closeScope("owner-b");
        assertTrue(ownerA.failureMessage().contains("Ambiguous expected ERROR"));
        assertTrue(ownerB.failureMessage().contains("Ambiguous expected ERROR"));
    }

    @Test
    void unownedLifecycleErrorFailsRun() {
        ErrorLogLedger ledger = new ErrorLogLedger();

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("lifecycle", "late"), null);

        List<String> violations = ledger.finishRun();
        assertEquals(1, violations.size());
        assertTrue(violations.getFirst().contains("Unowned Unexpected ERROR"));
    }

    @Test
    void lifecycleErrorIsOwnedByTheOnlyActiveContainer() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openContainer("container");

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("lifecycle", "before-all"),
                null);

        ErrorLogLedger.ScopeResult result = ledger.closeContainer("container");
        assertFalse(result.isSuccessful());
        assertTrue(result.failureMessage().contains("Unexpected ERROR"));
    }

    @Test
    void ambiguousLifecycleErrorFailsEveryActiveContainerInStableOwnerOrder() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.openContainer("container-b");
        ledger.openContainer("container-a");

        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("lifecycle", "parallel"),
                null);

        ErrorLogLedger.ScopeResult containerA = ledger.closeContainer("container-a");
        ErrorLogLedger.ScopeResult containerB = ledger.closeContainer("container-b");
        assertTrue(containerA.failureMessage().contains(
                "active owners=[container-a, container-b]"));
        assertTrue(containerB.failureMessage().contains(
                "active owners=[container-a, container-b]"));
    }

    @Test
    void invalidThrowablePolicyIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ErrorLogExpectation(
                        "subject",
                        ExpectedErrorLog.MessageMatch.EXACT,
                        "message",
                        ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
                        ExpectedErrorLog.NoThrowable.class,
                        1,
                        1));

        assertEquals("CAUSE_CHAIN_CONTAINS requires an explicit Throwable type",
                failure.getMessage());
    }

    @Test
    void wildcardLoggerIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ErrorLogExpectation(
                        "dev.mars.*",
                        ExpectedErrorLog.MessageMatch.EXACT,
                        "message",
                        ExpectedErrorLog.ThrowablePolicy.NONE,
                        ExpectedErrorLog.NoThrowable.class,
                        1,
                        1));

        assertEquals("loggerName must be exact and contain no wildcards", failure.getMessage());
    }

    @Test
    void rootLoggerExpectationIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> exactWithoutThrowable("ROOT", "message"));

        assertEquals("loggerName must not be the root logger", failure.getMessage());
    }

    @Test
    void finishRunResetsUnownedViolationsAndActiveScopes() {
        ErrorLogLedger ledger = new ErrorLogLedger();
        ledger.capture(ErrorLogLedger.ErrorEvent.withoutThrowable("lifecycle", "late"), null);
        ledger.openScope("abandoned", List.of());

        assertEquals(2, ledger.finishRun().size());
        assertTrue(ledger.finishRun().isEmpty());

        ledger.openScope("next", List.of());
        assertTrue(ledger.closeScope("next").isSuccessful());
    }

    private static ErrorLogExpectation exactWithoutThrowable(String logger, String message) {
        return new ErrorLogExpectation(
                logger,
                ExpectedErrorLog.MessageMatch.EXACT,
                message,
                ExpectedErrorLog.ThrowablePolicy.NONE,
                ExpectedErrorLog.NoThrowable.class,
                1,
                1);
    }

    private static ErrorLogExpectation prefixWithThrowable(
            String logger,
            String message,
            Class<? extends Throwable> throwableType) {
        return new ErrorLogExpectation(
                logger,
                ExpectedErrorLog.MessageMatch.PREFIX,
                message,
                ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
                throwableType,
                1,
                1);
    }
}
